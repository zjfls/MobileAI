package com.mobileai.notes.host

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 一个最小的 Host API 客户端：用于“拉题/生成试卷/上传作答/请求 AI 解答”。
 *
 * 说明：Host 端协议可按你自己的服务调整，本客户端做了少量兼容：
 * - 拉题：GET {baseUrl}/questions
 * - AI 出题：POST {baseUrl}/papers/generate
 * - 上传作答：POST {baseUrl}/pages/upload
 * - AI 解答：POST {baseUrl}/ai/solve
 */
class HostClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetchQuestions(baseUrl: String): List<HostQuestion> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/questions"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            decodeQuestions(body)
        }
    }

    suspend fun generatePaper(baseUrl: String, prompt: String, count: Int): List<HostQuestion> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/papers/generate"
            val payload = HostGeneratePaperRequest(prompt = prompt, count = count)
            val body = json.encodeToString(HostGeneratePaperRequest.serializer(), payload)
            val req =
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val text = resp.body?.string().orEmpty()
                decodeQuestions(text)
            }
        }

    suspend fun uploadPagePng(baseUrl: String, paperId: String?, pageIndex: Int, pngBytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/pages/upload"
            val payload =
                HostUploadPageRequest(
                    paperId = paperId,
                    pageIndex = pageIndex,
                    pngBase64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP),
                )
            val body = json.encodeToString(HostUploadPageRequest.serializer(), payload)
            val req =
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }
        }

    suspend fun solvePage(baseUrl: String, pngBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/ai/solve"
        val payload =
            HostSolveRequest(
                pngBase64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP),
            )
        val body = json.encodeToString(HostSolveRequest.serializer(), payload)
        val req =
            Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            runCatching { json.decodeFromString(HostSolveResponse.serializer(), text).answer }
                .getOrElse { text }
        }
    }

    private fun decodeQuestions(jsonText: String): List<HostQuestion> {
        runCatching { json.decodeFromString(HostQuestionsResponse.serializer(), jsonText).questions }
            .onSuccess { return it }
        runCatching { json.decodeFromString(ListSerializer(HostQuestion.serializer()), jsonText) }
            .onSuccess { return it }
        return emptyList()
    }
}

@Serializable
data class HostQuestion(
    val id: String,
    val text: String? = null,
    val imageUrl: String? = null,
    val imageBase64: String? = null,
)

@Serializable
data class HostQuestionsResponse(
    val questions: List<HostQuestion> = emptyList(),
)

@Serializable
data class HostGeneratePaperRequest(
    val prompt: String,
    val count: Int,
)

@Serializable
data class HostUploadPageRequest(
    val paperId: String? = null,
    val pageIndex: Int,
    @SerialName("png_base64")
    val pngBase64: String,
)

@Serializable
data class HostSolveRequest(
    @SerialName("png_base64")
    val pngBase64: String,
)

@Serializable
data class HostSolveResponse(
    val answer: String,
)
