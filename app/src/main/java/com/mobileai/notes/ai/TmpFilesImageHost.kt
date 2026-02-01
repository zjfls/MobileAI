package com.mobileai.notes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TmpFilesImageHost(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun uploadPngAndGetDirectUrl(pngBytes: ByteArray, filename: String = "image.png"): String =
        withContext(Dispatchers.IO) {
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        filename,
                        pngBytes.toRequestBody("image/png".toMediaType()),
                    )
                    .build()

            val req =
                Request.Builder()
                    .url("https://tmpfiles.org/api/v1/upload")
                    .post(body)
                    .build()

            val call = http.newCall(req)
            call.await().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("tmpfiles upload failed: HTTP ${resp.code}: $raw")
                val parsed = json.decodeFromString(TmpFilesUploadResponse.serializer(), raw)
                val url = parsed.data?.url?.trim().orEmpty()
                if (url.isBlank()) error("tmpfiles upload failed: missing url")
                // tmpfiles returns a page URL like http://tmpfiles.org/<id>/<name>; direct file is /dl/<id>/<name>
                toDirectDownloadUrl(url)
            }
        }

    private fun toDirectDownloadUrl(url: String): String {
        val cleaned = url.trim()
        val m = Regex("^https?://tmpfiles\\.org/(\\d+)/(.*)$").find(cleaned)
        if (m != null) {
            val id = m.groupValues[1]
            val name = m.groupValues[2]
            return "https://tmpfiles.org/dl/$id/$name"
        }
        return cleaned.replace("http://", "https://")
    }

    @Serializable
    private data class TmpFilesUploadResponse(
        val status: String? = null,
        val data: Data? = null,
    ) {
        @Serializable
        data class Data(
            val url: String? = null,
            @SerialName("filename")
            val filename: String? = null,
        )
    }
}
