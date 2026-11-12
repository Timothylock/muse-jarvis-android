# -*- coding: utf-8 -*-
"""JARVIS bridge between the Kotlin SdkTransport and the vendored, unmodified upstream `musegadget` SDK.

Everything here is OUR code; the SDK is never edited. Behavior is changed only by
  * monkeypatching musegadget.config.load_json/save_json/delete_json (storage -> Kotlin CredentialStore),
  * subclassing musegadget.service.Service (describe / run_command / serve / _sleep),
  * wrapping VoiceCall._reply_gate in a forwarding proxy (event tap).

Threading (DESIGN 6.5/6.8): ONE thread "python-loop" runs asyncio. Kotlin -> Python goes through the module-level
functions below, which only use loop.call_soon_threadsafe / run_coroutine_threadsafe (or tiny thread-safe dict ops).
Python -> Kotlin callbacks go to the `HOST` object (SdkHost.kt); they are short and never block the loop.
Never log tokens, server text, transcripts or command params.
"""
import asyncio
import json
import logging
import os
import ssl
import threading
import time
import uuid
import urllib.error
import urllib.request

from musegadget import config

HOST = None
SDK_COMMIT = "55cbf48"
APP_VERSION = "1.0.0"
SELFTEST_NAME = "selftest.json"

log = logging.getLogger("jarvis.bridge")

# --------------------------------------------------------------------------------------------------------------
# Storage hook: calls with directory=None go to the Kotlin CredentialStore (names: identity.json, pairing.json,
# sdk_token). Explicit directories fall through to the originals. Store errors propagate (never turned into
# "missing", which would make identity.load_or_create() mint a new identity over an unreadable one).
# --------------------------------------------------------------------------------------------------------------
_orig_load, _orig_save, _orig_delete = config.load_json, config.save_json, config.delete_json
_on_sdk_deleted_pairing = None


def _load_json(name, directory=None):
    if directory is not None:
        return _orig_load(name, directory)
    text = HOST.credRead(name)
    if text is None:
        return None
    try:
        data = json.loads(str(text))
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def _save_json(name, data, directory=None):
    if directory is not None:
        return _orig_save(name, data, directory)
    HOST.credWrite(name, json.dumps(data, indent=2))


def _delete_json(name, directory=None):
    if directory is not None:
        return _orig_delete(name, directory)
    HOST.credDelete(name)
    if name == config.PAIRING_FILE and _on_sdk_deleted_pairing is not None:
        _on_sdk_deleted_pairing()


config.load_json, config.save_json, config.delete_json = _load_json, _save_json, _delete_json

# --------------------------------------------------------------------------------------------------------------
# The five display commands, VERBATIM from DESIGN.md 5.2 (spec shape = executor.COMMAND_SPECS entries).
# --------------------------------------------------------------------------------------------------------------
_ID = {"type": "string", "description": "Stable id to update/clear later"}
COMMANDS = {
    "display.card": {
        "description": "Show a fact card on the user's desk display beside the orb. Use for key facts, answers, summaries during conversation. Same id updates in place.",
        "required": {"title": {"type": "string", "description": "≤60 chars"}},
        "optional": {
            "body": {"type": "string", "description": "≤600 chars plain text"},
            "bullets": {"type": "array", "description": "≤8 strings, ≤120 chars each"},
            "id": _ID,
            "sticky": {"type": "boolean", "description": "Keep until cleared (default false)"},
        },
        "timeout_ms": 3000,
    },
    "display.stats": {
        "description": "Show labeled numbers (weather, scores, prices) as a stat block on the desk display. metrics: [{label, value, unit?}].",
        "required": {
            "title": {"type": "string", "description": "≤40 chars"},
            "metrics": {"type": "array", "description": "1-6 of {label ≤16, value ≤10 chars, unit? ≤6}"},
        },
        "optional": {
            "caption": {"type": "string", "description": "≤80 chars, e.g. source or time"},
            "id": _ID,
            "sticky": {"type": "boolean", "description": "Keep until cleared"},
        },
        "timeout_ms": 3000,
    },
    "display.list": {
        "description": "Show a list/outline on the desk display, e.g. a plan while planning. items: strings or {text, level 0-2, done?}. Reuse id to update it as the plan evolves.",
        "required": {
            "title": {"type": "string", "description": "≤60 chars"},
            "items": {"type": "array", "description": "1-30 items, ≤100 chars each"},
        },
        "optional": {
            "ordered": {"type": "boolean", "description": "Number the items"},
            "id": _ID,
            "sticky": {"type": "boolean", "description": "Keep until cleared"},
        },
        "timeout_ms": 3000,
    },
    "display.timer": {
        "description": "Show a countdown timer on the desk display; it chimes locally at zero.",
        "required": {
            "label": {"type": "string", "description": "≤40 chars"},
            "seconds": {"type": "integer", "description": "1-86400"},
        },
        "optional": {
            "id": {"type": "string", "description": "Stable id; reuse to change or cancel via display.clear"},
        },
        "timeout_ms": 3000,
    },
    "display.clear": {
        "description": "Remove a panel by id, or all panels if id omitted.",
        "optional": {"id": {"type": "string", "description": "Panel id to remove"}},
        "timeout_ms": 3000,
    },
}


def describe_chars():
    total = 0
    for spec in COMMANDS.values():
        total += len(spec["description"])
        for group in ("required", "optional"):
            for param in spec.get(group, {}).values():
                total += len(param["description"])
    return total


# --------------------------------------------------------------------------------------------------------------
# Logging -> Kotlin (which runs Redactor before Log)
# --------------------------------------------------------------------------------------------------------------
class _HostHandler(logging.Handler):
    def emit(self, record):
        try:
            host = HOST
            if host is not None:
                host.log(self.format(record))
        except Exception:
            pass


_logging_ready = False


def _setup_logging():
    global _logging_ready
    if _logging_ready:
        return
    _logging_ready = True
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    handler = _HostHandler()
    handler.setFormatter(logging.Formatter("%(levelname).1s %(name)s: %(message)s"))
    root.addHandler(handler)
    root.setLevel(logging.INFO)
    # websockets debug logs include the Authorization header: never below WARNING.
    logging.getLogger("websockets").setLevel(logging.WARNING)


_DIAG_CACHE = {}


def _diag(key, value):
    # Cached so a later SdkTransport instance (same process, Python already started) still sees the
    # one-time self-test results; start() replays the cache to the new host.
    _DIAG_CACHE[key] = value
    try:
        HOST.onDiag(key, "" if value is None else str(value))
    except Exception:
        pass


# --------------------------------------------------------------------------------------------------------------
# TLS roots + HTTPS probe
# --------------------------------------------------------------------------------------------------------------
def _tls_setup():
    from musegadget import tls
    try:
        n = len(tls.system_roots())
        if n == 0:
            os.environ["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            n = len(tls.system_roots())
    except Exception as exc:
        log.warning("TLS root count failed: %s", type(exc).__name__)
        n = 0
    _diag("tlsRoots", n)


def _https_probe():
    try:
        from musegadget import muse_api, tls
        req = urllib.request.Request(muse_api.api_root(""), method="GET")
        try:
            with urllib.request.urlopen(req, timeout=10, context=tls.client_context()) as resp:
                result = "ok %d" % resp.getcode()
        except urllib.error.HTTPError as exc:
            result = "ok %d" % exc.code  # any HTTP answer means TLS worked
        except urllib.error.URLError as exc:
            reason = exc.reason
            result = "fail " + type(reason if isinstance(reason, Exception) else exc).__name__
    except Exception as exc:
        result = "fail " + type(exc).__name__
    _diag("httpsProbe", result)


def _storage_selftest():
    ok = False
    try:
        config.save_json(SELFTEST_NAME, {"probe": 1})
        got = config.load_json(SELFTEST_NAME)
        ok = got == {"probe": 1}
    except Exception as exc:
        log.warning("storage self-test failed: %s", type(exc).__name__)
    finally:
        try:
            config.delete_json(SELFTEST_NAME)
            ok = ok and config.load_json(SELFTEST_NAME) is None
        except Exception:
            ok = False
    _diag("storageRoundTrip", "true" if ok else "false")


_FAST_BYTES = True


def _tobytes(arr):
    """Java byte[] (signed) -> Python bytes."""
    if _FAST_BYTES:
        return bytes(arr)
    return bytes(bytearray(x & 0xFF for x in arr))


def _jbytes(data):
    """Python bytes -> Java byte[]."""
    from java import jarray, jbyte
    if _FAST_BYTES:
        return jarray(jbyte)(data)
    return jarray(jbyte)([x - 256 if x > 127 else x for x in data])


def _bytes_selftest():
    """Chaquopy byte[] <-> bytes conversions the PCM paths rely on; falls back to slow element-wise paths."""
    global _FAST_BYTES
    sample = b"\xff\x00\x7f\x80"
    try:
        _FAST_BYTES = bytes(HOST.sampleBytes()) == sample and bool(HOST.checkBytes(_jbytes(sample)))
    except Exception:
        _FAST_BYTES = False
    if not _FAST_BYTES:
        try:
            ok = _tobytes(HOST.sampleBytes()) == sample and bool(HOST.checkBytes(_jbytes(sample)))
        except Exception as exc:
            ok = False
            log.warning("bytes bridge failed: %s", type(exc).__name__)
        log.info("bytes bridge: slow path %s", "ok" if ok else "BROKEN")
    else:
        log.info("bytes bridge: fast path ok")


# --------------------------------------------------------------------------------------------------------------
# Command forwarding (SDK worker threads <-> Kotlin)
# --------------------------------------------------------------------------------------------------------------
_pending = {}
_pending_lock = threading.Lock()
COMMAND_REPLY_TIMEOUT_S = 2.5


def reply_command(invocation_id, ok, payload_json, error):
    """Kotlin -> Python (any thread): complete a pending run_command()."""
    with _pending_lock:
        entry = _pending.get(invocation_id)
    if entry is None:
        return
    event, slot = entry
    if ok:
        try:
            payload = json.loads(payload_json) if payload_json else {}
        except ValueError:
            payload = {}
        slot["r"] = {"ok": True, "payload": payload if isinstance(payload, dict) else {}}
    else:
        slot["r"] = {"ok": False, "error": str(error or "command failed")}
    event.set()


class _NoExecutor:
    """Service needs an executor field; we never use it (describe/run_command are overridden, no local socket)."""
    account = None


from musegadget import service as _service  # noqa: E402  (after the storage patch)
from musegadget import identity as _identity  # noqa: E402
from musegadget.link_client import DeviceDescription, LinkSession, Outcome  # noqa: E402


class BridgeService(_service.Service):
    def __init__(self, bridge, sdk_token):
        super().__init__(identity=_identity.load_or_create(), executor=_NoExecutor(),
                         sdk_token=sdk_token, display_name="Muse Desk display")
        self.bridge = bridge
        self.vm = None
        self.noise_host = _service.DEFAULT_NOISE_HOST
        self.why = None

    def describe(self):
        return DeviceDescription(
            node_id=self.identity.node_id,
            display_name="Muse Desk display",
            version=APP_VERSION + ";dp=1",
            commands=COMMANDS,
        )

    def run_command(self, name, params, timeout_ms=None):
        # SDK worker thread (python-workers).
        if name not in COMMANDS:
            return {"ok": False, "error": "unknown command"}
        inv = uuid.uuid4().hex
        event, slot = threading.Event(), {}
        with _pending_lock:
            _pending[inv] = (event, slot)
        try:
            HOST.onCommand(inv, name, json.dumps(params))
            if not event.wait(COMMAND_REPLY_TIMEOUT_S):
                return {"ok": False, "error": "display busy"}
            return slot["r"]
        finally:
            with _pending_lock:
                _pending.pop(inv, None)

    async def serve(self, session, vm, stop):
        self.vm = vm
        pairing = config.load_json(config.PAIRING_FILE) or {}
        self.noise_host = pairing.get("noise_host") or _service.DEFAULT_NOISE_HOST
        self.bridge.link("connecting")
        watcher = asyncio.ensure_future(self._watch_registered(session, vm))
        try:
            outcome = await session.run(stop)
        finally:
            watcher.cancel()
            self.vm = None
        self.why = outcome.value
        _diag("lastOutcome", outcome.value)
        if outcome is not Outcome.UNPAIRED:  # UNPAIRED: Service.run deletes pairing.json -> PairingLost below
            self.bridge.link("offline", outcome.value)
        return outcome

    async def _watch_registered(self, session, vm):
        try:
            await session.wait_registered(60)
        except asyncio.CancelledError:
            raise
        except Exception:
            return
        self.bridge.link("online", self.identity.node_id, vm.get("vm_name") or vm.get("vm_id") or "")

    async def _sleep(self, seconds):
        # Called for the unpaired poll, token retry and reconnect backoff. Report state, and let kick() cut it short.
        b = self.bridge
        pairing = config.load_json(config.PAIRING_FILE)
        if not pairing or config.pairing_problem(pairing) is not None:
            if not b.pairing_lost:
                b.link("unpaired")
                _diag("lastOutcome", "unpaired_poll")
        else:
            b.link("offline", self.why or "retry")
        try:
            await asyncio.wait_for(self._stop.wait(), seconds)
        except asyncio.TimeoutError:
            pass
        if not self._stop.is_set() and config.load_json(config.PAIRING_FILE):
            b.link("connecting")


class Bridge:
    def __init__(self):
        self.loop = None
        self.thread = None
        self.ready = threading.Event()
        self.stopping = False
        self.restart = False
        self.run_task = None
        self.svc = None
        self.pairing_lost = False
        self.restarts = 0
        self.calls = {}
        self._last_link = None

    # ---- Kotlin-facing (any thread) ------------------------------------------------------------------------
    def start(self):
        if self.thread is not None and self.thread.is_alive():
            return
        self.ready.clear()
        self.stopping = False
        self.thread = threading.Thread(target=self._thread_main, name="python-loop", daemon=True)
        self.thread.start()
        self.ready.wait(5)

    def stop(self):
        loop = self.loop
        if loop is not None and self.thread is not None and self.thread.is_alive():
            loop.call_soon_threadsafe(self._begin_stop)
            self.thread.join(12)

    def kick(self):
        loop = self.loop
        if loop is not None and self.thread is not None and self.thread.is_alive():
            loop.call_soon_threadsafe(self._do_kick)

    # ---- loop thread ---------------------------------------------------------------------------------------
    def _thread_main(self):
        try:
            asyncio.run(self._main())
        except BaseException as exc:  # noqa: BLE001 - never let the thread die silently
            log.error("python-loop crashed: %s", type(exc).__name__)
        finally:
            self.loop = None
            self.ready.set()

    def link(self, kind, a=None, b=None):
        key = (kind, a, b)
        if key == self._last_link:
            return
        self._last_link = key
        try:
            HOST.onLink(kind, a, b)
        except Exception:
            pass

    def _sdk_token(self):
        text = HOST.credRead(config.SDK_TOKEN_FILE)
        os.environ.pop(config.SDK_TOKEN_ENV, None)
        if text:
            os.environ[config.SDK_TOKEN_ENV] = str(text).strip()
        try:
            token = config.sdk_token()
        except ValueError:
            os.environ.pop(config.SDK_TOKEN_ENV, None)
            return None
        _diag("tokenHint", config.sdk_token_hint(token) if token else "")
        return token

    def _pairing_deleted_by_sdk(self):
        self.pairing_lost = True
        self.link("pairing_lost", "revoked")
        _diag("lastOutcome", "pairing_lost")

    async def _main(self):
        global _on_sdk_deleted_pairing
        self.loop = asyncio.get_running_loop()
        self._stop_evt = asyncio.Event()
        _on_sdk_deleted_pairing = lambda: self.loop.call_soon_threadsafe(self._pairing_deleted_by_sdk) \
            if self.loop else None
        self.ready.set()
        delay, backoff = 2.0, 2.0
        while not self.stopping:
            started = time.monotonic()
            try:
                svc = BridgeService(self, self._sdk_token())
                self.svc = svc
                d = svc.describe()
                _diag("nodeId", d.node_id)
                _diag("commandCount", len(d.commands))
                _diag("describeChars", describe_chars())
                self.pairing_lost = False
                if config.load_json(config.PAIRING_FILE):
                    self.link("connecting")
                self.run_task = asyncio.ensure_future(svc.run())
                await self.run_task
                if self.stopping:
                    break
                outcome = "stopped_unexpectedly"
                raise RuntimeError(outcome)
            except asyncio.CancelledError:
                if self.restart:
                    self.restart = False
                    backoff = 2.0
                    continue
                if self.stopping:
                    break
                raise
            except Exception as exc:  # internal failure: contain, report, restart with backoff
                self.restarts += 1
                _diag("restarts", self.restarts)
                _diag("lastOutcome", "internal")
                log.error("bridge failure: %s", type(exc).__name__)
                try:
                    HOST.onDiagnostic("ERROR", "bridge_" + type(exc).__name__)
                except Exception:
                    pass
                self.link("offline", "internal")
                if time.monotonic() - started > 60:
                    backoff = 2.0
                try:
                    await asyncio.wait_for(self._stop_evt.wait(), backoff)
                except asyncio.TimeoutError:
                    pass
                backoff = min(backoff * 2, 60.0)
            finally:
                self.svc = None
        # leaving: end calls
        for runner in list(self.calls.values()):
            runner.cancel()
        if self.calls:
            await asyncio.wait([r.task for r in self.calls.values() if r.task], timeout=12)
        self.link("offline", "stopped")

    def _begin_stop(self):
        self.stopping = True
        self._stop_evt.set()
        if self.svc is not None:
            self.svc.stop()
        if self.run_task is not None and not self.run_task.done():
            self.run_task.cancel()

    def _do_kick(self):
        self.pairing_lost = False
        self.restart = True
        if self.run_task is not None and not self.run_task.done():
            self.run_task.cancel()
        else:
            self.restart = False  # supervisor is in its backoff sleep or between runs
            self._stop_evt.set(); self._stop_evt.clear()

    # ---- voice ---------------------------------------------------------------------------------------------
    def open_call(self, call_id, thread_id):
        fut = asyncio.run_coroutine_threadsafe(self._open_call(call_id, thread_id), self.loop) \
            if self.loop else None
        if fut is None:
            raise RuntimeError("not_started")
        fut.result(5)

    async def _open_call(self, call_id, thread_id):
        svc = self.svc
        session = svc._current if svc is not None else None
        if session is None or session.registered_at is None or svc.vm is None:
            raise RuntimeError("offline")
        for old in list(self.calls.values()):
            old.cancel()
        runner = CallRunner(self, call_id, thread_id or None, svc.vm, svc.noise_host, svc.identity.node_id)
        self.calls[call_id] = runner
        runner.task = asyncio.ensure_future(runner.main())

    def call(self, call_id):
        return self.calls.get(call_id)

    def dictated(self, req_id, wav, session_id):
        if self.loop is None:
            HOST.onFallback(req_id, None, "offline")
            return
        wav_bytes = _tobytes(wav)
        fut = asyncio.run_coroutine_threadsafe(self._dictated(wav_bytes, session_id), self.loop)

        def done(f):
            try:
                reply, error = f.result()
            except Exception:
                reply, error = None, "send_failed"
            try:
                HOST.onFallback(req_id, reply, error)
            except Exception:
                pass
        fut.add_done_callback(done)

    async def _dictated(self, wav, sid):
        svc = self.svc
        session = svc._current if svc is not None else None
        if session is None or session.registered_at is None:
            return None, "offline"
        try:
            result = await _service.send_and_wait(
                session,
                lambda: session.send_voice(wav, sid, output_modality="voice", device="mcu-wearable"),
                sid, 60)
        except Exception as exc:
            log.warning("dictated turn failed: %s", type(exc).__name__)
            return None, "send_failed"
        if not isinstance(result, dict) or not result.get("ok"):
            return None, "send_failed"
        reply = result.get("reply")
        if reply is None:
            return None, "no_reply"
        return str(reply), None


_B = Bridge()


# ---- module-level API for Kotlin ----------------------------------------------------------------------------
def start(host, state_dir, version_name):
    global HOST, APP_VERSION
    HOST = host
    APP_VERSION = version_name or APP_VERSION
    os.environ[config.STATE_DIR_ENV] = state_dir
    _setup_logging()
    if not getattr(start, "_done", False):
        start._done = True
        import musegadget
        _diag("sdkVersion", musegadget.__version__)
        _diag("sdkCommit", SDK_COMMIT)
        _storage_selftest()
        _tls_setup()
        _bytes_selftest()
        threading.Thread(target=_https_probe, name="https-probe", daemon=True).start()
    else:
        for key, value in list(_DIAG_CACHE.items()):
            _diag(key, value)
    _B.start()


def stop():
    _B.stop()


def kick():
    _B.kick()


def open_call(call_id, thread_id):
    _B.open_call(call_id, thread_id)


def call_push(call_id, pcm):
    r = _B.call(call_id)
    if r is not None:
        r.push(_tobytes(pcm))


def call_eof(call_id):
    r = _B.call(call_id)
    if r is not None:
        r.push_eof()


def call_barge_in(call_id):
    r = _B.call(call_id)
    if r is not None:
        r.barge_in()


def call_hang_up(call_id):
    r = _B.call(call_id)
    if r is not None:
        r.hang_up()


def dictated(req_id, wav, session_id):
    _B.dictated(req_id, wav, session_id)


# --------------------------------------------------------------------------------------------------------------
# M7 pairing: mirrors musegadget.pair.pair() (pair.py:97-157) with the Kotlin BlePeripheral in place of BleServer
# (which hard-imports D-Bus). SetupController, PairingSession and pair._verify_and_save are reused UNMODIFIED.
# Runs on a Kotlin worker thread (Dispatchers.IO) while the service loop keeps polling pairing.json.
# --------------------------------------------------------------------------------------------------------------
from musegadget.ble_setup import SetupController  # noqa: E402
from musegadget.pairing import PairingSession  # noqa: E402

_STAGES = ("advertising", "connected", "secure", "saved")  # PairingStage.ORDER (Pairing.kt)
_PAIR_LOCK = threading.Lock()   # one pairing run (or self-test) at a time
_PAIR_CANCEL = threading.Event()
_PAIR_WAKE = threading.Event()
_ACTIVE = None                  # the _PairRun that Kotlin's listener entry points feed


class _ObservedSession(PairingSession):
    """PairingSession plus one hook: `on_secure` fires when the SDK confirms the session.

    The statuses are encrypted, so the transport cannot see "pairing_confirmed"; the SDK's own confirmation event is
    handle_client_finished() returning a nonzero generation (ble_setup.py:224-231 sends pairing_confirmed right
    after it). Nothing else is overridden.
    """
    on_secure = None

    def handle_client_finished(self, command):
        generation = super().handle_client_finished(command)
        if generation and self.on_secure is not None:
            try:
                self.on_secure()
            except Exception:
                pass
        return generation


class _KotlinTransport:
    """ble_setup.Transport over the Kotlin BlePeripheral. send_packets blocks (paced by onNotificationSent)."""

    def __init__(self, peripheral):
        self._p = peripheral

    def send_packets(self, packets):
        from java import jclass
        out = jclass("java.util.ArrayList")()
        for packet in packets:
            out.add(_jbytes(packet))
        self._p.sendPackets(out)

    def mtu(self):
        return int(self._p.mtu())

    def disconnect(self, delay):
        self._p.disconnect(int(delay * 1000))


class _KotlinNetwork:
    """ble_setup.Network over Kotlin's NetworkStatus (network.py:128 entry shape)."""

    def __init__(self, status):
        self._s = status

    def is_online(self):
        try:
            return bool(self._s.isOnline())
        except Exception:
            return False

    def current_connection_entry(self):
        from musegadget import network as _network
        try:
            ssid = self._s.ssid()
        except Exception:
            ssid = None
        return {"ssid": str(ssid) if ssid else _network.CURRENT_CONNECTION_LABEL, "rssi": -40, "secure": False}


class _PairRun:
    def __init__(self, ui):
        self.ui = ui
        self.controller = None
        self.completed = threading.Event()

    def stage(self, name):
        try:
            self.ui.onProgress(name, float(_STAGES.index(name) + 1) / len(_STAGES))
        except Exception:
            pass


def _make_controller(pairing, ident, transport, network, provision, on_complete):
    """The one place a SetupController is built (real pairing and the headless self-test share it; pair.py:134)."""
    from musegadget import __version__
    return SetupController(
        pairing=pairing, identity=ident, version=__version__, transport=transport, network=network,
        provision=provision, on_complete=on_complete,
    )


def pair_write(packet):
    """Kotlin -> Python, GATT binder thread: RX write. Only feeds the assembler/queue (never blocks)."""
    run = _ACTIVE
    if run is not None and run.controller is not None:
        run.controller.on_write(_tobytes(packet))


def pair_connected():
    run = _ACTIVE
    if run is not None:
        run.stage("connected")


def pair_disconnected():
    run = _ACTIVE
    if run is not None and run.controller is not None:
        run.controller.on_disconnect()


def pair_cancel():
    _PAIR_CANCEL.set()
    _PAIR_WAKE.set()


def pair_clear_cancel():
    _PAIR_CANCEL.clear()


def pair_run(peripheral, network, ui, token, force, listener):
    """Open the setup window until the Muse app pairs or it times out. Returns a PairOutcome name."""
    global _ACTIVE
    if not _PAIR_LOCK.acquire(False):
        return "BLE_ERROR"
    try:
        return _pair_locked(peripheral, network, ui, token, force, listener)
    except Exception as exc:
        log.error("pairing failed: %s", type(exc).__name__)
        return "BLE_ERROR"
    finally:
        _ACTIVE = None
        _PAIR_CANCEL.clear()
        _PAIR_WAKE.clear()
        _PAIR_LOCK.release()


def _pair_locked(peripheral, network, ui, token, force, listener):
    global _ACTIVE
    from musegadget import __version__, pair as _pair
    if config.load_json(config.PAIRING_FILE) and not force:
        return "ALREADY_PAIRED"
    # config.sdk_token() reads the env var or a real file in the state dir; our token lives in the CredentialStore,
    # so (like Bridge._sdk_token) it goes through the environment. The explicit argument wins over the stored one.
    text = str(token or "").strip()
    if not text:
        stored = HOST.credRead(config.SDK_TOKEN_FILE)
        text = str(stored).strip() if stored else ""
    os.environ.pop(config.SDK_TOKEN_ENV, None)
    if text:
        os.environ[config.SDK_TOKEN_ENV] = text
    try:
        sdk_token = config.sdk_token()
    except ValueError as exc:
        os.environ.pop(config.SDK_TOKEN_ENV, None)
        try:
            ui.onTokenProblem(str(exc))
        except Exception:
            pass
        return "INVALID_SDK_TOKEN"
    ident = _identity.load_or_create()
    run = _PairRun(ui)
    session = _ObservedSession(node_id=ident.node_id, device_id=ident.device_id, mac=ident.mac,
                               firmware_version=__version__, sdk_token=sdk_token)
    session.on_secure = lambda: run.stage("secure")

    def finish():
        run.stage("saved")
        run.completed.set()
        _B.kick()  # pairing.json is saved: connect now instead of waiting out the unpaired poll
        _PAIR_WAKE.set()

    run.controller = _make_controller(
        session, ident, _KotlinTransport(peripheral), _KotlinNetwork(network),
        lambda credentials, commit: _pair._verify_and_save(credentials, commit, sdk_token), finish)
    _PAIR_CANCEL.clear()
    _PAIR_WAKE.clear()
    _ACTIVE = run
    run.controller.start()
    try:
        err = peripheral.open(ident.ble_name, listener)
        if err is not None:
            log.warning("BLE open failed: %s", err)
            return "BLE_ERROR"
        timeout = _pair.SETUP_WINDOW_S
        try:
            ui.onWindowOpen(ident.ble_name, timeout, bool(sdk_token))
        except Exception:
            pass
        run.stage("advertising")
        deadline = time.monotonic() + timeout
        while not (_PAIR_CANCEL.is_set() or run.completed.is_set()):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                ui.onWindowRemaining(int(remaining + 0.999))
            except Exception:
                pass
            _PAIR_WAKE.wait(min(1.0, remaining))
        if run.completed.is_set():
            # Let the final status notification drain before dropping the link (pair.py:130-132).
            _PAIR_WAKE.clear()
            if not _PAIR_CANCEL.is_set():
                _PAIR_WAKE.wait(_pair.BLE_SHUTDOWN_DELAY_S)
            peripheral.disconnect(0)
            time.sleep(0.2)
        return "PAIRED" if run.completed.is_set() else "WINDOW_CLOSED"
    finally:
        _ACTIVE = None
        run.controller.stop()
        try:
            peripheral.close()
        except Exception as exc:
            log.warning("BLE close failed: %s", type(exc).__name__)


def pair_selftest(mtu):
    """Headless loopback of the whole setup protocol, no radio / no network / no saved state. JSON string."""
    global _ACTIVE
    if not _PAIR_LOCK.acquire(False):
        return json.dumps({"ok": False, "error": "busy"})
    try:
        import jarvis_pair_selftest as st

        def install(run_controller):
            global _ACTIVE
            run = _PairRun(None)
            run.controller = run_controller
            _ACTIVE = run

        return json.dumps(st.run(
            int(mtu), _make_controller, _ObservedSession, install,
            lambda packet: HOST.pairSelftestWrite(_jbytes(packet))))
    except Exception as exc:
        return json.dumps({"ok": False, "error": type(exc).__name__})
    finally:
        _ACTIVE = None
        _PAIR_LOCK.release()


# --------------------------------------------------------------------------------------------------------------
# Voice call
# --------------------------------------------------------------------------------------------------------------
class _Source:
    """Async iterator of PCM16LE frames fed from Kotlin (via call_soon_threadsafe). Bounded: drops oldest."""
    MAX_FRAMES = 500  # ~20 s of 40 ms frames

    def __init__(self):
        self.q = asyncio.Queue()
        self.eof = False

    def put(self, data):
        if self.q.qsize() >= self.MAX_FRAMES:
            try:
                self.q.get_nowait()
            except asyncio.QueueEmpty:
                pass
        self.q.put_nowait(data)

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self.eof and self.q.empty():
            raise StopAsyncIteration
        item = await self.q.get()
        if item is None:
            self.eof = True
            raise StopAsyncIteration
        return item


class _Output:
    def __init__(self, call_id):
        self.call_id = call_id

    def write(self, pcm):
        HOST.pcmOut(self.call_id, _jbytes(pcm))

    def flush(self):
        HOST.pcmFlush(self.call_id)


_TRANSCRIPTS = {
    "conversation.item.input_audio_transcription.delta": ("user", False, "delta"),
    "conversation.item.input_audio_transcription.completed": ("user", True, "transcript"),
    "response.output_audio_transcript.delta": ("muse", False, "delta"),
    "response.output_audio_transcript.done": ("muse", True, "transcript"),
    "response.audio_transcript.delta": ("muse", False, "delta"),
    "response.audio_transcript.done": ("muse", True, "transcript"),
}


class _TapGate:
    """Forwards everything to the real ReplyGate, tapping event() first. Never raises into the SDK."""

    def __init__(self, gate, tap):
        object.__setattr__(self, "_gate", gate)
        object.__setattr__(self, "_tap", tap)

    def event(self, event):
        try:
            self._tap(event)
        except Exception:
            pass
        return self._gate.event(event)

    def __getattr__(self, name):
        return getattr(self._gate, name)

    def __setattr__(self, name, value):
        setattr(self._gate, name, value)


class CallRunner:
    def __init__(self, bridge, call_id, thread_id, vm, noise_host, node_id):
        self.bridge, self.call_id, self.thread_id = bridge, call_id, thread_id
        self.vm, self.noise_host, self.node_id = vm, noise_host, node_id
        self.loop = asyncio.get_running_loop()
        self.source = _Source()
        self.output = _Output(call_id)
        self.call = None
        self.task = None
        self.hangup = False
        self.stop_evt = asyncio.Event()

    # ---- Kotlin-facing (any thread) ----
    def push(self, data):
        self.loop.call_soon_threadsafe(self.source.put, data)

    def push_eof(self):
        self.loop.call_soon_threadsafe(self.source.put, None)

    def barge_in(self):
        fut = asyncio.run_coroutine_threadsafe(self._barge_in(), self.loop)
        try:
            fut.result(3)
        except Exception:
            pass

    def hang_up(self):
        fut = asyncio.run_coroutine_threadsafe(self._hang_up(), self.loop)
        try:
            fut.result(14)
        except Exception:
            pass

    # ---- loop thread ----
    def cancel(self):
        self.hangup = True
        if self.task is not None and not self.task.done():
            self.task.cancel()

    async def _barge_in(self):
        call = self.call
        if call is not None:
            await call.barge_in()

    async def _hang_up(self):
        self.cancel()
        if self.task is not None:
            await asyncio.wait({self.task}, timeout=13)

    def _tap(self, event):
        kind = event.get("type")
        if kind == "hatch.voice.turn_started":
            mid = event.get("submission_message_id")
            if isinstance(mid, str) and mid:
                HOST.onTurn(self.call_id, True, mid)
        elif kind == "hatch.voice.turn_settled":
            mid = event.get("response_message_id")
            if isinstance(mid, str) and mid:
                HOST.onTurn(self.call_id, False, mid)
        else:
            t = _TRANSCRIPTS.get(kind)
            if t is not None:
                text = event.get(t[2])
                if isinstance(text, str) and text:
                    HOST.onTranscript(self.call_id, t[0], text, t[1])  # text goes to Kotlin only, never logged

    async def _watch(self, call):
        announced = False
        advisory = 0
        while True:
            if not announced and call.ready.is_set():
                announced = True
                HOST.onCallPhase(self.call_id, "ready", None)
            n = call.advisory_error_count
            if n != advisory:
                advisory = n
                HOST.onAdvisory(self.call_id, n)
            await asyncio.sleep(0.1)

    async def main(self):
        from musegadget.voice_call import VoiceCall
        stop, conn, watcher, call = self.stop_evt, None, None, None
        reason = "failed:Unknown"
        try:
            session = LinkSession(
                noise_host=self.noise_host,
                vm_id=self.vm["vm_id"] or self.vm["vm_name"],
                vm_auth_token=self.vm["vm_auth_token"],
                app_id="hatch-web",  # match the SDK's own voice-call CLI, which always
                # passes an app context for the Noise URL (the bridge omitted it)
                register_device=False,
                device=DeviceDescription(self.node_id, "Muse voice", APP_VERSION + ";dp=1", {}),
                run_command=lambda *unused: {"ok": False, "error": "unsupported command"},
            )
            conn = asyncio.ensure_future(session.run(stop))
            await session.wait_transport_ready(35)
            call = VoiceCall(session, device_id=self.node_id, thread_id=self.thread_id, half_duplex=True,
                             idle_timeout=45, overall_timeout=900, ready_timeout=35)
            call._reply_gate = _TapGate(call._reply_gate, self._tap)
            self.call = call
            watcher = asyncio.ensure_future(self._watch(call))
            reason = await call.run(self.source, self.output, realtime_input=True)
        except asyncio.CancelledError:
            reason = "hangup" if self.hangup else "cancelled"
        except asyncio.TimeoutError:  # (TimeoutError is an OSError: keep before ConnectionError/Exception)
            reason = "admit_timeout" if not (call and call.ready.is_set()) else "failed:TimeoutError"
        except ConnectionError as exc:
            # Include the SDK's fixed-string message (never server text): it splits
            # "voice session failed" (server error event) from "voice response ended
            # before readiness" (stream closed) from "session ended" (transport died).
            reason = ("session_dead" if (call and call.ready.is_set()) else "admission_failed") \
                + ":" + str(exc)
        except Exception as exc:
            reason = "failed:" + type(exc).__name__
        finally:
            if watcher is not None:
                watcher.cancel()
            stop.set()
            if conn is not None:
                try:
                    await asyncio.wait_for(asyncio.shield(conn), 3)
                except BaseException:  # noqa: BLE001
                    conn.cancel()
                    await asyncio.gather(conn, return_exceptions=True)
            self.bridge.calls.pop(self.call_id, None)
        try:
            HOST.onCallPhase(self.call_id, "ended", reason)
        except Exception:
            pass
