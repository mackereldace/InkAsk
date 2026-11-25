package app.inkask.tablet

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val ReaderIslandClearance = 70.dp
private val IslandSnapDistance = 52.dp
private val IslandEdgeHysteresis = 8.dp
private val IslandMargin = 10.dp
private val IslandHandleSize = 54.dp
private val IslandFlingMinSpeed = 360.dp
private val IslandFlingMaxSpeed = 5200.dp
private val IslandFlingBounceSpeed = 1000.dp
private val IslandFlingEasterEggSpeed = 2300.dp
private val IslandFlingStopSpeed = 38.dp

internal enum class ReaderIslandEdge {
    Free, Top, Bottom, Left, Right;

    val vertical get() = this == Left || this == Right

    fun distanceFrom(at: Offset, viewport: IntSize): Float = when (this) {
        Free -> Float.POSITIVE_INFINITY
        Top -> at.y
        Bottom -> viewport.height - at.y
        Left -> at.x
        Right -> viewport.width - at.x
    }
}

internal data class ReaderIslandPosition(
    val x: Float,
    val y: Float,
    val edge: ReaderIslandEdge,
    private val freeVertical: Boolean = false,
) {
    val vertical get() = if (edge == ReaderIslandEdge.Free) freeVertical else edge.vertical

    fun center(viewport: IntSize) = Offset(x * viewport.width, y * viewport.height)

    fun settled(viewport: IntSize, previousVertical: Boolean, tolerance: Float): ReaderIslandPosition {
        if (edge != ReaderIslandEdge.Free) return this
        val point = center(viewport)
        val sideDistance = minOf(point.x, viewport.width - point.x)
        val topOrBottomDistance = minOf(point.y, viewport.height - point.y)
        val vertical = when {
            sideDistance + tolerance < topOrBottomDistance -> true
            topOrBottomDistance + tolerance < sideDistance -> false
            else -> previousVertical
        }
        return copy(freeVertical = vertical)
    }

    fun offset(viewport: IntSize, island: IntSize, margin: Int, attraction: Float = 1f): IntOffset {
        fun axis(space: Int, length: Int, midpoint: Float, atStart: Boolean, atEnd: Boolean): Int {
            val max = (space - length).coerceAtLeast(0)
            val gap = margin.coerceAtMost(max / 2)
            val free = (midpoint - length / 2f).coerceIn(gap.toFloat(), (max - gap).toFloat())
            val docked = when {
                atStart -> gap.toFloat()
                atEnd -> (max - gap).toFloat()
                else -> free
            }
            return (free + (docked - free) * attraction).roundToInt()
        }
        val center = center(viewport)
        return IntOffset(
            axis(viewport.width, island.width, center.x, edge == ReaderIslandEdge.Left, edge == ReaderIslandEdge.Right),
            axis(viewport.height, island.height, center.y, edge == ReaderIslandEdge.Top, edge == ReaderIslandEdge.Bottom),
        )
    }

    companion object {
        fun fromDrag(
            at: Offset,
            viewport: IntSize,
            snapDistance: Float,
            previousEdge: ReaderIslandEdge = ReaderIslandEdge.Free,
            hysteresis: Float = 0f,
        ): ReaderIslandPosition {
            val bounds = IntSize(viewport.width.coerceAtLeast(1), viewport.height.coerceAtLeast(1))
            val point = Offset(at.x.coerceIn(0f, bounds.width.toFloat()), at.y.coerceIn(0f, bounds.height.toFloat()))
            val nearest = ReaderIslandEdge.entries.minBy { it.distanceFrom(point, bounds) }
            val distance = nearest.distanceFrom(point, bounds)
            val previousDistance = previousEdge.distanceFrom(point, bounds)
            val edge = when {
                previousDistance <= snapDistance + hysteresis && previousDistance <= distance + hysteresis -> previousEdge
                distance <= snapDistance -> nearest
                else -> ReaderIslandEdge.Free
            }
            return ReaderIslandPosition(point.x / bounds.width, point.y / bounds.height, edge)
        }
    }
}

@Composable
internal fun ReaderIsland(
    position: ReaderIslandPosition, onPlace: (ReaderIslandPosition) -> Unit, content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val place by rememberUpdatedState(onPlace)
    val previousVertical by rememberUpdatedState(position.vertical)
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var island by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragging by remember { mutableStateOf<ReaderIslandPosition?>(null) }
    var gliding by remember { mutableStateOf<ReaderIslandPosition?>(null) }
    var glideJob by remember { mutableStateOf<Job?>(null) }
    val moving = dragging ?: gliding
    val placement = moving ?: position
    val dockProgress = animateFloatAsState(
        targetValue = if (moving == null) 1f else 0f,
        animationSpec = tween(140),
        label = "Reader island docking",
    )

    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { viewport = it },
        content = {
            Surface(
                modifier = Modifier.onGloballyPositioned { island = it }.pointerInput(Unit) {
                    val snapDistance = IslandSnapDistance.toPx()
                    val hysteresis = IslandEdgeHysteresis.toPx()

                    fun resting(at: ReaderIslandPosition, size: IntSize, wasVertical: Boolean): ReaderIslandPosition {
                        val dock = if (at.edge == ReaderIslandEdge.Free) {
                            ReaderIslandPosition.fromDrag(at.center(size), size, snapDistance)
                        } else at
                        return dock.settled(size, wasVertical, hysteresis)
                    }

                    fun release(target: ReaderIslandPosition, size: IntSize, handle: LayoutCoordinates, velocity: Offset) {
                        val launch = readerIslandLaunch(
                            velocity, target.edge, IslandFlingMinSpeed.toPx(),
                            IslandFlingMaxSpeed.toPx(), IslandFlingBounceSpeed.toPx(), IslandFlingEasterEggSpeed.toPx(),
                        )
                        val wasVertical = previousVertical
                        if (launch == null) {
                            place(target.settled(size, wasVertical, hysteresis))
                            return
                        }

                        val initial = target.copy(edge = launch.edge)
                        gliding = initial
                        glideJob = scope.launch {
                            var current = initial
                            var motion = ReaderIslandMotion(target.center(size), launch.velocity)
                            val started = withFrameNanos { it }
                            var lastFrame = started
                            while (motion.velocity.getDistance() >= IslandFlingStopSpeed.toPx()) {
                                val frame = withFrameNanos { it }
                                val elapsed = (frame - started) / 1_000_000_000f
                                if (elapsed > launch.mode.maxSeconds || !handle.isAttached || viewport?.size != size) break
                                val duration = ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, .036f)
                                lastFrame = frame
                                val handleSize = IslandHandleSize.roundToPx()
                                val collisionSize = if (launch.edge == ReaderIslandEdge.Free) {
                                    IntSize(handleSize, handleSize)
                                } else handle.size
                                motion = motion.advance(
                                    duration, size, collisionSize, IslandMargin.toPx(), launch.mode, elapsed,
                                )
                                current = initial.copy(
                                    x = motion.center.x / size.width.coerceAtLeast(1),
                                    y = motion.center.y / size.height.coerceAtLeast(1),
                                )
                                gliding = current
                            }
                            val finalSize = viewport?.takeIf { it.isAttached }?.size ?: size
                            place(resting(current, finalSize, wasVertical))
                            gliding = null
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val root = viewport?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        val handle = island?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        gliding?.let { interrupted ->
                            glideJob?.cancel()
                            gliding = null
                            place(resting(interrupted, root.size, previousVertical))
                            down.consume()
                        }

                        fun pointerPosition(local: Offset) = root.windowToLocal(handle.localToWindow(local))
                        val velocity = ReaderIslandVelocity(down.uptimeMillis, pointerPosition(down.position))
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                                if (event.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                                val released = change.changedToUpIgnoreConsumed()
                                if (released && change.isConsumed) return@awaitEachGesture
                                if (!root.isAttached || !handle.isAttached) return@awaitEachGesture
                                val at = pointerPosition(change.position)
                                change.historical.forEach { velocity.sample(it.uptimeMillis, pointerPosition(it.position)) }
                                if (!released) velocity.sample(change.uptimeMillis, at)
                                if (dragging != null || (!released && (change.position - down.position).getDistance() > viewConfiguration.touchSlop)) {
                                    val size = root.size
                                    val target = ReaderIslandPosition.fromDrag(
                                        at, size, snapDistance, dragging?.edge ?: ReaderIslandEdge.Free, hysteresis,
                                    )
                                    dragging = target
                                    change.consume()

                                    if (released) release(target, size, handle, velocity.release(change.uptimeMillis, at))
                                }
                                if (released || !change.pressed) return@awaitEachGesture
                            }
                        } finally {
                            dragging = null
                        }
                    }
                },
                color = Paper,
                shape = CircleShape,
                border = BorderStroke(1.dp, Line),
                shadowElevation = 8.dp,
            ) {
                Box(
                    Modifier.animateContentSize(tween(140), alignment = Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        moving?.edge == ReaderIslandEdge.Free -> Box(Modifier.size(IslandHandleSize), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.inkask_ic_move), contentDescription = "移动浮岛",
                                tint = Green, modifier = Modifier.size(22.dp))
                        }
                        placement.vertical -> Column(
                            Modifier.verticalScroll(rememberScrollState()).padding(5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            content = { content() },
                        )
                        else -> Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = { content() },
                        )
                    }
                }
            }
        },
    ) { items, constraints ->
        val viewportSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val toolbar = items.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val edgeDistance = placement.edge.distanceFrom(placement.center(viewportSize), viewportSize)
        val pull = (1f - edgeDistance / (2 * IslandSnapDistance.toPx())).coerceIn(0f, 1f)
        val settle = if (moving == null) dockProgress.value else 0f
        val offset = placement.offset(
            viewportSize, IntSize(toolbar.width, toolbar.height), IslandMargin.roundToPx(), pull + (1f - pull) * settle,
        )
        layout(viewportSize.width, viewportSize.height) { toolbar.place(offset) }
    }
}
