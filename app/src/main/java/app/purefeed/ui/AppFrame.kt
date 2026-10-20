package app.purefeed.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.purefeed.R
import app.purefeed.ui.theme.LocalPureFeedColors

/**
 * PureFeed's own navigation around the web feed (the redesign): a top bar, feed chips
 * and a bottom bar. Each control drives Facebook's page through filter.js
 * (__fbm.navigate / __fbm.feedsFilter); Facebook's own header and tabs are hidden.
 */

/** Bottom-bar destinations, matched to Facebook's tabs by rules.json "nav". */
enum class NavTarget(val key: String, val label: Int, val icon: Int) {
    Feed("feed", R.string.nav_feed, R.drawable.ic_nav_feed),
    Friends("friends", R.string.nav_friends, R.drawable.ic_nav_friends),
    Messages("messages", R.string.nav_messages, R.drawable.ic_nav_messages),
    Alerts("notifications", R.string.nav_alerts, R.drawable.ic_nav_alerts),
    Menu("menu", R.string.nav_menu, R.drawable.ic_nav_menu),
}

/** Feeds filter chips; keys are rules.json "feeds.filterChips" keys. */
val FEED_CHIPS = listOf(
    "all" to R.string.chip_latest,
    "friends" to R.string.feeds_friends,
    "groups" to R.string.feeds_groups,
    "pages" to R.string.feeds_pages,
    "favourites" to R.string.feeds_favourites,
)

@Composable
fun FrameTopBar(hidden: Int, onFilter: () -> Unit, onSearch: () -> Unit, filterMenu: @Composable () -> Unit) {
    val c = LocalPureFeedColors.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(c.ground).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_logo), contentDescription = null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(26.dp))
        Text(stringResource(R.string.app_name_brand), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.weight(1f))
        Box {
            val description = stringResource(R.string.filter_button, hidden)
            Surface(
                onClick = onFilter,
                shape = RoundedCornerShape(17.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.line),
                modifier = Modifier.height(34.dp).semantics { contentDescription = description },
            ) {
                Row(Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_filter), contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.frame_hidden, hidden), color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            filterMenu()
        }
        IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.frame_search), tint = c.text)
        }
    }
}

@Composable
fun FrameFeedChips(selected: String?, onSelect: (String) -> Unit) {
    val c = LocalPureFeedColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(c.ground).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FEED_CHIPS.forEach { (key, label) ->
                val on = key == selected
                Surface(
                    onClick = { onSelect(key) },
                    shape = RoundedCornerShape(17.dp),
                    color = if (on) c.accent else c.surface,
                    border = if (on) null else BorderStroke(1.dp, c.line),
                    modifier = Modifier.height(34.dp).semantics { this.selected = on },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), color = if (on) c.onAccent else c.text.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        HorizontalDivider(color = c.line.copy(alpha = 0.6f))
    }
}

@Composable
fun FrameBottomBar(active: String?, badges: Map<String, Int>, onSelect: (NavTarget) -> Unit) {
    val c = LocalPureFeedColors.current
    Column(Modifier.background(c.bar)) {
        HorizontalDivider(color = c.line.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            NavTarget.entries.forEach { target ->
                val on = target.key == active
                val badge = badges[target.key] ?: 0
                val label = stringResource(target.label)
                val description = if (badge > 0) stringResource(R.string.nav_with_badge, label, badge) else label
                Box(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        onClick = { onSelect(target) },
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().semantics { contentDescription = description; selected = on },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(
                                Modifier.width(56.dp).height(28.dp).background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(target.icon), contentDescription = null, tint = if (on) c.activeTab else c.muted, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(label, color = if (on) c.activeTab else c.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (badge > 0) {
                        Box(
                            Modifier.align(Alignment.TopCenter).offset(x = 14.dp, y = 8.dp).widthIn(min = 18.dp).height(18.dp)
                                .background(c.badge, CircleShape).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (badge > 99) "99+" else badge.toString(), color = c.onBadge, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
