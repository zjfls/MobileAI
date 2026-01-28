package com.mobileai.notes.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.PageTemplate

@Composable
fun PageTemplateBackground(
    template: PageTemplate,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> {
                val gap = 32.dp.toPx()
                var y = gap
                while (y < size.height) {
                    drawLine(
                        color = Color(0x22000000),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    y += gap
                }
            }
            PageTemplate.GRID -> {
                val gap = 32.dp.toPx()
                var x = gap
                while (x < size.width) {
                    drawLine(
                        color = Color(0x16000000),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += gap
                }
                var y = gap
                while (y < size.height) {
                    drawLine(
                        color = Color(0x16000000),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    y += gap
                }
            }
            PageTemplate.DOTS -> {
                val gap = 24.dp.toPx()
                var y = gap
                while (y < size.height) {
                    var x = gap
                    while (x < size.width) {
                        drawCircle(
                            color = Color(0x22000000),
                            radius = 1.2.dp.toPx(),
                            center = Offset(x, y),
                        )
                        x += gap
                    }
                    y += gap
                }
            }
            PageTemplate.CORNELL -> {
                val headerH = 120.dp.toPx()
                val marginW = 180.dp.toPx()
                val lineGap = 32.dp.toPx()
                // Header separator
                drawLine(
                    color = Color(0x22000000),
                    start = Offset(0f, headerH),
                    end = Offset(size.width, headerH),
                    strokeWidth = 1.2.dp.toPx(),
                )
                // Margin
                drawLine(
                    color = Color(0x22000000),
                    start = Offset(marginW, headerH),
                    end = Offset(marginW, size.height),
                    strokeWidth = 1.2.dp.toPx(),
                )
                // Lines
                var y = headerH + lineGap
                while (y < size.height) {
                    drawLine(
                        color = Color(0x22000000),
                        start = Offset(marginW, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    y += lineGap
                }
            }
        }
    }
}

