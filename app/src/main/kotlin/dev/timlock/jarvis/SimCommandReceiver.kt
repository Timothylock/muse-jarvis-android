package dev.timlock.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import android.util.Base64
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
        if (cmd == "display") {
            handleDisplay(intent)
            return
        }
        val result = try {
            run(cmd, value)
        } catch (t: Throwable) {
            fail(cmd, "internal: ${t.javaClass.simpleName}")
        }
        Log.i("JARVIS_SIM", result.toString())
    }

    /** §7.7 `cmd display`: inject a display command through the sim transport and log the validator result. */
    private fun handleDisplay(intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val result = try {
                displayResult(intent)
            } catch (t: Throwable) {
                fail("display", "internal: ${t.javaClass.simpleName}")
            }
            Log.i("JARVIS_SIM", result.toString())
            pending.finish()
        }
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

    private fun run(cmd: String, value: String?): JSONObject {
        val store = AppGraph.store
        if (cmd == "diag") return diag(store.state.value)
        if (cmd != "state" && cmd != "ptt") return fail(cmd, "unknown cmd")
        if (store.state.value.mode != Mode.SIM) return fail(cmd, "not in sim mode")
        return when (cmd) {
            "state" -> {
                val v = value?.trim()?.lowercase() ?: return fail(cmd, "missing value")
                val target = if (v == "auto") null else OrbState.entries.firstOrNull { it.name.lowercase() == v }
                    ?: return fail(cmd, "unknown state")
                store.dispatch(StoreIntent.ForceOrb(target))
                ok(cmd, v)
            }
            else -> { // ptt
                when (value?.trim()?.lowercase()) {
                    "down" -> store.dispatch(StoreIntent.PttDown)
                    "up" -> store.dispatch(StoreIntent.PttUp)
                    "tap" -> { store.dispatch(StoreIntent.PttDown); store.dispatch(StoreIntent.PttUp) }
                    else -> return fail(cmd, "value must be down|up|tap")
                }
                ok(cmd, value!!.trim().lowercase())
            }
        }
    }

    private fun ok(cmd: String, value: String) = JSONObject().put("cmd", cmd).put("ok", true).put("value", value)
    private fun fail(cmd: String, error: String) = JSONObject().put("cmd", cmd).put("ok", false).put("error", error)

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
        .put("panelCount", s.panelCount)
        .put("panels", JSONArray().also { arr ->
            for (p in s.panels.panels) arr.put(
                JSONObject().put("id", p.id).put("type", p.type.name.lowercase())
                    .put("sticky", p.sticky).put("collapsed", p.collapsed),
            )
        })
}
