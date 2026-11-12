package dev.timlock.jarvis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dev.timlock.jarvis.core.model.QualityPref
import dev.timlock.jarvis.core.model.UserConfig
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The ONE DataStore delegate for the "settings" file (a second delegate for the same file crashes at runtime). */
internal val Context.settingsStore by preferencesDataStore(name = "settings")

private val KEY_TEST_INPUT = booleanPreferencesKey("dev_test_input")
private val KEY_TEST_THREAD = stringPreferencesKey("dev_test_thread_id")

/** Developer settings (§7.7): real-mode test-input injection flag and an optional dedicated thread id. */
class DevSettings(private val context: Context) {
    val testInputEnabledFlow: Flow<Boolean> = context.settingsStore.data.map { it[KEY_TEST_INPUT] ?: false }

    suspend fun testInputEnabled(): Boolean = context.settingsStore.data.first()[KEY_TEST_INPUT] ?: false

    suspend fun setTestInputEnabled(on: Boolean) {
        context.settingsStore.edit { it[KEY_TEST_INPUT] = on }
    }

    val testThreadIdFlow: Flow<String?> = context.settingsStore.data.map { it[KEY_TEST_THREAD]?.takeIf { v -> v.isNotEmpty() } }

    suspend fun testThreadId(): String? = context.settingsStore.data.first()[KEY_TEST_THREAD]?.takeIf { it.isNotEmpty() }

    /** Null or blank clears. */
    suspend fun setTestThreadId(id: String?) {
        context.settingsStore.edit {
            if (id.isNullOrBlank()) it.remove(KEY_TEST_THREAD) else it[KEY_TEST_THREAD] = id.trim()
        }
    }
}

private val KEY_STANDBY = longPreferencesKey("cfg_standby_ms")
private val KEY_THINK = longPreferencesKey("cfg_think_ms")
/** Absent = default (120 s); 0 = sticky by default. */
private val KEY_PANEL_TTL = longPreferencesKey("cfg_panel_ttl_ms")
private val KEY_MAX_PANELS = intPreferencesKey("cfg_max_panels")
private val KEY_CLOCK24 = booleanPreferencesKey("cfg_clock24h")
private val KEY_NIGHT_ON = booleanPreferencesKey("cfg_night_enabled")
private val KEY_NIGHT_START = intPreferencesKey("cfg_night_start_min")
private val KEY_NIGHT_END = intPreferencesKey("cfg_night_end_min")
private val KEY_QUALITY = stringPreferencesKey("cfg_quality")
private val KEY_MIC_GAIN = intPreferencesKey("cfg_mic_gain_db")

/** Persistence for [UserConfig] (§4.5) in the shared "settings" DataStore. */
class ConfigStore(private val context: Context) {
    suspend fun load(): UserConfig {
        val p = context.settingsStore.data.first()
        val d = UserConfig()
        return UserConfig(
            standbyMs = p[KEY_STANDBY] ?: d.standbyMs,
            thinkTimeoutMs = p[KEY_THINK] ?: d.thinkTimeoutMs,
            panelTtlMs = when (val v = p[KEY_PANEL_TTL]) { null -> d.panelTtlMs; 0L -> null; else -> v },
            maxPanels = p[KEY_MAX_PANELS] ?: d.maxPanels,
            clock24h = p[KEY_CLOCK24] ?: d.clock24h,
            nightEnabled = p[KEY_NIGHT_ON] ?: d.nightEnabled,
            nightStartMin = p[KEY_NIGHT_START] ?: d.nightStartMin,
            nightEndMin = p[KEY_NIGHT_END] ?: d.nightEndMin,
            quality = p[KEY_QUALITY]?.let { q -> QualityPref.entries.firstOrNull { it.name == q } } ?: d.quality,
            micGainDb = p[KEY_MIC_GAIN] ?: d.micGainDb,
        ).sanitized()
    }

    suspend fun save(c: UserConfig) {
        context.settingsStore.edit {
            it[KEY_STANDBY] = c.standbyMs
            it[KEY_THINK] = c.thinkTimeoutMs
            it[KEY_PANEL_TTL] = c.panelTtlMs ?: 0L
            it[KEY_MAX_PANELS] = c.maxPanels
            it[KEY_CLOCK24] = c.clock24h
            it[KEY_NIGHT_ON] = c.nightEnabled
            it[KEY_NIGHT_START] = c.nightStartMin
            it[KEY_NIGHT_END] = c.nightEndMin
            it[KEY_QUALITY] = c.quality.name
            it[KEY_MIC_GAIN] = c.micGainDb
        }
    }
}
