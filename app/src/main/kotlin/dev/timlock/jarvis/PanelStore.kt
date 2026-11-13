package dev.timlock.jarvis

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.PanelCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val KEY_PANELS = stringPreferencesKey("panels_v1")

/** Persists sticky/pinned panels + timers (§5.5) as one JSON string in the settings DataStore. Logs counts only. */
class PanelStore(private val context: Context) {
    private val mutex = Mutex()

    suspend fun save(stack: PanelStack) = mutex.withLock {
        try {
            val json = PanelCodec.encode(stack, monoMillis(), System.currentTimeMillis())
            context.settingsStore.edit { it[KEY_PANELS] = json }
            Log.d("JARVIS_STORE", "panels saved: ${stack.panels.size}")
        } catch (t: Exception) {
            Log.w("JARVIS_STORE", "panels save failed: ${t.javaClass.simpleName}")
        }
    }

    suspend fun load(): PanelStack? = mutex.withLock {
        try {
            val json = context.settingsStore.data.first()[KEY_PANELS] ?: return@withLock null
            val stack = PanelCodec.decode(json, monoMillis(), System.currentTimeMillis())
            if (stack == null) Log.w("JARVIS_STORE", "panels decode failed")
            else Log.d("JARVIS_STORE", "panels loaded: ${stack.panels.size}")
            stack
        } catch (t: Exception) {
            Log.w("JARVIS_STORE", "panels load failed: ${t.javaClass.simpleName}")
            null
        }
    }
}
