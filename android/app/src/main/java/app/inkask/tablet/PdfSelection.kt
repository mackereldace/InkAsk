package app.inkask.tablet

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PdfSelection(val fragments: List<PdfRange>) {
    val page get() = fragments.first().page
    val quote get() = fragments.joinToString("\n") { it.quote }.trim()

    fun anchor(size: (Int) -> PageSize): JSONObject {
        val parts = fragments.map { it.anchor(size(it.page)) }
        return JSONObject(parts.first().toString()).put("quote", quote).apply {
            if (parts.size > 1) put("fragments", JSONArray(parts))
        }
    }
}

internal fun JSONObject.anchorParts(): List<JSONObject> =
    optJSONArray("fragments").objects().ifEmpty { listOf(this) }

internal fun JSONObject.pdfSelection(size: (Int) -> PageSize): PdfSelection =
    PdfSelection(anchorParts().map { it.pdfRange(size(it.getInt("page"))) })

private data class PdfEndpoint(val page: Int, val point: Point)
internal data class PdfHandle(val page: Int, val point: Offset)
internal enum class PdfSelectionMode { Text, Region }

internal fun PdfSelection.handles(): List<PdfHandle> {
    val first = fragments.first()
    val last = fragments.last()
    return listOfNotNull(
        first.boxes.firstOrNull()?.let { PdfHandle(first.page, Offset(it.left, it.bottom)) },
        last.boxes.lastOrNull()?.let { PdfHandle(last.page, Offset(it.right, it.bottom)) },
    )
}

internal data class PdfPageLayout(
    val coordinates: LayoutCoordinates, val size: PageSize, val rotation: Int, val links: List<PdfPageLink>,
) {
    fun linkAt(point: Point): PdfPageLink? = links.firstOrNull { link ->
        link.bounds.any { it.contains(point.x.toFloat(), point.y.toFloat()) }
    }

    fun localOffset(point: Offset): Offset {
        val rotated = size.rotated(rotation)
        val mapped = floatArrayOf(point.x, point.y)
        rotationMatrix(size, rotation).mapPoints(mapped)
        return Offset(mapped[0] * coordinates.size.width / rotated.width, mapped[1] * coordinates.size.height / rotated.height)
    }

    fun pagePoint(position: Offset): Point {
        val rotated = size.rotated(rotation)
        val scaled = floatArrayOf(
            position.x / coordinates.size.width * rotated.width,
            position.y / coordinates.size.height * rotated.height,
        )
        val inverse = Matrix()
        rotationMatrix(size, rotation).invert(inverse)
        inverse.mapPoints(scaled)
        return Point(
            scaled[0].roundToInt().coerceIn(0, size.width - 1),
            scaled[1].roundToInt().coerceIn(0, size.height - 1),
        )
    }
}

private class PdfViewport {
    var coordinates: LayoutCoordinates? = null
}

@Composable
internal fun Modifier.pdfSelectionGestures(
    pdf: NativePdf, scroll: LazyListState, horizontal: Boolean, mode: PdfSelectionMode,
    layouts: Map<Int, PdfPageLayout>, pages: Map<Int, JSONObject>,
    handles: List<PdfHandle>,
    onZoomStart: (Offset) -> Unit, onZoom: (Float, Offset) -> Unit,
    canPanAcross: () -> Boolean, onPanAcross: (Float) -> Float, onDoubleTap: (Offset) -> Unit,
    onLink: (Int) -> Unit, onPreview: (PdfSelection?) -> Unit, onSelect: (PdfSelection, Offset) -> Unit, report: (String) -> Unit,
): Modifier {
    val viewport = remember(scroll) { PdfViewport() }
    val pageRecords by rememberUpdatedState(pages)
    val paint by rememberUpdatedState(onPreview)
    val deliver by rememberUpdatedState(onSelect)
    val showError by rememberUpdatedState(report)
    val markers by rememberUpdatedState(handles)
    val zoomBegin by rememberUpdatedState(onZoomStart)
    val zoomBy by rememberUpdatedState(onZoom)
    val canPan by rememberUpdatedState(canPanAcross)
    val panAcross by rememberUpdatedState(onPanAcross)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val followLink by rememberUpdatedState(onLink)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    val mainDirection = if (horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    return onGloballyPositioned { viewport.coordinates = it }
        .pointerInput(pdf, scroll, horizontal, mode, mainDirection, decay) {
            val textMode = mode == PdfSelectionMode.Text
            fun window(position: Offset) = viewport.coordinates?.takeIf { it.isAttached }?.localToWindow(position)
            fun endpoint(position: Offset, insidePage: Boolean = false): PdfEndpoint? {
                val window = window(position) ?: return null
                var nearest: PdfEndpoint? = null
                var nearestDistance = Float.POSITIVE_INFINITY
                for (item in scroll.layoutInfo.visibleItemsInfo) {
                    val number = item.index + 1
                    val layout = layouts[number] ?: continue
                    val page = layout.coordinates
                    if (!page.isAttached || page.size.width == 0 || page.size.height == 0) continue
                    val local = page.windowToLocal(window)
                    if (local.x >= 0 && local.x < page.size.width && local.y >= 0 && local.y < page.size.height) {
                        return PdfEndpoint(number, layout.pagePoint(local))
                    }
                    if (insidePage) continue
                    val axis = if (horizontal) local.x else local.y
                    val extent = if (horizontal) page.size.width else page.size.height
                    val distance = abs(axis - axis.coerceIn(0f, extent.toFloat()))
                    if (distance < nearestDistance) {
                        nearest = PdfEndpoint(number, layout.pagePoint(local))
                        nearestDistance = distance
                    }
                }
                return nearest
            }
            fun regionSelection(start: PdfEndpoint, position: Offset): PdfSelection? {
                val window = window(position) ?: return null
                val layout = layouts[start.page]?.takeIf { it.coordinates.isAttached } ?: return null
                val first = start.point
                val last = layout.pagePoint(layout.coordinates.windowToLocal(window))
                val box = RectF(
                    minOf(first.x, last.x).toFloat(), minOf(first.y, last.y).toFloat(),
                    maxOf(first.x, last.x).toFloat(), maxOf(first.y, last.y).toFloat(),
                )
                if (box.width() <= 1 || box.height() <= 1) return null
                return PdfSelection(listOf(PdfRange(start.page, "", listOf(box))))
            }

            var resolving: Job? = null
            var fling: Job? = null
            var lastTapTime = 0L
            var lastTap = Offset.Unspecified
            val edge = 52.dp.toPx()
            val maxScroll = 900.dp.toPx()
            fun handleStart(position: Offset): PdfEndpoint? {
                val finger = window(position) ?: return null
                if (markers.size != 2) return null
                val hit = markers.indexOfFirst { handle ->
                    val layout = layouts[handle.page]?.takeIf { it.coordinates.isAttached } ?: return@indexOfFirst false
                    val position = layout.coordinates.localToWindow(layout.localOffset(handle.point))
                    (position - finger).getDistance() <= 24.dp.toPx()
                }
                if (hit < 0) return null
                val opposite = markers[1 - hit]
                return PdfEndpoint(opposite.page, Point(opposite.point.x.roundToInt(), (opposite.point.y - 1).roundToInt()))
            }
            fun startZoom(point: Offset) {
                resolving?.cancel()
                lastTapTime = 0L
                window(point)?.let(zoomBegin)
            }
            fun transform(factor: Float, point: Offset) {
                window(point)?.let { zoomBy(factor, it) }
            }
            fun pan(delta: Offset, scrollAlong: (Float) -> Float): Boolean {
                val main = if (horizontal) delta.x else delta.y
                val cross = if (horizontal) delta.y else delta.x
                val scrolled = scrollAlong(main * mainDirection)
                val panned = panAcross(cross)
                return scrolled != 0f || panned != 0f
            }
            fun flingPan(velocity: Offset): Job = scope.launch {
                scroll.scroll {
                    var previous = Offset.Zero
                    AnimationState(Offset.VectorConverter, Offset.Zero, velocity).animateDecay(decay) {
                        val delta = value - previous
                        if (delta != Offset.Zero && !pan(delta, ::scrollBy)) cancelAnimation()
                        previous = value
                    }
                }
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fling?.cancel()
                    val fixed = if (textMode) handleStart(down.position) else null
                    val touched = endpoint(down.position, insidePage = true)
                    var event: PointerEvent = currentEvent
                    var dragStart: Offset? = null
                    if (fixed == null) {
                        val didEnd = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (event.pointerCount() != 1 || change == null) break
                                val delta = change.position - down.position
                                val distance = delta.getDistance()
                                if (distance > viewConfiguration.touchSlop) {
                                    if (canPan()) {
                                        dragStart = down.position + delta * (viewConfiguration.touchSlop / distance)
                                    }
                                    break
                                }
                            }
                            true
                        } == true
                        if (didEnd || touched == null) {
                            if (event.pointerCount() == 0) {
                                val up = event.changes.firstOrNull { it.id == down.id }
                                val link = touched?.let { layouts[it.page]?.linkAt(it.point) }
                                if (link != null && up != null && up.changedToUpIgnoreConsumed() &&
                                    (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                                    // Let a note button on top of the page claim the tap first.
                                    awaitPointerEvent(PointerEventPass.Final)
                                    if (!down.isConsumed && !up.isConsumed) {
                                        up.consume()
                                        resolving?.cancel()
                                        followLink(link.targetPage)
                                    }
                                    lastTapTime = 0L
                                    lastTap = Offset.Unspecified
                                    return@awaitEachGesture
                                }
                                val now = up?.uptimeMillis ?: 0L
                                if (touched != null && lastTap.isSpecified && now - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                                    (down.position - lastTap).getDistance() < 40.dp.toPx()) {
                                    up?.consume()
                                    window(down.position)?.let(doubleTap)
                                    lastTapTime = 0L
                                    lastTap = Offset.Unspecified
                                } else {
                                    lastTap = down.position
                                    lastTapTime = now
                                }
                                return@awaitEachGesture
                            }
                            lastTapTime = 0L
                            var lastPosition = dragStart ?: down.position
                            val tracker = dragStart?.let {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.stopScroll(MutatePriority.UserInput) }
                                VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                            }
                            while (event.pointerCount() > 0) {
                                if (event.pointerCount() >= 2) {
                                    followReaderPinch(event, ::startZoom, ::transform)
                                    break
                                }
                                if (tracker != null) event.changes.firstOrNull { it.id == down.id }?.let { change ->
                                    pan(change.position - lastPosition, scroll::dispatchRawDelta)
                                    lastPosition = change.position
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                                event = awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (tracker != null && event.pointerCount() == 0) {
                                val up = event.changes.firstOrNull { it.id == down.id && it.changedToUpIgnoreConsumed() }
                                if (up != null) {
                                    tracker.addPosition(up.uptimeMillis, up.position)
                                    val max = viewConfiguration.maximumFlingVelocity
                                    val velocity = tracker.calculateVelocity(Velocity(max, max))
                                    val initialVelocity = Offset(velocity.x, velocity.y)
                                    up.consume()
                                    if (initialVelocity.getDistance() >= viewConfiguration.minimumFlingVelocity) {
                                        fling = flingPan(initialVelocity)
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }
                    }
                    val start = fixed ?: touched ?: return@awaitEachGesture
                    if (fixed == null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    lastTapTime = 0L
                    var pointer = down.position
                    resolving?.cancel()
                    val records = pageRecords
                    val wholePages = mutableMapOf<Int, PdfRange?>()
                    val endpoints = if (textMode) Channel<PdfEndpoint>(Channel.CONFLATED) else null
                    val preview = if (endpoints != null) scope.launch {
                        for (end in endpoints) {
                            val result = try {
                                withContext(Dispatchers.IO) { pdf.selectPages(start, end, records, wholePages) }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { null }
                            paint(result)
                        }
                    } else null
                    endpoints?.trySend(if (fixed == null) start else endpoint(down.position) ?: start)
                    val autoScroll = if (endpoints != null) scope.launch {
                        var previous = withFrameNanos { it }
                        var lastPreviewAt = previous
                        var lastEndpoint = start
                        while (true) {
                            val time = withFrameNanos { it }
                            val elapsed = ((time - previous) / 1_000_000_000f).coerceAtMost(.05f)
                            previous = time
                            val extent = if (horizontal) size.width else size.height
                            val axis = if (horizontal) pointer.x else pointer.y
                            val leading = (1f - axis / edge).coerceIn(0f, 1f)
                            val trailing = (1f - (extent - axis) / edge).coerceIn(0f, 1f)
                            val delta = (trailing - leading) * maxScroll * elapsed
                            if (delta != 0f) scroll.scrollBy(delta)
                            if (time - lastPreviewAt >= 50_000_000L) {
                                lastPreviewAt = time
                                val end = endpoint(pointer)
                                if (end != null && end != lastEndpoint) {
                                    lastEndpoint = end
                                    endpoints.trySend(end)
                                }
                            }
                        }
                    } else null
                    try {
                        // A long press has claimed the gesture. Consume moves before the lazy list sees them.
                        while (true) {
                            event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.pointerCount() >= 2) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            pointer = change.position
                            change.consume()
                            if (change.changedToUpIgnoreConsumed()) break
                            if (!textMode) paint(regionSelection(start, pointer))
                        }
                    } finally {
                        autoScroll?.cancel()
                        endpoints?.cancel()
                        preview?.cancel()
                        paint(null)
                    }
                    if (event.pointerCount() >= 2) {
                        followReaderPinch(event, ::startZoom, ::transform)
                        return@awaitEachGesture
                    }
                    val position = window(pointer) ?: return@awaitEachGesture
                    if (!textMode) {
                        regionSelection(start, pointer)?.let { deliver(it, position) }
                        return@awaitEachGesture
                    }
                    val end = endpoint(pointer) ?: return@awaitEachGesture
                    resolving = scope.launch {
                        try {
                            preview?.join()
                            val selection = withContext(Dispatchers.IO) { pdf.selectPages(start, end, records, wholePages) }
                            if (selection != null) deliver(selection, position)
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { showError(error.message ?: "选区无法读取。") }
                    }
                }
            } finally {
                fling?.cancel()
                resolving?.cancel()
            }
        }
}

private suspend fun NativePdf.selectPages(
    start: PdfEndpoint, end: PdfEndpoint, indexed: Map<Int, JSONObject>, wholePages: MutableMap<Int, PdfRange?>,
): PdfSelection? {
    val (first, last) = if (start.page <= end.page) start to end else end to start
    val fragments = (first.page..last.page).mapNotNull { page ->
        coroutineContext.ensureActive()
        val from = first.point.takeIf { page == first.page }
        val to = last.point.takeIf { page == last.page }
        val entirePage = from == null && to == null
        if (entirePage && page in wholePages) return@mapNotNull wholePages[page]
        val record = indexed[page]
        val ocr = record?.takeIf { it.optString("source") == "ocr" }
        val fragment = if (ocr != null) {
            ocr.ocrRange(from, to)
        } else {
            require(canSelectText || record?.optString("status") == "empty") {
                "当前系统无法读取第 $page 页文字，请先识别该页。"
            }
            select(page, from, to)
        }?.takeIf { it.quote.isNotBlank() }
        if (entirePage) wholePages[page] = fragment
        fragment
    }
    return fragments.takeIf { it.isNotEmpty() }?.let(::PdfSelection)
}
