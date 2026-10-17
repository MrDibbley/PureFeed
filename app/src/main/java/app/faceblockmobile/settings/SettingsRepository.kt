package app.faceblockmobile.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Stores [FilterSettings] as one JSON string. The lists are small, so this is simpler
 * than Room, and fields added later just take their defaults.
 */
class SettingsRepository(private val context: Context) {

    private val key = stringPreferencesKey("filter_settings")
    private val rulesUrlKey = stringPreferencesKey("rules_url")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<FilterSettings> = context.dataStore.data
        .map { prefs -> prefs[key]?.let(::decode) ?: FilterSettings() }
        .distinctUntilChanged()

    /** Where to fetch rules.json from (e.g. a raw.githubusercontent.com URL). Empty = bundled only. */
    val rulesUrl: Flow<String> = context.dataStore.data
        .map { it[rulesUrlKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun setRulesUrl(url: String) {
        context.dataStore.edit { it[rulesUrlKey] = url.trim() }
    }

    suspend fun update(transform: (FilterSettings) -> FilterSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: FilterSettings()
            prefs[key] = json.encodeToString(FilterSettings.serializer(), transform(current))
        }
    }

    private fun decode(s: String): FilterSettings =
        runCatching { json.decodeFromString(FilterSettings.serializer(), s) }.getOrDefault(FilterSettings())
}
