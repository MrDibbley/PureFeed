package app.faceblockmobile.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Progress reporting, <input type="file"> support (posting photos) and full-screen video.
 *
 * Must be constructed during the activity's onCreate, because it registers an
 * activity-result launcher.
 */
class PureFeedChromeClient(
    private val activity: ComponentActivity,
    private val events: FeedEvents,
) : WebChromeClient() {

    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: CustomViewCallback? = null

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

    // ---- Full-screen video: the page hands us a view to show over everything ----

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        decor().addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        systemBars().apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onHideCustomView() {
        val view = fullscreenView ?: return
        decor().removeView(view)
        systemBars().show(WindowInsetsCompat.Type.systemBars())
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    /** Leaves full-screen video if it's showing. Returns true if it was (Back consumed). */
    fun exitFullscreen(): Boolean {
        if (fullscreenView == null) return false
        onHideCustomView()
        return true
    }

    private fun decor() = activity.window.decorView as FrameLayout

    private fun systemBars() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

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
