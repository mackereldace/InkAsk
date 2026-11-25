package app.inkask.tablet

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.Dp
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

private data class QuestionSelection(val range: PdfSelection, val parent: JSONObject? = null,
                                     val branchQuote: String = "", val offset: Int? = null, val parentMessage: String? = null,
                                     val location: Offset? = null, val submission: QuestionSubmission = QuestionSubmission())
private data class SourcePreviewPart(val label: String, val text: String, val range: PdfRange?, val image: ByteArray?)
private data class SourcePreview(val source: JSONObject, val size: PageSize, val parts: List<SourcePreviewPart>)
private data class PdfZoomPivot(
    val item: Int, val point: Offset, val size: IntSize, val scale: Float,
    val distanceToStart: Int?, val distanceToEnd: Int?,
)

@Composable
internal fun ReaderScreen(doc: Opened, workspace: Workspace, model: ReadingModel, profile: ModelProfile?,
                          openSettings: () -> Unit, report: (String) -> Unit) {
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
    val drafts = remember { mutableStateMapOf<String, String>() }
    val draftJobs = remember { mutableMapOf<String, Job>() }
    val replies = remember { mutableStateMapOf<String, String>() }
    val requests = remember { mutableStateMapOf<String, Job>() }
    var scanning by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<SourcePreview?>(null) }
    var pageDialog by remember { mutableStateOf(false) }
    var pendingJump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var paperPixels by remember { mutableFloatStateOf(0f) }
    var answerScale by remember { mutableFloatStateOf(1f) }
    var across by remember { mutableFloatStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var zoomPivot by remember { mutableStateOf<PdfZoomPivot?>(null) }
    var directionMenu by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    val verticalState = rememberLazyListState(page - 1)
    val horizontalState = rememberLazyListState(page - 1)
    val scroll = if (horizontal) horizontalState else verticalState
    var zoomPadding by remember(scroll) { mutableStateOf<PdfScrollPadding?>(null) }
    val pageLayouts = remember(pdf) { mutableMapOf<Int, PdfPageLayout>() }
    val notes = project.optJSONArray("notes").objects()
    val focused = notes.firstOrNull { it.optString("id") == noteId }
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
        messageId = focused?.optString("parentMessageId")?.ifEmpty { null }
        noteId = focused?.optString("parentId")?.ifEmpty { null }
    }
    BackHandler(enabled = focused != null || selection != null) { if (selection != null) selection = null else back() }

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
                templateId: String? = null, saveOnly: Boolean = false) {
        val existingId = existing?.getString("id")
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
        val needsImage = range.quote.isBlank() && existing == null && selected.parent == null
        val captures = if (screenshot || needsImage) range.fragments else emptyList()
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
        if (!retry) draftJobs.remove(askId)?.cancel()
        val origin = noteId to messageId
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            val now = System.currentTimeMillis()
            var answer: JSONObject? = null
            var finalStatus = "complete"
            var errorMessage: String? = null
            val output = StringBuilder()
            try {
                val result = withContext(Dispatchers.IO) {
                    val anchor = range.anchor(pdf::size)
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
                        it.hasCommittedSubmission(askId, submission, saveOnly, retryTarget)
                    }) { content, ids ->
                        val notes = content.getJSONArray("notes")
                        val index = notes.objects().indexOfFirst { it.getString("id") == askId }
                        val saved = notes.optJSONObject(index)
                        val template = model.template(content, task, templateId ?: saved?.optString("templateId"))
                        val note = saved ?: JSONObject().put("id", askId).put("title", text.take(100))
                            .put("anchor", anchor).put("collapsed", false).put("task", template.getString("task"))
                            .put("templateId", template.getString("id")).put("draft", "")
                            .put("draftAssetIds", JSONArray()).put("messages", JSONArray())
                            .put("inheritedHistory", JSONArray()).put("createdAt", now)
                        selected.parent?.takeIf { saved == null }?.let { parent ->
                            val parentId = parent.getString("id")
                            val parentMessage = requireNotNull(selected.parentMessage)
                            val currentParent = notes.objects().firstOrNull { it.optString("id") == parentId }
                            require(currentParent?.optJSONArray("messages").objects().any {
                                it.optString("id") == parentMessage && it.optString("role") == "assistant"
                            }) { "所选回答已不存在。" }
                            val messages = parent.optJSONArray("messages").objects()
                            note.put("parentId", parentId).put("branchQuote", selected.branchQuote).put("parentMessageId", parentMessage)
                            val parentIndex = messages.indexOfFirst { it.optString("id") == parentMessage }
                            require(parentIndex >= 0) { "无法定位追问所使用的回答。" }
                            val through = messages.take(parentIndex + 1)
                            selected.offset?.let { offset ->
                                note.put("answerAnchor", JSONObject().put("start", offset)
                                    .put("end", offset + selected.branchQuote.length).put("quote", selected.branchQuote))
                            }
                            note.put("inheritedHistory", JSONArray((parent.optJSONArray("inheritedHistory").objects() + through).map {
                                JSONObject().put("role", it.getString("role")).put("content", it.optString("content"))
                                    .put("assetIds", it.optJSONArray("assetIds") ?: JSONArray())
                            }))
                        }
                        val messages = note.getJSONArray("messages")
                        val lastAssistant = messages.optJSONObject(messages.length() - 1)?.takeIf {
                            retry && it.optString("role") == "assistant"
                        }
                        if (lastAssistant != null) messages.remove(messages.length() - 1)
                        val priorUser = messages.objects().lastOrNull { it.optString("role") == "user" }?.takeIf { retry }
                        val assetIds = priorUser?.optJSONArray("assetIds")
                            ?: JSONArray(note.optJSONArray("draftAssetIds").strings() + ids)
                        require(retry || assetIds.length() <= 4) { "每次最多附 4 张图片。" }
                        if (!retry) note.put("draftAssetIds", assetIds)
                        if (captures.isNotEmpty() && saved == null) {
                            note.put("sourceAssetId", ids.first())
                            if (captures.size > 1) note.put("sourceAssetIds", JSONArray(ids.take(captures.size)))
                        }
                        if (saveOnly) {
                            note.put("draft", question)
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
                        if (priorUser == null) note.put("draft", "").put("draftAssetIds", JSONArray())
                        if (index == -1) notes.put(note) else notes.put(index, note)
                        draft = RequestDraft(content, body, requestHistory, requestImages, assistant)
                    }
                    draft?.copy(project = updated) ?: RequestDraft(updated, "", emptyList(), emptyList(), null)
                }
                answer = result.answer
                publishProject(result.project)
                if (!retry) drafts.remove(askId)
                if (selection === selected) selection = null
                if ((noteId to messageId) == origin) {
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

    fun editDraft(note: JSONObject, value: String) {
        val id = note.getString("id")
        val messages = note.getJSONArray("messages").length()
        drafts[id] = value
        draftJobs.remove(id)?.cancel()
        draftJobs[id] = scope.launch {
            delay(150)
            try {
                withContext(Dispatchers.IO) {
                    workspace.update(doc.item.id) { content ->
                        content.getJSONArray("notes").objects().firstOrNull {
                            it.getString("id") == id && it.getJSONArray("messages").length() == messages
                        }?.put("draft", value)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { report("草稿无法保存。") }
        }
    }

    fun attachToDraft(id: String, uris: List<Uri>) {
        if (id in requests) return
        scope.launch {
            try {
                publishProject(withContext(Dispatchers.IO) {
                    require(uris.size <= 4) { "每次最多附 4 张图片。" }
                    val images = uris.map { uri ->
                        val (name, bytes) = workspace.readImage(uri)
                        QuestionImage(name, bytes)
                    }
                    workspace.updateWithImages(doc.item.id, images) { content, ids ->
                        val note = content.getJSONArray("notes").objects().first { it.getString("id") == id }
                        val all = note.getJSONArray("draftAssetIds").strings() + ids
                        require(all.size <= 4) { "每次最多附 4 张图片。" }
                        note.put("draftAssetIds", JSONArray(all))
                    }
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { report(error.message ?: "图片无法读取。") }
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
        Column(Modifier.fillMaxSize()) {
            if (focused != null) {
                Row(Modifier.fillMaxWidth().height(52.dp).background(Bar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Control("‹ 返回上一层", active = true) { back() }
                        Control("原始 PDF") { noteId = null; selection = null; messageId = null }
                        timeline.forEach { branch ->
                            Text("›", color = Muted)
                            Control(branch.optString("title").take(18), enabled = branch.getString("id") != noteId) {
                                val target = branch.getString("id")
                                messageId = timeline.firstOrNull { it.optString("parentId") == target }
                                    ?.optString("parentMessageId")?.ifEmpty { null }
                                noteId = target; selection = null
                            }
                        }
                    }
                    Control("−", "缩小回答") { answerScale = (answerScale - .1f).coerceAtLeast(.6f) }
                    Text("${(answerScale * 100).toInt()}%", color = Muted, fontSize = 12.sp)
                    Control("＋", "放大回答") { answerScale = (answerScale + .1f).coerceAtMost(3f) }
                }
                HorizontalDivider(color = Line)
                val focusedId = focused.getString("id")
                val focusedMessage = messageId ?: focused.optJSONArray("messages").objects()
                    .lastOrNull { it.optString("role") == "assistant" }?.optString("id")
                val position = focusedId to focusedMessage
                key(position) {
                    val answerScroll = remember { ScrollState(answerPositions[position] ?: 0) }
                    DisposableEffect(answerScroll) { onDispose { answerPositions[position] = answerScroll.value } }
                    val receiving = focusedId in requests
                    val latestMessage = focused.optJSONArray("messages").objects().lastOrNull()
                    val submission = remember(focusedId, latestMessage?.optString("id")) { QuestionSubmission(noteId = focusedId) }
                    val attachments = project.optJSONArray("assets").objects().associate {
                        val id = it.getString("id")
                        id to AttachmentPreview(id, it.getString("name"), file = workspace.assetFile(doc.item.id, it))
                    }
                    AnswerSheet(focused, focusedMessage, replies[focusedId], answerScale, receiving,
                        answerScroll, workspace.core, attachments, notes.filter { it.optString("parentId") == focusedId },
                        templates = model.templates(project),
                        onZoom = { factor -> answerScale = (answerScale * factor).coerceIn(.6f, 3f) },
                        onSource = ::readSource,
                        onStop = { requests[focusedId]?.cancel() },
                        onMessage = { messageId = it },
                        onBranch = { parentMessageId, selected ->
                            scope.launch {
                                val anchor = focused.getJSONObject("anchor")
                                val range = withContext(Dispatchers.IO) { anchor.pdfSelection(pdf::size) }
                                val parent = JSONObject(focused.toString())
                                val turns = parent.getJSONArray("messages")
                                if (turns.objects().any {
                                    it.optString("id") == parentMessageId && it.optString("status") != "streaming"
                                } && noteId == focusedId) {
                                    val active = selection
                                    if (active?.parent?.optString("id") != focusedId || active.parentMessage != parentMessageId ||
                                        active.offset != selected.start || active.branchQuote != selected.quote) {
                                        selection = QuestionSelection(range, parent, selected.quote, selected.start, parentMessageId, selected.location)
                                    }
                                }
                            }
                        },
                        onRequest = { question, retry ->
                            scope.launch {
                                val anchor = focused.getJSONObject("anchor")
                                val range = withContext(Dispatchers.IO) { anchor.pdfSelection(pdf::size) }
                                request(QuestionSelection(range, submission = submission), question,
                                    focused.optString("task", "explain"), false, focused, retry)
                            }
                        },
                        onDelete = {
                            if (requests.isNotEmpty()) return@AnswerSheet report("先等待当前回答结束，再删除便签。")
                            scope.launch {
                                try {
                                    publishProject(withContext(Dispatchers.IO) { workspace.removeNote(doc.item.id, focused.getString("id")) })
                                    back()
                                } catch (_: Exception) { report("无法删除便签。") }
                            }
                        },
                        onChild = { id -> noteId = id; messageId = null; selection = null },
                        draft = drafts[focusedId] ?: focused.optString("draft"),
                        onDraft = { editDraft(focused, it) },
                        onTitle = { value -> editNote(focusedId) { it.put("title", value) } },
                        onTemplate = { template -> editNote(focusedId) { note ->
                            note.put("templateId", template.getString("id")).put("task", template.getString("task"))
                        } },
                        onAttach = { attachToDraft(focusedId, it) },
                        onRemoveImage = { id -> editNote(focusedId) { note ->
                            note.put("draftAssetIds", JSONArray(note.optJSONArray("draftAssetIds").strings().filterNot { it == id }))
                        } },
                        report = report,
                    )
                }
            } else {
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
                Row(Modifier.fillMaxWidth().height(52.dp).background(Bar).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Control("‹", "上一页", enabled = page > 1) { go(page - 1) }
                    Control("$page / ${pdf.pageCount}", "页码", active = true) { pageDialog = true }
                    Control("›", "下一页", enabled = page < pdf.pageCount) { go(page + 1) }
                    Spacer(Modifier.weight(1f))
                    Control("−", "缩小") { zoomFromCenter(scale - .1f) }
                    Control("${(scale * 100).toInt()}%", "重置缩放") { fitPage() }
                    Control("＋", "放大") { zoomFromCenter(scale + .1f) }
                    Box {
                        Control(if (horizontal) "左右 ▾" else "上下 ▾", "页面拼接方向") { directionMenu = true }
                        DropdownMenu(expanded = directionMenu, onDismissRequest = { directionMenu = false }, shape = ControlShape,
                            containerColor = Paper, tonalElevation = 0.dp) {
                            DropdownMenuItem(text = { Text("上下拼接") }, onClick = { across = 0f; selection = null; horizontal = false; directionMenu = false })
                            DropdownMenuItem(text = { Text("左右拼接") }, onClick = { across = 0f; selection = null; horizontal = true; directionMenu = false })
                        }
                    }
                    if (width >= 560.dp || region) {
                        Control(if (region) "正在框选" else "框选区域", active = region) { region = !region; selection = null }
                    }
                    Box {
                        Control("⋯", "阅读工具") { toolsMenu = true }
                        DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }, containerColor = Paper) {
                            DropdownMenuItem(text = { Text("适合宽度") }, onClick = { zoomFromCenter(fitScale); toolsMenu = false })
                            DropdownMenuItem(text = { Text("旋转页面") }, onClick = {
                                across = 0f; selection = null; rotation = (rotation + 90) % 360; toolsMenu = false
                            })
                            if (width < 560.dp && !region) DropdownMenuItem(text = { Text("框选区域") }, onClick = {
                                region = true; selection = null; toolsMenu = false
                            })
                            if (scanning != null) DropdownMenuItem(text = { Text("取消识别") }, onClick = {
                                scanning?.cancel(); toolsMenu = false
                            }) else if (rawText.isBlank() || recognized != null) DropdownMenuItem(
                                text = { Text(if (recognized != null) "重新识别" else "识别本页") },
                                onClick = { recognizePage(); toolsMenu = false },
                            )
                        }
                    }
                }
                HorizontalDivider(color = Line)
                if (profile == null) {
                    Row(Modifier.fillMaxWidth().background(PaleGreen).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("阅读已就绪。配置模型 API 后，即可在便签中获得 AI 回答。", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Control("如何配置", action = openSettings)
                    }
                }
                val noteAnchors = notes.filterNot { it.has("parentId") }.flatMapIndexed { index, note ->
                    val root = note.getJSONObject("anchor")
                    root.anchorParts().map { PageNote(index + 1, note, it) }
                }.groupBy { it.anchor.getInt("page") }
                val handles = selection?.range?.takeIf { it.quote.isNotBlank() && selection?.parent == null }?.handles().orEmpty()
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
                HorizontalDivider(color = Line)
                Text(if (scanning != null) "正在识别第 $page 页"
                    else "${notes.count { !it.has("parentId") }} 条便签 · ${profile?.model ?: "本地阅读"}", color = Muted, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(Bar).padding(horizontal = 22.dp, vertical = 7.dp))
            }
        }
        selection?.let { selected ->
            key(selected) {
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
                SelectionPanel(selected.branchQuote.ifBlank { selected.range.quote }, selected.range.quote.isBlank() && selected.parent == null,
                    selected.parent == null, profile != null, selected.submission.noteId in requests,
                    modifier = overlay.onSizeChanged { popupSize = it }, maxHeight = maxHeight * .72f, bottom = bottom,
                    templates = model.templates(project),
                    dismiss = { selection = null },
                    submit = { question, task, screenshot, images, template, save ->
                        request(selected, question, task, screenshot, externalImages = images,
                            templateId = template?.getString("id"), saveOnly = save)
                    },
                    settings = openSettings, report = report)
            }
        }
    }

    if (pageDialog) {
        var input by remember { mutableStateOf(page.toString()) }
        AlertDialog(onDismissRequest = { pageDialog = false }, title = { Text("跳转页码") },
            text = { OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("页码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
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

@Composable
private fun SelectionPanel(quote: String, imageOnly: Boolean, cropAvailable: Boolean, configured: Boolean, busy: Boolean,
                           modifier: Modifier, maxHeight: Dp, bottom: Boolean, templates: List<JSONObject>, dismiss: () -> Unit,
                           submit: (String, String, Boolean, List<Uri>, JSONObject?, Boolean) -> Unit,
                           settings: () -> Unit, report: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf(imageOnly) }
    var images by remember { mutableStateOf(emptyList<Uri>()) }
    var template by remember { mutableStateOf<JSONObject?>(null) }
    fun attach(uris: List<Uri>) {
        val incoming = (images + uris).distinct()
        if (incoming.size > 4) report("每次最多附 4 张图片。") else images = incoming
    }
    fun send(task: String, save: Boolean = false) {
        if (busy) return
        if (configured || save) submit(question, template?.getString("task") ?: task, capture, images, template, save) else settings()
    }
    var tasksOpen by remember { mutableStateOf(false) }
    Surface(modifier = modifier.widthIn(max = if (bottom) 560.dp else 410.dp).fillMaxWidth(),
        color = Paper, border = androidx.compose.foundation.BorderStroke(1.dp, Line), shadowElevation = 8.dp, shape = ReaderCardShape) {
        Column(Modifier.heightIn(max = maxHeight.coerceAtLeast(142.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quote.ifBlank { "选中的 PDF 区域" }, color = Muted, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.weight(1f))
                Control("×", "关闭选区", action = dismiss)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                OutlinedTextField(question, { question = it.take(4_000) },
                    placeholder = { Text("想了解什么？留空则解释所选内容") }, minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().pasteImages(::attach).questionShortcut {
                        send(if (question.isBlank()) "explain" else "custom")
                    })
                if (cropAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(capture, { capture = it }, enabled = !imageOnly)
                    Text("附选区截图", color = Muted, fontSize = 13.sp)
                }
                AttachmentPreviews(images.map { uri ->
                    AttachmentPreview(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "图片", uri = uri)
                }) { id -> images = images.filterNot { it.toString() == id } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AttachmentControls(::attach, report)
                    TemplateMenu(templates, template?.getString("id"), automatic = true) { template = it }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Control("保存草稿", enabled = !busy) { send(if (question.isBlank()) "explain" else "custom", true) }
                Box {
                    Control("更多任务", enabled = !busy && template == null) { tasksOpen = true }
                    DropdownMenu(tasksOpen, { tasksOpen = false }, containerColor = Paper) {
                        listOf("总结" to "summarize", "解释公式" to "formula", "翻译" to "translate").forEach { (label, task) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { tasksOpen = false; send(task) })
                        }
                    }
                }
                Button(onClick = { send(if (question.isBlank()) "explain" else "custom") },
                    enabled = !busy, shape = ControlShape) { Text(if (busy) "正在保存…" else if (configured) "提问" else "配置模型") }
            }
        }
    }
}
