package io.github.aarul5.mangaloud

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException

/** Option 1: calls the Claude API straight from the phone with the user's own API key. */
class ClaudeApiOcr(apiKey: String, private val model: String, private val guide: String) : OcrProvider {
    override val lastModel: String get() = ModelName.short(model)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    private val schema: JsonOutputFormat.Schema = run {
        @Suppress("UNCHECKED_CAST")
        val map = ObjectMapper().readValue(OcrPrompt.schema, Map::class.java) as Map<String, Any>
        JsonOutputFormat.Schema.builder()
            .apply { map.forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }
            .build()
    }

    override fun recognize(slices: List<Slice>, rightToLeft: Boolean, context: OcrContext): List<List<RawBubble>> {
        val content = mutableListOf<ContentBlockParam>()
        slices.forEachIndexed { i, slice ->
            content += ContentBlockParam.ofText(TextBlockParam.builder().text("Slice $i:").build())
            content += ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .data(Base64.encodeToString(slice.jpeg, Base64.NO_WRAP))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build()
                    )
                    .build()
            )
        }
        content += ContentBlockParam.ofText(
            TextBlockParam.builder().text(OcrPrompt.full(guide, slices.size, rightToLeft, context)).build()
        )

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .addUserMessageOfContentBlockParams(content)
            .apply {
                // If a safety check declines a page, let the API retry it on a fallback model.
                if (model in MODELS_WITH_SERVER_FALLBACK) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val response = client.messages().create(params)
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            return List(slices.size) { emptyList() }
        }
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw IOException("Claude API returned no text")
        return OcrPrompt.parse(text, slices.size)
    }

    companion object {
        private val MODELS_WITH_SERVER_FALLBACK = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
