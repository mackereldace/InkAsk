package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import org.json.JSONObject

internal data class AnswerQuote(val start: Int, val quote: String, val location: Offset? = null) {
    fun anchor(): JSONObject = JSONObject().put("start", start).put("end", start + quote.length).put("quote", quote)
}

internal data class AnswerMarkup(val source: String)
internal data class AnswerTextPart(val start: Int, val end: Int, val markup: AnswerMarkup)

internal class AnswerText(private val displayed: String, parts: List<AnswerTextPart> = emptyList()) {
    private val starts = IntArray(displayed.length)
    private val ends = IntArray(displayed.length)
    private val source = buildString {
        var index = 0
        fun appendPlain(until: Int) {
            while (index < until) {
                val character = displayed[index]
                val repeatedBreak = character == '\n' && lastOrNull() == '\n'
                starts[index] = if (repeatedBreak) length - 1 else length
                if (!repeatedBreak) append(character)
                ends[index++] = length
            }
        }
        for (part in parts.sortedBy { it.start }) {
            require(part.start >= index && part.end <= displayed.length && part.end > part.start)
            appendPlain(part.start)
            val start = length
            append(part.markup.source)
            while (index < part.end) {
                starts[index] = start
                ends[index++] = length
            }
        }
        appendPlain(displayed.length)
    }

    fun whole(): AnswerQuote? = selection(0, displayed.length)

    fun selection(from: Int, to: Int, location: Offset? = null): AnswerQuote? {
        if (from < 0 || to > displayed.length || to <= from) return null
        val start = starts[from]
        val raw = source.substring(start, ends[to - 1])
        val quote = raw.trim()
        return quote.takeIf(String::isNotEmpty)?.let { AnswerQuote(start + raw.indexOf(it), it, location) }
    }

    fun range(child: JSONObject): IntRange? {
        val anchor = child.optJSONObject("answerAnchor")
        val quote = anchor?.optString("quote")?.takeIf(String::isNotBlank) ?: child.optString("branchQuote").trim()
        if (quote.isEmpty()) return null
        val hasOffsets = anchor != null
        val start = anchor?.optInt("start", -1) ?: -1
        val end = anchor?.optInt("end", -1) ?: -1
        fun offsetMatches(text: String) =
            hasOffsets && start >= 0 && end > start && end <= text.length && text.substring(start, end) == quote
        fun uniqueRange(text: String): IntRange? {
            val index = text.indexOf(quote)
            return if (index >= 0 && text.indexOf(quote, index + 1) < 0) index until index + quote.length else null
        }
        fun displayedRange(range: IntRange): IntRange? {
            val first = ends.indexOfFirst { it > range.first }
            val last = starts.indexOfLast { it <= range.last }
            return if (first >= 0 && last >= first) first..last else null
        }
        if (offsetMatches(source)) return displayedRange(start until end)
        if (offsetMatches(displayed)) return start until end
        return uniqueRange(source)?.let(::displayedRange) ?: uniqueRange(displayed)
    }
}
