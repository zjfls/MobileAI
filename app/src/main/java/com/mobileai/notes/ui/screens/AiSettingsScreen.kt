package com.mobileai.notes.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileai.notes.ai.AnthropicClient
import com.mobileai.notes.ai.GoogleGeminiClient
import com.mobileai.notes.ai.OpenAiCompatClient
import com.mobileai.notes.settings.AiAgentConfig
import com.mobileai.notes.settings.AiAgentPreset
import com.mobileai.notes.settings.AiProvider
import com.mobileai.notes.settings.AiProviderType
import com.mobileai.notes.settings.AiSettings
import com.mobileai.notes.settings.AppSettings
import com.mobileai.notes.settings.ExplainerConfig
import com.mobileai.notes.settings.PaperGeneratorConfig
import com.mobileai.notes.ui.widgets.VerticalScrollbar
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { AppSettings.create(context) }
    val settingsOrNull by store.aiSettings.map<AiSettings, AiSettings?> { it }.collectAsState(initial = null)

    var tabIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            val settings = settingsOrNull
            if (settings == null) {
                Text("读取设置中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("LLM") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Agent") })
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("生题器") })
                Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("讲解器") })
            }

            Spacer(Modifier.height(12.dp))

            when (tabIndex) {
                0 ->
                    ProviderPanel(
                        settings = settings,
                        onSave = { scope.launch(start = CoroutineStart.UNDISPATCHED) { store.setAiSettings(it) } },
                        snackbar = snackbar,
                    )
                1 ->
                    AgentPanel(
                        settings = settings,
                        onSave = { scope.launch(start = CoroutineStart.UNDISPATCHED) { store.setAiSettings(it) } },
                        snackbar = snackbar,
                    )
                2 ->
                    PaperGeneratorPanel(
                        settings = settings,
                        onSave = { scope.launch(start = CoroutineStart.UNDISPATCHED) { store.setAiSettings(it) } },
                        snackbar = snackbar,
                    )
                3 ->
                    ExplainerPanel(
                        settings = settings,
                        onSave = { scope.launch(start = CoroutineStart.UNDISPATCHED) { store.setAiSettings(it) } },
                        snackbar = snackbar,
                    )
            }
        }
    }
}

@Composable
private fun ProviderPanel(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(settings.providers) { mutableStateOf(settings.providers.firstOrNull()?.id) }
    val selected = settings.providers.firstOrNull { it.id == selectedId } ?: settings.providers.firstOrNull()
    if (selectedId == null) selectedId = selected?.id

    Row(modifier = Modifier.fillMaxSize()) {
        LeftList(
            title = "Providers",
            items = settings.providers.map { it.id to it.name },
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onAdd = {
                val id = "p_" + UUID.randomUUID().toString().take(8)
                val p = AiProvider(id = id, name = "Provider ${settings.providers.size + 1}")
                onSave(settings.copy(providers = settings.providers + p))
                selectedId = id
                scope.launch { snackbar.showSnackbar("已添加 Provider") }
            },
        )

        Spacer(Modifier.width(12.dp))

        if (selected == null) {
            EmptyRightPanel("请新增一个 Provider")
            return
        }

        ProviderEditor(
            provider = selected,
            onUpdate = { updated ->
                onSave(settings.copy(providers = settings.providers.map { if (it.id == updated.id) updated else it }))
            },
            onDelete = {
                scope.launch {
                    when {
                        settings.providers.size <= 1 -> snackbar.showSnackbar("至少保留一个 Provider")
                        settings.agents.any { it.config.providerId == selected.id } ->
                            snackbar.showSnackbar("该 Provider 正在被 Agent 使用，无法删除")
                        else -> {
                            val remain = settings.providers.filterNot { it.id == selected.id }
                            onSave(settings.copy(providers = remain))
                            selectedId = remain.firstOrNull()?.id
                            snackbar.showSnackbar("已删除")
                        }
                    }
                }
            },
            snackbar = snackbar,
        )
    }
}

@Composable
private fun ProviderEditor(
    provider: AiProvider,
    onUpdate: (AiProvider) -> Unit,
    onDelete: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var type by remember(provider.id) { mutableStateOf(provider.type) }
    var enabled by remember(provider.id) { mutableStateOf(provider.enabled) }
    var showKey by remember(provider.id) { mutableStateOf(false) }

    var typeMenuOpen by remember(provider.id) { mutableStateOf(false) }
    var models by remember(provider.id) { mutableStateOf(provider.models) }
    var modelPickerOpen by remember(provider.id) { mutableStateOf(false) }
    var modelManualAddOpen by remember(provider.id) { mutableStateOf(false) }
    var modelFetchError by remember(provider.id) { mutableStateOf<String?>(null) }
    var fetchedModels by remember(provider.id) { mutableStateOf<List<String>>(emptyList()) }
    var modelQuery by remember(provider.id) { mutableStateOf("") }
    var modelSelected by remember(provider.id) { mutableStateOf<Set<String>>(emptySet()) }
    var manualModelInput by remember(provider.id) { mutableStateOf("") }

    val openaiClient = remember { OpenAiCompatClient() }
    val anthropicClient = remember { AnthropicClient() }
    val googleClient = remember { GoogleGeminiClient() }

    fun buildProvider(modelsOverride: List<String> = models): AiProvider {
        return provider.copy(
            name = name.trim().ifEmpty { provider.name },
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            enabled = enabled,
            type = type,
            models = modelsOverride,
        )
    }

    fun persist(modelsOverride: List<String> = models) {
        onUpdate(buildProvider(modelsOverride = modelsOverride))
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Provider 配置", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist()
                        },
                    )
                    Text(if (enabled) "ON" else "OFF")
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            }

            Text("类型", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value =
                        when (type) {
                            AiProviderType.OPENAI_COMPATIBLE -> "OpenAI Compatible"
                            AiProviderType.ANTHROPIC -> "Anthropic"
                            AiProviderType.GOOGLE -> "Google (Gemini)"
                        },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider 类型") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Box {
                    TextButton(onClick = { typeMenuOpen = true }) { Text("选择") }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        fun pick(t: AiProviderType) {
                            type = t
                            // Best-effort baseUrl suggestion.
                            val suggested =
                                when (t) {
                                    AiProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
                                    AiProviderType.ANTHROPIC -> "https://api.anthropic.com"
                                    AiProviderType.GOOGLE -> "https://generativelanguage.googleapis.com"
                                }
                            if (baseUrl.isBlank() || baseUrl == provider.baseUrl) baseUrl = suggested
                            typeMenuOpen = false
                            persist()
                        }
                        DropdownMenuItem(text = { Text("OpenAI Compatible") }, onClick = { pick(AiProviderType.OPENAI_COMPATIBLE) })
                        DropdownMenuItem(text = { Text("Anthropic") }, onClick = { pick(AiProviderType.ANTHROPIC) })
                        DropdownMenuItem(text = { Text("Google (Gemini)") }, onClick = { pick(AiProviderType.GOOGLE) })
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    persist()
                },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    persist()
                },
                label = { Text("API 地址（Base URL）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    persist()
                },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "显示/隐藏")
                    }
                },
            )

            Text("模型列表（已添加）", style = MaterialTheme.typography.labelLarge)
            val modelListState = remember(provider.id) { LazyListState() }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (models.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("暂无模型，请先「获取模型」或「手动添加」。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            state = modelListState,
                            modifier = Modifier.fillMaxSize().padding(vertical = 6.dp).padding(end = 12.dp),
                        ) {
                            items(models, key = { it }) { m ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            val updated = models.filterNot { it == m }
                                            models = updated
                                            persist(modelsOverride = updated)
                                        },
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "移除模型")
                                    }
                                }
                            }
                        }
                    }

                    VerticalScrollbar(
                        state = modelListState,
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 12.dp)
                                .padding(end = 6.dp)
                                .width(6.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { modelManualAddOpen = true },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("手动添加")
                }

                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val list =
                                    when (type) {
                                        AiProviderType.OPENAI_COMPATIBLE -> openaiClient.listModels(baseUrl.trim(), apiKey.trim())
                                        AiProviderType.ANTHROPIC -> anthropicClient.listModels(baseUrl.trim(), apiKey.trim())
                                        AiProviderType.GOOGLE -> googleClient.listModels(baseUrl.trim(), apiKey.trim())
                                    }
                                fetchedModels = list
                                modelQuery = ""
                                modelSelected = emptySet()
                                modelFetchError = null
                                modelPickerOpen = true
                            }.onFailure {
                                modelFetchError = it.message
                                modelPickerOpen = true
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
                "已添加模型：${models.size}；支持 OpenAI-Compatible / Anthropic / Google Gemini。Key 将保存在本机 DataStore（未加密）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (modelPickerOpen) {
        AlertDialog(
            onDismissRequest = { modelPickerOpen = false },
            title = { Text("获取到的模型") },
            text = {
                if (modelFetchError != null) {
                    Text("获取失败：$modelFetchError")
                } else {
                    val filtered =
                        if (modelQuery.isBlank()) {
                            fetchedModels
                        } else {
                            val q = modelQuery.trim()
                            fetchedModels.filter { it.contains(q, ignoreCase = true) }
                        }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            label = { Text("搜索模型") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (modelQuery.isNotBlank()) {
                                    IconButton(onClick = { modelQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空")
                                    }
                                }
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "已选 ${modelSelected.size} / ${filtered.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(
                                    enabled = filtered.isNotEmpty(),
                                    onClick = { modelSelected = filtered.toSet() },
                                ) { Text("全选") }
                                TextButton(
                                    enabled = modelSelected.isNotEmpty(),
                                    onClick = { modelSelected = emptySet() },
                                ) { Text("清空") }
                            }
                        }

                        val fetchedListState = rememberLazyListState()
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                LazyColumn(
                                    state = fetchedListState,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).padding(end = 12.dp),
                                ) {
                                    items(filtered, key = { it }) { m ->
                                        val checked = modelSelected.contains(m)
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        modelSelected =
                                                            if (checked) modelSelected - m else modelSelected + m
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Checkbox(checked = checked, onCheckedChange = null)
                                            Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }

                                VerticalScrollbar(
                                    state = fetchedListState,
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(vertical = 12.dp)
                                            .padding(end = 6.dp)
                                            .width(6.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modelFetchError == null && modelSelected.isNotEmpty(),
                    onClick = {
                        val merged = (models + modelSelected).distinct()
                        models = merged
                        persist(modelsOverride = merged)
                        modelPickerOpen = false
                        scope.launch { snackbar.showSnackbar("已添加 ${modelSelected.size} 个模型") }
                    },
                ) { Text("添加选中(${modelSelected.size})") }
            },
            dismissButton = { TextButton(onClick = { modelPickerOpen = false }) { Text("取消") } },
        )
    }

    if (modelManualAddOpen) {
        AlertDialog(
            onDismissRequest = { modelManualAddOpen = false },
            title = { Text("手动添加模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = manualModelInput,
                        onValueChange = { manualModelInput = it },
                        label = { Text("模型名 / ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("例如：gpt-4o-mini / claude-3-5-sonnet / gemini-1.5-pro") },
                    )
                    Text(
                        "保存到该 Provider 的模型库，Agent 里可直接选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val m = manualModelInput.trim()
                        if (m.isBlank()) return@TextButton
                        val merged = (models + m).distinct()
                        models = merged
                        persist(modelsOverride = merged)
                        manualModelInput = ""
                        modelManualAddOpen = false
                        scope.launch { snackbar.showSnackbar("已添加：$m") }
                    },
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { modelManualAddOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AgentPanel(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(settings.agents) { mutableStateOf(settings.agents.firstOrNull()?.id) }
    val selected = settings.agents.firstOrNull { it.id == selectedId } ?: settings.agents.firstOrNull()
    if (selectedId == null) selectedId = selected?.id

    Row(modifier = Modifier.fillMaxSize()) {
        LeftList(
            title = "Agent（LLM + System Prompt）",
            items = settings.agents.map { it.id to it.name },
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onAdd = {
                val providerId = settings.providers.firstOrNull()?.id ?: "default"
                val id = "a_" + UUID.randomUUID().toString().take(8)
                val agent =
                    AiAgentPreset(
                        id = id,
                        name = "Agent ${settings.agents.size + 1}",
                        config = AiAgentConfig(providerId = providerId, model = "gpt-4o-mini", systemPrompt = ""),
                    )
                onSave(settings.copy(agents = settings.agents + agent))
                selectedId = id
                scope.launch { snackbar.showSnackbar("已添加 Agent") }
            },
        )

        Spacer(Modifier.width(12.dp))

        if (selected == null) {
            EmptyRightPanel("请新增一个 Agent 配置")
            return
        }

        AgentPresetEditor(
            preset = selected,
            providers = settings.providers,
            onSave = { updated ->
                onSave(settings.copy(agents = settings.agents.map { if (it.id == updated.id) updated else it }))
            },
            onDelete = {
                scope.launch {
                    val inUseByGenerator = settings.paperGenerators.any { it.agentId == selected.id }
                    val inUseByExplainer = settings.explainers.any { it.agentId == selected.id }
                    when {
                        inUseByGenerator || inUseByExplainer -> snackbar.showSnackbar("该 Agent 正在被生题器/讲解器使用，无法删除")
                        settings.agents.size <= 1 -> snackbar.showSnackbar("至少保留一个 Agent")
                        else -> {
                            val remain = settings.agents.filterNot { it.id == selected.id }
                            onSave(settings.copy(agents = remain))
                            selectedId = remain.firstOrNull()?.id
                            snackbar.showSnackbar("已删除")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun AgentPresetEditor(
    preset: AiAgentPreset,
    providers: List<AiProvider>,
    onSave: (AiAgentPreset) -> Unit,
    onDelete: () -> Unit,
) {
    var displayName by remember(preset.id) { mutableStateOf(preset.name) }
    var enabled by remember(preset.id) { mutableStateOf(preset.enabled) }

    val providerOptions = providers
    var providerId by remember(preset.id) { mutableStateOf(preset.config.providerId) }
    var model by remember(preset.id) { mutableStateOf(preset.config.model) }
    var temperature by remember(preset.id) { mutableStateOf(preset.config.temperature.toString()) }
    var maxTokens by remember(preset.id) { mutableStateOf(preset.config.maxTokens.toString()) }
    var systemPrompt by remember(preset.id) { mutableStateOf(preset.config.systemPrompt) }

    var providerMenuOpen by remember(preset.id) { mutableStateOf(false) }
    val currentProviderName = providerOptions.firstOrNull { it.id == providerId }?.name ?: providerId
    val currentProvider = providerOptions.firstOrNull { it.id == providerId }
    var modelMenuOpen by remember(preset.id) { mutableStateOf(false) }

    fun buildPreset(): AiAgentPreset {
        val t = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: preset.config.temperature
        val mt = maxTokens.toIntOrNull()?.coerceIn(128, 8192) ?: preset.config.maxTokens
        return preset.copy(
            name = displayName.trim().ifBlank { preset.name },
            enabled = enabled,
            config =
                AiAgentConfig(
                    providerId = providerId,
                    model = model.trim(),
                    systemPrompt = systemPrompt.trim(),
                    temperature = t,
                    maxTokens = mt,
                ),
        )
    }

    fun persist() {
        onSave(buildPreset())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent 配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        persist()
                    },
                )
                Text(if (enabled) "ON" else "OFF")
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    persist()
                },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LLM 配置", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentProviderName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Box {
                        TextButton(onClick = { providerMenuOpen = true }) { Text("选择") }
                        DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
                            providerOptions.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(if (p.enabled) p.name else "${p.name}（OFF）") },
                                    onClick = { providerId = p.id; providerMenuOpen = false; persist() },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        persist()
                    },
                    label = { Text("模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (!currentProvider?.models.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "从缓存选择（${currentProvider?.models?.size ?: 0}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            TextButton(onClick = { modelMenuOpen = true }) { Text("选择模型") }
                            DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                                (currentProvider?.models ?: emptyList()).take(200).forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { model = m; modelMenuOpen = false; persist() },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "提示：先在 Provider 里「获取模型」并添加，Agent 才能下拉选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = temperature,
                        onValueChange = {
                            temperature = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4)
                            persist()
                        },
                        label = { Text("temperature") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = {
                            maxTokens = it.filter { ch -> ch.isDigit() }.take(5)
                            persist()
                        },
                        label = { Text("max_tokens") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        systemPrompt = it
                        persist()
                    },
                    label = { Text("系统提示词（System Prompt）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            }

            Text("已自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaperGeneratorPanel(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(settings.paperGenerators) { mutableStateOf(settings.defaultPaperGeneratorId) }
    val selected = settings.paperGenerators.firstOrNull { it.id == selectedId } ?: settings.paperGenerators.firstOrNull()
    if (selectedId.isBlank()) selectedId = selected?.id.orEmpty()

    Row(modifier = Modifier.fillMaxSize()) {
        LeftList(
            title = "生题器",
            items = settings.paperGenerators.map { it.id to it.name },
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onAdd = {
                val fallbackProviderId = settings.providers.firstOrNull()?.id ?: "default"
                val (newSettings, agentId) =
                    if (settings.agents.isNotEmpty()) {
                        settings to settings.agents.first().id
                    } else {
                        val aid = "a_" + UUID.randomUUID().toString().take(8)
                        val a =
                            AiAgentPreset(
                                id = aid,
                                name = "默认 Agent",
                                config = AiAgentConfig(providerId = fallbackProviderId, model = "gpt-4o-mini", systemPrompt = ""),
                            )
                        settings.copy(agents = settings.agents + a) to aid
                    }

                val id = "gen_" + UUID.randomUUID().toString().take(8)
                val cfg = PaperGeneratorConfig(id = id, name = "生题器 ${settings.paperGenerators.size + 1}", agentId = agentId)
                onSave(
                    newSettings.copy(
                        paperGenerators = newSettings.paperGenerators + cfg,
                        defaultPaperGeneratorId = newSettings.defaultPaperGeneratorId.ifBlank { id },
                    ),
                )
                selectedId = id
                scope.launch { snackbar.showSnackbar("已添加生题器") }
            },
        )

        Spacer(Modifier.width(12.dp))

        if (selected == null) {
            EmptyRightPanel("请新增一个生题器配置")
            return
        }

        PaperGeneratorEditor(
            generator = selected,
            agents = settings.agents,
            isDefault = selected.id == settings.defaultPaperGeneratorId,
            onSetDefault = {
                onSave(settings.copy(defaultPaperGeneratorId = selected.id))
                scope.launch { snackbar.showSnackbar("已设为默认") }
            },
            onSave = { updated ->
                onSave(settings.copy(paperGenerators = settings.paperGenerators.map { if (it.id == updated.id) updated else it }))
            },
            onDelete = {
                val remain = settings.paperGenerators.filterNot { it.id == selected.id }
                val newDefault =
                    if (settings.defaultPaperGeneratorId == selected.id) remain.firstOrNull()?.id.orEmpty() else settings.defaultPaperGeneratorId
                onSave(settings.copy(paperGenerators = remain, defaultPaperGeneratorId = newDefault))
                selectedId = newDefault
                scope.launch { snackbar.showSnackbar("已删除") }
            },
        )
    }
}

@Composable
private fun PaperGeneratorEditor(
    generator: PaperGeneratorConfig,
    agents: List<AiAgentPreset>,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onSave: (PaperGeneratorConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var displayName by remember(generator.id) { mutableStateOf(generator.name) }
    var enabled by remember(generator.id) { mutableStateOf(generator.enabled) }
    var agentId by remember(generator.id) { mutableStateOf(generator.agentId) }
    var promptPreset by remember(generator.id) { mutableStateOf(generator.promptPreset) }
    var count by remember(generator.id) { mutableStateOf(generator.count.toString()) }

    var agentMenuOpen by remember(generator.id) { mutableStateOf(false) }
    val currentAgentName = agents.firstOrNull { it.id == agentId }?.name ?: agentId

    fun buildGenerator(): PaperGeneratorConfig {
        val c = count.toIntOrNull()?.coerceIn(1, 30) ?: generator.count
        return generator.copy(
            name = displayName.trim().ifBlank { generator.name },
            enabled = enabled,
            agentId = agentId,
            promptPreset = promptPreset.trim(),
            count = c,
        )
    }

    fun persist() {
        onSave(buildGenerator())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("生题器配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onSetDefault) {
                    Icon(if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "设为默认")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        persist()
                    },
                )
                Text(if (enabled) "ON" else "OFF")
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    persist()
                },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("使用的 Agent", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentAgentName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Agent") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Box {
                    TextButton(onClick = { agentMenuOpen = true }) { Text("选择") }
                    DropdownMenu(expanded = agentMenuOpen, onDismissRequest = { agentMenuOpen = false }) {
                        agents.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(if (a.enabled) a.name else "${a.name}（OFF）") },
                                onClick = { agentId = a.id; agentMenuOpen = false; persist() },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = promptPreset,
                onValueChange = {
                    promptPreset = it
                    persist()
                },
                label = { Text("默认出题要求（可被弹窗覆盖）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            OutlinedTextField(
                value = count,
                onValueChange = {
                    count = it.filter { ch -> ch.isDigit() }.take(2)
                    persist()
                },
                label = { Text("默认题目数量") },
                singleLine = true,
                modifier = Modifier.width(220.dp),
            )

            Text("已自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExplainerPanel(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(settings.explainers) { mutableStateOf(settings.defaultExplainerId) }
    val selected = settings.explainers.firstOrNull { it.id == selectedId } ?: settings.explainers.firstOrNull()
    if (selectedId.isBlank()) selectedId = selected?.id.orEmpty()

    Row(modifier = Modifier.fillMaxSize()) {
        LeftList(
            title = "讲解器",
            items = settings.explainers.map { it.id to it.name },
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onAdd = {
                val fallbackProviderId = settings.providers.firstOrNull()?.id ?: "default"
                val (newSettings, agentId) =
                    if (settings.agents.isNotEmpty()) {
                        settings to settings.agents.first().id
                    } else {
                        val aid = "a_" + UUID.randomUUID().toString().take(8)
                        val a =
                            AiAgentPreset(
                                id = aid,
                                name = "默认 Agent",
                                config = AiAgentConfig(providerId = fallbackProviderId, model = "gpt-4o-mini", systemPrompt = ""),
                            )
                        settings.copy(agents = settings.agents + a) to aid
                    }

                val id = "exp_" + UUID.randomUUID().toString().take(8)
                val cfg = ExplainerConfig(id = id, name = "讲解器 ${settings.explainers.size + 1}", agentId = agentId)
                onSave(newSettings.copy(explainers = newSettings.explainers + cfg, defaultExplainerId = newSettings.defaultExplainerId.ifBlank { id }))
                selectedId = id
                scope.launch { snackbar.showSnackbar("已添加讲解器") }
            },
        )

        Spacer(Modifier.width(12.dp))

        if (selected == null) {
            EmptyRightPanel("请新增一个讲解器配置")
            return
        }

        ExplainerEditor(
            explainer = selected,
            agents = settings.agents,
            isDefault = selected.id == settings.defaultExplainerId,
            onSetDefault = {
                onSave(settings.copy(defaultExplainerId = selected.id))
                scope.launch { snackbar.showSnackbar("已设为默认") }
            },
            onSave = { updated ->
                onSave(settings.copy(explainers = settings.explainers.map { if (it.id == updated.id) updated else it }))
            },
            onDelete = {
                val remain = settings.explainers.filterNot { it.id == selected.id }
                val newDefault =
                    if (settings.defaultExplainerId == selected.id) remain.firstOrNull()?.id.orEmpty() else settings.defaultExplainerId
                onSave(settings.copy(explainers = remain, defaultExplainerId = newDefault))
                selectedId = newDefault
                scope.launch { snackbar.showSnackbar("已删除") }
            },
        )
    }
}

@Composable
private fun ExplainerEditor(
    explainer: ExplainerConfig,
    agents: List<AiAgentPreset>,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onSave: (ExplainerConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var displayName by remember(explainer.id) { mutableStateOf(explainer.name) }
    var enabled by remember(explainer.id) { mutableStateOf(explainer.enabled) }
    var agentId by remember(explainer.id) { mutableStateOf(explainer.agentId) }
    var style by remember(explainer.id) { mutableStateOf(explainer.style) }

    var agentMenuOpen by remember(explainer.id) { mutableStateOf(false) }
    val currentAgentName = agents.firstOrNull { it.id == agentId }?.name ?: agentId

    fun buildExplainer(): ExplainerConfig {
        return explainer.copy(
            name = displayName.trim().ifBlank { explainer.name },
            enabled = enabled,
            agentId = agentId,
            style = style.trim(),
        )
    }

    fun persist() {
        onSave(buildExplainer())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("讲解器配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onSetDefault) {
                    Icon(if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "设为默认")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        persist()
                    },
                )
                Text(if (enabled) "ON" else "OFF")
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    persist()
                },
                label = { Text("显示名称") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("使用的 Agent", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentAgentName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Agent") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Box {
                    TextButton(onClick = { agentMenuOpen = true }) { Text("选择") }
                    DropdownMenu(expanded = agentMenuOpen, onDismissRequest = { agentMenuOpen = false }) {
                        agents.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(if (a.enabled) a.name else "${a.name}（OFF）") },
                                onClick = { agentId = a.id; agentMenuOpen = false; persist() },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = style,
                onValueChange = {
                    style = it
                    persist()
                },
                label = { Text("默认讲解风格") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Text("已自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LeftList(
    title: String,
    items: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(min = 220.dp, max = 360.dp).fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "新增") }
            }
            Divider()
            val listState = rememberLazyListState()
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                ) {
                    items(items, key = { it.first }) { (id, name) ->
                        val selected = id == selectedId
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(id) },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    contentColor =
                                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (selected) Text("编辑", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    state = listState,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 12.dp)
                            .padding(end = 6.dp)
                            .width(6.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyRightPanel(text: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
