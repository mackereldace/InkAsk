package app.inkask.tablet

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfZoomPositionTest {
    private val horizontalPadding = PdfScrollPadding(40, 40)

    @Test fun firstPageCanMoveRightOfTheNormalLeadingPadding() {
        val position = pdfZoomPosition(660.4f, 2560, horizontalPadding, 0f, null)

        assertEquals(660, position.padding.leading)
        assertEquals(0, position.offset)
    }

    @Test fun preservesTheOriginAtTheEndOfTheDocument() {
        val position = pdfZoomPosition(388f, 2560, horizontalPadding, null, 1420f)

        assertEquals(752, position.padding.trailing)
        assertEquals(388, position.padding.leading - position.offset)
    }

    @Test fun keepsNormalPaddingWhenTheAnchorIsInsideTheDocument() {
        val position = pdfZoomPosition(460f, 2560, horizontalPadding, 900f, 2500f)

        assertEquals(horizontalPadding, position.padding)
        assertEquals(460, position.padding.leading - position.offset)
    }
}
