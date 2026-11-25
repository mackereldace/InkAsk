package app.inkask.tablet

import android.graphics.Point
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun scanPage(pdf: NativePdf, page: Int): JSONObject {
    val (bitmap, pageSize) = withContext(Dispatchers.IO) { pdf.render(page, 2100, 0) to pdf.size(page) }
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    val scaleX = pageSize.width.toFloat() / bitmap.width
    val scaleY = pageSize.height.toFloat() / bitmap.height
    val result = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
            recognizer.close()
            bitmap.recycle()
            if (continuation.isActive) {
                if (task.isSuccessful) continuation.resume(task.result)
                else continuation.resumeWithException(IllegalStateException("本页识别失败。", task.exception))
            }
        }
    }
    val words = result.textBlocks.flatMap { it.lines }.flatMap { line ->
        line.elements.mapNotNull { word ->
            val box = word.boundingBox ?: return@mapNotNull null
            JSONObject().put("text", word.text).put("rect", JSONArray(listOf(
                box.left * scaleX, pageSize.height - box.bottom * scaleY,
                box.right * scaleX, pageSize.height - box.top * scaleY,
            )))
        }
    }
    return JSONObject().put("page", page).put("text", result.text).put("source", "ocr")
        .put("status", if (words.isEmpty()) "empty" else "ready")
        .put("width", pageSize.width).put("height", pageSize.height).put("words", JSONArray(words))
}

internal fun JSONObject.ocrRange(start: Point?, end: Point?): PdfRange? {
    val height = getInt("height")
    val words = optJSONArray("words").objects()
    if (words.isEmpty()) return null
    val bounds = words.map {
        val box = it.getJSONArray("rect")
        RectF(box.getDouble(0).toFloat(), height - box.getDouble(3).toFloat(),
            box.getDouble(2).toFloat(), height - box.getDouble(1).toFloat())
    }
    fun nearest(point: Point): Int = bounds.indices.minBy { index ->
        val box = bounds[index]
        val x = point.x.coerceIn(box.left.toInt(), box.right.toInt())
        val y = point.y.coerceIn(box.top.toInt(), box.bottom.toInt())
        (point.x - x).toDouble() * (point.x - x) + (point.y - y).toDouble() * (point.y - y)
    }
    val first = start?.let(::nearest) ?: 0
    if (start != null && start == end && !bounds[first].contains(start.x.toFloat(), start.y.toFloat())) return null
    val last = end?.let(::nearest) ?: words.lastIndex
    val order = minOf(first, last)..maxOf(first, last)
    return PdfRange(getInt("page"), order.joinToString(" ") { words[it].getString("text") }, order.map { bounds[it] })
}
