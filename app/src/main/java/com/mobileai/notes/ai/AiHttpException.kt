package com.mobileai.notes.ai

class AiHttpException(
    val exchange: AiHttpExchange,
) : IllegalStateException("HTTP ${exchange.responseCode}: ${exchange.responseBody}")

