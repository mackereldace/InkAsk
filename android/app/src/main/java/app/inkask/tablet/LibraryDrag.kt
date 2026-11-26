package app.inkask.tablet

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal enum class LibraryDropKind { Folder, Path, Up, Contents }
internal data class LibraryDropSite(val kind: LibraryDropKind, val folderId: String?)

internal class LibraryDropRules(library: Library) {
    private val folders = library.folders.associateBy(Folder::id)
    private val documents = library.documents.map(PdfItem::id).toSet()
    private val membership = library.membership

    private fun ancestors(id: String?): Set<String> {
        val seen = mutableSetOf<String>()
        var folder = folders[id]
        while (folder != null && seen.add(folder.id)) folder = folders[folder.parentId]
        return seen
    }

    private fun parent(item: LibraryItem): String? = if (item.folder) folders[item.id]?.parentId else membership[item.id]

    fun canDrop(items: Set<LibraryItem>, folderId: String?): Boolean {
        if (folderId != null && folderId !in folders) return false
        val selectedFolders = items.filter { it.folder }.map { it.id }.toSet()
        if (ancestors(folderId).any { it in selectedFolders }) return false
        val roots = items.filter { item ->
            (if (item.folder) item.id in folders else item.id in documents) &&
                ancestors(parent(item)).none { it in selectedFolders }
        }
        if (roots.none { parent(it) != folderId }) return false
        val movingFolders = roots.filter { it.folder }.map { it.id }.toSet()
        val names = folders.values.filter { it.parentId == folderId && it.id !in movingFolders }
            .map { it.name.lowercase() }.toMutableSet()
        return movingFolders.all { names.add(folders.getValue(it).name.lowercase()) }
    }
}

internal fun librarySelection(
    selected: Set<LibraryItem>, ordered: List<LibraryItem>, clicked: LibraryItem, anchor: LibraryItem?,
    additive: Boolean, range: Boolean,
): Set<LibraryItem> {
    if (range && anchor != null) {
        val start = ordered.indexOf(anchor)
        val end = ordered.indexOf(clicked)
        if (start >= 0 && end >= 0) {
            val span = ordered.subList(minOf(start, end), maxOf(start, end) + 1)
            return if (additive) selected + span else span.toSet()
        }
    }
    if (!additive) return setOf(clicked)
    return if (clicked in selected) selected - clicked else selected + clicked
}

internal class LibraryDragInteraction {
    data class Source(val item: LibraryItem, val coordinates: LayoutCoordinates)

    data class Session(
        val items: Set<LibraryItem>, val grabbed: LibraryItem, val point: Offset,
        val destination: LibraryDropSite? = null,
    )

    var session by mutableStateOf<Session?>(null)
        private set
    var root: LayoutCoordinates? = null
    val coordinates = mutableMapOf<LibraryDropSite, LayoutCoordinates>()
    val sources = mutableMapOf<LibraryItem, Source>()
    val selectionControls = mutableMapOf<LibraryItem, LayoutCoordinates>()

    fun sourceAt(point: Offset): Source? = sources.values.firstOrNull { source ->
        source.coordinates.isAttached && source.coordinates.boundsInWindow().contains(point) &&
            selectionControls[source.item]?.takeIf { it.isAttached }?.boundsInWindow()?.contains(point) != true
    }

    fun bounds(site: LibraryDropSite): Rect? = coordinates[site]?.takeIf { it.isAttached }?.boundsInWindow()

    fun start(items: Set<LibraryItem>, grabbed: LibraryItem, point: Offset, rules: LibraryDropRules) {
        session = Session(items, grabbed, point)
        update(point, rules)
    }

    fun update(point: Offset, rules: LibraryDropRules) {
        val current = session ?: return
        val hit = coordinates.keys.asSequence().filter { bounds(it)?.contains(point) == true }.minByOrNull { it.kind.ordinal }
        session = current.copy(point = point, destination = hit?.takeIf { rules.canDrop(current.items, it.folderId) })
    }

    fun finish(point: Offset, rules: LibraryDropRules, move: (Set<LibraryItem>, String?) -> Unit) {
        update(point, rules)
        val current = session
        session = null
        current?.destination?.let { move(current.items, it.folderId) }
    }

    fun cancel() { session = null }
}

@Composable
internal fun Modifier.libraryDropSite(drag: LibraryDragInteraction, site: LibraryDropSite): Modifier {
    DisposableEffect(drag, site) { onDispose { drag.coordinates.remove(site) } }
    return then(Modifier.onGloballyPositioned { drag.coordinates[site] = it })
}

@Composable
internal fun Modifier.libraryItemInput(
    drag: LibraryDragInteraction,
    enabled: Boolean,
    onMouseClick: (LibraryItem, PointerKeyboardModifiers, Boolean) -> Unit,
    onHold: (LibraryItem) -> Unit,
    onDragStart: (LibraryDragInteraction.Source, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: (Offset) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val mouseClick by rememberUpdatedState(onMouseClick)
    val hold by rememberUpdatedState(onHold)
    val start by rememberUpdatedState(onDragStart)
    val update by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    return if (!enabled) this else pointerInput(drag) {
        var lastMouseClick = Long.MIN_VALUE
        var lastMouseItem: LibraryItem? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val root = drag.root?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val source = drag.sourceAt(root.localToWindow(down.position)) ?: return@awaitEachGesture
            val mouse = down.type == PointerType.Mouse
            if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val modifiers = currentEvent.keyboardModifiers
            if (mouse) down.consume()

            if (!mouse) {
                val stopped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!change.pressed || event.changes.count { it.pressed } != 1 ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (stopped == true) return@awaitEachGesture
            }

            var moving = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (event.changes.any { it.id != down.id && it.pressed } || change.isConsumed) return@awaitEachGesture
                    if (!root.isAttached) return@awaitEachGesture
                    val point = root.localToWindow(change.position)
                    if (!change.pressed) {
                        change.consume()
                        if (moving) {
                            drop(point)
                        } else if (mouse) {
                            val interval = change.uptimeMillis - lastMouseClick
                            val double = lastMouseItem == source.item && lastMouseClick != Long.MIN_VALUE && interval in
                                viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis
                            lastMouseClick = if (double) Long.MIN_VALUE else change.uptimeMillis
                            lastMouseItem = source.item
                            mouseClick(source.item, modifiers, double)
                        } else {
                            hold(source.item)
                        }
                        return@awaitEachGesture
                    }
                    if (!moving && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moving = true
                        lastMouseClick = Long.MIN_VALUE
                        start(source, point)
                    }
                    if (moving) {
                        change.consume()
                        update(point)
                    }
                }
            } finally {
                if (moving) cancel()
            }
        }
    }
}

@Composable
internal fun LibraryItemIcon(folder: Boolean, size: Int, modifier: Modifier = Modifier) {
    val icon = if (folder) R.drawable.inkask_ic_library_folder else R.drawable.inkask_ic_library_document
    Image(painterResource(icon), contentDescription = null, modifier = modifier.size(size.dp))
}

@Composable
internal fun LibraryDragPreview(drag: LibraryDragInteraction) {
    val session = drag.session ?: return
    val stack = remember(session.items, session.grabbed) {
        listOf(session.grabbed) + session.items.asSequence().filter { it != session.grabbed }.take(2).toList()
    }
    val iconSize = 48
    val previewSize = if (stack.size == 1) iconSize.dp else 64.dp
    Box(Modifier.absoluteOffset {
        val root = drag.root?.takeIf { it.isAttached } ?: return@absoluteOffset IntOffset.Zero
        val point = root.windowToLocal(session.point)
        val x = (point.x - iconSize.dp.toPx() / 2).coerceIn(0f, (root.size.width - previewSize.toPx()).coerceAtLeast(0f))
        val y = (point.y - 43.dp.toPx()).coerceIn(0f, (root.size.height - previewSize.toPx()).coerceAtLeast(0f))
        IntOffset(x.roundToInt(), y.roundToInt())
    }.size(previewSize).clearAndSetSemantics {}) {
        for (index in stack.indices.reversed()) {
            LibraryItemIcon(stack[index].folder, iconSize,
                Modifier.absoluteOffset(x = (7 * index).dp, y = (3 * index).dp).alpha(if (index == 0) 1f else .8f))
        }
        if (stack.size > 1) {
            Surface(modifier = Modifier.align(Alignment.BottomEnd), color = Green, shape = CircleShape) {
                Box(Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center) {
                    Text(session.items.size.toString(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Paper)
                }
            }
        }
    }
}
