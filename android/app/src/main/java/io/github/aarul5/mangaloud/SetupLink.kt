package io.github.aarul5.mangaloud

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.Toast

/**
 * The relay prints a link like mangaloud://setup?name=PC&url=http://100.x.x.x:8787&token=…
 * Opening it on the phone (or pasting it in Settings) adds that relay, so nothing is typed.
 */
object SetupLink {
    fun isSetupLink(uri: Uri?): Boolean = uri?.scheme == "mangaloud" && uri.host == "setup"

    fun parse(text: String): Relay? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull()
        if (!isSetupLink(uri)) return null
        val url = uri!!.getQueryParameter("url")?.trim().orEmpty()
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        val scheme = Uri.parse(url).scheme
        if (token.isEmpty() || (scheme != "http" && scheme != "https")) return null
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifEmpty { "PC" }
        return Relay(name = name.take(40), url = url, token = token, model = "")
    }

    /**
     * Asks before adding, since any app or page could open such a link. A relay with the same
     * address gets the new name and token and keeps its model and effort.
     */
    fun confirmAndAdd(activity: Activity, settings: Settings, relay: Relay, onAdded: () -> Unit = {}) {
        AlertDialog.Builder(activity)
            .setTitle("Add this PC relay?")
            .setMessage("${relay.name}\n${relay.url}\n\nOnly add relays from your own PC.")
            .setPositiveButton("Add and use it") { _, _ ->
                val relays = settings.relays.filter { it.url.isNotBlank() || it.token.isNotBlank() }.toMutableList()
                val index = relays.indexOfFirst { it.url.trimEnd('/') == relay.url.trimEnd('/') }
                if (index >= 0) {
                    relays[index] = relays[index].copy(name = relay.name, token = relay.token)
                } else {
                    relays += relay
                }
                settings.relays = relays
                settings.activeRelayIndex = if (index >= 0) index else relays.lastIndex
                settings.ocrMode = OcrMode.PC_RELAY
                Toast.makeText(activity, "Relay added: ${relay.name}", Toast.LENGTH_SHORT).show()
                onAdded()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
