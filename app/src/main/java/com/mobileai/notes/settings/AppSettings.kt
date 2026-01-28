package com.mobileai.notes.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class HostSettings(
    val baseUrl: String,
) {
    companion object {
        val Default = HostSettings(baseUrl = "https://api.mock-edu.com")
    }
}

@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val type: AiProviderType = AiProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
)

@Serializable
enum class AiProviderType {
    OPENAI_COMPATIBLE,
}

@Serializable
data class AiAgentConfig(
    val providerId: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Float = 0.4f,
    val maxTokens: Int = 2048,
)

@Serializable
data class PaperGeneratorSettings(
    val count: Int = 5,
    val promptPreset: String = "生成一套数学试卷，难度中等，适合高中。",
)

@Serializable
data class ExplainerSettings(
    val style: String = "步骤清晰，先给思路再给步骤，最后给结论。",
)

@Serializable
data class AiSettings(
    val providers: List<AiProvider> = listOf(
        AiProvider(id = "default", name = "OpenAI Compatible"),
    ),
    val defaultProviderId: String = "default",
    val generator: AiAgentConfig = AiAgentConfig(
        providerId = "default",
        model = "gpt-4o-mini",
        systemPrompt = """
你是专业出题老师。请根据用户要求生成试题。
要求：
- 返回严格 JSON（不要 Markdown），格式为：{"questions":[{"title":"题目1","text":"..."}]}
- 题目要有可作答空间，不要直接给答案。
""".trim(),
        temperature = 0.7f,
        maxTokens = 2048,
    ),
    val explainer: AiAgentConfig = AiAgentConfig(
        providerId = "default",
        model = "gpt-4o-mini",
        systemPrompt = """
你是耐心的讲解老师。请对题目与学生作答进行讲解和纠错。
输出 Markdown：先给评分/结论，再给步骤解析，最后给易错点总结。
""".trim(),
        temperature = 0.3f,
        maxTokens = 2048,
    ),
    val paperGenerator: PaperGeneratorSettings = PaperGeneratorSettings(),
    val explainerSettings: ExplainerSettings = ExplainerSettings(),
)

class AppSettings(private val context: Context) {
    private val keyHostBaseUrl = stringPreferencesKey("host_base_url")
    private val keyAiSettingsJson = stringPreferencesKey("ai_settings_json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val hostSettings: Flow<HostSettings> =
        context.dataStore.data.map { prefs ->
            HostSettings(
                baseUrl = prefs[keyHostBaseUrl] ?: HostSettings.Default.baseUrl,
            )
        }

    suspend fun setHostBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[keyHostBaseUrl] = url.trim()
        }
    }

    val aiSettings: Flow<AiSettings> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[keyAiSettingsJson]
            if (raw.isNullOrBlank()) return@map AiSettings()
            runCatching { json.decodeFromString<AiSettings>(raw) }.getOrElse { AiSettings() }
        }

    suspend fun setAiSettings(settings: AiSettings) {
        context.dataStore.edit { prefs ->
            prefs[keyAiSettingsJson] = json.encodeToString(settings)
        }
    }

    companion object {
        fun create(context: Context): AppSettings = AppSettings(context.applicationContext)
    }
}
