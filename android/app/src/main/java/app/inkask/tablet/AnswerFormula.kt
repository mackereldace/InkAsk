package app.inkask.tablet

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun formula(latex: String, size: Float): JLatexMathDrawable? {
    fun render(source: String) = JLatexMathDrawable.builder(source)
        .textSize(size).color(Ink.toArgb()).build()
    return runCatching { render(latex) }.getOrNull() ?: runCatching {
        val compatible = latex.replace("\\begin{aligned}", "\\begin{array}{rl}")
            .replace("\\end{aligned}", "\\end{array}")
            .replace("\\begin{cases}", "\\left\\{\\begin{array}{ll}")
            .replace("\\end{cases}", "\\end{array}\\right.")
        render(compatible)
    }.getOrNull()
}

internal fun Markwon.Builder.useFormulas(width: Int, textSize: Float, onOpen: ((String) -> Unit)? = null): Markwon.Builder =
    usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(textSize) { it.inlinesEnabled(true) })
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(JLatexMathNode::class.java) { visitor, math ->
                    visitor.printFormula(math.latex(), false, width, textSize, onOpen)
                }
                builder.on(JLatexMathBlock::class.java) { visitor, math ->
                    visitor.ensureNewLine()
                    visitor.printFormula(math.latex(), true, width, textSize, onOpen)
                    if (visitor.hasNext(math)) visitor.ensureNewLine()
                }
            }
        })

private fun MarkwonVisitor.printFormula(
    latex: String, block: Boolean, maxWidth: Int, textSize: Float, onOpen: ((String) -> Unit)?,
) {
    if (latex.isBlank()) return
    val start = length()
    val drawable = formula(latex, textSize * if (block) 1.2f else 1.05f)
    if (drawable == null) builder().append(latex)
    else {
        // Android lays out a ReplacementSpan once per text line it covers.
        builder().append(latex.replace('\n', ' ').replace('\r', ' '))
        val fit = (maxWidth.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1)).coerceAtMost(1f)
        setSpans(start, FormulaSpan(drawable, fit, if (block) maxWidth else 0, if (block) textSize * .22f else 0f))
    }
    val delimiter = if (block) "\$\$" else "\$"
    setSpans(start, AnswerMarkup(delimiter + latex.trim('\r', '\n') + delimiter))
    if (onOpen != null) {
        setSpans(start, object : ClickableSpan() {
            override fun onClick(widget: View) { onOpen(latex) }
            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
        })
    }
}

private class FormulaSpan(
    private val drawable: JLatexMathDrawable, private val fit: Float, private val rowWidth: Int, verticalSpace: Float,
) : ReplacementSpan() {
    private val width = ceil(drawable.intrinsicWidth * fit).toInt().coerceAtLeast(1)
    private val height = ceil(drawable.intrinsicHeight * fit).toInt().coerceAtLeast(1)
    private val descent = (drawable.icon().iconDepth * fit).roundToInt().coerceAtLeast(0)
    private val space = verticalSpace.roundToInt()
    private val highlightPaint = Paint()

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let {
            val original = paint.fontMetricsInt
            it.ascent = minOf(original.ascent, descent - height - space)
            it.descent = maxOf(original.descent, descent + space)
            it.top = minOf(original.top, it.ascent)
            it.bottom = maxOf(original.bottom, it.descent)
        }
        return maxOf(rowWidth, width)
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val left = x + (rowWidth - width).coerceAtLeast(0) / 2f
        val formulaTop = (y + descent - height).toFloat()
        // TextView leaves background drawing to the span for replacement content.
        (text as? Spanned)?.getSpans(start, end, BackgroundColorSpan::class.java)?.lastOrNull()?.let { highlight ->
            highlightPaint.color = highlight.backgroundColor
            val highlightTop = if (rowWidth == 0) top.toFloat() else formulaTop - space / 2f
            val highlightBottom = if (rowWidth == 0) bottom.toFloat() else formulaTop + height + space / 2f
            canvas.drawRect(left, highlightTop, left + width, highlightBottom, highlightPaint)
        }
        canvas.save()
        canvas.translate(left, formulaTop)
        canvas.scale(fit, fit)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(canvas)
        canvas.restore()
    }
}

@Composable
internal fun FormulaDialog(latex: String, close: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var scale by remember(latex) { mutableFloatStateOf(1f) }
    val size = with(density) { (24.dp * scale).toPx() }
    val drawable = remember(latex, size) { formula(latex, size) }
    AlertDialog(onDismissRequest = close, title = { Text("公式") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (drawable == null) Text(latex, color = Ink)
            else {
                val contentWidth = with(density) { drawable.intrinsicWidth.toDp() }
                val contentHeight = with(density) { drawable.intrinsicHeight.toDp() }
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Canvas(Modifier.size(contentWidth, contentHeight)) {
                        drawIntoCanvas { canvas ->
                            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                            drawable.draw(canvas.nativeCanvas)
                        }
                    }
                }
                Row {
                    Control("−", "缩小公式") { scale = (scale - .25f).coerceAtLeast(.5f) }
                    Control("＋", "放大公式") { scale = (scale + .25f).coerceAtMost(3f) }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("LaTeX", latex))
        }) { Text("复制 LaTeX") }
    }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
}
