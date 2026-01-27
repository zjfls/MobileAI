package com.mobileai.notes.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.mobileai.notes.data.BlankNotebook
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.DocumentType
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.ink.InkInterop
import com.mobileai.notes.pdf.PdfDocumentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object ExportManager {
    suspend fun exportDocumentPdf(
        context: Context,
        doc: DocumentEntity,
        outUri: Uri,
        scale: Float = 2f,
    ) {
        when (doc.type) {
            DocumentType.BLANK -> exportBlankNotebookPdf(context, doc, outUri, scale)
            DocumentType.PDF -> exportAnnotatedPdf(context, doc, outUri, scale)
        }
    }

    suspend fun exportBlankNotebookPng(
        context: Context,
        doc: DocumentEntity,
        pageIndex: Int,
        outUri: Uri,
        scale: Float = 2f,
    ) {
        require(doc.type == DocumentType.BLANK)
        val notebook = doc.blank ?: BlankNotebook()
        val page = notebook.pages.getOrNull(pageIndex) ?: return

        val (baseW, baseH) = chooseCanvasSize(page.canvasWidthPx, page.canvasHeightPx)
        val outW = (baseW * scale).roundToInt().coerceAtLeast(1)
        val outH = (baseH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())

        val canvas = Canvas(bitmap)
        drawTemplate(canvas, notebook.template, scale)
        drawStrokes(canvas, page.strokes, scaleX = outW / baseW.toFloat(), scaleY = outH / baseH.toFloat())

        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(outUri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
        }
    }

    private suspend fun exportBlankNotebookPdf(
        context: Context,
        doc: DocumentEntity,
        outUri: Uri,
        scale: Float,
    ) = withContext(Dispatchers.IO) {
        val notebook = doc.blank ?: BlankNotebook()
        val pdfDocument = PdfDocument()
        try {
            notebook.pages.forEachIndexed { index, page ->
                val (baseW, baseH) = chooseCanvasSize(page.canvasWidthPx, page.canvasHeightPx)
                val outW = (baseW * scale).roundToInt().coerceAtLeast(1)
                val outH = (baseH * scale).roundToInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(outW, outH, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas = pdfPage.canvas
                canvas.drawColor(0xFFFFFFFF.toInt())

                drawTemplate(canvas, notebook.template, scale)
                drawStrokes(canvas, page.strokes, scaleX = outW / baseW.toFloat(), scaleY = outH / baseH.toFloat())

                pdfDocument.finishPage(pdfPage)
            }
            context.contentResolver.openOutputStream(outUri)?.use { os ->
                pdfDocument.writeTo(os)
            }
        } finally {
            pdfDocument.close()
        }
    }

    private suspend fun exportAnnotatedPdf(
        context: Context,
        doc: DocumentEntity,
        outUri: Uri,
        scale: Float,
    ) = withContext(Dispatchers.IO) {
        val pdfNote = doc.pdf ?: return@withContext
        val uri = Uri.parse(pdfNote.pdfUri)
        val handle = PdfDocumentHandle.open(context, uri) ?: return@withContext
        handle.use {
            val renderer = CanvasStrokeRenderer.create()
            val pdfDocument = PdfDocument()
            try {
                for (pageIndex in 0 until pdfNote.pageCount) {
                    val ann = pdfNote.pages.firstOrNull { it.pageIndex == pageIndex }
                    val baseWidth = (ann?.canvasWidthPx ?: 0).takeIf { it > 0 } ?: 1600
                    val bitmapWidth = (baseWidth * scale).roundToInt().coerceAtLeast(1)
                    val pdfBitmap = it.renderPage(pageIndex, bitmapWidth)

                    val pageInfo = PdfDocument.PageInfo.Builder(pdfBitmap.width, pdfBitmap.height, pageIndex + 1).create()
                    val pdfPage = pdfDocument.startPage(pageInfo)
                    val canvas = pdfPage.canvas
                    canvas.drawBitmap(pdfBitmap, 0f, 0f, null)

                    if (ann != null && ann.strokes.isNotEmpty()) {
                        val sx = if (ann.canvasWidthPx > 0) pdfBitmap.width / ann.canvasWidthPx.toFloat() else 1f
                        val sy = if (ann.canvasHeightPx > 0) pdfBitmap.height / ann.canvasHeightPx.toFloat() else 1f
                        val m = Matrix().apply { setScale(sx, sy) }
                        val inkStrokes = ann.strokes.map(InkInterop::dtoToStroke)
                        inkStrokes.forEach { s -> renderer.draw(canvas, s, m) }
                    }

                    pdfDocument.finishPage(pdfPage)
                    pdfBitmap.recycle()
                }
                context.contentResolver.openOutputStream(outUri)?.use { os ->
                    pdfDocument.writeTo(os)
                }
            } finally {
                pdfDocument.close()
            }
        }
    }

    private fun chooseCanvasSize(widthPx: Int, heightPx: Int): Pair<Float, Float> {
        if (widthPx > 0 && heightPx > 0) return widthPx.toFloat() to heightPx.toFloat()
        // Fallback A4-ish.
        return 1200f to (1200f * 1.4142f)
    }

    private fun drawTemplate(canvas: Canvas, template: PageTemplate, scale: Float) {
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> {
                val gap = 32f * scale
                val paint = Paint().apply {
                    color = 0x22000000
                    strokeWidth = 1f * scale
                    isAntiAlias = true
                }
                var y = gap
                while (y < canvas.height) {
                    canvas.drawLine(0f, y, canvas.width.toFloat(), y, paint)
                    y += gap
                }
            }
            PageTemplate.GRID -> {
                val gap = 32f * scale
                val paint = Paint().apply {
                    color = 0x16000000
                    strokeWidth = 1f * scale
                    isAntiAlias = true
                }
                var x = gap
                while (x < canvas.width) {
                    canvas.drawLine(x, 0f, x, canvas.height.toFloat(), paint)
                    x += gap
                }
                var y = gap
                while (y < canvas.height) {
                    canvas.drawLine(0f, y, canvas.width.toFloat(), y, paint)
                    y += gap
                }
            }
        }
    }

    private fun drawStrokes(
        canvas: Canvas,
        strokes: List<com.mobileai.notes.data.StrokeDto>,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (strokes.isEmpty()) return
        val renderer = CanvasStrokeRenderer.create()
        val m = Matrix().apply { setScale(scaleX, scaleY) }
        val inkStrokes = strokes.map(InkInterop::dtoToStroke)
        inkStrokes.forEach { s ->
            renderer.draw(canvas, s, m)
        }
    }
}
