package com.mobileai.notes.ai

import com.mobileai.notes.settings.AiAgentConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AiAgents(
    private val client: OpenAiCompatClient = OpenAiCompatClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun generatePaper(
        providerBaseUrl: String,
        providerApiKey: String,
        agent: AiAgentConfig,
        userPrompt: String,
        count: Int,
    ): List<GeneratedQuestion> {
        val prompt =
            buildString {
                appendLine(userPrompt.trim())
                appendLine()
                appendLine("题目数量：$count")
                appendLine("请只返回 JSON，不要输出其他内容。")
            }

        val raw =
            client.chat(
                baseUrl = providerBaseUrl,
                apiKey = providerApiKey,
                model = agent.model,
                systemPrompt = agent.systemPrompt,
                userText = prompt,
                temperature = agent.temperature,
                maxTokens = agent.maxTokens,
            )
        return parseQuestions(raw)
    }

    suspend fun explainPage(
        providerBaseUrl: String,
        providerApiKey: String,
        agent: AiAgentConfig,
        questionText: String?,
        pagePngBytes: ByteArray?,
        extraInstruction: String?,
    ): String {
        val userText =
            buildString {
                if (!questionText.isNullOrBlank()) {
                    appendLine("题目：")
                    appendLine(questionText.trim())
                    appendLine()
                }
                if (!extraInstruction.isNullOrBlank()) {
                    appendLine("额外要求：")
                    appendLine(extraInstruction.trim())
                    appendLine()
                }
                appendLine("请结合图片中的学生作答进行讲解与纠错。")
            }

        return client.chat(
            baseUrl = providerBaseUrl,
            apiKey = providerApiKey,
            model = agent.model,
            systemPrompt = agent.systemPrompt,
            userText = userText,
            userImagePngBytes = pagePngBytes,
            temperature = agent.temperature,
            maxTokens = agent.maxTokens,
        )
    }

    private fun parseQuestions(raw: String): List<GeneratedQuestion> {
        val jsonText = extractJsonObject(raw) ?: return emptyList()
        runCatching { json.decodeFromString(QuestionsEnvelope.serializer(), jsonText).questions }
            .onSuccess { return it }
        // Fallback: array format
        val arrayText = extractJsonArray(raw) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(GeneratedQuestion.serializer()), arrayText) }
            .getOrElse { emptyList() }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1).trim()
    }

    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1).trim()
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
