package dev.timlock.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothManager
import android.util.Log
import dev.timlock.jarvis.core.transport.BlePeripheral
import dev.timlock.jarvis.feature.pairing.ble.AndroidBlePeripheral
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import android.util.Base64
import dev.timlock.jarvis.core.model.DisplayPreset
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.transport.sdk.SdkTransport
import dev.timlock.jarvis.transport.sim.SimTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ADB control surface (§7.7): `adb shell am broadcast -a dev.timlock.jarvis.SIM --es cmd <cmd> [--es value <v>]`.
 * Protected by the DUMP permission (held by the ADB shell). Everything but `diag` requires mode == SIM.
 * Each command logs exactly one single-line JSON result to tag JARVIS_SIM.
 */
class SimCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: ""
        val value = intent.getStringExtra("value")
        if (cmd == "display" || cmd == "preset" || cmd in REAL_ASYNC_CMDS) {
            handleAsync(context.applicationContext, cmd, intent)
            return
        }
        val result = try {
            run(cmd, value, intent)
        } catch (t: Throwable) {
            fail(cmd, "internal: ${t.javaClass.simpleName}")
        }
        Log.i("JARVIS_SIM", result.toString())
    }

    /** §7.7 `cmd display`: inject a display command through the sim transport and log the validator result. */
    private fun handleAsync(context: Context, cmd: String, intent: Intent) {
        val pending = goAsync()
        // ble_smoke holds the radio for many seconds: release the broadcast first (10 s limit); the result is logged later.
        var finished = false
        fun finishOnce() { if (!finished) { finished = true; pending.finish() } }
        if (cmd == "ble_smoke") finishOnce()
        CoroutineScope(Dispatchers.Default).launch {
            val result = try {
                when (cmd) {
                    "display" -> displayResult(intent)
                    "preset" -> presetResult(intent)
                    else -> realResult(context, cmd, intent)
                }
            } catch (t: Throwable) {
                fail(cmd, "internal: ${t.javaClass.simpleName}")
            }
            Log.i("JARVIS_SIM", result.toString())
            finishOnce()
        }
    }

    /**
     * Real-mode commands (pairing material; never SIM injection, §7.7). Results carry node ids and token hints only.
     * `import_pairing [--ez overwrite true]`, `export_identity`, `provision_token --es token mgst_...`, `unpair`.
     */
    private suspend fun realResult(context: Context, cmd: String, intent: Intent): JSONObject {
        val mode = AppGraph.store.state.value.mode
        if (mode == Mode.SIM) return fail(cmd, "not available in sim mode")
        val pairing = AppGraph.pairing
        return when (cmd) {
            "import_pairing" -> {
                if (mode != Mode.REAL) return fail(cmd, "real mode required (start with --es jarvis.mode real)")
                val overwrite = intent.getBooleanExtra("overwrite", false)
                when (val r = pairing.import(overwrite)) {
                    is PairingController.Status.Imported -> JSONObject().put("cmd", cmd).put("ok", true).put("node_id", r.nodeId)
                    is PairingController.Status.NoFiles -> fail(cmd, "no pairing files found").put("dir", r.dir)
                    is PairingController.Status.Invalid -> fail(cmd, "invalid: ${r.reason}")
                    is PairingController.Status.Conflict -> fail(cmd, "identity_conflict")
                        .put("stored", r.storedNodeId).put("imported", r.importedNodeId)
                        .put("hint", "re-run with --ez overwrite true to replace")
                    is PairingController.Status.Failed -> fail(cmd, "failed: ${r.reason}")
                    else -> fail(cmd, "unexpected status")
                }
            }
            "export_identity" -> {
                val (nodeId, path) = pairing.exportIdentity()
                JSONObject().put("cmd", cmd).put("ok", true).put("node_id", nodeId).put("path", path)
            }
            "provision_token" -> {
                val raw = intent.getStringExtra("token")
                // §4.2 P1: while the pairing flow is open, provisioning goes through it (accepted only on P1);
                // otherwise the M6 controller path stores the token directly.
                val flow = AppGraph.pairingFlow.value
                val err = if (flow != null) flow.provisionToken(raw) else pairing.provisionToken(raw)
                if (err != null) fail(cmd, err) else {
                    val hint = SdkToken(raw.orEmpty()).hint()
                    JSONObject().put("cmd", cmd).put("ok", true).put("hint", hint)
                }
            }
            "pair_selftest" -> {
                if (mode != Mode.REAL) return fail(cmd, "real mode required (start with --es jarvis.mode real)")
                val t = AppGraph.transport as? SdkTransport ?: return fail(cmd, "no sdk transport")
                val r185 = JSONObject(t.pairSelfTest(185))
                val r23 = JSONObject(t.pairSelfTest(23))
                JSONObject().put("cmd", cmd).put("ok", r185.optBoolean("ok") && r23.optBoolean("ok"))
                    .put("mtu185", r185).put("mtu23", r23).put("statuses", r185.opt("statuses") ?: JSONObject.NULL)
            }
            "ble_smoke" -> {
                if (mode != Mode.REAL) return fail(cmd, "real mode required (start with --es jarvis.mode real)")
                bleSmoke(context, intent.getLongExtra("ms", 15_000L).coerceIn(1_000L, 60_000L))
            }
            "unpair" -> {
                if (mode != Mode.REAL) return fail(cmd, "real mode required")
                val t = AppGraph.transport ?: return fail(cmd, "no transport")
                t.unpair()
                JSONObject().put("cmd", cmd).put("ok", true)
            }
            else -> fail(cmd, "unknown cmd")
        }
    }

    /** Opens the real GATT peripheral under a fixed test name (no Muse/SDK involvement), holds it, closes, checks the name. */
    private suspend fun bleSmoke(context: Context, ms: Long): JSONObject = withContext(Dispatchers.IO) {
        val cmd = "ble_smoke"
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        fun adapterName(): String? = try { adapter?.name } catch (e: SecurityException) { null }
        val before = adapterName()
        val p = AndroidBlePeripheral(context)
        val result = JSONObject().put("cmd", cmd)
        val readiness = p.readinessProblem()
        result.put("readinessProblem", readiness ?: JSONObject.NULL)
        result.put("multipleAdvertisementSupported", p.multipleAdvertisementSupported())
        if (readiness != null) return@withContext result.put("ok", false).put("open", "skipped")
        var connects = 0
        var writes = 0
        val opened = p.open("MuseDeskSmoke", object : BlePeripheral.Listener {
            override fun onConnected() { connects++ }
            override fun onWrite(packet: ByteArray) { writes++ }
            override fun onDisconnected() {}
        })
        result.put("open", opened ?: "ok")
        result.put("nameDuringWindow", adapterName() == "MuseDeskSmoke")
        if (opened == null) delay(ms)
        p.close()
        var restored = before == null
        val end = System.currentTimeMillis() + 3_000
        while (!restored && System.currentTimeMillis() < end) { restored = adapterName() == before; if (!restored) delay(100) }
        result.put("serviceAddedStatus", p.lastServiceAddedStatus ?: JSONObject.NULL)
        result.put("adapterNameRestored", restored)
        result.put("centralsConnected", connects).put("writesReceived", writes)
        result.put("ok", opened == null && restored && p.lastServiceAddedStatus == 0)
    }

    private suspend fun displayResult(intent: Intent): JSONObject {
        if (AppGraph.store.state.value.mode != Mode.SIM) return fail("display", "not in sim mode")
        val sim = AppGraph.transport as? SimTransport ?: return fail("display", "no sim transport")
        val name = intent.getStringExtra("name")?.trim().orEmpty()
        if (name.isEmpty()) return fail("display", "missing name")
        val b64 = intent.getStringExtra("json_b64")
        val json = if (b64 != null) {
            try { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) } catch (e: IllegalArgumentException) {
                return fail("display", "bad json_b64")
            }
        } else intent.getStringExtra("json") ?: "{}"
        val r = sim.injectCommand(name, json) ?: return fail("display", "timeout waiting for reply")
        if (!r.ok) return fail("display", r.error ?: "error")
        val payload = try { JSONObject(r.payloadJson ?: "{}") } catch (e: Exception) { JSONObject() }
        return JSONObject().put("cmd", "display").put("name", name).put("ok", true).put("payload", payload)
    }

    private suspend fun presetResult(intent: Intent): JSONObject {
        if (AppGraph.store.state.value.mode != Mode.SIM) return fail("preset", "not in sim mode")
        val v = intent.getStringExtra("value")?.trim()?.lowercase() ?: return fail("preset", "missing value")
        val p = DisplayPreset.entries.firstOrNull { it.name.lowercase() == v } ?: return fail("preset", "unknown preset")
        val r = AppGraph.simController.preset(p) ?: return fail("preset", "timeout waiting for reply")
        return if (r.ok) ok("preset", v) else fail("preset", r.error ?: "error")
    }

    private fun run(cmd: String, value: String?, intent: Intent): JSONObject {
        val store = AppGraph.store
        if (cmd == "diag") return diag(store.state.value)
        if (cmd == "perfhud") {
            if (!intent.hasExtra("on")) return fail(cmd, "missing --ez on")
            val on = intent.getBooleanExtra("on", false)
            store.dispatch(StoreIntent.SetPerfHud(on))
            return ok(cmd, on.toString())
        }
        if (cmd !in SIM_CMDS) return fail(cmd, "unknown cmd")
        if (store.state.value.mode != Mode.SIM) return fail(cmd, "not in sim mode")
        val sim = AppGraph.simController
        return when (cmd) {
            "state" -> {
                val v = value?.trim()?.lowercase() ?: return fail(cmd, "missing value")
                val target = if (v == "auto") null else OrbState.entries.firstOrNull { it.name.lowercase() == v }
                    ?: return fail(cmd, "unknown state")
                store.dispatch(StoreIntent.ForceOrb(target))
                ok(cmd, v)
            }
            "ptt" -> {
                when (value?.trim()?.lowercase()) {
                    "down" -> store.dispatch(StoreIntent.PttDown)
                    "up" -> store.dispatch(StoreIntent.PttUp)
                    "tap" -> { store.dispatch(StoreIntent.PttDown); store.dispatch(StoreIntent.PttUp) }
                    else -> return fail(cmd, "value must be down|up|tap")
                }
                ok(cmd, value!!.trim().lowercase())
            }
            "scenario" -> {
                val id = intent.getStringExtra("id")?.trim().orEmpty()
                if (id.isEmpty()) return fail(cmd, "missing id")
                val ts = if (intent.hasExtra("timescale")) intent.getFloatExtra("timescale", 1f) else null
                val auto = if (intent.hasExtra("auto_ptt_ms")) intent.getLongExtra("auto_ptt_ms", 0L) else null
                val err = sim.play(id, ts, auto)
                if (err != null) fail(cmd, err) else JSONObject().put("cmd", cmd).put("ok", true).put("id", id)
            }
            "stop" -> { sim.stop(); JSONObject().put("cmd", cmd).put("ok", true) }
            "pause" -> {
                if (!intent.hasExtra("on")) return fail(cmd, "missing --ez on")
                val on = intent.getBooleanExtra("on", false)
                sim.pause(on)
                ok(cmd, on.toString())
            }
            "fault" -> {
                val kind = intent.getStringExtra("kind")
                val f = SimFault.parse(kind) ?: return fail(cmd, "kind must be offline|revoke|send_fail|admit_timeout|session_dead")
                val err = sim.fault(f)
                if (err != null) fail(cmd, err) else ok(cmd, f.wire)
            }
            "repair" -> sim.repair()?.let { fail(cmd, it) } ?: JSONObject().put("cmd", cmd).put("ok", true)
            "reload" -> sim.reload()?.let { fail(cmd, it) } ?: JSONObject().put("cmd", cmd).put("ok", true)
            "list" -> JSONObject().put("cmd", cmd).put("ok", true)
                .put("scenarios", JSONArray().also { a -> sim.scenarios.value.forEach { a.put(it.id) } })
            "hangup" -> { store.dispatch(StoreIntent.HangUp); JSONObject().put("cmd", cmd).put("ok", true) }
            else -> fail(cmd, "unknown cmd")
        }
    }

    private companion object {
        val REAL_ASYNC_CMDS = setOf("import_pairing", "export_identity", "provision_token", "unpair", "pair_selftest", "ble_smoke")
        val SIM_CMDS = setOf("state", "ptt", "scenario", "stop", "pause", "fault", "repair", "reload", "list", "hangup")
    }

    private fun ok(cmd: String, value: String) = JSONObject().put("cmd", cmd).put("ok", true).put("value", value)
    private fun fail(cmd: String, error: String) = JSONObject().put("cmd", cmd).put("ok", false).put("error", error)

    /** Read-only SdkDiagnostics for REAL mode (non-secret fields only); null otherwise. */
    private fun sdkDiag(): Any {
        val d = (AppGraph.transport as? SdkTransport)?.diagnostics?.value ?: return JSONObject.NULL
        fun <T> T?.j(): Any = this ?: JSONObject.NULL
        return JSONObject()
            .put("pythonStarted", d.pythonStarted).put("pythonStartMs", d.pythonStartMs)
            .put("sdkVersion", d.sdkVersion.j()).put("sdkCommit", d.sdkCommit.j())
            .put("nodeId", d.nodeId.j()).put("paired", d.paired).put("tokenHint", d.tokenHint.j())
            .put("tlsRoots", d.tlsRoots).put("httpsProbe", d.httpsProbe.j())
            .put("commandCount", d.commandCount).put("describeChars", d.describeChars)
            .put("storageRoundTrip", d.storageRoundTrip.j()).put("lastOutcome", d.lastOutcome.j())
            .put("restarts", d.restarts).put("callPhase", d.callPhase.j()).put("advisoryErrors", d.advisoryErrors)
    }

    private fun diag(s: AppState): JSONObject = JSONObject()
        .put("cmd", "diag").put("ok", true)
        .put("mode", s.mode?.name?.lowercase() ?: "none")
        .put("link", when (s.link) {
            LinkState.Unpaired -> "unpaired"
            LinkState.Connecting -> "connecting"
            is LinkState.Online -> "online"
            is LinkState.Offline -> "offline"
            is LinkState.PairingLost -> "pairing_lost"
        })
        .put("orb", s.orbState.name.lowercase())
        .put("machine", s.machine.name.lowercase())
        .put("forced", s.forcedOrb?.name?.lowercase() ?: "auto")
        .put("call", s.call.name.lowercase())
        .put("ptt", when (s.ptt) {
            is dev.timlock.jarvis.core.store.PttState.Up -> "up"
            is dev.timlock.jarvis.core.store.PttState.Held -> "held"
            is dev.timlock.jarvis.core.store.PttState.Latched -> "latched"
        })
        .put("perfHud", s.perfHud)
        .put("scenario", if (s.mode == Mode.SIM) {
            val r = AppGraph.simController.run.value
            JSONObject().put("id", r.id ?: JSONObject.NULL).put("step", r.step).put("total", r.total)
        } else JSONObject.NULL)
        .put("sdk", sdkDiag())
        .put("panelCount", s.panelCount)
        .put("panels", JSONArray().also { arr ->
            for (p in s.panels.panels) arr.put(
                JSONObject().put("id", p.id).put("type", p.type.name.lowercase())
                    .put("sticky", p.sticky).put("collapsed", p.collapsed),
            )
        })
}
