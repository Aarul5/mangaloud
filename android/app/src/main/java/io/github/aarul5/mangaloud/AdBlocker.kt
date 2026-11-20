package io.github.aarul5.mangaloud

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Host-based ad blocking: a request is blocked when its host, or any parent domain of it,
 * is on the list. The list is a small built-in one plus a downloadable public list.
 */
class AdBlocker(private val context: Context) {
    @Volatile
    private var blockedHosts: Set<String> = emptySet()

    val cosmeticCss: String = context.assets.open("cosmetic.css").bufferedReader().use { it.readText() }

    val overlayGuardJs: String = context.assets.open("overlay_guard.js").bufferedReader().use { it.readText() }

    // Renamed when the source list changes, so existing installs fetch the new one.
    private val downloadedList get() = File(context.filesDir, "blocklist-hagezi-pro.txt")

    val hasDownloadedList get() = downloadedList.exists()

    val size get() = blockedHosts.size

    init {
        reload()
    }

    fun isBlocked(host: String): Boolean {
        var candidate = host.lowercase()
        while (true) {
            if (candidate in blockedHosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
            if (!candidate.contains('.')) return false
        }
    }

    /** Downloads the public list and swaps it in. Blocking call: run it off the main thread. */
    fun updateFromNetwork(listUrl: String = DEFAULT_LIST_URL): Int {
        val connection = URL(listUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) error("Blocklist download failed: HTTP ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val hosts = parseHosts(text.lineSequence())
            if (hosts.size < 100) error("Blocklist looks wrong: only ${hosts.size} hosts")
            downloadedList.writeText(hosts.joinToString("\n"))
        } finally {
            connection.disconnect()
        }
        reload()
        return blockedHosts.size
    }

    private fun reload() {
        val builtIn = context.assets.open("adblock_hosts.txt").bufferedReader().useLines { parseHosts(it) }
        val downloaded = if (downloadedList.exists()) downloadedList.useLines { parseHosts(it) } else emptySet()
        blockedHosts = builtIn + downloaded
    }

    /** Accepts plain "domain" lines and hosts-file "0.0.0.0 domain" lines. */
    private fun parseHosts(lines: Sequence<String>): Set<String> = lines
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { it.split(Regex("\\s+")).last().lowercase() }
        .filter { it.contains('.') && it != "localhost" && it != "0.0.0.0" }
        .toHashSet()

    companion object {
        const val DEFAULT_LIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt"
    }
}
