package com.mobileai.notes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.roundToInt

class PdfDocumentHandle private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        renderer.openPage(pageIndex).use { page ->
            val scale = targetWidthPx.toFloat() / page.width.toFloat()
            val w = targetWidthPx
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFFFFFFFF.toInt())
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfDocumentHandle? {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val renderer = PdfRenderer(pfd)
            return PdfDocumentHandle(pfd, renderer)
        }
    }
}

