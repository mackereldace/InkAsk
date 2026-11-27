package app.inkask.tablet

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

private fun pdfTextDigest(text: String): String {
    var hash = 2166136261u
    for (char in text) hash = (hash xor char.code.toUInt()) * 16777619u
    return "${text.length}:${hash.toString(36)}"
}

internal fun JSONObject.currentPdfAnalysis(): JSONObject? =
    optJSONObject("analysis")?.takeIf {
        it.optInt("version") == 1 && it.optString("sourceDigest") == pdfTextDigest(optString("text"))
    }

internal suspend fun analyzePdfPage(
    doc: Opened, workspace: Workspace, model: ReadingModel, profile: ModelProfile, target: Int, repeat: Boolean,
): JSONObject? {
    val snapshot = withContext(Dispatchers.IO) {
        workspace.project(doc.item.id).getJSONArray("pages").objects().associateBy { it.getInt("page") }
    }
    if (!repeat && snapshot[target]?.currentPdfAnalysis() != null) return null

    val candidates = (maxOf(1, target - 1)..minOf(doc.pdf.pageCount, target + 1)).toList()
    val pages = withContext(Dispatchers.IO) { candidates.map { snapshot[it] ?: doc.pdf.index(it) } }
    val base = pages.single { it.getInt("page") == target }
    val prompt = withContext(Dispatchers.IO) { workspace.core.pdfAnalysisPrompt(pages, target) }
    val images = withContext(Dispatchers.IO) {
        candidates.map { number ->
            coroutineContext.ensureActive()
            val bitmap = doc.pdf.render(number, if (number == target) 1650 else 1180, 0)
            try {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 89, output)) { "无法生成第 $number 页的图像。" }
                InputImage("image/jpeg", output.toByteArray())
            } finally {
                bitmap.recycle()
            }
        }
    }
    val response = StringBuilder()
    model.stream(profile, prompt, emptyList(), images, readingInstructions = false).collect { chunk ->
        response.append(chunk)
        require(response.length <= 160_000) { "模型返回的解析结果太长。" }
    }

    return withContext(Dispatchers.IO) {
        val parsed = runCatching { workspace.core.parsePdfAnalysis(pages, target, response.toString()) }
            .getOrElse { throw IllegalArgumentException("第 $target 页的模型响应不是完整的解析结果。", it) }
        coroutineContext.ensureActive()
        workspace.update(doc.item.id) { project ->
            val records = project.getJSONArray("pages")
            val index = records.objects().indexOfFirst { it.getInt("page") == target }
            val stored = if (index == -1) base else records.getJSONObject(index)
            require(stored.optString("text") == base.optString("text")) { "第 $target 页的文字索引已更新，请重新解析该页。" }
            stored.put("analysis", parsed)
            if (index == -1) records.put(stored)
        }
    }
}

@Composable
internal fun PdfAnalysisDialog(
    current: Int, total: Int, profile: ModelProfile?, onDismiss: () -> Unit,
    configure: () -> Unit, start: (IntRange, Boolean) -> Unit,
) {
    var beginning by remember { mutableStateOf(current.toString()) }
    var ending by remember { mutableStateOf(current.toString()) }
    var repeat by remember { mutableStateOf(false) }
    val from = beginning.toIntOrNull()
    val to = ending.toIntOrNull()
    val range = if (from != null && to != null && from in 1..total && to in from..total) from..to else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 解析 PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (profile?.images == true)
                    "使用 ${profile.model} 对照所选页的原图和文字层，标注结构并校订文字。每次请求包含目标页和相邻页的图片。"
                else "启用模型的图片输入后，就能对照页图和文字层进行解析。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(beginning, { beginning = it }, Modifier.weight(1f), label = { Text("起始页") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(ending, { ending = it }, Modifier.weight(1f), label = { Text("结束页") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(repeat, { repeat = it })
                    Text("重新分析已有结果的页", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { beginning = "1"; ending = total.toString() }) { Text("整份 PDF") }
                }
                if (range != null) Text("所选范围共 ${range.last - range.first + 1} 页", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (profile?.images == true) {
                TextButton(enabled = range != null, onClick = { range?.let { start(it, repeat) } }) { Text("开始解析") }
            } else TextButton(onClick = configure) { Text("模型设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val structureLabels = mapOf(
    "theorem" to "定理", "proposition" to "命题", "lemma" to "引理", "corollary" to "推论",
    "definition" to "定义", "proof" to "证明", "example" to "例题", "remark" to "注",
)

@Composable
internal fun PdfAnalysisResultDialog(page: Int, analysis: JSONObject, discard: () -> Unit, onDismiss: () -> Unit) {
    val structures = analysis.optJSONArray("structures").objects()
    val corrections = analysis.optJSONArray("corrections").objects()
    val transcript = analysis.optJSONArray("transcript").strings()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("第 $page 页 · AI 解析") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (structures.isEmpty() && corrections.isEmpty() && transcript.isEmpty()) {
                    item { Text("本页没有识别到结构或需要校订的文字。") }
                }
                if (structures.isNotEmpty()) item { Text("结构", fontWeight = FontWeight.SemiBold) }
                items(structures) { item ->
                    Text(listOfNotNull(structureLabels[item.optString("kind")], item.optString("number"),
                        item.optString("title")).filter(String::isNotBlank).joinToString(" "))
                    val continuation = listOfNotNull("续前页".takeIf { item.optBoolean("continuesFromPrevious") },
                        "续下页".takeIf { item.optBoolean("continuesOnNext") })
                    Text((listOf("第 ${item.optInt("firstLine")}–${item.optInt("lastLine")} 行") + continuation)
                        .joinToString(" · "), fontSize = 12.sp, color = Muted)
                }
                if (corrections.isNotEmpty()) item { Text("文字校订", fontWeight = FontWeight.SemiBold) }
                items(corrections) { item ->
                    Text("第 ${item.optInt("line")} 行 · ${item.optString("original")}", color = Muted, fontSize = 12.sp)
                    Text(item.optString("text"), fontSize = 14.sp)
                }
                if (transcript.isNotEmpty()) item { Text("图像转写", fontWeight = FontWeight.SemiBold) }
                items(transcript) { Text(it, fontSize = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = discard) { Text("移除本页解析") } },
    )
}

@Composable
internal fun PdfAnalysisOutlineDialog(pages: Map<Int, JSONObject>, navigate: (Int) -> Unit, onDismiss: () -> Unit) {
    val entries = remember(pages) {
        pages.toSortedMap().flatMap { (page, record) ->
            record.currentPdfAnalysis()?.optJSONArray("structures").objects()
                .filterNot { it.optBoolean("continuesFromPrevious") }.map { page to it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("AI 文档结构") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entries.isEmpty()) item { Text("已解析的页面还没有可显示的文档结构。") }
                items(entries) { (page, item) ->
                    TextButton(onClick = { navigate(page) }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(listOfNotNull(structureLabels[item.optString("kind")], item.optString("number"),
                                item.optString("title")).filter(String::isNotBlank).joinToString(" "))
                            Text("PDF 第 $page 页 · 第 ${item.optInt("firstLine")} 行", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
