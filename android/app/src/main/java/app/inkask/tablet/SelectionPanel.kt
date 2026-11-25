package app.inkask.tablet

import android.net.Uri
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun SelectionPanel(
    quote: String, imageOnly: Boolean, cropAvailable: Boolean, configured: Boolean, busy: Boolean,
    modifier: Modifier, maxHeight: Dp, bottom: Boolean, templates: List<JSONObject>,
    draft: JSONObject?, savedImages: List<AttachmentPreview>, core: SharedReadingCore,
    onDismiss: () -> Unit,
    submit: (String, String, Boolean, List<Uri>, JSONObject?, Boolean, List<String>) -> Unit,
    settings: () -> Unit, report: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.mainActivity() }
    val dismissPanel by rememberUpdatedState(onDismiss)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(activity) {
        val outsideTap = ComposerOutsideTap(context, bounds = { bounds }, dismiss = { dismissPanel() })
        activity?.composerOutsideTap = outsideTap
        onDispose { if (activity?.composerOutsideTap === outsideTap) activity.composerOutsideTap = null }
    }

    var question by remember { mutableStateOf(draft?.optString("draft").orEmpty()) }
    var capture by remember { mutableStateOf(imageOnly) }
    var images by remember { mutableStateOf(emptyList<Uri>()) }
    var retained by remember { mutableStateOf(savedImages) }
    var template by remember { mutableStateOf(templates.firstOrNull { it.optString("id") == draft?.optString("templateId") }) }
    var tasksOpen by remember { mutableStateOf(false) }
    fun attach(uris: List<Uri>) {
        val incoming = (images + uris).distinct()
        if (incoming.size + retained.size > 4) report("每次最多附 4 张图片。") else images = incoming
    }
    fun send(task: String = if (question.isBlank()) "explain" else "custom", save: Boolean = false) {
        if (busy) return
        if (configured || save) {
            submit(question, template?.getString("task") ?: task, capture, images, template, save, retained.map { it.id })
        } else settings()
    }

    Surface(
        modifier = modifier.widthIn(max = if (bottom) 560.dp else 440.dp).fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .semantics {
                paneTitle = "选区提问"
                dismiss { onDismiss(); true }
            },
        color = Paper,
        border = BorderStroke(1.dp, Line),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.heightIn(max = maxHeight.coerceAtLeast(142.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectionQuote(quote.ifBlank { "选中的 PDF 区域" }, core, minOf(96.dp, maxHeight * .34f))
                OutlinedTextField(
                    question, { question = it.take(4_000) },
                    placeholder = { Text("输入问题，留空则解释选文") },
                    minLines = 2, maxLines = 5, enabled = !busy, shape = ControlShape,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green,
                        unfocusedBorderColor = Line,
                        focusedContainerColor = Paper,
                        unfocusedContainerColor = Bar,
                    ),
                    modifier = Modifier.fillMaxWidth().pasteImages(::attach).questionShortcut { send() },
                )
                AttachmentPreviews(retained + images.map { uri ->
                    AttachmentPreview(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "图片", uri = uri)
                }) { id ->
                    retained = retained.filterNot { it.id == id }
                    images = images.filterNot { it.toString() == id }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cropAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(capture, { capture = it }, enabled = !imageOnly)
                        Text("附选区截图", color = Muted, fontSize = 13.sp)
                    }
                    AttachmentControls(::attach, report)
                    TemplateMenu(templates, template?.getString("id"), automatic = true) { template = it }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp), color = Line)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Control("保存草稿", enabled = !busy) { send(save = true) }
                Box {
                    Control("更多任务", enabled = !busy && template == null) { tasksOpen = true }
                    DropdownMenu(tasksOpen, { tasksOpen = false }, containerColor = Paper) {
                        listOf("总结" to "summarize", "解释公式" to "formula", "翻译" to "translate").forEach { (label, task) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { tasksOpen = false; send(task) })
                        }
                    }
                }
                Button(onClick = { send() }, enabled = !busy, shape = ControlShape) {
                    Text(if (busy) "正在保存…" else if (configured) "提问" else "配置模型")
                }
            }
        }
    }
}

@Composable
private fun SelectionQuote(quote: String, core: SharedReadingCore, height: Dp) {
    Surface(color = PaleGreen, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        key(quote) {
            BoxWithConstraints(
                Modifier.heightIn(max = height.coerceAtLeast(40.dp)).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (quote.none { it == '$' || it == '\\' }) {
                    Text(quote, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    val display by produceState(quote, quote, core) {
                        value = withContext(Dispatchers.Default) {
                            runCatching { core.citations(quote, emptyList()).optString("nativeContent", quote) }
                                .getOrDefault(quote)
                        }
                    }
                    val width = with(LocalDensity.current) { maxWidth.roundToPx().coerceAtLeast(1) }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { context ->
                            TextView(context).apply { setTextColor(Ink.toArgb()); textSize = 14f; includeFontPadding = false }
                        },
                        update = { view ->
                            val renderKey = display to width
                            if (view.tag != renderKey) {
                                view.tag = renderKey
                                Markwon.builder(view.context).useFormulas(width, view.textSize).build().setMarkdown(view, display)
                            }
                        },
                    )
                }
            }
        }
    }
}
