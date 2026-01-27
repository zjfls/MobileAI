package com.mobileai.notes.ink

import androidx.annotation.ColorInt
import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.mobileai.notes.data.PointDto
import com.mobileai.notes.data.StrokeDto
import com.mobileai.notes.data.ToolKind
import java.util.UUID
import kotlin.math.PI

object InkInterop {
    fun brushFor(tool: ToolKind, @ColorInt colorIntArgb: Int, size: Float): Brush {
        val family = when (tool) {
            ToolKind.PEN -> StockBrushes.pressurePen()
            ToolKind.PENCIL -> StockBrushes.marker()
            ToolKind.HIGHLIGHTER -> StockBrushes.highlighter()
        }
        val epsilon = 0.1f
        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = colorIntArgb,
            size = size,
            epsilon = epsilon,
        )
    }

    fun strokeToDto(stroke: Stroke, tool: ToolKind): StrokeDto {
        val points = (0 until stroke.inputs.size).map { i ->
            val input = stroke.inputs[i]
            PointDto(
                x = input.x,
                y = input.y,
                t = input.elapsedTimeMillis,
                pressure = input.pressure.takeIf { input.hasPressure },
                tilt = input.tiltRadians.takeIf { input.hasTilt },
                orientation = input.orientationRadians.takeIf { input.hasOrientation },
                strokeUnitLengthCm = input.strokeUnitLengthCm.takeIf { it != StrokeInput.NO_STROKE_UNIT_LENGTH },
            )
        }
        return StrokeDto(
            id = UUID.randomUUID().toString(),
            tool = tool,
            colorArgb = stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFFL,
            size = stroke.brush.size,
            points = points,
        )
    }

    fun dtoToStroke(dto: StrokeDto): Stroke {
        val brush = brushFor(dto.tool, dto.colorArgb.toInt(), dto.size)
        val toolType = InputToolType.STYLUS
        val batch = MutableStrokeInputBatch()
        dto.points.forEach { p ->
            batch.add(
                type = toolType,
                x = p.x,
                y = p.y,
                elapsedTimeMillis = p.t,
                strokeUnitLengthCm = p.strokeUnitLengthCm ?: StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure = p.pressure ?: StrokeInput.NO_PRESSURE,
                tiltRadians = normalizeTilt(p.tilt),
                orientationRadians = normalizeOrientation(p.orientation),
            )
        }
        return Stroke(brush = brush, inputs = batch)
    }

    private fun normalizeTilt(tiltRadians: Float?): Float {
        val t = tiltRadians ?: StrokeInput.NO_TILT
        if (t == StrokeInput.NO_TILT) return t
        return t.coerceIn(0f, (PI / 2.0).toFloat())
    }

    private fun normalizeOrientation(orientationRadians: Float?): Float {
        val o = orientationRadians ?: StrokeInput.NO_ORIENTATION
        if (o == StrokeInput.NO_ORIENTATION) return o
        var v = o
        val twoPi = (2.0 * PI).toFloat()
        v %= twoPi
        if (v < 0) v += twoPi
        return v
    }

    fun distance(a: Offset, x: Float, y: Float): Float {
        val dx = a.x - x
        val dy = a.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
