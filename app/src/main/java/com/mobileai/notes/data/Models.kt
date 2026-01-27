package com.mobileai.notes.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentType {
    BLANK,
    PDF,
}

@Serializable
data class DocumentSummary(
    val id: String,
    val type: DocumentType,
    val title: String,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class DocumentIndex(
    val documents: List<DocumentSummary> = emptyList(),
)

@Serializable
data class DocumentEntity(
    val id: String,
    val type: DocumentType,
    val title: String,
    val updatedAtEpochMillis: Long,

    @SerialName("blank")
    val blank: BlankNotebook? = null,

    @SerialName("pdf")
    val pdf: PdfNote? = null,
)

@Serializable
data class BlankNotebook(
    val pages: List<NotebookPage> = listOf(NotebookPage()),
    val template: PageTemplate = PageTemplate.BLANK,
)

@Serializable
data class NotebookPage(
    val strokes: List<StrokeDto> = emptyList(),
    val canvasWidthPx: Int = 0,
    val canvasHeightPx: Int = 0,
)

@Serializable
enum class PageTemplate {
    BLANK,
    RULED,
    GRID,
}

@Serializable
data class PdfNote(
    val pdfUri: String,
    val pageCount: Int,
    val pages: List<PdfPageAnnotations> = emptyList(),
)

@Serializable
data class PdfPageAnnotations(
    val pageIndex: Int,
    val strokes: List<StrokeDto> = emptyList(),
    val canvasWidthPx: Int = 0,
    val canvasHeightPx: Int = 0,
)

@Serializable
enum class ToolKind {
    PEN,
    PENCIL,
    HIGHLIGHTER,
}

@Serializable
data class StrokeDto(
    val id: String,
    val tool: ToolKind,
    val colorArgb: Long,
    val size: Float,
    val points: List<PointDto>,
)

@Serializable
data class PointDto(
    val x: Float,
    val y: Float,
    val t: Long,
    val pressure: Float? = null,
    val tilt: Float? = null,
    val orientation: Float? = null,
    val strokeUnitLengthCm: Float? = null,
)
