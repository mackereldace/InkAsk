package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

internal data class ReaderIslandLaunch(val edge: ReaderIslandEdge, val velocity: Offset)

internal fun readerIslandLaunch(
    velocity: Offset,
    edge: ReaderIslandEdge,
    minSpeed: Float,
    maxSpeed: Float,
    bounceSpeed: Float,
): ReaderIslandLaunch? {
    val outward = when (edge) {
        ReaderIslandEdge.Free -> 0f
        ReaderIslandEdge.Top -> -velocity.y
        ReaderIslandEdge.Bottom -> velocity.y
        ReaderIslandEdge.Left -> -velocity.x
        ReaderIslandEdge.Right -> velocity.x
    }
    val launchEdge = if (outward >= bounceSpeed) ReaderIslandEdge.Free else edge
    val direction = when {
        launchEdge == ReaderIslandEdge.Free -> velocity
        launchEdge.vertical -> Offset(0f, velocity.y)
        else -> Offset(velocity.x, 0f)
    }
    val speed = direction.getDistance()
    if (!speed.isFinite() || speed < minSpeed) return null
    return ReaderIslandLaunch(launchEdge, direction * (speed.coerceAtMost(maxSpeed) / speed))
}

internal data class ReaderIslandMotion(val center: Offset, val velocity: Offset) {
    fun advance(seconds: Float, viewport: IntSize, island: IntSize, margin: Float): ReaderIslandMotion {
        val drag = 6.2f
        val bounce = .52f
        val remainingSpeed = exp(-drag * seconds)
        val travel = (1f - remainingSpeed) / drag

        fun axis(start: Float, speed: Float, space: Int, length: Int): Pair<Float, Float> {
            val min = (length / 2f + margin).coerceAtMost(space / 2f)
            val max = space - min
            if (min >= max) return min to 0f
            val next = start.coerceIn(min, max) + speed * travel
            val slowed = speed * remainingSpeed
            return when {
                next < min -> (min + (min - next) * bounce).coerceAtMost(max) to -slowed * bounce
                next > max -> (max - (next - max) * bounce).coerceAtLeast(min) to -slowed * bounce
                else -> next to slowed
            }
        }

        val (x, vx) = axis(center.x, velocity.x, viewport.width, island.width)
        val (y, vy) = axis(center.y, velocity.y, viewport.height, island.height)
        return ReaderIslandMotion(Offset(x, y), Offset(vx, vy))
    }
}
