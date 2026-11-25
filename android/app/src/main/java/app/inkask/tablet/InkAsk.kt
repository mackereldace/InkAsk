package app.inkask.tablet

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

internal val Desk = Color(0xFFF0EFE9)
internal val Bar = Color(0xFFF7F7F1)
internal val Paper = Color(0xFFFFFEFA)
internal val Ink = Color(0xFF293E33)
internal val Green = Color(0xFF32664C)
internal val Muted = Color(0xFF566A5B)
internal val Line = Color(0xFFE0E5D7)
internal val PaleGreen = Color(0xFFECF1E5)
internal val ControlShape = RoundedCornerShape(9.dp)
internal val ReaderCardShape = RoundedCornerShape(14.dp)

internal data class Opened(val item: PdfItem, val project: JSONObject, val pdf: NativePdf)
internal enum class ExportKind(val label: String, val suffix: String) {
    PROJECT("完整项目 (.inkask)", ".inkask"),
    MARKDOWN("笔记 Markdown", ".md"),
    NOTES("笔记目录 (.zip)", "-笔记目录.zip"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InkAsk(workspace: Workspace) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val model = remember(context) { ReadingModel(context) }
    var library by remember { mutableStateOf(Library(emptyList(), emptyList(), emptyMap())) }
    var opened by remember { mutableStateOf<Opened?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var drawer by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var trouble by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exportRequest by remember { mutableStateOf<Pair<String, ExportKind>?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(model.active()) }
    val readerLayout = remember(context) { context.getSharedPreferences("reader-layout", Context.MODE_PRIVATE) }
    var islandPosition by remember(readerLayout) {
        val edge = ReaderIslandEdge.entries.firstOrNull { it.name == readerLayout.getString("island-edge", null) }
            ?: ReaderIslandEdge.Bottom
        mutableStateOf(ReaderIslandPosition(
            readerLayout.getFloat("island-x", .5f), readerLayout.getFloat("island-y", 1f), edge,
            freeVertical = readerLayout.getBoolean("island-free-vertical", false),
        ))
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        trouble = ""
        scope.launch {
            try { block() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { trouble = error.localizedMessage ?: "操作未完成，请重试。" }
            finally { busy = false }
        }
    }

    suspend fun refresh() {
        library = withContext(Dispatchers.IO) { workspace.library() }
    }

    suspend fun open(item: PdfItem) {
        val next = withContext(Dispatchers.IO) {
            val project = workspace.project(item.id)
            Opened(item, project, NativePdf(workspace.pdfFile(item.id)))
        }
        opened = next
        drawer = false
    }
    fun showLibrary(target: String? = folder) {
        val refreshNeeded = opened != null
        opened = null
        folder = target
        drawer = false
        if (refreshNeeded) act { refresh() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act {
            val item = withContext(Dispatchers.IO) { workspace.import(uri, folder) }
            refresh()
            open(item)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val request = exportRequest
        exportRequest = null
        if (uri != null && request != null) act {
            val (id, kind) = request
            withContext(Dispatchers.IO) {
                if (kind == ExportKind.PROJECT) workspace.exportArchive(id, uri)
                else workspace.exportNotes(id, uri, kind == ExportKind.NOTES)
            }
        }
    }
    val addPdf = { picker.launch(arrayOf("application/pdf")) }
    val importArchive = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
    LaunchedEffect(workspace) { act { refresh() } }
    BackHandler(enabled = drawer || opened != null) {
        if (drawer) drawer = false else showLibrary()
    }

    val libraryStatusInset = if (opened == null) {
        Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
    } else Modifier
    Box(Modifier.fillMaxSize().background(Desk).safeDrawingPadding().then(libraryStatusInset).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            if (opened == null) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).background(Bar).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Control("▣", "显示资料侧栏") { drawer = !drawer }
                    Spacer(Modifier.width(10.dp))
                    InkIcon(R.drawable.inkask_ic_book_open, color = Muted)
                    Text("阅读空间", color = Muted, modifier = Modifier.clickable { showLibrary(null) }.padding(6.dp))
                    Spacer(Modifier.weight(1f))
                    Control("?", "帮助与设置") { showSettings = true }
                }
                HorizontalDivider(color = Line)
            }
            if (trouble.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFF2DB)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(trouble, Modifier.weight(1f), fontSize = 13.sp)
                    Control("×", "关闭通知") { trouble = "" }
                }
            }
            val document = opened
            if (document == null) {
                LibraryScreen(library, folder, query, { query = it }, { folder = it }, busy, addPdf, importArchive,
                    sample = { act { open(withContext(Dispatchers.IO) { workspace.sample(folder) }); refresh() } },
                    open = { item -> act { open(item) } },
                    create = { name -> act { withContext(Dispatchers.IO) { workspace.createFolder(name, folder) }; refresh() } },
                    rename = { id, name -> act { withContext(Dispatchers.IO) { workspace.renameFolder(id, name) }; refresh() } },
                    removeFolder = { id -> act { withContext(Dispatchers.IO) { workspace.removeFolder(id) }; refresh() } },
                    move = { items, target, success -> act {
                        withContext(Dispatchers.IO) { workspace.moveItems(items, target) }
                        refresh()
                        success()
                    } },
                    remove = { ids -> act { withContext(Dispatchers.IO) { workspace.remove(ids) }; refresh() } },
                )
            } else {
                key(document.item.id) {
                    DisposableEffect(document.pdf) {
                        onDispose { scope.launch(Dispatchers.IO) { document.pdf.close() } }
                    }
                    ReaderScreen(
                        document, workspace, model, profile, drawerOpen = drawer,
                        islandPosition = islandPosition,
                        onMoveIsland = { position ->
                            islandPosition = position
                            readerLayout.edit().putFloat("island-x", position.x).putFloat("island-y", position.y)
                                .putString("island-edge", position.edge.name)
                                .putBoolean("island-free-vertical", position.vertical).apply()
                        },
                        openDrawer = { drawer = true },
                        openLibrary = { showLibrary(null) },
                        openSettings = { showSettings = true },
                        export = { kind ->
                            exportRequest = document.item.id to kind
                            export.launch(document.item.name.substringBeforeLast('.') + kind.suffix)
                        },
                        report = { trouble = it },
                    )
                }
            }
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f)).clickable { drawer = false })
            Column(Modifier.fillMaxHeight().width(238.dp).background(Bar).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("inkask", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Control("×", "关闭资料侧栏") { drawer = false }
                }
                Text("我的阅读空间", modifier = Modifier.padding(vertical = 16.dp), fontWeight = FontWeight.SemiBold)
                Button(onClick = addPdf, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text("＋ 打开 PDF") }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(query, { query = it }, label = { Text("搜索资料") }, singleLine = true)
                LazyColumn(Modifier.weight(1f).padding(top = 14.dp)) {
                    item { Control("阅读空间", "阅读空间") { showLibrary(null) } }
                    items(library.folders.filter { it.parentId == null || it.parentId == folder }) {
                        Control(it.name, it.name) { showLibrary(it.id) }
                    }
                    items(library.documents.filter { it.name.contains(query, true) }) {
                        Control(it.name, it.name) { act { open(it) } }
                    }
                }
                Control("导入 InkAsk 项目", "导入 InkAsk 项目", action = importArchive)
                Control("API 与模板") { showSettings = true; drawer = false }
                Text("文档保存在你的设备上", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (showSettings) {
        val document = opened
        val templates = document?.let { model.templates(it.project) }.orEmpty()
        val storage by produceState<LocalStorage?>(null, library) {
            value = withContext(Dispatchers.IO) { runCatching { workspace.storage() }.getOrNull() }
        }
        ModelSettings(model, profile, templates, saveTemplate = { id, name, body, copy ->
            if (document != null) act {
                val project = withContext(Dispatchers.IO) {
                    workspace.update(document.item.id) { current ->
                        val list = model.templates(current).toMutableList()
                        val position = list.indexOfFirst { it.getString("id") == id }
                        if (position >= 0) {
                            val target = list[position]
                            val template = JSONObject(target.toString()).put("name", name.trim())
                                .put("body", body).put("version", if (copy == null) target.getInt("version") + 1 else 1)
                            if (copy == null) list[position] = template else list.add(template.put("id", copy))
                        }
                        current.put("templates", JSONArray(list))
                    }
                }
                opened = document.copy(project = project)
            }
        }, storage = storage, onChange = { profile = model.active() }, close = { showSettings = false })
    }
}

@Composable
internal fun Control(
    label: String,
    description: String = label,
    enabled: Boolean = true,
    active: Boolean = false,
    action: () -> Unit,
) {
    val icon = when (description) {
        "显示资料侧栏" -> R.drawable.inkask_ic_panel_left
        "关闭资料侧栏", "关闭通知", "关闭选区", "关闭设置" -> R.drawable.inkask_ic_close
        "帮助与设置" -> R.drawable.inkask_ic_circle_help
        "返回上一级文件夹", "上一页", "‹ 返回上一层" -> R.drawable.inkask_ic_chevron_left
        "下一页" -> R.drawable.inkask_ic_chevron_right
        "缩小", "缩小回答" -> R.drawable.inkask_ic_minus
        "放大", "放大回答" -> R.drawable.inkask_ic_plus
        "旋转页面" -> R.drawable.inkask_ic_rotate_cw
        "适合宽度" -> R.drawable.inkask_ic_maximize
        "图标视图" -> R.drawable.inkask_ic_grid
        "列表视图" -> R.drawable.inkask_ic_list
        "导出" -> R.drawable.inkask_ic_arrow_down_to_line
        "框选区域", "正在框选" -> R.drawable.inkask_ic_highlighter
        else -> null
    }
    val text = when (description) {
        "导出" -> "导出"
        "‹ 返回上一层" -> "返回上一层"
        "框选区域", "正在框选" -> label
        else -> if (icon == null) label else ""
    }
    TextButton(onClick = action, enabled = enabled, shape = ControlShape,
        colors = ButtonDefaults.textButtonColors(containerColor = if (active) PaleGreen else Color.Transparent),
        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (icon != null) InkIcon(icon)
        if (text.isNotEmpty()) {
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun InkIcon(id: Int, color: Color = LocalContentColor.current, size: Int = 16) {
    Icon(painterResource(id), contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

@Composable
private fun LibraryScreen(
    library: Library, folderId: String?, query: String, search: (String) -> Unit, navigate: (String?) -> Unit,
    busy: Boolean, addPdf: () -> Unit, importArchive: () -> Unit, sample: () -> Unit,
    open: (PdfItem) -> Unit, create: (String) -> Unit, rename: (String, String) -> Unit, removeFolder: (String) -> Unit,
    move: (Set<LibraryItem>, String?, () -> Unit) -> Unit, remove: (Set<String>) -> Unit,
) {
    var grid by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var selected by remember(folderId, query) { mutableStateOf(setOf<LibraryItem>()) }
    var cut by remember { mutableStateOf(setOf<LibraryItem>()) }
    var anchor by remember(folderId, query) { mutableStateOf<LibraryItem?>(null) }
    val drag = remember(folderId, query, grid) { LibraryDragInteraction() }
    val rules = remember(library) { LibraryDropRules(library) }
    val gridState = key(folderId, query, grid) { rememberLazyGridState() }
    val draggedItems by remember(drag) { derivedStateOf { drag.session?.items.orEmpty() } }
    val dropTarget by remember(drag) { derivedStateOf { drag.session?.destination } }
    val scrollEdge = with(LocalDensity.current) { 56.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 1080.dp.toPx() }
    val contentsSite = LibraryDropSite(LibraryDropKind.Contents, folderId)
    var folderMenu by remember { mutableStateOf<Folder?>(null) }
    var moving by remember { mutableStateOf(setOf<LibraryItem>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val folder = library.folders.firstOrNull { it.id == folderId }
    val folders = library.folders.filter { if (query.isBlank()) it.parentId == folderId else it.name.contains(query.trim(), true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Folder::name))
    val files = library.documents.filter { if (query.isBlank()) library.membership[it.id] == folderId else it.name.contains(query.trim(), true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, PdfItem::name))
    val order = folders.map { LibraryItem(it.id, true) } + files.map { LibraryItem(it.id, false) }
    val trail = generateSequence(folder) { next -> library.folders.firstOrNull { it.id == next.parentId } }
        .take(library.folders.size).toList().asReversed()
    fun select(item: LibraryItem, additive: Boolean, range: Boolean = false) {
        selected = librarySelection(selected, order, item, anchor, additive, range)
        if (!range) anchor = item
    }
    fun clearSelection() {
        selected = emptySet()
        anchor = null
    }
    fun moveTo(items: Set<LibraryItem>, target: String?) {
        if (items.isEmpty() || busy) return
        move(items, target) {
            cut = cut - items
            clearSelection()
            moving = emptySet()
        }
    }

    LaunchedEffect(drag, draggedItems.isNotEmpty(), gridState, rules, scrollEdge, scrollSpeed) {
        if (drag.session == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag.session != null) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previous) / 1_000_000_000f).coerceAtMost(.04f)
            previous = frame
            val session = drag.session ?: break
            val bounds = drag.bounds(contentsSite) ?: continue
            if (session.point.x !in bounds.left..bounds.right ||
                session.point.y !in (bounds.top - scrollEdge)..(bounds.bottom + scrollEdge)) continue
            val delta = when {
                session.point.y < bounds.top + scrollEdge -> -scrollSpeed *
                    ((bounds.top + scrollEdge - session.point.y) / scrollEdge).coerceIn(0f, 1f)
                session.point.y > bounds.bottom - scrollEdge -> scrollSpeed *
                    ((session.point.y - bounds.bottom + scrollEdge) / scrollEdge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f) {
                gridState.scrollBy(delta * elapsed)
                drag.session?.let { drag.update(it.point, rules) }
            }
        }
    }

    @Composable
    fun entry(ref: LibraryItem, name: String, detail: String, openItem: () -> Unit) {
        val itemSite = LibraryDropSite(LibraryDropKind.Folder, ref.id)
        val source = ref in draggedItems
        DisposableEffect(drag, ref) {
            onDispose {
                drag.sources.remove(ref)
                drag.selectionControls.remove(ref)
            }
        }
        LibraryTile(
            name, detail, ref.folder, grid, ref in selected, ref in cut, source,
            draggingTo = ref.folder && dropTarget == itemSite, enabled = !busy,
            modifier = if (ref.folder) Modifier.libraryDropSite(drag, itemSite) else Modifier,
            body = Modifier.onGloballyPositioned { drag.sources[ref] = LibraryDragInteraction.Source(ref, name, it) },
            selectionControl = Modifier.onGloballyPositioned { drag.selectionControls[ref] = it },
            onSelect = { select(ref, additive = true) },
            onClick = { if (selected.isEmpty()) openItem() else select(ref, additive = true) },
        )
    }

    val gestureInput = Modifier.libraryItemInput(
        drag, !busy,
        onMouseClick = { ref, modifiers, double ->
            val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed
            val range = modifiers.isShiftPressed
            if (double && !additive && !range) {
                if (ref.folder) navigate(ref.id) else library.documents.firstOrNull { it.id == ref.id }?.let(open)
            } else select(ref, additive, range)
        },
        onHold = { select(it, additive = true) },
        onDragStart = { source, point ->
            val items = if (source.item in selected) selected else setOf(source.item)
            drag.start(items, source.name, source.item.folder, point, rules)
        },
        onDrag = { drag.update(it, rules) },
        onDrop = { drag.finish(it, rules, ::moveTo) },
        onCancel = drag::cancel,
    )
    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.root = it }.then(gestureInput)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder?.name ?: "阅读空间", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { editing = Pair(null, "") }, shape = ControlShape, enabled = !busy) {
                    Text("新建文件夹")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = addPdf, shape = ControlShape, enabled = !busy) { Text("＋ 打开 PDF") }
            }
            Spacer(Modifier.height(22.dp))
            FlowRow(Modifier.fillMaxWidth().background(Bar, ReaderCardShape).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically) {
                val up = LibraryDropSite(LibraryDropKind.Up, folder?.parentId)
                Box(if (folder == null) Modifier else Modifier.libraryDropSite(drag, up)) {
                    Control("‹", "返回上一级文件夹", enabled = folder != null && !busy,
                        active = dropTarget == up) { navigate(folder?.parentId) }
                }
                Control("▦", "图标视图", active = grid) { grid = true }
                Control("☰", "列表视图", active = !grid) { grid = false }
                Control("剪切", enabled = selected.isNotEmpty() && !busy) { cut = selected; clearSelection() }
                Control("粘贴", enabled = cut.isNotEmpty() && !busy) { moveTo(cut, folderId) }
                if (selected.isNotEmpty()) {
                    Control("移动到…", enabled = !busy) { moving = selected }
                    Control("取消选择", action = ::clearSelection)
                }
                val singleFolder = selected.singleOrNull()?.takeIf { it.folder }
                if (singleFolder != null) Control("文件夹选项") {
                    folderMenu = library.folders.firstOrNull { it.id == singleFolder.id }
                }
                if (selected.isNotEmpty() && selected.none { it.folder }) Control("删除") { confirmRemove = true }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(query, search, singleLine = true, placeholder = { Text("搜索资料与文件夹") }, modifier = Modifier.widthIn(max = 260.dp))
            }
            FlowRow(Modifier.padding(vertical = 6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val rootSite = LibraryDropSite(LibraryDropKind.Path, null)
                Box(Modifier.libraryDropSite(drag, rootSite)) {
                    Control("阅读空间", active = dropTarget == rootSite, enabled = !busy) { navigate(null) }
                }
                trail.forEach { item ->
                    Text("›", color = Muted)
                    val site = LibraryDropSite(LibraryDropKind.Path, item.id)
                    Box(Modifier.libraryDropSite(drag, site)) {
                        Control(item.name, active = dropTarget == site, enabled = !busy) { navigate(item.id) }
                    }
                }
            }
            HorizontalDivider(color = Line)
            val overContents = dropTarget == contentsSite
            val emptyArea = if (selected.isEmpty()) Modifier else Modifier.pointerInput(folderId, query) {
                detectTapGestures { clearSelection() }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).libraryDropSite(drag, contentsSite).then(emptyArea),
                shape = ReaderCardShape, color = if (overContents) PaleGreen else Desk,
                border = if (overContents) BorderStroke(2.dp, Green) else null,
            ) {
                if (folders.isEmpty() && files.isEmpty()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "这个文件夹还是空的" else "没有找到匹配的项目", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = importArchive, shape = ControlShape, enabled = !busy) { Text("导入 InkAsk 项目") }
                        if (library.documents.isEmpty()) Control("先打开示例资料", enabled = !busy, action = sample)
                    }
                } else {
                    LazyVerticalGrid(columns = if (grid) GridCells.Adaptive(154.dp) else GridCells.Fixed(1), state = gridState,
                        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { "folder-${it.id}" }) { item ->
                            val size = library.folders.count { it.parentId == item.id } + library.membership.count { it.value == item.id }
                            entry(LibraryItem(item.id, true), item.name, "$size 个项目") { navigate(item.id) }
                        }
                        items(files, key = { "document-${it.id}" }) { item ->
                            entry(LibraryItem(item.id, false), item.name, "${item.pageCount} 页 · PDF") { open(item) }
                        }
                    }
                }
            }
            HorizontalDivider(color = Line)
            Text(if (selected.isEmpty()) "${folders.size} 个文件夹，${files.size} 份资料" else "已选中 ${selected.size} 项",
                modifier = Modifier.padding(vertical = 10.dp), color = Muted, fontSize = 12.sp)
        }
        LibraryDragPreview(drag)
    }
    editing?.let { (id, name) ->
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(if (id == null) "新建文件夹" else "重命名") },
            text = { OutlinedTextField(name, { editing = Pair(id, it) }, singleLine = true, label = { Text("文件夹名称") }) },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { if (id == null) create(name) else rename(id, name); editing = null }) {
                    Text(if (id == null) "创建" else "保存")
                }
            })
    }
    folderMenu?.let { item ->
        AlertDialog(onDismissRequest = { folderMenu = null }, title = { Text(item.name) },
            text = { Column {
                Control("移动到…") { folderMenu = null; moving = setOf(LibraryItem(item.id, true)) }
                Text("移除文件夹会把内容移到上一级。")
            } },
            confirmButton = { TextButton(onClick = { folderMenu = null; editing = Pair(item.id, item.name) }) { Text("重命名") } },
            dismissButton = { TextButton(onClick = { folderMenu = null; removeFolder(item.id) }) { Text("移除文件夹") } })
    }
    if (moving.isNotEmpty()) {
        val selectedFolderIds = moving.filter { it.folder }.map { it.id }.toSet()
        val byId = library.folders.associateBy { it.id }
        AlertDialog(onDismissRequest = { moving = emptySet() }, title = { Text("移动到文件夹") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Control("阅读空间", enabled = !busy) { moveTo(moving, null) } }
                items(library.folders) { target ->
                    val path = generateSequence(target) { byId[it.parentId] }.take(library.folders.size).toList()
                    Control(path.asReversed().joinToString(" / ") { it.name },
                        enabled = !busy && path.none { it.id in selectedFolderIds }) { moveTo(moving, target.id) }
                }
            } }, confirmButton = { TextButton(onClick = { moving = emptySet() }) { Text("取消") } })
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("删除所选项目？") },
        text = { Text("所选 PDF 和本机便签将一起删除。") },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = { remove(selected.filterNot { it.folder }.map { it.id }.toSet()); selected = emptySet(); confirmRemove = false }) { Text("删除") } })
}

@Composable
private fun LibraryTile(
    name: String, details: String, folder: Boolean, grid: Boolean, selected: Boolean, cut: Boolean,
    dragging: Boolean, draggingTo: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    body: Modifier, selectionControl: Modifier, onClick: () -> Unit, onSelect: () -> Unit,
) {
    val fill = if (selected || draggingTo) PaleGreen else Paper
    Box(modifier.fillMaxWidth()) {
        Surface(
            border = BorderStroke(if (draggingTo) 2.dp else 1.dp, if (selected || draggingTo) Green else Line),
            color = fill, shape = ReaderCardShape,
            modifier = body.fillMaxWidth().alpha(if (dragging || cut) .55f else 1f)
                .semantics { this.selected = selected }.clickable(enabled = enabled, onClick = onClick),
        ) {
            val icon = if (folder) R.drawable.inkask_ic_folder_open else R.drawable.inkask_ic_file_text
            if (grid) {
                Column(Modifier.height(138.dp).fillMaxWidth().padding(12.dp)) {
                    Box(Modifier.height(44.dp), contentAlignment = Alignment.CenterStart) { InkIcon(icon, Green, 26) }
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = Ink, fontSize = 14.sp, lineHeight = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text(details, color = Muted, fontSize = 12.sp)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 54.dp).heightIn(min = 58.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    InkIcon(icon, Green, 22)
                    Text(name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(details, color = Muted, fontSize = 12.sp)
                }
            }
        }
        Checkbox(checked = selected, onCheckedChange = { onSelect() }, enabled = enabled,
            modifier = selectionControl.align(if (grid) Alignment.TopEnd else Alignment.CenterEnd)
                .semantics { contentDescription = "选择 $name" })
    }
}
