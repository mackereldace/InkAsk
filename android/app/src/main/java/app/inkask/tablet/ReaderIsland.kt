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
import kotlin.math.roundToInt

internal val ReaderIslandClearance = 70.dp
private val IslandSnapDistance = 52.dp
private val IslandEdgeHysteresis = 8.dp

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

internal data class ReaderIslandPosition(val x: Float, val y: Float, val edge: ReaderIslandEdge) {
    fun center(viewport: IntSize) = Offset(x * viewport.width, y * viewport.height)

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
    val place by rememberUpdatedState(onPlace)
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var island by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragging by remember { mutableStateOf<ReaderIslandPosition?>(null) }
    val placement = dragging ?: position
    val dockProgress = animateFloatAsState(
        targetValue = if (dragging == null) 1f else 0f,
        animationSpec = tween(140),
        label = "Reader island docking",
    )

    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { viewport = it },
        content = {
            Surface(
                modifier = Modifier.onGloballyPositioned { island = it }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                                if (event.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                                val released = change.changedToUpIgnoreConsumed()
                                if (dragging != null || (!released && (change.position - down.position).getDistance() > viewConfiguration.touchSlop)) {
                                    val root = viewport?.takeIf { it.isAttached } ?: return@awaitEachGesture
                                    val handle = island?.takeIf { it.isAttached } ?: return@awaitEachGesture
                                    val at = root.windowToLocal(handle.localToWindow(change.position))
                                    val target = ReaderIslandPosition.fromDrag(
                                        at, root.size, IslandSnapDistance.toPx(),
                                        dragging?.edge ?: ReaderIslandEdge.Free, IslandEdgeHysteresis.toPx(),
                                    )
                                    dragging = target
                                    change.consume()
                                    if (released) place(target)
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
                        dragging?.edge == ReaderIslandEdge.Free -> Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.inkask_ic_move), contentDescription = "移动浮岛",
                                tint = Green, modifier = Modifier.size(22.dp))
                        }
                        placement.edge.vertical -> Column(
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
        val settle = if (dragging == null) dockProgress.value else 0f
        val offset = placement.offset(
            viewportSize, IntSize(toolbar.width, toolbar.height), 10.dp.roundToPx(), pull + (1f - pull) * settle,
        )
        layout(viewportSize.width, viewportSize.height) { toolbar.place(offset) }
    }
}
