package app.purefeed.settings

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
    private val blockKey = stringPreferencesKey("block_settings")
    private val updateKey = stringPreferencesKey("update_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<FilterSettings> = context.dataStore.data
        .map { prefs -> prefs[key]?.let(::decode) ?: FilterSettings() }
        .distinctUntilChanged()

    /**
     * Where to fetch rules.json from. Defaults to this project's GitHub copy until the
     * user saves something else; saving an empty URL means bundled rules only.
     */
    val rulesUrl: Flow<String> = context.dataStore.data
        .map { it[rulesUrlKey] ?: DEFAULT_RULES_URL }
        .distinctUntilChanged()

    suspend fun setRulesUrl(url: String) {
        context.dataStore.edit { it[rulesUrlKey] = url.trim() }
    }

    val blockSettings: Flow<BlockSettings> = context.dataStore.data
        .map { prefs ->
            prefs[blockKey]?.let { runCatching { json.decodeFromString(BlockSettings.serializer(), it) }.getOrNull() }
                ?: BlockSettings()
        }
        .distinctUntilChanged()

    suspend fun updateBlock(transform: (BlockSettings) -> BlockSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[blockKey]?.let { runCatching { json.decodeFromString(BlockSettings.serializer(), it) }.getOrNull() }
                ?: BlockSettings()
            prefs[blockKey] = json.encodeToString(BlockSettings.serializer(), transform(current))
        }
    }

    val updateSettings: Flow<UpdateSettings> = context.dataStore.data
        .map { prefs -> decodeUpdate(prefs[updateKey]) }
        .distinctUntilChanged()

    suspend fun updateUpdateSettings(transform: (UpdateSettings) -> UpdateSettings) {
        context.dataStore.edit { prefs ->
            prefs[updateKey] = json.encodeToString(UpdateSettings.serializer(), transform(decodeUpdate(prefs[updateKey])))
        }
    }

    private fun decodeUpdate(s: String?): UpdateSettings =
        s?.let { runCatching { json.decodeFromString(UpdateSettings.serializer(), it) }.getOrNull() } ?: UpdateSettings()

    suspend fun update(transform: (FilterSettings) -> FilterSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: FilterSettings()
            prefs[key] = json.encodeToString(FilterSettings.serializer(), transform(current))
        }
    }

    companion object {
        const val DEFAULT_RULES_URL = "https://raw.githubusercontent.com/MrDibbley/PureFeed/main/rules/rules.json"
    }

    private fun decode(s: String): FilterSettings =
        runCatching { json.decodeFromString(FilterSettings.serializer(), s) }.getOrDefault(FilterSettings())
}
