package io.github.aarul5.mangaloud

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Option 2: sends slices to the relay on the PC, which runs `claude -p` on your Pro plan. */
class RelayOcr(
    private val baseUrl: String,
    private val token: String,
    private val model: String,
    private val effort: String,
) : OcrProvider {

    @Volatile
    override var lastModel: String = ""
        private set

    override fun recognize(slices: List<Slice>, rightToLeft: Boolean, context: OcrContext): List<List<RawBubble>> {
        val body = JSONObject()
            .put("prompt", OcrPrompt.text(slices.size, rightToLeft, context))
            .put("guide", OcrPrompt.GUIDE)
            .put("schema", JSONObject(OcrPrompt.schema))
            .put("model", model)
            .put("effort", effort)
            .put("images", JSONArray(slices.map {
                JSONObject()
                    .put("mediaType", "image/jpeg")
                    .put("data", Base64.encodeToString(it.jpeg, Base64.NO_WRAP))
            }))
            .toString()
            .toByteArray()

        val connection = URL(baseUrl.trimEnd('/') + "/ocr").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 300_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(detail).getString("error") }.getOrDefault(detail)
                throw IOException("Relay error $code: ${message.take(200)}")
            }
            val reply = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            // Relays from before this send no meta; fall back to what was asked for.
            val meta = reply.optJSONObject("meta")
            // An older relay ignores the guide and reads with no rules at all: better to say so.
            if (meta?.optString("guide").isNullOrBlank()) {
                throw IOException("The relay on the PC is older than this app. Get the latest code there and restart it.")
            }
            val usedModel = meta?.optString("model").orEmpty().ifBlank { model.ifBlank { "default model" } }
            val usedEffort = meta?.optString("effort").orEmpty().ifBlank { effort }
            lastModel = listOf(ModelName.short(usedModel), usedEffort).filter { it.isNotBlank() }.joinToString(" · ")
            return OcrPrompt.parse(reply, slices.size)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Tailscale (100.64.0.0/10 or *.ts.net) encrypts the hop; loopback never leaves the phone. */
        fun isEncryptedRoute(relayUrl: String): Boolean {
            val host = android.net.Uri.parse(relayUrl.trim()).host?.lowercase() ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".ts.net")) return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }
    }
}
