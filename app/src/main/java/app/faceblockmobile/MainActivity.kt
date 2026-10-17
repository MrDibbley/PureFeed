package app.faceblockmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.faceblockmobile.ui.FeedScreen
import app.faceblockmobile.ui.FeedState
import app.faceblockmobile.ui.theme.FaceBlockTheme
import app.faceblockmobile.web.FeedEvents
import app.faceblockmobile.web.FeedWebView
import app.faceblockmobile.web.PureFeedChromeClient
import app.faceblockmobile.web.PureFeedWebViewClient

class MainActivity : ComponentActivity(), FeedEvents {

    private lateinit var webView: WebView
    private val feedState = FeedState()
    private var rendererGone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        webView = FeedWebView.create(this).apply {
            webViewClient = PureFeedWebViewClient(this@MainActivity)
            webChromeClient = PureFeedChromeClient(this@MainActivity, this@MainActivity)
        }

        // Restores the page and back history when the activity is recreated (e.g. light/dark switch).
        val restored = savedInstanceState?.getBundle(KEY_WEBVIEW)
            ?.let { webView.restoreState(it) != null } == true
        if (!restored) webView.loadUrl(FeedWebView.HOME_URL)

        setContent {
            FaceBlockTheme {
                Surface(Modifier.fillMaxSize()) {
                    FeedScreen(webView, feedState, onRefresh = {
                        feedState.refreshing = true
                        webView.reload()
                    })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) {
            outState.putBundle(KEY_WEBVIEW, Bundle().also { webView.saveState(it) })
        }
    }

    override fun onPause() {
        super.onPause()
        // Write the login cookies to disk now rather than whenever the WebView gets round to it.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---- FeedEvents ----

    override fun onLoadingChanged(loading: Boolean) {
        if (!loading) feedState.refreshing = false
        feedState.canGoBack = webView.canGoBack()
    }

    override fun onProgressChanged(progress: Int) {
        feedState.progress = progress
    }

    override fun onHistoryChanged(view: WebView, url: String?) {
        feedState.canGoBack = view.canGoBack()
    }

    override fun onOpenExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRendererGone() {
        // The WebView can't be reused after its renderer dies; rebuild the activity from scratch.
        rendererGone = true
        recreate()
    }

    private companion object {
        const val KEY_WEBVIEW = "webview"
    }
}
