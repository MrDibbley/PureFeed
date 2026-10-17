package app.faceblockmobile.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Progress reporting and <input type="file"> support (posting photos).
 *
 * Must be constructed during the activity's onCreate, because it registers an
 * activity-result launcher.
 */
class PureFeedChromeClient(
    activity: ComponentActivity,
    private val events: FeedEvents,
) : WebChromeClient() {

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingCallback ?: return@registerForActivityResult
        pendingCallback = null
        callback.onReceiveValue(parseResult(result.resultCode, result.data))
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgressChanged(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        // A previous chooser that never returned must be cancelled, or the page's input stays stuck.
        pendingCallback?.onReceiveValue(null)
        pendingCallback = filePathCallback

        val intent = fileChooserParams.createIntent().apply {
            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            // Facebook's accept attribute is often "image/*,video/*"; createIntent only
            // keeps the first type, so pass all of them as extra MIME types.
            val types = fileChooserParams.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (types.size > 1) {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
        }
        return try {
            picker.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            pendingCallback = null
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    private fun parseResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        // Multiple selection arrives as ClipData; FileChooserParams.parseResult only handles one file.
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        return FileChooserParams.parseResult(resultCode, data)
    }
}
