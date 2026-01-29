package com.mobileai.notes.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.input.motionprediction.MotionEventPredictor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.mobileai.notes.data.StrokeDto
import com.mobileai.notes.data.ToolKind
import android.view.MotionEvent

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkCanvas(
    strokes: List<StrokeDto>,
    modifier: Modifier = Modifier,
    isEraser: Boolean,
    tool: ToolKind,
    colorArgb: Long,
    size: Float,
    simulatePressureWithSizeSlider: Boolean = false,
    onStrokesChange: (List<StrokeDto>, committed: Boolean) -> Unit,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val stylusBrush = remember(tool, colorArgb, size) {
        InkInterop.brushFor(tool, colorArgb.toInt(), size)
    }
    val simulatedBrush = remember(tool, colorArgb) {
        InkInterop.brushFor(tool, colorArgb.toInt(), baseBrushSizeForTool(tool))
    }
    val latestStrokes by rememberUpdatedState(strokes)
    val latestTool by rememberUpdatedState(tool)
    val latestIsEraser by rememberUpdatedState(isEraser)
    val latestSize by rememberUpdatedState(size)
    val latestSimulatePressure by rememberUpdatedState(simulatePressureWithSizeSlider)
    val latestOnStrokesChange by rememberUpdatedState(onStrokesChange)
    val latestStylusBrush by rememberUpdatedState(stylusBrush)
    val latestSimulatedBrush by rememberUpdatedState(simulatedBrush)

    var hoverPosition by remember { mutableStateOf<Offset?>(null) }
    var eraserPosition by remember { mutableStateOf<Offset?>(null) }
    var stylusDown by remember { mutableStateOf(false) }
    val inProgressViewState = remember { mutableStateOf<InProgressStrokesView?>(null) }
    val predictorState = remember { mutableStateOf<MotionEventPredictor?>(null) }
    val inkStrokes = remember(strokes) { strokes.map(InkInterop::dtoToStroke) }

    Box(
        modifier = modifier.pointerInteropFilter { event ->
            // Only consume stylus / eraser. Let touch fall through for scroll/zoom.
            val toolType = runCatching { event.getToolType(0) }.getOrNull() ?: MotionEvent.TOOL_TYPE_UNKNOWN
            val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
            val isStylusEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
            val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE || toolType == MotionEvent.TOOL_TYPE_UNKNOWN
            val allowMouseInput = latestSimulatePressure && isMouse
            if (allowMouseInput && !isStylus && !isStylusEraser && event.pointerCount > 1) {
                return@pointerInteropFilter false
            }
            if (!isStylus && !isStylusEraser && !allowMouseInput) {
                hoverPosition = null
                eraserPosition = null
                // Palm rejection: when stylus is down, swallow touch.
                return@pointerInteropFilter stylusDown
            }

            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                hoverPosition = Offset(event.x, event.y)
                return@pointerInteropFilter true
            }

            val effectiveEraser = latestIsEraser || isStylusEraser
            if (effectiveEraser) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> stylusDown = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> stylusDown = false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        // Use history points for smoother erase.
                        var working = latestStrokes
                        for (i in 0 until event.historySize) {
                            val x = event.getHistoricalX(i)
                            val y = event.getHistoricalY(i)
                            working = PartialEraser.eraseAt(
                                working,
                                Offset(x, y),
                                radiusPx = latestSize * 1.6f,
                            )
                        }
                        working = PartialEraser.eraseAt(
                            working,
                            Offset(event.x, event.y),
                            radiusPx = latestSize * 1.6f,
                        )
                        eraserPosition = Offset(event.x, event.y)
                        latestOnStrokesChange(working, false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val last = eraserPosition ?: Offset(event.x, event.y)
                        val updated = PartialEraser.eraseAt(latestStrokes, last, radiusPx = latestSize * 1.6f)
                        eraserPosition = null
                        stylusDown = false
                        latestOnStrokesChange(updated, true)
                    }
                }
                return@pointerInteropFilter true
            }

            // Pen / pencil / highlighter.
            val view = inProgressViewState.value
            if (view == null) return@pointerInteropFilter false
            val predictor = predictorState.value
            runCatching { predictor?.record(event) }

            val pointerId = event.getPointerId(event.actionIndex.coerceAtLeast(0))
            val syntheticPressure =
                if (allowMouseInput && !isStylus) pressureFromSizeSlider(latestSize) else null
            val brushForThisEvent = if (syntheticPressure != null) latestSimulatedBrush else latestStylusBrush

            fun eventForInk(original: MotionEvent): MotionEvent {
                if (syntheticPressure == null) return original
                return toSinglePointerEvent(
                    event = original,
                    pointerIndex = original.actionIndex.coerceAtLeast(0),
                    toolTypeOverride = MotionEvent.TOOL_TYPE_STYLUS,
                    pressureOverride = syntheticPressure,
                ) ?: original
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Lower latency.
                    val e = eventForInk(event)
                    view.requestUnbufferedDispatch(e)
                    view.startStroke(e, pointerId, brushForThisEvent)
                    if (e !== event) e.recycle()
                    stylusDown = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val predicted = runCatching { predictor?.predict() }.getOrNull()
                    val e = eventForInk(event)
                    val p = predicted?.let { eventForInk(it) } ?: predicted
                    view.addToStroke(e, pointerId, p)
                    if (e !== event) e.recycle()
                    if (p != null && p !== predicted) p.recycle()
                    predicted?.recycle()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val e = eventForInk(event)
                    view.finishStroke(e, pointerId)
                    if (e !== event) e.recycle()
                    stylusDown = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Best-effort.
                    val e = eventForInk(event)
                    runCatching { view.finishStroke(e, pointerId) }
                    if (e !== event) e.recycle()
                    stylusDown = false
                }
            }
            true
        },
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val m = android.graphics.Matrix()
                inkStrokes.forEach { s ->
                    renderer.draw(nativeCanvas, s, m)
                }
            }

            if (isEraser) {
                val p = eraserPosition
                if (p != null) {
                    drawCircle(
                        color = Color(0x66111111),
                        radius = size * 1.6f,
                        center = p,
                    )
                }
            }
            val hover = hoverPosition
            if (!isEraser && hover != null) {
                drawCircle(
                    color = Color(0x33111111),
                    radius = (size * 0.8f).coerceAtLeast(3f),
                    center = hover,
                )
            }
        }

        if (!isEraser) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    InProgressStrokesView(ctx).also { view ->
                        view.isClickable = false
                        view.isFocusable = false
                        view.setWillNotDraw(false)
                        view.setOnTouchListener { _, _ -> false }
                        inProgressViewState.value = view
                        predictorState.value = runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()
                    }
                },
                update = { view ->
                    inProgressViewState.value = view
                    if (predictorState.value == null) {
                        predictorState.value = runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()
                    }
                    // Listener is set via side effect below.
                },
            )
        }
    }

    val currentInProgressView = inProgressViewState.value
    if (currentInProgressView != null && !isEraser) {
        DisposableEffect(currentInProgressView) {
            val listener = object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, androidx.ink.strokes.Stroke>) {
                    if (strokes.isEmpty()) return
                    val newDtos = strokes.values.map { stroke ->
                        InkInterop.strokeToDto(stroke, latestTool)
                    }
                    latestOnStrokesChange(latestStrokes + newDtos, true)
                    currentInProgressView.removeFinishedStrokes(strokes.keys)
                }
            }
            currentInProgressView.addFinishedStrokesListener(listener)
            onDispose { currentInProgressView.removeFinishedStrokesListener(listener) }
        }
    }
}

private fun baseBrushSizeForTool(tool: ToolKind): Float {
    return when (tool) {
        ToolKind.PEN -> 10f
        ToolKind.PENCIL -> 12f
        ToolKind.HIGHLIGHTER -> 18f
    }
}

private fun pressureFromSizeSlider(size: Float): Float {
    val clamped = size.coerceIn(2f, 22f)
    val t = (clamped - 2f) / 20f
    return (0.15f + 0.85f * t).coerceIn(0.05f, 1.0f)
}

private fun toSinglePointerEvent(
    event: MotionEvent,
    pointerIndex: Int,
    toolTypeOverride: Int?,
    pressureOverride: Float?,
): MotionEvent? {
    if (pointerIndex !in 0 until event.pointerCount) return null

    val originalAction = event.actionMasked
    val action = when (originalAction) {
        MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_DOWN
        MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_UP
        else -> originalAction
    }

    val props = MotionEvent.PointerProperties()
    event.getPointerProperties(pointerIndex, props)
    if (toolTypeOverride != null) props.toolType = toolTypeOverride

    val coords = MotionEvent.PointerCoords()
    event.getPointerCoords(pointerIndex, coords)
    if (pressureOverride != null) coords.pressure = pressureOverride

    val out =
        MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            action,
            1,
            arrayOf(props),
            arrayOf(coords),
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )

    // Preserve history for smoother curves, but normalize pressure.
    if (event.historySize > 0) {
        for (h in 0 until event.historySize) {
            val hc = MotionEvent.PointerCoords()
            event.getHistoricalPointerCoords(pointerIndex, h, hc)
            if (pressureOverride != null) hc.pressure = pressureOverride
            out.addBatch(event.getHistoricalEventTime(h), arrayOf(hc), event.metaState)
        }
    }
    return out
}
