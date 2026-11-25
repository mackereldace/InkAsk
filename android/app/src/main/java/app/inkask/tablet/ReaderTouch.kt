package app.inkask.tablet

import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass

internal fun Modifier.questionShortcut(send: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
        (event.isCtrlPressed || event.isMetaPressed)) {
        send()
        true
    } else false
}

internal fun PointerEvent.pointerCount(): Int = changes.count { it.pressed }

internal fun Context.mainActivity(): MainActivity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<MainActivity>().firstOrNull()

// Let the reader receive its touch events while observing taps outside the composer.
internal class ComposerOutsideTap(context: Context, private val bounds: () -> Rect?, val dismiss: () -> Unit) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var down: Offset? = null

    fun isOutsideTap(event: MotionEvent): Boolean {
        val position = Offset(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = position.takeIf { bounds()?.contains(position) == false }
            MotionEvent.ACTION_MOVE -> if (down?.let { (position - it).getDistance() > slop } == true) down = null
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> down = null
            MotionEvent.ACTION_UP -> {
                val start = down
                down = null
                return start != null && (position - start).getDistance() <= slop &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    bounds()?.contains(position) == false
            }
        }
        return false
    }
}

internal suspend fun AwaitPointerEventScope.followReaderPinch(
    initial: PointerEvent, onStart: (Offset) -> Unit, onZoom: (Float, Offset) -> Unit,
) {
    val fingers = initial.changes.filter { it.pressed }
    onStart(fingers.fold(Offset.Zero) { sum, finger -> sum + finger.position } / fingers.size.toFloat())
    initial.changes.forEach { it.consume() }
    var event: PointerEvent
    do {
        event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
            onZoom(event.calculateZoom(), event.calculateCentroid())
        }
        event.changes.forEach { it.consume() }
    } while (event.pointerCount() > 0)
}

// Answer text uses Android's selectable TextView, whose touch handling can bypass
// Compose pointer input. Recognize the pinch before passing events to either reader.
internal class AnswerPinch(
    context: Context,
    private val contains: (Offset) -> Boolean,
    onStart: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
) {
    private var insideReader = false
    private var intercepted = false
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            onStart(Offset(detector.focusX, detector.focusY))
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onZoom(detector.scaleFactor, Offset(detector.focusX, detector.focusY))
            return true
        }
    }).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }

    fun dispatch(event: MotionEvent, forward: (MotionEvent) -> Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            insideReader = contains(Offset(event.x, event.y))
            intercepted = false
        }
        if (!insideReader) return forward(event)
        if (!intercepted && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            intercepted = true
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            try { forward(cancel) } finally { cancel.recycle() }
        }
        scale.onTouchEvent(event)
        return intercepted || forward(event)
    }
}
