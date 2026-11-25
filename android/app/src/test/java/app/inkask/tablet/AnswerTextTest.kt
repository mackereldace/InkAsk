package app.inkask.tablet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerTextTest {
    @Test fun identicalWordsResolveToTheirSelectedOccurrence() {
        val text = AnswerText("一致有界\n\n一致有界")
        val selected = requireNotNull(text.selection(6, 10))
        assertEquals(5, selected.start)
        assertEquals(6..9, text.range(JSONObject().put("answerAnchor", selected.anchor())))
        assertNull(text.range(JSONObject().put("branchQuote", "一致有界")))
    }

    @Test fun preservesWholeFormulaAndOriginalLineBreaks() {
        val source = "\$\$a^2 \\\\\nb^2\$\$"
        val text = AnswerText("前文\n\na^2 b^2",
            listOf(AnswerTextPart(4, 11, AnswerMarkup(source))))
        val selected = requireNotNull(text.selection(5, 6))
        assertEquals(source, selected.quote)
        assertEquals(4..10, text.range(JSONObject().put("answerAnchor", selected.anchor())))
    }

    @Test fun resolvesAnOlderAndroidUnnormalizedOffset() {
        val text = AnswerText("第一段\n\n第二段\n\n第二段")
        val old = AnswerQuote(10, "第二段")
        assertEquals(10..12, text.range(JSONObject().put("answerAnchor", old.anchor())))
    }
}
