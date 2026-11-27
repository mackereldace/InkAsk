package app.inkask.tablet

import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val ICON_MARGIN = 16f
private const val ICON_WIDTH = 124f
private const val ICON_CENTER_Y = 36f
private const val ROW_GAP = 10f

internal fun libraryIconColumns(width: Float): Int =
    ((width - 2 * ICON_MARGIN) / ICON_WIDTH).toInt().coerceAtLeast(1)

// x is a fraction of the usable width for this number of columns; y is measured in dp.
internal data class LibraryIconSpot(val x: Float, val y: Float)

internal class LibraryIconLayout(
    width: Float, fontScale: Float, ordered: List<LibraryItem>,
    saved: Map<LibraryItem, LibraryIconSpot>, private val snap: Boolean = false,
) {
    private data class Cell(val column: Int, val row: Int) {
        operator fun minus(other: Cell) = Cell(column - other.column, row - other.row)
        operator fun plus(other: Cell) = Cell(column + other.column, row + other.row)
    }

    val columns = libraryIconColumns(width)
    private val margin = ICON_MARGIN.coerceAtMost(width / 4f)
    private val innerWidth = width - 2 * margin
    val tileWidth = innerWidth.coerceIn(0f, ICON_WIDTH)
    val tileHeight = maxOf(114f, 76f + 38f * fontScale)
    private val travel = innerWidth - tileWidth
    private val columnWidth = innerWidth / columns
    private val rowHeight = tileHeight + ROW_GAP
    private val columnStart = margin + (columnWidth - tileWidth) / 2
    private val centerOffset = Offset(tileWidth / 2, ICON_CENTER_Y)

    private fun point(cell: Cell) = Offset(columnStart + cell.column * columnWidth, ICON_MARGIN + cell.row * rowHeight)

    private fun cell(point: Offset) = Cell(
        ((point.x - columnStart) / columnWidth).roundToInt().coerceIn(0, columns - 1),
        ((point.y - ICON_MARGIN) / rowHeight).roundToInt().coerceAtLeast(0),
    )

    private fun nearestAvailable(start: Cell, occupied: Set<Cell>): Cell {
        var radius = 0
        while (true) {
            for (column in maxOf(0, start.column - radius)..minOf(columns - 1, start.column + radius)) {
                val distance = radius - abs(column - start.column)
                val above = Cell(column, start.row - distance)
                if (above.row >= 0 && above !in occupied) return above
                val below = Cell(column, start.row + distance)
                if (distance > 0 && below !in occupied) return below
            }
            radius++
        }
    }

    private fun point(spot: LibraryIconSpot) = Offset(margin + spot.x * travel, spot.y)

    fun center(spot: LibraryIconSpot): Offset = center(point(spot))
    fun center(position: Offset): Offset = position + centerOffset

    private fun save(point: Offset): LibraryIconSpot {
        val x = if (travel > 0f) ((point.x - margin) / travel).coerceIn(0f, 1f) else 0f
        return LibraryIconSpot(x, point.y)
    }

    val spots: Map<LibraryItem, Offset> = buildMap {
        val visible = ordered.toSet()
        val occupied = hashSetOf<Cell>()
        fun reserve(point: Offset) {
            if (columnWidth <= 0f) return
            val column = floor((point.x - columnStart) / columnWidth).toInt()
            val row = floor((point.y - ICON_MARGIN) / rowHeight).toInt()
            for (r in row.coerceAtLeast(0)..(row + 1)) {
                for (c in column.coerceAtLeast(0)..(column + 1).coerceAtMost(columns - 1)) {
                    val cell = Cell(c, r)
                    val (x, y) = point(cell)
                    if (x < point.x + tileWidth && x + tileWidth > point.x &&
                        y < point.y + tileHeight && y + tileHeight > point.y
                    ) occupied.add(cell)
                }
            }
        }
        saved.forEach { (item, spot) ->
            if (item in visible) {
                val desired = point(spot)
                if (snap) {
                    val cell = nearestAvailable(cell(desired), occupied)
                    put(item, point(cell))
                    occupied.add(cell)
                } else {
                    put(item, desired)
                    reserve(desired)
                }
            }
        }
        var slot = 0
        for (item in ordered) {
            if (item in this) continue
            while (Cell(slot % columns, slot / columns) in occupied) slot++
            put(item, point(Cell(slot % columns, slot / columns)))
            slot++
        }
    }

    val height = (spots.values.maxOfOrNull { it.y } ?: 0f) + tileHeight + ICON_MARGIN

    fun savedPositions(): Map<LibraryItem, LibraryIconSpot> = spots.mapValues { (_, point) -> save(point) }

    fun movementBounds(items: Set<LibraryItem>, grabbed: LibraryItem): Rect? {
        val origin = spots[grabbed] ?: return null
        val moving = items.mapNotNull(spots::get)
        if (moving.isEmpty()) return null
        val center = origin + centerOffset
        return Rect(
            margin + center.x - moving.minOf { it.x },
            center.y - moving.minOf { it.y },
            margin + travel + center.x - moving.maxOf { it.x },
            Float.POSITIVE_INFINITY,
        )
    }

    fun place(items: Set<LibraryItem>, grabbed: LibraryItem, center: Offset): Map<LibraryItem, LibraryIconSpot>? {
        val origin = spots[grabbed] ?: return null
        val moving = spots.filterKeys { it in items }
        if (moving.isEmpty()) return null
        val stationary = spots.filterKeys { it !in items }
        val desired = center - centerOffset
        if (snap) return placeOnGrid(moving, stationary, cell(origin), cell(desired))
        val bounds = movementBounds(items, grabbed) ?: return null
        val translation = Offset(
            center.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right)),
            center.y.coerceAtLeast(bounds.top),
        ) - centerOffset - origin
        if (translation == Offset.Zero) return null
        // Placed items are drawn above older items where their labels or icons overlap.
        return buildMap {
            stationary.forEach { (item, point) -> put(item, save(point)) }
            moving.forEach { (item, point) -> put(item, save(point + translation)) }
        }
    }

    private fun placeOnGrid(
        moving: Map<LibraryItem, Offset>, stationary: Map<LibraryItem, Offset>, start: Cell, preferred: Cell,
    ): Map<LibraryItem, LibraryIconSpot>? {
        val original = moving.mapValues { (_, position) -> cell(position) }
        val shape = original.values.map { it - start }
        val destination = Cell(
            preferred.column.coerceIn(-shape.minOf { it.column }, columns - 1 - shape.maxOf { it.column }),
            preferred.row.coerceAtLeast(-shape.minOf { it.row }),
        )
        if (destination == start) return null
        val step = destination - start
        val targets = original.mapValues { (_, old) -> old + step }
        // The moving group vacates its old cells. Pushes do not loop back into them.
        val reserved = original.values.toSet() + targets.values
        val occupants = stationary.entries.associateTo(linkedMapOf()) { (item, position) -> cell(position) to item }

        // Process the downstream targets first to preserve the order of each occupied run.
        val downstreamFirst = compareByDescending<Cell> { it.row }.thenByDescending { it.column }
        for (target in targets.values.sortedWith(downstreamFirst)) {
            var pushed = occupants.remove(target) ?: continue
            for (next in pushPath(target)) {
                if (next in reserved) continue
                pushed = occupants.put(next, pushed) ?: break
            }
        }
        return buildMap {
            occupants.forEach { (cell, item) -> put(item, save(point(cell))) }
            targets.forEach { (item, cell) -> put(item, save(point(cell))) }
        }
    }

    private fun pushPath(start: Cell) = generateSequence(start) { cell ->
        if (cell.column + 1 < columns) Cell(cell.column + 1, cell.row) else Cell(cell.column, cell.row + 1)
    }.drop(1)
}

internal class LibraryIconPreferences(private val preferences: SharedPreferences) {
    private fun key(folder: String?, columns: Int) = "positions/$columns/${folder ?: ""}"
    private fun snapKey(folder: String?) = "snap/${folder ?: ""}"

    init {
        // Import the arrangement that was active in 0.4.19, for every saved screen width.
        val values = preferences.all
        val oldKeys = values.keys.map { it.removePrefix("grid/") }.filter {
            '/' in it && it.substringBefore('/').toIntOrNull() != null
        }.toSet()
        if (oldKeys.isNotEmpty()) {
            val edits = preferences.edit()
            for (old in oldKeys) {
                val grid = "grid/$old"
                val current = "positions/$old"
                val folder = old.substringAfter('/')
                val preferGrid = values[snapKey(folder)] == true
                val saved = (if (preferGrid) values[grid] ?: values[old] else values[old] ?: values[grid]) as? String
                if (current !in values && saved != null) edits.putString(current, saved)
                edits.remove(old).remove(grid)
            }
            edits.apply()
        }
    }

    fun snaps(folder: String?): Boolean = preferences.getBoolean(snapKey(folder), false)

    fun setSnap(folder: String?, enabled: Boolean) {
        preferences.edit().putBoolean(snapKey(folder), enabled).apply()
    }

    fun read(folder: String?, columns: Int): Map<LibraryItem, LibraryIconSpot> {
        val data = preferences.getString(key(folder, columns), null) ?: return emptyMap()
        return runCatching {
            buildMap {
                JSONArray(data).objects().forEach { entry ->
                    val x = entry.optDouble("x").toFloat()
                    val y = entry.optDouble("y").toFloat()
                    val id = entry.optString("id")
                    if (id.isNotBlank() && x in 0f..1f && y.isFinite() && y >= 0f) {
                        put(LibraryItem(id, entry.optBoolean("folder")), LibraryIconSpot(x, y))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun write(folder: String?, columns: Int, spots: Map<LibraryItem, LibraryIconSpot>, snap: Boolean? = null) {
        val data = JSONArray(spots.map { (item, point) ->
            JSONObject().put("id", item.id).put("folder", item.folder)
                .put("x", point.x.toDouble()).put("y", point.y.toDouble())
        })
        val edits = preferences.edit().putString(key(folder, columns), data.toString())
        if (snap != null) edits.putBoolean(snapKey(folder), snap)
        edits.apply()
    }
}

internal class LibraryIconScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    var limit by mutableFloatStateOf(0f)
        private set
    val gestures = ScrollableState { delta ->
        val before = offset
        offset = (before - delta).coerceIn(0f, limit)
        before - offset
    }

    fun resize(maximum: Float) {
        limit = maximum.coerceAtLeast(0f)
        offset = offset.coerceAtMost(limit)
    }

    suspend fun scrollBy(pixels: Float): Float = -gestures.scrollBy(-pixels)
}

@Composable
internal fun LibraryIconCanvas(
    layout: LibraryIconLayout, scroll: LibraryIconScroll, viewportHeight: Int, shiftedFrom: Map<LibraryItem, Offset>,
    entry: @Composable (LibraryItem, Int) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val height = with(density) { viewportHeight.toDp().value }
    val shifting = remember(shiftedFrom) { Animatable(if (shiftedFrom.isEmpty()) 1f else 0f) }
    LaunchedEffect(shifting) {
        if (shiftedFrom.isNotEmpty()) shifting.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
    }
    SideEffect { scroll.resize(with(density) { layout.height.dp.toPx() } - viewportHeight) }
    val entries = remember(layout) { layout.spots.entries.withIndex().toList() }
    val visible by remember(entries, layout.tileHeight, scroll, density, height) {
        derivedStateOf {
            val top = with(density) { scroll.offset.toDp().value }
            entries.filter { (_, item) ->
                item.value.y in (top - 2 * layout.tileHeight)..(top + height + layout.tileHeight)
            }
        }
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().scrollable(scroll.gestures, Orientation.Vertical)
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange({ scroll.offset }, { scroll.limit })
                scrollBy { _, y ->
                    scope.launch { scroll.scrollBy(y) }
                    true
                }
            },
    ) {
        for ((index, item) in visible) key(item.key) {
            Box(Modifier.absoluteOffset {
                val end = item.value
                val point = shiftedFrom[item.key]?.let { it + (end - it) * shifting.value } ?: end
                IntOffset(point.x.dp.roundToPx(), (point.y.dp.toPx() - scroll.offset).roundToInt())
            }.width(layout.tileWidth.dp)) {
                entry(item.key, index)
            }
        }
    }
}
