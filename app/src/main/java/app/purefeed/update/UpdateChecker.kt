package app.purefeed.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.net.URL

/** A published GitHub release that has an APK attached. */
data class Release(
    val version: String,      // "1.0.2" (tag without the leading v)
    val pageUrl: String,      // the release page on github.com
    val notes: String,        // release notes (Markdown)
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String?,     // SHA256SUMS.txt, if attached
)

/**
 * Asks GitHub for the latest release of this app. Only api.github.com is contacted
 * (it sees the phone's IP address; nothing else is sent).
 */
object UpdateChecker {

    const val REPO = "MrDibbley/PureFeed"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** The latest release, or null if there's none with an APK. Throws on network errors. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            check(conn.responseCode == 200) { "GitHub returned HTTP ${conn.responseCode}" }
            parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Parses GitHub's release JSON. Pure, so it's unit-tested. */
    fun parseRelease(json: String): Release? {
        val obj = Json.parseToJsonElement(json).jsonObject
        val assets = obj["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
        val apk = assets.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return null
        val sums = assets.firstOrNull { it.str("name") == "SHA256SUMS.txt" }
        return Release(
            version = obj.str("tag_name")?.removePrefix("v") ?: return null,
            pageUrl = obj.str("html_url") ?: "https://github.com/$REPO/releases",
            notes = obj.str("body").orEmpty(),
            apkName = apk.str("name")!!,
            apkUrl = apk.str("browser_download_url") ?: return null,
            apkSize = apk["size"]?.jsonPrimitive?.long ?: 0,
            sumsUrl = sums?.str("browser_download_url"),
        )
    }

    /**
     * Compares dotted versions numerically: "1.0.10" > "1.0.9". Anything after a '-'
     * ("1.0.1-debug", "1.0.0-test") is ignored, so a debug build of 1.0.1 equals 1.0.1.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(release: Release, installed: String) = compareVersions(release.version, installed) > 0

    /** The SHA-256 listed for [fileName] in a SHA256SUMS.txt ("<hex>  <name>" lines), or null. */
    fun shaFor(sums: String, fileName: String): String? =
        sums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
