package app.inkask.tablet

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal data class AttachmentPreview(val id: String, val name: String, val file: File? = null, val uri: Uri? = null)

internal fun AttachmentPreview.decode(context: Context, maxDimension: Float): Bitmap? = runCatching {
    val source = file?.let(ImageDecoder::createSource)
        ?: ImageDecoder.createSource(context.contentResolver, requireNotNull(uri))
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val ratio = minOf(1f, maxDimension / maxOf(width, height))
        decoder.setTargetSize((width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1))
    }
}.getOrNull()

internal fun clipboardImages(context: Context): List<Uri> {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { index ->
        clip.getItemAt(index).uri?.takeIf { uri ->
            uri.scheme == "content" && runCatching { context.contentResolver.getType(uri)?.startsWith("image/") }.getOrNull() == true
        }
    }.distinct()
}

@Composable
internal fun Modifier.pasteImages(attach: (List<Uri>) -> Unit): Modifier {
    val context = LocalContext.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.V || !(event.isCtrlPressed || event.isMetaPressed)) {
            false
        } else {
            val images = clipboardImages(context)
            if (images.isEmpty()) false else {
                attach(images)
                true
            }
        }
    }
}

@Composable
internal fun AttachmentControls(onAttach: (List<Uri>) -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    Control("附加图片") { picker.launch(arrayOf("image/*")) }
    Control("粘贴图片") {
        val images = clipboardImages(context)
        if (images.isEmpty()) report("剪贴板没有可读取的图片。") else onAttach(images)
    }
}

@Composable
internal fun AttachmentPreviews(images: List<AttachmentPreview>, remove: ((String) -> Unit)? = null) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { it.id }) { image ->
            val bitmap by produceState<Bitmap?>(null, image) {
                value = withContext(Dispatchers.IO) { image.decode(context, 360f) }
            }
            Column(Modifier.width(138.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), image.name,
                    Modifier.fillMaxWidth().height(92.dp), contentScale = ContentScale.Fit) }
                Text(image.name, fontSize = 11.sp, color = Muted, maxLines = 1)
                if (remove != null) Control("移除", "移除图片 ${image.name}") { remove(image.id) }
            }
        }
    }
}
