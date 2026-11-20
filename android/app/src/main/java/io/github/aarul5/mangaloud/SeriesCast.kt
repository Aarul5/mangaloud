package io.github.aarul5.mangaloud

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The characters met so far in one series, kept on the phone across chapters. Claude gets the
 * list with each batch so it reuses the same label for the same person. Each character also
 * keeps the speaker type (age and gender) seen most often, so one misread page does not
 * change their voice.
 */
class SeriesCast(context: Context, val seriesKey: String) {
    private class Member(
        val name: String,
        val speakerCounts: MutableMap<String, Int>,
        var look: String = "",
        /** Set on the Character voices screen: wins over what Claude guessed. */
        var fixedSpeaker: String = "",
    ) {
        val lines get() = speakerCounts.values.sum()
        val speaker get() = fixedSpeaker.ifEmpty { speakerCounts.maxByOrNull { it.value }?.key ?: VoiceStyle.UNKNOWN }
    }

    private val file = File(File(context.filesDir, "cast").apply { mkdirs() }, sha256(seriesKey).take(24) + ".json")
    private val members = LinkedHashMap<String, Member>()
    private var loaded = false

    /** Counts who spoke in freshly read bubbles, and saves the list when it changed. */
    @Synchronized
    fun record(bubbles: List<Bubble>) {
        load()
        var changed = false
        for (bubble in bubbles) {
            val name = bubble.character
            if (name.isEmpty() || bubble.speaker == VoiceStyle.NARRATOR) continue
            val member = members.getOrPut(keyOf(name)) { Member(name, mutableMapOf()) }
            // A woman's line under a man's label (or the other way round) is someone else
            // labelled wrongly: it must not teach the list their look or type.
            if (member.lines > 0 && Gender.differ(member.speaker, bubble.speaker)) continue
            member.speakerCounts.merge(bubble.speaker, 1, Int::plus)
            // The first description sticks, so the label keeps meaning the same person.
            if (member.look.isEmpty() && bubble.look.isNotBlank()) member.look = bubble.look.trim()
            changed = true
        }
        if (changed) save()
    }

    /** Fixes a character's age and gender, chosen by hand, for both their voice and Claude's list. */
    @Synchronized
    fun fixSpeaker(name: String, speaker: String) {
        load()
        val member = members.getOrPut(keyOf(name)) { Member(name, mutableMapOf()) }
        if (member.fixedSpeaker == speaker) return
        member.fixedSpeaker = speaker
        save()
    }

    /** The bubble with its character's usual speaker type and their label as first written. */
    @Synchronized
    fun settle(bubble: Bubble): Bubble {
        load()
        val member = members[keyOf(bubble.character)] ?: return bubble
        // Claude saw a woman where the label is a man's (or the other way round): the label is
        // wrong, so read the line in a plain voice of the gender it saw, not that character's.
        if (Gender.differ(member.speaker, bubble.speaker)) return bubble.copy(character = "")
        return bubble.copy(character = member.name, speaker = member.speaker)
    }

    /** The series' characters as (name, speaker type), the ones who speak most first. */
    @Synchronized
    fun characters(): List<Pair<String, String>> {
        load()
        return members.values.sortedWith(compareBy({ it.name.lowercase() != "hero" }, { -it.lines })).map { it.name to it.speaker }
    }

    /** The characters to tell Claude about, the ones who speak most first. */
    @Synchronized
    fun promptList(): List<String> {
        load()
        return members.values.sortedByDescending { it.lines }.take(MAX_IN_PROMPT).map { member ->
            // A look of the other gender ("girl with twin tails" on a man) came from a wrong label.
            val look = member.look.takeUnless { Gender.lookDiffers(it, member.speaker) }.orEmpty()
            "\"${member.name}\" (${member.speaker})" + if (look.isNotEmpty()) ": $look" else ""
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText()).getJSONObject("characters")
            for (key in json.keys()) {
                val entry = json.getJSONObject(key)
                val counts = entry.getJSONObject("speakers")
                members[key] = Member(
                    entry.getString("name"),
                    counts.keys().asSequence().associateWith { counts.getInt(it) }.toMutableMap(),
                    entry.optString("look", ""),
                    entry.optString("fixedSpeaker", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Starting a new cast list for $seriesKey: ${e.message}")
            members.clear()
        }
    }

    private fun save() {
        val characters = JSONObject()
        for ((key, member) in members) {
            characters.put(
                key,
                JSONObject().put("name", member.name).put("speakers", JSONObject(member.speakerCounts.toMap())).put("look", member.look)
                    .put("fixedSpeaker", member.fixedSpeaker),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("series", seriesKey).put("characters", characters).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Male and female speaker types, to catch a label given to someone of the other gender. */
    object Gender {
        private val male = setOf("man", "boy", "old man")
        private val female = setOf("woman", "girl", "old woman")
        private val femaleWords = Regex("""\b(girl|woman|women|female|lady|she|her|mother|sister|queen|princess)\b""", RegexOption.IGNORE_CASE)
        private val maleWords = Regex("""\b(boy|man|men|male|guy|he|his|father|brother|king|prince)\b""", RegexOption.IGNORE_CASE)

        /** True when one type is male and the other female. Unknown, narrator and creature never differ. */
        fun differ(a: String, b: String): Boolean = (a in male && b in female) || (a in female && b in male)

        /** True when a look plainly describes the other gender from [speaker]. */
        fun lookDiffers(look: String, speaker: String): Boolean = when (speaker) {
            in male -> femaleWords.containsMatchIn(look) && !maleWords.containsMatchIn(look)
            in female -> maleWords.containsMatchIn(look) && !femaleWords.containsMatchIn(look)
            else -> false
        }
    }

    companion object {
        private const val TAG = "SeriesCast"
        private const val MAX_IN_PROMPT = 40
        private const val MAX_NAME = 60

        private val NOT_A_CHARACTER = setOf("unknown", "narrator", "none", "n/a", "crowd", "everyone")
        private val SPACES = Regex("""\s+""")
        private val TRAILING_NOTE = Regex("""\s*\((?:${VoiceStyle.SPEAKERS.joinToString("|")})\)$""")

        /** Tidies a label from Claude: no quotes, no "(man)" copied from the list, nothing that is not a person. */
        fun cleanName(raw: String): String {
            val name = raw.replace(SPACES, " ").trim().trim('"', '\'', '“', '”').replace(TRAILING_NOTE, "").trim().take(MAX_NAME)
            return if (name.lowercase() in NOT_A_CHARACTER) "" else name
        }

        private fun keyOf(name: String) = name.lowercase()

        // Path parts that mark where the chapter starts: "chapter-12", "ch_3", "episode-4", "12".
        private val CHAPTER_WORD = Regex("""^(chapter|chap|ch|episode|ep|viewer|read|reader)$""")
        // Anything after the chapter number ("-v1", "-raw", ".html") belongs to the chapter too.
        private val CHAPTER_PART = Regex("""^(chapter|chap|ch|episode|ep|c|e)[-_.]?\d+([-_.].*)?$""")
        private val CHAPTER_SUFFIX = Regex("""[-_](chapter|chap|ch|episode|ep)[-_.]?\d+([-_.].*)?$""")
        // Numbered mirrors like "w112." or "www2." change over time; the site stays the same.
        private val MIRROR_PREFIX = Regex("""^(www\d*|w\d+)\.""")
        private val NUMBER = Regex("""^\d+([-_.]\d+)?(\.html?)?$""")

        /**
         * A name for the series a chapter page belongs to, from its address: the site plus the
         * path up to the chapter part. "site.com/manga/solo-hunter/chapter-12" and
         * ".../chapter-13" both give "site.com/manga/solo-hunter". Sites that leave the series
         * out of chapter addresses share one list per site.
         */
        fun seriesKeyOf(pageUrl: String): String {
            val uri = Uri.parse(pageUrl)
            val host = uri.host?.lowercase()?.replace(MIRROR_PREFIX, "") ?: return "unknown"
            // Webtoon-style viewers keep the series in the query.
            for (param in listOf("title_no", "titleId", "series_id", "manga_id")) {
                uri.getQueryParameter(param)?.let { return "$host/$param=$it" }
            }
            return seriesKeyOf(host, uri.pathSegments)
        }

        /** True when the address has a chapter part, so it is a chapter page rather than a list. */
        fun looksLikeChapter(pageUrl: String): Boolean {
            val uri = Uri.parse(pageUrl)
            val host = uri.host?.lowercase()?.replace(MIRROR_PREFIX, "") ?: return false
            return looksLikeChapter(host, uri.pathSegments)
        }

        internal fun looksLikeChapter(host: String, pathSegments: List<String>): Boolean {
            val segments = pathSegments.filter { it.isNotEmpty() }
            return seriesKeyOf(host, segments) != (listOf(host) + segments.map { it.lowercase() }).joinToString("/")
        }

        internal fun seriesKeyOf(host: String, pathSegments: List<String>): String {
            val segments = pathSegments.map { it.lowercase() }
            val kept = mutableListOf<String>()
            for ((i, segment) in segments.withIndex()) {
                if (CHAPTER_WORD.matches(segment) || CHAPTER_PART.matches(segment)) break
                // A bare number at the end, after the series name, is the chapter.
                if (NUMBER.matches(segment) && i == segments.lastIndex && kept.isNotEmpty()) break
                val trimmed = segment.replace(CHAPTER_SUFFIX, "")
                if (trimmed.isNotEmpty()) kept += trimmed
                if (trimmed != segment) break
            }
            return (listOf(host) + kept).joinToString("/")
        }

        private fun sha256(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
