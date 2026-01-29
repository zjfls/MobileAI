package com.mobileai.notes.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GoogleGeminiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun generateContent(
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
        val v1 = if (root.endsWith("/v1beta")) root else "$root/v1beta"
        val cleanModel = model.removePrefix("models/")
        val url = "$v1/models/$cleanModel:generateContent?key=$apiKey"

        val parts =
            buildJsonArray {
                add(buildJsonObject { put("text", JsonPrimitive(userText)) })
                if (userImagePngBytes != null) {
                    val b64 = Base64.encodeToString(userImagePngBytes, Base64.NO_WRAP)
                    add(
                        buildJsonObject {
                            put(
                                "inline_data",
                                buildJsonObject {
                                    put("mime_type", JsonPrimitive("image/png"))
                                    put("data", JsonPrimitive(b64))
                                },
                            )
                        },
                    )
                }
            }

        val requestJson =
            buildJsonObject {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", JsonPrimitive(systemPrompt)) })
                            },
                        )
                    },
                )
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("parts", parts)
                            },
                        )
                    },
                )
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", JsonPrimitive(temperature))
                        put("maxOutputTokens", JsonPrimitive(maxTokens))
                    },
                )
            }

        val req =
            Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            val parsed = json.decodeFromString(GeminiGenerateContentResponse.serializer(), raw)
            val partsText =
                parsed.candidates.firstOrNull()?.content?.parts.orEmpty()
                    .mapNotNull { it.text }
                    .joinToString("")
                    .trim()
            partsText
        }
    }

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val v1 = if (root.endsWith("/v1beta")) root else "$root/v1beta"
        val url = "$v1/models?key=$apiKey"

        val req =
            Request.Builder()
                .url(url)
                .get()
                .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            val parsed = json.decodeFromString(GeminiListModelsResponse.serializer(), raw)
            parsed.models.mapNotNull { it.name?.removePrefix("models/") }.distinct()
        }
    }
}

@Serializable
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
private data class GeminiPart(
    val text: String? = null,
)

@Serializable
private data class GeminiListModelsResponse(
    val models: List<GeminiModel> = emptyList(),
)

@Serializable
private data class GeminiModel(
    val name: String? = null,
)

