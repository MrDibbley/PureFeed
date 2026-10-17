package app.faceblockmobile.web

import java.net.URI
import java.net.URLDecoder

/**
 * Decides where a navigation goes. Pure Kotlin (java.net.URI, not android.net.Uri)
 * so it can be unit-tested on the JVM.
 */
object UrlPolicy {

    sealed interface Decision {
        /** Let the WebView load it. */
        data object Internal : Decision

        /** Hand it to another app (browser, mail, dialer). */
        data class External(val url: String) : Decision

        /** Swallow it: app deep links such as fb:// or intent:// that would open the Facebook app. */
        data object Block : Decision
    }

    /** Domains that stay inside the WebView. Subdomains are included. */
    private val internalDomains = listOf(
        "facebook.com",
        "fb.com",
        "fbcdn.net",   // images and video
        "fbsbx.com",   // attachments
        "facebook.net",
    )

    /** Facebook's outbound link redirectors. The real target is in the `u` query parameter. */
    private val linkShimHosts = setOf("l.facebook.com", "lm.facebook.com")

    private val externalSchemes = setOf("mailto", "tel", "sms", "geo")

    fun decide(url: String): Decision {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Decision.Block
        val scheme = uri.scheme?.lowercase() ?: return Decision.Internal

        if (scheme in externalSchemes) return Decision.External(url)
        if (scheme != "http" && scheme != "https") return Decision.Block

        val host = uri.host?.lowercase() ?: return Decision.Block

        if (host in linkShimHosts) {
            // Skip the redirector: open the destination directly (also drops FB's click tracking).
            val target = queryParam(uri, "u")
            return if (target != null && decide(target) !is Decision.Internal) {
                Decision.External(target)
            } else if (target != null) {
                Decision.Internal
            } else {
                Decision.External(url)
            }
        }

        return if (isInternalHost(host)) Decision.Internal else Decision.External(url)
    }

    fun isInternalHost(host: String): Boolean =
        internalDomains.any { host == it || host.endsWith(".$it") }

    private fun queryParam(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == name && it.size == 2 }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
}
