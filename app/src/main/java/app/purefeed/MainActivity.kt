package app.purefeed

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.purefeed.block.TrackerBlocker
import app.purefeed.debug.DomDumper
import app.purefeed.filter.FilterBridge
import app.purefeed.filter.FilterInjector
import app.purefeed.filter.RulesRepository
import app.purefeed.settings.BlockSettings
import app.purefeed.settings.FilterSettings
import app.purefeed.settings.SettingsRepository
import app.purefeed.settings.UpdateSettings
import app.purefeed.update.UpdateController
import app.purefeed.ui.FeedActions
import app.purefeed.ui.FeedScreen
import app.purefeed.ui.FeedState
import app.purefeed.ui.BlockInfo
import app.purefeed.ui.RulesInfo
import app.purefeed.ui.SettingsScreen
import app.purefeed.ui.UpdateActions
import app.purefeed.ui.UpdateDialog
import app.purefeed.ui.UpdatesInfo
import app.purefeed.ui.theme.PureFeedTheme
import app.purefeed.web.DownloadHandler
import app.purefeed.web.FeedEvents
import app.purefeed.web.FeedWebView
import app.purefeed.web.PureFeedChromeClient
import app.purefeed.web.PureFeedWebViewClient
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), FeedEvents {

    private lateinit var webView: WebView
    private lateinit var injector: FilterInjector
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var dumper: DomDumper
    private lateinit var rulesRepo: RulesRepository
    private lateinit var chromeClient: PureFeedChromeClient
    private lateinit var updates: UpdateController
    private val blocker = TrackerBlocker()
    private val blockedUpdatePending = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
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
        updates = UpdateController(applicationContext, lifecycleScope, settingsRepo)

        chromeClient = PureFeedChromeClient(this, this)
        webView = FeedWebView.create(this).apply {
            // Transparent, so any area the page doesn't paint (below a short page such as
            // Alerts) shows the themed container behind it (FeedScreen) instead of grey.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = PureFeedWebViewClient(this@MainActivity, blocker)
            webChromeClient = chromeClient
            // "Save" on a photo (and any other download the page starts).
            val downloads = DownloadHandler(applicationContext, this) { feedState.saveStatus = it }
            setDownloadListener(downloads)
            addJavascriptInterface(downloads, DownloadHandler.NAME)
            addJavascriptInterface(
                FilterBridge(
                    onCounts = { feedState.hiddenCounts = it },
                    onDomChunk = { id, index, total, chunk -> dumper.onChunk(id, index, total, chunk, ::shareDump) },
                    onFeedsOpened = { autoFeedsIndex = webView.copyBackForwardList().currentIndex },
                    onSaveStarted = downloads::saveStarted,
                    onNav = { nav ->
                        feedState.navActive = nav.active
                        // Facebook counts new posts on its feed tab ("780 new"); not useful on our Feed button.
                        // Counts are only readable on Facebook's home screen; elsewhere keep the last ones.
                        if (nav.badges.isNotEmpty()) feedState.navBadges = nav.badges - "feed"
                        feedState.onFeedScreen = nav.onFeedScreen
                        feedState.feedsChip = nav.feedsChip
                    },
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
                feedState.ruleLabels = ruleLabels(rules)
                applyFilterConfig()
                if (!pageLoaded) {
                    pageLoaded = true
                    // Restores the page and back history when the activity is recreated (e.g. light/dark switch).
                    val restored = savedWebView?.let { webView.restoreState(it) != null } == true
                    if (restored) {
                        // Facebook lays the restored page out before the new theme settles,
                        // leaving profile pictures misplaced; a reload fixes it.
                        webView.reload()
                    } else {
                        webView.loadUrl(FeedWebView.HOME_URL)
                    }
                }
            }
        }

        val defaultBlocklist = TrackerBlocker.parseList(assets.open("blocklist.txt").bufferedReader().use { it.readText() })
        lifecycleScope.launch {
            settingsRepo.blockSettings.collect { block ->
                blocker.setDomains(if (block.enabled) block.domains ?: defaultBlocklist else emptyList())
            }
        }

        // Once per launch: the remote rules URL (cheap: conditional request with ETag), and
        // a new app release (at most once a day, quiet unless there is one).
        if (savedInstanceState == null) {
            lifecycleScope.launch { rulesRepo.refresh(settingsRepo.rulesUrl.first()) }
            updates.checkOnLaunch()
        }

        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(FilterSettings())
            val rulesState by rulesRepo.state.collectAsStateWithLifecycle()
            val rulesUrl by settingsRepo.rulesUrl.collectAsStateWithLifecycle("")
            val blockSettings by settingsRepo.blockSettings.collectAsStateWithLifecycle(BlockSettings())
            val updatePrefs by settingsRepo.updateSettings.collectAsStateWithLifecycle(UpdateSettings())
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val updateSettings: ((FilterSettings) -> FilterSettings) -> Unit = { transform ->
                lifecycleScope.launch { settingsRepo.update(transform) }
            }
            PureFeedTheme {
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
                            updates = UpdatesInfo(
                                version = updates.installedVersion,
                                autoCheck = updatePrefs.autoCheck,
                                onAutoCheck = { on -> lifecycleScope.launch { settingsRepo.updateUpdateSettings { it.copy(autoCheck = on) } } },
                                onCheckNow = updates::checkNow,
                            ),
                            block = BlockInfo(
                                settings = blockSettings,
                                defaults = defaultBlocklist,
                                onChange = { transform -> lifecycleScope.launch { settingsRepo.updateBlock(transform) } },
                            ),
                            onBack = { showSettings = false },
                        )
                    } else {
                        FeedScreen(
                            webView, feedState, settings.debug, updates.installedVersion, settings.nativeFrame,
                            FeedActions(
                                onBack = ::goBack,
                                onRefresh = {
                                    feedState.refreshing = true
                                    webView.reload()
                                },
                                onOpenSettings = { showSettings = true },
                                onToggleDebug = { updateSettings { it.copy(debug = !it.debug) } },
                                onDumpDom = { injector.dumpDom(dumper.begin()) },
                                onCheckUpdates = updates::checkNow,
                                onNavigate = injector::navigate,
                                onFeedsChip = injector::feedsChip,
                            ),
                        )
                    }
                    UpdateDialog(
                        state = updates.state,
                        installedVersion = updates.installedVersion,
                        actions = UpdateActions(
                            update = updates::update,
                            later = updates::later,
                            skip = updates::skip,
                            dismiss = updates::dismiss,
                            openInstallPermission = updates::openInstallPermission,
                            openReleasePage = { release -> onOpenExternal(release.pageUrl) },
                        ),
                    )
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

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": carry on with a waiting update.
        updates.onResume()
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

    /** Rule id → label from rules.json, for the counter breakdown. */
    private fun ruleLabels(rules: JsonObject): Map<String, String> =
        listOf("rules", "userRules").flatMap { section ->
            rules[section]?.jsonObject.orEmpty().map { (id, rule) ->
                id to (rule.jsonObject["label"]?.jsonPrimitive?.content ?: id)
            }
        }.toMap()

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
        if (chromeClient.exitFullscreen()) return
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
        if (loading) feedState.blockedRequests = 0
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

    override fun onRequestBlocked() {
        // Background thread, possibly many per second: coalesce into one UI update.
        if (blockedUpdatePending.getAndSet(true)) return
        mainHandler.postDelayed({
            blockedUpdatePending.set(false)
            feedState.blockedRequests = blocker.blockedCount
        }, 300)
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
