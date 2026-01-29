package com.mobileai.notes.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun messages(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        userImagePngBytes: ByteArray? = null,
        temperature: Float = 0.4f,
        maxTokens: Int = 2048,
    ): String = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/v1")) "$root/messages" else "$root/v1/messages"

        val content =
            buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(userText)) })
                if (userImagePngBytes != null) {
                    val b64 = Base64.encodeToString(userImagePngBytes, Base64.NO_WRAP)
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("image"))
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", JsonPrimitive("base64"))
                                    put("media_type", JsonPrimitive("image/png"))
                                    put("data", JsonPrimitive(b64))
                                },
                            )
                        },
                    )
                }
            }

        val requestJson =
            buildJsonObject {
                put("model", JsonPrimitive(model))
                put("max_tokens", JsonPrimitive(maxTokens))
                put("temperature", JsonPrimitive(temperature))
                put("system", JsonPrimitive(systemPrompt))
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", content)
                            },
                        )
                    },
                )
            }

        val req =
            Request.Builder()
                .url(url)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            val parsed = json.decodeFromString(AnthropicMessagesResponse.serializer(), raw)
            parsed.content.joinToString("") { it.text.orEmpty() }.trim()
        }
    }

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/v1")) "$root/models" else "$root/v1/models"

        val req =
            Request.Builder()
                .url(url)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")

            // Anthropic's schema varies by version; parse best-effort.
            val element = json.parseToJsonElement(raw)
            val rootObj = element as? JsonObject ?: return@withContext emptyList()

            val data = rootObj["data"]
            when (data) {
                is JsonArray -> {
                    data.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        (obj["id"] as? JsonPrimitive)?.content
                            ?: (obj["name"] as? JsonPrimitive)?.content
                    }
                }
                else -> emptyList()
            }
        }
    }
}

@Serializable
private data class AnthropicMessagesResponse(
    val content: List<AnthropicContentPart> = emptyList(),
)

@Serializable
private data class AnthropicContentPart(
    val type: String? = null,
    val text: String? = null,
)
