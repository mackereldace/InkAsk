package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.exp
import kotlin.math.sqrt

internal data class LibraryIconCollider(val rect: Rect, val radius: Float) {
    fun expandedFor(moving: LibraryIconCollider) = LibraryIconCollider(
        Rect(
            rect.left - moving.rect.width / 2, rect.top - moving.rect.height / 2,
            rect.right + moving.rect.width / 2, rect.bottom + moving.rect.height / 2,
        ),
        radius + moving.radius,
    )
}

internal fun libraryIconCollider(center: Offset, folder: Boolean, scale: Float): LibraryIconCollider {
    val size = libraryItemIconSize(folder, grid = true) * scale
    val halfWidth = size * if (folder) .45f else .33f
    val halfHeight = size * if (folder) .39f else .46f
    return LibraryIconCollider(
        Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight),
        (if (folder) 7f else 5f) * scale,
    )
}

private data class Impact(val fraction: Float, val normal: Offset)

private fun Offset.dot(other: Offset): Float = x * other.x + y * other.y

private fun LibraryIconCollider.sweep(start: Offset, delta: Offset): Impact? {
    val end = start + delta
    if (maxOf(start.x, end.x) < rect.left || minOf(start.x, end.x) > rect.right ||
        maxOf(start.y, end.y) < rect.top || minOf(start.y, end.y) > rect.bottom
    ) return null

    val left = rect.left + radius
    val right = rect.right - radius
    val top = rect.top + radius
    val bottom = rect.bottom - radius
    val nearest = Offset(start.x.coerceIn(left, right), start.y.coerceIn(top, bottom))
    // A manually placed icon can start overlapped. Let it leave that overlap.
    if ((start - nearest).getDistanceSquared() < radius * radius - .02f) return null

    var hit: Impact? = null
    fun consider(fraction: Float, normal: Offset) {
        if (fraction in 0f..1f && fraction < (hit?.fraction ?: Float.POSITIVE_INFINITY) && delta.dot(normal) < 0f) {
            hit = Impact(fraction, normal)
        }
    }
    if (delta.x != 0f) {
        val fromLeft = delta.x > 0f
        val fraction = ((if (fromLeft) rect.left else rect.right) - start.x) / delta.x
        if (start.y + delta.y * fraction in top..bottom) {
            consider(fraction, Offset(if (fromLeft) -1f else 1f, 0f))
        }
    }
    if (delta.y != 0f) {
        val fromTop = delta.y > 0f
        val fraction = ((if (fromTop) rect.top else rect.bottom) - start.y) / delta.y
        if (start.x + delta.x * fraction in left..right) {
            consider(fraction, Offset(0f, if (fromTop) -1f else 1f))
        }
    }

    val a = delta.getDistanceSquared()
    fun corner(x: Float, y: Float, horizontal: Float, vertical: Float) {
        val offset = start - Offset(x, y)
        val b = offset.dot(delta)
        if (b >= 0f) return
        val discriminant = b * b - a * (offset.getDistanceSquared() - radius * radius)
        if (discriminant < 0f) return
        val fraction = (-b - sqrt(discriminant)) / a
        if (fraction !in 0f..1f || fraction >= (hit?.fraction ?: Float.POSITIVE_INFINITY)) return
        val contact = offset + delta * fraction
        if (contact.x * horizontal >= 0f && contact.y * vertical >= 0f) {
            consider(fraction, contact / radius)
        }
    }
    corner(left, top, -1f, -1f)
    corner(right, top, 1f, -1f)
    corner(left, bottom, -1f, 1f)
    corner(right, bottom, 1f, 1f)
    return hit
}

private fun wallImpact(start: Offset, delta: Offset, limits: Rect): Impact? {
    val x = when {
        delta.x > 0f -> (limits.right - start.x) / delta.x
        delta.x < 0f -> (limits.left - start.x) / delta.x
        else -> Float.POSITIVE_INFINITY
    }
    val y = when {
        delta.y > 0f -> (limits.bottom - start.y) / delta.y
        delta.y < 0f -> (limits.top - start.y) / delta.y
        else -> Float.POSITIVE_INFINITY
    }
    return when {
        x in 0f..1f && x <= y -> Impact(x, Offset(if (delta.x > 0f) -1f else 1f, 0f))
        y in 0f..1f -> Impact(y, Offset(0f, if (delta.y > 0f) -1f else 1f))
        else -> null
    }
}

internal fun ReaderIslandMotion.advanceInLibrary(
    seconds: Float, limits: Rect, obstacles: List<LibraryIconCollider>,
    mode: ReaderIslandGlideMode, elapsedSeconds: Float,
): ReaderIslandMotion {
    if (seconds <= 0f) return this
    if (obstacles.isEmpty()) return advance(seconds, limits, mode, elapsedSeconds)
    fun clamp(point: Offset) = Offset(point.x.coerceIn(limits.left, limits.right), point.y.coerceIn(limits.top, limits.bottom))
    val drag = mode.dragAt(elapsedSeconds)
    val speedRatio = exp(-drag * seconds)
    var travel = (1f - speedRatio) / drag
    var position = clamp(center)
    var speed = Offset(if (limits.width == 0f) 0f else velocity.x, if (limits.height == 0f) 0f else velocity.y)

    // Sweep the full segment to catch thin corners and icons crossed between rendered frames.
    repeat(8) {
        val delta = speed * travel
        if (delta.getDistanceSquared() < .0001f) return ReaderIslandMotion(position, speed * speedRatio)
        var impact = wallImpact(position, delta, limits)
        for (obstacle in obstacles) {
            val contact = obstacle.sweep(position, delta) ?: continue
            if (contact.fraction < (impact?.fraction ?: Float.POSITIVE_INFINITY)) impact = contact
        }
        val hit = impact ?: return ReaderIslandMotion(clamp(position + delta), speed * speedRatio)
        position = clamp(position + delta * hit.fraction + hit.normal * .04f)
        speed -= hit.normal * ((1f + mode.restitution) * speed.dot(hit.normal))
        speed = Offset(if (limits.width == 0f) 0f else speed.x, if (limits.height == 0f) 0f else speed.y)
        travel *= 1f - hit.fraction
    }
    return ReaderIslandMotion(position, speed * speedRatio)
}
