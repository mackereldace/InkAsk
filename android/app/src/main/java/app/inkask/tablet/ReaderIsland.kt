package app.inkask.tablet

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

internal enum class ReaderIslandEdge {
    Free, Top, Bottom, Left, Right;

    val vertical get() = this == Left || this == Right
}

internal data class ReaderIslandPosition(val x: Float, val y: Float, val edge: ReaderIslandEdge) {
    fun offset(viewport: IntSize, island: IntSize, margin: Int, dragging: Offset? = null): IntOffset {
        fun axis(space: Int, length: Int, fraction: Float, drag: Float?, atStart: Boolean, atEnd: Boolean): Int {
            val max = (space - length).coerceAtLeast(0)
            val gap = margin.coerceAtMost(max / 2)
            val candidate = when {
                drag != null -> drag - length / 2f
                atStart -> gap.toFloat()
                atEnd -> (max - gap).toFloat()
                else -> fraction * space - length / 2f
            }
            return candidate.roundToInt().coerceIn(gap, max - gap)
        }
        return IntOffset(
            axis(viewport.width, island.width, x, dragging?.x, edge == ReaderIslandEdge.Left, edge == ReaderIslandEdge.Right),
            axis(viewport.height, island.height, y, dragging?.y, edge == ReaderIslandEdge.Top, edge == ReaderIslandEdge.Bottom),
        )
    }

    companion object {
        fun dropped(at: Offset, viewport: IntSize, snapDistance: Float): ReaderIslandPosition {
            val width = viewport.width.coerceAtLeast(1).toFloat()
            val height = viewport.height.coerceAtLeast(1).toFloat()
            val x = at.x.coerceIn(0f, width)
            val y = at.y.coerceIn(0f, height)
            val nearest = listOf(
                ReaderIslandEdge.Left to x, ReaderIslandEdge.Right to (width - x),
                ReaderIslandEdge.Top to y, ReaderIslandEdge.Bottom to (height - y),
            ).minBy { it.second }
            return ReaderIslandPosition(x / width, y / height,
                if (nearest.second <= snapDistance) nearest.first else ReaderIslandEdge.Free)
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
    var dragging by remember { mutableStateOf<Offset?>(null) }

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
                                    dragging = at
                                    change.consume()
                                    if (released) place(ReaderIslandPosition.dropped(at, root.size, 42.dp.toPx()))
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
                when {
                    dragging != null -> Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.inkask_ic_move), contentDescription = "移动浮岛",
                            tint = Green, modifier = Modifier.size(22.dp))
                    }
                    position.edge.vertical -> Column(
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
        },
    ) { items, constraints ->
        val viewportSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val toolbar = items.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val offset = position.offset(viewportSize, IntSize(toolbar.width, toolbar.height), 10.dp.roundToPx(), dragging)
        layout(viewportSize.width, viewportSize.height) { toolbar.place(offset) }
    }
}
