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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GoogleGeminiClient(
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(150, TimeUnit.SECONDS)
            .connectTimeout(150, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(150, TimeUnit.SECONDS)
            .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun sanitizeBodyPreview(body: String): String {
        // Avoid storing huge base64 blobs in debug payloads (multimodal).
        val base64Runs = Regex("[A-Za-z0-9+/=]{2000,}")
        return base64Runs.replace(body) { m -> "<base64 len=${m.value.length}>" }
    }

    private fun headersToPairs(headers: Headers): List<Pair<String, String>> =
        headers.names()
            .sorted()
            .flatMap { name -> headers.values(name).map { value -> name to value } }

    suspend fun generateContentWithRawResponse(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        userImagePngBytes: ByteArray? = null,
        requireJsonObject: Boolean = false,
        jsonEnvelopeKey: String = "questions",
        expectedQuestionsCountForSchema: Int? = null,
        temperature: Float? = 0.4f,
        topP: Float? = null,
        maxTokens: Int? = null,
    ): AiHttpResult = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val v1 = if (root.endsWith("/v1beta")) root else "$root/v1beta"
        val cleanModel = model.removePrefix("models/")
        val url = "$v1/models/$cleanModel:generateContent?key=$apiKey"
        val debugUrl = "$v1/models/$cleanModel:generateContent?key=<redacted>"

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

        val questionSchema =
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put("title", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("text", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("image_url", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("image_base64", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("title"))
                        add(JsonPrimitive("text"))
                    },
                )
            }

        val questionsArraySchema =
            buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", questionSchema)
                expectedQuestionsCountForSchema?.let { n ->
                    put("minItems", JsonPrimitive(n))
                    put("maxItems", JsonPrimitive(n))
                }
            }

        val envelopeSchema =
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put(jsonEnvelopeKey, questionsArraySchema)
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive(jsonEnvelopeKey))
                    },
                )
            }

        suspend fun execute(withJsonObject: Boolean, withSchema: Boolean): AiHttpResult {
            val requestJson =
                buildJsonObject {
                    if (systemPrompt.isNotBlank()) {
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
                    }
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
                            temperature?.let { put("temperature", JsonPrimitive(it)) }
                            topP?.let { put("topP", JsonPrimitive(it)) }
                            maxTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
                            if (withJsonObject) {
                                // Gemini: request JSON output when supported.
                                put("responseMimeType", JsonPrimitive("application/json"))
                            }
                            if (withSchema) {
                                put("responseSchema", envelopeSchema)
                            }
                        },
                    )
                }

            val body = requestJson.toString()
            val requestPreview = sanitizeBodyPreview(body)

            val req =
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

            val call = http.newCall(req)
            call.await().use { resp ->
                val raw = resp.body?.string().orEmpty()
	                val exchange =
	                    AiHttpExchange(
	                        requestUrl = debugUrl,
	                        requestBodyPreview = requestPreview,
	                        requestBodyRaw = null,
	                        responseProtocol = resp.protocol.toString(),
	                        responseCode = resp.code,
	                        responseMessage = resp.message,
	                        responseHeaders = headersToPairs(resp.headers),
	                        responseBody = raw,
                    )
                if (!resp.isSuccessful) throw AiHttpException(exchange)
                val parsed = json.decodeFromString(GeminiGenerateContentResponse.serializer(), raw)
                return AiHttpResult(
                    text =
                        parsed.candidates.firstOrNull()?.content?.parts.orEmpty()
                            .mapNotNull { it.text }
                            .joinToString("")
                            .trim(),
                    exchange = exchange,
                )
            }
        }

        try {
            execute(withJsonObject = requireJsonObject, withSchema = requireJsonObject && expectedQuestionsCountForSchema != null)
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            val likelyUnsupported =
                requireJsonObject &&
                    (msg.contains("responseMimeType", ignoreCase = true) ||
                        msg.contains("responseSchema", ignoreCase = true) ||
                        msg.contains("unrecognized", ignoreCase = true) ||
                        msg.contains("unknown", ignoreCase = true) ||
                        msg.contains("unsupported", ignoreCase = true))
            if (!likelyUnsupported) throw e

            // Fallback 1: try JSON mime type only.
            runCatching { execute(withJsonObject = true, withSchema = false) }
                .getOrElse {
                    // Fallback 2: plain text.
                    execute(withJsonObject = false, withSchema = false)
                }
        }
    }

    suspend fun generateContent(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        userImagePngBytes: ByteArray? = null,
        requireJsonObject: Boolean = false,
        jsonEnvelopeKey: String = "questions",
        expectedQuestionsCountForSchema: Int? = null,
        temperature: Float? = 0.4f,
        topP: Float? = null,
        maxTokens: Int? = null,
    ): String {
        return generateContentWithRawResponse(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            userText = userText,
            userImagePngBytes = userImagePngBytes,
            requireJsonObject = requireJsonObject,
            jsonEnvelopeKey = jsonEnvelopeKey,
            expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
        ).text
    }

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/')
        val v1 = if (root.endsWith("/v1beta")) root else "$root/v1beta"
        val url = "$v1/models?key=$apiKey"
        val debugUrl = "$v1/models?key=<redacted>"

        val req =
            Request.Builder()
                .url(url)
                .get()
                .build()

        val call = http.newCall(req)
        call.await().use { resp ->
            val raw = resp.body?.string().orEmpty()
	            val exchange =
	                AiHttpExchange(
	                    requestUrl = debugUrl,
	                    requestBodyPreview = "",
	                    requestBodyRaw = null,
	                    responseProtocol = resp.protocol.toString(),
	                    responseCode = resp.code,
	                    responseMessage = resp.message,
	                    responseHeaders = headersToPairs(resp.headers),
	                    responseBody = raw,
	                )
            if (!resp.isSuccessful) throw AiHttpException(exchange)
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
