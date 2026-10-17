package app.faceblockmobile.web

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Callbacks from the WebView up to the activity / UI state. */
interface FeedEvents {
    fun onLoadingChanged(loading: Boolean)
    fun onProgressChanged(progress: Int)
    /** Fired on full loads and on SPA (pushState) navigations. */
    fun onHistoryChanged(view: WebView, url: String?)
    fun onOpenExternal(url: String)
    /** The renderer crashed or was killed; the WebView instance is dead and must be replaced. */
    fun onRendererGone()
}

class PureFeedWebViewClient(private val events: FeedEvents) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Subframe navigations (iframes) are left alone.
        if (!request.isForMainFrame) return false
        return when (val d = UrlPolicy.decide(request.url.toString())) {
            UrlPolicy.Decision.Internal -> false
            UrlPolicy.Decision.Block -> true
            is UrlPolicy.Decision.External -> {
                events.onOpenExternal(d.url)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        events.onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        events.onLoadingChanged(false)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        events.onHistoryChanged(view, url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        events.onRendererGone()
        return true // we handle it, don't kill the app
    }
}
