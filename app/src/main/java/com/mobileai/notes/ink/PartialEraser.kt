package com.mobileai.notes.ink

import androidx.compose.ui.geometry.Offset
import com.mobileai.notes.data.PointDto
import com.mobileai.notes.data.StrokeDto
import java.util.UUID

object PartialEraser {
    fun eraseAt(
        strokes: List<StrokeDto>,
        at: Offset,
        radiusPx: Float,
    ): List<StrokeDto> {
        if (strokes.isEmpty()) return strokes

        val out = ArrayList<StrokeDto>(strokes.size)

        for (stroke in strokes) {
            val points = stroke.points
            if (points.size < 2) continue

            var anyErased = false
            val segments = mutableListOf<MutableList<PointDto>>()
            var current = mutableListOf<PointDto>()

            for (p in points) {
                val d = InkInterop.distance(at, p.x, p.y)
                val erased = d <= radiusPx
                if (erased) {
                    anyErased = true
                    if (current.size >= 2) segments.add(current)
                    current = mutableListOf()
                } else {
                    current.add(p)
                }
            }
            if (current.size >= 2) segments.add(current)

            if (!anyErased) {
                out.add(stroke)
                continue
            }
            for (seg in segments) {
                out.add(
                    stroke.copy(
                        id = UUID.randomUUID().toString(),
                        points = seg,
                    ),
                )
            }
        }
        return out
    }
}
