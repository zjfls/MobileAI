package com.mobileai.notes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.DocumentStore
import com.mobileai.notes.data.DocumentType
import com.mobileai.notes.data.NotebookPage
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.export.ExportManager
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
    onBack: () -> Unit,
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
    val sizeState = remember { mutableFloatStateOf(6f) }

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
                                text = { Text("切换模板：空白/横线/方格") },
                                onClick = {
                                    overflowExpanded = false
                                    val current = doc ?: return@DropdownMenuItem
                                    val blank = current.blank ?: return@DropdownMenuItem
                                    val next = when (blank.template) {
                                        PageTemplate.BLANK -> PageTemplate.RULED
                                        PageTemplate.RULED -> PageTemplate.GRID
                                        PageTemplate.GRID -> PageTemplate.BLANK
                                    }
                                    val updated = current.copy(blank = blank.copy(template = next))
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
        },
        bottomBar = {
            ToolBar(
                tool = toolKindState.value,
                isEraser = isEraserState.value,
                colorArgb = colorArgbState.longValue,
                size = sizeState.floatValue,
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
                onSizeChange = { sizeState.floatValue = it },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
        ) {
            when (doc?.type) {
                DocumentType.BLANK -> {
                BlankNotebookEditor(
                    doc = doc,
                    isEraser = isEraserState.value,
                    tool = toolKindState.value,
                    colorArgb = colorArgbState.longValue,
                    size = sizeState.floatValue,
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
                    size = sizeState.floatValue,
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
            null -> {
                Text("文档不存在或正在加载")
            }
            }
        }
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
