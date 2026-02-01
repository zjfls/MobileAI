package com.mobileai.notes.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.mobileai.notes.ui.widgets.MathText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.ui.widgets.VerticalScrollbar
import com.mobileai.notes.data.WorksheetNote
import com.mobileai.notes.data.WorksheetPage
import com.mobileai.notes.data.WorksheetPageType
import com.mobileai.notes.export.ExportManager
import com.mobileai.notes.ai.AiAgents
import com.mobileai.notes.ai.AiHttpException
import com.mobileai.notes.ai.AiJsonParseException
import com.mobileai.notes.ai.GeneratedQuestion
import com.mobileai.notes.ink.InkCanvas
import com.mobileai.notes.ink.InkFeel
import com.mobileai.notes.settings.AppSettings
import com.mobileai.notes.ui.widgets.PageTemplateBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorksheetEditor(
    doc: DocumentEntity,
    isFullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    inkFeel: InkFeel = InkFeel(),
    openGenerateOnStart: Boolean = false,
    onDocChange: (DocumentEntity, committed: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val settings = remember { AppSettings.create(context) }

    val aiSettingsState = settings.aiSettings.collectAsState(initial = com.mobileai.notes.settings.AiSettings())
    val aiSettings = aiSettingsState.value
    val aiAgents = remember { AiAgents() }

    val worksheet = doc.worksheet ?: WorksheetNote()
    val latestDoc = rememberUpdatedState(doc)
    var pageIndex by remember(doc.id) { mutableStateOf(0) }
    val pageScrollY = remember(doc.id) { mutableStateMapOf<String, Int>() }
    val currentPage = worksheet.pages.getOrNull(pageIndex)

    var aiGenerateOpen by remember { mutableStateOf(false) }
    var aiExplainOpen by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiCount by remember { mutableStateOf("") }
    var aiGenerating by remember { mutableStateOf(false) }
    var aiExplaining by remember { mutableStateOf(false) }
    var aiExplainJob by remember { mutableStateOf<Job?>(null) }
    var aiElapsedSeconds by remember { mutableStateOf(0) }
    var aiExplainExtra by remember { mutableStateOf("") }
    var selectedGeneratorId by remember { mutableStateOf<String?>(null) }
    var selectedExplainerId by remember { mutableStateOf<String?>(null) }
    var aiDebugOpen by remember { mutableStateOf(false) }
    var aiDebugTitle by remember { mutableStateOf("") }
    var aiDebugDetailsFull by remember { mutableStateOf("") }
    var aiDebugDetails by remember { mutableStateOf("") }
    var aiDebugRequestJsonRaw by remember { mutableStateOf<String?>(null) }
    var aiDebugPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aiDebugPreviewPngBytes by remember { mutableStateOf<ByteArray?>(null) }
    var aiDebugCloseCommits by remember { mutableStateOf(false) }
    var aiDebugPendingCommit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var aiExplainLastRequestJsonRaw by remember(doc.id, pageIndex) { mutableStateOf<String?>(null) }
    var aiExplainLastRequestPreview by remember(doc.id, pageIndex) { mutableStateOf<String?>(null) }

    fun showAiDebug(title: String, details: String, requestJsonRaw: String? = null) {
        aiDebugTitle = title
        aiDebugDetailsFull = details
        // Don't render extremely large strings directly in Compose dialogs (can crash on some devices).
        val maxChars = 80_000
        aiDebugDetails =
            if (details.length <= maxChars) {
                details
            } else {
                details.take(maxChars) + "\n\n<omitted tail len=${details.length - maxChars}>"
            }
        aiDebugRequestJsonRaw = requestJsonRaw
        aiDebugOpen = true
    }

    fun setAiDebugPreview(bytes: ByteArray?) {
        // Do NOT call Bitmap.recycle() here: Compose may still be drawing the old Bitmap when recomposition happens,
        // which can lead to native crashes ("Canvas: trying to use a recycled bitmap").
        aiDebugPreviewPngBytes = bytes
        aiDebugPreviewBitmap = null
        if (bytes == null) return
        runCatching {
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = max(1, bounds.outWidth)
            val h = max(1, bounds.outHeight)
            val longEdge = max(w, h)
            val target = 720
            val sample = max(1, longEdge / target)
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.onSuccess { bmp ->
            if (bmp != null) aiDebugPreviewBitmap = bmp
        }
    }

    fun formatAiThrowableForDebug(t: Throwable): String =
        when (t) {
            is AiJsonParseException ->
                buildString {
                    appendLine(t.message.orEmpty())
                    appendLine()
                    appendLine(t.debugHttp)
                    appendLine()
                    appendLine("-----")
                    appendLine()
                    appendLine(t.stackTraceToString())
                }
            is AiHttpException ->
                buildString {
                    appendLine(t.exchange.toDebugString())
                    appendLine()
                    appendLine("-----")
                    appendLine()
                    appendLine(t.stackTraceToString())
                }
            else ->
                buildString {
                    appendLine("error=${t::class.qualifiedName}")
                    appendLine("message=${t.message}")
                    appendLine()
                    appendLine(t.stackTraceToString())
                }
        }

    val openGeneratePaperDialog: () -> Unit = {
        val enabledGenerators = aiSettings.paperGenerators.filter { it.enabled }
        val selected =
            enabledGenerators.firstOrNull { it.id == selectedGeneratorId }
                ?: enabledGenerators.firstOrNull { it.id == aiSettings.defaultPaperGeneratorId }
                ?: enabledGenerators.firstOrNull()
                ?: aiSettings.paperGenerators.firstOrNull()

        if (selected == null) {
            selectedGeneratorId = null
            aiPrompt = ""
            aiCount = ""
        } else {
            val selectionValid = enabledGenerators.any { it.id == selectedGeneratorId }
            if (!selectionValid) {
                selectedGeneratorId = selected.id
                aiPrompt = selected.promptPreset
                aiCount = selected.count.toString()
            } else {
                if (aiPrompt.isBlank()) aiPrompt = selected.promptPreset
                if (aiCount.isBlank()) aiCount = selected.count.toString()
            }
        }
        aiGenerateOpen = true
    }

    var autoGenerateConsumed by remember(doc.id) { mutableStateOf(false) }
    LaunchedEffect(doc.id, openGenerateOnStart) {
        if (openGenerateOnStart && !autoGenerateConsumed) {
            openGeneratePaperDialog()
            autoGenerateConsumed = true
        }
    }

    val openExplainDialog: () -> Unit = {
        val explainer =
            aiSettings.explainers.firstOrNull { it.id == aiSettings.defaultExplainerId && it.enabled }
                ?: aiSettings.explainers.firstOrNull { it.enabled }
                ?: aiSettings.explainers.firstOrNull()
        selectedExplainerId = explainer?.id
        aiExplainExtra = explainer?.style.orEmpty()
        aiExplainOpen = true
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (!isFullScreen) {
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
                val pageListState = rememberLazyListState()
                val questionItems =
                    remember(worksheet.pages) {
                        worksheet.pages.withIndex().filter { it.value.type == WorksheetPageType.QUESTION }
                    }
                val currentQuestionId =
                    when (currentPage?.type) {
                        WorksheetPageType.QUESTION -> currentPage.id
                        WorksheetPageType.ANSWER -> currentPage.answerForPageId
                        else -> null
                    }
                val answerItemsForCurrentQuestion =
                    remember(worksheet.pages, currentQuestionId) {
                        if (currentQuestionId.isNullOrBlank()) {
                            emptyList()
                        } else {
                            worksheet.pages.withIndex()
                                .filter { it.value.type == WorksheetPageType.ANSWER && it.value.answerForPageId == currentQuestionId }
                        }
                    }
                Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    LazyColumn(
                        state = pageListState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(questionItems, key = { _, it -> it.value.id }) { qIdx, indexed ->
                            val idx = indexed.index
                            val p = indexed.value
                            val selected = idx == pageIndex
                            val displayTitle = p.title?.takeIf { it.isNotBlank() } ?: "题目 ${qIdx + 1}"
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
                                            displayTitle,
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

                    VerticalScrollbar(
                        state = pageListState,
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 12.dp)
                                .padding(end = 6.dp)
                                .width(6.dp),
                    )
                }

                Text(
                    "ANSWER PAGES",
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
                        if (currentQuestionId.isNullOrBlank()) {
                            Text(
                                "选择一个题目页后，这里会显示对应的讲解页",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (answerItemsForCurrentQuestion.isEmpty()) {
                            Text(
                                "还没有讲解页：点击右侧「AI 解答」生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            answerItemsForCurrentQuestion.forEachIndexed { aIdx, indexed ->
                                val idx = indexed.index
                                val p = indexed.value
                                val selected = idx == pageIndex
                                val displayTitle = p.title?.takeIf { it.isNotBlank() } ?: "讲解 ${aIdx + 1}"
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
                                            Icons.Filled.Psychology,
                                            contentDescription = null,
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                displayTitle,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        // Main
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(if (isFullScreen) 0.dp else 16.dp)) {
                if (!isFullScreen) {
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
                                Button(onClick = openGeneratePaperDialog) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("生成题目")
                                }
                                FilledTonalButton(
                                    onClick = openExplainDialog,
                                ) {
                                    Icon(Icons.Filled.Psychology, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("AI 解答")
                                }
                                IconButton(onClick = { onFullScreenChange(true) }) {
                                    Icon(
                                        Icons.Filled.Fullscreen,
                                        contentDescription = "全屏",
                                    )
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
                            pageScrollY = pageScrollY,
                            isFullScreen = false,
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
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (currentPage == null) {
                            Text("没有页面", modifier = Modifier.align(Alignment.Center))
                        } else {
                            WorksheetPageView(
                                template = worksheet.template,
                                page = currentPage,
                                pageScrollY = pageScrollY,
                                isFullScreen = true,
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

                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 1.dp,
                            shadowElevation = 8.dp,
                            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.extraLarge,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(
                                            enabled = pageIndex > 0,
                                            onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                                        ) { Icon(Icons.Filled.NavigateBefore, contentDescription = "上一页") }
                                        Text(
                                            "${pageIndex + 1} / ${worksheet.pages.size}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(
                                            enabled = pageIndex < worksheet.pages.lastIndex,
                                            onClick = { pageIndex = (pageIndex + 1).coerceAtMost(worksheet.pages.lastIndex) },
                                        ) { Icon(Icons.Filled.NavigateNext, contentDescription = "下一页") }
                                    }
                                }

                                IconButton(onClick = openGeneratePaperDialog) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 生题")
                                }
                                IconButton(onClick = openExplainDialog) {
                                    Icon(Icons.Filled.Psychology, contentDescription = "AI 解答")
                                }
                                IconButton(onClick = { onFullScreenChange(false) }) {
                                    Icon(Icons.Filled.FullscreenExit, contentDescription = "退出全屏")
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    val aiBusy = aiGenerating || aiExplaining
    LaunchedEffect(aiBusy) {
        if (aiBusy) {
            val start = System.currentTimeMillis()
            while (aiGenerating || aiExplaining) {
                aiElapsedSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                delay(1000)
            }
        } else {
            aiElapsedSeconds = 0
        }
    }

    if (aiGenerateOpen) {
        val enabledGenerators = aiSettings.paperGenerators.filter { it.enabled }
        val generator =
            enabledGenerators.firstOrNull { it.id == selectedGeneratorId }
                ?: enabledGenerators.firstOrNull { it.id == aiSettings.defaultPaperGeneratorId }
                ?: enabledGenerators.firstOrNull()
        LaunchedEffect(generator?.id) {
            if (generator != null && selectedGeneratorId == null) selectedGeneratorId = generator.id
        }
        var generatorMenuOpen by remember(aiSettings.paperGenerators, selectedGeneratorId) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!aiGenerating) aiGenerateOpen = false
            },
            title = { Text("生成题目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (enabledGenerators.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = generator?.name.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("生题器") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            Box {
                                TextButton(onClick = { generatorMenuOpen = true }) { Text("选择") }
                                DropdownMenu(expanded = generatorMenuOpen, onDismissRequest = { generatorMenuOpen = false }) {
                                    enabledGenerators.forEach { g ->
                                        DropdownMenuItem(
                                            text = { Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            onClick = {
                                                selectedGeneratorId = g.id
                                                aiPrompt = g.promptPreset
                                                aiCount = g.count.toString()
                                                generatorMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text("请先在「AI 设置」里添加生题器配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        label = { Text("题目要求/范围") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 160.dp),
                        minLines = 2,
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = aiCount,
                        onValueChange = { aiCount = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("题目数量") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "提示：该按钮会直接调用你配置的 LLM。如果返回了题图链接/图片，将自动落盘并生成试卷页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (aiGenerating) {
                        Text(
                            "生成中… 已用时 ${aiElapsedSeconds}s / 120s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !aiGenerating,
                    onClick = {
                        val count = aiCount.toIntOrNull()?.coerceIn(1, 30) ?: 5
                        scope.launch {
                            runCatching {
                                aiGenerating = true
                                val generatorFinal =
                                    enabledGenerators.firstOrNull { it.id == selectedGeneratorId }
                                        ?: enabledGenerators.firstOrNull { it.id == aiSettings.defaultPaperGeneratorId }
                                        ?: enabledGenerators.firstOrNull()
                                if (generatorFinal == null) {
                                    aiGenerating = false
                                    showAiDebug(
                                        title = "生成题目：配置缺失",
                                        details = "未找到可用生题器（paperGenerators 为空或全部 disabled）",
                                    )
                                    return@launch
                                }
                                val agent =
                                    aiSettings.agents.firstOrNull { it.id == generatorFinal.agentId && it.enabled }
                                        ?: aiSettings.agents.firstOrNull { it.id == generatorFinal.agentId }
                                if (agent == null || !agent.enabled) {
                                    aiGenerating = false
                                    showAiDebug(
                                        title = "生成题目：配置缺失",
                                        details = "生题器=${generatorFinal.id}/${generatorFinal.name} 引用的 Agent 未配置或已关闭：agentId=${generatorFinal.agentId}",
                                    )
                                    return@launch
                                }
                                val provider =
                                    aiSettings.providers.firstOrNull { it.id == agent.config.providerId }
                                if (provider == null || !provider.enabled || provider.apiKey.isBlank()) {
                                    aiGenerating = false
                                    showAiDebug(
                                        title = "生成题目：配置缺失",
                                        details =
                                            buildString {
                                                appendLine("生题器=${generatorFinal.id}/${generatorFinal.name}")
                                                appendLine("agentId=${generatorFinal.agentId} model=${agent.config.model}")
                                                appendLine("providerId=${agent.config.providerId} 未配置/未启用/API Key 为空")
                                            },
                                    )
                                    return@launch
                                }
                                val generatedResult =
                                    withTimeout(240_000) {
                                        aiAgents.generatePaperWithRaw(
                                            provider = provider,
                                            agent = agent.config,
                                            userPrompt = aiPrompt.ifBlank { generatorFinal.promptPreset },
                                            count = count,
                                        )
                                    }
                                val pages =
                                    materializeGeneratedQuestionsToPages(
                                        context = context,
                                        docId = doc.id,
                                        questions = generatedResult.questions,
                                        sourceRawMessage = generatedResult.rawResponse,
                                    )
                                val basePages = worksheet.pages
                                val baseIsEmpty =
                                    basePages.size == 1 &&
                                        basePages.firstOrNull()?.let { p ->
                                            p.strokes.isEmpty() &&
                                                p.backgroundImageUri.isNullOrBlank() &&
                                                p.questionText.isNullOrBlank() &&
                                                p.title.isNullOrBlank()
                                        } == true
                                val mergedPages = if (baseIsEmpty) pages else basePages + pages

                                val titleTime =
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                        .withZone(ZoneId.systemDefault())
                                        .format(Instant.ofEpochMilli(System.currentTimeMillis()))
                                val titleWithMeta = "${generatorFinal.name} · $titleTime"
                                val updatedTitle = if (doc.title == "AI 试卷" || doc.title.isBlank()) titleWithMeta else doc.title

                                val updated =
                                    doc.copy(
                                        title = updatedTitle,
                                        worksheet = worksheet.copy(pages = mergedPages),
                                    )
                                onDocChange(updated, true)
                                pageIndex = if (baseIsEmpty) 0 else basePages.size
                                aiGenerating = false
                                aiGenerateOpen = false
                            }.onFailure {
                                aiGenerating = false
                                showAiDebug(
                                    title = "生成题目失败",
                                    details = formatAiThrowableForDebug(it),
                                )
                            }
                        }
                    },
                ) { Text("生成") }
            },
            dismissButton = {
                TextButton(
                    enabled = !aiGenerating,
                    onClick = { aiGenerateOpen = false },
                ) { Text("取消") }
            },
        )
    }

    if (aiExplainOpen) {
        val enabledExplainers = aiSettings.explainers.filter { it.enabled }
        val explainer =
            enabledExplainers.firstOrNull { it.id == selectedExplainerId }
                ?: enabledExplainers.firstOrNull { it.id == aiSettings.defaultExplainerId }
                ?: enabledExplainers.firstOrNull()
        LaunchedEffect(explainer?.id) {
            if (explainer != null && selectedExplainerId == null) selectedExplainerId = explainer.id
        }
        var explainerMenuOpen by remember(aiSettings.explainers, selectedExplainerId) { mutableStateOf(false) }
        var explainDebugEnabled by remember(doc.id) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                aiExplainJob?.cancel()
                aiExplainJob = null
                aiExplaining = false
                aiExplainOpen = false
            },
            title = { Text("AI 解答") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (enabledExplainers.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = explainer?.name.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("答题器") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            Box {
                                TextButton(onClick = { explainerMenuOpen = true }) { Text("选择") }
                                DropdownMenu(expanded = explainerMenuOpen, onDismissRequest = { explainerMenuOpen = false }) {
                                    enabledExplainers.forEach { e ->
                                        DropdownMenuItem(
                                            text = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            onClick = {
                                                selectedExplainerId = e.id
                                                aiExplainExtra = e.style
                                                explainerMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text("请先在「AI 设置」里添加讲解器配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = aiExplainExtra,
                        onValueChange = { aiExplainExtra = it },
                        label = { Text("讲解风格/额外要求") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        minLines = 6,
                        maxLines = 6,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("调试模式", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.Switch(
                            checked = explainDebugEnabled,
                            onCheckedChange = { checked -> explainDebugEnabled = checked },
                        )
                    }
                    aiDebugPreviewBitmap?.let { bmp ->
                        // Show the exact PNG we send to the provider, to debug "blank image" issues.
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "AI exported preview",
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 220.dp),
                        )
                    }
                    TextButton(
                        enabled = !aiExplaining,
                        onClick = {
                            scope.launch {
                                aiExplaining = true
                                runCatching {
                                    val explainerFinal =
                                        enabledExplainers.firstOrNull { it.id == selectedExplainerId }
                                            ?: enabledExplainers.firstOrNull { it.id == aiSettings.defaultExplainerId }
                                            ?: enabledExplainers.firstOrNull()
                                    if (explainerFinal == null) {
                                        aiExplaining = false
                                        showAiDebug(
                                            title = "答题器测试：配置缺失",
                                            details = "未找到可用讲解器（explainers 为空或全部 disabled）",
                                        )
                                        return@launch
                                    }
                                    val agent =
                                        aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId && it.enabled }
                                            ?: aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId }
                                    if (agent == null || !agent.enabled) {
                                        aiExplaining = false
                                        showAiDebug(
                                            title = "答题器测试：配置缺失",
                                            details = "讲解器=${explainerFinal.id}/${explainerFinal.name} 引用的 Agent 未配置或已关闭：agentId=${explainerFinal.agentId}",
                                        )
                                        return@launch
                                    }
                                    val provider =
                                        aiSettings.providers.firstOrNull { it.id == agent.config.providerId }
                                    if (provider == null || !provider.enabled || provider.apiKey.isBlank()) {
                                        aiExplaining = false
                                        showAiDebug(
                                            title = "答题器测试：配置缺失",
                                            details =
                                                buildString {
                                                    appendLine("讲解器=${explainerFinal.id}/${explainerFinal.name}")
                                                    appendLine("agentId=${explainerFinal.agentId} model=${agent.config.model}")
                                                    appendLine("providerId=${agent.config.providerId} 未配置/未启用/API Key 为空")
                                                },
                                        )
                                        return@launch
                                    }
                                    val style = (aiExplainExtra.takeIf { it.isNotBlank() } ?: explainerFinal.style).trim()
                                    val res =
                                        withTimeout(20_000) {
                                            aiAgents.debugHelloWithRaw(
                                                provider = provider,
                                                agent = agent.config,
                                                systemPromptExtra = style,
                                            )
                                        }
                                    showAiDebug(
                                        title = "答题器测试（hello）",
                                        details = res.debugHttp,
                                    )
                                }.onFailure {
                                    showAiDebug(
                                        title = "答题器测试失败",
                                        details = formatAiThrowableForDebug(it),
                                    )
                                }
                                aiExplaining = false
                            }
                        },
                    ) { Text("测试连接（hello）") }
                    if (aiExplaining) {
                        Text(
                            "解答中… 已用时 ${aiElapsedSeconds}s / 240s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(
                        enabled = !aiExplaining,
                        onClick = {
                            scope.launch {
                                aiExplaining = true
                                runCatching {
                                    val explainerFinal =
                                        enabledExplainers.firstOrNull { it.id == selectedExplainerId }
                                            ?: enabledExplainers.firstOrNull { it.id == aiSettings.defaultExplainerId }
                                            ?: enabledExplainers.firstOrNull()
                                    if (explainerFinal == null) {
                                        showAiDebug(
                                            title = "测试图片：配置缺失",
                                            details = "未找到可用讲解器（explainers 为空或全部 disabled）",
                                        )
                                        return@launch
                                    }
                                    val agent =
                                        aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId && it.enabled }
                                            ?: aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId }
                                    if (agent == null || !agent.enabled) {
                                        showAiDebug(
                                            title = "测试图片：配置缺失",
                                            details = "讲解器=${explainerFinal.id}/${explainerFinal.name} 引用的 Agent 未配置或已关闭：agentId=${explainerFinal.agentId}",
                                        )
                                        return@launch
                                    }
                                    val provider =
                                        aiSettings.providers.firstOrNull { it.id == agent.config.providerId }
                                    if (provider == null || !provider.enabled || provider.apiKey.isBlank()) {
                                        showAiDebug(
                                            title = "测试图片：配置缺失",
                                            details =
                                                buildString {
                                                    appendLine("讲解器=${explainerFinal.id}/${explainerFinal.name}")
                                                    appendLine("agentId=${explainerFinal.agentId} model=${agent.config.model}")
                                                    appendLine("providerId=${agent.config.providerId} 未配置/未启用/API Key 为空")
                                                },
                                        )
                                        return@launch
                                    }

                                    val idx = pageIndex
                                    val bytes = ExportManager.renderWorksheetPagePngBytes(context, doc, idx, scale = 0.5f)
                                    if (bytes == null) {
                                        showAiDebug(
                                            title = "测试图片：导出失败",
                                            details = "无法渲染当前页为 PNG：pageIndex=$idx docId=${doc.id}",
                                        )
                                        return@launch
                                    }
                                    setAiDebugPreview(bytes)
                                    val exportInfo =
                                        runCatching {
                                            val opts =
                                                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                            buildString {
                                                appendLine("export_source=render_full_canvas")
                                                appendLine("export_png_bytes=${bytes.size}")
                                                appendLine("export_png_width=${opts.outWidth}")
                                                appendLine("export_png_height=${opts.outHeight}")
                                            }.trim()
                                        }.getOrNull()

                                    val style = (aiExplainExtra.takeIf { it.isNotBlank() } ?: explainerFinal.style).trim()
                                    val res =
                                        withTimeout(40_000) {
                                            aiAgents.debugDescribeImageWithRaw(
                                                provider = provider,
                                                agent = agent.config,
                                                pagePngBytes = bytes,
                                                systemPromptExtra = style,
                                                onRequestPrepared = { raw, preview ->
                                                    showAiDebug(
                                                        title = "测试图片：已发送（可复制）",
                                                        details =
                                                            buildString {
                                                                exportInfo?.takeIf { it.isNotBlank() }?.let {
                                                                    appendLine(it)
                                                                    appendLine()
                                                                }
                                                                appendLine("requestBodyPreview=")
                                                                appendLine(preview)
                                                            },
                                                        requestJsonRaw = raw ?: preview,
                                                    )
                                                },
                                            )
                                        }
                                    showAiDebug(
                                        title = "测试图片（解读结果）",
                                        details =
                                            buildString {
                                                exportInfo?.takeIf { it.isNotBlank() }?.let {
                                                    appendLine(it)
                                                    appendLine()
                                                }
                                                appendLine("model_output=")
                                                appendLine(res.text.trim())
                                                appendLine()
                                                appendLine("-----")
                                                appendLine()
                                                appendLine(res.debugHttp)
                                            },
                                    )
                                }.onFailure {
                                    showAiDebug(
                                        title = "测试图片失败",
                                        details = formatAiThrowableForDebug(it),
                                    )
                                }
                                aiExplaining = false
                            }
                        },
                    ) { Text("测试图片（解读）") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !aiExplaining,
		                    onClick = {
		                        aiExplainJob?.cancel()
		                        aiExplainJob =
		                            scope.launch {
		                                aiExplaining = true
		                                var exportInfo: String? = null
		                                runCatching {
	                                val explainerFinal =
	                                    enabledExplainers.firstOrNull { it.id == selectedExplainerId }
	                                        ?: enabledExplainers.firstOrNull { it.id == aiSettings.defaultExplainerId }
                                        ?: enabledExplainers.firstOrNull()
                                if (explainerFinal == null) {
                                    aiExplaining = false
                                    showAiDebug(
                                        title = "生成讲解页：配置缺失",
                                        details = "未找到可用讲解器（explainers 为空或全部 disabled）",
                                    )
                                    return@launch
                                }
                                val agent =
                                    aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId && it.enabled }
                                        ?: aiSettings.agents.firstOrNull { it.id == explainerFinal.agentId }
                                if (agent == null || !agent.enabled) {
                                    aiExplaining = false
                                    showAiDebug(
                                        title = "生成讲解页：配置缺失",
                                        details = "讲解器=${explainerFinal.id}/${explainerFinal.name} 引用的 Agent 未配置或已关闭：agentId=${explainerFinal.agentId}",
                                    )
                                    return@launch
                                }
                                val provider =
                                    aiSettings.providers.firstOrNull { it.id == agent.config.providerId }
                                if (provider == null || !provider.enabled || provider.apiKey.isBlank()) {
                                    aiExplaining = false
                                    showAiDebug(
                                        title = "生成讲解页：配置缺失",
                                        details =
                                            buildString {
                                                appendLine("讲解器=${explainerFinal.id}/${explainerFinal.name}")
                                                appendLine("agentId=${explainerFinal.agentId} model=${agent.config.model}")
                                                appendLine("providerId=${agent.config.providerId} 未配置/未启用/API Key 为空")
                                            },
                                    )
                                    return@launch
                                }

                                val questionPage = currentPage
                                if (questionPage == null || questionPage.type != WorksheetPageType.QUESTION) {
                                    aiExplaining = false
                                    showAiDebug(
                                        title = "生成讲解页：页类型错误",
                                        details = "请在题目页（type=QUESTION）上使用「AI 解答」。当前页 type=${currentPage?.type} id=${currentPage?.id}",
                                    )
                                    return@launch
                                }

	                                val idx = pageIndex
	                                val bytes =
	                                    runCatching {
	                                        ExportManager.renderWorksheetPagePngBytes(context, doc, idx, scale = 0.5f)
	                                    }.getOrNull()
	                                if (bytes == null) {
                                    aiExplaining = false
                                    showAiDebug(
                                        title = "生成讲解页：导出失败",
	                                        details = "无法渲染当前页为 PNG：pageIndex=$idx docId=${doc.id}",
	                                    )
		                                    return@launch
		                                }
		                                // Store for UI preview/debug before sending network request.
		                                setAiDebugPreview(bytes)
	                                exportInfo =
	                                    runCatching {
	                                        val opts =
	                                            android.graphics.BitmapFactory.Options().apply {
	                                                inJustDecodeBounds = true
	                                            }
	                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
	                                        buildString {
	                                            appendLine("export_source=render_full_canvas")
	                                            appendLine("page_strokes=${questionPage.strokes.size}")
	                                            appendLine("export_png_bytes=${bytes.size}")
	                                            appendLine("export_png_width=${opts.outWidth}")
	                                            appendLine("export_png_height=${opts.outHeight}")
	                                        }.trim()
	                                    }.getOrNull()
		                                val style = (aiExplainExtra.takeIf { it.isNotBlank() } ?: explainerFinal.style).trim()
			                                val rawResult =
			                                    withTimeout(240_000) {
			                                        aiAgents.explainToAnswerPagesRawOnly(
			                                            provider = provider,
			                                            agent = agent.config,
			                                            pagePngBytes = bytes,
			                                            extraInstruction = style,
			                                            count = 1,
			                                            onRequestPrepared = { raw, preview ->
			                                                aiExplainLastRequestJsonRaw = raw
			                                                aiExplainLastRequestPreview = preview
			                                                if (!explainDebugEnabled) return@explainToAnswerPagesRawOnly
			                                                // Optional debug: show immediately after request is prepared.
			                                                aiDebugPendingCommit = null
			                                                aiDebugCloseCommits = false
			                                                showAiDebug(
			                                                    title = "已发送 AI 请求（可复制）",
			                                                    details =
			                                                        buildString {
			                                                            exportInfo?.takeIf { it.isNotBlank() }?.let {
			                                                                appendLine(it)
			                                                                appendLine()
			                                                            }
			                                                            appendLine("requestBodyPreview=")
			                                                            appendLine(preview)
			                                                        },
			                                                    requestJsonRaw = raw ?: preview,
			                                                )
			                                            },
			                                        )
			                                    }
			                                // Two-step flow:
			                                // 1) Show raw response in debug panel.
			                                // 2) Write to answer page ONLY after closing the panel.
			                                val questionPageId = questionPage.id
			                                val responseBody = rawResult.rawResponse
			                                val extractedText = rawResult.extractedText

			                                suspend fun commitAnswerPageFromExtractedText() {
			                                    val docNow = latestDoc.value
			                                    val worksheetNow = docNow.worksheet ?: WorksheetNote()
			                                    val basePages = worksheetNow.pages.toMutableList()

			                                    val qIndexNow = basePages.indexOfFirst { it.id == questionPageId }
			                                    if (qIndexNow < 0) {
			                                        showAiDebug(
			                                            title = "写入讲解页失败",
			                                            details = "找不到题目页：questionPageId=$questionPageId docId=${docNow.id}",
			                                        )
			                                        return
			                                    }

			                                    val parsedPages = aiAgents.parsePagesStrictJson(extractedText, expectedCount = 1)
			                                    val answerPages =
			                                        materializeGeneratedAnswerPagesToPages(
			                                            context = context,
			                                            docId = docNow.id,
			                                            answerForPageId = questionPageId,
			                                            pages = parsedPages,
			                                            sourceRawMessage = responseBody,
			                                        )
			                                    val fresh = answerPages.firstOrNull()

			                                    // Ensure 1:1 mapping: keep at most one ANSWER page per question.
			                                    val existingIdx = basePages.indexOfFirst { it.type == WorksheetPageType.ANSWER && it.answerForPageId == questionPageId }
			                                    val targetIndex =
			                                        if (existingIdx >= 0 && fresh != null) {
			                                            val existing = basePages[existingIdx]
			                                            basePages[existingIdx] =
			                                                existing.copy(
			                                                    title = fresh.title,
			                                                    backgroundImageUri = fresh.backgroundImageUri,
			                                                    answerText = fresh.answerText,
			                                                    sourceRawMessage = fresh.sourceRawMessage,
			                                                )
			                                            // Remove any duplicates if present.
			                                            for (i in basePages.lastIndex downTo 0) {
			                                                if (i != existingIdx && basePages[i].type == WorksheetPageType.ANSWER && basePages[i].answerForPageId == questionPageId) {
			                                                    basePages.removeAt(i)
			                                                }
			                                            }
			                                            existingIdx
			                                        } else {
			                                            val insertAt = (qIndexNow + 1).coerceAtMost(basePages.size)
			                                            basePages.addAll(insertAt, answerPages)
			                                            insertAt
			                                        }

			                                    onDocChange(docNow.copy(worksheet = worksheetNow.copy(pages = basePages)), true)
			                                    pageIndex = targetIndex
			                                }

			                                if (explainDebugEnabled) {
			                                    aiDebugCloseCommits = true
			                                    aiDebugPendingCommit = {
			                                        scope.launch {
			                                            runCatching { commitAnswerPageFromExtractedText() }.onFailure {
			                                                showAiDebug(
			                                                    title = "写入讲解页失败",
			                                                    details = formatAiThrowableForDebug(it),
			                                                )
			                                            }
			                                        }
			                                    }

			                                    showAiDebug(
			                                        title = "讲解原始响应（关闭后写入）",
			                                        details =
			                                            buildString {
			                                                exportInfo?.takeIf { it.isNotBlank() }?.let {
			                                                    appendLine(it)
			                                                    appendLine()
			                                                }
			                                                aiExplainLastRequestPreview?.takeIf { it.isNotBlank() }?.let {
			                                                    appendLine("requestBodyPreview=")
			                                                    appendLine(it)
			                                                    appendLine()
			                                                }
			                                                appendLine("-----")
			                                                appendLine()
			                                                appendLine("raw_response_body=")
			                                                appendLine(responseBody)
			                                            },
			                                        requestJsonRaw = aiExplainLastRequestJsonRaw ?: aiExplainLastRequestPreview,
			                                    )
			                                } else {
			                                    // Default: no two-stage debug; parse+write immediately.
			                                    runCatching { commitAnswerPageFromExtractedText() }.onFailure {
			                                        showAiDebug(
			                                            title = "写入讲解页失败",
			                                            details = formatAiThrowableForDebug(it),
			                                        )
			                                    }
			                                }

		                                aiExplainOpen = false
			                            }.onFailure {
			                                aiDebugPendingCommit = null
			                                aiDebugCloseCommits = false
		                                showAiDebug(
		                                    title = "生成讲解页失败",
		                                    details =
		                                        buildString {
		                                            exportInfo?.takeIf { it.isNotBlank() }?.let {
		                                                appendLine(it)
		                                                appendLine()
		                                            }
		                                            appendLine(formatAiThrowableForDebug(it))
		                                        },
		                                )
		                            }
		                            aiExplaining = false
		                            aiExplainJob = null
		                        }
		                    },
                ) { Text("生成讲解页") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        aiExplainJob?.cancel()
                        aiExplainJob = null
                        aiExplaining = false
                        aiExplainOpen = false
                    },
                ) { Text(if (aiExplaining) "取消并停止" else "取消") }
            },
        )
    }

    if (aiDebugOpen) {
        val scroll = rememberScrollState()
        val requestJsonToCopy =
            remember(aiDebugDetailsFull, aiDebugRequestJsonRaw) {
                aiDebugRequestJsonRaw?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("<omitted ") }
                    ?: run {
                        val marker = "requestBodyRaw="
                        val start = aiDebugDetailsFull.indexOf(marker)
                        if (start < 0) return@remember null
                        val after = start + marker.length
                        val end = aiDebugDetailsFull.indexOf("\n\nrequestBodyPreview=", startIndex = after).takeIf { it >= 0 }
                        val raw = (if (end != null) aiDebugDetailsFull.substring(after, end) else aiDebugDetailsFull.substring(after)).trim()
                        raw.takeIf { it.isNotBlank() && !it.startsWith("<omitted ") }
                    }
            }
        val pngBytesToCopy = aiDebugPreviewPngBytes
        fun closeDebug(committing: Boolean) {
            aiDebugOpen = false
            if (committing) {
                val commit = aiDebugPendingCommit
                aiDebugPendingCommit = null
                aiDebugCloseCommits = false
                runCatching { commit?.invoke() }
            } else if (aiDebugCloseCommits) {
                // User dismissed without committing: treat as "不写入".
                aiDebugPendingCommit = null
                aiDebugCloseCommits = false
            }
        }

        AlertDialog(
            onDismissRequest = { closeDebug(committing = false) },
            title = { Text(aiDebugTitle.ifBlank { "AI Debug" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "用于排查问题：可复制并发给我或直接对照 Provider 返回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    aiDebugPreviewBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "AI exported preview",
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 220.dp),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(scroll),
                    ) {
                        Text(aiDebugDetails.ifBlank { "（无详细信息）" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = requestJsonToCopy != null,
                        onClick = {
                            clipboard.setText(AnnotatedString(requestJsonToCopy.orEmpty()))
                            scope.launch { snackbar.showSnackbar("已复制请求 JSON") }
                        },
                    ) { Text("复制请求JSON") }
                    TextButton(
                        enabled = pngBytesToCopy != null && pngBytesToCopy.isNotEmpty(),
                        onClick = {
                            val b64 = Base64.encodeToString(pngBytesToCopy, Base64.NO_WRAP)
                            clipboard.setText(AnnotatedString(b64))
                            scope.launch { snackbar.showSnackbar("已复制 PNG Base64") }
                        },
                    ) { Text("复制PNG Base64") }
                    TextButton(
                        enabled = pngBytesToCopy != null && pngBytesToCopy.isNotEmpty(),
                        onClick = {
                            val b64 = Base64.encodeToString(pngBytesToCopy, Base64.NO_WRAP)
                            clipboard.setText(AnnotatedString("data:image/png;base64,$b64"))
                            scope.launch { snackbar.showSnackbar("已复制 PNG dataURL") }
                        },
                    ) { Text("复制PNG dataURL") }
                    TextButton(
                        enabled = aiDebugDetailsFull.isNotBlank(),
                        onClick = {
                            clipboard.setText(AnnotatedString(aiDebugDetailsFull))
                            scope.launch { snackbar.showSnackbar("已复制全部") }
                        },
                    ) { Text("复制全部") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (aiDebugCloseCommits) {
                        TextButton(
                            onClick = {
                                aiDebugPendingCommit = null
                                aiDebugCloseCommits = false
                                closeDebug(committing = false)
                            },
                        ) { Text("不写入") }
                    }
                    TextButton(onClick = { closeDebug(committing = aiDebugCloseCommits) }) {
                        Text(if (aiDebugCloseCommits) "关闭并写入" else "关闭")
                    }
                }
            },
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
    pageScrollY: MutableMap<String, Int>,
    isFullScreen: Boolean,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    onPageChange: (WorksheetPage, committed: Boolean) -> Unit,
) {
    key(page.id) {
        val clipboard = LocalClipboardManager.current
        val context = androidx.compose.ui.platform.LocalContext.current
        val initialScrollY = pageScrollY[page.id] ?: 0
        val scrollState = rememberScrollState(initial = initialScrollY)
        DisposableEffect(Unit) {
            onDispose { pageScrollY[page.id] = scrollState.value }
        }

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
        val pageText =
            when (page.type) {
                WorksheetPageType.QUESTION -> page.questionText.orEmpty()
                WorksheetPageType.ANSWER -> page.answerText.orEmpty()
            }
        val rawMessage = page.sourceRawMessage.orEmpty()
        var rawQuestionDialogOpen by remember(page.id) { mutableStateOf(false) }
        val isAnswerPage = page.type == WorksheetPageType.ANSWER

        if (rawQuestionDialogOpen) {
            val rawScroll = rememberScrollState()
            val textToShow =
                when {
                    rawMessage.isNotBlank() -> rawMessage
                    pageText.isNotBlank() -> pageText
                    else -> "（无原始消息：此页可能不是 AI 生成，或生成时未保存原始响应）"
                }
            AlertDialog(
                onDismissRequest = { rawQuestionDialogOpen = false },
                title = {
                    Text(
                        when (page.type) {
                            WorksheetPageType.QUESTION -> "题目：LLM 原始消息"
                            WorksheetPageType.ANSWER -> "讲解：LLM 原始消息"
                        },
                    )
                },
                text = {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rawScroll),
                    ) {
                        Text(textToShow, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = textToShow.isNotBlank(),
                        onClick = {
                            clipboard.setText(AnnotatedString(textToShow))
                            rawQuestionDialogOpen = false
                        },
                    ) { Text("复制") }
                },
                dismissButton = {
                    TextButton(onClick = { rawQuestionDialogOpen = false }) { Text("关闭") }
                },
            )
        }

        if (isAnswerPage) {
            var renderMath by remember(page.id) { mutableStateOf(true) }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = RectangleShape,
                tonalElevation = 0.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            IconButton(onClick = { rawQuestionDialogOpen = true }) {
                                Icon(Icons.Filled.Description, contentDescription = "查看原始文本")
                            }
                            IconButton(
                                enabled = pageText.isNotBlank(),
                                onClick = { clipboard.setText(AnnotatedString(pageText)) },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制讲解")
                            }
                            Spacer(Modifier.weight(1f))
                            if (pageText.isNotBlank()) {
                                TextButton(onClick = { renderMath = !renderMath }) {
                                    Text(if (renderMath) "纯文本" else "渲染公式")
                                }
                            }
                        }

                        if (pageText.isNotBlank()) {
                            // Default to plain text to avoid WebView-related crashes on some devices.
                            if (renderMath && pageText.length <= 4000) {
                                MathText(
                                    text = pageText,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    pageText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text("（空）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    VerticalScrollbar(
                        state = scrollState,
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
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = if (isFullScreen) RectangleShape else MaterialTheme.shapes.extraLarge,
                tonalElevation = if (isFullScreen) 0.dp else 1.dp,
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().padding(if (isFullScreen) 0.dp else 12.dp),
                ) {
                    val density = LocalDensity.current
                    val inkingState = remember { mutableStateOf(false) }

                    val maxStrokeY =
                        remember(page.strokes) {
                            page.strokes
                                .asSequence()
                                .flatMap { it.points.asSequence() }
                                .map { it.y }
                                .maxOrNull()
                                ?: 0f
                        }

                    val viewportHpx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                    val viewportWpx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val viewportMaxHeight = maxHeight
                    val baseHpx = maxOf(viewportHpx * 2f, page.canvasHeightPx.toFloat())
                    val contentHpx = maxOf(baseHpx, maxStrokeY + viewportHpx)
                    val contentHdp = with(density) { contentHpx.toDp() }

                    val canvasWidthPx = viewportWpx.toInt().coerceAtLeast(1)
                    val canvasHeightPx = contentHpx.toInt().coerceAtLeast(1)

                    val isEmulator =
                        remember {
                            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                                Build.FINGERPRINT.contains("emu", ignoreCase = true) ||
                                Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
                                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                                Build.MODEL.contains("Android SDK built for", ignoreCase = true)
                        }
                    val dragScrollEnabled = !(simulatePressureWithSizeSlider && isEmulator)

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState, enabled = dragScrollEnabled && !inkingState.value),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(contentHdp)) {
                                PageTemplateBackground(template = template)

                                InkCanvas(
                                    strokes = page.strokes,
                                    modifier = Modifier.fillMaxSize(),
                                    isEraser = isEraser,
                                    tool = tool,
                                    colorArgb = colorArgb,
                                    size = size,
                                    simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                                    feel = inkFeel,
                                    onInkingChanged = { active -> inkingState.value = active },
                                    onStrokesChange = { updatedStrokes, committed ->
                                        onPageChange(
                                            page.copy(
                                                strokes = updatedStrokes,
                                                canvasWidthPx = canvasWidthPx,
                                                canvasHeightPx = canvasHeightPx,
                                            ),
                                            committed,
                                        )
                                    },
                                )

                                if (bitmap != null || pageText.isNotBlank()) {
                                    val maxQuestionHeight = viewportMaxHeight * 0.75f
                                    val maxW = this@BoxWithConstraints.maxWidth - 24.dp
                                    val baseQuestionWidth = minOf(maxW, 560.dp)
                                    val questionWidth = minOf(maxW, baseQuestionWidth * 1.25f)
                                    val shouldScrollQuestion =
                                        remember(bitmap, pageText) {
                                            bitmap != null ||
                                                pageText.length > 220 ||
                                                pageText.count { it == '\n' } > 6
                                        }
                                    val questionScrollState = rememberScrollState()
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                        shape = MaterialTheme.shapes.extraLarge,
                                        tonalElevation = 1.dp,
                                        shadowElevation = 8.dp,
                                        modifier =
                                            Modifier
                                                .padding(12.dp)
                                                .widthIn(max = questionWidth)
                                                .heightIn(max = maxQuestionHeight)
                                                .align(Alignment.TopStart),
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .then(
                                                        if (shouldScrollQuestion) {
                                                            Modifier.verticalScroll(questionScrollState)
                                                        } else {
                                                            Modifier
                                                        },
                                                    )
                                                    .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                IconButton(onClick = { rawQuestionDialogOpen = true }) {
                                                    Icon(Icons.Filled.Description, contentDescription = "查看原始文本")
                                                }
                                                IconButton(
                                                    enabled = pageText.isNotBlank(),
                                                    onClick = { clipboard.setText(AnnotatedString(pageText)) },
                                                ) {
                                                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制题目")
                                                }
                                            }

                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                                                )
                                            }

                                            if (pageText.isNotBlank()) {
                                                MathText(
                                                    text = pageText,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        VerticalScrollbar(
                            state = scrollState,
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
    }
}

private suspend fun materializeGeneratedQuestionsToPages(
    context: android.content.Context,
    docId: String,
    questions: List<GeneratedQuestion>,
    sourceRawMessage: String?,
): List<WorksheetPage> =
    withContext(Dispatchers.IO) {
        val cappedRaw = capRawMessageForDoc(sourceRawMessage)
        val maxChars = 12_000
        val outDir = File(context.filesDir, "worksheet-assets/$docId").apply { mkdirs() }
        val client = okhttp3.OkHttpClient()

        questions.mapIndexed { index, q ->
            val safeText = sanitizeAiTextForDisplay(q.text)
            val imageUri =
                when {
                    !q.imageBase64.isNullOrBlank() -> {
                        runCatching { android.util.Base64.decode(q.imageBase64, android.util.Base64.DEFAULT) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() && it.size <= 8_000_000 }
                            ?.let { bytes ->
                                val file = File(outDir, "ai_q_${index + 1}_${UUID.randomUUID()}.png")
                                file.writeBytes(bytes)
                                Uri.fromFile(file).toString()
                            }
                    }
                    !q.imageUrl.isNullOrBlank() -> {
                        val req = okhttp3.Request.Builder().url(q.imageUrl).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("image HTTP ${resp.code}")
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            if (bytes.isEmpty() || bytes.size > 8_000_000) return@use null
                            val file = File(outDir, "ai_q_${index + 1}_${UUID.randomUUID()}.png")
                            file.writeBytes(bytes)
                            Uri.fromFile(file).toString()
                        }
                    }
                    else -> null
                }

            WorksheetPage(
                id = UUID.randomUUID().toString(),
                type = WorksheetPageType.QUESTION,
                title = q.title?.takeIf { it.isNotBlank() } ?: "Question ${index + 1}",
                questionText =
                    safeText.let { t ->
                        if (t.length <= maxChars) t else t.take(maxChars) + "\n\n（题目过长，已截断；可在「LLM 原始消息」中查看完整内容）"
                    },
                backgroundImageUri = imageUri,
                sourceRawMessage = cappedRaw,
                strokes = emptyList(),
            )
        }.ifEmpty { listOf(WorksheetPage(id = UUID.randomUUID().toString(), type = WorksheetPageType.QUESTION)) }
    }

private suspend fun materializeGeneratedAnswerPagesToPages(
    context: android.content.Context,
    docId: String,
    answerForPageId: String,
    pages: List<GeneratedQuestion>,
    sourceRawMessage: String?,
): List<WorksheetPage> =
    withContext(Dispatchers.IO) {
        val cappedRaw = capRawMessageForDoc(sourceRawMessage)
        val maxChars = 12_000
        val outDir = File(context.filesDir, "worksheet-assets/$docId").apply { mkdirs() }
        val client = okhttp3.OkHttpClient()

        pages.mapIndexed { index, p ->
            val safeText = sanitizeAiTextForDisplay(p.text)
            val imageUri =
                when {
                    !p.imageBase64.isNullOrBlank() -> {
                        runCatching { android.util.Base64.decode(p.imageBase64, android.util.Base64.DEFAULT) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() && it.size <= 8_000_000 }
                            ?.let { bytes ->
                                val file = File(outDir, "ai_ans_${index + 1}_${UUID.randomUUID()}.png")
                                file.writeBytes(bytes)
                                Uri.fromFile(file).toString()
                            }
                    }
                    !p.imageUrl.isNullOrBlank() -> {
                        val req = okhttp3.Request.Builder().url(p.imageUrl).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("image HTTP ${resp.code}")
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            if (bytes.isEmpty() || bytes.size > 8_000_000) return@use null
                            val file = File(outDir, "ai_ans_${index + 1}_${UUID.randomUUID()}.png")
                            file.writeBytes(bytes)
                            Uri.fromFile(file).toString()
                        }
                    }
                    else -> null
                }

            WorksheetPage(
                id = UUID.randomUUID().toString(),
                type = WorksheetPageType.ANSWER,
                title = p.title?.takeIf { it.isNotBlank() } ?: "讲解 ${index + 1}",
                answerForPageId = answerForPageId,
                answerText =
                    safeText.let { t ->
                        if (t.length <= maxChars) t else t.take(maxChars) + "\n\n（讲解过长，已截断；可在「LLM 原始消息」中查看完整内容）"
                    },
                backgroundImageUri = imageUri,
                sourceRawMessage = cappedRaw,
                strokes = emptyList(),
            )
        }.ifEmpty { listOf(WorksheetPage(id = UUID.randomUUID().toString(), type = WorksheetPageType.ANSWER, answerForPageId = answerForPageId)) }
    }

private fun sanitizeAiTextForDisplay(text: String): String {
    if (text.isEmpty()) return text
    val sb = StringBuilder(text.length)
    for (ch in text) {
        val code = ch.code
        when {
            ch == '\u0000' -> Unit
            ch == '\uFFFE' || ch == '\uFFFF' -> Unit
            code in 0..0x1F && ch != '\n' && ch != '\r' && ch != '\t' -> Unit
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

private fun capRawMessageForDoc(raw: String?, maxChars: Int = 80_000): String? {
    val t = raw?.trim().orEmpty()
    if (t.isBlank()) return null
    if (t.length <= maxChars) return t
    return t.take(maxChars) + "\n\n（原始响应过长，已截断；请在 AI Debug 弹窗中使用「复制全部」保存完整原始响应。）"
}
