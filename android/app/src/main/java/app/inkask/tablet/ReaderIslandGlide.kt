package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.exp

internal val IslandFlingMinSpeed = 360.dp
internal val IslandFlingMaxSpeed = 5200.dp
internal val IslandFlingBounceSpeed = 1000.dp
internal val IslandFlingEasterEggSpeed = 2300.dp
internal val IslandFlingStopSpeed = 38.dp

internal enum class ReaderIslandGlideMode(val restitution: Float, val maxSeconds: Float) {
    Normal(.52f, .82f),
    EasterEgg(.92f, 3.80f);

    fun dragAt(elapsedSeconds: Float): Float {
        if (this == Normal) return 6.2f
        val braking = ((elapsedSeconds - 2.15f) / .75f).coerceIn(0f, 1f)
        return .25f + (6.2f - .25f) * braking
    }
}

internal data class ReaderIslandLaunch(
    val edge: ReaderIslandEdge,
    val velocity: Offset,
    val mode: ReaderIslandGlideMode,
)

internal fun readerIslandLaunch(
    velocity: Offset,
    edge: ReaderIslandEdge,
    minSpeed: Float,
    maxSpeed: Float,
    bounceSpeed: Float,
    easterEggSpeed: Float,
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
    val mode = if (speed >= easterEggSpeed) ReaderIslandGlideMode.EasterEgg else ReaderIslandGlideMode.Normal
    return ReaderIslandLaunch(launchEdge, direction * (speed.coerceAtMost(maxSpeed) / speed), mode)
}

internal data class ReaderIslandMotion(val center: Offset, val velocity: Offset) {
    fun advance(
        seconds: Float,
        viewport: IntSize,
        island: IntSize,
        margin: Float,
        mode: ReaderIslandGlideMode = ReaderIslandGlideMode.Normal,
        elapsedSeconds: Float = 0f,
    ): ReaderIslandMotion {
        val x = (island.width / 2f + margin).coerceAtMost(viewport.width / 2f)
        val y = (island.height / 2f + margin).coerceAtMost(viewport.height / 2f)
        return advance(seconds, Rect(x, y, viewport.width - x, viewport.height - y), mode, elapsedSeconds)
    }

    fun advance(
        seconds: Float, limits: Rect, mode: ReaderIslandGlideMode, elapsedSeconds: Float,
    ): ReaderIslandMotion {
        val drag = mode.dragAt(elapsedSeconds)
        val bounce = mode.restitution
        val remainingSpeed = exp(-drag * seconds)
        val travel = (1f - remainingSpeed) / drag

        fun axis(start: Float, speed: Float, min: Float, max: Float): Pair<Float, Float> {
            if (min >= max) return min to 0f
            val next = start.coerceIn(min, max) + speed * travel
            val slowed = speed * remainingSpeed
            return when {
                next < min -> (min + (min - next) * bounce).coerceAtMost(max) to -slowed * bounce
                next > max -> (max - (next - max) * bounce).coerceAtLeast(min) to -slowed * bounce
                else -> next to slowed
            }
        }

        val (x, vx) = axis(center.x, velocity.x, limits.left, limits.right)
        val (y, vy) = axis(center.y, velocity.y, limits.top, limits.bottom)
        return ReaderIslandMotion(Offset(x, y), Offset(vx, vy))
    }
}
