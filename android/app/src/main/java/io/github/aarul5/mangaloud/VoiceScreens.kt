@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.aarul5.mangaloud

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Where you are inside Settings › Voice. */
sealed interface VoiceRoute {
    val title: String

    data object Main : VoiceRoute {
        override val title = "Voice"
    }

    data class Provider(val provider: VoiceProviderId) : VoiceRoute {
        override val title get() = provider.label
    }

    data class Series(val key: String, val name: String) : VoiceRoute {
        override val title get() = name
    }
}

/** What the voice screens need from the app. */
interface VoiceActions {
    fun open(route: VoiceRoute)
    fun openLink(url: String)
    fun pasteText(): String

    /** The PC relay's character voices: for [seriesKey], or the series read last when null. */
    fun pcCharacterVoices(seriesKey: String?)
}

@Composable
fun VoiceSettings(route: VoiceRoute, settings: Settings, actions: VoiceActions) {
    val context = LocalContext.current
    val setup = remember { VoiceSetup(context) }
    remember { SamplePlayer.init(context) }
    DisposableEffect(route) { onDispose { SamplePlayer.stop() } }
    when (route) {
        VoiceRoute.Main -> VoiceMain(setup, settings, actions)
        is VoiceRoute.Provider -> if (route.provider == VoiceProviderId.PC) PcVoicePage(settings, actions) else ProviderPage(route.provider, setup, actions)
        is VoiceRoute.Series -> SeriesPage(route, setup, actions)
    }
}

// Settings › Voice

@Composable
private fun VoiceMain(setup: VoiceSetup, settings: Settings, actions: VoiceActions) {
    var chosen by remember { mutableStateOf(setup.provider) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Heading("Your usual voice · all series")
        VoiceProviderId.entries.forEach { provider ->
            ProviderCard(
                provider = provider,
                selected = chosen == provider,
                status = statusOf(setup, provider),
                onSelect = {
                    chosen = provider
                    setup.provider = provider
                    // A cloud voice with no key yet: go straight to adding one.
                    if (!setup.isReady(provider) && provider.isCloud) actions.open(VoiceRoute.Provider(provider))
                },
                onOpen = if (provider == VoiceProviderId.PHONE) null else ({ actions.open(VoiceRoute.Provider(provider)) }),
            )
        }
        Toggle("Phone voice if this one fails", "No key, no signal or out of credit: keep reading with the phone voice.", setup.fallbackToPhone) {
            setup.fallbackToPhone = it
        }
        Toggle("Expressive phone voice", "Pitch, speed and volume follow each line's tone.", settings.expressiveVoice) {
            settings.expressiveVoice = it
        }
        Heading("Series with their own voices")
        val series = remember { setup.seriesWithVoices() }
        if (series.isEmpty()) {
            Note("None yet. While reading, open ⋮ › Voices for this series, or press and hold a series under Continue reading.")
        }
        series.forEach { s ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { actions.open(VoiceRoute.Series(s.key, s.name)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(s.summary(chosen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

private enum class Status(val label: String) { READY("Ready"), NEEDS_KEY("Needs key"), NEEDS_RELAY("Needs relay"), ADVANCED("Advanced") }

private fun statusOf(setup: VoiceSetup, provider: VoiceProviderId): Status = when {
    provider == VoiceProviderId.PC -> if (setup.isReady(provider)) Status.ADVANCED else Status.NEEDS_RELAY
    setup.isReady(provider) -> Status.READY
    else -> Status.NEEDS_KEY
}

@Composable
private fun ProviderCard(provider: VoiceProviderId, selected: Boolean, status: Status, onSelect: () -> Unit, onOpen: (() -> Unit)?) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    StatusChip(status)
                }
                Text(provider.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpen != null) {
                IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Set up ${provider.label}") }
            }
        }
    }
}

@Composable
private fun StatusChip(status: Status) {
    val (bg, fg) = when (status) {
        Status.READY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Status.NEEDS_KEY, Status.NEEDS_RELAY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Status.ADVANCED -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
        Text(status.label, color = fg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

// One cloud provider: key, model and your usual voices

@Composable
private fun ProviderPage(provider: VoiceProviderId, setup: VoiceSetup, actions: VoiceActions) {
    val tts = CloudTts.of(provider) ?: return
    var key by remember { mutableStateOf(setup.key(provider)) }
    var keyMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(setup.model(provider)) }
    var version by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<String?>(null) }

    fun test() {
        setup.setKey(provider, key)
        testing = true
        keyMessage = null
        background({ tts.test(key.trim()) }) { result ->
            testing = false
            keyMessage = result.fold({ true to it }, { false to (it.message ?: "Could not check the key") })
            // ElevenLabs voices differ per account: choose usual voices once the key works.
            if (result.isSuccess && tts is ElevenLabsTts) {
                background({ tts.chooseDefaults(tts.library(key.trim(), "")) }) { picks ->
                    picks.onSuccess { setup.fillMissingUsual(provider, it); version++ }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                keyMessage = null
            },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = {
                key = actions.pasteText().trim()
                setup.setKey(provider, key)
                if (key.isNotEmpty()) test()
            }) { Text("Paste") }
            TextButton(onClick = { test() }, enabled = key.isNotBlank() && !testing) { Text("Test key") }
            TextButton(onClick = { actions.openLink(provider.keyUrl) }) { Text("Get a key ↗") }
        }
        if (testing) Note("Checking…")
        keyMessage?.let { (ok, text) ->
            Text(
                (if (ok) "✓ " else "✗ ") + text,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        Note("The key is stored encrypted on this phone and only sent to ${provider.label}.")
        if (tts.models.size > 1) {
            Heading("Model")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                tts.models.forEachIndexed { i, (id, label) ->
                    SegmentedButton(
                        selected = model == id,
                        onClick = {
                            model = id
                            setup.setModel(provider, id)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, tts.models.size),
                    ) { Text(label) }
                }
            }
        }
        Heading("Your usual voices")
        Note("Every series uses these unless it has its own.")
        androidx.compose.runtime.key(version) {
            SpeakerRows.TYPES.forEach { type ->
                val pick = setup.usual(provider, type)
                VoiceRow(
                    who = SpeakerRows.label(type),
                    value = pick?.name ?: "Choose…",
                    changed = false,
                    onReset = null,
                    onPlay = pick?.let { p -> { SamplePlayer.playVoice(setup, provider, p, type) } },
                    onClick = { picking = type },
                )
            }
        }
    }

    picking?.let { type ->
        VoicePicker(
            provider = provider,
            setup = setup,
            title = "Voice for ${SpeakerRows.label(type)}",
            scope = "All series · ${provider.label}",
            current = setup.usual(provider, type),
            usual = null,
            onPick = { pick ->
                setup.setUsual(provider, type, pick)
                version++
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

// The PC voice server, through the relay

@Composable
private fun PcVoicePage(settings: Settings, actions: VoiceActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Note("An OmniVoice voice server on your own PC, reached through the PC relay. Optional: only for people who run one.")
        Heading("Quality")
        var engine by remember { mutableStateOf(settings.voiceEngine.takeIf { it != VoiceEngine.PHONE } ?: VoiceEngine.PC_NORMAL) }
        listOf(
            VoiceEngine.PC_NORMAL to ("Best quality" to "May pause between lines."),
            VoiceEngine.PC_FAST to ("Fast" to "A quicker setting that keeps up with reading."),
        ).forEach { (value, text) ->
            Radio(text.first, text.second, engine == value) {
                engine = value
                settings.voiceEngine = value
            }
        }
        Heading("Speech model")
        Note("Best quality and fast only change OmniVoice. Qwen3-TTS and VibeVoice also ignore speech speed.")
        var model by remember { mutableStateOf(settings.pcVoiceModel) }
        PcVoiceModel.entries.forEach { option ->
            Radio(option.label, option.summary, model == option) {
                model = option
                settings.pcVoiceModel = option
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { actions.pcCharacterVoices(null) }, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text("Character voices on the PC")
        }
    }
}

// One series

@Composable
private fun SeriesPage(route: VoiceRoute.Series, setup: VoiceSetup, actions: VoiceActions) {
    val context = LocalContext.current
    var series by remember { mutableStateOf(setup.series(route.key, route.name)) }
    var choosingProvider by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) } // ("type" or "char", name)
    val characters = remember { SeriesCast(context, route.key).characters() }
    fun save(changed: SeriesVoices) {
        series = changed
        setup.saveSeries(changed)
    }
    val usualProvider = setup.provider
    val provider = series.provider ?: usualProvider
    val mine = series.on(provider)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Grey rows follow your usual voices. Changed ones are marked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { save(SeriesVoices(series.key, series.name, null, emptyMap())) }, enabled = !series.isEmpty) { Text("Reset all") }
        }
        Heading("Voice provider")
        VoiceRow(
            who = "Provider",
            value = if (series.provider == null) "Usual · ${usualProvider.label}" else provider.label,
            changed = series.provider != null,
            onReset = if (series.provider != null) ({ save(series.withProvider(null)) }) else null,
            onPlay = null,
            onClick = { choosingProvider = true },
        )
        if (series.provider != null) Note("Your usual is ${usualProvider.label}.")
        when {
            provider == VoiceProviderId.PHONE -> Note("The phone voice has one voice; its pitch changes with each speaker.")
            provider == VoiceProviderId.PC -> {
                Note("Characters on the PC voice are set on the relay.")
                FilledTonalButton(onClick = { actions.pcCharacterVoices(route.key) }, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("Character voices on the PC")
                }
            }
            !setup.isReady(provider) -> {
                Note("${provider.label} needs an API key first.")
                FilledTonalButton(onClick = { actions.open(VoiceRoute.Provider(provider)) }, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("Add the key")
                }
            }
            else -> {
                Heading("Speaker voices")
                SpeakerRows.TYPES.forEach { type ->
                    val own = mine.speakers[type]
                    val usual = setup.usual(provider, type)
                    val shown = own ?: usual
                    VoiceRow(
                        who = SpeakerRows.label(type),
                        value = if (own != null) own.name else "Usual · ${usual?.name ?: "none"}",
                        changed = own != null,
                        onReset = if (own != null) ({ save(series.withSpeaker(provider, type, null)) }) else null,
                        onPlay = shown?.let { p -> { SamplePlayer.playVoice(setup, provider, p, type) } },
                        onClick = { picking = "type" to type },
                    )
                }
                Heading("Characters")
                if (characters.isEmpty()) Note("Characters appear here after a chapter of this series is read.")
                characters.forEach { (name, speaker) ->
                    val own = mine.characters[name.lowercase()]?.pick
                    val type = SpeakerRows.typeOf(Bubble(0, "", 0.0, 0.0, 0.0, 0.0, speaker = speaker))
                    val fallback = mine.speakers[type] ?: setup.usual(provider, type)
                    VoiceRow(
                        who = name,
                        whoStrong = true,
                        value = if (own != null) "Own voice · ${own.name}" else "Uses the ${SpeakerRows.label(type).lowercase()} voice",
                        changed = own != null,
                        onReset = if (own != null) ({ save(series.withCharacter(provider, name, null)) }) else null,
                        onPlay = (own ?: fallback)?.let { p -> { SamplePlayer.playVoice(setup, provider, p, type) } },
                        onClick = { picking = "char" to name },
                    )
                }
            }
        }
    }

    if (choosingProvider) {
        AlertDialog(
            onDismissRequest = { choosingProvider = false },
            title = { Text("Voice for ${series.name}") },
            text = {
                Column {
                    Radio("Use your usual voice", usualProvider.label, series.provider == null) {
                        save(series.withProvider(null))
                        choosingProvider = false
                    }
                    VoiceProviderId.entries.forEach { p ->
                        val ready = setup.isReady(p)
                        Radio(p.label, if (ready) null else if (p == VoiceProviderId.PC) "Needs a relay" else "Needs a key", series.provider == p) {
                            save(series.withProvider(p))
                            choosingProvider = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingProvider = false }) { Text("Close") } },
        )
    }

    picking?.let { (what, name) ->
        val isType = what == "type"
        val type = if (isType) name else SpeakerRows.typeOf(Bubble(0, "", 0.0, 0.0, 0.0, 0.0, speaker = characters.firstOrNull { it.first == name }?.second ?: "man"))
        val fallback = if (isType) setup.usual(provider, type) else mine.speakers[type] ?: setup.usual(provider, type)
        VoicePicker(
            provider = provider,
            setup = setup,
            title = "Voice for ${if (isType) SpeakerRows.label(type) else name}",
            scope = "Only in ${series.name} · ${provider.label}",
            current = if (isType) mine.speakers[type] else mine.characters[name.lowercase()]?.pick,
            usual = (if (isType) "Use your usual voice" else "Use the ${SpeakerRows.label(type).lowercase()} voice") to fallback,
            onPick = { pick ->
                save(if (isType) series.withSpeaker(provider, type, pick) else series.withCharacter(provider, name, pick))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

// The voice library

@Composable
private fun VoicePicker(
    provider: VoiceProviderId,
    setup: VoiceSetup,
    title: String,
    scope: String,
    current: VoicePick?,
    /** The row that clears the choice ("Use your usual voice"), with the voice that then reads. */
    usual: Pair<String, VoicePick?>?,
    onPick: (VoicePick?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tts = CloudTts.of(provider) ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<LibraryVoice>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var gender by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var token by remember { mutableIntStateOf(0) }

    LaunchedEffect(search) {
        val mine = ++token
        voices = null
        problem = null
        val query = search
        val key = setup.key(provider)
        Handler(Looper.getMainLooper()).postDelayed({
            if (mine != token) return@postDelayed
            background({ tts.library(key, query) }) { result ->
                if (mine != token) return@background
                result.onSuccess { voices = it }.onFailure { problem = it.message ?: "Could not load voices" }
            }
        }, if (query.isEmpty()) 0L else 400L)
    }

    ModalBottomSheet(onDismissRequest = { SamplePlayer.stop(); onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            if (usual != null) {
                LibraryRow(usual.first, usual.second?.name.orEmpty(), current == null,
                    onPlay = usual.second?.let { p -> { SamplePlayer.playVoice(setup, provider, p, VoiceStyle.NARRATOR) } }) { onPick(null) }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search voices") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("female" to "Female", "male" to "Male").forEach { (value, label) ->
                    FilterChip(selected = gender == value, onClick = { gender = if (gender == value) "" else value }, label = { Text(label) })
                }
                listOf("younger" to "Younger", "older" to "Older").forEach { (value, label) ->
                    FilterChip(selected = age == value, onClick = { age = if (age == value) "" else value }, label = { Text(label) })
                }
            }
            val shown = voices?.filter { v ->
                (gender.isEmpty() || v.gender == gender) &&
                    when (age) {
                        "younger" -> v.age in setOf("child", "teen", "young")
                        "older" -> v.age in setOf("middle-aged", "old")
                        else -> true
                    }
            }
            when {
                problem != null -> Note(problem!!)
                shown == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Note("No voices match.")
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(shown, key = { it.id }) { v ->
                        val about = listOf(v.gender, v.age, v.about).filter { it.isNotBlank() }.joinToString(" · ")
                        LibraryRow(v.name, about, current?.id == v.id,
                            onPlay = { SamplePlayer.playLibrary(setup, provider, v) }) { onPick(v.pick) }
                    }
                }
            }
            if (tts.provider == VoiceProviderId.FISH && search.isEmpty()) {
                Note("These are Fish Audio's own voices. Search to find community voices too.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LibraryRow(name: String, about: String, selected: Boolean, onPlay: (() -> Unit)?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        ),
        leadingContent = { PlayButton(onPlay) },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (about.isNotBlank()) { { Text(about, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
        trailingContent = if (selected) { { Icon(Icons.Outlined.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary) } } else null,
    )
}

// Shared rows

@Composable
private fun VoiceRow(
    who: String,
    value: String,
    changed: Boolean,
    onReset: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onClick: () -> Unit,
    whoStrong: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            who,
            style = MaterialTheme.typography.bodyMedium,
            color = if (whoStrong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(112.dp),
        )
        if (changed) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
            Spacer(Modifier.width(6.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onReset != null) {
            IconButton(onClick = onReset) { Icon(Icons.Outlined.Refresh, contentDescription = "Use the usual voice") }
        }
        PlayButton(onPlay)
    }
}

@Composable
private fun PlayButton(onPlay: (() -> Unit)?) {
    if (onPlay == null) {
        Spacer(Modifier.size(48.dp))
        return
    }
    IconButton(onClick = onPlay) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Play a sample",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, body: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    ListItem(
        modifier = Modifier.clickable {
            on = !on
            onChange(on)
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = on, onCheckedChange = { on = it; onChange(it) }) },
    )
}

@Composable
private fun Radio(title: String, body: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = if (body != null) { { Text(body) } } else null,
    )
}

/** Runs [work] on a worker thread and hands the result back on the main thread. */
private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread(isDaemon = true) {
        val result = runCatching(work)
        main.post { done(result) }
    }
}

/**
 * Plays voice samples on the settings screens: a library voice's own sample when it has one,
 * otherwise a short line made with the voice and kept on the phone.
 */
object SamplePlayer {
    private var player: MediaPlayer? = null
    private var request = 0

    /** What is playing or loading, for the play buttons. */
    var playingKey by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun playLibrary(setup: VoiceSetup, provider: VoiceProviderId, voice: LibraryVoice) {
        val url = voice.previewUrl
        if (url != null) play(voice.id) { url } else playVoice(setup, provider, voice.pick, "")
    }

    fun playVoice(setup: VoiceSetup, provider: VoiceProviderId, voice: VoicePick, type: String) {
        val tts = CloudTts.of(provider) ?: return
        play(voice.id) {
            val line = when (type) {
                VoiceStyle.NARRATOR -> "The forge had been cold for three hundred years, until tonight."
                SpeakerRows.SYSTEM -> "Quest complete! You gained three hundred experience."
                else -> "Hey. This is how I sound. Do you like it?"
            }
            val dir = File(app.cacheDir, "voice-samples").apply { mkdirs() }
            val id = MessageDigest.getInstance("SHA-256").digest("${provider.id}|${voice.id}|$line".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(32)
            dir.listFiles { f -> f.name.startsWith(id) }?.firstOrNull()?.let { return@play it.path }
            val bubble = Bubble(0, line, 0.0, 0.0, 0.0, 0.0, speaker = if (type == SpeakerRows.SYSTEM || type.isEmpty()) "man" else type,
                kind = if (type == SpeakerRows.SYSTEM) TextKind.SYSTEM else TextKind.DIALOGUE)
            val (audio, ext) = tts.speak(setup.key(provider), setup.model(provider), voice, bubble, 1f, false)
            File(dir, "$id.$ext").apply { writeBytes(audio) }.path
        }
    }

    /** Stops what is playing; pressing play on the same voice again also stops it. */
    fun stop() {
        request++
        player?.release()
        player = null
        playingKey = null
        loading = false
    }

    private fun play(key: String, source: () -> String) {
        val again = playingKey == key
        stop()
        if (again) return
        val mine = request
        playingKey = key
        loading = true
        background(source) { result ->
            if (mine != request) return@background
            val path = result.getOrElse {
                Log.w("SamplePlayer", "No sample: ${it.message}")
                android.widget.Toast.makeText(app, "Could not play a sample: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                stop()
                return@background
            }
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setDataSource(path)
                mp.setOnPreparedListener {
                    if (mine == request) {
                        loading = false
                        it.start()
                    }
                }
                mp.setOnCompletionListener { if (mine == request) stop() }
                mp.setOnErrorListener { _, _, _ ->
                    if (mine == request) stop()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                stop()
            }
        }
    }
}
