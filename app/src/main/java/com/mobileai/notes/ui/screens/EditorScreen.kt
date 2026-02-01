package com.mobileai.notes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.DocumentStore
import com.mobileai.notes.data.DocumentType
import com.mobileai.notes.data.NotebookPage
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.data.WorksheetNote
import com.mobileai.notes.data.WorksheetPage
import com.mobileai.notes.export.ExportManager
import com.mobileai.notes.ink.InkFeel
import com.mobileai.notes.oppo.OppoPenKit
import com.mobileai.notes.ui.widgets.ToolBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert

private sealed interface ExportRequest {
    data object Pdf : ExportRequest
    data class Png(val pageIndex: Int) : ExportRequest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    store: DocumentStore,
    docId: String,
    openGenerateOnStart: Boolean = false,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val docState = remember { mutableStateOf<DocumentEntity?>(null) }
    val doc = docState.value
    LaunchedEffect(docId) {
        docState.value = store.loadDocument(docId)
    }

    val undoStack = remember { ArrayDeque<DocumentEntity>() }
    val redoStack = remember { ArrayDeque<DocumentEntity>() }
    var pendingSaveJob by remember { mutableStateOf<Job?>(null) }

    val toolKindState = remember { mutableStateOf(ToolKind.PEN) }
    val isEraserState = remember { mutableStateOf(false) }
    val colorArgbState = remember { mutableLongStateOf(0xFF111111) }
    var penSize by remember { mutableFloatStateOf(6f) }
    var pencilSize by remember { mutableFloatStateOf(6f) }
    var highlighterSize by remember { mutableFloatStateOf(10f) }
    var eraserSize by remember { mutableFloatStateOf(14f) }
    var simulatePressureWithSizeSlider by remember { mutableStateOf(true) }
    var inkFeel by remember { mutableStateOf(InkFeel()) }
    var inkFeelDialogOpen by remember { mutableStateOf(false) }
    var toolBarExpanded by remember { mutableStateOf(true) }
    var worksheetFullScreen by remember(docId) { mutableStateOf(false) }

    var overflowExpanded by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var oppoStatusOpen by remember { mutableStateOf(false) }
    var oppoStatusText by remember { mutableStateOf<String?>(null) }

    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
    var pngPageDialogOpen by remember { mutableStateOf(false) }
    var pngPageValue by remember { mutableStateOf("1") }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onResult = { uri ->
            val req = pendingExport
            pendingExport = null
            val current = docState.value ?: return@rememberLauncherForActivityResult
            if (uri == null || req !is ExportRequest.Pdf) return@rememberLauncherForActivityResult
            scope.launch {
                ExportManager.exportDocumentPdf(context, current, uri)
            }
        },
    )

    val exportPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
        onResult = { uri ->
            val req = pendingExport
            pendingExport = null
            val current = docState.value ?: return@rememberLauncherForActivityResult
            val png = req as? ExportRequest.Png ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                ExportManager.exportBlankNotebookPng(
                    context = context,
                    doc = current,
                    pageIndex = png.pageIndex,
                    outUri = uri,
                )
            }
        },
    )

    Scaffold(
        topBar = {
            val hideBars = doc?.type == DocumentType.WORKSHEET && worksheetFullScreen
            if (!hideBars) {
                TopAppBar(
                    title = { Text(doc?.title ?: "打开中…") },
                    colors = TopAppBarDefaults.topAppBarColors(),
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    docState.value?.let { store.saveDocument(it) }
                                }
                                onBack()
                            },
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                overflowExpanded = false
                                renameValue = doc?.title.orEmpty()
                                renameDialogOpen = true
                            },
                        )

                        if (doc?.type == DocumentType.BLANK) {
                            DropdownMenuItem(
                                text = { Text("新增页面") },
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val blank = current.blank ?: return@DropdownMenuItem
                                    val updated = current.copy(blank = blank.copy(pages = blank.pages + NotebookPage()))
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除最后一页") },
                                enabled = (doc?.blank?.pages?.size ?: 0) > 1,
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val blank = current.blank ?: return@DropdownMenuItem
                                    if (blank.pages.size <= 1) return@DropdownMenuItem
                                    val updated = current.copy(blank = blank.copy(pages = blank.pages.dropLast(1)))
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("切换模板：空白/横线/方格/点阵/Cornell") },
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val blank = current.blank ?: return@DropdownMenuItem
                                    val next = when (blank.template) {
                                        PageTemplate.BLANK -> PageTemplate.RULED
                                        PageTemplate.RULED -> PageTemplate.GRID
                                        PageTemplate.GRID -> PageTemplate.DOTS
                                        PageTemplate.DOTS -> PageTemplate.CORNELL
                                        PageTemplate.CORNELL -> PageTemplate.BLANK
                                    }
                                    val updated = current.copy(blank = blank.copy(template = next))
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                        }
                        if (doc?.type == DocumentType.WORKSHEET) {
                            DropdownMenuItem(
                                text = { Text("新增页面") },
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val ws = current.worksheet ?: WorksheetNote()
                                    val updated = current.copy(
                                        worksheet = ws.copy(pages = ws.pages + WorksheetPage(id = java.util.UUID.randomUUID().toString())),
                                    )
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除最后一页") },
                                enabled = (doc?.worksheet?.pages?.size ?: 0) > 1,
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val ws = current.worksheet ?: WorksheetNote()
                                    if (ws.pages.size <= 1) return@DropdownMenuItem
                                    val updated = current.copy(worksheet = ws.copy(pages = ws.pages.dropLast(1)))
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("切换模板：空白/横线/方格/点阵/Cornell") },
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val ws = current.worksheet ?: WorksheetNote()
                                    val next = when (ws.template) {
                                        PageTemplate.BLANK -> PageTemplate.RULED
                                        PageTemplate.RULED -> PageTemplate.GRID
                                        PageTemplate.GRID -> PageTemplate.DOTS
                                        PageTemplate.DOTS -> PageTemplate.CORNELL
                                        PageTemplate.CORNELL -> PageTemplate.BLANK
                                    }
                                    val updated = current.copy(worksheet = ws.copy(template = next))
                                    docState.value = updated
                                    scope.launch { store.saveDocument(updated) }
                                },
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("OPPO Pencil 状态") },
                            onClick = {
                                overflowExpanded = false
                                oppoStatusText = OppoPenKit.tryGetStatusText()
                                oppoStatusOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("AI 设置") },
                            onClick = {
                                overflowExpanded = false
                                onOpenAiSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (simulatePressureWithSizeSlider) {
                                        "模拟压感（粗细滑条）: 开"
                                    } else {
                                        "模拟压感（粗细滑条）: 关"
                                    },
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                simulatePressureWithSizeSlider = !simulatePressureWithSizeSlider
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("书写手感…") },
                            onClick = {
                                overflowExpanded = false
                                inkFeelDialogOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 PDF") },
                            onClick = {
                                overflowExpanded = false
                                pendingExport = ExportRequest.Pdf
                                val name = (doc?.title ?: "export") + ".pdf"
                                exportPdfLauncher.launch(name)
                            },
                        )
                        if (doc?.type == DocumentType.BLANK) {
                            DropdownMenuItem(
                                text = { Text("导出 PNG（选择页）") },
                                onClick = {
                                    overflowExpanded = false
                                    pngPageValue = "1"
                                    pngPageDialogOpen = true
                                },
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("删除文档") },
                            onClick = {
                                overflowExpanded = false
                                val id = doc?.id ?: return@DropdownMenuItem
                                scope.launch {
                                    store.deleteDocument(id)
                                    onBack()
                                }
                            },
                        )
                    }
                    },
                )
            }
        },
        bottomBar = {
            val density = LocalDensity.current
            val dragThresholdPx = with(density) { 36.dp.toPx() }
            var dragAccumulated by remember { mutableFloatStateOf(0f) }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                            .pointerInput(toolBarExpanded) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        dragAccumulated += dragAmount
                                        if (!toolBarExpanded && dragAccumulated < -dragThresholdPx) {
                                            toolBarExpanded = true
                                            dragAccumulated = 0f
                                        } else if (toolBarExpanded && dragAccumulated > dragThresholdPx) {
                                            toolBarExpanded = false
                                            dragAccumulated = 0f
                                        }
                                        change.consume()
                                    },
                                    onDragEnd = { dragAccumulated = 0f },
                                    onDragCancel = { dragAccumulated = 0f },
                                )
                            }
                            .clickable {
                                toolBarExpanded = !toolBarExpanded
                                dragAccumulated = 0f
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }

                AnimatedVisibility(
                    visible = toolBarExpanded,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val currentSize =
                            if (isEraserState.value) {
                                eraserSize
                            } else {
                                when (toolKindState.value) {
                                    ToolKind.PEN -> penSize
                                    ToolKind.PENCIL -> pencilSize
                                    ToolKind.HIGHLIGHTER -> highlighterSize
                                }
                            }
                        ToolBar(
                            modifier = Modifier.widthIn(max = 920.dp),
                            tool = toolKindState.value,
                            isEraser = isEraserState.value,
                            colorArgb = colorArgbState.longValue,
                            size = currentSize,
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onUndo = {
                                val current = docState.value ?: return@ToolBar
                                if (undoStack.isEmpty()) return@ToolBar
                                val prev = undoStack.removeLast()
                                redoStack.addLast(current)
                                docState.value = prev
                                scope.launch { store.saveDocument(prev) }
                            },
                            onRedo = {
                                val current = docState.value ?: return@ToolBar
                                if (redoStack.isEmpty()) return@ToolBar
                                val next = redoStack.removeLast()
                                undoStack.addLast(current)
                                docState.value = next
                                scope.launch { store.saveDocument(next) }
                            },
                            onToolChange = { toolKindState.value = it; isEraserState.value = false },
                            onEraser = { isEraserState.value = true },
                            onColorChange = { colorArgbState.longValue = it },
                            onSizeChange = { v ->
                                if (isEraserState.value) {
                                    eraserSize = v
                                } else {
                                    when (toolKindState.value) {
                                        ToolKind.PEN -> penSize = v
                                        ToolKind.PENCIL -> pencilSize = v
                                        ToolKind.HIGHLIGHTER -> highlighterSize = v
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        LaunchedEffect(doc?.type) {
            if (doc?.type != DocumentType.WORKSHEET) worksheetFullScreen = false
        }
        LaunchedEffect(worksheetFullScreen) {
            if (worksheetFullScreen) toolBarExpanded = false
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(if (doc?.type == DocumentType.WORKSHEET && worksheetFullScreen) 0.dp else 12.dp),
        ) {
            when (doc?.type) {
                DocumentType.BLANK -> {
                BlankNotebookEditor(
                    doc = doc,
                    isEraser = isEraserState.value,
                    tool = toolKindState.value,
                    colorArgb = colorArgbState.longValue,
                    size =
                        if (isEraserState.value) {
                            eraserSize
                        } else {
                            when (toolKindState.value) {
                                ToolKind.PEN -> penSize
                                ToolKind.PENCIL -> pencilSize
                                ToolKind.HIGHLIGHTER -> highlighterSize
                            }
                        },
                    simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                    inkFeel = inkFeel,
                    onDocChange = { updated, committed ->
                        if (committed) {
                            docState.value?.let { current ->
                                undoStack.addLast(current)
                                while (undoStack.size > 30) undoStack.removeFirst()
                                redoStack.clear()
                            }
                            pendingSaveJob?.cancel()
                            pendingSaveJob = null
                            docState.value = updated
                            scope.launch { store.saveDocument(updated) }
                            return@BlankNotebookEditor
                        }
                        docState.value = updated
                        pendingSaveJob?.cancel()
                        pendingSaveJob = scope.launch {
                            delay(300)
                            store.saveDocument(updated)
                        }
                    },
                )
            }
            DocumentType.PDF -> {
                PdfNoteEditor(
                    doc = doc,
                    isEraser = isEraserState.value,
                    tool = toolKindState.value,
                    colorArgb = colorArgbState.longValue,
                    size =
                        if (isEraserState.value) {
                            eraserSize
                        } else {
                            when (toolKindState.value) {
                                ToolKind.PEN -> penSize
                                ToolKind.PENCIL -> pencilSize
                                ToolKind.HIGHLIGHTER -> highlighterSize
                            }
                        },
                    simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                    inkFeel = inkFeel,
                    onDocChange = { updated, committed ->
                        if (committed) {
                            docState.value?.let { current ->
                                undoStack.addLast(current)
                                while (undoStack.size > 30) undoStack.removeFirst()
                                redoStack.clear()
                            }
                            pendingSaveJob?.cancel()
                            pendingSaveJob = null
                            docState.value = updated
                            scope.launch { store.saveDocument(updated) }
                            return@PdfNoteEditor
                        }
                        docState.value = updated
                        pendingSaveJob?.cancel()
                        pendingSaveJob = scope.launch {
                            delay(300)
                            store.saveDocument(updated)
                        }
                    },
                )
            }
            DocumentType.WORKSHEET -> {
                WorksheetEditor(
                    doc = doc,
                    isFullScreen = worksheetFullScreen,
                    onFullScreenChange = { worksheetFullScreen = it },
                    isEraser = isEraserState.value,
                    tool = toolKindState.value,
                    colorArgb = colorArgbState.longValue,
                    size =
                        if (isEraserState.value) {
                            eraserSize
                        } else {
                            when (toolKindState.value) {
                                ToolKind.PEN -> penSize
                                ToolKind.PENCIL -> pencilSize
                                ToolKind.HIGHLIGHTER -> highlighterSize
                            }
                        },
                    simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                    inkFeel = inkFeel,
                    openGenerateOnStart = openGenerateOnStart,
                    onDocChange = { updated, committed ->
                        if (committed) {
                            docState.value?.let { current ->
                                undoStack.addLast(current)
                                while (undoStack.size > 30) undoStack.removeFirst()
                                redoStack.clear()
                            }
                            pendingSaveJob?.cancel()
                            pendingSaveJob = null
                            docState.value = updated
                            scope.launch { store.saveDocument(updated) }
                            return@WorksheetEditor
                        }
                        docState.value = updated
                        pendingSaveJob?.cancel()
                        pendingSaveJob = scope.launch {
                            delay(300)
                            store.saveDocument(updated)
                        }
                    },
                )
            }
            null -> {
                Text("文档不存在或正在加载")
            }
            }
        }
    }

    if (inkFeelDialogOpen) {
        AlertDialog(
            onDismissRequest = { inkFeelDialogOpen = false },
            title = { Text("书写手感") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "可调：粗细倍率 / 稳定度 / 压感曲线 / 掌托。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "粗细倍率：${"%.2f".format(inkFeel.sizeScale)}（默认 1 不影响历史笔迹）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = inkFeel.sizeScale,
                            onValueChange = { v -> inkFeel = inkFeel.copy(sizeScale = v.coerceIn(0.6f, 2.0f)) },
                            valueRange = 0.6f..2.0f,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "稳定度：${"%.2f".format(inkFeel.stabilization)}（0=跟手，1=更稳）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = inkFeel.stabilization,
                            onValueChange = { v -> inkFeel = inkFeel.copy(stabilization = v.coerceIn(0f, 1f)) },
                            valueRange = 0f..1f,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "压感曲线：${"%.2f".format(inkFeel.pressureGamma)}（<1 更显压，>1 更细）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = inkFeel.pressureGamma,
                            onValueChange = { v -> inkFeel = inkFeel.copy(pressureGamma = v.coerceIn(0.6f, 1.4f)) },
                            valueRange = 0.6f..1.4f,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("增强掌托", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = inkFeel.enhancedPalmRejection,
                            onCheckedChange = { checked -> inkFeel = inkFeel.copy(enhancedPalmRejection = checked) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inkFeelDialogOpen = false }) { Text("关闭") }
            },
        )
    }

    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = docState.value ?: return@TextButton
                        renameDialogOpen = false
                        val newTitle = renameValue.trim().ifEmpty { current.title }
                        val updated = current.copy(title = newTitle)
                        docState.value = updated
                        scope.launch { store.saveDocument(updated) }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (pngPageDialogOpen) {
        AlertDialog(
            onDismissRequest = { pngPageDialogOpen = false },
            title = { Text("导出 PNG") },
            text = {
                OutlinedTextField(
                    value = pngPageValue,
                    onValueChange = { pngPageValue = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text("页码（从 1 开始）") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = docState.value ?: return@TextButton
                        val blank = current.blank ?: return@TextButton
                        val page = (pngPageValue.toIntOrNull() ?: 1).coerceIn(1, blank.pages.size) - 1
                        pngPageDialogOpen = false
                        pendingExport = ExportRequest.Png(page)
                        exportPngLauncher.launch((current.title.ifEmpty { "page" }) + "-${page + 1}.png")
                    },
                ) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { pngPageDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (oppoStatusOpen) {
        AlertDialog(
            onDismissRequest = { oppoStatusOpen = false },
            title = { Text("OPPO Pencil 状态") },
            text = { Text(oppoStatusText ?: "ipe_sdk 未集成或未授权/设备不支持") },
            confirmButton = {
                TextButton(onClick = { oppoStatusOpen = false }) { Text("关闭") }
            },
        )
    }
}
