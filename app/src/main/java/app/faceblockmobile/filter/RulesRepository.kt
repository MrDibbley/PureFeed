package app.faceblockmobile.filter

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Supplies rules.json. Phase 2: the copy bundled in the APK (built from rules/rules.json).
 * Phase 3 adds a remote copy, cached, with this bundled one as the fallback.
 */
class RulesRepository(private val context: Context) {

    fun bundled(): JsonObject {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return requireNotNull(parse(text)) { "Bundled $ASSET is invalid" }
    }

    companion object {
        const val ASSET = "rules.json"
        /** filter.js understands this schema. Remote files with another major version are rejected. */
        const val SCHEMA_VERSION = 1

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
