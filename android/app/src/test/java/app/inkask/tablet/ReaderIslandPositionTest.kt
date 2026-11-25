package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderIslandPositionTest {
    private val viewport = IntSize(1000, 800)
    private val horizontal = IntSize(200, 54)
    private val vertical = IntSize(54, 200)

    @Test fun freePlacementPreservesBothCoordinates() {
        val placement = ReaderIslandPosition.dropped(Offset(421f, 294f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Free, placement.edge)
        assertEquals(IntOffset(321, 267), placement.offset(viewport, horizontal, 10))
    }

    @Test fun verticalEdgesPinOnlyTheHorizontalAxis() {
        val left = ReaderIslandPosition.dropped(Offset(20f, 297f), viewport, 42f)
        val right = ReaderIslandPosition.dropped(Offset(970f, 469f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Left, left.edge)
        assertEquals(ReaderIslandEdge.Right, right.edge)
        assertEquals(IntOffset(10, 197), left.offset(viewport, vertical, 10))
        assertEquals(IntOffset(936, 369), right.offset(viewport, vertical, 10))
    }

    @Test fun horizontalEdgesPinOnlyTheVerticalAxis() {
        val top = ReaderIslandPosition.dropped(Offset(382f, 16f), viewport, 42f)
        val bottom = ReaderIslandPosition.dropped(Offset(611f, 777f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Top, top.edge)
        assertEquals(ReaderIslandEdge.Bottom, bottom.edge)
        assertEquals(IntOffset(282, 10), top.offset(viewport, horizontal, 10))
        assertEquals(IntOffset(511, 736), bottom.offset(viewport, horizontal, 10))
    }

    @Test fun draggedCircleFollowsPointerAndStaysOnScreen() {
        val placement = ReaderIslandPosition(.5f, 1f, ReaderIslandEdge.Bottom)
        val circle = IntSize(54, 54)

        assertEquals(IntOffset(442, 204), placement.offset(viewport, circle, 10, Offset(469f, 231f)))
        assertEquals(IntOffset(10, 736), placement.offset(viewport, circle, 10, Offset(0f, 799f)))
    }
}
