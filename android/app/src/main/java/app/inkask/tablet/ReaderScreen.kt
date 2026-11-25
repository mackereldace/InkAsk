package app.inkask.tablet

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.abs

private data class AnswerTarget(val noteId: String, val messageId: String, val quote: AnswerQuote)
private data class QuestionSelection(
    val range: PdfSelection? = null,
    val answer: AnswerTarget? = null,
    val draft: JSONObject? = null,
    val location: Offset? = answer?.quote?.location,
    val submission: QuestionSubmission = draft?.getString("id")?.let { QuestionSubmission(noteId = it) } ?: QuestionSubmission(),
) {
    val quote get() = answer?.quote?.quote
        ?: draft?.optString("branchQuote")?.takeIf { it.isNotBlank() }
        ?: draft?.optJSONObject("anchor")?.optString("quote")
        ?: range?.quote.orEmpty()
}
private data class SourcePreviewPart(val label: String, val text: String, val range: PdfRange?, val image: ByteArray?)
private data class SourcePreview(val source: JSONObject, val size: PageSize, val parts: List<SourcePreviewPart>)
private data class PdfZoomPivot(
    val item: Int, val point: Offset, val size: IntSize, val scale: Float,
    val distanceToStart: Int?, val distanceToEnd: Int?,
)

@Composable
internal fun ReaderScreen(
    doc: Opened, workspace: Workspace, model: ReadingModel, profile: ModelProfile?, drawerOpen: Boolean,
    openDrawer: () -> Unit, openLibrary: () -> Unit, openSettings: () -> Unit,
    export: (ExportKind) -> Unit, report: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pdf = doc.pdf
    val initial = doc.project.getJSONObject("view")
    var project by remember(doc.project) { mutableStateOf(doc.project) }
    fun publishProject(next: JSONObject) {
        if (next.getJSONObject("document").optLong("updatedAt") >= project.getJSONObject("document").optLong("updatedAt")) {
            project = next
        }
    }
    var page by remember { mutableIntStateOf(initial.optInt("page", 1).coerceIn(1, pdf.pageCount)) }
    var rotation by remember { mutableIntStateOf((initial.optInt("rotation", 0) % 360 + 360) % 360) }
    var scale by remember { mutableFloatStateOf(initial.optDouble("scale", 1.0).toFloat().coerceIn(.25f, 3f)) }
    var horizontal by remember { mutableStateOf(initial.optString("direction") == "horizontal") }
    var region by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<QuestionSelection?>(null) }
    var draggingText by remember { mutableStateOf<PdfSelection?>(null) }
    var located by remember { mutableStateOf<PdfRange?>(null) }
    var noteId by remember { mutableStateOf<String?>(null) }
    var messageId by remember { mutableStateOf<String?>(null) }
    val answerPositions = remember { mutableMapOf<Pair<String, String?>, Int>() }
    val replies = remember { mutableStateMapOf<String, String>() }
    val requests = remember { mutableStateMapOf<String, Job>() }
    var scanning by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<SourcePreview?>(null) }
    var pageDialog by remember { mutableStateOf(false) }
    var pendingJump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var paperPixels by remember { mutableFloatStateOf(0f) }
    val answerScales = remember { mutableStateMapOf<Pair<String, String?>, Float>() }
    var activeBranch by remember { mutableStateOf<String?>(null) }
    var across by remember { mutableFloatStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var zoomPivot by remember { mutableStateOf<PdfZoomPivot?>(null) }
    var toolsMenu by remember { mutableStateOf(false) }
    var pathOpen by remember { mutableStateOf(false) }
    var answerPicker by remember { mutableStateOf(false) }
    var detailsNoteId by remember { mutableStateOf<String?>(null) }
    val verticalState = rememberLazyListState(page - 1)
    val horizontalState = rememberLazyListState(page - 1)
    val scroll = if (horizontal) horizontalState else verticalState
    var zoomPadding by remember(scroll) { mutableStateOf<PdfScrollPadding?>(null) }
    val pageLayouts = remember(pdf) { mutableMapOf<Int, PdfPageLayout>() }
    val notes = project.optJSONArray("notes").objects()
    val focused = notes.firstOrNull { it.optString("id") == noteId }
    val answers = focused?.answerMessages().orEmpty()
    val focusedMessage = answers.firstOrNull { it.optString("id") == messageId }?.optString("id")
        ?: answers.lastOrNull()?.optString("id")
    val answerPosition = focused?.let { it.getString("id") to focusedMessage }
    val timeline = generateSequence(focused) { note -> notes.firstOrNull { it.optString("id") == note.optString("parentId") } }
        .take(notes.size).toList().asReversed()
    val pages = project.optJSONArray("pages").objects().associateBy { it.getInt("page") }
    var rawText by remember { mutableStateOf("") }
    val recognized = pages[page]?.takeIf { it.optString("source") == "ocr" && it.optString("status") == "ready" }

    LaunchedEffect(scroll, noteId) {
        if (noteId != null) return@LaunchedEffect
        val target = pendingJump
        val position = (target?.first ?: page) - 1
        val restoredOffset = scroll.firstVisibleItemScrollOffset.takeIf { position == scroll.firstVisibleItemIndex } ?: 0
        scroll.scrollToItem(position, target?.second ?: restoredOffset)
        pendingJump = null
        snapshotFlow { scroll.firstVisibleItemIndex }.collect { page = it + 1 }
    }
    LaunchedEffect(page, scale, rotation, horizontal) {
        delay(120)
        val state = JSONObject().put("page", page).put("scale", scale.toDouble()).put("rotation", rotation)
            .put("direction", if (horizontal) "horizontal" else "vertical")
        try { withContext(Dispatchers.IO) { workspace.update(doc.item.id) { it.put("view", state) } } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { report("无法保存阅读位置。") }
    }
    LaunchedEffect(page, pdf) {
        rawText = withContext(Dispatchers.IO) { runCatching { pdf.text(page) }.getOrDefault("") }
    }
    LaunchedEffect(pdf) {
        if (!pdf.canSelectText) return@LaunchedEffect
        val remaining = (1..pdf.pageCount).filter { number -> pages[number]?.missingNativeTextBounds() != false }
        try {
            remaining.chunked(8).forEach { batch ->
                publishProject(withContext(Dispatchers.IO) {
                    val next = batch.mapNotNull { index -> runCatching { pdf.index(index) }.getOrNull() }
                    workspace.update(doc.item.id) { current ->
                        val records = current.getJSONArray("pages")
                        val stored = records.objects().withIndex().associateBy { it.value.getInt("page") }
                        next.forEach { indexed ->
                            val previous = stored[indexed.getInt("page")]
                            if (previous == null) records.put(indexed)
                            else if (previous.value.missingNativeTextBounds() && !indexed.missingNativeTextBounds()) {
                                records.put(previous.index, indexed)
                            }
                        }
                    }
                })
                delay(20)
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { report("无法保存 PDF 文本索引。") }
    }

    fun go(target: Int, offset: Int = 0) {
        val next = target.coerceIn(1, pdf.pageCount)
        zoomPadding = null
        page = next
        if (noteId != null) {
            pendingJump = next to offset
            noteId = null
            messageId = null
        } else {
            scope.launch { scroll.scrollToItem(next - 1, offset) }
        }
    }
    fun back() {
        selection = null
        activeBranch = focused?.optString("id")
        messageId = focused?.optString("parentMessageId")?.ifEmpty { null }
        noteId = focused?.optString("parentId")?.ifEmpty { null }
    }
    BackHandler(enabled = !drawerOpen && (focused != null || selection != null)) {
        if (selection != null) selection = null else back()
    }

    fun readSource(source: JSONObject, claim: String) {
        val previewRotation = rotation
        val noteAnchor = focused?.optJSONObject("anchor")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val number = source.getInt("page")
                    val size = pdf.size(number)
                    val page = pages[number]?.takeUnless { it.missingNativeTextBounds() } ?: pdf.index(number)
                    val parts = workspace.core.preview(source, page, noteAnchor, claim).getJSONArray("parts").objects().map { part ->
                        val range = part.optJSONObject("anchor")?.pdfRange(size)
                        SourcePreviewPart(part.getString("label"), part.getString("text"), range,
                            range?.takeIf { it.boxes.isNotEmpty() }?.let { pdf.crop(it, previewRotation) })
                    }
                    SourcePreview(source, size, parts)
                }
                preview = result
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { report("无法预览来源。") }
        }
    }

    fun request(selected: QuestionSelection, question: String, task: String, screenshot: Boolean,
                existing: JSONObject? = null, retry: Boolean = false, externalImages: List<Uri> = emptyList(),
                templateId: String? = null, saveOnly: Boolean = false, retainedImages: List<String>? = null) {
        val existingId = (existing ?: selected.draft)?.getString("id")
        val submission = selected.submission
        val askId = existingId ?: submission.noteId
        if (askId in requests) return
        val retryTarget = if (retry) {
            val last = existing?.optJSONArray("messages").objects().lastOrNull() ?: return
            AnswerRetry(last.getString("id"), last.optLong("createdAt")).takeIf { it.matches(last) } ?: return
        } else null
        val configured = profile
        if (!saveOnly && configured == null) { openSettings(); return }
        val range = selected.range
        val needsImage = range?.quote?.isBlank() == true
        val captures = if (screenshot || needsImage) range?.fragments.orEmpty() else emptyList()
        val captureRotation = rotation
        if (captures.size + externalImages.size > 4) {
            report(if (captures.size > 4) "跨页选区超过 4 页，请缩小截图范围，或取消附图后使用完整选文提问。"
                else "每次最多附 4 张图片。")
            return
        }
        if (!saveOnly && configured?.images != true && (captures.isNotEmpty() || externalImages.isNotEmpty())) {
            report("当前模型未开启图片输入支持。")
            return
        }
        report("")
        val origin = noteId to messageId
        val draftMessageCount = selected.draft?.getJSONArray("messages")?.length()
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            val now = System.currentTimeMillis()
            var answer: JSONObject? = null
            var finalStatus = "complete"
            var errorMessage: String? = null
            val output = StringBuilder()
            try {
                val result = withContext(Dispatchers.IO) {
                    val text = question.trim().ifEmpty { model.taskLabel(task) }
                    val files = captures.map { part ->
                        val source = JSONObject().put("page", part.page).put("rect", part.bounds().pdfRect(pdf.size(part.page)))
                        QuestionImage("第 ${part.page} 页选区.png", pdf.crop(part, captureRotation), source)
                    } + externalImages.map { uri ->
                        val (name, bytes) = workspace.readImage(uri)
                        QuestionImage(name, bytes)
                    }
                    var draft: RequestDraft? = null
                    val updated = workspace.updateWithImages(doc.item.id, files, skip = {
                        it.hasCommittedSubmission(askId, submission, saveOnly, retryTarget, draftMessageCount)
                    }) { content, ids ->
                        val notes = content.getJSONArray("notes")
                        val index = notes.objects().indexOfFirst { it.getString("id") == askId }
                        val saved = notes.optJSONObject(index)
                        require(existingId == null || saved != null) { "这条便签已不存在。" }
                        val taskTemplate = model.template(content, task, templateId ?: saved?.optString("templateId").takeIf { retry })
                        val template = if (retry) {
                            saved?.optJSONArray("messages").objects().lastOrNull { it.optString("role") == "user" }
                                ?.optJSONObject("template") ?: taskTemplate
                        } else taskTemplate
                        val note = saved ?: selected.answer?.let { target ->
                            workspace.core.branch(content, target.noteId, target.messageId, target.quote.anchor(),
                                template, askId, now)
                        } ?: JSONObject().put("id", askId).put("title", text.take(100))
                            .put("anchor", requireNotNull(range).anchor(pdf::size)).put("collapsed", false)
                            .put("draft", "").put("draftAssetIds", JSONArray()).put("messages", JSONArray())
                            .put("inheritedHistory", JSONArray()).put("createdAt", now)
                        if (!retry) note.put("task", template.getString("task")).put("templateId", template.getString("id"))
                        val messages = note.getJSONArray("messages")
                        val lastAssistant = messages.optJSONObject(messages.length() - 1)?.takeIf {
                            retry && it.optString("role") == "assistant"
                        }
                        if (lastAssistant != null) messages.remove(messages.length() - 1)
                        val priorUser = messages.objects().lastOrNull { it.optString("role") == "user" }?.takeIf { retry }
                        val savedImages = note.optJSONArray("draftAssetIds").strings()
                        val keptImages = retainedImages?.let { savedImages.intersect(it.toSet()).toList() } ?: savedImages
                        val assetIds = priorUser?.optJSONArray("assetIds") ?: JSONArray(keptImages + ids)
                        require(retry || assetIds.length() <= 4) { "每次最多附 4 张图片。" }
                        if (!retry) note.put("draftAssetIds", assetIds)
                        if (captures.isNotEmpty() && saved == null) {
                            note.put("sourceAssetId", ids.first())
                            if (captures.size > 1) note.put("sourceAssetIds", JSONArray(ids.take(captures.size)))
                        }
                        if (saveOnly) {
                            note.put("draft", question).put("draftSaveToken", submission.userId)
                            if (index == -1) notes.put(note) else notes.put(index, note)
                            draft = RequestDraft(content, "", emptyList(), emptyList(), null)
                            return@updateWithImages
                        }
                        val previousPrompt = priorUser?.optString("prompt")?.takeIf { it.isNotBlank() }
                        val contextNote = if (priorUser != null) JSONObject(note.toString())
                            .put("messages", JSONArray(messages.objects().dropLast(1))).put("draftAssetIds", assetIds) else note
                        val context = workspace.core.context(content, contextNote, question, template)
                        val body = previousPrompt ?: context.getString("prompt")
                        val sources = priorUser?.optJSONArray("sources") ?: context.getJSONArray("sources")
                        val coverage = priorUser?.optString("coverage")?.ifBlank { null } ?: context.getString("coverage")
                        val history = priorUser?.optJSONArray("history") ?: context.getJSONArray("history")
                        val assets = content.getJSONArray("assets").objects().associateBy { it.getString("id") }
                        val loaded = mutableMapOf<String, InputImage>()
                        fun images(references: JSONArray?): List<InputImage> = references.strings().map { id ->
                            loaded.getOrPut(id) {
                                val item = assets.getValue(id)
                                InputImage(item.optString("mime", "image/png"), workspace.assetFile(doc.item.id, item).readBytes())
                            }
                        }
                        val requestImages = images(assetIds)
                        val requestHistory = history.objects().filter { it.optString("content").isNotBlank() }.map {
                            Turn(it.getString("role"), it.getString("content"), images(it.optJSONArray("assetIds")))
                        }
                        require(configured?.images == true || (requestImages.isEmpty() && requestHistory.none { it.images.isNotEmpty() })) {
                            "当前模型未开启图片输入支持。"
                        }
                        if (priorUser == null) messages.put(JSONObject().put("id", submission.userId).put("role", "user")
                            .put("content", text).put("assetIds", assetIds).put("createdAt", now).put("status", "complete")
                            .put("prompt", body).put("template", template).put("sources", sources)
                            .put("coverage", coverage).put("history", history))
                        val assistant = JSONObject().put("id", lastAssistant?.getString("id") ?: submission.answerId)
                            .put("role", "assistant").put("content", "").put("status", "streaming")
                            .put("createdAt", maxOf(now, (retryTarget?.createdAt ?: 0) + 1))
                            .put("assetIds", JSONArray()).put("prompt", body)
                            .put("template", template).put("sources", sources).put("coverage", coverage).put("history", history)
                        messages.put(assistant)
                        if (priorUser == null) {
                            note.put("draft", "").put("draftAssetIds", JSONArray())
                            note.remove("draftSaveToken")
                        }
                        if (index == -1) notes.put(note) else notes.put(index, note)
                        draft = RequestDraft(content, body, requestHistory, requestImages, assistant)
                    }
                    draft?.copy(project = updated) ?: RequestDraft(updated, "", emptyList(), emptyList(), null)
                }
                answer = result.answer
                publishProject(result.project)
                val open = selection?.submission
                if (open == submission) selection = null
                if ((noteId to messageId) == origin && (open == null || open == submission)) {
                    activeBranch = null
                    noteId = askId
                    messageId = answer?.getString("id") ?: retryTarget?.id
                }
                if (answer == null || configured == null) return@launch
                var savedAt = android.os.SystemClock.elapsedRealtime()
                model.stream(configured, result.prompt, result.history, result.images).collect { chunk ->
                    output.append(chunk)
                    replies[askId] = output.toString()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - savedAt >= 750L) {
                        val partial = output.toString()
                        withContext(Dispatchers.IO) {
                            workspace.update(doc.item.id) { content ->
                                val note = content.getJSONArray("notes").objects().first { it.getString("id") == askId }
                                note.getJSONArray("messages").objects().first {
                                    it.getString("id") == result.answer?.getString("id")
                                }.put("content", partial)
                            }
                        }
                        savedAt = now
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                finalStatus = "stopped"
                throw cancelled
            } catch (error: Exception) {
                finalStatus = "error"
                errorMessage = error.localizedMessage ?: "未能生成回答。"
                report(errorMessage ?: "未能生成回答。")
            } finally {
                withContext(NonCancellable) {
                    answer?.let { message ->
                        try {
                            publishProject(withContext(Dispatchers.IO) {
                                workspace.update(doc.item.id) { content ->
                                    val target = content.getJSONArray("notes").objects().first { it.getString("id") == askId }
                                    val turn = target.getJSONArray("messages").objects().first { it.getString("id") == message.getString("id") }
                                    turn.put("content", output.toString()).put("status", finalStatus)
                                    if (errorMessage != null) turn.put("error", errorMessage)
                                }
                            })
                        } catch (_: Exception) { report("回答未能保存。") }
                    }
                    requests.remove(askId)
                    replies.remove(askId)
                }
            }
        }
        requests[askId] = pending
        pending.start()
    }

    fun recognizePage() {
        val target = page
        scanning = scope.launch {
            try {
                val text = scanPage(pdf, target)
                publishProject(withContext(Dispatchers.IO) {
                    workspace.update(doc.item.id) { current ->
                        val previous = current.getJSONArray("pages").objects().filterNot { it.getInt("page") == target }
                        current.put("pages", JSONArray(previous).put(text))
                    }
                })
                if (text.optString("status") == "empty") report("本页没有识别出文字，可框选截图。")
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { report("本页识别失败。") }
            finally { scanning = null }
        }
    }

    fun editNote(id: String, edit: (JSONObject) -> Unit) {
        scope.launch {
            try {
                publishProject(withContext(Dispatchers.IO) {
                    workspace.update(doc.item.id) { content ->
                        content.getJSONArray("notes").objects().firstOrNull { it.getString("id") == id }?.let(edit)
                    }
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { report(error.message ?: "便签无法保存。") }
        }
    }

    var rootPosition by remember { mutableStateOf(Offset.Zero) }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootPosition = it.positionInWindow() }) {
        val width = maxWidth
        val baseWidth = (width - 68.dp).coerceIn(180.dp, 620.dp)
        val paperWidth = baseWidth * scale
        val density = LocalDensity.current
        val crossSize = if (horizontal) viewportSize.height else viewportSize.width
        val fitScale = ((width - 44.dp) / baseWidth).coerceIn(.25f, 3f)
        val normalPadding = with(density) {
            if (horizontal) PdfScrollPadding(20.dp.roundToPx(), 20.dp.roundToPx())
            else PdfScrollPadding(0, 220.dp.roundToPx())
        }
        fun findZoomPivot(window: Offset): PdfZoomPivot? {
            val items = scroll.layoutInfo.visibleItemsInfo
            val first = items.firstOrNull { it.index == 0 }
            val last = items.firstOrNull { it.index == pdf.pageCount - 1 }
            val visible = items.mapNotNull { item ->
                val layout = pageLayouts[item.index + 1]?.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
                val local = layout.windowToLocal(window)
                val x = local.x - local.x.coerceIn(0f, layout.size.width.toFloat())
                val y = local.y - local.y.coerceIn(0f, layout.size.height.toFloat())
                PdfZoomPivot(item.index, local, layout.size, scale,
                    first?.let { item.offset - it.offset },
                    last?.let { it.offset + it.size - item.offset }) to (x * x + y * y)
            }
            return visible.minByOrNull { it.second }?.first
        }
        fun beginZoom(window: Offset) {
            zoomPivot = findZoomPivot(window)
            selection = null
            draggingText = null
        }
        fun verticalPageExtent(numbers: IntProgression, scaledWidth: Int, limit: Int): Float? {
            var extent = 0f
            for (number in numbers) {
                val size = pageLayouts[number]?.size ?: pages[number]?.let { indexed ->
                    val w = indexed.optDouble("width").toInt()
                    val h = indexed.optDouble("height").toInt()
                    if (w > 0 && h > 0) PageSize(w, h) else null
                } ?: return null
                val rotated = size.rotated(rotation)
                extent += (scaledWidth * (rotated.height.toFloat() / rotated.width)).roundToInt()
                if (extent >= limit) break
            }
            return extent
        }
        fun zoomTo(target: Float, window: Offset) {
            val next = target.coerceIn(.25f, 3f)
            val view = viewportCoordinates?.takeIf { it.isAttached } ?: return
            val pivot = zoomPivot ?: findZoomPivot(window)?.also { zoomPivot = it } ?: return
            val focus = view.windowToLocal(window)
            val ratio = next / pivot.scale
            val main = if (horizontal) focus.x - pivot.point.x * ratio else focus.y - pivot.point.y * ratio
            val viewport = if (horizontal) view.size.width else view.size.height
            val scaledWidth = with(density) { (baseWidth * next).roundToPx() }
            // Zooming out can reveal pages beyond the visible items at gesture start.
            val position = if (horizontal) {
                val width = scaledWidth.toFloat()
                pdfZoomPosition(main, viewport, normalPadding, pivot.item * width, (pdf.pageCount - pivot.item) * width)
            } else {
                val before = pivot.distanceToStart?.times(ratio)
                    ?: verticalPageExtent(pivot.item downTo 1, scaledWidth, viewport)
                val after = pivot.distanceToEnd?.times(ratio)
                    ?: verticalPageExtent(pivot.item + 1..pdf.pageCount, scaledWidth, viewport)
                pdfZoomPosition(main, viewport, normalPadding, before, after)
            }
            zoomPadding = position.padding
            scroll.requestScrollToItem(pivot.item, position.offset)
            val extent = (if (horizontal) pivot.size.height else pivot.size.width) * ratio
            val desired = if (horizontal) focus.y - pivot.point.y * ratio else focus.x - pivot.point.x * ratio
            across = desired - (crossSize - extent) / 2
            scale = next
        }
        fun zoomFromCenter(target: Float) {
            val viewer = viewportCoordinates?.takeIf { it.isAttached }
            if (viewer == null) {
                scale = target.coerceIn(.25f, 3f)
                return
            }
            val center = viewer.localToWindow(Offset(viewer.size.width / 2f, viewer.size.height / 2f))
            beginZoom(center)
            zoomTo(target, center)
        }
        fun fitPage(index: Int = scroll.firstVisibleItemIndex) {
            selection = null
            draggingText = null
            zoomPadding = null
            across = 0f
            scale = fitScale
            scroll.requestScrollToItem(index)
        }
        fun crossPanLimit(): Float {
            val size = pageLayouts[scroll.firstVisibleItemIndex + 1]?.coordinates?.size ?: return 0f
            val extent = if (horizontal) size.height else size.width
            return ((extent - crossSize) / 2f).coerceAtLeast(0f)
        }
        SideEffect { paperPixels = with(density) { paperWidth.toPx() } }
        val answerScale = answerPosition?.let { answerScales[it] } ?: 1f
        Column(Modifier.fillMaxSize()) {
            if (focused != null && answerPosition != null) {
                val position = answerPosition
                val focusedId = position.first
                key(position) {
                    val savedScroll = remember { answerPositions[position] }
                    val answerScroll = remember { ScrollState(savedScroll ?: 0) }
                    DisposableEffect(answerScroll) { onDispose { answerPositions[position] = answerScroll.value } }
                    val receiving = focusedId in requests
                    val attachments = project.optJSONArray("assets").objects().associate {
                        val id = it.getString("id")
                        id to AttachmentPreview(id, it.getString("name"), file = workspace.assetFile(doc.item.id, it))
                    }
                    AnswerSheet(focused, focusedMessage, replies[focusedId], answerScale, receiving,
                        answerScroll, savedScroll, workspace.core, attachments, notes.filter { it.optString("parentId") == focusedId },
                        activeBranch = activeBranch, pendingQuote = selection?.answer?.quote,
                        onZoom = { factor -> answerScales[position] = ((answerScales[position] ?: 1f) * factor).coerceIn(.6f, 3f) },
                        onSource = ::readSource,
                        onStop = { requests[focusedId]?.cancel() },
                        onBranch = { parentMessageId, quote ->
                            if (noteId == focusedId && focusedMessage == parentMessageId) {
                                val target = AnswerTarget(focusedId, parentMessageId, quote)
                                val current = selection?.takeIf { it.answer?.noteId == focusedId && it.answer.messageId == parentMessageId }
                                if (current == null) {
                                    selection = QuestionSelection(answer = target)
                                    activeBranch = null
                                } else if (current.submission.noteId !in requests) {
                                    selection = current.copy(answer = target, location = quote.location)
                                }
                            }
                        },
                        onResumeDraft = { selection = QuestionSelection(draft = focused) },
                        onRetry = { question ->
                            request(QuestionSelection(), question, focused.optString("task", "explain"), false, focused, retry = true)
                        },
                        onChild = { id -> noteId = id; messageId = null; selection = null; activeBranch = null },
                    )
                }
            } else {
                val noteAnchors = notes.filterNot { it.has("parentId") }.flatMapIndexed { index, note ->
                    val root = note.getJSONObject("anchor")
                    root.anchorParts().map { PageNote(index + 1, note, it) }
                }.groupBy { it.anchor.getInt("page") }
                val handles = selection?.range?.takeIf { it.quote.isNotBlank() }?.handles().orEmpty()
                val pager = Modifier.fillMaxWidth().weight(1f).clipToBounds()
                    .onGloballyPositioned { viewportCoordinates = it }.onSizeChanged { viewportSize = it }.pdfTextSelection(
                    pdf, scroll, horizontal, !region, pageLayouts, pages,
                    handles = handles,
                    onZoomStart = ::beginZoom,
                    onZoom = { factor, position -> zoomTo(scale * factor, position) },
                    canPanAcross = { crossPanLimit() > 0f || across != 0f },
                    onPanAcross = { delta ->
                        val limit = crossPanLimit()
                        val previous = across
                        across = (across + delta).coerceIn(minOf(-limit, across), maxOf(limit, across))
                        across - previous
                    },
                    onDoubleTap = { position ->
                        beginZoom(position)
                        if (abs(scale - fitScale) < .05f) zoomTo(fitScale * 2, position)
                        else fitPage(zoomPivot?.item ?: scroll.firstVisibleItemIndex)
                    },
                    onPreview = { draggingText = it; if (it != null) { selection = null; located = null } },
                    onSelect = { range, position -> selection = QuestionSelection(range, location = position); located = null },
                    report = report,
                )
                val item: @Composable (Int) -> Unit = { number ->
                    val highlight = (draggingText ?: selection?.range)?.fragments?.firstOrNull { it.page == number } ?: located
                    PdfPageView(pdf, number, paperWidth, rotation, noteAnchors[number].orEmpty(), highlight, region, pageLayouts,
                        handles, horizontal, across,
                        onSelect = { range, position -> selection = QuestionSelection(PdfSelection(listOf(range)), location = position); located = null },
                        onNote = { id -> selection = null; messageId = null; noteId = id }, report = report)
                }
                val padding = zoomPadding ?: normalPadding
                val leading = with(density) { padding.leading.toDp() }
                val trailing = with(density) { padding.trailing.toDp() }
                if (horizontal) LazyRow(state = scroll, modifier = pager, verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(start = leading, end = trailing)) {
                    items(pdf.pageCount) { item(it + 1) }
                } else LazyColumn(state = scroll, modifier = pager, horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(top = leading, bottom = trailing)) {
                    items(pdf.pageCount) { item(it + 1) }
                }
            }
        }
        if (selection == null && draggingText == null && WindowInsets.ime.getBottom(density) == 0) {
            ReaderIsland(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp)) {
                ReaderButton("显示资料侧栏", R.drawable.inkask_ic_panel_left, action = openDrawer)
                if (answerPosition == null) {
                    ReaderButton("上一页", R.drawable.inkask_ic_chevron_left, enabled = page > 1) { go(page - 1) }
                    ReaderButton("页码", label = page.toString(), state = "第 $page 页，共 ${pdf.pageCount} 页", active = true) {
                        pageDialog = true
                    }
                    ReaderButton("下一页", R.drawable.inkask_ic_chevron_right, enabled = page < pdf.pageCount) { go(page + 1) }
                    if (width >= 320.dp) ReaderButton(
                        if (region) "正在框选" else "框选区域", R.drawable.inkask_ic_highlighter, active = region,
                    ) {
                        region = !region
                        selection = null
                    }
                } else {
                    ReaderButton("返回上一层", R.drawable.inkask_ic_chevron_left, action = ::back)
                    ReaderButton("便签路径", R.drawable.inkask_ic_git_branch, state = "第 ${timeline.size} 层") {
                        pathOpen = true
                    }
                }
                Box {
                    ReaderButton("阅读工具", R.drawable.inkask_ic_ellipsis, active = scanning != null) { toolsMenu = true }
                    DropdownMenu(
                        expanded = toolsMenu, onDismissRequest = { toolsMenu = false },
                        modifier = Modifier.widthIn(min = 218.dp, max = 300.dp).heightIn(max = 470.dp),
                        shape = ReaderCardShape, containerColor = Paper, tonalElevation = 0.dp,
                    ) {
                        Text(doc.item.name, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            val zoom = answerPosition?.let { answerScale } ?: scale
                            fun changeZoom(amount: Float) {
                                if (answerPosition == null) zoomFromCenter(scale + amount)
                                else answerScales[answerPosition] = (answerScale + amount).coerceIn(.6f, 3f)
                            }
                            ReaderButton(if (answerPosition == null) "缩小" else "缩小回答", R.drawable.inkask_ic_minus) { changeZoom(-.1f) }
                            ReaderButton("重置缩放", label = "${(zoom * 100).roundToInt()}%") {
                                if (answerPosition == null) fitPage() else answerScales[answerPosition] = 1f
                            }
                            ReaderButton(if (answerPosition == null) "放大" else "放大回答", R.drawable.inkask_ic_plus) { changeZoom(.1f) }
                        }
                        if (answerPosition == null) {
                            DropdownMenuItem(text = { Text(if (horizontal) "切换为上下拼接" else "切换为左右拼接") }, onClick = {
                                across = 0f; selection = null; horizontal = !horizontal; toolsMenu = false
                            })
                            DropdownMenuItem(text = { Text("旋转页面") }, onClick = {
                                across = 0f; selection = null; rotation = (rotation + 90) % 360; toolsMenu = false
                            })
                            if (width < 320.dp) DropdownMenuItem(text = { Text(if (region) "退出框选" else "框选区域") }, onClick = {
                                region = !region; selection = null; toolsMenu = false
                            })
                            if (scanning != null) DropdownMenuItem(text = { Text("取消识别") }, onClick = {
                                scanning?.cancel(); toolsMenu = false
                            }) else if (rawText.isBlank() || recognized != null) DropdownMenuItem(
                                text = { Text(if (recognized != null) "重新识别" else "识别本页") },
                                onClick = { recognizePage(); toolsMenu = false },
                            )
                        } else {
                            if (answers.size > 1) DropdownMenuItem(text = { Text("切换本便签的回答") }, onClick = {
                                toolsMenu = false; answerPicker = true
                            })
                            DropdownMenuItem(text = { Text("便签信息") }, onClick = {
                                toolsMenu = false; detailsNoteId = answerPosition.first
                            })
                        }
                        HorizontalDivider(color = Line)
                        ExportKind.entries.forEach { kind ->
                            DropdownMenuItem(text = { Text("导出${kind.label}") }, onClick = {
                                toolsMenu = false
                                export(kind)
                            })
                        }
                        DropdownMenuItem(text = { Text("帮助与设置") }, onClick = { toolsMenu = false; openSettings() })
                        DropdownMenuItem(text = { Text("返回资料库") }, onClick = { toolsMenu = false; openLibrary() })
                    }
                }
            }
        }
        selection?.let { selected ->
            key(selected.submission.noteId) {
                val roomWidth = constraints.maxWidth
                val roomHeight = constraints.maxHeight
                val margin = with(density) { 14.dp.roundToPx() }
                val at = selected.location?.minus(rootPosition)
                val bottom = width < 840.dp || WindowInsets.ime.getBottom(density) > 0 || at == null
                val overlay = if (bottom) Modifier.align(Alignment.BottomCenter).padding(10.dp)
                else Modifier.align(Alignment.TopStart).offset {
                    val maxX = (roomWidth - popupSize.width - margin).coerceAtLeast(margin)
                    val maxY = (roomHeight - popupSize.height - margin).coerceAtLeast(margin)
                    val anchor = requireNotNull(at)
                    val down = anchor.y.roundToInt() + margin
                    val y = if (down <= maxY) down else anchor.y.roundToInt() - (popupSize.height + margin)
                    IntOffset((anchor.x.roundToInt() + margin).coerceIn(margin, maxX), y.coerceIn(margin, maxY))
                }
                val savedImages = selected.draft?.optJSONArray("draftAssetIds").strings()
                val previews = project.optJSONArray("assets").objects().filter { it.optString("id") in savedImages }.map {
                    AttachmentPreview(it.getString("id"), it.getString("name"), file = workspace.assetFile(doc.item.id, it))
                }
                SelectionPanel(selected.quote, selected.range?.quote?.isBlank() == true,
                    selected.range != null, profile != null, selected.submission.noteId in requests,
                    modifier = overlay.onSizeChanged { popupSize = it }, maxHeight = maxHeight * .72f, bottom = bottom,
                    templates = model.templates(project), draft = selected.draft, savedImages = previews, core = workspace.core,
                    onDismiss = { if (selection?.submission == selected.submission) selection = null },
                    submit = { question, task, screenshot, images, template, save, retained ->
                        request(selected, question, task, screenshot, externalImages = images,
                            templateId = template?.getString("id"), saveOnly = save, retainedImages = retained)
                    },
                    settings = openSettings, report = report)
            }
        }
    }

    if (pathOpen && timeline.isNotEmpty()) {
        val originPage = timeline.first().getJSONObject("anchor").getInt("page")
        ReaderPath(doc.item.name, originPage, timeline, focusedMessage,
            onPdf = {
                pathOpen = false
                activeBranch = null
                selection = null
                located = null
                go(originPage)
            },
            onNote = { index ->
                val child = timeline.getOrNull(index + 1)
                activeBranch = child?.optString("id")
                messageId = child?.optString("parentMessageId")?.ifBlank { null }
                noteId = timeline[index].getString("id")
                selection = null
                pathOpen = false
            },
            onDismiss = { pathOpen = false },
        )
    }
    if (answerPicker && answers.size > 1) {
        AlertDialog(
            onDismissRequest = { answerPicker = false },
            title = { Text("切换本便签的回答") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(answers.size) { index ->
                        val id = answers[index].getString("id")
                        Control("回答 ${index + 1}", active = id == focusedMessage) {
                            messageId = id
                            selection = null
                            activeBranch = null
                            answerPicker = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { answerPicker = false }) { Text("关闭") } },
            containerColor = Paper,
        )
    }
    notes.firstOrNull { it.optString("id") == detailsNoteId }?.let { current ->
        val id = current.getString("id")
        ReaderNoteDetails(current, canDelete = requests.isEmpty(),
            onSave = { value -> editNote(id) { it.put("title", value) }; detailsNoteId = null },
            onDelete = {
                if (requests.isNotEmpty()) report("先等待当前回答结束，再删除便签。") else {
                    detailsNoteId = null
                    scope.launch {
                        try {
                            publishProject(withContext(Dispatchers.IO) { workspace.removeNote(doc.item.id, id) })
                            if (noteId == id) back()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { report("无法删除便签。") }
                    }
                }
            },
            onDismiss = { detailsNoteId = null },
        )
    }

    if (pageDialog) {
        var input by remember { mutableStateOf(page.toString()) }
        AlertDialog(onDismissRequest = { pageDialog = false }, title = { Text("跳转页码") },
            text = { OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("页码") }, supportingText = { Text("共 ${pdf.pageCount} 页") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { TextButton(onClick = { input.toIntOrNull()?.let(::go); pageDialog = false }) { Text("跳转") } },
            dismissButton = { TextButton(onClick = { pageDialog = false }) { Text("取消") } })
    }

    preview?.let { data ->
        val id = data.source.optString("id")
        val sourcePage = data.source.getInt("page")
        val pageLabel = "第 $sourcePage 页"
        fun locate(range: PdfRange?) {
            located = range
            val first = range?.boxes?.firstOrNull()?.let { android.graphics.RectF(it) }
            val offset = if (first == null) 0 else {
                rotationMatrix(data.size, rotation).mapRect(first)
                val portion = paperPixels / data.size.rotated(rotation).width
                ((if (horizontal) first.left else first.top) * portion - 90).roundToInt().coerceAtLeast(0)
            }
            go(sourcePage, offset)
            preview = null
        }
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(if (id.isBlank()) pageLabel else "[$id] · $pageLabel") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                data.parts.forEach { part ->
                    Text(part.label, color = Muted)
                    part.image?.let { bytes -> remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }?.let {
                        Image(it.asImageBitmap(), "引用原文截图", modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    } }
                    Text(part.text, color = Ink)
                    if (data.parts.size > 1 && part.range != null) Control("定位此段") { locate(part.range) }
                }
            } },
            confirmButton = { TextButton(onClick = { locate(data.parts.firstNotNullOfOrNull { it.range }) }) { Text("定位原文") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
    }
}

private data class RequestDraft(val project: JSONObject, val prompt: String, val history: List<Turn>,
                                val images: List<InputImage>, val answer: JSONObject?)
