package app.faceblockmobile.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** UI state driven by WebView callbacks. */
@Stable
class FeedState {
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var refreshing by mutableStateOf(false)
}

@Composable
fun FeedScreen(webView: WebView, state: FeedState, onRefresh: () -> Unit) {
    BackHandler(enabled = state.canGoBack) { webView.goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // The WebView outlives this composable (it belongs to the activity), so detach
                // it from any previous container before adding it here.
                (webView.parent as? ViewGroup)?.removeView(webView)
                SwipeRefreshLayout(context).apply {
                    addView(webView, ViewGroup.LayoutParams(MATCH, MATCH))
                    setOnRefreshListener(onRefresh)
                    // Only start a pull-to-refresh when the page is scrolled to the very top.
                    setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
                }
            },
            update = { it.isRefreshing = state.refreshing },
        )

        if (state.progress in 1..99 && !state.refreshing) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
