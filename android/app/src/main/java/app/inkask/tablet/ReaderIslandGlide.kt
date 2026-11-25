package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

internal enum class ReaderIslandGlideMode(val restitution: Float, val maxSeconds: Float) {
    Normal(.52f, .82f),
    EasterEgg(.92f, 2.85f);

    fun dragAt(elapsedSeconds: Float): Float {
        if (this == Normal) return 6.2f
        val braking = ((elapsedSeconds - 1.45f) / .65f).coerceIn(0f, 1f)
        return .72f + (6.2f - .72f) * braking
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
        val drag = mode.dragAt(elapsedSeconds)
        val bounce = mode.restitution
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

internal class ReaderIslandVelocity(downTime: Long, downPosition: Offset) {
    private data class Sample(val time: Long, val position: Offset)
    private val samples = ArrayDeque<Sample>().apply { add(Sample(downTime, downPosition)) }

    fun sample(time: Long, position: Offset) {
        val latest = samples.last()
        if (time <= latest.time || position == latest.position) return
        while (samples.size > 1 && time - samples[1].time > 52L) samples.removeFirst()
        samples.addLast(Sample(time, position))
    }

    fun release(time: Long, position: Offset): Offset {
        if (time - samples.last().time > 48L) return Offset.Zero
        sample(time, position)
        val first = samples.first()
        val last = samples.last()
        val duration = last.time - first.time
        if (duration !in 5L..86L) return Offset.Zero
        return (last.position - first.position) * (1000f / duration)
    }
}
