package app.purefeed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.purefeed.R
import app.purefeed.block.TrackerBlocker
import app.purefeed.filter.RulesRepository
import app.purefeed.settings.BlockSettings
import app.purefeed.settings.FilterSettings
import app.purefeed.settings.RuleIds

/** Everything the settings screen shows besides [FilterSettings]. */
class RulesInfo(
    val state: RulesRepository.State,
    val url: String,
    val onSaveUrl: (String) -> Unit,
    val onCheckNow: () -> Unit,
)

/** Tracker blocking settings plus the bundled default list. */
class BlockInfo(
    val settings: BlockSettings,
    val defaults: List<String>,
    val onChange: ((BlockSettings) -> BlockSettings) -> Unit,
) {
    val domains get() = settings.domains ?: defaults
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: FilterSettings,
    onChange: ((FilterSettings) -> FilterSettings) -> Unit,
    rules: RulesInfo,
    block: BlockInfo,
    onBack: () -> Unit,
) {
    var showBlocklist by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding()) {
            item { SectionHeader(R.string.section_feed) }
            item {
                RuleSwitch(R.string.start_on_feeds, R.string.start_on_feeds_desc, settings.startOnFeeds) { on ->
                    onChange { it.copy(startOnFeeds = on) }
                }
            }
            if (settings.startOnFeeds) {
                item { FeedsFilterChips(settings.feedsFilter) { key -> onChange { it.copy(feedsFilter = key) } } }
            }
            item {
                RuleSwitch(R.string.no_autoplay, R.string.no_autoplay_desc, settings.noAutoplay) { on ->
                    onChange { it.copy(noAutoplay = on) }
                }
            }

            item { HorizontalDivider(); SectionHeader(R.string.section_rules) }
            items(BUILT_IN_RULES, key = { it.id }) { rule ->
                RuleSwitch(rule.title, rule.description, settings.isEnabled(rule.id)) { on ->
                    onChange { it.withEnabled(rule.id, on) }
                }
            }

            item { HorizontalDivider(); SectionHeader(R.string.section_keywords) }
            item {
                RuleSwitch(R.string.rule_keywords, R.string.rule_keywords_desc, settings.isEnabled(RuleIds.KEYWORDS)) { on ->
                    onChange { it.withEnabled(RuleIds.KEYWORDS, on) }
                }
            }
            listEditor(
                key = "keywords",
                entries = settings.keywords,
                hint = R.string.keyword_hint,
                onAdd = { word -> onChange { it.copy(keywords = it.keywords.addUnique(word)) } },
                onRemove = { word -> onChange { it.copy(keywords = it.keywords - word) } },
            )

            item { HorizontalDivider(); SectionHeader(R.string.section_muted) }
            item {
                RuleSwitch(R.string.rule_muted, R.string.rule_muted_desc, settings.isEnabled(RuleIds.MUTED)) { on ->
                    onChange { it.withEnabled(RuleIds.MUTED, on) }
                }
            }
            listEditor(
                key = "muted",
                entries = settings.muted,
                hint = R.string.muted_hint,
                onAdd = { name -> onChange { it.copy(muted = it.muted.addUnique(name)) } },
                onRemove = { name -> onChange { it.copy(muted = it.muted - name) } },
            )

            item { HorizontalDivider(); SectionHeader(R.string.section_block) }
            item {
                RuleSwitch(R.string.block_enabled, R.string.block_enabled_desc, block.settings.enabled) { on ->
                    block.onChange { it.copy(enabled = on) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { showBlocklist = !showBlocklist }) {
                        Text(if (showBlocklist) stringResource(R.string.block_hide) else stringResource(R.string.block_show, block.domains.size))
                    }
                    TextButton(onClick = { block.onChange { it.copy(domains = null) } }, enabled = block.settings.domains != null) {
                        Text(stringResource(R.string.block_reset))
                    }
                }
            }
            if (showBlocklist) {
                listEditor(
                    key = "block",
                    entries = block.domains,
                    hint = R.string.block_hint,
                    onAdd = { entry ->
                        TrackerBlocker.normalise(entry)?.let { d -> block.onChange { it.copy(domains = block.domains.addUnique(d)) } }
                    },
                    onRemove = { d -> block.onChange { it.copy(domains = block.domains - d) } },
                )
            }

            item { HorizontalDivider(); SectionHeader(R.string.section_css) }
            item { CustomCssEditor(settings.customCss) { css -> onChange { it.copy(customCss = css) } } }

            item { HorizontalDivider(); SectionHeader(R.string.section_rules_source) }
            item { RulesSourceEditor(rules) }

            item { HorizontalDivider(); SectionHeader(R.string.section_debug) }
            item {
                RuleSwitch(R.string.debug_mode, R.string.debug_mode_desc, settings.debug) { on ->
                    onChange { it.copy(debug = on) }
                }
            }
        }
    }
}

/** A switch for one rules.json rule. */
private class BuiltInRule(val id: String, val title: Int, val description: Int)

private val BUILT_IN_RULES = listOf(
    BuiltInRule(RuleIds.SPONSORED, R.string.rule_sponsored, R.string.rule_sponsored_desc),
    BuiltInRule(RuleIds.SUGGESTED, R.string.rule_suggested, R.string.rule_suggested_desc),
    BuiltInRule(RuleIds.GROUPS, R.string.rule_groups, R.string.rule_groups_desc),
    BuiltInRule(RuleIds.REELS, R.string.rule_reels, R.string.rule_reels_desc),
    BuiltInRule(RuleIds.PYMK, R.string.rule_pymk, R.string.rule_pymk_desc),
    BuiltInRule(RuleIds.STORIES, R.string.rule_stories, R.string.rule_stories_desc),
    BuiltInRule(RuleIds.MEMORIES, R.string.rule_memories, R.string.rule_memories_desc),
    BuiltInRule(RuleIds.APP_PROMPTS, R.string.rule_app_prompts, R.string.rule_app_prompts_desc),
)

private fun List<String>.addUnique(entry: String) =
    if (any { it.equals(entry, ignoreCase = true) }) this else this + entry

private val FEEDS_FILTERS = listOf(
    "all" to R.string.feeds_all,
    "friends" to R.string.feeds_friends,
    "favourites" to R.string.feeds_favourites,
    "groups" to R.string.feeds_groups,
    "pages" to R.string.feeds_pages,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedsFilterChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FEEDS_FILTERS.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RuleSwitch(title: Int, description: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An "add" field followed by the current entries, each with a remove button. */
private fun LazyListScope.listEditor(
    key: String,
    entries: List<String>,
    hint: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    item(key = "$key-add") { AddEntryField(hint, onAdd) }
    items(entries, key = { "$key-$it" }) { entry ->
        ListItem(
            headlineContent = { Text(entry) },
            trailingContent = {
                IconButton(onClick = { onRemove(entry) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove_entry, entry))
                }
            },
        )
    }
}

@Composable
private fun AddEntryField(hint: Int, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        val entry = text.trim()
        if (entry.isNotEmpty()) onAdd(entry)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) }
    }
}

/** Multi-line CSS editor. Applied on Save, not per keystroke (each change re-scans the page). */
@Composable
private fun CustomCssEditor(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Text(
        stringResource(R.string.css_desc),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.css_hint), fontFamily = FontFamily.Monospace) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 16.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { onSave(text) }, enabled = text != saved) { Text(stringResource(R.string.save)) }
        TextButton(onClick = { text = saved }, enabled = text != saved) { Text(stringResource(R.string.revert)) }
    }
}

@Composable
private fun RulesSourceEditor(rules: RulesInfo) {
    var url by rememberSaveable(rules.url) { mutableStateOf(rules.url) }
    val state = rules.state
    val source = stringResource(
        if (state.source == RulesRepository.Source.REMOTE) R.string.rules_source_remote else R.string.rules_source_bundled
    )
    Text(
        stringResource(R.string.rules_using, source, state.updated),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    state.lastCheck?.let {
        Text(
            stringResource(R.string.rules_last_check, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.rules_url)) },
        placeholder = { Text("https://raw.githubusercontent.com/…/rules/rules.json") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { rules.onSaveUrl(url) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { rules.onSaveUrl(url) }, enabled = url.trim() != rules.url) {
            Text(stringResource(R.string.save))
        }
        OutlinedButton(onClick = rules.onCheckNow, enabled = rules.url.isNotBlank()) {
            Text(stringResource(R.string.rules_check_now))
        }
    }
}
