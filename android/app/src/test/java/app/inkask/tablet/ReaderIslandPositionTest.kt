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
        val placement = ReaderIslandPosition.fromDrag(Offset(421f, 294f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Free, placement.edge)
        assertEquals(IntOffset(321, 267), placement.offset(viewport, horizontal, 10))
    }

    @Test fun verticalEdgesPinOnlyTheHorizontalAxis() {
        val left = ReaderIslandPosition.fromDrag(Offset(20f, 297f), viewport, 42f)
        val right = ReaderIslandPosition.fromDrag(Offset(970f, 469f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Left, left.edge)
        assertEquals(ReaderIslandEdge.Right, right.edge)
        assertEquals(IntOffset(10, 197), left.offset(viewport, vertical, 10))
        assertEquals(IntOffset(936, 369), right.offset(viewport, vertical, 10))
    }

    @Test fun horizontalEdgesPinOnlyTheVerticalAxis() {
        val top = ReaderIslandPosition.fromDrag(Offset(382f, 16f), viewport, 42f)
        val bottom = ReaderIslandPosition.fromDrag(Offset(611f, 777f), viewport, 42f)

        assertEquals(ReaderIslandEdge.Top, top.edge)
        assertEquals(ReaderIslandEdge.Bottom, bottom.edge)
        assertEquals(IntOffset(282, 10), top.offset(viewport, horizontal, 10))
        assertEquals(IntOffset(511, 736), bottom.offset(viewport, horizontal, 10))
    }

    @Test fun draggedCircleFollowsPointerAndStaysOnScreen() {
        val circle = IntSize(54, 54)
        val central = ReaderIslandPosition.fromDrag(Offset(469f, 231f), viewport, 42f)
        val corner = ReaderIslandPosition(0f, 1f, ReaderIslandEdge.Free)

        assertEquals(IntOffset(442, 204), central.offset(viewport, circle, 10))
        assertEquals(IntOffset(10, 736), corner.offset(viewport, circle, 10))
    }

    @Test fun aSmallLeadAtTheCornerKeepsThePreviewStable() {
        val nearCorner = Offset(35f, 30f)
        val left = ReaderIslandPosition.fromDrag(nearCorner, viewport, 52f, ReaderIslandEdge.Left, 8f)
        val top = ReaderIslandPosition.fromDrag(Offset(44f, 30f), viewport, 52f, left.edge, 8f)

        assertEquals(ReaderIslandEdge.Left, left.edge)
        assertEquals(ReaderIslandEdge.Top, top.edge)
    }

    @Test fun thePreviewReleasesOutsideItsHysteresisBand() {
        val held = ReaderIslandPosition.fromDrag(Offset(57f, 250f), viewport, 52f, ReaderIslandEdge.Left, 8f)
        val free = ReaderIslandPosition.fromDrag(Offset(61f, 250f), viewport, 52f, held.edge, 8f)

        assertEquals(ReaderIslandEdge.Left, held.edge)
        assertEquals(ReaderIslandEdge.Free, free.edge)
    }

    @Test fun partialAttractionMovesOnlyTheAxisPerpendicularToTheEdge() {
        val left = ReaderIslandPosition.fromDrag(Offset(51f, 400f), viewport, 52f)

        assertEquals(IntOffset(24, 300), left.offset(viewport, vertical, 10, attraction = 0f))
        assertEquals(IntOffset(17, 300), left.offset(viewport, vertical, 10, attraction = .5f))
        assertEquals(IntOffset(10, 300), left.offset(viewport, vertical, 10))
    }
}
