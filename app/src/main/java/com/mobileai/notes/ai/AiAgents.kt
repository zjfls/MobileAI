package com.mobileai.notes.ai

import com.mobileai.notes.settings.AiProvider
import com.mobileai.notes.settings.AiProviderType
import com.mobileai.notes.settings.AiAgentConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AiAgents(
    private val client: OpenAiCompatClient = OpenAiCompatClient(),
    private val anthropic: AnthropicClient = AnthropicClient(),
    private val google: GoogleGeminiClient = GoogleGeminiClient(),
    private val tmpFiles: TmpFilesImageHost = TmpFilesImageHost(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun sanitizeForStrictJson(text: String): String {
        val cleaned =
            text.lines()
                .filterNot { line ->
                    val lower = line.lowercase()
                    lower.contains("markdown") ||
                        line.contains("输出 Markdown") ||
                        line.contains("不要 Markdown") ||
                        line.contains("不要输出 Markdown")
                }
                .joinToString("\n")
                .trim()
        return cleaned
    }

    private fun composeSystemPrompt(agentSystemPrompt: String, toolSystemPrompt: String?): String {
        val a = agentSystemPrompt.trim()
        val t = toolSystemPrompt?.trim().orEmpty()
        return when {
            a.isBlank() && t.isBlank() -> ""
            a.isBlank() -> t
            t.isBlank() -> a
            else -> a + "\n\n" + t
        }
    }

    private data class ResolvedImage(
        val pngBytes: ByteArray? = null,
        val imageUrl: String? = null,
    )

    private suspend fun resolveImageForOpenAiCompatible(provider: AiProvider, pngBytes: ByteArray?): ResolvedImage {
        if (pngBytes == null) return ResolvedImage()
        val baseUrl = provider.baseUrl.lowercase()
        // Default to "data:image/png;base64,..." (works well for many OpenAI-compatible providers).
        // Some gateways may hang on data: URLs; for those we switch to http(s) image URL mode.
        val requiresHttpImageUrl = baseUrl.contains("chataiapi.com")
        if (!requiresHttpImageUrl) return ResolvedImage(pngBytes = pngBytes)

        val url = runCatching { tmpFiles.uploadPngAndGetDirectUrl(pngBytes, filename = "worksheet.png") }.getOrNull()
        return if (!url.isNullOrBlank()) ResolvedImage(pngBytes = pngBytes, imageUrl = url) else ResolvedImage(pngBytes = pngBytes)
    }

    data class GeneratePaperResult(
        val questions: List<GeneratedQuestion>,
        val messageText: String,
        val rawResponse: String,
    )

    data class ExplainResult(
        val answerText: String,
        val rawResponse: String,
    )

    data class ExplainToPagesResult(
        val pages: List<GeneratedQuestion>,
        val messageText: String,
        val rawResponse: String,
    )

    data class ExplainRawResult(
        // The extracted assistant/tool output string (should be a strict JSON object when requireJsonObject=true).
        val extractedText: String,
        // Raw HTTP response body (for debugging).
        val rawResponse: String,
    )

    fun parsePagesStrictJson(extractedText: String, expectedCount: Int): List<GeneratedQuestion> {
        return parseGeneratedItemsStrictJsonObject(extractedText, envelopeKey = "pages", expectedCount = expectedCount)
    }

    suspend fun debugHelloWithRaw(
        provider: AiProvider,
        agent: AiAgentConfig,
        systemPromptExtra: String? = null,
    ): AiHttpResult {
        return chatWithRawResponse(
            provider = provider,
            agent = agent,
            systemPromptExtra = systemPromptExtra?.trim().takeIf { !it.isNullOrBlank() },
            userText = "hello",
            userImagePngBytes = null,
            userImageUrl = null,
        )
    }

    suspend fun debugDescribeImageWithRaw(
        provider: AiProvider,
        agent: AiAgentConfig,
        pagePngBytes: ByteArray?,
        systemPromptExtra: String? = null,
        onRequestPrepared: ((requestBodyRaw: String?, requestBodyPreview: String) -> Unit)? = null,
    ): AiHttpResult {
        val prompt =
            buildString {
                appendLine("请解读这张图片的内容，用中文简要描述即可：")
                appendLine("- 图片里是否有文字？如果有，请尽量识别出来（允许不完整）。")
                appendLine("- 图片里是否有手写/线条/图形？请描述其位置与大致形状。")
                appendLine("- 如果图片几乎为空白，请直接说明“几乎空白”。")
                appendLine()
                appendLine("输出要求：只输出纯文本，不要输出 JSON，不要代码块。")
            }
        val resolvedImage =
            when (provider.type) {
                AiProviderType.OPENAI_COMPATIBLE -> resolveImageForOpenAiCompatible(provider, pagePngBytes)
                else -> ResolvedImage(pngBytes = pagePngBytes)
            }
        return chatWithRawResponse(
            provider = provider,
            agent = agent,
            systemPromptExtra = systemPromptExtra?.trim().takeIf { !it.isNullOrBlank() },
            userText = prompt,
            userImagePngBytes = resolvedImage.pngBytes,
            userImageUrl = resolvedImage.imageUrl,
            requireJsonObject = false,
            onRequestPrepared = onRequestPrepared,
        )
    }

    suspend fun generatePaperWithRaw(
        provider: AiProvider,
        agent: AiAgentConfig,
        userPrompt: String,
        count: Int,
    ): GeneratePaperResult {
        val toolSystemPrompt = userPrompt.trim().takeIf { it.isNotBlank() }
        val prompt =
            buildString {
                appendLine("题目数量：$count")
                appendLine("你必须只返回严格 JSON，不要输出任何额外文字（不要 Markdown / 不要代码块）。")
                appendLine("""JSON 格式必须为：{"questions":[{"title":"题目1","text":"..."}]}""")
                appendLine("其中 questions 数组长度必须等于 $count，且每题的 title/text 都不能为空。")
            }

        val first =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = prompt,
                userImagePngBytes = null,
                userImageUrl = null,
                requireJsonObject = true,
                jsonEnvelopeKey = "questions",
                expectedQuestionsCountForSchema = count,
            )
        runCatching { parseGeneratedItemsStrictJsonObject(first.text, envelopeKey = "questions", expectedCount = count) }
            .onSuccess { parsed ->
                return GeneratePaperResult(
                    questions = parsed,
                    messageText = first.text,
                    rawResponse = first.rawResponse,
                )
            }

        val repairPrompt =
            buildString {
                appendLine("你刚才的输出不是合法 JSON 或不符合要求。请修正并重新输出。")
                appendLine("你必须只返回严格 JSON，不要输出任何额外文字（不要 Markdown / 不要代码块）。")
                appendLine("""JSON 格式必须为：{"questions":[{"title":"题目1","text":"..."}]}""")
                appendLine("其中 questions 数组长度必须等于 $count，且每题的 title/text 都不能为空。")
                appendLine()
                appendLine("<<<BEGIN_PREVIOUS_OUTPUT")
                appendLine(first.text.trim())
                appendLine("END_PREVIOUS_OUTPUT>>>")
            }

        val second =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = repairPrompt,
                userImagePngBytes = null,
                userImageUrl = null,
                requireJsonObject = true,
                jsonEnvelopeKey = "questions",
                expectedQuestionsCountForSchema = count,
            )
        val questions =
            runCatching { parseGeneratedItemsStrictJsonObject(second.text, envelopeKey = "questions", expectedCount = count) }
                .getOrElse {
                    throw AiJsonParseException(
                        message = it.message ?: "LLM 未按要求返回严格 JSON",
                        debugHttp = first.debugHttp + "\n\n-----\n\n" + second.debugHttp,
                    )
                }
        return GeneratePaperResult(
            questions = questions,
            messageText = second.text,
            rawResponse = first.rawResponse + "\n\n-----\n\n" + second.rawResponse,
        )
    }

    suspend fun generatePaper(
        provider: AiProvider,
        agent: AiAgentConfig,
        userPrompt: String,
        count: Int,
    ): List<GeneratedQuestion> {
        return generatePaperWithRaw(
            provider = provider,
            agent = agent,
            userPrompt = userPrompt,
            count = count,
        ).questions
    }

    suspend fun explainPage(
        provider: AiProvider,
        agent: AiAgentConfig,
        questionText: String?,
        pagePngBytes: ByteArray?,
        extraInstruction: String?,
    ): String {
        return explainPageWithRaw(
            provider = provider,
            agent = agent,
            questionText = questionText,
            pagePngBytes = pagePngBytes,
            extraInstruction = extraInstruction,
        ).answerText
    }

    suspend fun explainPageWithRaw(
        provider: AiProvider,
        agent: AiAgentConfig,
        questionText: String?,
        pagePngBytes: ByteArray?,
        extraInstruction: String?,
    ): ExplainResult {
        val toolSystemPrompt = extraInstruction?.trim()?.takeIf { it.isNotBlank() }
        val resolvedImage =
            when (provider.type) {
                com.mobileai.notes.settings.AiProviderType.OPENAI_COMPATIBLE ->
                    resolveImageForOpenAiCompatible(provider, pagePngBytes)
                else -> ResolvedImage(pngBytes = pagePngBytes)
            }
        val userText =
            buildString {
                if (!questionText.isNullOrBlank()) {
                    appendLine("题目：")
                    appendLine(questionText.trim())
                    appendLine()
                }
                appendLine("若图片中存在清晰可辨的学生作答：请基于作答讲解与纠错。")
                appendLine("若图片中没有清晰作答：请不要编造学生步骤，直接给出标准解答与讲解即可。")
            }

        val res =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = userText,
                userImagePngBytes = resolvedImage.pngBytes,
                userImageUrl = resolvedImage.imageUrl,
            )
        return ExplainResult(answerText = res.text, rawResponse = res.debugHttp)
    }

    suspend fun explainToAnswerPagesWithRaw(
        provider: AiProvider,
        agent: AiAgentConfig,
        questionText: String?,
        pagePngBytes: ByteArray?,
        extraInstruction: String?,
        count: Int = 1,
        onRequestPrepared: ((requestBodyRaw: String?, requestBodyPreview: String) -> Unit)? = null,
    ): ExplainToPagesResult {
        val toolSystemPrompt = extraInstruction?.trim()?.takeIf { it.isNotBlank() }
        val resolvedImage =
            when (provider.type) {
                com.mobileai.notes.settings.AiProviderType.OPENAI_COMPATIBLE ->
                    resolveImageForOpenAiCompatible(provider, pagePngBytes)
                else -> ResolvedImage(pngBytes = pagePngBytes)
            }
        val prompt =
            buildString {
                appendLine("你将收到一张图片：上方为题目，下方为学生作答/草稿。")
                appendLine()
                appendLine("任务：")
                appendLine("- 若图片中存在清晰可辨的学生作答：请基于作答逐步讲解与纠错。")
                appendLine("- 若图片中没有清晰作答：请不要编造学生步骤，直接给出标准解答与讲解即可。")
                appendLine()
                appendLine("输出要求：你必须只返回严格 JSON 对象，不要输出任何额外文字，不要输出代码块。")
                appendLine("""JSON 结构：{"pages":[{"title":"...","text":"..."}]}""")
                appendLine("pages 数组长度必须等于 $count。每页只包含 title 与 text 字段，且都不能为空。")
            }

        // NOTE: questionText is intentionally ignored for multimodal explain: question should be present in the image.
        val first =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = prompt,
                userImagePngBytes = resolvedImage.pngBytes,
                userImageUrl = resolvedImage.imageUrl,
                requireJsonObject = true,
                jsonEnvelopeKey = "pages",
                expectedQuestionsCountForSchema = count,
                onRequestPrepared = onRequestPrepared,
            )
        runCatching { parseGeneratedItemsStrictJsonObject(first.text, envelopeKey = "pages", expectedCount = count) }
            .onSuccess { parsed ->
                return ExplainToPagesResult(
                    pages = parsed,
                    messageText = first.text,
                    rawResponse = first.rawResponse,
                )
            }

        val repairPrompt =
            buildString {
                appendLine("你刚才的输出不是合法 JSON 或不符合要求。请修正并重新输出。")
                appendLine("你必须只返回严格 JSON 对象，不要输出任何额外文字，不要输出代码块。")
                appendLine("""JSON 结构：{"pages":[{"title":"...","text":"..."}]}""")
                appendLine("pages 数组长度必须等于 $count。每页只包含 title 与 text 字段，且都不能为空。")
                appendLine()
                appendLine("<<<BEGIN_PREVIOUS_OUTPUT")
                appendLine(first.text.trim())
                appendLine("END_PREVIOUS_OUTPUT>>>")
            }

        val second =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = repairPrompt,
                userImagePngBytes = resolvedImage.pngBytes,
                userImageUrl = resolvedImage.imageUrl,
                requireJsonObject = true,
                jsonEnvelopeKey = "pages",
                expectedQuestionsCountForSchema = count,
            )
        val pages =
            runCatching { parseGeneratedItemsStrictJsonObject(second.text, envelopeKey = "pages", expectedCount = count) }
                .getOrElse {
                    throw AiJsonParseException(
                        message = it.message ?: "LLM 未按要求返回严格 JSON",
                        debugHttp = first.debugHttp + "\n\n-----\n\n" + second.debugHttp,
                    )
                }
        return ExplainToPagesResult(
            pages = pages,
            messageText = second.text,
            rawResponse = first.rawResponse + "\n\n-----\n\n" + second.rawResponse,
        )
    }

    suspend fun explainToAnswerPagesRawOnly(
        provider: AiProvider,
        agent: AiAgentConfig,
        pagePngBytes: ByteArray?,
        extraInstruction: String?,
        count: Int = 1,
        onRequestPrepared: ((requestBodyRaw: String?, requestBodyPreview: String) -> Unit)? = null,
    ): ExplainRawResult {
        val toolSystemPrompt = extraInstruction?.trim()?.takeIf { it.isNotBlank() }
        val resolvedImage =
            when (provider.type) {
                com.mobileai.notes.settings.AiProviderType.OPENAI_COMPATIBLE ->
                    resolveImageForOpenAiCompatible(provider, pagePngBytes)
                else -> ResolvedImage(pngBytes = pagePngBytes)
            }
        val prompt =
            buildString {
                appendLine("你将收到一张图片：上方为题目，下方为学生作答/草稿。")
                appendLine()
                appendLine("任务：")
                appendLine("- 若图片中存在清晰可辨的学生作答：请基于作答逐步讲解与纠错。")
                appendLine("- 若图片中没有清晰作答：请不要编造学生步骤，直接给出标准解答与讲解即可。")
                appendLine()
                appendLine("输出要求：你必须只返回严格 JSON 对象，不要输出任何额外文字，不要输出代码块。")
                appendLine("""JSON 结构：{"pages":[{"title":"...","text":"..."}]}""")
                appendLine("pages 数组长度必须等于 $count。每页只包含 title 与 text 字段，且都不能为空。")
            }

        val res =
            chatWithRawResponse(
                provider = provider,
                agent = agent,
                systemPromptExtra = toolSystemPrompt,
                userText = prompt,
                userImagePngBytes = resolvedImage.pngBytes,
                userImageUrl = resolvedImage.imageUrl,
                requireJsonObject = true,
                jsonEnvelopeKey = "pages",
                expectedQuestionsCountForSchema = count,
                onRequestPrepared = onRequestPrepared,
            )
        return ExplainRawResult(extractedText = res.text, rawResponse = res.rawResponse)
    }

    private suspend fun chat(
        provider: AiProvider,
        agent: AiAgentConfig,
        userText: String,
        userImagePngBytes: ByteArray?,
        requireJsonObject: Boolean = false,
        jsonEnvelopeKey: String = "questions",
        expectedQuestionsCountForSchema: Int? = null,
    ): String {
        return chatWithRawResponse(
            provider = provider,
            agent = agent,
            systemPromptExtra = null,
            userText = userText,
            userImagePngBytes = userImagePngBytes,
            userImageUrl = null,
            requireJsonObject = requireJsonObject,
            jsonEnvelopeKey = jsonEnvelopeKey,
            expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
        ).text
    }

    private suspend fun chatWithRawResponse(
        provider: AiProvider,
        agent: AiAgentConfig,
        systemPromptExtra: String?,
        userText: String,
        userImagePngBytes: ByteArray?,
        userImageUrl: String?,
        requireJsonObject: Boolean = false,
        jsonEnvelopeKey: String = "questions",
        expectedQuestionsCountForSchema: Int? = null,
        onRequestPrepared: ((requestBodyRaw: String?, requestBodyPreview: String) -> Unit)? = null,
    ): AiHttpResult {
        val baseUrl = provider.baseUrl
        val apiKey = provider.apiKey
        val modelKey = agent.model.trim()
        val modelKeyCandidates =
            linkedSetOf(modelKey).apply {
                add(modelKey.removePrefix("models/"))
                if (!modelKey.startsWith("models/")) add("models/$modelKey")
            }
        val modelParams = modelKeyCandidates.asSequence().mapNotNull { provider.modelParams[it] }.firstOrNull()

        val temperatureEnabled =
            when (modelParams?.temperatureEnabled) {
                true -> true
                false -> false
                null -> modelParams?.temperature != null // legacy: enable when a value exists
            }
        val temperatureToSend =
            if (temperatureEnabled) modelParams?.temperature else null

        val topPEnabled =
            when (modelParams?.topPEnabled) {
                true -> true
                false -> false
                null -> modelParams?.topP != null // legacy: enable when a value exists
            }
        val topPToSend =
            when {
                topPEnabled -> modelParams?.topP
                else -> null
            }

        val maxTokensEnabled =
            when (modelParams?.maxTokensEnabled) {
                true -> true
                false -> false
                null -> modelParams?.maxTokens != null // legacy: enable when a value exists
            }
        val maxTokensToSend =
            when {
                maxTokensEnabled -> modelParams?.maxTokens
                // If not explicitly enabled, don't send max_tokens at all (use server default).
                else -> null
            }
        val composedSystemPrompt = composeSystemPrompt(agent.systemPrompt, systemPromptExtra)
        val systemPromptToSend = if (requireJsonObject) sanitizeForStrictJson(composedSystemPrompt) else composedSystemPrompt

        return when (provider.type) {
            AiProviderType.OPENAI_COMPATIBLE ->
                runCatching {
                    client.chatWithRawResponse(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = agent.model,
                        systemPrompt = systemPromptToSend,
                        userText = userText,
                        userImagePngBytes = userImagePngBytes,
                        userImageUrl = userImageUrl,
                        requireJsonObject = requireJsonObject,
                        jsonEnvelopeKey = jsonEnvelopeKey,
                        expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
                        temperature = temperatureToSend,
                        topP = topPToSend,
                        maxTokens = maxTokensToSend,
                        onRequestPrepared = onRequestPrepared,
                    )
                }.recoverCatching { e ->
                    // Some gateways reject external image URLs (allow-list); retry with base64 data URL if we have bytes.
                    val http = (e as? AiHttpException)?.exchange
                    val lower = http?.responseBody?.lowercase().orEmpty()
                    val isUnsupportedImageUrl = lower.contains("unsupported image url") || lower.contains("invalid request: unsupported image url")
                    if (isUnsupportedImageUrl && userImageUrl != null && userImagePngBytes != null) {
                        return@recoverCatching client.chatWithRawResponse(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = agent.model,
                            systemPrompt = systemPromptToSend,
                            userText = userText,
                            userImagePngBytes = userImagePngBytes,
                            userImageUrl = null,
                            requireJsonObject = requireJsonObject,
                            jsonEnvelopeKey = jsonEnvelopeKey,
                            expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
                            temperature = temperatureToSend,
                            topP = topPToSend,
                            maxTokens = maxTokensToSend,
                            onRequestPrepared = onRequestPrepared,
                        )
                    }
                    throw e
                }.getOrThrow()
            AiProviderType.ANTHROPIC ->
                anthropic.messagesWithRawResponse(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = agent.model,
                    systemPrompt = systemPromptToSend,
                    userText = userText,
                    userImagePngBytes = userImagePngBytes,
                    requireJsonObject = requireJsonObject,
                    jsonEnvelopeKey = jsonEnvelopeKey,
                    expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
                    temperature = temperatureToSend,
                    topP = topPToSend,
                    // Anthropic requires max_tokens. If not explicitly enabled, fall back to Agent default.
                    maxTokens = maxTokensToSend ?: agent.maxTokens,
                )
            AiProviderType.GOOGLE ->
                google.generateContentWithRawResponse(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = agent.model,
                    systemPrompt = systemPromptToSend,
                    userText = userText,
                    userImagePngBytes = userImagePngBytes,
                    requireJsonObject = requireJsonObject,
                    jsonEnvelopeKey = jsonEnvelopeKey,
                    expectedQuestionsCountForSchema = expectedQuestionsCountForSchema,
                    temperature = temperatureToSend,
                    topP = topPToSend,
                    maxTokens = maxTokensToSend,
                )
        }
    }

    private fun parseGeneratedItemsStrictJsonObject(
        raw: String,
        envelopeKey: String,
        expectedCount: Int,
    ): List<GeneratedQuestion> {
        extractJsonObjects(raw)
            .asSequence()
            .map(::repairJsonCandidate)
            .forEach { jsonText ->
                runCatching {
                    val el = json.parseToJsonElement(jsonText)
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
                    val arr = obj[envelopeKey] ?: return@runCatching null
                    json.decodeFromJsonElement(ListSerializer(GeneratedQuestion.serializer()), arr)
                }.onSuccess { parsed ->
                    if (parsed != null && parsed.size == expectedCount && parsed.none { it.title.isNullOrBlank() || it.text.isBlank() }) {
                        return parsed
                    }
                }
            }

        // Fallback: array format
        extractJsonArrays(raw)
            .asSequence()
            .map(::repairJsonCandidate)
            .forEach { arrayText ->
                runCatching { json.decodeFromString(ListSerializer(GeneratedQuestion.serializer()), arrayText) }
                    .onSuccess { parsed ->
                        if (parsed.size == expectedCount && parsed.none { it.title.isNullOrBlank() || it.text.isBlank() }) {
                            return parsed
                        }
                    }
            }

        error(
            "LLM 未按要求返回严格 JSON：必须为 {\"$envelopeKey\":[{\"title\":\"...\",\"text\":\"...\"}]}，" +
                "且 $envelopeKey 数组长度 = $expectedCount，title/text 均不能为空。",
        )
    }

    private fun parseQuestions(
        raw: String,
        expectedCount: Int?,
        strictJson: Boolean,
    ): List<GeneratedQuestion> {
        fun isPlaceholder(parsed: List<GeneratedQuestion>): Boolean {
            if (parsed.isEmpty()) return true
            // Common prompt-echo placeholders like "..." should not be treated as real questions.
            return parsed.any { q ->
                val t = q.text.trim()
                t == "..." || t == "…" || t == "……"
            }
        }

        fun score(parsed: List<GeneratedQuestion>): Int {
            var s = 0
            val totalLen = parsed.sumOf { it.text.trim().length }
            s += totalLen
            if (expectedCount != null && expectedCount > 0) {
                s += if (parsed.size == expectedCount) 2000 else -2000 - kotlin.math.abs(parsed.size - expectedCount) * 10
            }
            if (isPlaceholder(parsed)) s -= 10000
            return s
        }

        val candidates = mutableListOf<List<GeneratedQuestion>>()

        extractJsonObjects(raw)
            .asSequence()
            .map { repairJsonCandidate(it) }
            .forEach { jsonText ->
                runCatching { json.decodeFromString(QuestionsEnvelope.serializer(), jsonText).questions }
                    .onSuccess { parsed ->
                        if (parsed.isNotEmpty() && parsed.all { it.text.isNotBlank() }) {
                            candidates.add(parsed)
                        }
                    }
            }

        // Fallback: array format
        extractJsonArrays(raw)
            .asSequence()
            .map { repairJsonCandidate(it) }
            .forEach { arrayText ->
                runCatching { json.decodeFromString(ListSerializer(GeneratedQuestion.serializer()), arrayText) }
                    .onSuccess { parsed ->
                        if (parsed.isNotEmpty() && parsed.all { it.text.isNotBlank() }) {
                            candidates.add(parsed)
                        }
                    }
            }

        val best =
            candidates
                .maxByOrNull(::score)
                ?.takeIf { parsed ->
                    // Avoid returning a schema example like {"text":"..."} even if it technically parses.
                    !isPlaceholder(parsed) && parsed.sumOf { it.text.trim().length } >= 10
                }
        if (best != null) return best

        if (strictJson) {
            error("LLM 未返回合法 JSON（或 questions 为空）")
        }
        // Final fallback: show raw text as a single question
        val cleaned = stripCodeFences(raw).trim()
        return if (cleaned.isBlank()) {
            listOf(GeneratedQuestion(title = "AI 返回为空", text = "（AI 返回为空，请检查模型/Key/网络/限流）"))
        } else {
            listOf(GeneratedQuestion(text = cleaned))
        }
    }

    private fun extractJsonObjects(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (trimmed in normalizeForJsonExtraction(text)) {
            var index = 0
            while (index < trimmed.length) {
                val start = trimmed.indexOf('{', index)
                if (start < 0) break
                val end = findBalancedEnd(trimmed, start, openChar = '{', closeChar = '}')
                if (end > start) {
                    out.add(trimmed.substring(start, end + 1).trim())
                    index = end + 1
                } else {
                    index = start + 1
                }
            }
        }
        return out.toList()
    }

    private fun extractJsonArrays(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (trimmed in normalizeForJsonExtraction(text)) {
            var index = 0
            while (index < trimmed.length) {
                val start = trimmed.indexOf('[', index)
                if (start < 0) break
                val end = findBalancedEnd(trimmed, start, openChar = '[', closeChar = ']')
                if (end > start) {
                    out.add(trimmed.substring(start, end + 1).trim())
                    index = end + 1
                } else {
                    index = start + 1
                }
            }
        }
        return out.toList()
    }

    private fun findBalancedEnd(
        text: String,
        start: Int,
        openChar: Char,
        closeChar: Char,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (ch) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }

    private fun stripCodeFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trimStart()
        }
        if (t.endsWith("```")) {
            t = t.removeSuffix("```").trimEnd()
        }
        return t
    }

    private fun normalizeJsonCandidate(text: String): String {
        // Normalize common “smart quotes” that break JSON.
        return text
            .replace('“', '"')
            .replace('”', '"')
            .replace('„', '"')
            .replace('‟', '"')
            .replace('＂', '"')
    }

    private fun normalizeForJsonExtraction(text: String): List<String> {
        val base = normalizeJsonCandidate(stripCodeFences(text))
        val out = LinkedHashSet<String>()
        out.add(base)

        // Some models embed JSON as an escaped literal like:
        //   {\"questions\":[{\"title\":\"...\",\"text\":\"...\"}]}
        // Brace matching fails because all string boundary quotes are escaped.
        val unescaped = unescapeStructuralQuotesIfNeeded(base)
        out.add(unescaped)

        return out.toList()
    }

    private fun unescapeStructuralQuotesIfNeeded(text: String): String {
        if (!text.contains("\\\"")) return text
        val looksLikeEscapedJson =
            text.contains("{\\\"") ||
                text.contains("[\\\"") ||
                text.contains("\\\"questions\\\"") ||
                text.contains("\\\"title\\\"") ||
                text.contains("\\\"text\\\"")
        if (!looksLikeEscapedJson) return text
        return text.replace("\\\"", "\"")
    }

    private fun repairJsonCandidate(text: String): String {
        val normalized = normalizeJsonCandidate(text)
        return escapeInvalidBackslashesInJson(normalized)
    }

    private fun escapeInvalidBackslashesInJson(text: String): String {
        val sb = StringBuilder(text.length + 32)
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!inString) {
                if (ch == '"') inString = true
                sb.append(ch)
                i++
                continue
            }

            if (escaped) {
                sb.append(ch)
                escaped = false
                i++
                continue
            }

            when (ch) {
                '\\' -> {
                    val next = if (i + 1 < text.length) text[i + 1] else null
                    if (next == null) {
                        sb.append("\\\\")
                        i++
                        continue
                    }
                    val isValidEscape =
                        when (next) {
                            '\\', '"', '/', 'b', 'f', 'n', 'r', 't' -> true
                            'u' -> {
                                val end = i + 6
                                if (end <= text.length) {
                                    text.substring(i + 2, end).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    if (isValidEscape) {
                        sb.append('\\')
                        escaped = true
                        i++
                        continue
                    }
                    // Invalid JSON escape (common in LaTeX like \lim, \frac): escape it.
                    sb.append("\\\\")
                    i++
                }
                '"' -> {
                    inString = false
                    sb.append(ch)
                    i++
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString()
    }
}

@Serializable
data class QuestionsEnvelope(
    val questions: List<GeneratedQuestion> = emptyList(),
)

@Serializable
data class GeneratedQuestion(
    val title: String? = null,
    val text: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("image_base64")
    val imageBase64: String? = null,
)
