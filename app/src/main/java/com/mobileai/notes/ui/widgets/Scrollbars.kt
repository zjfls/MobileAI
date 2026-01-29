package com.mobileai.notes.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbMinHeight: Dp = 24.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbMinHeightPx = with(density) { thumbMinHeight.toPx() }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val layoutInfo = state.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItemsCount = layoutInfo.visibleItemsInfo.size
    if (totalItemsCount <= 0 || visibleItemsCount <= 0 || totalItemsCount <= visibleItemsCount) return

    val scrollRangeItems = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
    val scrollFraction = (state.firstVisibleItemIndex.coerceIn(0, scrollRangeItems)).toFloat() / scrollRangeItems.toFloat()
    val visibleFraction = visibleItemsCount.toFloat() / totalItemsCount.toFloat()

    Canvas(
        modifier =
            modifier
                .onSizeChanged { containerHeightPx = it.height.toFloat() }
                .pointerInput(totalItemsCount, visibleItemsCount, containerHeightPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, _ ->
                            val height = containerHeightPx
                            if (height <= 0f) return@detectVerticalDragGestures
                            val thumbHeight = maxOf(height * visibleFraction, thumbMinHeightPx)
                            val trackHeight = height - thumbHeight
                            if (trackHeight <= 0f) return@detectVerticalDragGestures

                            val y = change.position.y
                            val fraction = ((y - thumbHeight / 2f) / trackHeight).coerceIn(0f, 1f)
                            val targetIndex = (fraction * scrollRangeItems.toFloat()).roundToInt()
                            scope.launch { state.scrollToItem(targetIndex) }
                            change.consumeAllChanges()
                        },
                    )
                },
    ) {
        val thumbHeight = maxOf(size.height * visibleFraction, thumbMinHeightPx)
        val trackHeight = size.height - thumbHeight
        val y = trackHeight * scrollFraction
        val radius = CornerRadius(x = size.width / 2f, y = size.width / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(width = size.width, height = size.height),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x = 0f, y = y),
            size = Size(width = size.width, height = thumbHeight),
            cornerRadius = radius,
        )
    }
}

@Composable
fun VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    thumbMinHeight: Dp = 24.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbMinHeightPx = with(density) { thumbMinHeight.toPx() }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val layoutInfo = state.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItemsInfo = layoutInfo.visibleItemsInfo
    if (totalItemsCount <= 0 || visibleItemsInfo.isEmpty()) return

    val visibleItemsCount = visibleItemsInfo.size
    if (totalItemsCount <= visibleItemsCount) return

    val scrollRangeItems = (totalItemsCount - visibleItemsCount).coerceAtLeast(1)
    val scrollFraction = (state.firstVisibleItemIndex.coerceIn(0, scrollRangeItems)).toFloat() / scrollRangeItems.toFloat()
    val visibleFraction = visibleItemsCount.toFloat() / totalItemsCount.toFloat()

    Canvas(
        modifier =
            modifier
                .onSizeChanged { containerHeightPx = it.height.toFloat() }
                .pointerInput(totalItemsCount, visibleItemsCount, containerHeightPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, _ ->
                            val height = containerHeightPx
                            if (height <= 0f) return@detectVerticalDragGestures
                            val thumbHeight = maxOf(height * visibleFraction, thumbMinHeightPx)
                            val trackHeight = height - thumbHeight
                            if (trackHeight <= 0f) return@detectVerticalDragGestures

                            val y = change.position.y
                            val fraction = ((y - thumbHeight / 2f) / trackHeight).coerceIn(0f, 1f)
                            val targetIndex = (fraction * scrollRangeItems.toFloat()).roundToInt().coerceIn(0, totalItemsCount - 1)
                            scope.launch { state.scrollToItem(targetIndex) }
                            change.consumeAllChanges()
                        },
                    )
                },
    ) {
        val thumbHeight = maxOf(size.height * visibleFraction, thumbMinHeightPx)
        val trackHeight = size.height - thumbHeight
        val y = trackHeight * scrollFraction
        val radius = CornerRadius(x = size.width / 2f, y = size.width / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(width = size.width, height = size.height),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x = 0f, y = y),
            size = Size(width = size.width, height = thumbHeight),
            cornerRadius = radius,
        )
    }
}

@Composable
fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    thumbMinHeight: Dp = 24.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbMinHeightPx = with(density) { thumbMinHeight.toPx() }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val max = state.maxValue
    if (max <= 0) return

    Canvas(
        modifier =
            modifier
                .onSizeChanged { containerHeightPx = it.height.toFloat() }
                .pointerInput(max, containerHeightPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, _ ->
                            val height = containerHeightPx
                            if (height <= 0f) return@detectVerticalDragGestures
                            val contentHeight = height + max.toFloat()
                            val visibleFraction = (height / contentHeight).coerceIn(0.05f, 1f)
                            val thumbHeight = maxOf(height * visibleFraction, thumbMinHeightPx)
                            val trackHeight = height - thumbHeight
                            if (trackHeight <= 0f) return@detectVerticalDragGestures

                            val y = change.position.y
                            val fraction = ((y - thumbHeight / 2f) / trackHeight).coerceIn(0f, 1f)
                            val target = (fraction * max.toFloat()).roundToInt()
                            scope.launch { state.scrollTo(target) }
                            change.consumeAllChanges()
                        },
                    )
                },
    ) {
        val height = size.height
        val contentHeight = height + max.toFloat()
        val visibleFraction = (height / contentHeight).coerceIn(0.05f, 1f)

        val thumbHeight = maxOf(height * visibleFraction, thumbMinHeightPx)
        val trackHeight = height - thumbHeight
        val scrollFraction = (state.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val y = trackHeight * scrollFraction
        val radius = CornerRadius(x = size.width / 2f, y = size.width / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(width = size.width, height = size.height),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x = 0f, y = y),
            size = Size(width = size.width, height = thumbHeight),
            cornerRadius = radius,
        )
    }
}
