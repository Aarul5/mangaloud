package io.github.aarul5.mangaloud

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaPlayer
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * The series' characters and their PC voices: pick another voice, describe one, make it soft
 * or playful, or give a character the same voice as another (one person under two labels).
 * Changes are saved on the relay, which owns the cast list.
 */
object CastDialog {

    fun show(activity: Activity, settings: Settings, pageUrl: String?) {
        val problem = when {
            pageUrl.isNullOrBlank() -> "Open a chapter of the series first."
            settings.relayUrl.isBlank() || settings.relayToken.isBlank() -> "Character voices need a PC relay. Add one in Settings."
            !settings.allowPlainWifiRelay && !RelayOcr.isEncryptedRoute(settings.relayUrl) ->
                "The relay address is not a Tailscale address. Use the 100.x address, or allow plain Wi-Fi in Settings."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(activity, problem, Toast.LENGTH_LONG).show()
            return
        }
        showSeries(activity, settings, SeriesCast.seriesKeyOf(pageUrl!!))
    }

    /** The PC character voices of one series, by its key. */
    fun showSeries(activity: Activity, settings: Settings, series: String) {
        if (settings.relayUrl.isBlank() || settings.relayToken.isBlank()) {
            Toast.makeText(activity, "Character voices on the PC need a PC relay. Add one in Settings.", Toast.LENGTH_LONG).show()
            return
        }
        val relay = RelayCast(settings.relayUrl, settings.relayToken)
        Toast.makeText(activity, "Loading characters…", Toast.LENGTH_SHORT).show()
        thread(isDaemon = true) {
            val result = runCatching { relay.load(series) }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                result.onSuccess { showList(activity, settings, relay, it) }
                    .onFailure { Toast.makeText(activity, "Could not load characters: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showList(activity: Activity, settings: Settings, relay: RelayCast, cast: CastList) {
        if (cast.characters.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Character voices")
                .setMessage("No characters yet for ${cast.series}. Read a chapter with the PC voice first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val sorted = cast.characters.sortedWith(compareBy({ it.key != "hero" }, { it.name.lowercase() }))
        val labels = sorted.map { "${it.name}\n${it.speaker} · ${it.sounds}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Character voices: ${cast.series}")
            .setItems(labels) { _, which -> showEditor(activity, settings, relay, cast, sorted[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEditor(activity: Activity, settings: Settings, relay: RelayCast, cast: CastList, entry: CastEntry) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(text: String) = form.addView(TextView(activity).apply {
            this.text = text
            setPadding(0, pad, 0, pad / 4)
        })
        fun dropDown(items: List<String>): Spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
        }.also { form.addView(it) }

        // Same voice as: one person under two labels shares one voice.
        val others = cast.characters.filter { it.key != entry.key && it.sameAs.isEmpty() }
            .sortedWith(compareBy({ it.key != "hero" }, { it.name.lowercase() }))
        label("Same voice as another character")
        val sameAs = dropDown(listOf("No one (their own voice)") + others.map { it.name })
        sameAs.setSelection(others.indexOfFirst { it.key == entry.sameAs } + 1)

        val ownSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        form.addView(ownSection)

        // Age and gender: fixes a character Claude took for the wrong one. Each has its own voices.
        val speakers = cast.voices.keys.filter { it != VoiceStyle.UNKNOWN }.ifEmpty { listOf(entry.speaker) }
            .let { if (entry.speaker in it) it else it + entry.speaker }
        ownSection.addView(TextView(activity).apply {
            text = "Age and gender"
            setPadding(0, pad, 0, pad / 4)
        })
        val speakerPick = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, speakers)
            setSelection(speakers.indexOf(entry.speaker).coerceAtLeast(0))
        }
        ownSection.addView(speakerPick)
        fun chosenSpeaker() = speakers[speakerPick.selectedItemPosition.coerceIn(0, speakers.lastIndex)]

        // Voice: the chosen speaker type's voices, or a description of your own.
        fun choicesFor(speaker: String) = cast.voices[speaker].orEmpty()
        fun labelsFor(speaker: String) =
            choicesFor(speaker).mapIndexed { i, d -> if (i == 0) "Plain $speaker voice: $d" else "Voice $i: $d" } + "My own description…"
        val voiceHeading = TextView(activity).apply {
            text = "Voice"
            setPadding(0, pad, 0, pad / 4)
        }
        ownSection.addView(voiceHeading)
        val voice = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labelsFor(entry.speaker))
        }
        ownSection.addView(voice)
        val custom = EditText(activity).apply {
            hint = "e.g. male, teenager, high pitch"
            isSingleLine = true
            setText(entry.voice)
        }
        ownSection.addView(custom)
        ownSection.addView(TextView(activity).apply {
            text = "Words the voice understands: male or female; child, teenager, young adult, middle-aged, " +
                "elderly; very low, low, moderate, high or very high pitch; English accents."
            textSize = 12f
        })
        fun customIndex() = voice.adapter.count - 1
        val ownChoices = choicesFor(entry.speaker)
        voice.setSelection(if (entry.voice.isNotBlank()) customIndex() else entry.variant.coerceIn(0, (ownChoices.size - 1).coerceAtLeast(0)))
        fun showCustom() {
            custom.visibility = if (voice.selectedItemPosition == customIndex()) View.VISIBLE else View.GONE
        }
        voice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showCustom()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showCustom()
        var shownSpeaker = entry.speaker
        speakerPick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val speaker = chosenSpeaker()
                if (speaker == shownSpeaker) return
                shownSpeaker = speaker
                val keepCustom = voice.selectedItemPosition == customIndex()
                voice.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labelsFor(speaker))
                // A new type starts on its first character voice, unless a description of your own is in use.
                voice.setSelection(if (keepCustom) customIndex() else minOf(1, customIndex() - 1).coerceAtLeast(0))
                showCustom()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val soft = CheckBox(activity).apply { text = "Soft (gentle; loud lines acted with restraint)"; isChecked = entry.soft }
        val playful = CheckBox(activity).apply { text = "Playful (bouncy, childish delivery)"; isChecked = entry.playful }
        ownSection.addView(soft)
        ownSection.addView(playful)

        fun showOwn() {
            ownSection.visibility = if (sameAs.selectedItemPosition == 0) View.VISIBLE else View.GONE
        }
        sameAs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showOwn()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showOwn()

        fun edited(): CastEntry {
            val linked = sameAs.selectedItemPosition
            if (linked > 0) return entry.copy(sameAs = others[linked - 1].key)
            val useCustom = voice.selectedItemPosition == customIndex()
            return entry.copy(
                sameAs = "",
                speaker = chosenSpeaker(),
                variant = if (useCustom) entry.variant else voice.selectedItemPosition,
                voice = if (useCustom) custom.text.toString().trim() else "",
                soft = soft.isChecked,
                playful = playful.isChecked,
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Save and play", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val playButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            fun save(andPlay: Boolean) {
                saveButton.isEnabled = false
                playButton.isEnabled = false
                val changed = edited()
                thread(isDaemon = true) {
                    val result = runCatching {
                        val saved = relay.update(cast.series, changed)
                        // Claude is told each character's age and gender from the phone's list.
                        if (changed.speaker != entry.speaker) SeriesCast(activity, cast.series).fixSpeaker(entry.name, changed.speaker)
                        // Clips the phone saved were made with the old voice.
                        PcVoice.clearSavedClips(activity)
                        val clip = if (andPlay) {
                            relay.sample(cast.series, saved, settings.voiceEngine.quality, File(activity.cacheDir, "voice-sample.wav"))
                        } else null
                        saved to clip
                    }
                    activity.runOnUiThread {
                        saveButton.isEnabled = true
                        playButton.isEnabled = true
                        result.onSuccess { (saved, clip) ->
                            Toast.makeText(activity, "${saved.name}: ${saved.sounds}", Toast.LENGTH_SHORT).show()
                            if (clip != null) play(activity, clip) else dialog.dismiss()
                        }.onFailure {
                            Toast.makeText(activity, "Could not save: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            saveButton.setOnClickListener { save(andPlay = false) }
            playButton.setOnClickListener { save(andPlay = true) }
        }
        dialog.show()
    }

    private fun play(activity: Activity, clip: File) {
        val player = MediaPlayer()
        try {
            player.setDataSource(clip.path)
            player.setOnCompletionListener { it.release() }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            player.release()
            Toast.makeText(activity, "Could not play the sample: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
