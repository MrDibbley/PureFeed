package app.purefeed.block

import app.purefeed.web.UrlPolicy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocks requests to third-party tracker/analytics domains, from inside
 * WebViewClient.shouldInterceptRequest (a background thread, hence the volatile
 * set and the atomic counter).
 *
 * Note: Facebook serves its ads and its own telemetry first-party, and
 * m.facebook.com's Content Security Policy already stops its pages loading
 * third-party resources (verified 2026-10-17: every request on the feed was to
 * facebook.com / fbcdn.net, and even unlisted third-party images are refused by
 * CSP). So this is a backstop for embedded content without that policy. The DOM
 * filter (filter.js) does the real ad blocking. Blocks are logged under the
 * "PureFeed" tag: adb logcat -s PureFeed
 */
class TrackerBlocker {

    @Volatile private var domains: Set<String> = emptySet()
    private val blocked = AtomicInteger()

    /** Number of requests blocked since the last [resetCount] (i.e. on this page). */
    val blockedCount get() = blocked.get()

    fun setDomains(list: List<String>) {
        domains = list.mapNotNull(::normalise).toSet()
    }

    fun resetCount() = blocked.set(0)

    /** True if a request to [host] should be blocked. Counts it if so. */
    fun shouldBlock(host: String?): Boolean {
        if (!matches(domains, host)) return false
        blocked.incrementAndGet()
        return true
    }

    companion object {
        /**
         * host matches if it is a listed domain or a subdomain of one. Facebook's own
         * hosts never match, so a bad list entry can't break the site.
         */
        fun matches(domains: Set<String>, host: String?): Boolean {
            if (host.isNullOrEmpty() || domains.isEmpty()) return false
            val h = host.lowercase().trimEnd('.')
            if (UrlPolicy.isInternalHost(h)) return false
            var candidate = h
            while (true) {
                if (candidate in domains) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) return false
                candidate = candidate.substring(dot + 1)
            }
        }

        /** Parses blocklist.txt: one domain per line, # comments, blank lines ignored. */
        fun parseList(text: String): List<String> =
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull(::normalise)
                .distinct()
                .toList()

        /** "https://www.Example.com/x" or "example.com." → "example.com"; null if not a domain. */
        fun normalise(entry: String): String? {
            val d = entry.trim().lowercase()
                .removePrefix("http://").removePrefix("https://")
                .substringBefore('/').trimEnd('.')
                .removePrefix("*.")
            return d.takeIf { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
        }
    }
}
