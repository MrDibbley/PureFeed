package app.purefeed.filter

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `PureFeedBridge` object filter.js talks to. Methods are called on a WebView
 * background thread; results are posted to the main thread.
 *
 * Any frame in the WebView can call these, so they only accept data (counts, a DOM
 * dump we asked for) and never trigger anything else.
 */
class FilterBridge(
    private val onCounts: (Map<String, Int>) -> Unit,
    private val onDomChunk: (requestId: String, index: Int, total: Int, chunk: String) -> Unit,
    private val onFeedsOpened: () -> Unit,
    private val onSaveStarted: () -> Unit,
    private val onNav: (NavReport) -> Unit,
) {

    /** Where Facebook is and its unread counts, for the native frame. */
    data class NavReport(val active: String?, val badges: Map<String, Int>, val onFeedScreen: Boolean, val feedsChip: String?)
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onCounts(countsJson: String) {
        val counts = runCatching {
            Json.parseToJsonElement(countsJson).jsonObject.mapValues { it.value.jsonPrimitive.int }
        }.getOrNull() ?: return
        main.post { onCounts(counts) }
    }

    @JavascriptInterface
    fun onDomChunk(requestId: String, index: Int, total: Int, chunk: String) {
        onDomChunk.invoke(requestId, index, total, chunk)
    }

    /** filter.js has just switched to the Feeds screen via the menu ("Start on Feeds"). */
    @JavascriptInterface
    fun onFeedsOpened() {
        main.post { onFeedsOpened.invoke() }
    }

    /** The user tapped Save on a photo; Facebook takes a few seconds to produce the file. */
    @JavascriptInterface
    fun onSaveStarted() {
        main.post { onSaveStarted.invoke() }
    }

    /** filter.js: {"active": "feed"|…|null, "badges": {"messages": 3}, "feedScreen": true, "chip": "all"|null} */
    @JavascriptInterface
    fun onNav(json: String) {
        val report = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.takeIf { it.isString }?.content
            NavReport(
                active = str("active"),
                badges = o["badges"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }.orEmpty(),
                onFeedScreen = o["feedScreen"]?.jsonPrimitive?.content == "true",
                feedsChip = str("chip"),
            )
        }.getOrNull() ?: return
        main.post { onNav(report) }
    }

    companion object {
        const val NAME = "PureFeedBridge"
    }
}
