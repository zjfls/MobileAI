package com.mobileai.notes.ai

/**
 * Debug-friendly result for an AI call.
 *
 * - [text] is the extracted text the app uses (assistant message / tool args, etc.)
 * - [exchange] is the sanitized HTTP exchange for debugging (includes raw response body).
 */
data class AiHttpResult(
    val text: String,
    val exchange: AiHttpExchange,
) {
    val rawResponse: String
        get() = exchange.responseBody

    val debugHttp: String
        get() = exchange.toDebugString()
}
