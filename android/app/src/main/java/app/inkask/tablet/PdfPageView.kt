package app.inkask.tablet

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
                         notes: List<PageNote>, located: PdfRange?,
                         layouts: MutableMap<Int, PdfPageLayout>, handles: List<PdfHandle>,
                         horizontal: Boolean, across: Float,
                         onNote: (String) -> Unit, report: (String) -> Unit) {
    val pageInfo by produceState<PdfPageInfo?>(null, pdf, page) {
        value = runCatching { withContext(Dispatchers.IO) { pdf.pageInfo(page) } }.getOrNull()
    }
    val pageSize = pageInfo?.size ?: PageSize(595, 842)
    val links = pageInfo?.links.orEmpty()
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
    var layout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    SideEffect {
        val coordinates = layout
        if (coordinates != null && pageInfo != null) layouts[page] = PdfPageLayout(coordinates, pageSize, rotation, links)
    }
    DisposableEffect(layouts, page) {
        onDispose {
            if (layouts[page]?.coordinates === layout) layouts.remove(page)
        }
    }

    Box(Modifier.offset {
        val offset = across.roundToInt()
        if (horizontal) IntOffset(0, offset) else IntOffset(offset, 0)
    }.requiredSize(width, height).background(Color.White)
        .onGloballyPositioned {
            layout = it
            layouts[page] = PdfPageLayout(it, pageSize, rotation, links)
        }.semantics { contentDescription = "PDF 第 $page 页" }) {
        pageImage?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
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
