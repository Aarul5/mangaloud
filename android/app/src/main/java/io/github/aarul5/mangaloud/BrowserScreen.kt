package io.github.aarul5.mangaloud

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** What the browser screen can ask the app to do. */
interface BrowserActions {
    fun back()
    fun home()
    fun openTyped(text: String)
    fun toggleBookmark()
    fun readOrPause()
    fun stop()
    fun skip()
    fun openSettings()
    fun characterVoices()
    fun switchRelay()
    fun reload()
    fun share()
    fun openInOtherApp()
    fun openBlockedJump()
    fun dismissStatus()

    /** Stops the countdown to the next chapter. */
    fun cancelNext()

    /** Opens the next chapter now instead of waiting for the countdown. */
    fun nextNow()
}

/**
 * The page with a slim bar on top and the reading bar docked at the bottom. The page sits
 * between the two, so a bubble the reading glides to is never hidden under the controls.
 */
@Composable
fun BrowserScreen(ui: UiState, webView: WebView, bookmarked: Boolean, actions: BrowserActions) {
    // A Surface, so icons and text get the theme's colours rather than plain black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.statusBarsPadding()) {
                TopBar(ui, bookmarked, actions)
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (ui.progress in 1..99) {
                        LinearProgressIndicator(progress = { ui.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        // The same WebView is kept for the whole app; take it off its old screen first.
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                ui.blockedJump?.let {
                    BlockedJumpCard(it, actions, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
            ReaderBar(ui, actions)
        }
    }
}

@Composable
private fun TopBar(ui: UiState, bookmarked: Boolean, actions: BrowserActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::home) {
            Icon(Icons.Outlined.Home, contentDescription = "Home")
        }
        AddressField(
            shown = ui.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
            placeholder = "Search or type a site",
            onGo = actions::openTyped,
            modifier = Modifier.weight(1f),
            height = 42.dp,
        )
        IconButton(onClick = actions::toggleBookmark) {
            Icon(
                if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val close = { menu = false }
                MenuItem("Back", Icons.AutoMirrored.Outlined.ArrowBack, close, actions::back)
                MenuItem("Reload", Icons.Outlined.Refresh, close, actions::reload)
                MenuItem("Share link", Icons.Outlined.Share, close, actions::share)
                MenuItem("Open in another app", Icons.Outlined.OpenInBrowser, close, actions::openInOtherApp)
                MenuItem("Voices for this series", Icons.Outlined.Groups, close, actions::characterVoices)
                MenuItem("Switch relay", Icons.Outlined.SwapHoriz, close, actions::switchRelay)
                MenuItem("Settings", Icons.Outlined.Settings, close, actions::openSettings)
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, close: () -> Unit, run: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            close()
            run()
        },
    )
}

@Composable
private fun BlockedJumpCard(url: String, actions: BrowserActions, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Blocked a jump to ${Library.hostOf(url)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = actions::openBlockedJump) {
                Text("Open", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

/**
 * The reading bar, docked to the bottom edge and running under the gesture bar. Kept small so
 * the page stays in view. With the line text on: the line on the left and the controls on the
 * right. With it off: just the controls, centred. Under either, the model and time in very
 * small grey text, if switched on in Settings.
 */
@Composable
private fun ReaderBar(ui: UiState, actions: BrowserActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
            val idle = ui.readerState == ReadAloud.State.IDLE
            val centred = !idle && !ui.showLineText && ui.readerState != ReadAloud.State.PREPARING
            if (idle) IdleBar(ui, actions) else PlayingBar(ui, actions, centred)
            val details = ui.details
            if (ui.showDetails && details != null && !idle) {
                Text(
                    details,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun IdleBar(ui: UiState, actions: BrowserActions) {
    ui.nextCountdown?.let { seconds ->
        NextChapterBar(seconds, actions)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ui.status.ifBlank { "Read this chapter aloud" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (ui.status.isNotBlank()) {
            IconButton(onClick = actions::dismissStatus, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = actions::readOrPause,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.height(38.dp),
        ) {
            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Read aloud", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** At the end of a chapter, with "Go on to the next chapter" on: a short countdown, which can be cancelled. */
@Composable
private fun NextChapterBar(seconds: Int, actions: BrowserActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Next chapter in $seconds…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = actions::cancelNext, modifier = Modifier.height(38.dp)) {
            Text("Cancel", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = actions::nextNow,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.height(38.dp),
        ) {
            Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Go now", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** While reading: the line (if switched on) beside the controls, or the controls alone, centred. */
@Composable
private fun PlayingBar(ui: UiState, actions: BrowserActions, centred: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centred) Arrangement.Center else Arrangement.Start,
    ) {
        if (!centred) {
            Text(
                ui.status.ifBlank { "Getting ready…" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        Controls(ui, actions, gap = if (centred) 14.dp else 8.dp)
    }
}

@Composable
private fun Controls(ui: UiState, actions: BrowserActions, gap: Dp) {
    val preparing = ui.readerState == ReadAloud.State.PREPARING
    FilledTonalIconButton(onClick = actions::stop, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Rounded.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.width(gap))
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        if (preparing) {
            // A ring round the button while the page is being read; tapping cancels.
            CircularProgressIndicator(Modifier.size(50.dp), strokeWidth = 2.5.dp)
        }
        FilledIconButton(
            onClick = actions::readOrPause,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            val (icon, label) = when (ui.readerState) {
                ReadAloud.State.PREPARING -> Icons.Rounded.Close to "Cancel"
                ReadAloud.State.READING -> Icons.Rounded.Pause to "Pause"
                else -> Icons.Rounded.PlayArrow to "Resume"
            }
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
    Spacer(Modifier.width(gap))
    FilledTonalIconButton(
        onClick = actions::skip,
        enabled = ui.readerState == ReadAloud.State.READING || ui.readerState == ReadAloud.State.PAUSED,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(Icons.Rounded.SkipNext, contentDescription = "Skip", modifier = Modifier.size(20.dp))
    }
}
