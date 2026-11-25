package app.inkask.tablet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun ModelSettings(model: ReadingModel, active: ModelProfile?, templates: List<JSONObject>,
                           saveTemplate: (String, String, String, String?) -> Unit,
                           storage: LocalStorage?, onChange: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val first = active?.provider ?: model.providers.first().getString("id")
    var form by remember { mutableStateOf((active ?: model.defaults(first)).copy(key = "")) }
    var protocol by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val configured = model.profile(form.provider)
    val provider = model.providers.first { it.getString("id") == form.provider }
    var templateId by remember { mutableStateOf(templates.firstOrNull()?.getString("id")) }
    val template = templates.firstOrNull { it.getString("id") == templateId } ?: templates.firstOrNull()
    var title by remember(template) { mutableStateOf(template?.optString("name").orEmpty()) }
    var body by remember(template) { mutableStateOf(template?.optString("body").orEmpty()) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Paper, shape = ControlShape, modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(.93f).heightIn(max = 680.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("API 与模板", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Control("×", "关闭设置", action = close)
                }
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模型服务", style = MaterialTheme.typography.titleMedium)
                        Text("选好服务商，填入密钥，就可以开始对话。", color = Muted, fontSize = 13.sp)
                    }
                    if (active != null) Text("● 已配置", color = Green, fontSize = 12.sp)
                }
                Surface(border = BorderStroke(1.dp, Line), shape = ControlShape, color = Paper) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.width(184.dp).background(PaleGreen).padding(7.dp)) {
                            model.providers.forEach { item ->
                                val selected = item.getString("id") == form.provider
                                TextButton(onClick = {
                                    val id = item.getString("id")
                                    form = (model.profile(id) ?: model.defaults(id)).copy(key = "")
                                    status = ""
                                    advanced = id == "custom"
                                }, modifier = Modifier.fillMaxWidth(), shape = ControlShape,
                                    colors = ButtonDefaults.textButtonColors(containerColor = if (selected) Bar else PaleGreen),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text(item.getString("shortName"), color = Green, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.background(Bar, ControlShape).padding(horizontal = 8.dp, vertical = 4.dp))
                                    Text(item.getString("name"), color = if (selected) Green else Muted, fontSize = 13.sp,
                                        modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(provider.getString("name"), style = MaterialTheme.typography.titleMedium)
                            provider.optString("hint").takeIf(String::isNotBlank)?.let {
                                Text(it, color = Muted, fontSize = 12.sp)
                            }
                            provider.optString("docsURL").takeIf(String::isNotBlank)?.let { link ->
                                Control("服务商文档") { uriHandler.openUri(link) }
                            }
                            OutlinedTextField(form.key, { form = form.copy(key = it) }, label = { Text("API Key") },
                                placeholder = { Text(if (configured?.key.isNullOrBlank()) "输入服务商 API Key" else "已保存，留空沿用") },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors())
                            OutlinedTextField(form.model, { form = form.copy(model = it) }, label = { Text("模型名称") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors())
                            val names = provider.getJSONArray("models")
                            if (names.length() > 0) androidx.compose.foundation.lazy.LazyRow {
                                items(names.length()) { index ->
                                    val name = names.getString(index)
                                    Control(name, active = form.model == name) { form = form.copy(model = name) }
                                }
                            }
                            if (advanced || form.provider == "custom") OutlinedTextField(form.url, { form = form.copy(url = it) },
                                label = { Text("服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors())
                            else Column(Modifier.fillMaxWidth().background(PaleGreen, ControlShape).padding(12.dp)) {
                                Text("接口地址", color = Muted, fontSize = 12.sp)
                                Text(form.url, color = Green, fontSize = 12.sp)
                            }
                            val endpoints = provider.optJSONArray("alternateURLs").objects()
                            if (endpoints.isNotEmpty()) {
                                val default = provider.getString("baseURL")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Control("默认地址", active = form.url == default) { form = form.copy(url = default) }
                                    endpoints.forEach { endpoint ->
                                        val url = endpoint.getString("url")
                                        Control(endpoint.getString("name"), active = form.url == url) { form = form.copy(url = url) }
                                    }
                                }
                            }
                            Control(if (advanced) "高级设置 ▴" else "高级设置 ▾", "高级设置") { advanced = !advanced }
                            if (advanced) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Control(form.protocol + " ▾", "API 协议") { protocol = true }
                                    DropdownMenu(expanded = protocol, onDismissRequest = { protocol = false }, shape = ControlShape,
                                        containerColor = Paper, tonalElevation = 0.dp) {
                                        listOf("responses", "chat-completions", "anthropic").forEach {
                                            DropdownMenuItem(text = { Text(it) }, onClick = { form = form.copy(protocol = it); protocol = false })
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(form.images, { form = form.copy(images = it) })
                                Text("此模型支持图片输入")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(shape = ControlShape, enabled = !checking, onClick = {
                                    val requested = form
                                    checking = true
                                    status = "正在测试连接…"
                                    scope.launch {
                                        try {
                                            model.stream(model.validated(requested), "请只回答 OK。", emptyList(), emptyList(), true).collect()
                                            status = "测试通过。"
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (error: Exception) { status = error.localizedMessage ?: "连接测试失败。" }
                                        finally { checking = false }
                                    }
                                }) { Text("测试连接") }
                                Button(shape = ControlShape, enabled = !checking, onClick = {
                                    val requested = form
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { model.save(requested) }
                                            onChange()
                                            form = (model.active() ?: requested).copy(key = "")
                                            status = "已保存并使用。"
                                        } catch (error: Exception) { status = error.localizedMessage ?: "无法保存模型配置。" }
                                    }
                                }) { Text("保存并使用") }
                                if (configured != null) Control("移除配置") {
                                    val id = form.provider
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { model.remove(id) } }.onSuccess {
                                            onChange()
                                            form = model.defaults(id)
                                            status = "已移除配置。"
                                        }.onFailure { status = "无法移除配置。" }
                                    }
                                }
                            }
                            if (status.isNotBlank()) Text(status, color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 10.dp))
                Text("本机存储", style = MaterialTheme.typography.titleMedium)
                if (storage != null) {
                    val size = android.text.format.Formatter.formatFileSize(context, storage.bytes)
                    Text("${storage.documents} 份资料 · ${storage.notes} 条便签 · $size", color = Muted, fontSize = 13.sp)
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 10.dp))
                Text("提示词模板", style = MaterialTheme.typography.titleMedium)
                if (template == null) Text("打开一份 PDF 后编辑模板。模板随项目保存与导出。", color = Muted)
                else {
                    androidx.compose.foundation.lazy.LazyRow { items(templates.size) { index ->
                        val item = templates[index]
                        Control(item.getString("name"), active = item.getString("id") == templateId) { templateId = item.getString("id") }
                    } }
                    OutlinedTextField(title, { title = it }, label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("模板内容") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val id = template.getString("id")
                        Button(enabled = title.isNotBlank() && body.isNotBlank(),
                            onClick = { saveTemplate(id, title, body, null) }, shape = ControlShape) { Text("保存模板") }
                        Control("另存为模板", enabled = title.isNotBlank() && body.isNotBlank()) {
                            val copy = UUID.randomUUID().toString()
                            saveTemplate(id, "$title · 副本", body, copy)
                            templateId = copy
                        }
                        Control("恢复默认内容") {
                            body = model.defaultTemplate(template.getString("task")).getString("body")
                            saveTemplate(id, title, body, null)
                        }
                    }
                    Text("支持 {{task}}、{{selected_text}}、{{question}}、{{context_with_source_ids}} 等变量。",
                        color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Green, unfocusedBorderColor = Line,
    focusedContainerColor = Bar, unfocusedContainerColor = Bar,
)
