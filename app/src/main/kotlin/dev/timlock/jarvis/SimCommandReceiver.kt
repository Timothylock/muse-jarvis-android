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
import dev.timlock.jarvis.core.model.QualityPref
import dev.timlock.jarvis.core.model.UserConfig
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
 * Protected by the DUMP permission (held by the ADB shell). Everything but `diag`, `perfhud` and the test hooks
 * requires mode == SIM. Each command logs exactly one single-line JSON result to tag JARVIS_SIM.
 *
 * Commands: sim (SIM only) state, ptt, scenario, stop, pause, fault, repair, reload, list, hangup, pairflow, display, preset;
 * any mode: export_logs (redacted logcat+diag zip), diag (adds testInput/call/lastCall/night/config), perfhud, night, settings, config, config_set;
 * real-pairing (REAL only): import_pairing, export_identity, provision_token, unpair, pair_selftest, ble_smoke;
 * test hooks (REAL needs dev_setting test_input on + link online; SIM always allowed; results never carry server text):
 * inject_wav, make_wav, list_wav, dev_setting (test_input | test_thread_id).
 */
class SimCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: ""
        val value = intent.getStringExtra("value")
        if (cmd == "display" || cmd == "preset" || cmd == "config_set" || cmd == "export_logs" || cmd in REAL_ASYNC_CMDS || cmd in TEST_CMDS) {
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
        // inject_wav / make_wav can run for a minute: release the broadcast first, the single result line comes later.
        if (cmd == "ble_smoke" || cmd == "inject_wav" || cmd == "make_wav") finishOnce()
        CoroutineScope(Dispatchers.Default).launch {
            val result = try {
                when (cmd) {
                    "display" -> displayResult(intent)
                    "preset" -> presetResult(intent)
                    "config_set" -> configSetResult(intent)
                    "export_logs" -> exportLogsResult(context)
                    in TEST_CMDS -> testResult(cmd, intent)
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
     * §7.7 test hooks, allowed in REAL and SIM (inject_wav enforces its own real-mode preconditions):
     * `inject_wav --es name n [--es thread_id id] [--ez hangup false] [--el timeout_ms 90000]`,
     * `make_wav --es name n --es text "..." [--ez label false]`, `list_wav`,
     * `dev_setting --es key test_input --ez on true|false` / `--es key test_thread_id --es value <id|empty>`.
     */
    private suspend fun testResult(cmd: String, intent: Intent): JSONObject {
        val ti = AppGraph.testInput
        return when (cmd) {
            "inject_wav" -> ti.inject(
                intent.getStringExtra("name")?.trim().orEmpty(),
                intent.getStringExtra("thread_id")?.trim()?.takeIf { it.isNotEmpty() },
                intent.getBooleanExtra("hangup", true),
                (if (intent.hasExtra("timeout_ms")) intent.getLongExtra("timeout_ms", 90_000L) else 90_000L).coerceIn(5_000L, 300_000L),
            )
            "make_wav" -> ti.makeWav(
                intent.getStringExtra("name")?.trim().orEmpty(),
                intent.getStringExtra("text").orEmpty(),
                intent.getBooleanExtra("label", true),
            )
            "list_wav" -> ti.list()
            "dev_setting" -> when (intent.getStringExtra("key")) {
                "test_input" -> {
                    if (!intent.hasExtra("on")) return fail(cmd, "missing --ez on")
                    val on = intent.getBooleanExtra("on", false)
                    AppGraph.devSettings.setTestInputEnabled(on)
                    val s = AppGraph.store.state.value
                    AppGraph.store.dispatch(StoreIntent.SetTestInput(on, s.testInputActive))
                    JSONObject().put("cmd", cmd).put("ok", true).put("key", "test_input").put("value", on)
                }
                "test_thread_id" -> {
                    val v = intent.getStringExtra("value")?.trim().orEmpty()
                    AppGraph.devSettings.setTestThreadId(v)
                    JSONObject().put("cmd", cmd).put("ok", true).put("key", "test_thread_id").put("set", v.isNotEmpty())
                }
                else -> fail(cmd, "key must be test_input|test_thread_id")
            }
            else -> fail(cmd, "unknown cmd")
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

    /**
     * `config_set --es key <k> --es value <v>`; keys: standby_s, think_s, panel_ttl_s (0 = sticky), max_panels, clock24h,
     * night_enabled, night_start / night_end (HH:MM), quality (auto|a|b|c), mic_gain_db. Persists, then dispatches.
     */
    private suspend fun configSetResult(intent: Intent): JSONObject {
        val cmd = "config_set"
        val key = intent.getStringExtra("key")?.trim().orEmpty()
        val v = intent.getStringExtra("value")?.trim().orEmpty()
        fun hm(x: String): Int? {
            val p = x.split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: return null
            val m = if (p.size > 1) p[1].toIntOrNull() ?: return null else 0
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }
        val f: ((UserConfig) -> UserConfig) = when (key) {
            "standby_s" -> { val n = v.toLongOrNull() ?: return fail(cmd, "bad number"); { it.copy(standbyMs = n * 1000) } }
            "think_s" -> { val n = v.toLongOrNull() ?: return fail(cmd, "bad number"); { it.copy(thinkTimeoutMs = n * 1000) } }
            "panel_ttl_s" -> { val n = v.toLongOrNull() ?: return fail(cmd, "bad number"); { it.copy(panelTtlMs = if (n <= 0) null else n * 1000) } }
            "max_panels" -> { val n = v.toIntOrNull() ?: return fail(cmd, "bad number"); { it.copy(maxPanels = n) } }
            "clock24h" -> { val b = v.toBooleanStrictOrNull() ?: return fail(cmd, "bad boolean"); { it.copy(clock24h = b) } }
            "night_enabled" -> { val b = v.toBooleanStrictOrNull() ?: return fail(cmd, "bad boolean"); { it.copy(nightEnabled = b) } }
            "night_start" -> { val n = hm(v) ?: return fail(cmd, "want HH:MM"); { it.copy(nightStartMin = n) } }
            "night_end" -> { val n = hm(v) ?: return fail(cmd, "want HH:MM"); { it.copy(nightEndMin = n) } }
            "quality" -> { val q = QualityPref.entries.firstOrNull { it.name.equals(v, true) } ?: return fail(cmd, "want auto|a|b|c"); { it.copy(quality = q) } }
            "mic_gain_db" -> { val n = v.toIntOrNull() ?: return fail(cmd, "bad number"); { it.copy(micGainDb = n) } }
            else -> return fail(cmd, "unknown key")
        }
        val c = AppGraph.updateConfig(f)
        return JSONObject().put("cmd", cmd).put("ok", true).put("config", configJson(c))
    }

    private fun configJson(c: UserConfig): JSONObject = JSONObject()
        .put("standbyMs", c.standbyMs).put("thinkTimeoutMs", c.thinkTimeoutMs)
        .put("panelTtlMs", c.panelTtlMs ?: JSONObject.NULL).put("maxPanels", c.maxPanels)
        .put("clock24h", c.clock24h).put("nightEnabled", c.nightEnabled)
        .put("nightStartMin", c.nightStartMin).put("nightEndMin", c.nightEndMin)
        .put("quality", c.quality.name.lowercase()).put("micGainDb", c.micGainDb)

    private suspend fun exportLogsResult(context: Context): JSONObject {
        val r = LogExporter.export(context, diag(AppGraph.store.state.value))
        return if (r.ok) JSONObject().put("cmd", "export_logs").put("ok", true)
            .put("path", r.path).put("bytes", r.bytes).put("lines", r.lines)
        else fail("export_logs", r.error ?: "failed")
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
        when (cmd) {
            // `night on|off|auto`: force & hold the night dim (§2.7) or return to the schedule.
            "night" -> {
                val f = when (value?.trim()?.lowercase()) {
                    "on" -> NightController.Force.ON
                    "off" -> NightController.Force.OFF
                    "auto" -> NightController.Force.AUTO
                    else -> return fail(cmd, "value must be on|off|auto")
                }
                AppGraph.night.setForce(f)
                return ok(cmd, f.name.lowercase()).put("night", store.state.value.night)
            }
            // `settings open|close`: show/hide the §4.5 overlay (screenshots).
            "settings" -> {
                val open = when (value?.trim()?.lowercase()) {
                    "open" -> true
                    "close" -> false
                    else -> return fail(cmd, "value must be open|close")
                }
                AppGraph.setSettingsOpen(open)
                return ok(cmd, if (open) "open" else "close")
            }
            "config" -> return JSONObject().put("cmd", cmd).put("ok", true).put("config", configJson(store.state.value.config))
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
            "pairflow" -> pairFlow(intent, value)
            else -> fail(cmd, "unknown cmd")
        }
    }

    /**
     * Sim-only driver for the §4.2 pairing screens (screenshots / headless checks):
     * `--es value open|next|cancel|retry|continue|back|status [--es outcome paired|window_closed|ble_error|invalid_token|already_paired] [--ef timescale F]`.
     * `next` fills P1 with an obviously fake, well-formed token; sim pairing never stores credentials.
     */
    private fun pairFlow(intent: Intent, value: String?): JSONObject {
        val cmd = "pairflow"
        val sim = AppGraph.simController
        if (intent.hasExtra("outcome") || intent.hasExtra("timescale")) {
            var s = sim.settings.value
            intent.getStringExtra("outcome")?.let { o ->
                s = s.copy(pairOutcome = dev.timlock.jarvis.core.model.SimPairOutcome.parse(o) ?: return fail(cmd, "unknown outcome"))
            }
            if (intent.hasExtra("timescale")) s = s.copy(timescale = intent.getFloatExtra("timescale", 1f))
            sim.updateSettings(s)
        }
        val v = value?.trim()?.lowercase() ?: "status"
        if (v == "open") { AppGraph.openPairing(); return ok(cmd, v) }
        val flow = AppGraph.pairingFlow.value ?: return fail(cmd, "no pairing flow open")
        when (v) {
            "next" -> { flow.onTokenChanged(FAKE_SIM_TOKEN); flow.next() }
            "cancel" -> flow.cancel()
            "retry" -> flow.retry()
            "continue" -> flow.continueAfterResult()
            "back" -> flow.backToToken()
            "status" -> Unit
            else -> return fail(cmd, "value must be open|next|cancel|retry|continue|back|status")
        }
        val st = flow.state.value
        return JSONObject().put("cmd", cmd).put("ok", true).put("value", v)
            .put("step", st.step.name.lowercase()).put("stages", st.stagesReached)
            .put("result", st.result?.name?.lowercase() ?: JSONObject.NULL)
    }

    private companion object {
        val TEST_CMDS = setOf("inject_wav", "make_wav", "list_wav", "dev_setting")
        val REAL_ASYNC_CMDS = setOf("import_pairing", "export_identity", "provision_token", "unpair", "pair_selftest", "ble_smoke")
        val SIM_CMDS = setOf("state", "ptt", "scenario", "stop", "pause", "fault", "repair", "reload", "list", "hangup", "pairflow")
        /** Fake, format-valid token for sim pairing screenshots only (sim never stores or sends it). */
        const val FAKE_SIM_TOKEN = "mgst_SIMULATIONxONLYxNOTxAxREALxTOKENxxxxxxxxxxA"
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

    internal fun diag(s: AppState): JSONObject = JSONObject()
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
        .put("night", AppGraph.night.diag())
        .put("config", configJson(s.config))
        .put("settingsOpen", AppGraph.settingsOpen.value)
        .put("testInput", JSONObject().put("enabled", s.testInputEnabled).put("active", s.testInputActive))
        .put("callStats", AppGraph.calls.current?.toJson() ?: JSONObject.NULL)
        .put("lastCall", AppGraph.calls.last?.toJson() ?: JSONObject.NULL)
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
