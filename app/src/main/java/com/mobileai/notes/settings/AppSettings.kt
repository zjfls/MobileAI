package com.mobileai.notes.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val type: AiProviderType = AiProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val models: List<String> = emptyList(),
    // Optional per-model sampling overrides. If set, they take precedence over Agent defaults.
    val modelParams: Map<String, AiModelParams> = emptyMap(),
)

@Serializable
data class AiModelParams(
    val temperature: Float? = null,
    // When null, treat as legacy behavior (enabled if value is present).
    val temperatureEnabled: Boolean? = null,
    @SerialName("top_p")
    val topP: Float? = null,
    // When null, treat as legacy behavior (enabled if value is present).
    @SerialName("top_p_enabled")
    val topPEnabled: Boolean? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    // When null, treat as legacy behavior (enabled if value is present).
    @SerialName("max_tokens_enabled")
    val maxTokensEnabled: Boolean? = null,
)

@Serializable
enum class AiProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GOOGLE,
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
data class AiAgentPreset(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val config: AiAgentConfig,
)

@Serializable
data class PaperGeneratorConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val agentId: String,
    val count: Int = 5,
    val promptPreset: String = "生成一套数学试卷，难度中等，适合高中。",
)

@Serializable
data class ExplainerConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val agentId: String,
    val style: String = "步骤清晰，先给思路再给步骤，最后给结论。",
)

@Serializable
data class AiSettings(
    val providers: List<AiProvider> = listOf(
        AiProvider(id = "default", name = "OpenAI Compatible"),
    ),
    val agents: List<AiAgentPreset> = listOf(
        AiAgentPreset(
            id = "agent_gen_default",
            name = "出题 Agent",
            config = AiAgentConfig(
                providerId = "default",
                model = "gpt-4o-mini",
                systemPrompt = """
你是专业出题老师。请根据用户要求生成试题。
范围：数学分析（极限、微分、积分）。
题型：计算题、证明题。
要求：
- 返回严格 JSON（不要 Markdown），格式为：{"questions":[{"title":"题目1","text":"..."}]}
- 题目要有可作答空间，不要直接给答案。
""".trim(),
                temperature = 0.7f,
                maxTokens = 2048,
            ),
        ),
        AiAgentPreset(
            id = "agent_exp_default",
            name = "讲解 Agent",
            config = AiAgentConfig(
                providerId = "default",
                model = "gpt-4o-mini",
                systemPrompt = """
你是耐心的讲解老师。请对题目与学生作答进行讲解和纠错。
""".trim(),
                temperature = 0.3f,
                maxTokens = 2048,
            ),
        ),
    ),
    val paperGenerators: List<PaperGeneratorConfig> = listOf(
        PaperGeneratorConfig(
            id = "gen_default",
            name = "默认生题器",
            agentId = "agent_gen_default",
            count = 5,
            promptPreset = "生成一套数学试卷，难度中等，适合高中。",
        ),
    ),
    val explainers: List<ExplainerConfig> = listOf(
        ExplainerConfig(
            id = "exp_default",
            name = "默认讲解器",
            agentId = "agent_exp_default",
            style = "步骤清晰，先给思路再给步骤，最后给结论。",
        ),
    ),
    val defaultPaperGeneratorId: String = "gen_default",
    val defaultExplainerId: String = "exp_default",
)

class AppSettings(private val context: Context) {
    private val keyAiSettingsJson = stringPreferencesKey("ai_settings_json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val aiSettings: Flow<AiSettings> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[keyAiSettingsJson]
            if (raw.isNullOrBlank()) return@map AiSettings()
            val hasAgents =
                runCatching { json.parseToJsonElement(raw).jsonObject.containsKey("agents") }
                    .getOrNull() == true
            if (hasAgents) {
                return@map runCatching { json.decodeFromString<AiSettings>(raw) }.getOrElse { AiSettings() }
            }
            // Best-effort migrations from older schemas.
            runCatching { migrateFromV1(json.decodeFromString<AiSettingsV1>(raw)) }
                .getOrElse {
                    runCatching { migrateFromLegacy(json.decodeFromString<LegacyAiSettings>(raw)) }
                        .getOrElse { AiSettings() }
                }
        }

    suspend fun setAiSettings(settings: AiSettings) {
        withContext(NonCancellable) {
            context.dataStore.edit { prefs ->
                prefs[keyAiSettingsJson] = json.encodeToString(settings)
            }
        }
    }

    companion object {
        fun create(context: Context): AppSettings = AppSettings(context.applicationContext)
    }

    @Serializable
    private data class LegacyAiSettings(
        val providers: List<AiProvider> = listOf(AiProvider(id = "default", name = "OpenAI Compatible")),
        val defaultProviderId: String = "default",
        val generator: AiAgentConfig = AiAgentConfig(
            providerId = "default",
            model = "gpt-4o-mini",
            systemPrompt = "",
        ),
        val explainer: AiAgentConfig = AiAgentConfig(
            providerId = "default",
            model = "gpt-4o-mini",
            systemPrompt = "",
        ),
        val paperGenerator: LegacyPaperGeneratorSettings = LegacyPaperGeneratorSettings(),
        val explainerSettings: LegacyExplainerSettings = LegacyExplainerSettings(),
    )

    @Serializable
    private data class LegacyPaperGeneratorSettings(
        val count: Int = 5,
        val promptPreset: String = "生成一套数学试卷，难度中等，适合高中。",
    )

    @Serializable
    private data class LegacyExplainerSettings(
        val style: String = "步骤清晰，先给思路再给步骤，最后给结论。",
    )

    @Serializable
    private data class AiSettingsV1(
        val providers: List<AiProvider> = listOf(AiProvider(id = "default", name = "OpenAI Compatible")),
        val paperGenerators: List<PaperGeneratorConfigV1> = emptyList(),
        val explainers: List<ExplainerConfigV1> = emptyList(),
        val defaultPaperGeneratorId: String = "gen_default",
        val defaultExplainerId: String = "exp_default",
    )

    @Serializable
    private data class PaperGeneratorConfigV1(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val agent: AiAgentConfig,
        val count: Int = 5,
        val promptPreset: String = "生成一套数学试卷，难度中等，适合高中。",
    )

    @Serializable
    private data class ExplainerConfigV1(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val agent: AiAgentConfig,
        val style: String = "步骤清晰，先给思路再给步骤，最后给结论。",
    )

    private fun migrateFromLegacy(old: LegacyAiSettings): AiSettings {
        val providers = old.providers.ifEmpty { listOf(AiProvider(id = "default", name = "OpenAI Compatible")) }
        val providerIds = providers.map { it.id }.toSet()
        val genAgentCfg =
            if (old.generator.providerId in providerIds) old.generator else old.generator.copy(providerId = providers.first().id)
        val expAgentCfg =
            if (old.explainer.providerId in providerIds) old.explainer else old.explainer.copy(providerId = providers.first().id)
        val agents =
            listOf(
                AiAgentPreset(id = "agent_gen_default", name = "出题 Agent", config = genAgentCfg),
                AiAgentPreset(id = "agent_exp_default", name = "讲解 Agent", config = expAgentCfg),
            )
        return AiSettings(
            providers = providers,
            agents = agents,
            paperGenerators = listOf(
                PaperGeneratorConfig(
                    id = "gen_default",
                    name = "默认生题器",
                    agentId = "agent_gen_default",
                    count = old.paperGenerator.count,
                    promptPreset = old.paperGenerator.promptPreset,
                ),
            ),
            explainers = listOf(
                ExplainerConfig(
                    id = "exp_default",
                    name = "默认讲解器",
                    agentId = "agent_exp_default",
                    style = old.explainerSettings.style,
                ),
            ),
            defaultPaperGeneratorId = "gen_default",
            defaultExplainerId = "exp_default",
        )
    }

    private fun migrateFromV1(old: AiSettingsV1): AiSettings {
        val providers = old.providers.ifEmpty { listOf(AiProvider(id = "default", name = "OpenAI Compatible")) }
        val providerIds = providers.map { it.id }.toSet()

        data class AgentKey(
            val providerId: String,
            val model: String,
            val systemPrompt: String,
            val temperature: Float,
            val maxTokens: Int,
        )

        val agents = mutableListOf<AiAgentPreset>()
        val agentIdsByKey = linkedMapOf<AgentKey, String>()

        fun ensureAgentId(cfg: AiAgentConfig, nameHint: String): String {
            val fixedCfg = if (cfg.providerId in providerIds) cfg else cfg.copy(providerId = providers.first().id)
            val key = AgentKey(fixedCfg.providerId, fixedCfg.model, fixedCfg.systemPrompt, fixedCfg.temperature, fixedCfg.maxTokens)
            return agentIdsByKey.getOrPut(key) {
                val id = "a_" + UUID.randomUUID().toString().take(8)
                agents += AiAgentPreset(id = id, name = nameHint, config = fixedCfg)
                id
            }
        }

        val paperGenerators =
            old.paperGenerators.map { g ->
                PaperGeneratorConfig(
                    id = g.id,
                    name = g.name,
                    enabled = g.enabled,
                    agentId = ensureAgentId(g.agent, "${g.name} Agent"),
                    count = g.count,
                    promptPreset = g.promptPreset,
                )
            }
        val explainers =
            old.explainers.map { e ->
                ExplainerConfig(
                    id = e.id,
                    name = e.name,
                    enabled = e.enabled,
                    agentId = ensureAgentId(e.agent, "${e.name} Agent"),
                    style = e.style,
                )
            }

        val defaultPaperGeneratorId =
            old.defaultPaperGeneratorId.takeIf { id -> paperGenerators.any { it.id == id } }
                ?: paperGenerators.firstOrNull()?.id.orEmpty()
        val defaultExplainerId =
            old.defaultExplainerId.takeIf { id -> explainers.any { it.id == id } }
                ?: explainers.firstOrNull()?.id.orEmpty()

        val fallbackProviderId = providers.first().id
        val fallbackAgents =
            listOf(
                AiAgentPreset(
                    id = "agent_gen_default",
                    name = "出题 Agent",
                    config = AiAgentConfig(providerId = fallbackProviderId, model = "gpt-4o-mini", systemPrompt = ""),
                ),
                AiAgentPreset(
                    id = "agent_exp_default",
                    name = "讲解 Agent",
                    config = AiAgentConfig(providerId = fallbackProviderId, model = "gpt-4o-mini", systemPrompt = ""),
                ),
            )
        val agentsFinal = agents.ifEmpty { fallbackAgents }
        val paperGeneratorsFinal =
            paperGenerators.ifEmpty {
                listOf(PaperGeneratorConfig(id = "gen_default", name = "默认生题器", agentId = agentsFinal.first().id))
            }
        val explainersFinal =
            explainers.ifEmpty {
                listOf(ExplainerConfig(id = "exp_default", name = "默认讲解器", agentId = agentsFinal.getOrNull(1)?.id ?: agentsFinal.first().id))
            }

        return AiSettings(
            providers = providers,
            agents = agentsFinal,
            paperGenerators = paperGeneratorsFinal,
            explainers = explainersFinal,
            defaultPaperGeneratorId = defaultPaperGeneratorId.ifBlank { paperGeneratorsFinal.first().id },
            defaultExplainerId = defaultExplainerId.ifBlank { explainersFinal.first().id },
        )
    }
}
