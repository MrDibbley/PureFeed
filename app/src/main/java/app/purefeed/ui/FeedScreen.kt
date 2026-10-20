package app.purefeed.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import app.purefeed.R
import app.purefeed.ui.theme.LocalPureFeedColors
import app.purefeed.web.SaveStatus

/** UI state driven by WebView callbacks. */
@Stable
class FeedState {
    var progress by mutableIntStateOf(0)
    var refreshing by mutableStateOf(false)
    /** Hidden items on the current page, per rule id (from filter.js). */
    var hiddenCounts by mutableStateOf(emptyMap<String, Int>())
    /** Rule id → label (from rules.json), for the breakdown. */
    var ruleLabels by mutableStateOf(emptyMap<String, String>())
    /** Tracker requests blocked on the current page. */
    var blockedRequests by mutableIntStateOf(0)
    /** "Saving photo…" / "Saved to …" pill, or null. */
    var saveStatus by mutableStateOf<SaveStatus?>(null)

    // ---- Native frame, from filter.js (__fbm reports via onNav) ----
    /** Which bottom-bar destination Facebook is showing (NavTarget.key), or null. */
    var navActive by mutableStateOf<String?>(null)
    /** Unread counts per destination, read from Facebook's tab labels. */
    var navBadges by mutableStateOf(emptyMap<String, Int>())
    /** True on the home/Feeds screens, where the feed chips apply. */
    var onFeedScreen by mutableStateOf(true)
    /** Selected Feeds chip key, or null when Facebook isn't on Feeds. */
    var feedsChip by mutableStateOf<String?>(null)
}

/** Actions offered by the filter button's menu. */
class FeedActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onToggleDebug: () -> Unit,
    val onDumpDom: () -> Unit,
    val onCheckUpdates: () -> Unit,
    /** Native frame: go to a bottom-bar destination or "search". */
    val onNavigate: (String) -> Unit = {},
    /** Native frame: pick a Feeds chip (all, friends, groups, pages, favourites). */
    val onFeedsChip: (String) -> Unit = {},
)

@Composable
fun FeedScreen(webView: WebView, state: FeedState, debug: Boolean, version: String, frame: Boolean, actions: FeedActions) {
    // Always on: WebView.canGoBack() is checked at press time, because during Facebook's
    // in-page navigations it reports stale values when doUpdateVisitedHistory fires.
    BackHandler(onBack = actions.onBack)

    if (!frame) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            WebArea(webView, state, actions)
            FilterButton(
                state = state,
                debug = debug,
                version = version,
                actions = actions,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
        return
    }

    // State is read inside the pieces that show it (lambdas, small composables), so a new
    // hidden count, badge or load-progress value recomposes only that piece, not the
    // screen around the WebView. (Benchmark: slow-UI-thread frames 490 vs 290 without.)
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        FrameTopBar(
            hidden = { state.hiddenCounts.values.sum() },
            onFilter = { menuOpen = true },
            onSearch = { actions.onNavigate("search") },
            filterMenu = { FilterMenu(menuOpen, { menuOpen = false }, state, debug, version, actions) },
        )
        FeedChipsSlot(state, actions.onFeedsChip)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WebArea(webView, state, actions)
        }
        FrameBottomBar({ state.navActive }, { state.navBadges }) { actions.onNavigate(it.key) }
    }
}

/** The WebView (with pull to refresh), its load progress and the photo-save pill. */
@Composable
private fun WebArea(webView: WebView, state: FeedState, actions: FeedActions) {
    Box(Modifier.fillMaxSize()) {
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

        LoadProgress(state, Modifier.fillMaxWidth().align(Alignment.TopCenter))
        SaveStatusPill(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

/** Feed chips, shown only on the feed screens. Reads its own state. */
@Composable
private fun FeedChipsSlot(state: FeedState, onSelect: (String) -> Unit) {
    if (state.onFeedScreen) FrameFeedChips(state.feedsChip, onSelect)
}

/** Load progress bar. Reads progress here so each % only recomposes this. */
@Composable
private fun LoadProgress(state: FeedState, modifier: Modifier) {
    if (state.progress in 1..99 && !state.refreshing) {
        LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier = modifier,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SaveStatusPill(state: FeedState, modifier: Modifier) {
    state.saveStatus?.let { SavePill(it, modifier) }
}

/** Small translucent pill: filter icon + hidden count. Tap for the menu. */
@Composable
private fun FilterButton(state: FeedState, debug: Boolean, version: String, actions: FeedActions, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val hidden = state.hiddenCounts.values.sum()
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
        FilterMenu(menuOpen, { menuOpen = false }, state, debug, version, actions)
    }
}

/** The filter menu: hidden counts, settings, debug tools, updates and the version. */
@Composable
private fun FilterMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: FeedState,
    debug: Boolean,
    version: String,
    actions: FeedActions,
) {
    val close = onDismiss
    run {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            CountsSummary(state)
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_settings)) },
                onClick = { close(); actions.onOpenSettings() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_debug)) },
                trailingIcon = { Checkbox(checked = debug, onCheckedChange = null) },
                onClick = { close(); actions.onToggleDebug() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_dump)) },
                onClick = { close(); actions.onDumpDom() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_reload)) },
                onClick = { close(); actions.onRefresh() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_check_updates)) },
                onClick = { close(); actions.onCheckUpdates() },
            )
            Text(
                stringResource(R.string.menu_version, version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Feedback while a photo saves: spinner + "Saving photo…", then the result. */
@Composable
private fun SavePill(status: SaveStatus, modifier: Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Box(Modifier.width(10.dp))
            }
            Text(status.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Hidden on this page" breakdown at the top of the menu. */
@Composable
private fun CountsSummary(state: FeedState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(min = 200.dp)) {
        Text(stringResource(R.string.counts_title), style = MaterialTheme.typography.labelLarge)
        val counts = state.hiddenCounts.filterValues { it > 0 }.toList().sortedByDescending { it.second }
        if (counts.isEmpty()) {
            Text(stringResource(R.string.counts_none), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        counts.forEach { (id, n) -> CountRow(state.ruleLabels[id] ?: id, n) }
        CountRow(stringResource(R.string.counts_trackers), state.blockedRequests)
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.bodySmall)
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
