package com.mobileai.notes.ai

class AiJsonParseException(
    message: String,
    val debugHttp: String,
) : IllegalStateException(message)

