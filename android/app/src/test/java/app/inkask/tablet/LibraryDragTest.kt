package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDragTest {
    private val source = LibraryItem("source", true)
    private val nested = LibraryItem("nested", true)
    private val pdf = LibraryItem("one", false)
    private val other = LibraryItem("two", false)
    private val rules = LibraryDropRules(Library(
        documents = listOf(PdfItem("one", "One.pdf", 1, 1), PdfItem("two", "Two.pdf", 1, 1)),
        folders = listOf(
            Folder("source", "Research", null),
            Folder("nested", "Notes", "source"),
            Folder("target", "Archive", null),
            Folder("conflict", "research", "target"),
        ),
        membership = mapOf("one" to "nested"),
    ))

    @Test fun dragGroupCanMoveIntoFolderOrOutToRoot() {
        assertTrue(rules.canDrop(setOf(pdf, other), "target"))
        assertTrue(rules.canDrop(setOf(pdf), null))
        assertTrue(rules.canDrop(setOf(nested), "target"))
    }

    @Test fun invalidAndUnchangedDestinationsDoNotHighlight() {
        assertFalse(rules.canDrop(setOf(source), "source"))
        assertFalse(rules.canDrop(setOf(source), "nested"))
        assertFalse(rules.canDrop(setOf(other), "missing"))
        assertFalse(rules.canDrop(setOf(other), null))
        assertFalse(rules.canDrop(setOf(source), "target"))
    }

    @Test fun childrenOfASelectedFolderDoNotMakeItsCurrentParentAnActiveDestination() {
        assertFalse(rules.canDrop(setOf(source, nested, pdf), null))
        assertTrue(rules.canDrop(setOf(nested, pdf), null))
    }

    @Test fun modifierSelectionExtendsFromAnAnchorAndPreservesEarlierItemsWhenAdditive() {
        val order = listOf(source, nested, pdf, other)

        assertEquals(setOf(other, source, nested, pdf),
            librarySelection(setOf(other), order, pdf, source, additive = true, range = true))
        assertEquals(setOf(nested, pdf),
            librarySelection(setOf(other), order, nested, pdf, additive = false, range = true))
        assertEquals(setOf(source),
            librarySelection(setOf(source, pdf), order, pdf, source, additive = true, range = false))
    }

    @Test fun edgeScrollingLetsTheUserHoldOverAFolder() {
        val bounds = Rect(0f, 100f, 400f, 700f)

        assertEquals(0f, libraryDragEdgeStrength(Offset(200f, 122f), bounds, 44f, 10f, true), .001f)
        assertEquals(-.25f, libraryDragEdgeStrength(Offset(200f, 122f), bounds, 44f, 10f, false), .001f)
        assertEquals(1f, libraryDragEdgeStrength(Offset(200f, 705f), bounds, 44f, 10f, true), .001f)
        assertEquals(0f, libraryDragEdgeStrength(Offset(420f, 705f), bounds, 44f, 10f, false), .001f)
    }

    @Test fun marqueeWorksInReverseAndStopsAtTheViewportEdges() {
        val viewport = Rect(50f, 90f, 550f, 700f)

        assertEquals(Rect(50f, 180f, 200f, 350f), libraryMarqueeBounds(Offset(200f, 350f), Offset(-20f, 180f), viewport))
        assertEquals(Rect(200f, 350f, 550f, 700f), libraryMarqueeBounds(Offset(200f, 350f), Offset(800f, 900f), viewport))
    }

    @Test fun manualPlacementKeepsGroupSpacingAndLeavesOtherIconsInPlace() {
        val order = listOf(source, pdf, other)
        val before = LibraryIconLayout(790f, 1f, order, emptyMap())
        val at = before.spots.getValue(pdf)
        val saved = requireNotNull(before.place(setOf(pdf, other), pdf, at + Offset(103f, 213f)))
        val after = LibraryIconLayout(790f, 1f, order, saved)

        assertEquals(0f, (before.spots.getValue(source) - after.spots.getValue(source)).getDistance(), .01f)
        assertEquals(0f, (at + Offset(41f, 177f) - after.spots.getValue(pdf)).getDistance(), .01f)
        val spacing = before.spots.getValue(other) - at
        assertEquals(0f, (after.spots.getValue(other) - after.spots.getValue(pdf) - spacing).getDistance(), .01f)
        assertEquals(listOf(source, pdf, other), after.spots.keys.toList())
    }

    @Test fun automaticPlacementSkipsAnOccupiedSlot() {
        val default = LibraryIconLayout(620f, 1f, listOf(pdf), emptyMap())
        val center = default.spots.getValue(pdf) + Offset(62f, 160f)
        val saved = requireNotNull(default.place(setOf(pdf), pdf, center))
        val fifth = LibraryItem("fifth", false)
        val fourth = LibraryItem("fourth", false)
        val after = LibraryIconLayout(620f, 1f, listOf(pdf, source, nested, other, fourth, fifth), saved)

        assertEquals(140f, after.spots.getValue(pdf).y, .01f)
        assertEquals(16f, after.spots.getValue(source).y, .01f)
        assertEquals(140f, after.spots.getValue(fifth).y, .01f)
        assertTrue(after.spots.getValue(fifth).x > after.spots.getValue(pdf).x)
    }

    @Test fun aLeftwardDropStillPushesRightAndPreservesGroupSpacing() {
        val order = listOf(source, pdf, other)
        val before = LibraryIconLayout(528f, 1f, order, emptyMap(), snap = true)
        val first = before.spots.getValue(source)
        val saved = requireNotNull(before.place(setOf(pdf, other), pdf, first + Offset(62f, 36f)))
        val after = LibraryIconLayout(528f, 1f, order, saved, snap = true)

        assertEquals(first + Offset(372f, 0f), after.spots.getValue(source))
        assertEquals(first, after.spots.getValue(pdf))
        assertEquals(first + Offset(124f, 0f), after.spots.getValue(other))
        val free = LibraryIconLayout(528f, 1f, order, after.savedPositions())
        assertEquals(after.spots, free.spots)
    }

    @Test fun aRightwardDropPushesTheOccupiedRunAndLeavesTheSourceCellEmpty() {
        val third = LibraryItem("third", false)
        val pinned = mapOf(source to LibraryIconSpot(1f, 16f))
        val order = listOf(pdf, other, third, source)
        val before = LibraryIconLayout(652f, 1f, order, pinned, snap = true)
        val origin = before.spots.getValue(pdf)
        val target = before.spots.getValue(other)
        val saved = requireNotNull(before.place(setOf(pdf), pdf, target + Offset(62f, 36f)))
        val after = LibraryIconLayout(652f, 1f, order, saved, snap = true)

        assertEquals(target, after.spots.getValue(pdf))
        assertEquals(target + Offset(124f, 0f), after.spots.getValue(other))
        assertEquals(target + Offset(248f, 0f), after.spots.getValue(third))
        assertEquals(before.spots.getValue(source), after.spots.getValue(source))
        assertFalse(origin in after.spots.values)
    }

    @Test fun aDownwardDropPushesTheColumnOneCellAtATime() {
        val order = listOf(pdf, source, other)
        val before = LibraryIconLayout(156f, 1f, order, emptyMap(), snap = true)
        val target = before.spots.getValue(source)
        val saved = requireNotNull(before.place(setOf(pdf), pdf, target + Offset(62f, 36f)))
        val after = LibraryIconLayout(156f, 1f, order, saved, snap = true)

        assertEquals(target, after.spots.getValue(pdf))
        assertEquals(target + Offset(0f, 124f), after.spots.getValue(source))
        assertEquals(target + Offset(0f, 248f), after.spots.getValue(other))
        assertFalse(before.spots.getValue(pdf) in after.spots.values)
    }

    @Test fun aPushTurnsDownAtTheRightEdge() {
        val order = listOf(pdf, other)
        val before = LibraryIconLayout(528f, 1f, order, mapOf(other to LibraryIconSpot(1f, 16f)), snap = true)
        val target = before.spots.getValue(other)
        val saved = requireNotNull(before.place(setOf(pdf), pdf, target + Offset(62f, 36f)))
        val after = LibraryIconLayout(528f, 1f, order, saved, snap = true)

        assertEquals(target, after.spots.getValue(pdf))
        assertEquals(target + Offset(0f, 124f), after.spots.getValue(other))
    }

    @Test fun oldFreePositionsProjectToDifferentGridCells() {
        val shared = LibraryIconSpot(.5f, 47f)
        val layout = LibraryIconLayout(528f, 1f, listOf(pdf, other), mapOf(pdf to shared, other to shared), snap = true)

        assertEquals(Offset(264f, 16f), layout.spots.getValue(pdf))
        assertEquals(Offset(140f, 16f), layout.spots.getValue(other))
    }

    @Test fun glideLimitsAccountForTheWidthOfTheWholeDraggedGroup() {
        val layout = LibraryIconLayout(528f, 1f, listOf(source, pdf, other), emptyMap())
        val limit = requireNotNull(layout.movementBounds(setOf(pdf, other), pdf))

        assertEquals(78f, limit.left, .001f)
        assertEquals(326f, limit.right, .001f)
        assertEquals(36f, limit.top, .001f)
    }

    @Test fun motionReflectsOffTheActualCanvasEdges() {
        val room = Rect(15f, 45f, 820f, 730f)
        val start = ReaderIslandMotion(Offset(813f, 600f), Offset(5000f, 120f))
        val after = start.advance(.02f, room, ReaderIslandGlideMode.EasterEgg, .02f)

        assertTrue(after.center.x < start.center.x)
        assertTrue(after.velocity.x < 0f)
        assertTrue(after.velocity.y > 0f)
    }

    @Test fun aFastIconHitsTheFirstObstacleEvenWhenItCouldCrossItInOneFrame() {
        val room = Rect(0f, 0f, 800f, 500f)
        val target = LibraryIconCollider(Rect(200f, 100f, 240f, 180f), 12f)
        val farther = LibraryIconCollider(Rect(260f, 100f, 300f, 180f), 12f)
        val flight = ReaderIslandMotion(Offset(100f, 145f), Offset(5200f, 0f))
        val after = flight.advanceInLibrary(.036f, room, listOf(farther, target), ReaderIslandGlideMode.EasterEgg, .1f)

        assertTrue(after.center.x < target.rect.left)
        assertTrue(after.velocity.x < -4000f)
        assertEquals(0f, after.velocity.y)
    }

    @Test fun aRoundedCornerReflectsDiagonallyAndItsEmptyOuterCornerDoesNotHit() {
        val room = Rect(0f, 0f, 800f, 500f)
        val collider = LibraryIconCollider(Rect(200f, 100f, 270f, 180f), 20f)
        val mode = ReaderIslandGlideMode.EasterEgg
        val graze = ReaderIslandMotion(Offset(190f, 106f), Offset(500f, 0f))
            .advanceInLibrary(.03f, room, listOf(collider), mode, .1f)
        val impact = ReaderIslandMotion(Offset(178f, 78f), Offset(1800f, 1800f))
            .advanceInLibrary(.036f, room, listOf(collider), mode, .1f)

        assertTrue(graze.center.x > collider.rect.left)
        assertTrue(graze.velocity.x > 0f)
        assertTrue(impact.velocity.x < 0f)
        assertTrue(impact.velocity.y < 0f)
    }

    @Test fun aFlingCanLeaveAnExistingOverlap() {
        val room = Rect(0f, 0f, 800f, 500f)
        val collider = LibraryIconCollider(Rect(200f, 100f, 270f, 180f), 20f)
        val after = ReaderIslandMotion(Offset(215f, 145f), Offset(-2000f, 0f))
            .advanceInLibrary(.02f, room, listOf(collider), ReaderIslandGlideMode.Normal, .1f)

        assertTrue(after.center.x < collider.rect.left)
        assertTrue(after.velocity.x < 0f)
    }
}
