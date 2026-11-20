package io.github.aarul5.mangaloud

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URLEncoder
import kotlin.concurrent.thread

enum class Screen { HOME, BROWSER, SETTINGS }

/** Everything the screens show, as Compose state the activity updates. */
class UiState {
    var screen by mutableStateOf(Screen.HOME)
    var homeTab by mutableStateOf(HomeTab.HOME)
    var settingsPage by mutableStateOf<SettingsPage?>(null)
    /** The screen to go back to from Settings. */
    var settingsFrom by mutableStateOf(Screen.HOME)

    /** Pages opened inside Settings › Voice, last on top; empty shows the voice list. */
    var voiceStack by mutableStateOf(listOf<VoiceRoute>())

    /** A series' voices opened straight from the browser or Home: back returns there. */
    var voiceEntry by mutableStateOf<Screen?>(null)

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var blockedJump by mutableStateOf<String?>(null)

    var readerState by mutableStateOf(ReadAloud.State.IDLE)
    var status by mutableStateOf("")
    var line by mutableStateOf<Bubble?>(null)
    var details by mutableStateOf<String?>(null)
    var showLineText by mutableStateOf(true)
    var showDetails by mutableStateOf(true)

    /** Seconds until the next chapter opens by itself, or null when it is not about to. */
    var nextCountdown by mutableStateOf<Int?>(null)

    /** Bumped when bookmarks or history change, so lists read them again. */
    var libraryVersion by mutableIntStateOf(0)
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var dynamicColor by mutableStateOf(false)
}

class MainActivity : ComponentActivity(), BrowserEvents, ReadAloud.Listener {
    private lateinit var settings: Settings
    private lateinit var adBlocker: AdBlocker
    private lateinit var library: Library
    private lateinit var readAloud: ReadAloud
    private lateinit var webView: WebView
    private val ui = UiState()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The next chapter to open when the countdown ends. */
    private var nextChapterUrl: String? = null

    /** The page being opened came from the countdown, so it reads even with auto read off. */
    private var readNextOnLoad = false

    /** The page reading started by itself for, so a second "finished" for it does not start it again. */
    private var autoStartedFor: String? = null

    /** A page put back after the app was closed by the system: not opened by the reader, so no auto read. */
    private var restoredPage = false

    private val countdownTick = object : Runnable {
        override fun run() {
            val left = (ui.nextCountdown ?: return) - 1
            if (left > 0) {
                ui.nextCountdown = left
                mainHandler.postDelayed(this, 1000)
                return
            }
            ui.nextCountdown = null
            val next = nextChapterUrl ?: return
            nextChapterUrl = null
            readNextOnLoad = true
            webView.loadUrl(next)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        adBlocker = AdBlocker(this)
        library = Library(this)
        readDisplaySettings()

        webView = WebView(this)
        setUpWebView()
        readAloud = ReadAloud(this, PageScript(webView), settings, this)
        watchManualScrolling()
        downloadBlocklistOnFirstRun()

        setContent {
            AppTheme(ui.themeMode, ui.dynamicColor) { Screens() }
        }

        val savedPage = savedInstanceState?.getBundle(STATE_WEB)
        val link = linkFrom(intent)
        when {
            savedPage != null -> {
                webView.restoreState(savedPage)
                restoredPage = true
                ui.url = webView.url.orEmpty()
                ui.screen = runCatching { Screen.valueOf(savedInstanceState?.getString(STATE_SCREEN)!!) }.getOrDefault(Screen.BROWSER)
            }
            link != null -> openUrl(link)
        }
        if (savedInstanceState == null) addRelayFrom(intent)
    }

    @androidx.compose.runtime.Composable
    private fun Screens() {
        when (ui.screen) {
            Screen.HOME -> {
                BackHandler(enabled = ui.homeTab != HomeTab.HOME) { ui.homeTab = HomeTab.HOME }
                HomeScreen(ui.homeTab, { ui.homeTab = it }, library, ui.libraryVersion, homeActions)
            }
            Screen.BROWSER -> {
                BackHandler { browserActions.back() }
                val bookmarked = ui.libraryVersion.let { library.isBookmarked(ui.url) }
                BrowserScreen(ui, webView, bookmarked, browserActions)
            }
            Screen.SETTINGS -> {
                BackHandler { settingsActions.back() }
                SettingsScreen(ui.settingsPage, settings, settingsActions, ui.voiceStack.lastOrNull() ?: VoiceRoute.Main, voiceActions)
            }
        }
    }

    // Actions from the screens

    private val homeActions = object : HomeActions {
        override fun open(url: String) = openUrl(url)
        override fun openTyped(text: String) = openTyped(text, this@MainActivity::openUrl)
        override fun openSettings() = showSettings()
        override fun renameBookmark(url: String, title: String) = library.renameBookmark(url, title).also { libraryChanged() }
        override fun removeBookmark(url: String) = library.removeBookmark(url).also { libraryChanged() }
        override fun removeVisit(url: String) = library.removeVisit(url).also { libraryChanged() }
        override fun clearHistory() = library.clearHistory().also { libraryChanged() }
        override fun seriesVoices(url: String, series: String) = openSeriesVoices(url, series)
    }

    private val voiceActions = object : VoiceActions {
        override fun open(route: VoiceRoute) {
            ui.voiceStack = ui.voiceStack + route
        }

        override fun openLink(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No app can open $url") }
        }

        override fun pasteText(): String {
            val clipboard = getSystemService(ClipboardManager::class.java)
            return clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
        }

        override fun pcCharacterVoices(seriesKey: String?) {
            if (seriesKey == null) CastDialog.show(this@MainActivity, settings, lastChapterUrl()) else CastDialog.showSeries(this@MainActivity, settings, seriesKey)
        }
    }

    /** Opens one series' voices from outside Settings; back returns to where it was opened. */
    private fun openSeriesVoices(url: String?, name: String?) {
        if (url.isNullOrBlank() || url.startsWith("about:")) {
            toast("Open a chapter of the series first.")
            return
        }
        val key = SeriesCast.seriesKeyOf(url)
        val shown = name?.takeIf { it.isNotBlank() } ?: Library.seriesName(ui.title, url)
        readAloud.pause()
        val from = ui.screen
        if (from != Screen.SETTINGS) ui.settingsFrom = from
        ui.voiceEntry = from
        ui.settingsPage = SettingsPage.VOICE
        ui.voiceStack = listOf(VoiceRoute.Series(key, shown))
        ui.screen = Screen.SETTINGS
    }

    private val browserActions = object : BrowserActions {
        override fun back() {
            if (webView.canGoBack()) webView.goBack() else home()
        }

        override fun home() {
            // Reading follows the page on screen, so it stops when the page goes away.
            cancelNextChapter()
            readAloud.stop()
            ui.screen = Screen.HOME
            libraryChanged()
        }

        override fun openTyped(text: String) = openTyped(text, this@MainActivity::openUrl)

        override fun toggleBookmark() {
            val url = webView.url ?: return
            val saved = library.toggleBookmark(webView.title.orEmpty(), url)
            libraryChanged()
            toast(if (saved) "Bookmarked" else "Bookmark removed")
        }

        override fun readOrPause() {
            cancelNextChapter()
            when (readAloud.state) {
                ReadAloud.State.IDLE -> readAloud.start(webView.url ?: return, webView.settings.userAgentString)
                ReadAloud.State.PREPARING -> readAloud.stop()
                ReadAloud.State.READING -> readAloud.pause()
                ReadAloud.State.PAUSED -> readAloud.resume()
            }
        }

        override fun stop() {
            cancelNextChapter()
            readAloud.stop()
            ui.status = ""
        }

        override fun cancelNext() {
            cancelNextChapter()
            ui.status = "Finished this chapter"
        }

        override fun nextNow() {
            if (ui.nextCountdown == null) return
            mainHandler.removeCallbacks(countdownTick)
            ui.nextCountdown = 1
            countdownTick.run()
        }

        override fun skip() = readAloud.skip()
        override fun openSettings() = showSettings()
        override fun characterVoices() = openSeriesVoices(webView.url, null)

        override fun switchRelay() = RelayPicker.pick(this@MainActivity, settings) { ui.status = "Relay: ${it.label}" }

        override fun reload() = webView.reload()

        override fun share() {
            val url = webView.url ?: return
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            startActivity(Intent.createChooser(send, null))
        }

        override fun openInOtherApp() {
            val url = webView.url ?: return
            // A chooser, so this app is not picked straight back.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null))
        }

        override fun openBlockedJump() {
            val url = ui.blockedJump ?: return
            ui.blockedJump = null
            webView.loadUrl(url)
        }

        override fun dismissStatus() {
            ui.status = ""
        }
    }

    private val settingsActions = object : SettingsActions {
        override fun back() {
            if (ui.settingsPage == SettingsPage.VOICE && ui.voiceStack.isNotEmpty()) {
                SamplePlayer.stop()
                ui.voiceStack = ui.voiceStack.dropLast(1)
                val entry = ui.voiceEntry
                if (ui.voiceStack.isEmpty() && entry != null) {
                    // Opened straight from a series: back goes where it came from.
                    ui.voiceEntry = null
                    ui.settingsPage = null
                    if (entry != Screen.SETTINGS) {
                        readDisplaySettings()
                        ui.screen = entry
                    }
                }
            } else if (ui.settingsPage != null) {
                ui.settingsPage = null
            } else {
                readDisplaySettings()
                ui.screen = ui.settingsFrom
            }
        }

        override fun openPage(page: SettingsPage) {
            ui.voiceStack = emptyList()
            ui.voiceEntry = null
            ui.settingsPage = page
        }

        override fun characterVoices() {
            val url = lastChapterUrl()
            val name = library.continueReading(1).firstOrNull()?.takeIf { url != null && SeriesCast.seriesKeyOf(it.url) == SeriesCast.seriesKeyOf(url) }?.series
            openSeriesVoices(url, name)
        }

        override fun pasteSetupLink(onAdded: () -> Unit) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
            val relay = SetupLink.parse(text)
            if (relay == null) {
                toast("Copy the setup link the relay prints (mangaloud://setup?…) first")
                return
            }
            SetupLink.confirmAndAdd(this@MainActivity, settings, relay, onAdded)
        }

        override fun updateBlocklist(onDone: (String) -> Unit) {
            thread(isDaemon = true) {
                val message = runCatching { "Blocklist updated: ${adBlocker.updateFromNetwork()} hosts" }
                    .getOrElse { "Update failed: ${it.message}" }
                runOnUiThread { onDone(message) }
            }
        }

        override fun clearHistory() {
            library.clearHistory()
            libraryChanged()
            toast("History cleared")
        }

        override fun themeChanged() = readDisplaySettings()

        override val blockedHosts: Int get() = adBlocker.size

        override val version: String
            get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
    }

    private fun showSettings() {
        readAloud.pause()
        ui.settingsFrom = if (ui.screen == Screen.SETTINGS) ui.settingsFrom else ui.screen
        ui.settingsPage = null
        ui.voiceStack = emptyList()
        ui.voiceEntry = null
        ui.screen = Screen.SETTINGS
    }

    /** Character voices belong to a series: the page open now, or the last chapter read. */
    private fun lastChapterUrl(): String? =
        webView.url?.takeIf { SeriesCast.looksLikeChapter(it) } ?: library.continueReading(1).firstOrNull()?.url ?: webView.url

    /** Settings the screens draw from, read again when Settings closes. */
    private fun readDisplaySettings() {
        ui.themeMode = settings.themeMode
        ui.dynamicColor = settings.dynamicColor
        ui.showLineText = settings.showLineText
        ui.showDetails = settings.showReadingDetails
    }

    private fun libraryChanged() {
        ui.libraryVersion++
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // Opening pages

    private fun openUrl(url: String) {
        ui.url = url
        ui.screen = Screen.BROWSER
        if (webView.url != url) webView.loadUrl(url)
    }

    private fun openTyped(input: String, open: (String) -> Unit) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
        }
        open(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (addRelayFrom(intent)) return
        linkFrom(intent)?.let(::openUrl)
    }

    /** The relay's setup link adds that relay, after asking. True when the intent was one. */
    private fun addRelayFrom(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW || !SetupLink.isSetupLink(intent.data)) return false
        val relay = SetupLink.parse(intent.dataString.orEmpty())
        if (relay == null) {
            toast("That setup link is not complete. Copy it again from the PC.")
        } else {
            SetupLink.confirmAndAdd(this, settings, relay) { ui.status = "Relay: ${relay.name}" }
        }
        return true
    }

    /** Other apps can send any URI here, so anything but a web link (javascript:, file:, intent:) is dropped. */
    private fun linkFrom(intent: Intent?): String? {
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        }
        return link?.takeIf(::isWebLink)
    }

    private fun isWebLink(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    // Lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webView.url.isNullOrEmpty()) {
            outState.putBundle(STATE_WEB, Bundle().also { webView.saveState(it) })
            outState.putString(STATE_SCREEN, ui.screen.name)
        }
    }

    // Leaving the app (home, another app, an incoming call screen) pauses the reading, and
    // coming back carries on from the same bubble.
    override fun onStart() {
        super.onStart()
        readAloud.release(ReadAloud.HOLD_AWAY)
    }

    override fun onStop() {
        readAloud.hold(ReadAloud.HOLD_AWAY)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(countdownTick)
        readAloud.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    // The page

    /** Scrolling by hand while reading moves the reading to what is on screen. */
    @SuppressLint("ClickableViewAccessibility")
    private fun watchManualScrolling() {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> readAloud.onUserTouch(fingerUp = false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> readAloud.onUserTouch(fingerUp = true)
            }
            false // The page still gets the touch, so scrolling and taps work as normal.
        }
        webView.setOnScrollChangeListener { _, _, _, _, _ -> readAloud.onPageScrolled() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // so pop-ups reach onCreateWindow, where they are dropped
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = ReaderWebViewClient(adBlocker, this)
        webView.webChromeClient = ReaderChromeClient(this)
    }

    private fun downloadBlocklistOnFirstRun() {
        if (adBlocker.hasDownloadedList) return
        thread(isDaemon = true) { runCatching { adBlocker.updateFromNetwork() } }
    }

    // BrowserEvents

    override fun onPageStarted(url: String) {
        // Another page is opening (a link, back): the countdown is for the old one.
        if (!readNextOnLoad) cancelNextChapter()
        autoStartedFor = null
        readAloud.stop()
        ui.url = url
        ui.blockedJump = null
        ui.status = ""
    }

    override fun onPageFinished(url: String) {
        ui.url = url
        if (!isWebLink(url)) return
        settings.lastUrl = url
        library.addVisit(webView.title.orEmpty(), url)
        libraryChanged()
        val userAgent = webView.settings.userAgentString
        val auto = readNextOnLoad || (settings.autoRead && !restoredPage)
        readNextOnLoad = false
        restoredPage = false
        if (auto && autoStartedFor != url && ui.screen == Screen.BROWSER) {
            autoStartedFor = url
            readAloud.startIfChapter(url, userAgent) { readAloud.readOnOpen(url, userAgent) }
        } else {
            readAloud.readOnOpen(url, userAgent)
        }
    }

    override fun onChapterFinished(nextUrl: String?) {
        if (!settings.autoNextChapter) return
        if (nextUrl == null) {
            ui.status = "Finished this chapter. No link to the next one was found."
            return
        }
        nextChapterUrl = nextUrl
        ui.nextCountdown = NEXT_COUNTDOWN_SECONDS
        mainHandler.removeCallbacks(countdownTick)
        mainHandler.postDelayed(countdownTick, 1000)
    }

    private fun cancelNextChapter() {
        mainHandler.removeCallbacks(countdownTick)
        ui.nextCountdown = null
        nextChapterUrl = null
    }

    override fun onTitle(url: String, title: String) {
        ui.title = title
        library.updateTitle(url, title)
    }

    override fun onIcon(url: String, icon: Bitmap) {
        thread(isDaemon = true) { library.saveIcon(url, icon) }
    }

    override fun onProgress(percent: Int) {
        ui.progress = percent
    }

    override fun onBlockedJump(url: String) {
        ui.blockedJump = url
    }

    // ReadAloud.Listener

    override fun onStateChanged(state: ReadAloud.State) {
        ui.readerState = state
        if (state == ReadAloud.State.IDLE) ui.line = null
        val busy = state == ReadAloud.State.READING || state == ReadAloud.State.PREPARING
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStatus(text: String) {
        ui.status = text
    }

    override fun onLine(bubble: Bubble?) {
        ui.line = bubble
    }

    override fun onDetails(text: String?) {
        ui.details = text
    }

    companion object {
        private const val STATE_WEB = "web"
        private const val STATE_SCREEN = "screen"

        /** How long "Next chapter in 3…" shows before the next chapter opens. */
        private const val NEXT_COUNTDOWN_SECONDS = 3
    }
}
