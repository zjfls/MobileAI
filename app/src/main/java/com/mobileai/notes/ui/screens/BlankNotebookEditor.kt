package com.mobileai.notes.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.BlankNotebook
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.PageTemplate
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.ink.InkCanvas

@Composable
fun BlankNotebookEditor(
    doc: DocumentEntity,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    onDocChange: (DocumentEntity, committed: Boolean) -> Unit,
) {
    val notebook = doc.blank ?: BlankNotebook()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(notebook.pages) { pageIndex, page ->
            val pageSizeState = remember(pageIndex) { mutableStateOf(IntSize.Zero) }
            Text(
                text = "第 ${pageIndex + 1} 页",
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.4142f) // A4-ish
                    .padding(bottom = 18.dp)
                    .onSizeChanged { pageSizeState.value = it },
            ) {
                PageTemplateBackground(template = notebook.template)
                InkCanvas(
                    strokes = page.strokes,
                    modifier = Modifier.fillMaxSize(),
                    isEraser = isEraser,
                    tool = tool,
                    colorArgb = colorArgb,
                    size = size,
                    simulatePressureWithSizeSlider = simulatePressureWithSizeSlider,
                    onStrokesChange = { updatedStrokes, committed ->
                        val px = pageSizeState.value
                        val updatedPages = notebook.pages.toMutableList()
                        updatedPages[pageIndex] = updatedPages[pageIndex].copy(
                            strokes = updatedStrokes,
                            canvasWidthPx = px.width,
                            canvasHeightPx = px.height,
                        )
                        onDocChange(
                            doc.copy(
                                blank = notebook.copy(pages = updatedPages),
                            ),
                            committed,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PageTemplateBackground(template: PageTemplate) {
    Canvas(modifier = Modifier.fillMaxSize()) {
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
        }
    }
}
