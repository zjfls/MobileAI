package com.mobileai.notes.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
    private const val AI_EXPORT_MAX_QUESTION_CHARS = 1600

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
        scale: Float = 0.5f,
    ): ByteArray? {
        require(doc.type == DocumentType.WORKSHEET)
        val worksheet = doc.worksheet ?: WorksheetNote()
        val page = worksheet.pages.getOrNull(pageIndex) ?: return null

        // IMPORTANT: This image is used for multimodal "AI 解答".
        // The worksheet page is scrollable; capturing just the visible viewport will miss content.
        // So we render the FULL logical canvas here (enough to include all strokes).
        //
        // Question text is already sent as plain text in the prompt; do NOT attempt to "screenshot" the overlay.
        val baseW0 = (page.canvasWidthPx.takeIf { it > 0 } ?: 1200).toFloat()
        val baseH0 = (page.canvasHeightPx.takeIf { it > 0 } ?: (1200 * 1.4142f)).toFloat()
        val strokeBounds = computeStrokeBounds(page.strokes)
        val marginBottom = 220f
        val baseH =
            maxOf(
                baseH0,
                ((strokeBounds?.bottom ?: 0f) + marginBottom).coerceAtLeast(1f),
            )
        val baseW = baseW0.coerceAtLeast(1f)
        val cropRect = RectF(0f, 0f, baseW, baseH)
        val cropW = baseW
        val cropH = baseH

        val maxLongEdgePx = 2200f
        val maxPixels = 3_000_000f

        var effectiveScale = scale.coerceAtLeast(0.05f)
        run {
            val outW0 = cropW * effectiveScale
            val outH0 = cropH * effectiveScale
            val longEdge0 = maxOf(outW0, outH0)
            if (longEdge0 > maxLongEdgePx) effectiveScale *= maxLongEdgePx / longEdge0
        }
        run {
            val outPixels0 = (cropW * effectiveScale) * (cropH * effectiveScale)
            if (outPixels0 > maxPixels) {
                val factor = kotlin.math.sqrt(maxPixels / outPixels0)
                effectiveScale *= factor
            }
        }
        effectiveScale = effectiveScale.coerceAtLeast(0.05f)

        val outW = (cropW * effectiveScale).roundToInt().coerceAtLeast(1)
        val outH = (cropH * effectiveScale).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())
        val canvas = Canvas(bitmap)

        // Always render the notebook template as background.
        drawTemplate(canvas, worksheet.template, effectiveScale)

        // For QUESTION pages, render the question itself into the exported image.
        // The question overlay in UI is scrollable; sending it as text can conflict with "image-based" workflows.
        // This is a pragmatic rendering: if there is a background image, draw it; otherwise draw a text box.
        if (page.type == com.mobileai.notes.data.WorksheetPageType.QUESTION) {
            val bgQuestion = page.backgroundImageUri?.let { decodeBitmap(context, it) }
            if (bgQuestion != null) {
                val pad = 18f * effectiveScale
                val maxW = (outW - pad * 2f).coerceAtLeast(1f)
                val maxH = (outH * 0.45f).coerceAtLeast(1f)
                val sx = maxW / bgQuestion.width.toFloat().coerceAtLeast(1f)
                val sy = maxH / bgQuestion.height.toFloat().coerceAtLeast(1f)
                val s = minOf(sx, sy, 1.0f)
                val dstW = (bgQuestion.width * s).roundToInt().coerceAtLeast(1)
                val dstH = (bgQuestion.height * s).roundToInt().coerceAtLeast(1)
                val dst =
                    Rect(
                        pad.roundToInt(),
                        pad.roundToInt(),
                        (pad + dstW).roundToInt(),
                        (pad + dstH).roundToInt(),
                    )
                canvas.drawBitmap(bgQuestion, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                bgQuestion.recycle()
            } else {
                val text =
                    page.questionText
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { t -> if (t.length > AI_EXPORT_MAX_QUESTION_CHARS) t.take(AI_EXPORT_MAX_QUESTION_CHARS) + "…" else t }
                if (!text.isNullOrBlank()) {
                    drawWorksheetHeader(canvas, page.title, text, effectiveScale)
                }
            }
        }

        val scaleX = outW / cropW
        val scaleY = outH / cropH
        drawStrokes(
            canvas = canvas,
            strokes = page.strokes,
            scaleX = scaleX,
            scaleY = scaleY,
            translateX = -cropRect.left * scaleX,
            translateY = -cropRect.top * scaleY,
        )

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

                val headerText =
                    when (page.type) {
                        com.mobileai.notes.data.WorksheetPageType.QUESTION -> page.questionText
                        com.mobileai.notes.data.WorksheetPageType.ANSWER -> page.answerText
                    }
                drawWorksheetHeader(canvas, page.title, headerText, scale)
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
        translateX: Float = 0f,
        translateY: Float = 0f,
    ) {
        if (strokes.isEmpty()) return
        val renderer = CanvasStrokeRenderer.create()
        val m =
            Matrix().apply {
                setScale(scaleX, scaleY)
                postTranslate(translateX, translateY)
            }
        val inkStrokes = strokes.map(InkInterop::dtoToStroke)
        inkStrokes.forEach { s ->
            renderer.draw(canvas, s, m)
        }
    }

    private fun computeStrokeBounds(strokes: List<com.mobileai.notes.data.StrokeDto>): RectF? {
        var bounds: RectF? = null
        for (s in strokes) {
            for (p in s.points) {
                val x = p.x
                val y = p.y
                if (!x.isFinite() || !y.isFinite()) continue
                val b = bounds
                if (b == null) {
                    bounds = RectF(x, y, x, y)
                } else {
                    b.union(x, y)
                }
            }
        }
        return bounds
    }

    private fun decodeBitmap(context: Context, uriString: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    private fun drawWorksheetHeader(
        canvas: Canvas,
        title: String?,
        questionText: String?,
        scale: Float,
    ) {
        val text = questionText?.trim().orEmpty()
        if (text.isBlank()) return

        val padding = (20f * scale).roundToInt()
        val corner = 18f * scale
        val headerMaxW = canvas.width - padding * 2
        if (headerMaxW <= 0) return

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * scale
            color = 0x1A000000
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4F46E5.toInt()
            textSize = 14f * scale
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 13.5f * scale
        }

        val layout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, headerMaxW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        val titleLine = (title?.trim().takeIf { !it.isNullOrBlank() } ?: "题目")
        val titleH = (titlePaint.fontMetrics.bottom - titlePaint.fontMetrics.top).toInt()
        val contentH = titleH + (10f * scale).roundToInt() + layout.height
        val boxH = contentH + padding

        val left = padding.toFloat()
        val top = padding.toFloat()
        val right = (padding + headerMaxW).toFloat()
        val bottom = (padding + boxH).toFloat().coerceAtMost(canvas.height.toFloat())
        val rect = android.graphics.RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        canvas.drawRoundRect(rect, corner, corner, strokePaint)

        canvas.save()
        canvas.translate(left + padding / 2f, top + padding / 2f)
        canvas.drawText(titleLine, 0f, titleH.toFloat(), titlePaint)
        canvas.translate(0f, titleH + (10f * scale))
        layout.draw(canvas)
        canvas.restore()
    }
}
