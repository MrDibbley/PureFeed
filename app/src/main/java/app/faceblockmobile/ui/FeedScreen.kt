package app.faceblockmobile.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.faceblockmobile.R

/** UI state driven by WebView callbacks. */
@Stable
class FeedState {
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var refreshing by mutableStateOf(false)
    /** Hidden items on the current page, per rule id (from filter.js). */
    var hiddenCounts by mutableStateOf(emptyMap<String, Int>())
}

/** Actions offered by the filter button's menu. */
class FeedActions(
    val onRefresh: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onToggleDebug: () -> Unit,
    val onDumpDom: () -> Unit,
)

@Composable
fun FeedScreen(webView: WebView, state: FeedState, debug: Boolean, actions: FeedActions) {
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
                    setOnRefreshListener(actions.onRefresh)
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

        FilterButton(
            hidden = state.hiddenCounts.values.sum(),
            debug = debug,
            actions = actions,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}

/** Small translucent pill: filter icon + hidden count. Tap for the menu. */
@Composable
private fun FilterButton(hidden: Int, debug: Boolean, actions: FeedActions, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val description = stringResource(R.string.filter_button, hidden)
    Box(modifier) {
        Surface(
            onClick = { menuOpen = true },
            shape = CircleShape,
            color = if (debug) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            contentColor = if (debug) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 3.dp,
            modifier = Modifier.alpha(0.85f).semantics { contentDescription = description },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_filter), contentDescription = null, modifier = Modifier.size(16.dp))
                if (hidden > 0) {
                    Box(Modifier.width(4.dp))
                    Text(hidden.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_settings)) },
                onClick = { menuOpen = false; actions.onOpenSettings() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_debug)) },
                trailingIcon = { Checkbox(checked = debug, onCheckedChange = null) },
                onClick = { menuOpen = false; actions.onToggleDebug() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_dump)) },
                onClick = { menuOpen = false; actions.onDumpDom() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_reload)) },
                onClick = { menuOpen = false; actions.onRefresh() },
            )
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
