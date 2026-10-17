package app.faceblockmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.faceblockmobile.R
import app.faceblockmobile.settings.FilterSettings
import app.faceblockmobile.settings.RuleIds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: FilterSettings,
    onChange: ((FilterSettings) -> FilterSettings) -> Unit,
    onBack: () -> Unit,
) {
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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(R.string.section_rules) }
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
            item {
                AddEntryField { word ->
                    onChange { s -> if (s.keywords.any { it.equals(word, ignoreCase = true) }) s else s.copy(keywords = s.keywords + word) }
                }
            }
            items(settings.keywords, key = { it }) { word ->
                ListItem(
                    headlineContent = { Text(word) },
                    trailingContent = {
                        IconButton(onClick = { onChange { s -> s.copy(keywords = s.keywords - word) } }) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove_keyword, word))
                        }
                    },
                )
            }

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
    BuiltInRule(RuleIds.APP_PROMPTS, R.string.rule_app_prompts, R.string.rule_app_prompts_desc),
)

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

@Composable
private fun AddEntryField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        val word = text.trim()
        if (word.isNotEmpty()) onAdd(word)
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
            placeholder = { Text(stringResource(R.string.keyword_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) }
    }
}
