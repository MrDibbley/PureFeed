package app.purefeed.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object FeedWebView {

    const val HOME_URL = "https://m.facebook.com/"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // Keep target=_blank links in this window so they pass through shouldOverrideUrlLoading.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = true   // needed for photos picked via the file chooser
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = mobileChromeUserAgent(context)
        }

        // Follow the system theme. With targetSdk 33+ the WebView reports prefers-color-scheme
        // from the activity theme (see values-night/themes.xml); algorithmic darkening is only
        // a fallback for pages that have no dark styles of their own.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }

        // Client hints (sec-ch-ua) list "Android WebView" as a brand, which tells Facebook this
        // is an app rather than Chrome (seen in the 2026-10-17 header capture). Report
        // "Google Chrome" instead so they agree with the user agent. Model and platform version
        // stay empty, matching the generic "Android 10; K" user agent.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val metadata = WebSettingsCompat.getUserAgentMetadata(settings)
            val brands = metadata.brandVersionList.map { brand ->
                if (brand.brand == "Android WebView") {
                    UserAgentMetadata.BrandVersion.Builder(brand).setBrand("Google Chrome").build()
                } else {
                    brand
                }
            }
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(metadata)
                    .setBrandVersionList(brands)
                    .setModel("")
                    .setPlatformVersion("")
                    .build(),
            )
        }

        val webView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        isVerticalScrollBarEnabled = false
    }

    /**
     * The device's own WebView user agent, minus the bits that mark it as a WebView
     * ("; wv" and "Version/4.0"). The result is the same string mobile Chrome sends
     * on this device, with a current Chrome version, so Facebook serves the
     * same markup we see when researching selectors in Chrome's mobile emulation.
     */
    fun mobileChromeUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+(\.\d+)*\s"""), "")
}
