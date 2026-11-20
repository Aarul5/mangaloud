@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package io.github.aarul5.mangaloud

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class HomeTab { HOME, HISTORY }

/** What the home screen can ask the app to do. */
interface HomeActions {
    fun open(url: String)
    fun openTyped(text: String)
    fun openSettings()
    fun renameBookmark(url: String, title: String)
    fun removeBookmark(url: String)
    fun removeVisit(url: String)
    /** One series' voices, from a Continue reading card. */
    fun seriesVoices(url: String, series: String)
    fun clearHistory()
}

@Composable
fun HomeScreen(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    library: Library,
    libraryVersion: Int,
    actions: HomeActions,
) {
    // Read again whenever the library changes.
    val bookmarks = remember(libraryVersion) { library.bookmarks }
    val continueItems = remember(libraryVersion) { library.continueReading() }
    val history = remember(libraryVersion) { library.history }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark()
                        Spacer(Modifier.width(10.dp))
                        Text(if (tab == HomeTab.HOME) appName() else "History")
                    }
                },
                actions = {
                    if (tab == HomeTab.HISTORY && history.isNotEmpty()) {
                        IconButton(onClick = actions::clearHistory) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear history")
                        }
                    }
                    IconButton(onClick = actions::openSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.HOME,
                    onClick = { onTab(HomeTab.HOME) },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.HISTORY,
                    onClick = { onTab(HomeTab.HISTORY) },
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    label = { Text("History") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.HOME -> HomeTabContent(padding, library, bookmarks, continueItems, actions)
            HomeTab.HISTORY -> HistoryTabContent(padding, library, history, actions)
        }
    }
}

@Composable
private fun appName(): String = androidx.compose.ui.res.stringResource(R.string.app_name)

@Composable
private fun HomeTabContent(
    padding: PaddingValues,
    library: Library,
    bookmarks: List<Bookmark>,
    continueItems: List<ContinueItem>,
    actions: HomeActions,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            AddressField(
                shown = "",
                placeholder = "Search or type a site",
                onGo = actions::openTyped,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                height = 50.dp,
            )
        }
        if (continueItems.isNotEmpty()) {
            item { SectionTitle("Continue reading", Modifier.padding(top = 12.dp)) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(continueItems, key = { it.url }) { ContinueCard(it, library, actions) }
                }
            }
        }
        item { SectionTitle("Bookmarks", Modifier.padding(top = 16.dp)) }
        if (bookmarks.isEmpty()) {
            item {
                EmptyNote(
                    icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                    title = "Save your sites here",
                    body = "Open a site, then tap the bookmark in the top bar.",
                )
            }
        } else {
            // Three tiles a row; a grid inside a scrolling list is laid out by hand.
            items(bookmarks.chunked(3)) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { BookmarkTile(it, library, actions, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, library: Library, actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier.width(168.dp).combinedClickable(
                onClick = { actions.open(item.url) },
                onLongClick = { menu = true },
            ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteBadge(item.url, library, size = 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(item.series, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.chapter,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ago(item.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Voices") }, onClick = {
                menu = false
                actions.seriesVoices(item.url, item.series)
            })
            DropdownMenuItem(text = { Text("Remove from history") }, onClick = {
                menu = false
                actions.removeVisit(item.url)
            })
        }
    }
}

@Composable
private fun BookmarkTile(bookmark: Bookmark, library: Library, actions: HomeActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Box(modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = { actions.open(bookmark.url) },
                onLongClick = { menu = true },
            ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SiteBadge(bookmark.url, library, size = 44.dp)
                Spacer(Modifier.height(8.dp))
                Text(Library.cleanTitle(bookmark.title), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                menu = false
                renaming = true
            })
            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                menu = false
                actions.removeBookmark(bookmark.url)
            })
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(Library.cleanTitle(bookmark.title)) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename bookmark") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    actions.renameBookmark(bookmark.url, name)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryTabContent(padding: PaddingValues, library: Library, history: List<Visit>, actions: HomeActions) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyNote(
                icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                title = "Pages you open show up here",
                body = "Chapters you read also fill in Continue reading on Home.",
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        items(history, key = { it.url }) { visit ->
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { actions.open(visit.url) }),
                leadingContent = { SiteBadge(visit.url, library, size = 36.dp) },
                headlineContent = { Text(visit.title.ifBlank { visit.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "${Library.hostOf(visit.url)} · ${ago(visit.time)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { actions.removeVisit(visit.url) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun EmptyNote(icon: @Composable () -> Unit, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ago(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
