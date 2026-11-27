package app.inkask.tablet

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun ReaderButton(
    description: String, icon: Int? = null, label: String? = null, state: String? = null,
    enabled: Boolean = true, active: Boolean = false, modifier: Modifier = Modifier, action: () -> Unit,
) {
    FilledIconButton(
        onClick = action,
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Green else PaleGreen,
            contentColor = if (active) Paper else Green,
            disabledContainerColor = Bar,
            disabledContentColor = Muted.copy(alpha = .42f),
        ),
        modifier = modifier.size(44.dp).semantics {
            contentDescription = description
            if (state != null) stateDescription = state
        },
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(19.dp))
        else if (label != null) Text(
            label, fontWeight = FontWeight.SemiBold,
            fontSize = if (label.length > 3) 10.sp else 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReaderSelectionModeButton(mode: PdfSelectionMode, vertical: Boolean, onModeChange: (PdfSelectionMode) -> Unit) {
    val textMode = mode == PdfSelectionMode.Text
    FilledIconButton(
        onClick = { onModeChange(if (textMode) PdfSelectionMode.Region else PdfSelectionMode.Text) },
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Bar, contentColor = Ink),
        modifier = Modifier.size(width = if (vertical) 44.dp else 72.dp, height = 44.dp)
            .border(1.dp, Line, CircleShape)
            .semantics {
                contentDescription = "选取方式"
                stateDescription = if (textMode) "文字选取" else "区域框选"
            },
    ) {
        Crossfade(targetState = mode, animationSpec = tween(120), label = "Selection mode") { displayed ->
            val text = displayed == PdfSelectionMode.Text
            val content: @Composable () -> Unit = {
                Icon(
                    painterResource(if (text) R.drawable.inkask_ic_text_cursor else R.drawable.inkask_ic_scan),
                    contentDescription = null, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(if (vertical) 1.dp else 5.dp))
                Text(
                    if (text) "文字" else "区域", fontSize = if (vertical) 10.sp else 12.sp,
                    lineHeight = if (vertical) 13.sp else 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (vertical) Column(
                Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() } else Row(
                Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    }
}

internal fun JSONObject.answerMessages(): List<JSONObject> =
    optJSONArray("messages").objects().filter { it.optString("role") == "assistant" }

@Composable
internal fun ReaderPath(
    document: String, page: Int, notes: List<JSONObject>, currentAnswer: String?,
    onPdf: () -> Unit, onNote: (Int) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("便签路径") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Surface(
                        onClick = onPdf, shape = ControlShape, color = PaleGreen,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "原始 PDF" },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.inkask_ic_file_text), contentDescription = null, tint = Green,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(document, color = Ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("原始 PDF · 第 $page 页", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                itemsIndexed(notes, key = { _, note -> note.getString("id") }) { index, note ->
                    val current = index == notes.lastIndex
                    val target = if (current) currentAnswer else notes[index + 1].optString("parentMessageId")
                    val answers = note.answerMessages()
                    val version = answers.indexOfFirst { it.optString("id") == target } + 1
                    val detail = listOfNotNull(
                        "第 ${index + 1} 层",
                        "回答 $version".takeIf { version > 0 },
                        "当前便签".takeIf { current },
                    ).joinToString(" · ")
                    Surface(
                        onClick = { onNote(index) }, enabled = !current, shape = ControlShape,
                        color = if (current) PaleGreen else Paper,
                        modifier = Modifier.fillMaxWidth().padding(start = (8 * (index + 1).coerceAtMost(4)).dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(note.optString("title").ifBlank { "便签" }, color = Ink, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(detail, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = Paper,
    )
}

@Composable
internal fun ReaderNoteDetails(
    note: JSONObject, canDelete: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    val original = note.optString("title")
    var title by remember(note.getString("id"), original) { mutableStateOf(original) }
    var confirmingDelete by remember { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除便签？") },
            text = { Text("这条便签及其关联的追问将一起删除。") },
            confirmButton = { TextButton(onClick = onDelete, enabled = canDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
            containerColor = Paper,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("便签信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("便签标题") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { confirmingDelete = true }, enabled = canDelete) { Text("删除此便签及子追问") }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank() && title.trim() != original) {
                    Text("保存标题")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            containerColor = Paper,
        )
    }
}
