@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.aarul5.mangaloud

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class SettingsPage(val title: String, val summary: String, val icon: ImageVector) {
    READING("Reading", "Speed, picture pauses, reading ahead", Icons.AutoMirrored.Outlined.MenuBook),
    VOICE("Voice", "Phone, cloud or PC voices", Icons.Outlined.RecordVoiceOver),
    CAST("Voices for a series", "The series you read last, or any series", Icons.Outlined.Groups),
    TEXT("Text recognition", "Claude API or PC relay", Icons.Outlined.DocumentScanner),
    RELAY("PC relay", "Saved relays and setup link", Icons.Outlined.Computer),
    BROWSER("Browser and ad-block", "Blocklist and history", Icons.Outlined.Shield),
    APPEARANCE("Appearance", "Theme and colours", Icons.Outlined.Palette),
    ABOUT("About", "Version and source", Icons.Outlined.Info),
}

/** What the settings screens need from the app beyond [Settings] itself. */
interface SettingsActions {
    fun back()
    fun openPage(page: SettingsPage)
    fun characterVoices()
    fun pasteSetupLink(onAdded: () -> Unit)
    fun updateBlocklist(onDone: (String) -> Unit)
    fun clearHistory()
    fun themeChanged()
    val blockedHosts: Int
    val version: String
}

@Composable
fun SettingsScreen(page: SettingsPage?, settings: Settings, actions: SettingsActions, voiceRoute: VoiceRoute, voiceActions: VoiceActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == SettingsPage.VOICE) voiceRoute.title else page?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                null -> SettingsHome(actions)
                SettingsPage.READING -> ReadingPage(settings)
                SettingsPage.VOICE -> VoiceSettings(voiceRoute, settings, voiceActions)
                SettingsPage.CAST -> Unit // Opens as a dialog over the list; see MainActivity.
                SettingsPage.TEXT -> TextPage(settings)
                SettingsPage.RELAY -> RelayPage(settings, actions)
                SettingsPage.BROWSER -> BrowserPage(actions)
                SettingsPage.APPEARANCE -> AppearancePage(settings, actions)
                SettingsPage.ABOUT -> AboutPage(actions)
            }
        }
    }
}

@Composable
private fun SettingsHome(actions: SettingsActions) {
    LazyColumn {
        itemsIndexed(SettingsPage.entries) { _, page ->
            ListItem(
                modifier = Modifier.clickable { if (page == SettingsPage.CAST) actions.characterVoices() else actions.openPage(page) },
                leadingContent = { Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(page.title) },
                supportingContent = { Text(page.summary) },
            )
        }
    }
}

// Pages

@Composable
private fun ReadingPage(settings: Settings) {
    Page {
        var rate by remember { mutableStateOf(settings.speechRate) }
        SliderRow("Speech speed", "%.2f×".format(Locale.US, rate), rate, 0.5f..2.5f, steps = 39) {
            rate = it
            settings.speechRate = it
        }
        var art by remember { mutableStateOf(settings.artPauseSeconds) }
        SliderRow(
            "Pause on pictures with no text",
            if (art < 0.05f) "Off" else "%.1f s".format(Locale.US, art),
            art,
            0f..Settings.MAX_ART_PAUSE_SECONDS,
            steps = (Settings.MAX_ART_PAUSE_SECONDS * 2).toInt() - 1,
        ) {
            art = it
            settings.artPauseSeconds = it
        }
        Label("Slices sent to Claude at a time", "More keeps further ahead of the voice; the first request is always small.")
        var batch by remember { mutableIntStateOf(settings.batchSlices) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Settings.BATCH_SLICE_CHOICES.forEachIndexed { i, n ->
                SegmentedButton(
                    selected = batch == n,
                    onClick = {
                        batch = n
                        settings.batchSlices = n
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, Settings.BATCH_SLICE_CHOICES.size),
                ) { Text("$n") }
            }
        }
        Divider()
        SwitchRow("Right to left", "For Japanese manga, read each row from the right.", settings.rightToLeft) { settings.rightToLeft = it }
        SwitchRow(
            "Read the chapter when it opens",
            "Pressing Read then starts at once. Uses a little more of your plan.",
            settings.readOnOpen,
        ) { settings.readOnOpen = it }
        SwitchRow(
            "Read the next chapter early",
            "Once this chapter's text is read, the start of the next one is too.",
            settings.readNextChapterEarly,
        ) { settings.readNextChapterEarly = it }
        SwitchRow(
            "Read aloud when a chapter opens",
            "Starts reading by itself, as if you pressed Read.",
            settings.autoRead,
        ) { settings.autoRead = it }
        SwitchRow(
            "Go on to the next chapter",
            "At the end of a chapter, opens the next one from the page's Next link and reads it.",
            settings.autoNextChapter,
        ) { settings.autoNextChapter = it }
        SwitchRow(
            "Read game windows",
            "Quest, skill and status windows, in a voice of their own. Off skips them.",
            settings.readSystemBoxes,
        ) { settings.readSystemBoxes = it }
        Divider()
        Label("Reading bar", null)
        SwitchRow(
            "Show the line being read",
            "Off keeps the bar to one row, so more of the page shows.",
            settings.showLineText,
        ) { settings.showLineText = it }
        SwitchRow(
            "Show model and time",
            "A small line with which model read the text and how long it took.",
            settings.showReadingDetails,
        ) { settings.showReadingDetails = it }
    }
}

@Composable
private fun TextPage(settings: Settings) {
    Page {
        Label("Read the text with", null)
        var mode by remember { mutableStateOf(settings.ocrMode) }
        RadioRow("PC relay", "Claude on your PC reads the pages, on your Claude plan.", mode == OcrMode.PC_RELAY) {
            mode = OcrMode.PC_RELAY
            settings.ocrMode = mode
        }
        RadioRow("Claude API", "The phone sends pages to Claude with your API key.", mode == OcrMode.CLAUDE_API) {
            mode = OcrMode.CLAUDE_API
            settings.ocrMode = mode
        }
        if (mode == OcrMode.CLAUDE_API) {
            Divider()
            var key by remember { mutableStateOf(settings.apiKey) }
            var model by remember { mutableStateOf(settings.apiModel) }
            Field("API key", key, "sk-ant-…", secret = true) {
                key = it
                settings.apiKey = it
            }
            Field("Model", model, Settings.DEFAULT_API_MODEL) {
                model = it
                settings.apiModel = it
            }
        }
    }
}

@Composable
private fun RelayPage(settings: Settings, actions: SettingsActions) {
    var version by remember { mutableIntStateOf(0) }
    // Entries with nothing in them (from older versions) are dropped, so the list matches what is saved.
    val relays = remember(version) {
        settings.relays.filter(::hasAnything).also { if (it.size != settings.relays.size) settings.relays = it }
    }
    var active by remember(version) { mutableIntStateOf(settings.activeRelayIndex) }
    var editing by remember { mutableStateOf<Int?>(null) }
    Page {
        if (relays.isEmpty()) {
            Label("No relay yet", "Start the relay on your PC, copy the setup link it prints, then paste it here.")
        }
        relays.forEachIndexed { i, relay ->
            ListItem(
                modifier = Modifier.selectable(selected = active == i, role = Role.RadioButton) {
                    active = i
                    settings.activeRelayIndex = i
                },
                leadingContent = { RadioButton(selected = active == i, onClick = null) },
                headlineContent = { Text(relay.label) },
                supportingContent = {
                    Text(listOf(relay.url, relay.model.ifBlank { "PC default model" }, relay.effort.ifBlank { "default effort" }).joinToString(" · "))
                },
                trailingContent = {
                    IconButton(onClick = { editing = i }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                },
            )
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            FilledTonalButton(onClick = { actions.pasteSetupLink { version++ } }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Paste setup link")
            }
            Spacer(Modifier.padding(6.dp))
            OutlinedButton(onClick = { editing = relays.size }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Add")
            }
        }
        Divider()
        SwitchRow(
            "Allow a plain Wi-Fi address",
            "Off keeps the token on Tailscale, where it is encrypted.",
            settings.allowPlainWifiRelay,
        ) { settings.allowPlainWifiRelay = it }
    }
    editing?.let { index ->
        val all = relays
        val relay = all.getOrNull(index) ?: Relay.blank().copy(name = "Relay ${all.size + 1}")
        RelayEditor(
            relay = relay,
            canDelete = index in all.indices,
            onSave = { edited ->
                val list = all.toMutableList()
                if (index in list.indices) list[index] = edited else list += edited
                settings.relays = list
                editing = null
                version++
            },
            onDelete = {
                val list = all.toMutableList()
                if (index in list.indices) list.removeAt(index)
                settings.relays = list
                settings.activeRelayIndex = settings.activeRelayIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                editing = null
                version++
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RelayEditor(relay: Relay, canDelete: Boolean, onSave: (Relay) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(relay.name) }
    var url by remember { mutableStateOf(relay.url) }
    var token by remember { mutableStateOf(relay.token) }
    var model by remember { mutableStateOf(relay.model) }
    var effort by remember { mutableStateOf(relay.effort) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relay") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Home PC") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    url, { url = it }, label = { Text("Address") }, placeholder = { Text("http://100.x.x.x:8787") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    token, { token = it }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(12.dp))
                Choice("Model", model, Relay.MODELS.let { if (model in it) it else it + model }, ::modelLabel) { model = it }
                Spacer(Modifier.height(8.dp))
                Choice("Effort", effort, Relay.EFFORTS, ::effortLabel) { effort = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Relay(name.trim(), url.trim(), token.trim(), model, effort)) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun hasAnything(relay: Relay) = relay.name.isNotBlank() || relay.url.isNotBlank() || relay.token.isNotBlank()

private fun modelLabel(model: String) = if (model.isBlank()) "PC default" else model.replaceFirstChar { it.uppercase() }

private fun effortLabel(effort: String) = when (effort) {
    "" -> "PC default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

@Composable
private fun BrowserPage(actions: SettingsActions) {
    Page {
        var message by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Label("Ad blocker", "${actions.blockedHosts} ad and pop-up hosts blocked.")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            FilledTonalButton(enabled = !busy, onClick = {
                busy = true
                actions.updateBlocklist {
                    busy = false
                    message = it
                }
            }) { Text(if (busy) "Updating…" else "Update blocklist") }
        }
        message?.let { Label(it, null) }
        Divider()
        Label("History", "Pages you opened, used for Continue reading on Home.")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = actions::clearHistory) { Text("Clear history") }
        }
    }
}

@Composable
private fun AppearancePage(settings: Settings, actions: SettingsActions) {
    Page {
        Label("Theme", null)
        var mode by remember { mutableStateOf(settings.themeMode) }
        listOf(ThemeMode.SYSTEM to "Follow the phone", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (value, label) ->
            RadioRow(label, null, mode == value) {
                mode = value
                settings.themeMode = value
                actions.themeChanged()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Divider()
            SwitchRow("Wallpaper colours", "Take the app's colours from your wallpaper instead of amber.", settings.dynamicColor) {
                settings.dynamicColor = it
                actions.themeChanged()
            }
        }
    }
}

@Composable
private fun AboutPage(actions: SettingsActions) {
    Page {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(48.dp)
            Spacer(Modifier.padding(8.dp))
            Column {
                Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text("Version ${actions.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Label(
            "Reads comics aloud",
            "Claude reads the speech bubbles on the page and a voice reads them out, one line at a time, " +
                "with the page following along.",
        )
    }
}

// Building blocks

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { content() }
}

@Composable
private fun Label(title: String, body: String?) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))

@Composable
private fun SwitchRow(title: String, body: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    ListItem(
        modifier = Modifier.clickable {
            on = !on
            onChange(on)
        },
        headlineContent = { Text(title) },
        supportingContent = if (body != null) { { Text(body) } } else null,
        trailingContent = {
            Switch(checked = on, onCheckedChange = {
                on = it
                onChange(it)
            })
        },
    )
}

@Composable
private fun RadioRow(title: String, body: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = if (body != null) { { Text(body) } } else null,
    )
}

@Composable
private fun SliderRow(
    title: String,
    shown: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, show: (String) -> String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(show(value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(show(option)) }, onClick = {
                        open = false
                        onPick(option)
                    })
                }
            }
        }
    }
}
