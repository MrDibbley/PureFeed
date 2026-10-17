package app.faceblockmobile.filter

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `FaceBlockBridge` object filter.js talks to. Methods are called on a WebView
 * background thread; results are posted to the main thread.
 *
 * Any frame in the WebView can call these, so they only accept data (counts, a DOM
 * dump we asked for) and never trigger anything else.
 */
class FilterBridge(
    private val onCounts: (Map<String, Int>) -> Unit,
    private val onDomChunk: (requestId: String, index: Int, total: Int, chunk: String) -> Unit,
) {
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

    companion object {
        const val NAME = "FaceBlockBridge"
    }
}
