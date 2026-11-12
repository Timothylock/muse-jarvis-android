# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Headless pairing loopback for `adb ... cmd pair_selftest` (JARVIS, not part of the SDK).

The phone-side simulator (Mobile, hello, client_finished_record, the community_app_v5 vector) is ADAPTED from the
SDK tests (muse-gadget-sdk linux/tests/test_pairing.py and vectors/link_pairing_v5.json @55cbf48). All data here is
public test data (fixed throwaway keys): nothing real is read, saved or sent. The device side is a real
SetupController built by jarvis_bridge._make_controller; the loopback feeds phone messages back through the SAME
Kotlin->Python write entry point the GATT server uses (`inject` -> Kotlin listener -> jarvis_bridge.pair_write).
"""
import base64
import json
import threading
import time

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from musegadget.ble_framing import ChunkAssembler, encode_chunks
from musegadget.ble_setup import ProvisionFailed
from musegadget.identity import Identity

APP = {
    "device_id": "hatch-link:02:00:00:00:00:01",
    "node_id": "homelink-000001",
    "mac": "02:00:00:00:00:01",
    "firmware_version": "1.0.0",
    "device_private_scalar_hex": "0000000000000000000000000000000000000000000000000000000000000002",
    "mobile_pub": "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU",
    "mobile_nonce": "AAECAwQFBgcICQoLDA0ODw",
    "device_nonce": "EBESExQVFhcYGRobHB0eHw",
    "mobile_tx_key_hex": "ee25e7f1eb3c05cc8465634e5f634deed858862b1c4c35fa434dc926f7facaeb",
    "mobile_rx_key_hex": "9abb2954b05e2f5f7112e9bfd41fd970602e27ed07453c1cff51f575de5d0b4e",
    "session_id": "z0eNLGw5mczvD4a1F2ubSQ",
    "client_finished_ciphertext": "uwNX3KEkix5T6nWce3s2SKPPh-5E31x-UIHFSK6MpMUg562B",
    "client_finished_tag": "zT4kU6XK-rM7rvt3VEITLg",
}

PROVISION = {
    "action": "provision_v2",
    "ssid": "HomeNet",
    "password": "",
    "access_token": "device-access",
    "refresh_token": "device-refresh",
    "token_type": "device",
    "username": "someone",
    "api_url": "https://legacy-api.example",
    "api_url_v2": "https://api.example",
}

EXPECTED = ["pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"]


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class Mobile:
    """Mobile side of the record layer (test_pairing.py Mobile)."""

    def __init__(self, vector):
        self.tx = AESGCM(bytes.fromhex(vector["mobile_tx_key_hex"]))
        self.rx = AESGCM(bytes.fromhex(vector["mobile_rx_key_hex"]))
        self.session_id = vector["session_id"]
        self.tx_counter = 0

    def _nonce(self, direction, counter):
        return bytes([direction]) + b"\0\0\0" + counter.to_bytes(8, "big")

    def _aad(self, arrow, counter):
        return ("hatch-link ble setup v1|%s|%s|%d" % (self.session_id, arrow, counter)).encode()

    def seal(self, command):
        counter = self.tx_counter
        self.tx_counter += 1
        sealed = self.tx.encrypt(self._nonce(0, counter), json.dumps(command).encode(), self._aad("m2d", counter))
        return {
            "action": "pairing_encrypted",
            "session_id": self.session_id,
            "counter": str(counter),
            "ciphertext": b64(sealed[:-16]),
            "tag": b64(sealed[-16:]),
        }

    def open(self, envelope):
        assert envelope["session_id"] == self.session_id
        counter = int(envelope["counter"])
        plain = self.rx.decrypt(
            self._nonce(1, counter), unb64(envelope["ciphertext"]) + unb64(envelope["tag"]), self._aad("d2m", counter))
        return json.loads(plain)


def hello(vector=APP):
    return {
        "action": "pairing_client_hello",
        "version": 5,
        "pairing_auth": "none",
        "pairing_policy": "confirm_app",
        "mobile_pub": vector["mobile_pub"],
        "mobile_nonce": vector["mobile_nonce"],
    }


def client_finished_record(vector=APP):
    return {
        "action": "pairing_encrypted",
        "session_id": vector["session_id"],
        "counter": "0",
        "ciphertext": vector["client_finished_ciphertext"],
        "tag": vector["client_finished_tag"],
    }


def make_device(session_cls):
    scalar = int(APP["device_private_scalar_hex"], 16)
    return session_cls(
        node_id=APP["node_id"], device_id=APP["device_id"], mac=APP["mac"], firmware_version=APP["firmware_version"],
        generate_key=lambda: ec.derive_private_key(scalar, ec.SECP256R1()),
        random_bytes=lambda n: unb64(APP["device_nonce"]),
    )


class Loopback:
    """ble_setup.Transport: device notifications are reassembled for the phone; phone writes go through `inject`."""

    def __init__(self, mtu, inject):
        self._mtu = mtu
        self._inject = inject
        self._assembler = ChunkAssembler()
        self._lock = threading.Lock()
        self.messages = []
        self.disconnects = []
        self.max_packet = 0

    def mtu(self):
        return self._mtu

    def send_packets(self, packets):
        with self._lock:
            for packet in packets:
                self.max_packet = max(self.max_packet, len(packet))
                if len(packet) > max(self._mtu - 3, 20):
                    raise AssertionError("packet exceeds MTU")
                message = self._assembler.feed(packet)
                if message is None:
                    continue
                self.messages.append(json.loads(message) if packet[0] == 0xFE else message.decode())

    def disconnect(self, delay):
        self.disconnects.append(delay)

    def phone_send(self, obj):
        # Writes are limited to MTU-3 bytes exactly like a real central; each one is a separate GATT write.
        for packet in encode_chunks(json.dumps(obj, separators=(",", ":")).encode(), self._mtu):
            self._inject(packet)

    def snapshot(self):
        with self._lock:
            return list(self.messages)


class LoopbackNetwork:
    def is_online(self):
        return True

    def current_connection_entry(self):
        return {"ssid": "HomeNet", "rssi": -40, "secure": False}


def _wait(predicate, what, timeout=5.0):
    end = time.monotonic() + timeout
    while True:
        value = predicate()
        if value:
            return value
        if time.monotonic() > end:
            raise AssertionError("timeout waiting for " + what)
        time.sleep(0.01)


def run(mtu, make_controller, session_cls, install, inject):
    """Drive the full setup protocol once. Returns {"ok", "statuses", ...}; never saves or calls the network."""
    transport = Loopback(mtu, inject)
    done = threading.Event()
    secure = []
    session = make_device(session_cls)
    session.on_secure = lambda: secure.append(True)
    mobile = Mobile(APP)
    result = {"mtu": mtu, "ok": False, "statuses": []}

    def provision(credentials, commit):
        # Stub: verify nothing, save nothing (no pairing.json), no network call.
        if not commit(lambda: True):
            raise ProvisionFailed("error_storage")

    controller = make_controller(session, Identity(APP["mac"]), transport, LoopbackNetwork(), provision, done.set)
    install(controller)
    controller.start()

    def opened():
        return [mobile.open(m) for m in transport.snapshot()
                if isinstance(m, dict) and m.get("type") == "pairing_encrypted"]

    def statuses():
        return [m["status"] for m in opened() if m.get("type") == "status"]

    try:
        transport.phone_send(hello())
        _wait(lambda: any(isinstance(m, dict) and m.get("type") == "pairing_ready" for m in transport.snapshot()),
              "pairing_ready")
        transport.phone_send(client_finished_record())
        mobile.tx_counter = 1
        _wait(lambda: "pairing_confirmed" in statuses(), "pairing_confirmed")
        transport.phone_send(mobile.seal({"action": "wifi_scan"}))
        _wait(lambda: any(m.get("type") == "wifi_scan_result" for m in opened()), "wifi_scan_result")
        transport.phone_send(mobile.seal(PROVISION))
        _wait(done.is_set, "setup complete")
        _wait(lambda: "auth_ok" in statuses(), "auth_ok")
        result["statuses"] = statuses()
        result["secure_hook"] = bool(secure)
        result["max_packet"] = transport.max_packet
        result["ok"] = result["statuses"] == EXPECTED and bool(secure)
    except Exception as exc:
        result["error"] = "%s: %s" % (type(exc).__name__, exc)
        try:
            result["statuses"] = statuses()
        except Exception:
            pass
    finally:
        controller.stop()
    return result
