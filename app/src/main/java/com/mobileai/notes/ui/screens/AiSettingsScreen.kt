package com.mobileai.notes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mobileai.notes.ai.OpenAiCompatClient
import com.mobileai.notes.settings.AiAgentConfig
import com.mobileai.notes.settings.AiProvider
import com.mobileai.notes.settings.AiSettings
import com.mobileai.notes.settings.AppSettings
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { AppSettings.create(context) }
    val settings by store.aiSettings.collectAsState(initial = AiSettings())

    var tabIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("LLM") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("生题器") })
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("讲解器") })
            }
            Spacer(Modifier.height(12.dp))

            when (tabIndex) {
                0 -> ProviderEditor(settings = settings, onSave = { scope.launch { store.setAiSettings(it) } }, snackbar = snackbar)
                1 -> AgentEditor(
                    title = "生题器配置",
                    settings = settings,
                    agent = settings.generator,
                    extra = settings.paperGenerator.promptPreset,
                    onSave = { newAgent, newPreset ->
                        scope.launch {
                            store.setAiSettings(
                                settings.copy(
                                    generator = newAgent,
                                    paperGenerator = settings.paperGenerator.copy(promptPreset = newPreset),
                                ),
                            )
                            snackbar.showSnackbar("已保存")
                        }
                    },
                    snackbar = snackbar,
                )
                2 -> AgentEditor(
                    title = "讲解器配置",
                    settings = settings,
                    agent = settings.explainer,
                    extra = settings.explainerSettings.style,
                    onSave = { newAgent, newStyle ->
                        scope.launch {
                            store.setAiSettings(
                                settings.copy(
                                    explainer = newAgent,
                                    explainerSettings = settings.explainerSettings.copy(style = newStyle),
                                ),
                            )
                            snackbar.showSnackbar("已保存")
                        }
                    },
                    snackbar = snackbar,
                )
            }
        }
    }
}

@Composable
private fun ProviderEditor(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val provider = settings.providers.firstOrNull { it.id == settings.defaultProviderId } ?: settings.providers.first()
    var baseUrl by remember(provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.apiKey) { mutableStateOf(provider.apiKey) }
    var name by remember(provider.name) { mutableStateOf(provider.name) }
    var showKey by remember { mutableStateOf(false) }
    var modelsDialogOpen by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("默认 Provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("显示名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API 地址（Base URL）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "显示/隐藏",
                    )
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val updatedProvider =
                        provider.copy(
                            name = name.trim().ifEmpty { provider.name },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                        )
                    onSave(
                        settings.copy(
                            providers = settings.providers.map { if (it.id == provider.id) updatedProvider else it },
                            defaultProviderId = updatedProvider.id,
                        ),
                    )
                    scope.launch { snackbar.showSnackbar("已保存") }
                },
            ) { Text("保存") }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val list = OpenAiCompatClient().listModels(baseUrl.trim(), apiKey.trim())
                            models = list
                            modelFetchError = null
                            modelsDialogOpen = true
                        }.onFailure {
                            modelFetchError = it.message
                            modelsDialogOpen = true
                        }
                    }
                },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("获取模型")
            }
        }

        Text(
            "说明：本地直连 LLM，App 不再依赖 Host；Key 将保存在本机 DataStore（未加密）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (modelsDialogOpen) {
        AlertDialog(
            onDismissRequest = { modelsDialogOpen = false },
            title = { Text("可用模型") },
            text = {
                if (modelFetchError != null) {
                    Text("获取失败：$modelFetchError")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.take(30).forEach { Text(it) }
                        if (models.size > 30) Text("…共 ${models.size} 个（仅展示前 30）")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modelsDialogOpen = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun AgentEditor(
    title: String,
    settings: AiSettings,
    agent: AiAgentConfig,
    extra: String,
    onSave: (AiAgentConfig, String) -> Unit,
    snackbar: SnackbarHostState,
) {
    var model by remember(agent.model) { mutableStateOf(agent.model) }
    var systemPrompt by remember(agent.systemPrompt) { mutableStateOf(agent.systemPrompt) }
    var temperature by remember(agent.temperature) { mutableStateOf(agent.temperature.toString()) }
    var maxTokens by remember(agent.maxTokens) { mutableStateOf(agent.maxTokens.toString()) }
    var extraText by remember(extra) { mutableStateOf(extra) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        val provider = settings.providers.firstOrNull { it.id == agent.providerId } ?: settings.providers.first()
        Text(
            "Provider: ${provider.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = temperature,
                onValueChange = { temperature = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) },
                label = { Text("temperature") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("max_tokens") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("系统提示词（System Prompt）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
        )

        OutlinedTextField(
            value = extraText,
            onValueChange = { extraText = it },
            label = { Text(if (title.contains("生题")) "默认出题要求" else "默认讲解风格") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Button(
            onClick = {
                val t = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: agent.temperature
                val m = maxTokens.toIntOrNull()?.coerceIn(128, 8192) ?: agent.maxTokens
                onSave(agent.copy(model = model.trim(), systemPrompt = systemPrompt.trim(), temperature = t, maxTokens = m), extraText.trim())
            },
        ) { Text("保存") }
    }
}

