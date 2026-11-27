package app.inkask.tablet

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.graphics.pdf.content.PdfPageTextContent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class PageSize(val width: Int, val height: Int) {
    fun rotated(rotation: Int) = if (rotation % 180 == 0) this else PageSize(height, width)
}

internal data class PdfPageLink(val targetPage: Int, val bounds: List<RectF>)
internal data class PdfPageInfo(val size: PageSize, val links: List<PdfPageLink>)

internal data class PdfRange(val page: Int, val quote: String, val boxes: List<RectF>) {
    fun anchor(size: PageSize): JSONObject = JSONObject().put("page", page).put("quote", quote)
        .put("rects", JSONArray(boxes.map { it.pdfRect(size) }))

    fun bounds(): RectF = RectF(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }
}

internal fun RectF.pdfRect(size: PageSize) = JSONArray(listOf(left, size.height - bottom, right, size.height - top))

internal class NativePdf(private val file: File) : Closeable {
    private val renderer: PdfRenderer = openReader()
    private var previousAndroidRenderer: PdfRendererPreV? = null
    private var closed = false
    val pageCount: Int = renderer.pageCount
    val canSelectText = Build.VERSION.SDK_INT >= 35 || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    @Synchronized
    fun size(page: Int): PageSize = renderer.openPage(page - 1).use { PageSize(it.width, it.height) }

    @Synchronized
    fun pageInfo(page: Int): PdfPageInfo = renderer.openPage(page - 1).use { source ->
        val size = PageSize(source.width, source.height)
        val links = try {
            val targets = when {
                Build.VERSION.SDK_INT >= 35 -> source.gotoLinks
                canSelectText -> openCompatibleRenderer().openPage(page - 1).use { it.gotoLinks }
                else -> emptyList()
            }
            targets.mapNotNull { link ->
                val index = link.destination.pageNumber
                val bounds = link.bounds.filterNot { it.isEmpty }
                if (index in 0 until pageCount && bounds.isNotEmpty()) PdfPageLink(index + 1, bounds) else null
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
        PdfPageInfo(size, links)
    }

    @Synchronized
    fun render(page: Int, pixelWidth: Int, rotation: Int): Bitmap =
        renderer.openPage(page - 1).use { source ->
            val size = PageSize(source.width, source.height).rotated(rotation)
            val aspect = size.height.toDouble() / size.width
            val width = minOf(pixelWidth.coerceAtLeast(1), 4096, (4096 / aspect).toInt().coerceAtLeast(1),
                sqrt(6_000_000 / aspect).toInt().coerceAtLeast(1))
            val height = ceil(width * aspect).toInt().coerceIn(1, 4096)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val matrix = rotationMatrix(PageSize(source.width, source.height), rotation)
                matrix.postScale(width.toFloat() / size.width, height.toFloat() / size.height)
                source.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        }

    @Synchronized
    fun select(page: Int, first: Point?, last: Point?): PdfRange? {
        if (!canSelectText) return null
        return selectedContent(page, first, last)?.toRange(page)
    }

    private fun selectedContent(page: Int, first: Point?, last: Point?): PageSelection? {
        val start = first?.let(::SelectionBoundary) ?: SelectionBoundary(0)
        val end = last?.let(::SelectionBoundary) ?: SelectionBoundary(Int.MAX_VALUE)
        return if (Build.VERSION.SDK_INT >= 35) {
            renderer.openPage(page - 1).use { it.selectContent(start, end) }
        } else {
            openCompatibleRenderer().openPage(page - 1).use { it.selectContent(start, end) }
        }
    }

    @Synchronized
    fun text(page: Int): String = textContents(page).joinToString("\n") { it.text }

    @Synchronized
    fun index(page: Int): JSONObject {
        val size = size(page)
        val contents = textContents(page)
        val text = contents.joinToString("\n") { it.text }
        // Some Android renderers return text without coordinates in getTextContents().
        val geometry = if (text.isNotBlank() && contents.any { it.text.isNotBlank() && it.bounds.isEmpty() }) {
            selectedContent(page, null, null)?.selectedTextContents.orEmpty().ifEmpty { contents }
        } else contents
        val words = geometry.flatMap { item ->
            val boxes = item.bounds.filterNot { it.isEmpty }
            val lines = item.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            when {
                boxes.isEmpty() || lines.isEmpty() -> emptyList()
                boxes.size == lines.size -> lines.zip(boxes).map { (line, box) ->
                    JSONObject().put("text", line).put("rect", box.pdfRect(size))
                }
                else -> {
                    val bounds = RectF(boxes.first()).apply { boxes.drop(1).forEach { union(it) } }
                    listOf(JSONObject().put("text", item.text.trim()).put("rect", bounds.pdfRect(size)))
                }
            }
        }
        return JSONObject().put("page", page).put("width", size.width).put("height", size.height)
            .put("source", "native").put("status", if (text.isBlank()) "empty" else "ready")
            .put("text", text).put("words", JSONArray(words))
    }

    private fun textContents(page: Int): List<PdfPageTextContent> {
        if (!canSelectText) return emptyList()
        return if (Build.VERSION.SDK_INT >= 35) {
            renderer.openPage(page - 1).use { it.textContents }
        } else {
            openCompatibleRenderer().openPage(page - 1).use { it.textContents }
        }
    }

    @Synchronized
    fun crop(range: PdfRange, rotation: Int): ByteArray {
        val bounds = range.bounds()
        val matrix = rotationMatrix(size(range.page), rotation)
        matrix.mapRect(bounds)
        require(!bounds.isEmpty) { "选区没有可截图的范围。" }
        val scale = minOf(2f, 1600f / maxOf(bounds.width(), bounds.height()))
        val width = ceil(bounds.width() * scale).toInt().coerceAtLeast(1)
        val height = ceil(bounds.height() * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            matrix.postTranslate(-bounds.left, -bounds.top)
            matrix.postScale(scale, scale)
            renderer.openPage(range.page - 1).use {
                it.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun openReader(): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
    }

    private fun openCompatibleRenderer(): PdfRendererPreV {
        check(canSelectText)
        previousAndroidRenderer?.let { return it }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRendererPreV(descriptor).also { previousAndroidRenderer = it } }
        catch (error: Exception) { descriptor.close(); throw error }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        previousAndroidRenderer?.close()
        renderer.close()
    }
}

internal fun rotationMatrix(size: PageSize, rotation: Int): Matrix = Matrix().apply {
    when (rotation) {
        90 -> { postRotate(90f); postTranslate(size.height.toFloat(), 0f) }
        180 -> { postRotate(180f); postTranslate(size.width.toFloat(), size.height.toFloat()) }
        270 -> { postRotate(270f); postTranslate(0f, size.width.toFloat()) }
    }
}

private fun PageSelection.toRange(page: Int): PdfRange? {
    val texts = selectedTextContents
    val boxes = texts.flatMap { content -> content.bounds.map { RectF(it) } }.filterNot { it.isEmpty }
    if (boxes.isEmpty()) return null
    return PdfRange(page, texts.joinToString("\n") { it.text }.trim(), boxes)
}

internal fun JSONObject.pdfRange(size: PageSize): PdfRange = PdfRange(getInt("page"), optString("quote", optString("text")),
    optJSONArray("rects")?.let { boxes ->
        (0 until boxes.length()).map { index ->
            val box = boxes.getJSONArray(index)
            RectF(box.getDouble(0).toFloat(), size.height - box.getDouble(3).toFloat(),
                box.getDouble(2).toFloat(), size.height - box.getDouble(1).toFloat())
        }
    }.orEmpty())

internal fun JSONObject.missingNativeTextBounds(): Boolean =
    optString("source") == "native" && optString("status") == "ready" &&
        optString("text").isNotBlank() && optJSONArray("words").objects().isEmpty()
