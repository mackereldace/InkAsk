package app.inkask.tablet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
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

internal fun libraryDragEdgeStrength(
    point: Offset, bounds: Rect, edge: Float, folderInset: Float, overFolder: Boolean,
): Float {
    if (point.x !in bounds.left..bounds.right || point.y !in (bounds.top - edge)..(bounds.bottom + edge)) return 0f
    val top = point.y - bounds.top
    val bottom = bounds.bottom - point.y
    val distance = minOf(top, bottom)
    if (overFolder && distance >= folderInset) return 0f
    val strength = (1f - distance / edge).coerceIn(0f, 1f)
    return strength * strength * if (top < bottom) -1f else 1f
}

internal fun libraryMarqueeBounds(origin: Offset, point: Offset, viewport: Rect): Rect {
    val x = point.x.coerceIn(viewport.left, viewport.right)
    val y = point.y.coerceIn(viewport.top, viewport.bottom)
    return Rect(minOf(origin.x, x), minOf(origin.y, y), maxOf(origin.x, x), maxOf(origin.y, y)).intersect(viewport)
}

internal class LibraryDragInteraction(private val grid: Boolean, private val canvasPlacement: Boolean) {
    data class Source(val item: LibraryItem, val coordinates: LayoutCoordinates, val order: Int)

    data class Session(
        val id: Int, val items: Set<LibraryItem>, val grabbed: LibraryItem, val point: Offset,
        val origin: Offset, val grabOffset: Offset, val iconSize: Int, val moving: Boolean = false,
        val destination: LibraryDropSite? = null, val placing: Boolean = false,
    ) {
        val center get() = point + grabOffset
    }

    data class Placement(
        val limits: Rect, val colliders: () -> List<LibraryIconCollider>, val place: (Offset) -> Offset?,
    )
    data class Settling(val session: Session, val destination: Offset?)

    class Glide(val session: Session, val launch: ReaderIslandLaunch, val placement: Placement) {
        val colliders = placement.colliders()
        var center by mutableStateOf(Offset(
            session.center.x.coerceIn(placement.limits.left, placement.limits.right),
            session.center.y.coerceIn(placement.limits.top, placement.limits.bottom),
        ))
    }

    var session by mutableStateOf<Session?>(null)
        private set
    var settling by mutableStateOf<Settling?>(null)
        private set
    var glide by mutableStateOf<Glide?>(null)
        private set
    val active get() = session ?: glide?.session ?: settling?.session
    var root: LayoutCoordinates? = null
    val coordinates = mutableMapOf<LibraryDropSite, LayoutCoordinates>()
    val sources = mutableMapOf<LibraryItem, Source>()
    val icons = mutableMapOf<LibraryItem, LayoutCoordinates>()
    private val destinations = mutableMapOf<LibraryDropSite, Boolean>()
    private var checkedRules: LibraryDropRules? = null
    private var id = 0

    fun sourceAt(point: Offset, excluding: Set<LibraryItem> = emptySet()): Source? =
        sources.values.asSequence().filter { source ->
            source.item !in excluding && source.coordinates.isAttached &&
                source.coordinates.boundsInWindow().contains(point)
        }.maxByOrNull { it.order }

    fun bounds(site: LibraryDropSite): Rect? {
        val coordinates = if (site.kind == LibraryDropKind.Folder) {
            sources[LibraryItem(requireNotNull(site.folderId), true)]?.coordinates
        } else coordinates[site]
        return coordinates?.takeIf { it.isAttached }?.boundsInWindow()
    }

    private fun iconCenter(item: LibraryItem): Offset? =
        icons[item]?.takeIf { it.isAttached }?.let {
            it.localToWindow(Offset(it.size.width / 2f, it.size.height / 2f))
        }

    fun lift(items: Set<LibraryItem>, grabbed: LibraryItem, point: Offset, mouse: Boolean) {
        settling = null
        destinations.clear()
        val origin = iconCenter(grabbed) ?: point
        val size = libraryItemIconSize(grabbed.folder, grid)
        val offset = if (grid) origin - point else Offset(0f, if (mouse) 0f else (origin.y - point.y).coerceAtMost(0f))
        session = Session(++id, items, grabbed, point, origin, offset, size)
    }

    fun update(point: Offset, rules: LibraryDropRules, tolerance: Float) {
        val current = session ?: return
        if (rules !== checkedRules) {
            checkedRules = rules
            destinations.clear()
        }
        val region = coordinates.keys.asSequence().filter { bounds(it)?.contains(point) == true }.minByOrNull { it.kind.ordinal }
        val hit = if (region?.kind == LibraryDropKind.Contents) {
            sourceAt(point, current.items)?.item?.takeIf { it.folder }?.let { LibraryDropSite(LibraryDropKind.Folder, it.id) }
                ?: region
        } else region
        val previous = current.destination?.takeIf { it.kind != LibraryDropKind.Contents }
        val sticky = if (hit == null || hit.kind == LibraryDropKind.Contents) {
            previous?.takeIf { site ->
                val rect = bounds(site) ?: return@takeIf false
                point.x in (rect.left - tolerance)..(rect.right + tolerance) &&
                    point.y in (rect.top - tolerance)..(rect.bottom + tolerance)
            }
        } else null
        val over = sticky ?: hit
        val destination = over?.takeIf { destinations.getOrPut(it) { rules.canDrop(current.items, it.folderId) } }
        session = current.copy(
            point = point, moving = true, destination = destination,
            placing = canvasPlacement && over?.kind == LibraryDropKind.Contents && destination == null,
        )
    }

    fun finish(
        point: Offset, launch: ReaderIslandLaunch?, rules: LibraryDropRules, tolerance: Float,
        placement: (Session) -> Placement?, move: (Set<LibraryItem>, String?) -> Unit,
    ) {
        update(point, rules, tolerance)
        val current = session ?: return
        if (current.placing) {
            val target = placement(current)
            if (target != null) {
                if (launch != null && target.limits.width >= 0f && target.limits.height >= 0f) {
                    glide = Glide(current, launch, target)
                    session = null
                } else {
                    land(current, target)
                }
                return
            }
        }
        val site = current.destination?.takeIf { rules.canDrop(current.items, it.folderId) }
        val destination = when (site?.kind) {
            LibraryDropKind.Folder -> site.folderId?.let { iconCenter(LibraryItem(it, true)) } ?: bounds(site)?.center
            LibraryDropKind.Path, LibraryDropKind.Up -> bounds(site)?.center
            LibraryDropKind.Contents -> null
            null -> iconCenter(current.grabbed)
        }
        settle(current, destination)
        site?.let { move(current.items, it.folderId) }
    }

    private fun settle(current: Session, destination: Offset?) {
        settling = Settling(current, destination)
        session = null
    }

    private fun land(current: Session, placement: Placement) {
        val destination = placement.place(current.center) ?: iconCenter(current.grabbed)
        if (destination != null && (destination - current.center).getDistance() <= 1f) session = null
        else settle(current, destination)
    }

    fun finishGlide(flight: Glide) {
        if (glide !== flight) return
        val rest = flight.session.copy(point = flight.center - flight.session.grabOffset)
        land(rest, flight.placement)
        glide = null
    }

    fun cancel() {
        session?.let { settle(it, iconCenter(it.grabbed)) }
    }

    fun finishSettling(animation: Settling) {
        if (settling == animation) settling = null
    }
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
    onLift: (LibraryDragInteraction.Source, Offset, Boolean) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: (Offset, Offset) -> Unit,
    onCancel: () -> Unit,
    marqueeArea: () -> Rect?,
    onMarquee: (Rect, PointerKeyboardModifiers) -> Unit,
    onMarqueeEnd: (Boolean) -> Unit,
    onBlankTap: () -> Unit,
): Modifier {
    val mouseClick by rememberUpdatedState(onMouseClick)
    val hold by rememberUpdatedState(onHold)
    val lift by rememberUpdatedState(onLift)
    val haptics = LocalHapticFeedback.current
    val update by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    val selectionArea by rememberUpdatedState(marqueeArea)
    val previewSelection by rememberUpdatedState(onMarquee)
    val finishSelection by rememberUpdatedState(onMarqueeEnd)
    val clearSelection by rememberUpdatedState(onBlankTap)
    return if (!enabled) this else pointerInput(drag) {
        var lastMouseClick = Long.MIN_VALUE
        var lastMouseItem: LibraryItem? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val root = drag.root?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val origin = root.localToWindow(down.position)
            val mouse = down.type == PointerType.Mouse
            if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val flight = drag.glide
            if (flight != null) {
                val grabbed = (origin - flight.center).getDistance() <= (flight.session.iconSize.dp * .6f).toPx()
                drag.finishGlide(flight)
                if (grabbed) {
                    down.consume()
                    return@awaitEachGesture
                }
            }
            val source = drag.sourceAt(origin, flight?.session?.items.orEmpty())
            val marquee = if (source == null) selectionArea()?.takeIf { it.contains(origin) } else null
            if (source == null && marquee == null) return@awaitEachGesture
            val modifiers = currentEvent.keyboardModifiers
            if (mouse && source != null) down.consume()

            if (!mouse) {
                val systemTimeout = viewConfiguration.longPressTimeoutMillis
                val pickUpTime = if (systemTimeout <= 500L) minOf(275L, systemTimeout) else systemTimeout
                var wasTap = false
                val stopped = withTimeoutOrNull<Unit>(if (marquee != null) systemTimeout else pickUpTime) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                        if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed } ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) return@withTimeoutOrNull
                        if (!change.pressed) {
                            wasTap = true
                            return@withTimeoutOrNull
                        }
                    }
                }
                if (stopped != null) {
                    if (wasTap && marquee != null) clearSelection()
                    return@awaitEachGesture
                }
                if (source != null) lift(source, origin, false)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            var moving = false
            val velocity = DragReleaseVelocity(down.uptimeMillis, origin)
            val dragSlop = if (mouse) 2.dp.toPx() else 4.dp.toPx()
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (event.changes.any { it.id != down.id && it.pressed } ||
                        (change.isConsumed && (marquee == null || change.pressed))
                    ) return@awaitEachGesture
                    if (!root.isAttached) return@awaitEachGesture
                    val point = root.localToWindow(change.position)
                    change.historical.forEach { velocity.sample(it.uptimeMillis, root.localToWindow(it.position)) }
                    if (!change.pressed) {
                        change.consume()
                        if (moving) {
                            if (marquee != null) previewSelection(libraryMarqueeBounds(origin, point, marquee), modifiers)
                            else drop(point, velocity.release(change.uptimeMillis, point))
                        } else if (source == null) {
                            clearSelection()
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
                        completed = true
                        return@awaitEachGesture
                    }
                    velocity.sample(change.uptimeMillis, point)
                    if (!moving && (change.position - down.position).getDistance() > dragSlop) {
                        moving = true
                        lastMouseClick = Long.MIN_VALUE
                        if (mouse && source != null) lift(source, origin, true)
                    }
                    if (moving) {
                        change.consume()
                        if (marquee != null) previewSelection(libraryMarqueeBounds(origin, point, marquee), modifiers)
                        else update(point)
                    }
                }
            } finally {
                if (marquee != null) finishSelection(completed) else cancel()
            }
        }
    }
}

@Composable
internal fun LibraryItemIcon(folder: Boolean, size: Int, modifier: Modifier = Modifier) {
    val icon = if (folder) R.drawable.inkask_ic_library_folder else R.drawable.inkask_ic_library_document
    Image(painterResource(icon), contentDescription = null, modifier = modifier.size(size.dp))
}

internal fun libraryItemIconSize(folder: Boolean, grid: Boolean): Int = when {
    !grid -> 24
    folder -> 60
    else -> 52
}

@Composable
internal fun LibraryDragPreview(drag: LibraryDragInteraction) {
    val settling = drag.settling
    val flight = drag.glide
    val session = drag.active ?: return
    val stopSpeed = with(LocalDensity.current) { IslandFlingStopSpeed.toPx() }
    LaunchedEffect(flight) {
        flight ?: return@LaunchedEffect
        var motion = ReaderIslandMotion(flight.center, flight.launch.velocity)
        val start = withFrameNanos { it }
        var previous = start
        while (drag.glide === flight && motion.velocity.getDistance() >= stopSpeed) {
            val frame = withFrameNanos { it }
            val elapsed = (frame - start) / 1_000_000_000f
            if (elapsed > flight.launch.mode.maxSeconds) break
            val duration = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .036f)
            previous = frame
            motion = motion.advanceInLibrary(duration, flight.placement.limits, flight.colliders, flight.launch.mode, elapsed)
            flight.center = motion.center
        }
        drag.finishGlide(flight)
    }
    val lift = remember(session.id) { Animatable(0f) }
    val release = remember(settling) { Animatable(0f) }
    LaunchedEffect(session.id, settling) {
        if (settling == null) lift.animateTo(1f, tween(110, easing = LinearOutSlowInEasing))
        else {
            release.animateTo(1f, tween(135, easing = LinearOutSlowInEasing))
            drag.finishSettling(settling)
        }
    }
    val iconSize = session.iconSize.dp
    val previewSize = if (session.items.size == 1) iconSize else iconSize + 12.dp
    Box(Modifier.absoluteOffset {
        val root = drag.root?.takeIf { it.isAttached } ?: return@absoluteOffset IntOffset.Zero
        val lifted = if (session.moving) session.center else session.origin + (session.center - session.origin) * lift.value
        val end = settling?.destination ?: lifted
        val position = root.windowToLocal(flight?.center ?: lifted + (end - lifted) * release.value)
        val radius = iconSize.toPx() / 2
        IntOffset((position.x - radius).roundToInt(), (position.y - radius).roundToInt())
    }.size(previewSize).graphicsLayer {
        alpha = if (settling?.destination == null) 1f - release.value
            else 1f
    }.clearAndSetSemantics {}) {
        LibraryItemIcon(session.grabbed.folder, session.iconSize)
        if (session.items.size > 1) {
            Surface(modifier = Modifier.align(Alignment.BottomEnd), color = Green, shape = CircleShape) {
                Box(Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center) {
                    Text(session.items.size.toString(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Paper)
                }
            }
        }
    }
}
