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
import android.os.Build
import android.view.MotionEvent
import android.view.InputDevice
import kotlin.math.pow
import kotlin.math.sqrt

private const val ERASER_RADIUS_MULTIPLIER = 4.8f // 3x larger than previous (1.6x)

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
    feel: InkFeel = InkFeel(),
    onInkingChanged: (Boolean) -> Unit = {},
    onStrokesChange: (List<StrokeDto>, committed: Boolean) -> Unit,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val sizeScaled = size * feel.sizeScale
    val stylusBrush = remember(tool, colorArgb, sizeScaled) {
        InkInterop.brushFor(tool, colorArgb.toInt(), sizeScaled)
    }
    val simulatedBrush = remember(tool, colorArgb, feel.sizeScale) {
        InkInterop.brushFor(tool, colorArgb.toInt(), baseBrushSizeForTool(tool) * feel.sizeScale)
    }
    val latestStrokes by rememberUpdatedState(strokes)
    val latestTool by rememberUpdatedState(tool)
    val latestIsEraser by rememberUpdatedState(isEraser)
    val latestSize by rememberUpdatedState(size)
    val latestSimulatePressure by rememberUpdatedState(simulatePressureWithSizeSlider)
    val latestFeel by rememberUpdatedState(feel)
    val latestOnStrokesChange by rememberUpdatedState(onStrokesChange)
    val latestStylusBrush by rememberUpdatedState(stylusBrush)
    val latestSimulatedBrush by rememberUpdatedState(simulatedBrush)
    val latestOnInkingChanged by rememberUpdatedState(onInkingChanged)

    var hoverPosition by remember { mutableStateOf<Offset?>(null) }
    var eraserPosition by remember { mutableStateOf<Offset?>(null) }
    var stylusDown by remember { mutableStateOf(false) }
    var activeInkPointerId by remember { mutableStateOf<Int?>(null) }
    var inkingNotified by remember { mutableStateOf(false) }
    val inProgressViewState = remember { mutableStateOf<InProgressStrokesView?>(null) }
    val predictorState = remember { mutableStateOf<MotionEventPredictor?>(null) }
    val inkStrokes = remember(strokes) { strokes.map(InkInterop::dtoToStroke) }
    val stabilizer = remember { InkStabilizer() }
    var lastStylusEventTimeMs by remember { mutableStateOf(0L) }

    fun notifyInking(active: Boolean) {
        if (inkingNotified == active) return
        inkingNotified = active
        latestOnInkingChanged(active)
    }

    DisposableEffect(Unit) {
        onDispose { notifyInking(false) }
    }

    Box(
        modifier = modifier.pointerInteropFilter { event ->
            // 手写笔应当“绘制而不是滚动”：识别 stylus/eraser 并消费事件，避免父级滚动容器响应。
            // 同时在笔落下时做掌托拒绝（吞掉手指触摸）。
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                return@pointerInteropFilter false
            }

            fun isStylusSource(source: Int): Boolean {
                val stylus = InputDevice.SOURCE_STYLUS
                val btStylus = InputDevice.SOURCE_BLUETOOTH_STYLUS
                return (source and stylus) == stylus || (source and btStylus) == btStylus
            }

            fun toolTypeOf(pointerIndex: Int): Int {
                return runCatching { event.getToolType(pointerIndex) }.getOrNull() ?: MotionEvent.TOOL_TYPE_UNKNOWN
            }

            fun isInkToolType(toolType: Int): Boolean {
                return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
            }

            val pointerCount = event.pointerCount
            val safeActionIndex = event.actionIndex.coerceIn(0, (pointerCount - 1).coerceAtLeast(0))
            val actionPointerId = runCatching { event.getPointerId(safeActionIndex) }.getOrNull()
            val actionToolType = toolTypeOf(safeActionIndex)

            val fromStylusSource = isStylusSource(event.source)

            val activePointerId = activeInkPointerId
            val activePointerIndex =
                activePointerId
                    ?.let { id -> runCatching { event.findPointerIndex(id) }.getOrNull() }
                    ?.takeIf { it >= 0 }

            // 关键：不要只看 pointer 0。掌托/多指时，笔通常不是 0 号 pointer。
            val inkPointerIndex =
                activePointerIndex
                    ?: runCatching {
                        val tt = toolTypeOf(safeActionIndex)
                        if (isInkToolType(tt)) safeActionIndex else null
                    }.getOrNull()
                    ?: (0 until pointerCount).firstOrNull { isInkToolType(toolTypeOf(it)) }
                    ?: if (fromStylusSource) safeActionIndex else null

            val inkToolType = inkPointerIndex?.let(::toolTypeOf) ?: MotionEvent.TOOL_TYPE_UNKNOWN
            val isStylus = inkPointerIndex != null && (inkToolType == MotionEvent.TOOL_TYPE_STYLUS || fromStylusSource)
            val isStylusEraser = inkPointerIndex != null && inkToolType == MotionEvent.TOOL_TYPE_ERASER

            // 模拟器/鼠标：用于本地调试（可选）
            val isMouseTool = actionToolType == MotionEvent.TOOL_TYPE_MOUSE || actionToolType == MotionEvent.TOOL_TYPE_UNKNOWN
            val isMouseSource = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            val hasMouseButton = event.buttonState != 0
            val isEmulator =
                Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.FINGERPRINT.contains("emu", ignoreCase = true) ||
                    Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
                    Build.MODEL.contains("Emulator", ignoreCase = true) ||
                    Build.MODEL.contains("Android SDK built for", ignoreCase = true)
            val isMouseLike =
                isMouseTool ||
                    (actionToolType == MotionEvent.TOOL_TYPE_FINGER && (isMouseSource || hasMouseButton || isEmulator))
            val allowMouseInput = latestSimulatePressure && isMouseLike

            val isPenEvent = isStylus || isStylusEraser || allowMouseInput || activePointerId != null

            if (!isPenEvent) {
                hoverPosition = null
                eraserPosition = null
                // 掌托拒绝：笔落下时屏蔽手指触摸
                val enhancedPalm =
                    latestFeel.enhancedPalmRejection &&
                        (event.eventTime - lastStylusEventTimeMs) in 0..latestFeel.palmRejectionWindowMs
                return@pointerInteropFilter stylusDown || activePointerId != null || enhancedPalm
            }

            val pointerIndex = activePointerIndex ?: inkPointerIndex ?: safeActionIndex

            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                if (isStylus) {
                    hoverPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
                    lastStylusEventTimeMs = event.eventTime
                }
                return@pointerInteropFilter true
            }

	            val isStylusButtonDown =
	                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
	                    (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
	            val effectiveEraser = latestIsEraser || isStylusEraser || (isStylus && isStylusButtonDown && activeInkPointerId == null)
	            if (effectiveEraser) {
	                val eraserRadiusPx = (latestSize * latestFeel.sizeScale) * ERASER_RADIUS_MULTIPLIER
	                when (event.actionMasked) {
	                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
	                        hoverPosition = null
	                        if (activeInkPointerId == null && actionPointerId != null && (isInkToolType(actionToolType) || fromStylusSource || allowMouseInput)) {
	                            activeInkPointerId = actionPointerId
                            stylusDown = true
                            notifyInking(true)
                            lastStylusEventTimeMs = event.eventTime
                        }
	                    }
	                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
	                        if (actionPointerId != null && actionPointerId == activeInkPointerId) {
	                            val last = eraserPosition ?: Offset(event.getX(pointerIndex), event.getY(pointerIndex))
	                            val updated = PartialEraser.eraseAt(latestStrokes, last, radiusPx = eraserRadiusPx)
	                            eraserPosition = null
	                            activeInkPointerId = null
	                            stylusDown = false
	                            notifyInking(false)
                                lastStylusEventTimeMs = event.eventTime
                            latestOnStrokesChange(updated, true)
                            return@pointerInteropFilter true
                        }
                    }
	                    MotionEvent.ACTION_CANCEL -> {
	                        val last = eraserPosition
	                        if (last != null) {
	                            val updated = PartialEraser.eraseAt(latestStrokes, last, radiusPx = eraserRadiusPx)
	                            latestOnStrokesChange(updated, true)
	                        }
	                        eraserPosition = null
	                        activeInkPointerId = null
                        stylusDown = false
                        notifyInking(false)
                        lastStylusEventTimeMs = event.eventTime
                        return@pointerInteropFilter true
                    }
                }

	                when (event.actionMasked) {
	                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
	                        var working = latestStrokes
	                        for (i in 0 until event.historySize) {
	                            val x = event.getHistoricalX(pointerIndex, i)
	                            val y = event.getHistoricalY(pointerIndex, i)
	                            working = PartialEraser.eraseAt(
	                                working,
	                                Offset(x, y),
	                                radiusPx = eraserRadiusPx,
	                            )
	                        }
	                        working = PartialEraser.eraseAt(
	                            working,
	                            Offset(event.getX(pointerIndex), event.getY(pointerIndex)),
	                            radiusPx = eraserRadiusPx,
	                        )
	                        eraserPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
	                        stylusDown = true
	                        notifyInking(true)
                            lastStylusEventTimeMs = event.eventTime
                        latestOnStrokesChange(working, false)
                        return@pointerInteropFilter true
                    }
                }

                return@pointerInteropFilter true
            }

            // 笔画处理（Jetpack Ink）
            val view = inProgressViewState.value
            if (view == null) return@pointerInteropFilter false

            val predictor = predictorState.value
            val isCanceled = event.actionMasked == MotionEvent.ACTION_CANCEL || (event.flags and MotionEvent.FLAG_CANCELED) != 0

            val syntheticPressure =
                if (allowMouseInput && !isStylus) pressureFromSizeSlider(latestSize) else null
            val brushForThisEvent = if (syntheticPressure != null) latestSimulatedBrush else latestStylusBrush

	            val toolTypeOverrideForInk =
	                when {
	                    syntheticPressure != null -> MotionEvent.TOOL_TYPE_STYLUS
	                    fromStylusSource && inkToolType != MotionEvent.TOOL_TYPE_STYLUS -> MotionEvent.TOOL_TYPE_STYLUS
	                    else -> null
	                }

	            val shouldStabilize = isStylus && latestFeel.stabilization > 0.001f
	            val pointMapperForInk: ((Float, Float, Long) -> Offset)? =
	                if (!shouldStabilize) {
	                    null
	                } else {
	                    { x, y, t -> stabilizer.filter(x, y, t, latestFeel.stabilization) }
	                }

	            val pressureMapper: ((Float) -> Float)? =
	                if (syntheticPressure != null) {
	                    null
	                } else if (isStylus && latestFeel.pressureGamma != 1.0f) {
	                    { p -> applyPressureCurve(p, latestFeel.pressureGamma) }
	                } else {
	                    null
	                }

	            fun eventForInk(original: MotionEvent, idx: Int): MotionEvent {
	                val needsSinglePointer =
	                    original.pointerCount != 1 ||
	                        idx != 0 ||
	                        syntheticPressure != null ||
	                        toolTypeOverrideForInk != null ||
	                        pointMapperForInk != null ||
	                        pressureMapper != null
	                if (!needsSinglePointer) return original
	                return toSinglePointerEvent(
	                    event = original,
	                    pointerIndex = idx,
	                    toolTypeOverride = toolTypeOverrideForInk,
	                    pressureOverride = syntheticPressure,
	                    pointMapper = pointMapperForInk,
	                    pressureMapper = pressureMapper,
	                ) ?: original
	            }

	            // For MotionEventPredictor: do NOT apply stabilizer, otherwise it pollutes the filter state.
	            fun eventForPredictor(original: MotionEvent, idx: Int): MotionEvent {
	                val needsSinglePointer =
	                    original.pointerCount != 1 || idx != 0 || syntheticPressure != null || toolTypeOverrideForInk != null
	                if (!needsSinglePointer) return original
	                return toSinglePointerEvent(
	                    event = original,
	                    pointerIndex = idx,
	                    toolTypeOverride = toolTypeOverrideForInk,
	                    pressureOverride = syntheticPressure,
	                    pointMapper = null,
	                    pressureMapper = null,
	                ) ?: original
	            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Only start inking for stylus-like pointers (or mouse when enabled).
                    if (activeInkPointerId != null) return@pointerInteropFilter true
                    if (actionPointerId == null) return@pointerInteropFilter true
                    if (!allowMouseInput && !fromStylusSource && !isInkToolType(actionToolType)) return@pointerInteropFilter true

                    activeInkPointerId = actionPointerId
	                    stylusDown = true
	                    notifyInking(true)
	                    hoverPosition = null
	                    eraserPosition = null
                        lastStylusEventTimeMs = event.eventTime
                        stabilizer.reset(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)

	                    val e = eventForInk(event, safeActionIndex)
	                    val rec = eventForPredictor(event, safeActionIndex)
	                    view.requestUnbufferedDispatch(e)
	                    runCatching { view.parent?.requestDisallowInterceptTouchEvent(true) }
	                    runCatching { predictor?.record(rec) }
	                    view.startStroke(e, actionPointerId, brushForThisEvent)
	                    if (e !== event) e.recycle()
	                    if (rec !== event && rec !== e) rec.recycle()
	                    return@pointerInteropFilter true
	                }
	                MotionEvent.ACTION_MOVE -> {
                    val moveId = activeInkPointerId ?: return@pointerInteropFilter true
                    val moveIndex =
                        runCatching { event.findPointerIndex(moveId) }.getOrNull()?.takeIf { it >= 0 }
                            ?: return@pointerInteropFilter true

	                    val e = eventForInk(event, moveIndex)
	                    val rec = eventForPredictor(event, moveIndex)
                        view.requestUnbufferedDispatch(e)
	                    runCatching { predictor?.record(rec) }
	                    val predicted = runCatching { predictor?.predict() }.getOrNull()
	                    val p = predicted
	                    if (isCanceled) {
	                        runCatching { view.cancelStroke(e, moveId) }
                        activeInkPointerId = null
                        stylusDown = false
                        notifyInking(false)
                    } else {
                        view.addToStroke(e, moveId, p)
                    }
	                    if (e !== event) e.recycle()
	                    if (rec !== event && rec !== e) rec.recycle()
	                    if (p != null && p !== predicted) p.recycle()
	                    predicted?.recycle()
                        lastStylusEventTimeMs = event.eventTime
	                    return@pointerInteropFilter true
	                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val upId = actionPointerId
                    if (upId == null || upId != activeInkPointerId) return@pointerInteropFilter true

	                    val e = eventForInk(event, safeActionIndex)
                        view.requestUnbufferedDispatch(e)
	                    if (isCanceled) {
                        runCatching { view.cancelStroke(e, upId) }
                    } else {
                        view.finishStroke(e, upId)
                    }
                    if (e !== event) e.recycle()
                    activeInkPointerId = null
	                    stylusDown = false
	                    notifyInking(false)
                        lastStylusEventTimeMs = event.eventTime
	                    return@pointerInteropFilter true
	                }
	                MotionEvent.ACTION_CANCEL -> {
                    val cancelId = activeInkPointerId
                    if (cancelId != null) {
	                        val cancelIndex =
	                            runCatching { event.findPointerIndex(cancelId) }.getOrNull()?.takeIf { it >= 0 } ?: safeActionIndex
	                        val e = eventForInk(event, cancelIndex)
                            view.requestUnbufferedDispatch(e)
	                        runCatching { view.cancelStroke(e, cancelId) }
	                        if (e !== event) e.recycle()
	                    }
	                    activeInkPointerId = null
	                    stylusDown = false
	                    notifyInking(false)
                        lastStylusEventTimeMs = event.eventTime
	                    return@pointerInteropFilter true
	                }
	            }

            return@pointerInteropFilter true
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
		                        radius = (size * latestFeel.sizeScale) * ERASER_RADIUS_MULTIPLIER,
		                        center = p,
		                    )
		                }
		            }
	            val hover = hoverPosition
	            if (!isEraser && hover != null) {
	                drawCircle(
	                    color = Color(0x33111111),
	                    radius = ((size * latestFeel.sizeScale) * 0.8f).coerceAtLeast(3f),
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
                        runCatching { view.eagerInit() }
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
    pointMapper: ((Float, Float, Long) -> Offset)?,
    pressureMapper: ((Float) -> Float)?,
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

    // Important: if pointMapper is stateful (stabilizer), apply it in chronological order:
    // history points first, then current point.
    val historySize = event.historySize
    val historyTimes = if (historySize > 0) LongArray(historySize) else null
    val historyCoords = if (historySize > 0) arrayOfNulls<MotionEvent.PointerCoords>(historySize) else null

    if (historySize > 0) {
        for (h in 0 until historySize) {
            val hc = MotionEvent.PointerCoords()
            event.getHistoricalPointerCoords(pointerIndex, h, hc)
            if (pressureOverride != null) hc.pressure = pressureOverride
            if (pressureMapper != null) hc.pressure = pressureMapper(hc.pressure)
            if (pointMapper != null) {
                val ht = event.getHistoricalEventTime(h)
                val o = pointMapper(hc.x, hc.y, ht)
                hc.x = o.x
                hc.y = o.y
                historyTimes!![h] = ht
            } else {
                historyTimes!![h] = event.getHistoricalEventTime(h)
            }
            historyCoords!![h] = hc
        }
    }

    val coords = MotionEvent.PointerCoords()
    event.getPointerCoords(pointerIndex, coords)
    if (pressureOverride != null) coords.pressure = pressureOverride
    if (pressureMapper != null) coords.pressure = pressureMapper(coords.pressure)
    if (pointMapper != null) {
        val o = pointMapper(coords.x, coords.y, event.eventTime)
        coords.x = o.x
        coords.y = o.y
    }

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

    if (historySize > 0) {
        for (h in 0 until historySize) {
            out.addBatch(historyTimes!![h], arrayOf(historyCoords!![h]!!), event.metaState)
        }
    }
    return out
}

private fun applyPressureCurve(raw: Float, gamma: Float): Float {
    val p = raw.coerceIn(0.01f, 1.0f)
    val mapped = p.pow(gamma.coerceIn(0.5f, 2.0f))
    // Keep a small floor so light touches still draw consistently.
    return mapped.coerceIn(0.03f, 1.0f)
}

private class InkStabilizer {
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastT: Long = 0L

    fun reset(x: Float, y: Float, t: Long) {
        lastX = x
        lastY = y
        lastT = t
    }

    fun filter(x: Float, y: Float, t: Long, stabilization: Float): Offset {
        val lx = lastX
        val ly = lastY
        if (lx == null || ly == null) {
            reset(x, y, t)
            return Offset(x, y)
        }

        val dt = (t - lastT).coerceAtLeast(1L).toFloat()
        val dx = x - lx
        val dy = y - ly
        val speed = sqrt(dx * dx + dy * dy) / dt // px/ms

        // Dynamic smoothing: slow movement => more smoothing; fast => closer to raw.
        val stability = stabilization.coerceIn(0f, 1f)
        val alphaSlow = (1f - stability).coerceIn(0.10f, 1.0f)
        val alphaFast = 1.0f
        val tSpeed = (speed / 2.2f).coerceIn(0f, 1f) // ~2.2 px/ms ~= fast pen stroke
        val alpha = alphaSlow + (alphaFast - alphaSlow) * tSpeed

        val fx = lx + alpha * dx
        val fy = ly + alpha * dy
        lastX = fx
        lastY = fy
        lastT = t
        return Offset(fx, fy)
    }
}
