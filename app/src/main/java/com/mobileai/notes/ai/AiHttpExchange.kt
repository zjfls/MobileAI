package com.mobileai.notes.ai

/**
 * Sanitized request/response snapshot for debugging.
 *
 * Note: request bodies may contain large base64 strings (multimodal). Those should be sanitized before
 * being stored here.
 */
data class AiHttpExchange(
    val requestUrl: String,
    val requestBodyPreview: String,
    val requestBodyRaw: String? = null,
    val responseProtocol: String,
    val responseCode: Int,
    val responseMessage: String,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: String,
) {
    fun toDebugString(): String =
        buildString {
            appendLine("requestUrl=$requestUrl")
            appendLine("response=$responseProtocol $responseCode${responseMessage.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}")
            if (responseHeaders.isNotEmpty()) {
                appendLine("responseHeaders=")
                responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            }
            appendLine()
            appendLine("responseBody=")
            appendLine(responseBody)
            appendLine()
            if (requestBodyRaw != null) {
                appendLine("requestBodyRaw=")
                appendLine(requestBodyRaw)
                appendLine()
            }
            appendLine("requestBodyPreview=")
            appendLine(requestBodyPreview)
        }
}
