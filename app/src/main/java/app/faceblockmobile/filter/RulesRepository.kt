package app.faceblockmobile.filter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supplies rules.json to the filter.
 *
 * Sources, in order of preference:
 *   1. remote: fetched from the URL set in Filter settings, cached in the app's files dir
 *   2. bundled: assets/rules.json (built from rules/rules.json)
 *
 * The cached remote copy is only used if it is valid AND its "updated" date is not
 * older than the bundled one, so a stale remote file can't override a newer APK.
 */
class RulesRepository(private val context: Context) {

    enum class Source { BUNDLED, REMOTE }

    data class State(
        val rules: JsonObject,
        val source: Source,
        val updated: String,
        /** Result of the last remote check, for the settings screen. Null = never checked. */
        val lastCheck: String? = null,
    )

    private val cacheFile = File(context.filesDir, "rules-remote.json")
    private val etagFile = File(context.filesDir, "rules-remote.etag")
    private val bundled: JsonObject = requireNotNull(parse(readAsset())) { "Bundled $ASSET is invalid" }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun readAsset() = context.assets.open(ASSET).bufferedReader().use { it.readText() }

    private fun initialState(): State {
        val cached = cacheFile.takeIf { it.exists() }?.let { parse(it.readText()) }
        return if (cached != null && updated(cached) >= updated(bundled)) {
            State(cached, Source.REMOTE, updated(cached))
        } else {
            State(bundled, Source.BUNDLED, updated(bundled))
        }
    }

    /**
     * Checks [url] for a newer rules file. Safe to call on every launch: it sends the
     * last ETag, so an unchanged file costs one small request. Never throws; the
     * outcome is reported in [State.lastCheck].
     */
    suspend fun refresh(url: String) {
        if (url.isBlank()) return
        val result = withContext(Dispatchers.IO) { runCatching { fetch(url) } }
        val message = result.fold(
            onSuccess = { it },
            onFailure = { "Failed: ${it.message ?: it.javaClass.simpleName}" },
        )
        _state.update { it.copy(lastCheck = message) }
    }

    /** Forget the remote copy and go back to the bundled rules (e.g. after clearing the URL). */
    fun clearRemote() {
        cacheFile.delete()
        etagFile.delete()
        _state.value = State(bundled, Source.BUNDLED, updated(bundled), lastCheck = null)
    }

    private fun fetch(url: String): String {
        require(url.startsWith("https://")) { "URL must start with https://" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            if (cacheFile.exists() && etagFile.exists()) {
                setRequestProperty("If-None-Match", etagFile.readText())
            }
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return "Up to date"
                HttpURLConnection.HTTP_OK -> Unit
                else -> error("HTTP $code")
            }
            val bytes = conn.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            check(bytes.size <= MAX_BYTES) { "File too large" }
            val text = bytes.decodeToString()
            val rules = parse(text) ?: error("Not a valid rules file (schemaVersion $SCHEMA_VERSION expected)")
            if (updated(rules) < updated(bundled)) {
                return "Remote file (${updated(rules)}) is older than the built-in rules (${updated(bundled)}); ignored"
            }
            cacheFile.writeText(text)
            conn.getHeaderField("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
            _state.update { State(rules, Source.REMOTE, updated(rules), it.lastCheck) }
            return "Updated to ${updated(rules)}"
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ASSET = "rules.json"
        /** filter.js understands this schema. Files with another version are rejected. */
        const val SCHEMA_VERSION = 1
        private const val MAX_BYTES = 512 * 1024

        private fun updated(rules: JsonObject): String =
            rules["updated"]?.jsonPrimitive?.content ?: ""

        /** Parses and sanity-checks a rules file; null if it's not usable. */
        fun parse(text: String): JsonObject? = runCatching {
            val obj = Json.parseToJsonElement(text).jsonObject
            check(obj["schemaVersion"]!!.jsonPrimitive.int == SCHEMA_VERSION)
            check(obj["rules"]!!.jsonObject.isNotEmpty())
            obj["units"]!!.jsonObject
            obj["text"]!!.jsonObject
            obj
        }.getOrNull()
    }
}
