package app.inkask.tablet

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
}
