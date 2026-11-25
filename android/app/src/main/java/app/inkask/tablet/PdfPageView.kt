package app.inkask.tablet

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class PageNote(val number: Int, val note: JSONObject, val anchor: JSONObject)

@Composable
internal fun PdfPageView(pdf: NativePdf, page: Int, width: Dp, rotation: Int,
                         notes: List<PageNote>, located: PdfRange?, region: Boolean,
                         layouts: MutableMap<Int, PdfPageLayout>, handles: List<PdfHandle>,
                         horizontal: Boolean, across: Float, viewportCrossSize: Int,
                         onSelect: (PdfRange, Offset?) -> Unit, onNote: (String) -> Unit, report: (String) -> Unit) {
    val nativeSize by produceState<PageSize?>(null, pdf, page) {
        value = runCatching { withContext(Dispatchers.IO) { pdf.size(page) } }.getOrNull()
    }
    val pageSize = nativeSize ?: PageSize(595, 842)
    val rotated = pageSize.rotated(rotation)
    val height = width * (rotated.height.toFloat() / rotated.width)
    val pixels = with(LocalDensity.current) { width.roundToPx() }
    val renderWidth = ((pixels + 95) / 96 * 96).coerceAtLeast(1)
    var pageImage by remember(pdf, page, rotation) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(pdf, page, renderWidth, rotation) {
        if (pageImage != null) delay(105)
        try { pageImage = withContext(Dispatchers.IO) { pdf.render(page, renderWidth, rotation) } }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { report("第 $page 页无法渲染。") }
    }
    val deliver by rememberUpdatedState(onSelect)
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var layout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    SideEffect {
        val coordinates = layout
        if (coordinates != null && nativeSize != null) layouts[page] = PdfPageLayout(coordinates, pageSize, rotation)
    }
    DisposableEffect(layouts, page) { onDispose { layouts.remove(page) } }

    val maxAcross = with(LocalDensity.current) {
        (((if (horizontal) height else width).toPx() - viewportCrossSize) / 2).coerceAtLeast(0f)
    }
    Box(Modifier.offset {
        val offset = across.coerceIn(-maxAcross, maxAcross).roundToInt()
        if (horizontal) IntOffset(0, offset) else IntOffset(offset, 0)
    }.requiredSize(width, height).background(Color.White)
        .onGloballyPositioned { layout = it }.semantics { contentDescription = "PDF 第 $page 页" }) {
        pageImage?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        val regionGesture = if (!region) Modifier else Modifier.pointerInput(page, pageSize, rotation) {
            fun complete() {
                val (first, last) = drawing ?: return
                drawing = null
                val geometry = layouts[page]?.takeIf { it.coordinates.isAttached } ?: return
                val a = geometry.pagePoint(first)
                val b = geometry.pagePoint(last)
                val box = RectF(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat(),
                    maxOf(a.x, b.x).toFloat(), maxOf(a.y, b.y).toFloat())
                if (box.width() > 1 && box.height() > 1) {
                    deliver(PdfRange(page, "", listOf(box)), geometry.coordinates.localToWindow(last))
                }
            }
            detectDragGestures(onDragStart = { drawing = it to it }, onDragCancel = { drawing = null }, onDragEnd = ::complete) { change, _ ->
                change.consume()
                drawing?.let { drawing = it.first to change.position }
            }
        }
        Canvas(Modifier.fillMaxSize().then(regionGesture)) {
            val matrix = rotationMatrix(pageSize, rotation)
            matrix.postScale(size.width / rotated.width, size.height / rotated.height)
            fun mark(box: RectF, color: Color) {
                val mapped = RectF(box)
                matrix.mapRect(mapped)
                drawRect(color, Offset(mapped.left, mapped.top), Size(mapped.width(), mapped.height()))
            }
            notes.forEach { item -> runCatching { item.anchor.pdfRange(pageSize) }.getOrNull()?.boxes
                ?.forEach { mark(it, Color(0x4497BF46)) } }
            located?.takeIf { it.page == page }?.boxes?.forEach { mark(it, Color(0x70A3C85E)) }
            handles.filter { it.page == page }.forEach { handle ->
                val mapped = floatArrayOf(handle.point.x, handle.point.y)
                matrix.mapPoints(mapped)
                val point = Offset(mapped[0], mapped[1])
                drawCircle(Color.White, 8.dp.toPx(), point)
                drawCircle(Green, 6.dp.toPx(), point)
            }
            drawing?.takeIf { region }?.let { (a, b) ->
                drawRect(Color(0x709EBD6D), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            }
        }
        notes.forEach { item ->
            val box = runCatching { item.anchor.pdfRange(pageSize).boxes.firstOrNull() }.getOrNull()
            if (box != null) {
                val rotatedBox = RectF(box)
                rotationMatrix(pageSize, rotation).mapRect(rotatedBox)
                Box(Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = height * (rotatedBox.top / rotated.height).coerceIn(0f, .95f))) {
                    androidx.compose.material3.Button(onClick = { onNote(item.note.getString("id")) },
                        shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(31.dp)) {
                        Text(item.number.toString(), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
