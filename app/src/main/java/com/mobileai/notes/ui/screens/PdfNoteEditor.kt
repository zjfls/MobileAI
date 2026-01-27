package com.mobileai.notes.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.PdfNote
import com.mobileai.notes.data.PdfPageAnnotations
import com.mobileai.notes.data.StrokeDto
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.ink.InkCanvas
import com.mobileai.notes.pdf.PdfBitmapCache
import com.mobileai.notes.pdf.PdfDocumentHandle

@Composable
fun PdfNoteEditor(
    doc: DocumentEntity,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    onDocChange: (DocumentEntity, committed: Boolean) -> Unit,
) {
    val pdf = doc.pdf ?: return
    val context = LocalContext.current
    val uri = remember(pdf.pdfUri) { Uri.parse(pdf.pdfUri) }

    val handle = remember(uri) { PdfDocumentHandle.open(context, uri) }
    DisposableEffect(handle) {
        onDispose { handle?.close() }
    }
    if (handle == null) {
        Text("无法打开 PDF（请确认已授予持久化读取权限）")
        return
    }

    val cache = remember { PdfBitmapCache(maxBytes = 64 * 1024 * 1024) } // 64MB

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
    ) {
        items((0 until pdf.pageCount).toList()) { pageIndex ->
            Text(
                text = "第 ${pageIndex + 1} 页",
                modifier = Modifier.padding(vertical = 10.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt().coerceAtLeast(1) }

                val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = widthPx) {
                    val cached = cache.get(pageIndex, widthPx)
                    if (cached != null) {
                        value = cached
                        return@produceState
                    }
                    val bmp = handle.renderPage(pageIndex, widthPx)
                    cache.put(pageIndex, widthPx, bmp)
                    value = bmp
                }
                val bitmap = bitmapState.value
                if (bitmap != null) {
                    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )

                        val pageStrokes = strokesFor(pdf, pageIndex)
                        InkCanvas(
                            strokes = pageStrokes,
                            modifier = Modifier.fillMaxSize(),
                            isEraser = isEraser,
                            tool = tool,
                            colorArgb = colorArgb,
                            size = size,
                            onStrokesChange = { updatedStrokes, committed ->
                                val updatedPdf = setPageStrokes(
                                    pdf,
                                    pageIndex,
                                    updatedStrokes,
                                    canvasWidthPx = bitmap.width,
                                    canvasHeightPx = bitmap.height,
                                )
                                onDocChange(doc.copy(pdf = updatedPdf), committed)
                            },
                        )
                    }
                } else {
                    Text("渲染中…")
                }
            }
        }
    }
}

private fun strokesFor(pdf: PdfNote, pageIndex: Int): List<StrokeDto> =
    pdf.pages.firstOrNull { it.pageIndex == pageIndex }?.strokes ?: emptyList()

private fun setPageStrokes(
    pdf: PdfNote,
    pageIndex: Int,
    strokes: List<StrokeDto>,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
): PdfNote {
    val pages = pdf.pages.toMutableList()
    val idx = pages.indexOfFirst { it.pageIndex == pageIndex }
    if (idx >= 0) {
        pages[idx] = pages[idx].copy(
            strokes = strokes,
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
        )
    } else {
        pages.add(
            PdfPageAnnotations(
                pageIndex = pageIndex,
                strokes = strokes,
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
            ),
        )
    }
    return pdf.copy(pages = pages)
}
