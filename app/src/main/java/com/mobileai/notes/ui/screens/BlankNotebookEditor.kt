package com.mobileai.notes.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobileai.notes.data.BlankNotebook
import com.mobileai.notes.data.DocumentEntity
import com.mobileai.notes.data.ToolKind
import com.mobileai.notes.ink.InkCanvas
import com.mobileai.notes.ink.InkFeel
import com.mobileai.notes.ui.widgets.PageTemplateBackground
import com.mobileai.notes.ui.widgets.VerticalScrollbar

@Composable
fun BlankNotebookEditor(
    doc: DocumentEntity,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean,
    inkFeel: InkFeel = InkFeel(),
    onDocChange: (DocumentEntity, committed: Boolean) -> Unit,
) {
    val notebook = doc.blank ?: BlankNotebook()
    val listState = rememberLazyListState()
    val inkingState = remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = !inkingState.value,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
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
                        feel = inkFeel,
                        onInkingChanged = { active -> inkingState.value = active },
                        onStrokesChange = { updatedStrokes, committed ->
                            val px = pageSizeState.value
                            val updatedPages = notebook.pages.toMutableList()
                            updatedPages[pageIndex] =
                                updatedPages[pageIndex].copy(
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

        VerticalScrollbar(
            state = listState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .padding(end = 6.dp)
                    .width(6.dp),
        )
    }
}
