package app.inkask.tablet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.commonmark.node.Code
import org.commonmark.node.Image as MarkdownImage
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as MarkdownText
import org.json.JSONObject

internal data class AnswerQuote(val start: Int, val quote: String, val location: Offset? = null)

private class AnswerZoom {
    var offset = -1
    var y = 0f
    var revision = 0

    fun remember(view: TextView, point: Offset) {
        val layout = view.layout ?: return
        val position = IntArray(2).also(view::getLocationInWindow)
        val line = layout.getLineForVertical((point.y - position[1] - view.totalPaddingTop).toInt())
        offset = layout.getOffsetForHorizontal(line, point.x - position[0])
        y = point.y
    }

    fun restore(view: TextView, scroll: ScrollState) {
        val expected = ++revision
        view.doOnPreDraw {
            if (expected != revision || offset < 0) return@doOnPreDraw
            val layout = view.layout ?: return@doOnPreDraw
            val index = offset.coerceAtMost(view.length())
            val line = layout.getLineForOffset(index)
            val position = IntArray(2).also(view::getLocationInWindow)
            scroll.dispatchRawDelta(position[1] + view.totalPaddingTop + layout.getLineBaseline(line) - y)
        }
    }
}

@Composable
internal fun AnswerSheet(
    note: JSONObject, selectedMessage: String?, reply: String?, scale: Float, busy: Boolean, scroll: ScrollState,
    core: SharedReadingCore, attachments: Map<String, AttachmentPreview>, children: List<JSONObject>, templates: List<JSONObject>,
    onZoom: (Float) -> Unit,
    onSource: (JSONObject, String) -> Unit, onStop: () -> Unit, onMessage: (String) -> Unit,
    onBranch: (String, AnswerQuote) -> Unit, onChild: (String) -> Unit, onDelete: () -> Unit,
    draft: String, onDraft: (String) -> Unit, onTitle: (String) -> Unit, onTemplate: (JSONObject) -> Unit,
    onAttach: (List<Uri>) -> Unit, onRemoveImage: (String) -> Unit,
    onRequest: (String, Boolean) -> Unit, report: (String) -> Unit,
) {
    val context = LocalContext.current
    val anchor = note.getJSONObject("anchor")
    val parts = anchor.anchorParts()
    val pageNumbers = parts.map { it.getInt("page") }
    val pageLabel = if (pageNumbers.size > 1 && pageNumbers.last() - pageNumbers.first() == pageNumbers.lastIndex)
        "${pageNumbers.first()}–${pageNumbers.last()}" else pageNumbers.joinToString("、")
    val messages = note.optJSONArray("messages").objects()
    val answers = messages.filter { it.optString("role") == "assistant" }
    val answer = answers.firstOrNull { it.optString("id") == selectedMessage } ?: answers.lastOrNull()
    val answerId = answer?.optString("id")
    val question = if (answer == null) null else messages.takeWhile { it !== answer }.lastOrNull { it.optString("role") == "user" }
    val draftImages = note.optJSONArray("draftAssetIds").strings().mapNotNull(attachments::get)
    var choosingAnswer by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(answer == null) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var confirmDeletion by remember { mutableStateOf(false) }
    var reader by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var textView by remember(answerId) { mutableStateOf<TextView?>(null) }
    var openFormula by remember { mutableStateOf<String?>(null) }
    val zoom = remember(answerId) { AnswerZoom() }
    val changeScale by rememberUpdatedState(onZoom)
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<MainActivity>().firstOrNull()
    }
    DisposableEffect(activity, zoom, scroll) {
        val handler = AnswerPinch(context,
            contains = { point ->
                reader?.takeIf { it.isAttached }?.let {
                    val local = it.windowToLocal(point)
                    local.x >= 0 && local.x < it.size.width && local.y >= 0 && local.y < it.size.height
                } == true
            },
            onStart = { point -> textView?.let { zoom.remember(it, point) } },
            onZoom = { factor, point ->
                zoom.y = point.y
                changeScale(factor)
                textView?.let { zoom.restore(it, scroll) }
            },
        )
        activity?.answerPinch = handler
        onDispose { if (activity?.answerPinch === handler) activity.answerPinch = null }
    }
    Column(Modifier.fillMaxSize().onGloballyPositioned { reader = it }
        .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Paper, border = BorderStroke(1.dp, Line), shape = ReaderCardShape,
            modifier = Modifier.widthIn(max = 830.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (answer == null) "新的提问" else "阅读回答",
                        color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (answers.size > 1) Box {
                        val number = answers.indexOf(answer) + 1
                        Control("回答 $number ▾", "切换本便签的回答") { choosingAnswer = true }
                        DropdownMenu(expanded = choosingAnswer, onDismissRequest = { choosingAnswer = false }, containerColor = Paper) {
                            answers.forEachIndexed { index, turn ->
                                DropdownMenuItem(text = { Text("回答 ${index + 1}") }, onClick = {
                                    choosingAnswer = false
                                    onMessage(turn.getString("id"))
                                })
                            }
                        }
                    }
                    Control(if (detailsExpanded) "▾ 便签信息" else "▸ 便签信息") { detailsExpanded = !detailsExpanded }
                }
                HorizontalDivider(color = Line)
                if (detailsExpanded) {
                    val saved = note.optString("title")
                    var title by remember(saved) { mutableStateOf(saved) }
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("便签标题") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Control("保存标题", enabled = title.isNotBlank() && title.trim() != saved) { onTitle(title.trim()) }
                    Control("删除此便签及子追问", enabled = !busy) { confirmDeletion = true }
                }
                Row(Modifier.fillMaxWidth().background(PaleGreen, ControlShape)) {
                    val title = if (note.has("parentId")) "选中的上一层回答" else "选中的 PDF 原文 · 第 $pageLabel 页"
                    Control("${if (sourceExpanded) "▾" else "▸"} $title") { sourceExpanded = !sourceExpanded }
                }
                if (sourceExpanded) {
                    Text(note.optString("branchQuote").ifBlank { anchor.optString("quote").ifBlank { "已选择原文区域" } }, color = Muted)
                    if (!note.has("parentId")) parts.forEach { part ->
                        val label = if (parts.size == 1) "预览原文" else "预览第 ${part.getInt("page")} 页原文"
                        Control(label) { onSource(JSONObject(part.toString()).put("text", part.optString("quote")), "") }
                    }
                }
                if (question != null) {
                    Text("这次提问", fontSize = 12.sp, color = Muted)
                    Text(question.optString("content"), color = Ink, fontSize = (16 * scale).sp)
                    AttachmentPreviews(question.optJSONArray("assetIds").strings().mapNotNull(attachments::get))
                }
                if (answer != null && answerId != null) key(answerId) {
                    val displayed = if (answer === answers.lastOrNull()) reply ?: answer.optString("content") else answer.optString("content")
                    val canBranch = displayed.isNotBlank() && (answer.optString("status") != "streaming" || !busy)
                    val sources = answer.optJSONArray("sources").objects()
                    val branches = children.filter {
                        it.optString("parentMessageId").ifBlank { answers.last().getString("id") } == answerId
                    }
                    val prepared by produceState<JSONObject?>(null, displayed, sources) {
                        if (busy) delay(80)
                        value = withContext(Dispatchers.Default) { runCatching { core.citations(displayed, sources) }.getOrNull() }
                    }
                    var renderedText by remember { mutableStateOf("") }
                    var promptExpanded by remember { mutableStateOf(false) }
                    var evidenceExpanded by remember { mutableStateOf(false) }
                    var materialsExpanded by remember { mutableStateOf(false) }
                    if (displayed.isNotBlank()) {
                        NativeMarkdown(prepared?.optString("nativeContent") ?: displayed, scale, sources, branches, attachments,
                            canBranch, onSource, { quote -> onBranch(answerId, quote) }, onChild, { renderedText = it },
                            onView = { textView = it }, onFormula = { openFormula = it })
                    }
                    val unplaced = branches.filter { child -> answerRange(renderedText, child) == null }
                    unplaced.forEach { child ->
                        Control("› " + child.optString("title"), "打开追问分支") { onChild(child.getString("id")) }
                    }
                    if (answer.optString("status") == "error") Text(answer.optString("error", "回答未完成。"), color = Muted)
                    if (answer.optString("status") == "stopped" || (!busy && answer.optString("status") == "streaming")) {
                        Text("已停止，收到的回答已保留。", color = Muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Control("停止生成", action = onStop)
                        } else if (displayed.isNotBlank()) {
                            Control("对整篇回答提问", enabled = renderedText.isNotBlank() && canBranch) {
                                val text = renderedText.trim()
                                onBranch(answerId, AnswerQuote(renderedText.indexOf(text), text))
                            }
                            Control("复制回答") {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("InkAsk", displayed))
                            }
                        }
                        if (!busy && answer === answers.lastOrNull() && question != null &&
                            answer.optString("status") in setOf("error", "stopped", "streaming")) {
                            Control("重试") { onRequest(question.optString("content"), true) }
                        }
                        if (answer.optString("prompt").isNotBlank()) Control("提问记录") { promptExpanded = !promptExpanded }
                    }
                    if (promptExpanded) Text(answer.optString("prompt"), color = Muted, fontSize = 12.sp)
                    if (sources.isNotEmpty()) {
                        val ids = prepared?.optJSONArray("ids").strings().toSet()
                        val cited = sources.filter { it.optString("id") in ids }
                        val pages = cited.map { it.optInt("page") }.distinct().size
                        Control("回答引用 · ${cited.size} 项原文 · $pages 页") { evidenceExpanded = !evidenceExpanded }
                        if (evidenceExpanded) {
                            cited.forEach { CitationControl(it, onSource) }
                            val provided = sources.map { it.optInt("page") }.distinct().size
                            Control("本次提供的材料 · ${sources.size} 个片段 · $provided 页") { materialsExpanded = !materialsExpanded }
                            if (materialsExpanded) sources.forEach { CitationControl(it, onSource) }
                        }
                    }
                }
                if (!busy) {
                    HorizontalDivider(color = Line)
                    val readyToSend = answer == null || draft.isNotBlank() || draftImages.isNotEmpty()
                    OutlinedTextField(draft, { onDraft(it.take(4_000)) }, modifier = Modifier.fillMaxWidth().pasteImages(onAttach)
                        .questionShortcut { if (readyToSend) onRequest(draft, false) },
                        placeholder = { Text("在这个便签中继续提问") }, minLines = 2)
                    AttachmentPreviews(draftImages, onRemoveImage)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AttachmentControls(onAttach, report)
                        TemplateMenu(templates, note.optString("templateId")) { if (it != null) onTemplate(it) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onRequest(draft, false) },
                            enabled = readyToSend,
                            shape = ControlShape) { Text(if (answer == null) "发送提问" else "继续提问") }
                    }
                }
            }
        }
    }
    if (confirmDeletion) AlertDialog(onDismissRequest = { confirmDeletion = false }, title = { Text("删除便签？") },
        text = { Text("所选便签及其关联的追问分支将一起删除。") },
        confirmButton = { TextButton(onClick = { confirmDeletion = false; onDelete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("取消") } })
    openFormula?.let { FormulaDialog(it) { openFormula = null } }
}

@Composable
internal fun TemplateMenu(templates: List<JSONObject>, selectedId: String?, automatic: Boolean = false,
                          select: (JSONObject?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = templates.firstOrNull { it.optString("id") == selectedId }
    Box {
        Control(selected?.optString("name")?.plus(" ▾") ?: "模板 ▾", "提问模板") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Paper) {
            if (automatic) DropdownMenuItem(text = { Text("按提问任务自动选择") }, onClick = { select(null); expanded = false })
            templates.forEach { template ->
                DropdownMenuItem(text = { Text(template.optString("name")) }, onClick = { select(template); expanded = false })
            }
        }
    }
}

@Composable
private fun CitationControl(source: JSONObject, action: (JSONObject, String) -> Unit) {
    val title = "[${source.getString("id")}] · 第 ${source.getInt("page")} 页"
    val label = source.optString("label")
    Control(if (label.isBlank()) title else "$title · $label") { action(source, "") }
}

@Composable
private fun NativeMarkdown(
    raw: String, scale: Float, sources: List<JSONObject>, children: List<JSONObject>, attachments: Map<String, AttachmentPreview>,
    canBranch: Boolean,
    onSource: (JSONObject, String) -> Unit, onBranch: (AnswerQuote) -> Unit, onChild: (String) -> Unit, onText: (String) -> Unit,
    onView: (TextView) -> Unit, onFormula: (String) -> Unit,
) {
    val handler by rememberUpdatedState(onSource)
    val branch by rememberUpdatedState(onBranch)
    val openChild by rememberUpdatedState(onChild)
    val lookup by rememberUpdatedState(sources.associateBy { it.getString("id") })
    val branchEnabled by rememberUpdatedState(canBranch)
    val formulaViewer by rememberUpdatedState(onFormula)
    val rendered = raw
    val context = LocalContext.current
    val ids = remember(raw) { assetReferences.findAll(raw).map { it.groupValues[1] }.distinct().take(20).toList() }
    val images by produceState<Map<String, Bitmap>>(emptyMap(), ids, attachments) {
        val maxDimension = 260f * context.resources.displayMetrics.density
        value = withContext(Dispatchers.IO) {
            ids.mapNotNull { id -> attachments[id]?.decode(context, maxDimension)?.let { id to it } }.toMap()
        }
    }
    var marks by remember { mutableStateOf<List<AnswerMark>>(emptyList()) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val gutter = if (children.isEmpty()) 0.dp else 44.dp
    val textWidth = with(density) { (maxWidth - gutter).roundToPx().coerceAtLeast(1) }
    AndroidView(modifier = Modifier.fillMaxWidth().padding(end = gutter), factory = { context ->
        TextView(context).apply {
            setTextColor(Ink.toArgb())
            setLinkTextColor(Green.toArgb())
            setTextIsSelectable(true)
            movementMethod = CitationMovement()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(4f * resources.displayMetrics.density, 1.13f)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    if (branchEnabled) menu.add(0, 1001, 0, "追问").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (item.itemId != 1001) return false
                    val start = minOf(selectionStart, selectionEnd)
                    val end = maxOf(selectionStart, selectionEnd)
                    if (start >= 0 && end > start) {
                        val selected = text.subSequence(start, end).toString()
                        val quote = selected.trim()
                        if (quote.isNotEmpty() && branchEnabled) {
                            val at = IntArray(2).also(::getLocationInWindow)
                            val line = layout?.getLineForOffset(end - 1)
                            val x = layout?.getPrimaryHorizontal(end - 1) ?: 0f
                            val y = line?.let { layout.getLineBottom(it) } ?: 0
                            branch(AnswerQuote(start + selected.indexOf(quote), quote,
                                Offset(at[0] + x, at[1] + y + totalPaddingTop.toFloat())))
                        }
                    }
                    mode.finish()
                    return true
                }
                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
            onView(this)
        }
    }, update = { view ->
        val size = 16 * scale
        val key = listOf(rendered, size, children.map { it.toString() }, images, textWidth)
        if (view.tag != key) {
            view.tag = key
            view.textSize = size
            val targets = mutableMapOf<String, Pair<String, String>>()
            val markdown = Markwon.builder(view.context)
                .usePlugin(TablePlugin.create(view.context))
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(view.textSize) { it.inlinesEnabled(true) })
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver { _, link ->
                            targets[link]?.let { (id, claim) -> lookup[id]?.let { handler(it, claim) } }
                        }
                    }
                    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                        builder.on(JLatexMathNode::class.java) { visitor, math ->
                            visitor.printFormula(math.latex(), false, textWidth, view.textSize) { formulaViewer(it) }
                        }
                        builder.on(JLatexMathBlock::class.java) { visitor, math ->
                            visitor.ensureNewLine()
                            visitor.printFormula(math.latex(), true, textWidth, view.textSize) { formulaViewer(it) }
                            if (visitor.hasNext(math)) visitor.ensureNewLine()
                        }
                        builder.on(MarkdownImage::class.java) { visitor, image ->
                            val id = assetReferences.matchEntire(image.destination)?.groupValues?.get(1)
                            val bitmap = images[id]
                            if (bitmap == null) {
                                visitor.visitChildren(image)
                            } else {
                                val label = attachments[id]?.name ?: "图片"
                                val drawable = BitmapDrawable(view.resources, bitmap).apply {
                                    setBounds(0, 0, bitmap.width, bitmap.height)
                                }
                                visitor.builder().append(label, ImageSpan(drawable))
                            }
                        }
                    }
                }).build()
            val document = markdown.parse(rendered)
            document.visit { node ->
                if (node is Link && node.destination.startsWith("#source-")) {
                    val id = node.destination.removePrefix("#source-")
                    if (id in lookup) {
                        val paragraph = generateSequence(node.parent) { it.parent }.firstOrNull { it is Paragraph } ?: node.parent
                        val claim = paragraph?.claim().orEmpty()
                        val key = "inkask-source:${targets.size}"
                        targets[key] = id to claim
                        node.destination = key
                        while (node.firstChild != null) node.firstChild.unlink()
                        node.appendChild(MarkdownText("[$id]"))
                    }
                }
            }
            val native = SpannableStringBuilder(markdown.render(document))
            val text = native.toString()
            val ranges = children.mapIndexedNotNull { index, child ->
                val range = answerRange(text, child) ?: return@mapIndexedNotNull null
                Triple(index + 1, child, range)
            }
            for ((_, child, range) in ranges) {
                native.setSpan(BackgroundColorSpan(PaleGreen.toArgb()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                native.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { openChild(child.getString("id")) }
                    override fun updateDrawState(paint: TextPaint) {
                        paint.color = Ink.toArgb()
                        paint.isUnderlineText = true
                    }
                }, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            markdown.setParsedMarkdown(view, native)
            onText(text)
            view.doOnPreDraw {
                if (view.tag != key) return@doOnPreDraw
                val layout = view.layout ?: return@doOnPreDraw
                marks = ranges.map { (number, child, range) ->
                    val y = layout.getLineTop(layout.getLineForOffset(range.first)) + view.totalPaddingTop
                    AnswerMark(number, child, y)
                }
            }
        }
    })
    val separation = with(density) { 42.dp.roundToPx() }
    val groups = mutableListOf<MutableList<AnswerMark>>()
    for (mark in marks.sortedBy { it.top }) {
        val group = groups.lastOrNull()
        if (group == null || mark.top - group.first().top >= separation) groups.add(mutableListOf(mark))
        else group.add(mark)
    }
    for (group in groups) {
        val first = group.first()
        key(first.child.getString("id")) {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, first.top) }) {
                val label = group.joinToString("·") { it.number.toString() }
                Control(if (group.size > 2) "${first.number}+" else label, "打开追问 ${group.joinToString("、") { it.number.toString() }}") {
                    if (group.size == 1) onChild(first.child.getString("id")) else expanded = true
                }
                DropdownMenu(expanded, { expanded = false }, containerColor = Paper) {
                    group.forEach { mark ->
                        DropdownMenuItem(text = { Text("${mark.number}. ${mark.child.optString("title")}") }, onClick = {
                            expanded = false
                            onChild(mark.child.getString("id"))
                        })
                    }
                }
            }
        }
    }
    }
}

private data class AnswerMark(val number: Int, val child: JSONObject, val top: Int)
private fun answerRange(text: String, child: JSONObject): IntRange? {
    val anchor = child.optJSONObject("answerAnchor")
    val quote = anchor?.optString("quote")?.takeIf { it.isNotBlank() } ?: child.optString("branchQuote").trim()
    if (quote.isEmpty()) return null
    val start = anchor?.optInt("start", -1) ?: -1
    val end = anchor?.optInt("end", -1) ?: -1
    if (start >= 0 && end >= start && end <= text.length && text.substring(start, end) == quote) return start until end
    val found = text.indexOf(quote)
    return if (found >= 0 && text.indexOf(quote, found + 1) < 0) found until found + quote.length else null
}

private fun Node.visit(action: (Node) -> Unit) {
    action(this)
    var child = firstChild
    while (child != null) {
        child.visit(action)
        child = child.next
    }
}

private fun Node.claim(): String = buildString {
    fun add(node: Node) {
        when (node) {
            is Link -> if (!node.destination.startsWith("#source-") && !node.destination.startsWith("inkask-source:")) {
                var child = node.firstChild
                while (child != null) { add(child); child = child.next }
            }
            is MarkdownText -> append(node.literal)
            is Code -> append(node.literal)
            is JLatexMathNode -> append(node.latex())
            is JLatexMathBlock -> append(node.latex())
            else -> {
                var child = node.firstChild
                while (child != null) { add(child); child = child.next }
            }
        }
    }
    add(this@claim)
}

private val assetReferences = Regex("(?:asset:|inkask://asset/)([A-Za-z0-9._-]+)")

private class CitationMovement : ArrowKeyMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
            widget.selectionStart == widget.selectionEnd) {
            val layout = widget.layout
            if (layout != null) {
                val x = event.x - widget.totalPaddingLeft + widget.scrollX
                val y = event.y - widget.totalPaddingTop + widget.scrollY
                if (y >= 0 && y < layout.height) {
                    val line = layout.getLineForVertical(y.toInt())
                    if (x in layout.getLineLeft(line)..layout.getLineRight(line)) {
                        val index = layout.getOffsetForHorizontal(line, x)
                        buffer.getSpans(index, index, ClickableSpan::class.java).firstOrNull()?.let {
                            it.onClick(widget)
                            return true
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}
