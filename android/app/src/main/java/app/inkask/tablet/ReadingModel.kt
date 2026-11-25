package app.inkask.tablet

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ModelProfile(
    val provider: String,
    val url: String,
    val model: String,
    val protocol: String,
    val key: String,
    val images: Boolean,
)

internal data class InputImage(val mime: String, val bytes: ByteArray) {
    fun base64() = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun dataUrl() = "data:$mime;base64,${base64()}"
}
internal data class Turn(val role: String, val content: String, val images: List<InputImage> = emptyList())

internal class ReadingModel(context: Context) {
    private val configuration = JSONObject(context.assets.open("reading-config.json").bufferedReader().use { it.readText() })
    val providers = configuration.getJSONArray("providers").objects()
    private val defaults = configuration.getJSONArray("templates").objects()
    private val systemPrompt = configuration.getString("systemPrompt")
    private val prefs = context.getSharedPreferences("inkask-providers", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        .build()

    fun active() = profile(prefs.getString("active", "") ?: "")

    fun profile(id: String): ModelProfile? {
        val value = prefs.getString("provider:$id", null)?.let(::JSONObject) ?: return null
        return ModelProfile(id, value.getString("url"), value.getString("model"), value.getString("protocol"),
            value.getString("key"), value.getBoolean("images"))
    }

    fun defaults(id: String): ModelProfile {
        val provider = providers.first { it.getString("id") == id }
        return ModelProfile(id, provider.getString("baseURL"), provider.getJSONArray("models").optString(0),
            provider.getString("protocol"), "", provider.getBoolean("supportsImages"))
    }

    fun validated(profile: ModelProfile): ModelProfile {
        val provider = providers.first { it.getString("id") == profile.provider }
        val url = URI(profile.url.trim())
        val host = url.host?.lowercase()
        require(host != null && (url.scheme == "https" || (url.scheme == "http" && host in setOf("localhost", "127.0.0.1")))) {
            "服务地址必须使用 HTTPS 或本机回环 HTTP。"
        }
        require(url.userInfo == null && url.query == null && url.fragment == null) { "服务地址不能包含账号、查询参数或片段。" }
        require(profile.model.isNotBlank() && profile.model.length <= 256) { "请输入有效的模型名称。" }
        require(profile.protocol in setOf("responses", "chat-completions", "anthropic")) { "API 协议不受支持。" }
        val previous = profile(profile.provider)
        val endpoint = url.toString().trimEnd('/').let { address ->
            listOf("/chat/completions", "/responses", "/messages").firstOrNull(address::endsWith)?.let(address::removeSuffix) ?: address
        }
        val key = profile.key.trim().ifEmpty { previous?.key?.takeIf { previous.url == endpoint }.orEmpty() }
        require(key.length <= 16384 && key.none { it.isISOControl() }) { "API Key 格式无效。" }
        require(key.isNotBlank() || !provider.getBoolean("requiresKey")) { "请输入 API Key。" }
        return profile.copy(url = endpoint, model = profile.model.trim(), key = key)
    }

    fun save(profile: ModelProfile) {
        val value = validated(profile)
        val json = JSONObject().put("url", value.url).put("model", value.model)
            .put("protocol", value.protocol).put("key", value.key).put("images", value.images)
        check(prefs.edit().putString("provider:${value.provider}", json.toString()).putString("active", value.provider).commit()) {
            "无法保存模型配置。"
        }
    }

    fun remove(id: String) {
        val edit = prefs.edit().remove("provider:$id")
        if (prefs.getString("active", "") == id) edit.remove("active")
        check(edit.commit()) { "无法删除模型配置。" }
    }

    fun templates(project: JSONObject): List<JSONObject> =
        project.optJSONArray("templates").objects().ifEmpty { defaults }

    fun template(project: JSONObject, task: String, id: String? = null): JSONObject {
        val templates = templates(project)
        return templates.firstOrNull { it.optString("id") == id }
            ?: templates.firstOrNull { it.optString("task") == task }
            ?: defaults.first { it.getString("task") == task }
    }

    fun taskLabel(task: String): String = configuration.getJSONObject("taskLabels").getString(task)

    fun defaultTemplate(task: String): JSONObject = defaults.first { it.getString("task") == task }

    fun stream(profile: ModelProfile, prompt: String, history: List<Turn>, images: List<InputImage>, probe: Boolean = false): Flow<String> = callbackFlow {
        val path = when (profile.protocol) {
            "responses" -> "/responses"
            "anthropic" -> "/messages"
            else -> "/chat/completions"
        }
        require(profile.images || (images.isEmpty() && history.none { it.images.isNotEmpty() })) {
            "当前模型未开启图片输入支持。"
        }
        val instruction = if (probe || prompt.startsWith(systemPrompt)) prompt else "$systemPrompt\n\n$prompt"
        val body = JSONObject().put("model", profile.model).put("stream", true)
        val turns = history + Turn("user", instruction, images)
        val messages = JSONArray(turns.map { turn ->
            val content = JSONArray()
            val textType = when {
                profile.protocol != "responses" -> "text"
                turn.role == "assistant" -> "output_text"
                else -> "input_text"
            }
            if (turn.content.isNotBlank()) content.put(JSONObject().put("type", textType).put("text", turn.content))
            turn.images.forEach { image ->
                val part = when (profile.protocol) {
                    "responses" -> JSONObject().put("type", "input_image").put("image_url", image.dataUrl())
                    "anthropic" -> JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", image.mime).put("data", image.base64()))
                    else -> JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image.dataUrl()))
                }
                content.put(part)
            }
            val payload = if (turn.images.isEmpty() && profile.protocol != "responses") turn.content else content
            JSONObject().put("role", turn.role).put("content", payload)
        })
        when (profile.protocol) {
            "responses" -> {
                body.put("store", false).put("input", messages)
                if (probe) body.put("max_output_tokens", 1024)
            }
            "anthropic" -> body.put("max_tokens", if (probe) 1024 else 8192).put("messages", messages)
            else -> {
                body.put("messages", messages)
                if (probe) body.put("max_tokens", 1024)
            }
        }
        val request = Request.Builder().url(profile.url + path).header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (profile.protocol == "anthropic") request.header("anthropic-version", "2023-06-01")
        if (profile.key.isNotBlank()) {
            if (profile.protocol == "anthropic") request.header("x-api-key", profile.key)
            else request.header("Authorization", "Bearer ${profile.key}")
        }
        val call = client.newCall(request.build())
        if (probe) call.timeout().timeout(30, TimeUnit.SECONDS)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(IOException("AI 服务连接失败或超时。")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw IOException(when (response.code) {
                            401, 403 -> "AI 服务鉴权失败，请检查服务配置。"
                            429 -> "AI 服务请求过于频繁，请稍后重试。"
                            else -> "AI 服务返回错误（HTTP ${response.code}）。"
                        })
                        val source = response.body.source()
                        val data = StringBuilder()
                        var complete = false
                        var output = 0
                        fun consume() {
                            if (data.isEmpty()) return
                            val text = data.toString()
                            data.clear()
                            if (text == "[DONE]") { complete = true; return }
                            val event = JSONObject(text)
                            val type = event.optString("type")
                            if (event.optJSONObject("error") != null || type in setOf("response.failed", "response.incomplete", "error"))
                                throw IOException("AI 服务未完成回答。")
                            val delta = when (profile.protocol) {
                                "responses" -> {
                                    if (type == "response.completed") complete = true
                                    if (type == "response.output_text.delta") event.optString("delta") else ""
                                }
                                "anthropic" -> {
                                    if (type == "message_stop") complete = true
                                    if (type == "content_block_delta") event.optJSONObject("delta")?.optString("text").orEmpty() else ""
                                }
                                else -> {
                                    val choice = event.optJSONArray("choices")?.optJSONObject(0)
                                    if (choice != null && !choice.isNull("finish_reason")) complete = true
                                    choice?.optJSONObject("delta")?.optString("content")?.takeUnless { it == "null" }.orEmpty()
                                }
                            }
                            output += delta.length
                            require(output <= 500_000) { "回答超过可保存的长度。" }
                            if (delta.isNotEmpty()) trySendBlocking(delta)
                        }
                        while (!source.exhausted() && !complete && !call.isCanceled()) {
                            val line = source.readUtf8LineStrict(1_048_576)
                            if (line.isEmpty()) consume()
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter(':').trimStart())
                                require(data.length <= 1_048_576)
                            }
                        }
                        consume()
                        if (!complete && !call.isCanceled()) throw IOException("连接提前结束，已保留部分回答。")
                        close()
                    } catch (error: Exception) {
                        close(IOException(error.message?.takeIf { error is IOException && it.contains(Regex("[\u4e00-\u9fa5]")) }
                            ?: "AI 响应无法读取，已保留部分回答。"))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }
}
