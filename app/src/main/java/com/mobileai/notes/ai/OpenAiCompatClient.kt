package com.mobileai.notes.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiCompatClient(
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            // Keep a small cushion over coroutine timeouts (e.g. 180s) to avoid premature OkHttp timeouts.
            .callTimeout(260, TimeUnit.SECONDS)
            .connectTimeout(260, TimeUnit.SECONDS)
            .readTimeout(260, TimeUnit.SECONDS)
            .writeTimeout(260, TimeUnit.SECONDS)
            .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private enum class JsonEnforcement {
        NONE,
        RESPONSE_JSON_OBJECT,
        RESPONSE_JSON_SCHEMA,
        TOOLS_STRICT,
    }

    private fun sanitizeBodyPreview(body: String): String {
        // Avoid storing huge base64 blobs in debug payloads (multimodal).
        val base64Runs = Regex("[A-Za-z0-9+/=]{2000,}")
        return base64Runs.replace(body) { m -> "<base64 len=${m.value.length}>" }
    }

    private fun maybeKeepRawRequestBody(body: String): String? {
        // Keep request JSON for debugging; avoid very large base64 payloads by returning a sanitized JSON.
        val maxChars = 120_000
        if (body.length <= maxChars) return body
        return sanitizeBodyPreview(body)
    }

	    private fun headersToPairs(headers: Headers): List<Pair<String, String>> =
	        headers.names()
	            .sorted()
	            .flatMap { name -> headers.values(name).map { value -> name to value } }

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

    private fun JsonElement?.asStr(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return runCatching { p.content }.getOrNull()
    }

    private fun extractToolArgsOpenAiCompat(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val msg =
            root.asObj()
                ?.get("choices")
                .asArr()
                ?.firstOrNull()
                .asObj()
                ?.get("message")
                .asObj()
                ?: return null

        val toolCalls = msg["tool_calls"].asArr()
        val toolArgs =
            toolCalls
                ?.firstOrNull()
                .asObj()
                ?.get("function")
                .asObj()
                ?.get("arguments")
                .asStr()
                ?.trim()
                .orEmpty()
        if (toolArgs.isNotBlank()) return toolArgs

        val fnArgs =
            msg["function_call"]
                .asObj()
                ?.get("arguments")
                .asStr()
                ?.trim()
                .orEmpty()
        if (fnArgs.isNotBlank()) return fnArgs

        return null
    }

    private fun extractTextOpenAiCompat(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null

        // Some gateways return: {"error":{"message":"..."}} or {"error":{"type":...,"message":...},"type":"error"}
        val rootObj = root.asObj()
        val errMsg =
            rootObj
                ?.get("error")
                .asObj()
                ?.get("message")
                .asStr()
                ?.trim()
        if (!errMsg.isNullOrBlank()) return errMsg

        val msg =
            rootObj
                ?.get("choices")
                .asArr()
                ?.firstOrNull()
                .asObj()
                ?.get("message")
                .asObj()
                ?: return null

        // Prefer tool/function args (strict JSON mode).
        extractToolArgsOpenAiCompat(raw)?.let { return it }

        // Standard content (string or parts array).
        val contentEl = msg["content"]
        val contentText = contentEl.asStr()?.trim().orEmpty()
        if (contentText.isNotBlank()) return contentText

        val parts =
            contentEl.asArr()
                ?.mapNotNull { it.asObj() }
                ?.mapNotNull { it["text"].asStr() }
                ?.joinToString("")
                ?.trim()
                .orEmpty()
        if (parts.isNotBlank()) return parts

        val reasoning = msg["reasoning_content"].asStr()?.trim().orEmpty()
        if (reasoning.isNotBlank()) return reasoning

        // Last resort: if schema changed but still has something like "text" at top-level.
        val topText = rootObj?.get("text").asStr()?.trim().orEmpty()
        if (topText.isNotBlank()) return topText

        return null
    }

    suspend fun chatWithRawResponse(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        userImagePngBytes: ByteArray? = null,
        userImageUrl: String? = null,
        requireJsonObject: Boolean = false,
        jsonEnvelopeKey: String = "questions",
        expectedQuestionsCountForSchema: Int? = null,
        temperature: Float? = 0.4f,
        topP: Float? = null,
        maxTokens: Int? = null,
        onRequestPrepared: ((requestBodyRaw: String?, requestBodyPreview: String) -> Unit)? = null,
    ): AiHttpResult = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val messages = buildJsonArray {
            if (systemPrompt.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(systemPrompt))
                    },
                )
            }
            val imageUrlToSend = userImageUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (userImagePngBytes == null && imageUrlToSend == null) {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(userText))
                    },
                )
            } else {
                val urlValue =
                    if (imageUrlToSend != null) {
                        imageUrlToSend
                    } else {
                        val b64 = Base64.encodeToString(requireNotNull(userImagePngBytes), Base64.NO_WRAP)
                        "data:image/png;base64,$b64"
                    }
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
                                            buildJsonObject { put("url", JsonPrimitive(urlValue)) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }

	        val questionSchema =
	            buildJsonObject {
	                put("type", JsonPrimitive("object"))
	                put("additionalProperties", JsonPrimitive(false))
	                put(
	                    "properties",
	                    buildJsonObject {
	                        put(
	                            "title",
	                            buildJsonObject {
	                                put("type", JsonPrimitive("string"))
	                            },
	                        )
	                        put(
	                            "text",
	                            buildJsonObject {
	                                put("type", JsonPrimitive("string"))
	                            },
	                        )
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
	                put("additionalProperties", JsonPrimitive(false))
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
        val toolFunctionName = "submit_${jsonEnvelopeKey}"

        suspend fun execute(enforcement: JsonEnforcement): AiHttpResult {
            val requestJson =
                buildJsonObject {
                    put("model", JsonPrimitive(model))
                    put("messages", messages)
                    temperature?.let { put("temperature", JsonPrimitive(it)) }
                    topP?.let { put("top_p", JsonPrimitive(it)) }
                    maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
                    when (enforcement) {
                        JsonEnforcement.NONE -> Unit
                        JsonEnforcement.RESPONSE_JSON_OBJECT -> {
                            put(
                                "response_format",
                                buildJsonObject { put("type", JsonPrimitive("json_object")) },
                            )
                        }
                        JsonEnforcement.RESPONSE_JSON_SCHEMA -> {
                            put(
                                "response_format",
                                buildJsonObject {
                                    put("type", JsonPrimitive("json_schema"))
                                    put(
                                        "json_schema",
                                        buildJsonObject {
                                            put("name", JsonPrimitive("${jsonEnvelopeKey}_envelope"))
                                            put("strict", JsonPrimitive(true))
                                            put("schema", envelopeSchema)
                                        },
                                    )
                                },
                            )
                        }
                        JsonEnforcement.TOOLS_STRICT -> {
                            put(
                                "tools",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("function"))
                                            put(
                                                "function",
                                                buildJsonObject {
                                                    put("name", JsonPrimitive(toolFunctionName))
                                                    put("description", JsonPrimitive("Return ${jsonEnvelopeKey} strictly as JSON."))
                                                    put("parameters", envelopeSchema)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put(
                                "tool_choice",
                                buildJsonObject {
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject { put("name", JsonPrimitive(toolFunctionName)) },
                                    )
                                },
                            )
                        }
                    }
                }
			            val body = requestJson.toString()
			            val requestPreview = sanitizeBodyPreview(body)
	                    val requestRaw = maybeKeepRawRequestBody(body)
	                    runCatching { onRequestPrepared?.invoke(requestRaw, requestPreview) }
			
			            val req =
			                Request.Builder()
		                    .url(url)
	                    .header("Authorization", "Bearer $apiKey")
	                    .post(body.toRequestBody("application/json".toMediaType()))
	                    .build()
		
	            val call = http.newCall(req)
                val resp =
                    try {
                        call.await()
                    } catch (e: IOException) {
                        val exchange =
                            AiHttpExchange(
                                requestUrl = url,
                                requestBodyPreview = requestPreview,
                                requestBodyRaw = requestRaw,
                                responseProtocol = "(no response)",
                                responseCode = 0,
                                responseMessage = "IOException",
                                responseHeaders = emptyList(),
                                responseBody = buildString { appendLine("exception=${e.javaClass.name}"); appendLine("message=${e.message.orEmpty()}") },
                            )
                        throw AiHttpException(exchange)
                    }
		            resp.use { r ->
		                val raw = r.body?.string().orEmpty()
		                val exchange =
		                    AiHttpExchange(
	                        requestUrl = url,
	                        requestBodyPreview = requestPreview,
                            requestBodyRaw = requestRaw,
		                        responseProtocol = r.protocol.toString(),
		                        responseCode = r.code,
		                        responseMessage = r.message,
		                        responseHeaders = headersToPairs(r.headers),
		                        responseBody = raw,
			                    )
			                if (!r.isSuccessful) throw AiHttpException(exchange)
                    if (enforcement == JsonEnforcement.TOOLS_STRICT) {
                        val toolArgs = extractToolArgsOpenAiCompat(raw).orEmpty()
                        if (toolArgs.isBlank()) {
                            throw IllegalStateException("tools/tool_choice unsupported or ignored: no tool_calls/function_call in response")
                        }
                        return AiHttpResult(text = toolArgs, exchange = exchange)
                    }

                    val extracted = extractTextOpenAiCompat(raw)?.trim().orEmpty()
                    if (extracted.isNotBlank()) return AiHttpResult(text = extracted, exchange = exchange)

                    // Last resort: return raw response so upper layer can show it.
                    return AiHttpResult(text = raw.trim(), exchange = exchange)
		            }
		        }

        fun likelyUnsupported(e: Throwable, enforcement: JsonEnforcement): Boolean {
            val msg = e.message.orEmpty()
            val lower = msg.lowercase()

            // Don't treat auth/permission/quota errors as "unsupported feature".
            if (
                lower.contains("http 401") ||
                    lower.contains("http 403") ||
                    lower.contains("invalid api key") ||
                    lower.contains("insufficient_quota") ||
                    lower.contains("rate limit")
            ) {
                return false
            }

            val isClientRequestError =
                Regex("\\bhttp\\s+(\\d{3})\\b")
                    .find(lower)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { it in setOf(400, 404, 415, 422) }
                    ?: false
            if (isClientRequestError) return true

            val hasGeneric =
                lower.contains("unrecognized") ||
                    lower.contains("unknown") ||
                    lower.contains("unsupported") ||
                    lower.contains("not supported") ||
                    lower.contains("not allowed") ||
                    lower.contains("invalid") ||
                    lower.contains("bad request")
            return when (enforcement) {
                JsonEnforcement.NONE -> false
                JsonEnforcement.TOOLS_STRICT ->
                    hasGeneric && (msg.contains("tools", ignoreCase = true) || msg.contains("tool_choice", ignoreCase = true))
                JsonEnforcement.RESPONSE_JSON_SCHEMA ->
                    hasGeneric && (msg.contains("response_format", ignoreCase = true) || msg.contains("json_schema", ignoreCase = true))
                JsonEnforcement.RESPONSE_JSON_OBJECT ->
                    hasGeneric && (msg.contains("response_format", ignoreCase = true) || msg.contains("json_object", ignoreCase = true))
            }
        }

        if (!requireJsonObject) return@withContext execute(JsonEnforcement.NONE)

        val attempts =
            listOf(
                JsonEnforcement.TOOLS_STRICT,
                JsonEnforcement.RESPONSE_JSON_SCHEMA,
                JsonEnforcement.RESPONSE_JSON_OBJECT,
                JsonEnforcement.NONE,
            )

        var lastError: Throwable? = null
        for (enforcement in attempts) {
            try {
                return@withContext execute(enforcement)
            } catch (e: Throwable) {
                lastError = e
                if (enforcement != JsonEnforcement.NONE && likelyUnsupported(e, enforcement)) {
                    continue
                }
                throw e
            }
        }
        throw lastError ?: error("Unknown error")
    }

    suspend fun chat(
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
        return chatWithRawResponse(
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

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/models"
        val req =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
	        val call = http.newCall(req)
	        call.await().use { resp ->
	            val raw = resp.body?.string().orEmpty()
	            val exchange =
	                AiHttpExchange(
	                    requestUrl = url,
	                    requestBodyPreview = "",
	                    requestBodyRaw = null,
	                    responseProtocol = resp.protocol.toString(),
	                    responseCode = resp.code,
	                    responseMessage = resp.message,
	                    responseHeaders = headersToPairs(resp.headers),
	                    responseBody = raw,
	                )
	            if (!resp.isSuccessful) throw AiHttpException(exchange)
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
    @kotlinx.serialization.SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @kotlinx.serialization.SerialName("tool_calls")
    val toolCalls: List<ToolCallOut>? = null,
    @kotlinx.serialization.SerialName("function_call")
    val functionCall: FunctionCallOut? = null,
)

@Serializable
private data class ContentPartOut(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
private data class ToolCallOut(
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallOut? = null,
)

@Serializable
private data class FunctionCallOut(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class ListModelsResponse(
    val data: List<ModelItem> = emptyList(),
)

@Serializable
private data class ModelItem(
    val id: String,
)
