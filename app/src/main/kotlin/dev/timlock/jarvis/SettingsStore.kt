package dev.timlock.jarvis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
