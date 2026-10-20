package app.purefeed.filter

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.purefeed.settings.FilterSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Gets assets/filter.js and its config into every Facebook page.
 *
 * Preferred path: a document-start script (androidx.webkit). It runs before
 * Facebook's own scripts on every page load, with the config baked in, so the
 * observer is watching before the first post is rendered and nothing flashes.
 * When the config changes, the script is swapped and the current page is
 * re-configured in place.
 *
 * Fallback (very old WebView): evaluateJavascript after the page commits.
 *
 * Either way, [onNavigation] re-sends the config after every load and SPA
 * navigation. filter.js ignores repeat installs and treats an identical
 * config as "just rescan".
 */
class FilterInjector(private val webView: WebView, private val filterJs: String) {

    private val json = Json { encodeDefaults = true }
    private val useDocumentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var handler: ScriptHandler? = null
    private var configJson: String? = null

    val isConfigured get() = configJson != null

    fun update(rules: JsonObject, settings: FilterSettings) {
        val effective = settings.copy(enabled = FilterSettings.DEFAULT_ENABLED + settings.enabled)
        val config = buildJsonObject {
            put("rules", rules)
            put("settings", json.encodeToJsonElement(effective))
        }.toString()
        if (config == configJson) return
        configJson = config

        if (useDocumentStart) {
            handler?.remove()
            handler = WebViewCompat.addDocumentStartJavaScript(webView, script(config), ALLOWED_ORIGINS)
        }
        // Apply to the page that's already showing.
        webView.evaluateJavascript(script(config), null)
    }

    /** Call on page commit/finish and on SPA history changes. */
    fun onNavigation() {
        val config = configJson ?: return
        webView.evaluateJavascript(script(config), null)
    }

    private fun script(config: String) = "$filterJs\n;window.__fbm && window.__fbm.configure($config);"

    /** Native frame: go to a destination (rules.json "nav" key, e.g. "friends", or "search"). */
    fun navigate(target: String) {
        webView.evaluateJavascript("window.__fbm && window.__fbm.navigate(${json.encodeToString(target)});", null)
    }

    /** Native frame: show Feeds with a chip selected ("all", "friends", …). */
    fun feedsChip(key: String) {
        webView.evaluateJavascript("window.__fbm && window.__fbm.feedsChip(${json.encodeToString(key)});", null)
    }

    fun dumpDom(requestId: String) {
        webView.evaluateJavascript("window.__fbm && window.__fbm.dumpDom(${json.encodeToString(requestId)});", null)
    }

    private companion object {
        val ALLOWED_ORIGINS = setOf("https://*.facebook.com", "https://facebook.com")
    }
}
