package app.faceblockmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.faceblockmobile.debug.DomDumper
import app.faceblockmobile.filter.FilterBridge
import app.faceblockmobile.filter.FilterInjector
import app.faceblockmobile.filter.RulesRepository
import app.faceblockmobile.settings.FilterSettings
import app.faceblockmobile.settings.SettingsRepository
import app.faceblockmobile.ui.FeedActions
import app.faceblockmobile.ui.FeedScreen
import app.faceblockmobile.ui.FeedState
import app.faceblockmobile.ui.RulesInfo
import app.faceblockmobile.ui.SettingsScreen
import app.faceblockmobile.ui.theme.FaceBlockTheme
import app.faceblockmobile.web.FeedEvents
import app.faceblockmobile.web.FeedWebView
import app.faceblockmobile.web.PureFeedChromeClient
import app.faceblockmobile.web.PureFeedWebViewClient
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File

class MainActivity : ComponentActivity(), FeedEvents {

    private lateinit var webView: WebView
    private lateinit var injector: FilterInjector
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var dumper: DomDumper
    private lateinit var rulesRepo: RulesRepository
    private val feedState = FeedState()
    private var rendererGone = false
    private var pageLoaded = false
    /** History index of a Feeds screen that filter.js opened via the menu, or -1. */
    private var autoFeedsIndex = -1
    /** True while loading the ranked feed after Back from an auto-opened Feeds. */
    private var loadingRankedHome = false
    private var currentRules: JsonObject? = null
    private var currentSettings: FilterSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug builds expose the WebView to chrome://inspect over USB, for selector research
        // against the real Android markup.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        settingsRepo = SettingsRepository(applicationContext)
        dumper = DomDumper(applicationContext)
        rulesRepo = RulesRepository(applicationContext)

        webView = FeedWebView.create(this).apply {
            webViewClient = PureFeedWebViewClient(this@MainActivity)
            webChromeClient = PureFeedChromeClient(this@MainActivity, this@MainActivity)
            addJavascriptInterface(
                FilterBridge(
                    onCounts = { feedState.hiddenCounts = it },
                    onDomChunk = { id, index, total, chunk -> dumper.onChunk(id, index, total, chunk, ::shareDump) },
                    onFeedsOpened = { autoFeedsIndex = webView.copyBackForwardList().currentIndex },
                ),
                FilterBridge.NAME,
            )
        }
        injector = FilterInjector(webView, assets.open("filter.js").bufferedReader().use { it.readText() })

        // The first page load waits for the first settings value, so the filter is
        // already registered when Facebook starts rendering. After that, any change to
        // the settings or the rules (e.g. a remote update) re-configures the page live.
        val savedWebView = savedInstanceState?.getBundle(KEY_WEBVIEW)
        lifecycleScope.launch {
            combine(rulesRepo.state.map { it.rules }.distinctUntilChanged(), settingsRepo.settings, ::Pair).collect { (rules, settings) ->
                currentRules = rules
                currentSettings = settings
                applyFilterConfig()
                if (!pageLoaded) {
                    pageLoaded = true
                    // Restores the page and back history when the activity is recreated (e.g. light/dark switch).
                    val restored = savedWebView?.let { webView.restoreState(it) != null } == true
                    if (!restored) webView.loadUrl(FeedWebView.HOME_URL)
                }
            }
        }

        // Check the remote rules URL once per launch (cheap: conditional request with ETag).
        if (savedInstanceState == null) {
            lifecycleScope.launch { rulesRepo.refresh(settingsRepo.rulesUrl.first()) }
        }

        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(FilterSettings())
            val rulesState by rulesRepo.state.collectAsStateWithLifecycle()
            val rulesUrl by settingsRepo.rulesUrl.collectAsStateWithLifecycle("")
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val updateSettings: ((FilterSettings) -> FilterSettings) -> Unit = { transform ->
                lifecycleScope.launch { settingsRepo.update(transform) }
            }
            FaceBlockTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (showSettings) {
                        SettingsScreen(
                            settings,
                            onChange = updateSettings,
                            rules = RulesInfo(
                                state = rulesState,
                                url = rulesUrl,
                                onSaveUrl = { url -> lifecycleScope.launch { saveRulesUrl(url) } },
                                onCheckNow = { lifecycleScope.launch { rulesRepo.refresh(rulesUrl) } },
                            ),
                            onBack = { showSettings = false },
                        )
                    } else {
                        FeedScreen(
                            webView, feedState, settings.debug,
                            FeedActions(
                                onBack = ::goBack,
                                onRefresh = {
                                    feedState.refreshing = true
                                    webView.reload()
                                },
                                onOpenSettings = { showSettings = true },
                                onToggleDebug = { updateSettings { it.copy(debug = !it.debug) } },
                                onDumpDom = { injector.dumpDom(dumper.begin()) },
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) {
            outState.putBundle(KEY_WEBVIEW, Bundle().also { webView.saveState(it) })
        }
    }

    override fun onPause() {
        super.onPause()
        // Write the login cookies to disk now rather than whenever the WebView gets round to it.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun applyFilterConfig() {
        val rules = currentRules ?: return
        val settings = currentSettings ?: return
        injector.update(rules, if (loadingRankedHome) settings.copy(startOnFeeds = false) else settings)
    }

    /**
     * WebView back, except from a Feeds screen that "Start on Feeds" opened: there
     * the user expects the ranked feed, but the history can't get them there.
     * Chromium's history intervention marks entries created by script without a
     * real tap (our automatic menu and Feeds taps) as skippable, so canGoBack() is
     * false and goBackOrForward(-2) is refused, and Facebook's router turns a
     * page-side history.go(-2) into the menu. So reload the home page with Start on
     * Feeds held off for that one load, then drop the history so the next Back
     * leaves the app.
     */
    private fun goBack() {
        val history = webView.copyBackForwardList()
        val current = history.currentIndex
        val fromAutoFeeds = autoFeedsIndex >= 0 && current >= 2 &&
            history.getItemAtIndex(current - 1).url.contains("/bookmarks")
        when {
            fromAutoFeeds -> {
                autoFeedsIndex = -1
                loadingRankedHome = true
                applyFilterConfig()
                webView.loadUrl(FeedWebView.HOME_URL)
            }
            webView.canGoBack() -> webView.goBack()
            // Nothing to go back to: behave like the system Back on a launcher activity.
            else -> moveTaskToBack(true)
        }
    }

    private suspend fun saveRulesUrl(url: String) {
        settingsRepo.setRulesUrl(url)
        if (url.isBlank()) rulesRepo.clearRemote() else rulesRepo.refresh(url.trim())
    }

    private fun shareDump(file: File) {
        Toast.makeText(this, getString(R.string.dump_saved, file.name), Toast.LENGTH_SHORT).show()
        startActivity(dumper.shareIntent(file))
    }

    // ---- FeedEvents ----

    override fun onLoadingChanged(loading: Boolean) {
        if (!loading) {
            feedState.refreshing = false
            if (loadingRankedHome) {
                // The ranked feed is up. Restore the real settings for future loads (this
                // page already had its one Start on Feeds chance) and forget the history.
                loadingRankedHome = false
                webView.clearHistory()
                applyFilterConfig()
            }
            injector.onNavigation()
        }
    }

    override fun onProgressChanged(progress: Int) {
        feedState.progress = progress
    }

    override fun onHistoryChanged(view: WebView, url: String?) {
        // SPA navigation: Facebook swapped the screen without a page load.
        injector.onNavigation()
    }

    override fun onOpenExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRendererGone() {
        // The WebView can't be reused after its renderer dies; rebuild the activity from scratch.
        rendererGone = true
        recreate()
    }

    private companion object {
        const val KEY_WEBVIEW = "webview"
    }
}
