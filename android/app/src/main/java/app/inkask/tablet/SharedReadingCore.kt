package app.inkask.tablet

import android.content.Context
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject

internal class SharedReadingCore(context: Context) {
    private val script by lazy {
        QuickJSLoader.init()
        context.assets.open("reading-core.js").bufferedReader().use { it.readText() }
    }

    fun context(project: JSONObject, note: JSONObject, question: String, template: JSONObject): JSONObject =
        JSONObject(run("context", JSONObject().put("project", project).put("note", note)
            .put("question", question).put("template", template)))

    fun branch(project: JSONObject, parentId: String, messageId: String, anchor: JSONObject,
               template: JSONObject, id: String, createdAt: Long): JSONObject =
        JSONObject(run("branch", JSONObject().put("project", project).put("parentId", parentId)
            .put("messageId", messageId).put("anchor", anchor).put("template", template)
            .put("id", id).put("createdAt", createdAt)))

    fun citations(raw: String, sources: List<JSONObject>): JSONObject =
        JSONObject(run("citations", JSONObject().put("raw", raw).put("sources", JSONArray(sources))))

    fun preview(source: JSONObject, page: JSONObject?, anchor: JSONObject?, claim: String): JSONObject =
        JSONObject(run("preview", JSONObject().put("source", source).put("page", page)
            .put("anchor", anchor).put("claim", claim)))

    fun notes(project: JSONObject): JSONObject = JSONObject(run("notes", project))

    fun markdown(project: JSONObject): String = run("markdown", project)

    fun moveLibrary(items: List<LibraryItem>, folders: List<JSONObject>, membership: Map<String, String>,
                    target: String?): List<LibraryItem> {
        val request = JSONObject().put("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("kind", if (it.folder) "folder" else "document")
        })).put("folders", JSONArray(folders)).put("membership", JSONObject(membership)).put("target", target)
        return JSONArray(run("moveLibrary", request)).objects().map {
            LibraryItem(it.getString("id"), it.getString("kind") == "folder")
        }
    }

    private fun run(operation: String, input: JSONObject): String {
        val code = script
        val engine = QuickJSContext.create()
        try {
            engine.evaluate(code)
            val result = engine.evaluate("InkAskCore.$operation(${JSONObject.quote(input.toString())})")
            return result as? String ?: error("无法处理阅读数据。")
        } finally {
            engine.destroy()
        }
    }
}
