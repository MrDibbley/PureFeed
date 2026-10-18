package app.purefeed.settings

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
    /** Switch the ranked home feed to Facebook's chronological "Feeds" screen on each load. */
    val startOnFeeds: Boolean = true,
    /** Feeds filter chip: a key of rules.json "feeds.filterChips" (all, favourites, friends, groups, pages). */
    val feedsFilter: String = "all",
    /** Refuse video play() unless the user just tapped (filter.js installAutoplayBlock). */
    val noAutoplay: Boolean = true,
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
            RuleIds.STORIES to true,
            RuleIds.MEMORIES to false,   // detection unverified, see docs/selectors.md
            RuleIds.APP_PROMPTS to true,
            RuleIds.KEYWORDS to true,
            RuleIds.MUTED to true,
        )
    }
}

/** Tracker blocking (TrackerBlocker). Not sent to the page. */
@Serializable
data class BlockSettings(
    val enabled: Boolean = true,
    /** null = use assets/blocklist.txt; otherwise the user's edited list. */
    val domains: List<String>? = null,
)

/** App update checks (UpdateController). Not sent to the page. */
@Serializable
data class UpdateSettings(
    val autoCheck: Boolean = true,
    val lastCheckMs: Long = 0,
    /** A version the user chose to skip; automatic checks stay quiet about it. */
    val skippedVersion: String? = null,
)

object RuleIds {
    const val SPONSORED = "sponsored"
    const val SUGGESTED = "suggested"
    const val GROUPS = "groups"
    const val REELS = "reels"
    const val PYMK = "pymk"
    const val STORIES = "stories"
    const val MEMORIES = "memories"
    const val MUTED = "muted"
    const val APP_PROMPTS = "appPrompts"
    const val KEYWORDS = "keywords"
}
