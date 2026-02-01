package com.mobileai.notes.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.DocumentStore
import com.mobileai.notes.data.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.TextButton
import com.mobileai.notes.ui.widgets.VerticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    store: DocumentStore,
    onOpenDocument: (docId: String, openGenerateOnStart: Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val documents by store.documents.collectAsState()
    val scope = rememberCoroutineScope()
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameInitialTitle by remember { mutableStateOf("") }

    val openPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                runCatching {
                    store.refreshIndex() // best effort
                }
                runCatching {
                    store.createFromPdf(uri = uri, title = "PDF 批注")
                }.onSuccess { onOpenDocument(it, false) }
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aura Note", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "AI 手写笔记 · 试卷 · 批注",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAiSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "AI 设置")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.large,
                        shadowElevation = 10.dp,
                    ) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("开始创作", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "用笔写 · 用 AI 出题/讲解 · 一键同步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val id = store.createWorksheet(title = "AI 试卷")
                                onOpenDocument(id, false)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("生成AI试卷")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val id = store.createBlank(title = "空白笔记")
                            onOpenDocument(id, false)
                        }
                    },
                ) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("空白笔记")
                }
                Button(
                    onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入 PDF")
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("最近", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            val gridState = rememberLazyGridState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (documents.isEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.extraLarge,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "还没有文档：试试右上角新建或导入",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    items(documents, key = { it.id }) { doc ->
                        ElevatedCard(
                            onClick = { onOpenDocument(doc.id, false) },
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Surface(
                                            modifier = Modifier.size(36.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            contentColor = MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.large,
                                        ) {
                                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                val icon =
                                                    when (doc.type) {
                                                        DocumentType.BLANK -> Icons.Filled.NoteAdd
                                                        DocumentType.PDF -> Icons.Filled.PictureAsPdf
                                                        DocumentType.WORKSHEET -> Icons.Filled.AutoAwesome
                                                    }
                                                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Text(
                                            when (doc.type) {
                                                DocumentType.BLANK -> "笔记"
                                                DocumentType.PDF -> "PDF"
                                                DocumentType.WORKSHEET -> "试卷"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                DocumentOverflowMenu(
                                    onRename = {
                                        renameTargetId = doc.id
                                        renameInitialTitle = doc.title
                                    },
                                    onDelete = { scope.launch(Dispatchers.IO) { store.deleteDocument(doc.id) } },
                                )
                            }
                            Text(
                                doc.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                when (doc.type) {
                                    DocumentType.BLANK -> "空白画布 · 多页 · 模板"
                                    DocumentType.PDF -> "阅读 · 批注 · 导出"
                                    DocumentType.WORKSHEET -> "拉题 · 作答 · AI 解答 · 同步"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

            }

            VerticalScrollbar(
                state = gridState,
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

    if (renameTargetId != null) {
        RenameDialog(
            initial = renameInitialTitle,
            onDismiss = { renameTargetId = null },
            onConfirm = { newTitle ->
                val id = renameTargetId ?: return@RenameDialog
                renameTargetId = null
                scope.launch(Dispatchers.IO) { store.renameDocument(id, newTitle) }
            },
        )
    }
}

}


@Composable
private fun DocumentOverflowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("重命名") },
            onClick = { expanded = false; onRename() },
        )
        DropdownMenuItem(
            text = { Text("删除") },
            onClick = { expanded = false; onDelete() },
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("标题") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim().ifEmpty { initial }) },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
