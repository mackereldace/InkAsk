package app.inkask.tablet

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
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
    var splitUpgrade by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxSize().then(if (drawer && opened == null) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (trouble.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFF2DB)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(trouble, Modifier.weight(1f), fontSize = 13.sp)
                    Control("×", "关闭通知") { trouble = "" }
                }
            }
            val document = opened
            if (document == null) {
                LibraryScreen(library, folder, query, { query = it }, { folder = it }, busy, addPdf, importArchive,
                    openSidebar = { drawer = !drawer }, openSettings = { showSettings = true },
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
                    var readerFolder by remember { mutableStateOf(library.membership[document.item.id]) }
                    var readerQuery by remember { mutableStateOf("") }
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
                        sidebar = { notes, openNote ->
                            LibrarySidebar(
                                drawer, library, readerFolder, document.item.id, readerQuery, { readerQuery = it },
                                busy, onDismiss = { drawer = false },
                                onFolder = { target -> readerFolder = target; readerQuery = "" },
                                onDocument = { item ->
                                    if (item.id == document.item.id) drawer = false else act {
                                        open(item)
                                        folder = library.membership[item.id]
                                        query = ""
                                    }
                                },
                                openPdf = addPdf, importArchive = importArchive,
                                openSettings = { showSettings = true; drawer = false },
                                reader = ReaderSidebarActions(notes,
                                    openNote = { id -> openNote(id); drawer = false },
                                    splitNote = { splitUpgrade = true },
                                    splitDocument = { splitUpgrade = true },
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (opened == null) LibrarySidebar(
            drawer, library, folder, null, query, { query = it }, busy, onDismiss = { drawer = false },
            onFolder = { target -> query = ""; showLibrary(target) },
            onDocument = { item -> act { open(item); folder = library.membership[item.id]; query = "" } },
            openPdf = addPdf, importArchive = importArchive,
            openSettings = { showSettings = true; drawer = false },
        )
    }
    if (splitUpgrade) {
        AlertDialog(
            onDismissRequest = { splitUpgrade = false },
            title = { Text("分屏是升级版功能") },
            text = { Text("升级版支持同时阅读两份 PDF，或并排查看 PDF 和追问。") },
            confirmButton = { TextButton(onClick = { splitUpgrade = false }) { Text("知道了") } },
            containerColor = Paper,
        )
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
private fun InkIcon(id: Int, color: Color = LocalContentColor.current, size: Int = 16) {
    Icon(painterResource(id), contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

@Composable
private fun LibraryScreen(
    library: Library, folderId: String?, query: String, search: (String) -> Unit, navigate: (String?) -> Unit,
    busy: Boolean, addPdf: () -> Unit, importArchive: () -> Unit, sample: () -> Unit,
    openSidebar: () -> Unit, openSettings: () -> Unit,
    open: (PdfItem) -> Unit, create: (String) -> Unit, rename: (String, String) -> Unit, removeFolder: (String) -> Unit,
    move: (Set<LibraryItem>, String?, () -> Unit) -> Unit, remove: (Set<String>) -> Unit,
) {
    var grid by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var selected by remember(folderId, query) { mutableStateOf(setOf<LibraryItem>()) }
    var cut by remember { mutableStateOf(setOf<LibraryItem>()) }
    var anchor by remember(folderId, query) { mutableStateOf<LibraryItem?>(null) }
    val canvasIcons = grid && query.isBlank()
    val context = LocalContext.current
    val iconPreferences = remember(context) {
        LibraryIconPreferences(context.getSharedPreferences("library-icons", Context.MODE_PRIVATE))
    }
    var snapIcons by remember(folderId, iconPreferences) { mutableStateOf(iconPreferences.snaps(folderId)) }
    var placementMenu by remember(folderId, canvasIcons) { mutableStateOf(false) }
    val drag = remember(folderId, query, grid, snapIcons) { LibraryDragInteraction(grid, canvasIcons) }
    val rules = remember(library) { LibraryDropRules(library) }
    val gridState = key(folderId, query, grid) { rememberLazyGridState() }
    val iconScroll = remember(folderId, canvasIcons) { LibraryIconScroll() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val iconWidth = with(density) { viewport.width.toDp().value }
    val columns = libraryIconColumns(iconWidth)
    var savedIcons by remember(folderId, columns) {
        mutableStateOf(iconPreferences.read(folderId, columns))
    }
    var shiftedIcons by remember(folderId, columns, snapIcons) { mutableStateOf(emptyMap<LibraryItem, Offset>()) }
    val draggedItems by remember(drag) { derivedStateOf { drag.active?.items.orEmpty() } }
    var marquee by remember(drag) { mutableStateOf<Rect?>(null) }
    var selectionPreview by remember(drag) { mutableStateOf<Set<LibraryItem>?>(null) }
    val highlightedSelection = selectionPreview ?: selected
    val dropTarget by remember(drag) { derivedStateOf { drag.session?.destination } }
    val dragMoving by remember(drag) { derivedStateOf { drag.session?.moving == true } }
    val scrollEdge = with(LocalDensity.current) { 44.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 1080.dp.toPx() }
    val folderEdgeInset = with(LocalDensity.current) { 10.dp.toPx() }
    val dropTolerance = with(LocalDensity.current) { 6.dp.toPx() }
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
    val iconLayout = remember(iconWidth, density.fontScale, order, savedIcons, canvasIcons, snapIcons) {
        if (canvasIcons && iconWidth > 0f) LibraryIconLayout(iconWidth, density.fontScale, order, savedIcons, snapIcons)
        else null
    }
    val foldersById = remember(folders) { folders.associateBy(Folder::id) }
    val filesById = remember(files) { files.associateBy(PdfItem::id) }
    val folderSizes = remember(library) {
        (library.folders.mapNotNull { it.parentId } + library.membership.values).groupingBy { it }.eachCount()
    }
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
    fun placement(session: LibraryDragInteraction.Session): LibraryDragInteraction.Placement? {
        val layout = iconLayout ?: return null
        val bounds = drag.bounds(contentsSite) ?: return null
        val movement = layout.movementBounds(session.items, session.grabbed) ?: return null
        val scale = density.density
        val canvasOrigin = bounds.topLeft - Offset(0f, iconScroll.offset)
        val inset = (session.iconSize / 2f + 10f) * scale
        val horizontalInset = inset.coerceAtMost(bounds.width / 2f)
        val verticalInset = inset.coerceAtMost(bounds.height / 2f)
        val limits = Rect(
            maxOf(bounds.left + horizontalInset, canvasOrigin.x + movement.left * scale),
            maxOf(bounds.top + verticalInset, canvasOrigin.y + movement.top * scale),
            minOf(bounds.right - horizontalInset, canvasOrigin.x + movement.right * scale),
            bounds.bottom - verticalInset,
        )
        val colliders = {
            val movingIcon = libraryIconCollider(Offset.Zero, session.grabbed.folder, scale)
            layout.spots.asSequence().filter { (item, _) -> item !in session.items }
                .map { (item, position) ->
                    val center = layout.center(position) * scale + canvasOrigin
                    libraryIconCollider(center, item.folder, scale).expandedFor(movingIcon)
                }.filter { it.rect.overlaps(limits) }.toList()
        }
        return LibraryDragInteraction.Placement(limits, colliders) { center ->
            val desired = (center - canvasOrigin) / scale
            val positions = layout.place(session.items, session.grabbed, desired) ?: return@Placement null
            if (snapIcons) {
                val previous = layout.savedPositions()
                shiftedIcons = layout.spots.filter { (item, _) -> item !in session.items && positions[item] != previous[item] }
            }
            savedIcons = positions
            iconPreferences.write(folderId, layout.columns, positions)
            cut = cut - session.items
            layout.center(positions.getValue(session.grabbed)) * scale + canvasOrigin
        }
    }

    fun setPlacementMode(snap: Boolean) {
        placementMenu = false
        if (snap == snapIcons) return
        val current = iconLayout
        if (current == null) {
            iconPreferences.setSnap(folderId, snap)
        } else {
            val positions = current.savedPositions()
            savedIcons = if (snap) LibraryIconLayout(iconWidth, density.fontScale, order, positions, snap = true)
                .savedPositions() else positions
            iconPreferences.write(folderId, columns, savedIcons, snap)
        }
        snapIcons = snap
    }

    DisposableEffect(drag) {
        onDispose { drag.glide?.let(drag::finishGlide) }
    }
    LaunchedEffect(drag, viewport) {
        drag.glide?.let(drag::finishGlide)
    }

    LaunchedEffect(drag, dragMoving, gridState, iconScroll, rules, scrollEdge, scrollSpeed, folderEdgeInset, dropTolerance) {
        if (!dragMoving) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        var edgeSince = previousFrame
        var direction = 0
        while (drag.session?.moving == true) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.04f)
            previousFrame = frame
            val session = drag.session ?: break
            val bounds = drag.bounds(contentsSite) ?: continue
            val strength = when (session.destination?.kind) {
                LibraryDropKind.Up, LibraryDropKind.Path -> 0f
                else -> libraryDragEdgeStrength(session.point, bounds, scrollEdge, folderEdgeInset,
                    session.destination?.kind == LibraryDropKind.Folder)
            }
            val nextDirection = if (strength == 0f) 0 else strength.compareTo(0f)
            if (nextDirection != direction) {
                direction = nextDirection
                edgeSince = frame
            }
            if (direction != 0 && frame - edgeSince >= 170_000_000L) {
                val delta = strength * scrollSpeed * elapsed
                if (canvasIcons) iconScroll.scrollBy(delta) else gridState.scrollBy(delta)
                drag.session?.let { drag.update(it.point, rules, dropTolerance) }
            }
        }
    }

    @Composable
    fun entry(ref: LibraryItem, name: String, detail: String, rowIndex: Int, openItem: () -> Unit) {
        val itemSite = LibraryDropSite(LibraryDropKind.Folder, ref.id)
        val source = ref in draggedItems
        DisposableEffect(drag, ref) {
            onDispose {
                drag.sources.remove(ref)
                drag.icons.remove(ref)
            }
        }
        SideEffect {
            val existing = drag.sources[ref]
            if (existing != null && existing.order != rowIndex) {
                drag.sources[ref] = existing.copy(order = rowIndex)
            }
        }
        LibraryTile(
            name, detail, ref.folder, grid, rowIndex % 2 != 0, ref in highlightedSelection, ref in cut, source,
            draggingTo = ref.folder && dropTarget == itemSite, enabled = !busy,
            body = Modifier.onGloballyPositioned { drag.sources[ref] = LibraryDragInteraction.Source(ref, it, rowIndex) },
            icon = Modifier.onGloballyPositioned { drag.icons[ref] = it },
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
        onLift = { source, point, mouse ->
            val items = if (source.item in selected) selected else setOf(source.item)
            drag.lift(items, source.item, point, mouse)
        },
        onDrag = { drag.update(it, rules, dropTolerance) },
        onDrop = { point, velocity ->
            val launch = if (canvasIcons) with(density) {
                readerIslandLaunch(
                    velocity, ReaderIslandEdge.Free, IslandFlingMinSpeed.toPx(),
                    IslandFlingMaxSpeed.toPx(), IslandFlingBounceSpeed.toPx(), IslandFlingEasterEggSpeed.toPx(),
                )
            } else null
            drag.finish(point, launch, rules, dropTolerance, ::placement, ::moveTo)
        },
        onCancel = drag::cancel,
        marqueeArea = { if (order.isEmpty()) null else drag.bounds(contentsSite) },
        onMarquee = { box, modifiers ->
            val hits = drag.sources.values.asSequence().filter {
                it.coordinates.isAttached && it.coordinates.boundsInWindow().overlaps(box)
            }.map { it.item }.toSet()
            marquee = box
            selectionPreview = when {
                modifiers.isCtrlPressed || modifiers.isMetaPressed -> (selected - hits) + (hits - selected)
                modifiers.isShiftPressed -> selected + hits
                else -> hits
            }
        },
        onMarqueeEnd = { commit ->
            if (commit) selectionPreview?.let {
                selected = it
                anchor = order.lastOrNull { item -> item in it }
            }
            selectionPreview = null
            marquee = null
        },
        onBlankTap = { clearSelection() },
    )
    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.root = it }.then(gestureInput)) {
        Column(Modifier.fillMaxSize().background(Paper)) {
            Row(
                Modifier.fillMaxWidth().background(Bar).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Control("显示资料侧栏", action = openSidebar)
                val up = LibraryDropSite(LibraryDropKind.Up, folder?.parentId)
                Box(if (folder == null) Modifier else Modifier.libraryDropSite(drag, up)) {
                    Control("‹", "返回上一级文件夹", enabled = folder != null && !busy,
                        active = dropTarget == up) { navigate(folder?.parentId) }
                }
                Text(
                    folder?.name ?: "阅读空间", Modifier.weight(1f).padding(horizontal = 8.dp),
                    color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.background(Desk, ControlShape)) {
                    Control("图标视图", active = grid) { grid = true }
                    Control("列表视图", active = !grid) { grid = false }
                }
                Control("帮助与设置", action = openSettings)
            }
            FlowRow(
                Modifier.fillMaxWidth().background(Bar).padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                LibrarySearch(query, search)
                Control("新建文件夹", enabled = !busy) { editing = Pair(null, "") }
                Control("打开 PDF", enabled = !busy, action = addPdf)
                if (canvasIcons) Box {
                    Control(if (snapIcons) "吸附网格 ▾" else "自由摆放 ▾", "图标摆放方式", enabled = !busy) {
                        placementMenu = true
                    }
                    DropdownMenu(
                        expanded = placementMenu, onDismissRequest = { placementMenu = false },
                        shape = ControlShape, containerColor = Paper, tonalElevation = 0.dp,
                    ) {
                        for (snap in listOf(false, true)) {
                            DropdownMenuItem(
                                text = { Text(if (snap) "吸附网格" else "自由摆放") },
                                trailingIcon = { if (snap == snapIcons) Text("✓", color = Green) },
                                modifier = Modifier.semantics { this.selected = snap == snapIcons },
                                onClick = { setPlacementMode(snap) },
                            )
                        }
                    }
                }
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
            }
            HorizontalDivider(color = Line)
            val overContents = dropTarget == contentsSite
            Box(
                Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport = it }
                    .libraryDropSite(drag, contentsSite)
                    .then(if (overContents) Modifier.border(2.dp, Green) else Modifier),
            ) {
                if (folders.isEmpty() && files.isEmpty()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "这个文件夹还是空的" else "没有找到匹配的项目", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = importArchive, shape = ControlShape, enabled = !busy) { Text("导入 InkAsk 项目") }
                        if (library.documents.isEmpty()) Control("先打开示例资料", enabled = !busy, action = sample)
                    }
                } else if (canvasIcons) {
                    iconLayout?.let { layout ->
                        LibraryIconCanvas(layout, iconScroll, viewport.height, shiftedIcons) { ref, index ->
                            if (ref.folder) {
                                val item = foldersById.getValue(ref.id)
                                entry(ref, item.name, "", index) { navigate(item.id) }
                            } else {
                                val item = filesById.getValue(ref.id)
                                entry(ref, item.name, "", index) { open(item) }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (!grid) {
                            Row(
                                Modifier.fillMaxWidth().background(Bar).padding(start = 52.dp, end = 38.dp)
                                    .heightIn(min = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("名称", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                                Text("详情", Modifier.width(LibraryDetailWidth), color = Muted, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = Line)
                        }
                        LazyVerticalGrid(
                            columns = if (grid) GridCells.Adaptive(124.dp) else GridCells.Fixed(1),
                            state = gridState, modifier = Modifier.fillMaxSize(),
                            contentPadding = if (grid) PaddingValues(horizontal = 16.dp, vertical = 16.dp) else PaddingValues(end = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 0.dp),
                        ) {
                            itemsIndexed(folders, key = { _, item -> "folder-${item.id}" }) { index, item ->
                                val size = folderSizes[item.id] ?: 0
                                entry(LibraryItem(item.id, true), item.name, "$size 个项目", index) { navigate(item.id) }
                            }
                            itemsIndexed(files, key = { _, item -> "document-${item.id}" }) { index, item ->
                                entry(LibraryItem(item.id, false), item.name, "${item.pageCount} 页 · PDF",
                                    folders.size + index) { open(item) }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Line)
            Row(
                Modifier.fillMaxWidth().background(Bar).padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rootSite = LibraryDropSite(LibraryDropKind.Path, null)
                    Box(Modifier.libraryDropSite(drag, rootSite)) {
                        Control("阅读空间", active = dropTarget == rootSite, enabled = !busy) { navigate(null) }
                    }
                    trail.forEach { item ->
                        InkIcon(R.drawable.inkask_ic_chevron_right, color = Muted, size = 12)
                        val site = LibraryDropSite(LibraryDropKind.Path, item.id)
                        Box(Modifier.widthIn(max = 220.dp).libraryDropSite(drag, site)) {
                            Control(item.name, active = dropTarget == site, enabled = !busy) { navigate(item.id) }
                        }
                    }
                }
                Text(
                    if (highlightedSelection.isEmpty()) "${order.size} 个项目" else "已选中 ${highlightedSelection.size} 项",
                    Modifier.padding(start = 8.dp), color = Muted, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val coordinates = drag.root?.takeIf { it.isAttached } ?: return@Canvas
            val box = marquee?.translate(-coordinates.localToWindow(Offset.Zero)) ?: return@Canvas
            drawRect(Green.copy(alpha = .12f), box.topLeft, box.size)
            drawRect(Green.copy(alpha = .75f), box.topLeft, box.size, style = Stroke(1.dp.toPx()))
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
private fun LibrarySearch(query: String, search: (String) -> Unit) {
    OutlinedTextField(
        query, search, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        placeholder = { Text("搜索资料与文件夹", fontSize = 13.sp, maxLines = 1) },
        leadingIcon = { InkIcon(R.drawable.inkask_ic_search, color = Muted) },
        shape = ControlShape, modifier = Modifier.width(204.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Paper, unfocusedContainerColor = Paper,
            unfocusedBorderColor = Line,
        ),
    )
}

private val LibraryDetailWidth = 112.dp

@Composable
private fun LibraryTile(
    name: String, details: String, folder: Boolean, grid: Boolean, striped: Boolean, selected: Boolean, cut: Boolean,
    dragging: Boolean, draggingTo: Boolean, enabled: Boolean,
    body: Modifier, icon: Modifier, onClick: () -> Unit, onSelect: () -> Unit,
) {
    val highlighted = selected || draggingTo
    val item = body.semantics {
        this.selected = selected
        if (enabled) {
            customActions = listOf(CustomAccessibilityAction(if (selected) "取消选择" else "选择") {
                onSelect()
                true
            })
        }
    }.clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, onClick = onClick,
    )
    val opacity = when {
        dragging -> 0f
        cut -> .48f
        else -> 1f
    }
    if (grid) {
        Box(Modifier.fillMaxWidth().heightIn(min = 114.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                item.widthIn(min = 76.dp, max = 144.dp).alpha(opacity).padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val selectionShape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(66.dp).background(if (highlighted) PaleGreen else Color.Transparent, selectionShape)
                        .then(if (draggingTo) Modifier.border(1.dp, Green, selectionShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    LibraryItemIcon(folder, libraryItemIconSize(folder, grid), icon)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    name, Modifier.background(if (selected) Green else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    color = if (selected) Paper else Ink, textAlign = TextAlign.Center,
                    fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val fill = when {
            highlighted && !dragging -> PaleGreen
            striped -> Bar
            else -> Paper
        }
        Row(
            item.fillMaxWidth().background(fill)
                .then(if (draggingTo) Modifier.border(1.dp, Green) else Modifier)
                .heightIn(min = 44.dp).padding(start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryItemIcon(folder, libraryItemIconSize(folder, grid), icon.alpha(opacity))
            Text(
                name, Modifier.weight(1f).padding(start = 10.dp, end = 8.dp).alpha(opacity),
                color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(details, Modifier.width(LibraryDetailWidth).alpha(opacity),
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
