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
import com.mobileai.notes.data.WorksheetNote
import com.mobileai.notes.ink.InkInterop
import com.mobileai.notes.pdf.PdfDocumentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
            DocumentType.WORKSHEET -> exportWorksheetPdf(context, doc, outUri, scale)
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

    suspend fun renderWorksheetPagePngBytes(
        context: Context,
        doc: DocumentEntity,
        pageIndex: Int,
        scale: Float = 2f,
    ): ByteArray? {
        require(doc.type == DocumentType.WORKSHEET)
        val worksheet = doc.worksheet ?: WorksheetNote()
        val page = worksheet.pages.getOrNull(pageIndex) ?: return null

        val bg = page.backgroundImageUri?.let { decodeBitmap(context, it) }
        val baseW = (page.canvasWidthPx.takeIf { it > 0 } ?: bg?.width ?: 1200).toFloat()
        val baseH = (page.canvasHeightPx.takeIf { it > 0 } ?: bg?.height ?: (1200 * 1.4142f)).toFloat()
        val outW = (baseW * scale).roundToInt().coerceAtLeast(1)
        val outH = (baseH * scale).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())
        val canvas = Canvas(bitmap)

        // Background (image) or template.
        if (bg != null) {
            val srcW = bg.width.toFloat().coerceAtLeast(1f)
            val srcH = bg.height.toFloat().coerceAtLeast(1f)
            val m = Matrix().apply { setScale(outW / srcW, outH / srcH) }
            canvas.drawBitmap(bg, m, Paint(Paint.FILTER_BITMAP_FLAG))
            bg.recycle()
        } else {
            drawTemplate(canvas, worksheet.template, scale)
        }

        drawStrokes(canvas, page.strokes, scaleX = outW / baseW, scaleY = outH / baseH)

        return withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                bitmap.recycle()
                baos.toByteArray()
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

    private suspend fun exportWorksheetPdf(
        context: Context,
        doc: DocumentEntity,
        outUri: Uri,
        scale: Float,
    ) = withContext(Dispatchers.IO) {
        val worksheet = doc.worksheet ?: WorksheetNote()
        val pdfDocument = PdfDocument()
        try {
            worksheet.pages.forEachIndexed { index, page ->
                val bg = page.backgroundImageUri?.let { decodeBitmap(context, it) }
                val baseW = (page.canvasWidthPx.takeIf { it > 0 } ?: bg?.width ?: 1200).toFloat()
                val baseH = (page.canvasHeightPx.takeIf { it > 0 } ?: bg?.height ?: (1200 * 1.4142f)).toFloat()
                val outW = (baseW * scale).roundToInt().coerceAtLeast(1)
                val outH = (baseH * scale).roundToInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(outW, outH, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas = pdfPage.canvas
                canvas.drawColor(0xFFFFFFFF.toInt())

                if (bg != null) {
                    val srcW = bg.width.toFloat().coerceAtLeast(1f)
                    val srcH = bg.height.toFloat().coerceAtLeast(1f)
                    val m = Matrix().apply { setScale(outW / srcW, outH / srcH) }
                    canvas.drawBitmap(bg, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    bg.recycle()
                } else {
                    drawTemplate(canvas, worksheet.template, scale)
                }

                drawStrokes(canvas, page.strokes, scaleX = outW / baseW, scaleY = outH / baseH)
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
            PageTemplate.DOTS -> {
                val gap = 24f * scale
                val paint = Paint().apply {
                    color = 0x22000000
                    isAntiAlias = true
                }
                var y = gap
                while (y < canvas.height) {
                    var x = gap
                    while (x < canvas.width) {
                        canvas.drawCircle(x, y, 1.2f * scale, paint)
                        x += gap
                    }
                    y += gap
                }
            }
            PageTemplate.CORNELL -> {
                val headerH = 120f * scale
                val marginW = 180f * scale
                val lineGap = 32f * scale
                val paint = Paint().apply {
                    color = 0x22000000
                    strokeWidth = 1.2f * scale
                    isAntiAlias = true
                }
                // Header separator.
                canvas.drawLine(0f, headerH, canvas.width.toFloat(), headerH, paint)
                // Cornell margin.
                canvas.drawLine(marginW, headerH, marginW, canvas.height.toFloat(), paint)
                // Horizontal lines.
                var y = headerH + lineGap
                while (y < canvas.height) {
                    canvas.drawLine(marginW, y, canvas.width.toFloat(), y, paint)
                    y += lineGap
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

    private fun decodeBitmap(context: Context, uriString: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
}
