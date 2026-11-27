package app.inkask.tablet

import androidx.compose.ui.geometry.Offset

internal class DragReleaseVelocity(downTime: Long, downPosition: Offset) {
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
