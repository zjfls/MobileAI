package com.mobileai.notes.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class DocumentStore private constructor(
    private val appContext: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val documentsDir = File(appContext.filesDir, "documents")
    private val indexFile = File(documentsDir, "index.json")

    private val _documents = MutableStateFlow<List<DocumentSummary>>(emptyList())
    val documents: StateFlow<List<DocumentSummary>> = _documents.asStateFlow()

    init {
        documentsDir.mkdirs()
        scope.launch { refreshIndex() }
    }

    suspend fun refreshIndex() {
        _documents.value = readIndex().documents.sortedByDescending { it.updatedAtEpochMillis }
    }

    suspend fun createBlank(title: String = "空白笔记"): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val doc = DocumentEntity(
            id = id,
            type = DocumentType.BLANK,
            title = title,
            updatedAtEpochMillis = now,
            blank = BlankNotebook(),
        )
        writeDocument(doc)
        upsertSummary(
            DocumentSummary(
                id = id,
                type = DocumentType.BLANK,
                title = title,
                updatedAtEpochMillis = now,
            ),
        )
        id
    }

    suspend fun createFromPdf(
        uri: Uri,
        title: String = "PDF 批注",
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pageCount = readPdfPageCount(uri)
        val doc = DocumentEntity(
            id = id,
            type = DocumentType.PDF,
            title = title,
            updatedAtEpochMillis = now,
            pdf = PdfNote(
                pdfUri = uri.toString(),
                pageCount = pageCount,
                pages = emptyList(),
            ),
        )
        writeDocument(doc)
        upsertSummary(
            DocumentSummary(
                id = id,
                type = DocumentType.PDF,
                title = title,
                updatedAtEpochMillis = now,
            ),
        )
        id
    }

    suspend fun createWorksheet(title: String = "AI 试卷"): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val doc = DocumentEntity(
            id = id,
            type = DocumentType.WORKSHEET,
            title = title,
            updatedAtEpochMillis = now,
            worksheet = WorksheetNote(),
        )
        writeDocument(doc)
        upsertSummary(
            DocumentSummary(
                id = id,
                type = DocumentType.WORKSHEET,
                title = title,
                updatedAtEpochMillis = now,
            ),
        )
        id
    }

    suspend fun loadDocument(id: String): DocumentEntity? = withContext(Dispatchers.IO) {
        val file = File(documentsDir, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(DocumentEntity.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun saveDocument(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = doc.copy(updatedAtEpochMillis = now)
        writeDocument(updated)
        upsertSummary(
            DocumentSummary(
                id = updated.id,
                type = updated.type,
                title = updated.title,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        File(documentsDir, "$id.json").delete()
        val index = readIndex()
        val updatedIndex = index.copy(documents = index.documents.filterNot { it.id == id })
        writeIndex(updatedIndex)
        refreshIndex()
    }

    suspend fun renameDocument(id: String, title: String) = withContext(Dispatchers.IO) {
        val doc = loadDocument(id) ?: return@withContext
        saveDocument(doc.copy(title = title))
    }

    private suspend fun upsertSummary(summary: DocumentSummary) {
        val index = readIndex()
        val filtered = index.documents.filterNot { it.id == summary.id }
        val updatedIndex = index.copy(documents = listOf(summary) + filtered)
        writeIndex(updatedIndex)
        refreshIndex()
    }

    private fun writeDocument(doc: DocumentEntity) {
        File(documentsDir, "${doc.id}.json").writeText(json.encodeToString(doc))
    }

    private fun readIndex(): DocumentIndex {
        if (!indexFile.exists()) return DocumentIndex()
        return runCatching { json.decodeFromString(DocumentIndex.serializer(), indexFile.readText()) }
            .getOrElse { DocumentIndex() }
    }

    private fun writeIndex(index: DocumentIndex) {
        indexFile.writeText(json.encodeToString(index))
    }

    private fun readPdfPageCount(uri: Uri): Int {
        val documentFile = DocumentFile.fromSingleUri(appContext, uri) ?: return 0
        val pfd = appContext.contentResolver.openFileDescriptor(documentFile.uri, "r") ?: return 0
        pfd.use {
            PdfRenderer(it).use { renderer ->
                return renderer.pageCount
            }
        }
    }

    companion object {
        fun create(appContext: Context): DocumentStore = DocumentStore(appContext)
    }
}
