package app.faceblockmobile.settings

import kotlinx.serialization.Serializable

/**
 * The user's filter choices. Serialised as-is into the page as `settings` in
 * `__fbm.configure({ rules, settings })`, so field names here are what filter.js reads.
 */
@Serializable
data class FilterSettings(
    /** Rule id (rules.json "rules" keys, plus "keywords" and "muted") → on/off. */
    val enabled: Map<String, Boolean> = DEFAULT_ENABLED,
    val keywords: List<String> = emptyList(),
    val muted: List<String> = emptyList(),
    val customCss: String = "",
    /** Outline matches instead of hiding them. */
    val debug: Boolean = false,
) {
    fun isEnabled(id: String) = enabled[id] ?: DEFAULT_ENABLED[id] ?: false

    fun withEnabled(id: String, on: Boolean) = copy(enabled = enabled + (id to on))

    companion object {
        val DEFAULT_ENABLED = mapOf(
            RuleIds.SPONSORED to true,
            RuleIds.SUGGESTED to true,
            RuleIds.GROUPS to true,
            RuleIds.REELS to true,
            RuleIds.PYMK to true,
            RuleIds.MEMORIES to false,   // detection unverified, see docs/selectors.md
            RuleIds.APP_PROMPTS to true,
            RuleIds.KEYWORDS to true,
            RuleIds.MUTED to true,
        )
    }
}

object RuleIds {
    const val SPONSORED = "sponsored"
    const val SUGGESTED = "suggested"
    const val GROUPS = "groups"
    const val REELS = "reels"
    const val PYMK = "pymk"
    const val MEMORIES = "memories"
    const val MUTED = "muted"
    const val APP_PROMPTS = "appPrompts"
    const val KEYWORDS = "keywords"
}
