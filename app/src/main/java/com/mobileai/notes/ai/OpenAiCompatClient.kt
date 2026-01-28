package com.mobileai.notes.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        userImagePngBytes: ByteArray? = null,
        temperature: Float = 0.4f,
        maxTokens: Int = 2048,
    ): String = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemPrompt))
                },
            )
            if (userImagePngBytes == null) {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(userText))
                    },
                )
            } else {
                val b64 = Base64.encodeToString(userImagePngBytes, Base64.NO_WRAP)
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(userText)) })
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("image_url"))
                                        put(
                                            "image_url",
                                            buildJsonObject { put("url", JsonPrimitive("data:image/png;base64,$b64")) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }

        val requestJson =
            buildJsonObject {
                put("model", JsonPrimitive(model))
                put("messages", messages)
                put("temperature", JsonPrimitive(temperature))
                put("max_tokens", JsonPrimitive(maxTokens))
            }
        val body = requestJson.toString()

        val req =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            val parsed = json.decodeFromString(ChatCompletionsResponse.serializer(), raw)
            val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (text.isNotBlank()) return@withContext text
            // Some providers return content in parts; fallback to join.
            val parts = parsed.choices.firstOrNull()?.message?.contentParts.orEmpty()
            parts.joinToString("") { it.text.orEmpty() }.trim()
        }
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/models"
        val req =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            runCatching {
                json.decodeFromString(ListModelsResponse.serializer(), raw).data.map { it.id }
            }.getOrElse { emptyList() }
        }
    }
}

@Serializable
private data class ChatCompletionsResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val message: ChatMessageOut,
)

@Serializable
private data class ChatMessageOut(
    val content: String? = null,
    val contentParts: List<ContentPartOut>? = null,
)

@Serializable
private data class ContentPartOut(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
private data class ListModelsResponse(
    val data: List<ModelItem> = emptyList(),
)

@Serializable
private data class ModelItem(
    val id: String,
)
