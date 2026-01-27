package com.mobileai.notes.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.DocumentStore
import com.mobileai.notes.data.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    store: DocumentStore,
    onOpenDocument: (docId: String) -> Unit,
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
                }.onSuccess(onOpenDocument)
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MobileAI Notes") })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val id = store.createBlank(title = "空白笔记")
                            onOpenDocument(id)
                        }
                    },
                ) {
                    Text("新建空白笔记")
                }
                Button(
                    onClick = {
                        openPdfLauncher.launch(arrayOf("application/pdf"))
                    },
                ) {
                    Text("导入 PDF")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("最近", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(documents, key = { it.id }) { doc ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDocument(doc.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(doc.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (doc.type) {
                                        DocumentType.BLANK -> "空白笔记"
                                        DocumentType.PDF -> "PDF 批注"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            DocumentOverflowMenu(
                                onRename = {
                                    renameTargetId = doc.id
                                    renameInitialTitle = doc.title
                                },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) { store.deleteDocument(doc.id) }
                                },
                            )
                        }
                    }
                }
            }
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
