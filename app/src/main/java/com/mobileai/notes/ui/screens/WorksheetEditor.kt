package com.mobileai.notes.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.data.WorksheetNote
import com.mobileai.notes.data.WorksheetPage
import com.mobileai.notes.export.ExportManager
import com.mobileai.notes.host.HostClient
import com.mobileai.notes.host.HostQuestion
import com.mobileai.notes.ink.InkCanvas
import com.mobileai.notes.settings.AppSettings
import com.mobileai.notes.ui.widgets.PageTemplateBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorksheetEditor(
    doc: DocumentEntity,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    onDocChange: (DocumentEntity, committed: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val settings = remember { AppSettings.create(context) }
    val hostSettingsState = settings.hostSettings.collectAsState(initial = com.mobileai.notes.settings.HostSettings.Default)
    val hostBaseUrl = hostSettingsState.value.baseUrl
    var hostBaseUrlDraft by remember(hostBaseUrl) { mutableStateOf(hostBaseUrl) }

    val host = remember { HostClient() }

    val worksheet = doc.worksheet ?: WorksheetNote()
    var pageIndex by remember(doc.id) { mutableStateOf(0) }
    val currentPage = worksheet.pages.getOrNull(pageIndex)

    var aiDialogOpen by remember { mutableStateOf(false) }
    var aiDialogText by remember { mutableStateOf("") }
    var aiGenerateOpen by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("生成一套数学/物理试卷，难度适中") }
    var aiCount by remember { mutableStateOf("5") }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar
        Surface(
            modifier = Modifier.width(300.dp).fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.large,
                        shadowElevation = 8.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Aura Note", style = MaterialTheme.typography.titleMedium)
                        Text(
                            doc.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    "PAGES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(worksheet.pages) { idx, p ->
                        val selected = idx == pageIndex
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = if (selected) 2.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pageIndex = idx }
                                .padding(0.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.title ?: "Page ${idx + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!p.backgroundImageUri.isNullOrBlank()) {
                                        Text(
                                            "题目图片",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    "CLOUD HOST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                Surface(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "ENDPOINT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = hostBaseUrlDraft,
                            onValueChange = { hostBaseUrlDraft = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://api.example.com") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        settings.setHostBaseUrl(hostBaseUrlDraft)
                                        snackbar.showSnackbar("Host 已保存")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("保存") }
                            Button(
                                onClick = {
                                    val updated = doc.copy(worksheet = worksheet.copy(pages = listOf(WorksheetPage(id = UUID.randomUUID().toString()))))
                                    onDocChange(updated, true)
                                    pageIndex = 0
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("新试卷") }
                        }

                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val questions = host.fetchQuestions(hostBaseUrl)
                                        val pages = materializeQuestionsToPages(context, doc.id, questions)
                                        val updated = doc.copy(worksheet = worksheet.copy(pages = pages))
                                        onDocChange(updated, true)
                                        pageIndex = 0
                                    }.onFailure {
                                        snackbar.showSnackbar("拉题失败：${it.message}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetch Exercises")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val idx = pageIndex
                                    val bytes =
                                        runCatching { ExportManager.renderWorksheetPagePngBytes(context, doc, idx) }.getOrNull()
                                    if (bytes == null) {
                                        snackbar.showSnackbar("当前页无法导出")
                                        return@launch
                                    }
                                    runCatching {
                                        host.uploadPagePng(hostBaseUrl, paperId = doc.id, pageIndex = idx, pngBytes = bytes)
                                        snackbar.showSnackbar("已同步到 Host")
                                    }.onFailure {
                                        snackbar.showSnackbar("同步失败：${it.message}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sync to Host")
                        }
                    }
                }
            }
        }

        // Main
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header (breadcrumb + pager + AI)
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 1.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "My Workbook",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                doc.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        enabled = pageIndex > 0,
                                        onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                                    ) { Icon(Icons.Filled.NavigateBefore, contentDescription = "上一页") }
                                    Text(
                                        "${pageIndex + 1} / ${worksheet.pages.size}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                    )
                                    TextButton(
                                        enabled = pageIndex < worksheet.pages.lastIndex,
                                        onClick = { pageIndex = (pageIndex + 1).coerceAtMost(worksheet.pages.lastIndex) },
                                    ) { Icon(Icons.Filled.NavigateNext, contentDescription = "下一页") }
                                }
                            }
                            Button(onClick = { aiGenerateOpen = true }) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("AI Generate Paper")
                            }
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val idx = pageIndex
                                        val bytes =
                                            runCatching { ExportManager.renderWorksheetPagePngBytes(context, doc, idx) }.getOrNull()
                                        if (bytes == null) {
                                            snackbar.showSnackbar("当前页无法导出")
                                            return@launch
                                        }
                                        runCatching {
                                            val answer = host.solvePage(hostBaseUrl, bytes)
                                            aiDialogText = answer
                                            aiDialogOpen = true
                                        }.onFailure {
                                            snackbar.showSnackbar("AI 解答失败：${it.message}")
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Psychology, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("AI 解答")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (currentPage == null) {
                    Text("没有页面")
                } else {
                    WorksheetPageView(
                        template = worksheet.template,
                        page = currentPage,
                        isEraser = isEraser,
                        tool = tool,
                        colorArgb = colorArgb,
                        size = size,
                        simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                        onPageChange = { updatedPage, committed ->
                            val pages = worksheet.pages.toMutableList()
                            pages[pageIndex] = updatedPage
                            onDocChange(doc.copy(worksheet = worksheet.copy(pages = pages)), committed)
                        },
                    )
                }
            }

            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    if (aiGenerateOpen) {
        AlertDialog(
            onDismissRequest = { aiGenerateOpen = false },
            title = { Text("AI 生成试卷") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        label = { Text("题目要求/范围") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = aiCount,
                        onValueChange = { aiCount = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("题目数量") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "提示：该按钮会向 Host 发送请求，由 Host 调用 AI 出题并返回题目图片/链接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        aiGenerateOpen = false
                        val count = aiCount.toIntOrNull()?.coerceIn(1, 30) ?: 5
                        scope.launch {
                            runCatching {
                                val questions = host.generatePaper(hostBaseUrl, aiPrompt, count)
                                val pages = materializeQuestionsToPages(context, doc.id, questions)
                                val updated = doc.copy(worksheet = worksheet.copy(pages = pages))
                                onDocChange(updated, true)
                                pageIndex = 0
                            }.onFailure {
                                snackbar.showSnackbar("生成失败：${it.message}")
                            }
                        }
                    },
                ) { Text("生成") }
            },
            dismissButton = {
                TextButton(onClick = { aiGenerateOpen = false }) { Text("取消") }
            },
        )
    }

    if (aiDialogOpen) {
        AlertDialog(
            onDismissRequest = { aiDialogOpen = false },
            title = { Text("AI 解答") },
            text = {
                Text(
                    aiDialogText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { aiDialogOpen = false }) { Text("关闭") } },
        )
    }

    // Long-press analyze (simple affordance): analyze current page.
    DisposableEffect(pageIndex, currentPage?.strokes?.size) {
        onDispose { }
    }
}

@Composable
private fun WorksheetPageView(
    template: PageTemplate,
    page: WorksheetPage,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    onPageChange: (WorksheetPage, committed: Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = page.backgroundImageUri) {
        value =
            page.backgroundImageUri?.let { uriString ->
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
    }
    val bitmap = bitmapState.value

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Template background when no image.
                PageTemplateBackground(template = template)
            }

            InkCanvas(
                strokes = page.strokes,
                modifier = Modifier.fillMaxSize(),
                isEraser = isEraser,
                tool = tool,
                colorArgb = colorArgb,
                size = size,
                simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                onStrokesChange = { updatedStrokes, committed ->
                    onPageChange(
                        page.copy(
                            strokes = updatedStrokes,
                            canvasWidthPx = bitmap?.width ?: page.canvasWidthPx,
                            canvasHeightPx = bitmap?.height ?: page.canvasHeightPx,
                        ),
                        committed,
                    )
                },
            )
        }
    }
}

private suspend fun materializeQuestionsToPages(
    context: android.content.Context,
    docId: String,
    questions: List<HostQuestion>,
): List<WorksheetPage> = withContext(Dispatchers.IO) {
    val outDir = File(context.filesDir, "worksheet-assets/$docId").apply { mkdirs() }
    val client = okhttp3.OkHttpClient()

    questions.mapIndexed { index, q ->
        val imageUri =
            when {
                !q.imageBase64.isNullOrBlank() -> {
                    val bytes = android.util.Base64.decode(q.imageBase64, android.util.Base64.DEFAULT)
                    val file = File(outDir, "q_${index + 1}_${q.id}.png")
                    file.writeBytes(bytes)
                    Uri.fromFile(file).toString()
                }
                !q.imageUrl.isNullOrBlank() -> {
                    val req = okhttp3.Request.Builder().url(q.imageUrl).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("image HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        val file = File(outDir, "q_${index + 1}_${q.id}.png")
                        file.writeBytes(bytes)
                        Uri.fromFile(file).toString()
                    }
                }
                else -> null
            }

        WorksheetPage(
            id = UUID.randomUUID().toString(),
            title = q.text?.takeIf { it.isNotBlank() } ?: "Question ${index + 1}",
            backgroundImageUri = imageUri,
            strokes = emptyList(),
        )
    }.ifEmpty { listOf(WorksheetPage(id = UUID.randomUUID().toString())) }
}
