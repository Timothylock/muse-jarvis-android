package dev.timlock.jarvis

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.timlock.jarvis.core.model.Mode
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore(name = "settings")
private val KEY_MODE = stringPreferencesKey("mode")

/** Persists mode = REAL | SIM (§4.1 step 3). */
class ModeStore(private val context: Context) {
    suspend fun read(): Mode? =
        when (context.settingsStore.data.first()[KEY_MODE]) {
            "REAL" -> Mode.REAL
            "SIM" -> Mode.SIM
            else -> null
        }

    suspend fun write(mode: Mode) {
        context.settingsStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun clear() {
        context.settingsStore.edit { it.remove(KEY_MODE) }
    }
}
