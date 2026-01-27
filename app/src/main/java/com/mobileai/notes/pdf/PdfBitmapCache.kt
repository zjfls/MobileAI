package com.mobileai.notes.pdf

import android.graphics.Bitmap
import android.util.LruCache

class PdfBitmapCache(maxBytes: Int) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(pageIndex: Int, widthPx: Int): Bitmap? = cache.get(key(pageIndex, widthPx))

    fun put(pageIndex: Int, widthPx: Int, bitmap: Bitmap) {
        cache.put(key(pageIndex, widthPx), bitmap)
    }

    private fun key(pageIndex: Int, widthPx: Int) = "$pageIndex@$widthPx"
}

