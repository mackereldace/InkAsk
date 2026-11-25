package app.inkask.tablet

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class PdfItem(val id: String, val name: String, val pageCount: Int, val updatedAt: Long)
internal data class Folder(val id: String, val name: String, val parentId: String?)
internal data class Library(val documents: List<PdfItem>, val folders: List<Folder>, val membership: Map<String, String>)
internal data class LibraryItem(val id: String, val folder: Boolean)
internal data class LocalStorage(val documents: Int, val notes: Int, val bytes: Long)
internal data class QuestionImage(val name: String, val bytes: ByteArray, val source: JSONObject? = null)

internal class Workspace(private val context: Context) {
    val core = SharedReadingCore(context)
    private val root = File(context.filesDir, "inkask-native").apply { mkdirs() }
    private val projects = File(root, "projects").apply { mkdirs() }
    private val index = AtomicFile(File(root, "workspace.json"))
    private val defaultTemplates by lazy {
        JSONObject(context.assets.open("reading-config.json").bufferedReader().use { it.readText() }).getJSONArray("templates")
    }

    @Synchronized
    fun library(): Library {
        val json = read(index)
        val folders = json.optJSONArray("folders").objects().map {
            Folder(it.getString("id"), it.getString("name"), it.optString("parentId").ifEmpty { null })
        }
        val membership = json.optJSONArray("memberships").objects().associate {
            it.getString("projectId") to it.getString("folderId")
        }
        val documents = projects.listFiles().orEmpty().mapNotNull { dir ->
            val document = read(AtomicFile(File(dir, "project.json"))).optJSONObject("document")
            document?.toItem()
        }.sortedByDescending(PdfItem::updatedAt)
        return Library(documents, folders, membership)
    }

    @Synchronized
    fun storage(): LocalStorage {
        val documents = library().documents
        val notes = documents.sumOf { project(it.id).optJSONArray("notes")?.length() ?: 0 }
        return LocalStorage(documents.size, notes, root.walkTopDown().filter(File::isFile).sumOf(File::length))
    }

    @Synchronized
    fun createFolder(name: String, parentId: String?) {
        val label = name.trim().take(120)
        require(label.isNotEmpty()) { "请输入文件夹名称。" }
        val json = read(index)
        val old = json.optJSONArray("folders") ?: JSONArray()
        check(old.objects().none { it.folderParent() == parentId && it.getString("name").equals(label, true) }) {
            "该位置已存在同名文件夹。"
        }
        val folder = JSONObject().put("id", UUID.randomUUID().toString()).put("name", label)
            .put("createdAt", System.currentTimeMillis())
        if (parentId != null) folder.put("parentId", parentId)
        write(index, json.put("folders", old.put(folder)).put("version", 1))
    }

    @Synchronized
    fun renameFolder(id: String, name: String) {
        val label = name.trim().take(120)
        require(label.isNotEmpty()) { "请输入文件夹名称。" }
        val json = read(index)
        val folders = json.optJSONArray("folders").objects()
        val folder = folders.firstOrNull { it.getString("id") == id } ?: error("文件夹已不存在。")
        check(folders.none { it !== folder && it.folderParent() == folder.folderParent() && it.getString("name").equals(label, true) }) {
            "该位置已存在同名文件夹。"
        }
        folder.put("name", label)
        write(index, json)
    }

    @Synchronized
    fun removeFolder(id: String) {
        val json = read(index)
        val folders = json.optJSONArray("folders").objects()
        val removed = folders.firstOrNull { it.getString("id") == id } ?: return
        val parent = removed.folderParent()
        val children = folders.filter { it.folderParent() == id }
        val labelConflict = children.any { child ->
            folders.any { it !== removed && it !== child && it.folderParent() == parent && it.getString("name").equals(child.getString("name"), true) }
        }
        check(!labelConflict) { "上一级存在同名文件夹，请先重命名。" }
        children.forEach { if (parent == null) it.remove("parentId") else it.put("parentId", parent) }
        val memberships = json.optJSONArray("memberships").objects().mapNotNull {
            if (it.getString("folderId") != id) it else parent?.let { target -> it.put("folderId", target) }
        }
        write(index, json.put("folders", JSONArray(folders.filter { it !== removed })).put("memberships", JSONArray(memberships)))
    }

    @Synchronized
    fun moveItems(items: Set<LibraryItem>, target: String?) {
        val json = read(index)
        val folders = json.optJSONArray("folders").objects().associateBy { it.getString("id") }
        val membership = json.optJSONArray("memberships").objects().associate {
            it.getString("projectId") to it.getString("folderId")
        }.toMutableMap()
        val existing = items.filter { if (it.folder) it.id in folders else pdfFile(it.id).isFile }
        val top = core.moveLibrary(existing, folders.values.toList(), membership, target)
        val movingFolders = top.filter { it.folder }.map { folders.getValue(it.id) }
        val names = folders.values.filter { it.folderParent() == target && it !in movingFolders }
            .map { it.getString("name").lowercase() }.toMutableSet()
        for (folder in movingFolders) {
            val name = folder.getString("name")
            require(names.add(name.lowercase())) { "目标目录已存在同名文件夹「$name」，请先重命名。" }
            folder.put("parentId", target)
        }
        for (item in top.filterNot { it.folder }) {
            if (target == null) membership.remove(item.id) else membership[item.id] = target
        }
        val records = membership.map { (id, folder) -> JSONObject().put("projectId", id).put("folderId", folder) }
        write(index, json.put("folders", JSONArray(folders.values)).put("memberships", JSONArray(records)).put("version", 1))
    }

    @Synchronized
    fun move(ids: Set<String>, folder: String?) {
        val json = read(index)
        val old = json.optJSONArray("memberships").objects().filterNot { it.getString("projectId") in ids }
        val memberships = JSONArray(old)
        if (folder != null) ids.forEach {
            memberships.put(JSONObject().put("projectId", it).put("folderId", folder))
        }
        write(index, json.put("memberships", memberships).put("version", 1))
    }

    @Synchronized
    fun remove(ids: Set<String>) {
        ids.forEach { projectDir(it).deleteRecursively() }
        move(ids, null)
    }

    fun pdfFile(id: String) = File(projectDir(id), "document.pdf")
    private fun projectDir(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}")) && id != "." && id != "..")
        return File(projects, id)
    }

    fun project(id: String): JSONObject = read(AtomicFile(File(projectDir(id), "project.json"))).also {
        check(it.has("document")) { "资料不存在。" }
    }

    @Synchronized
    fun update(id: String, change: (JSONObject) -> Unit): JSONObject {
        val project = project(id)
        change(project)
        val document = project.getJSONObject("document")
        document.put("updatedAt", maxOf(System.currentTimeMillis(), document.optLong("updatedAt") + 1))
        write(AtomicFile(File(projectDir(id), "project.json")), project)
        return project
    }

    @Synchronized
    fun updateWithImages(id: String, images: List<QuestionImage>, skip: ((JSONObject) -> Boolean)? = null,
                         change: (JSONObject, List<String>) -> Unit): JSONObject {
        if (skip != null) {
            val current = project(id)
            if (skip(current)) return current
        }
        val written = mutableListOf<File>()
        return try {
            update(id) { project ->
                val assets = project.getJSONArray("assets")
                require(assets.length() + images.size <= 2_000) { "项目附件数量超过限制。" }
                val directory = File(projectDir(id), "assets")
                if (images.isNotEmpty()) directory.mkdirs()
                val ids = images.map { image ->
                    val key = assets.length().toString()
                    val file = File(directory, key)
                    written.add(file)
                    file.writeBytes(image.bytes)
                    val assetId = UUID.randomUUID().toString()
                    val asset = JSONObject().put("id", assetId).put("name", image.name.take(180))
                        .put("mime", "image/png").put("localAsset", key)
                    image.source?.let { asset.put("source", it) }
                    assets.put(asset)
                    assetId
                }
                change(project, ids)
            }
        } catch (error: Exception) {
            written.forEach { it.delete() }
            throw error
        }
    }

    fun assetFile(id: String, asset: JSONObject): File {
        val key = asset.getString("localAsset")
        require(key.matches(Regex("[0-9]{1,4}")))
        return File(projectDir(id), "assets/$key")
    }

    fun removeNote(id: String, noteId: String): JSONObject = update(id) { project ->
        val notes = project.getJSONArray("notes").objects()
        val children = notes.groupBy { it.optString("parentId") }
        val delete = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(noteId) }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (delete.add(next)) children[next].orEmpty().forEach { pending.add(it.getString("id")) }
        }
        project.put("notes", JSONArray(notes.filterNot { it.getString("id") in delete }))
    }

    fun inputName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "未命名.pdf"
        }
        return "未命名.pdf"
    }

    fun readImage(uri: Uri): Pair<String, ByteArray> {
        val originalName = inputName(uri).substringBeforeLast('.')
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val factor = minOf(1f, 1280f / maxOf(width, height))
            decoder.setTargetSize((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
        }
        try {
            return "$originalName.png" to ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法读取图片。" }
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    fun import(uri: Uri, folder: String?): PdfItem {
        val name = inputName(uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开文件。")
        return input.use {
            if (name.endsWith(".inkask", ignoreCase = true)) importArchive(it, folder)
            else addPdf(name, it, folder)
        }
    }

    @Synchronized
    fun sample(folder: String?): PdfItem {
        library().documents.firstOrNull { it.id == "inkask-sample" }?.let { return it }
        return context.assets.open("sample.pdf").use { addPdf("理解导数 · InkAsk 示例.pdf", it, folder, "inkask-sample") }
    }

    @Synchronized
    private fun addPdf(name: String, input: InputStream, folder: String?, id: String = UUID.randomUUID().toString()): PdfItem {
        val stage = stage()
        try {
            val file = File(stage, "document.pdf")
            file.outputStream().use { input.copyLimited(it, MAX_PDF) }
            val now = System.currentTimeMillis()
            val document = JSONObject().put("id", id).put("name", name.take(240))
                .put("fingerprint", file.sha256()).put("pageCount", verifyPdf(file))
                .put("createdAt", now).put("updatedAt", now)
            val project = JSONObject().put("version", 1).put("document", document)
                .put("pages", JSONArray()).put("notes", JSONArray()).put("assets", JSONArray())
                .put("templates", defaultTemplates)
                .put("view", JSONObject().put("page", 1).put("scale", 1).put("rotation", 0).put("direction", "vertical"))
            return commit(stage, id, project, folder)
        } finally {
            stage.deleteRecursively()
        }
    }

    @Synchronized
    private fun importArchive(input: InputStream, folder: String?): PdfItem {
        val uploaded = File.createTempFile("inkask-import-", ".zip", context.cacheDir)
        val stage = stage()
        try {
            uploaded.outputStream().use { input.copyLimited(it, MAX_ARCHIVE) }
            ZipFile(uploaded).use { zip ->
                fun copy(name: String, output: OutputStream, limit: Long): Long {
                    val entry = zip.getEntry(name) ?: error("项目缺少文件。")
                    require(!entry.isDirectory && entry.size in 0..limit) { "项目文件超过限制。" }
                    return zip.getInputStream(entry).use { it.copyLimited(output, limit) }
                }
                val manifest = java.io.ByteArrayOutputStream()
                copy("manifest.json", manifest, MAX_METADATA)
                val parsed = JSONObject(manifest.toString(Charsets.UTF_8.name()))
                require(parsed.getString("format") == "inkask" && parsed.getInt("formatVersion") == 1) { "项目版本不受支持。" }
                val project = parsed.getJSONObject("project")
                require(project.getInt("version") == 1) { "项目版本不受支持。" }
                val document = project.getJSONObject("document")
                require(document.getString("name").isNotBlank()) { "项目缺少文档名。" }
                val pdf = File(stage, "document.pdf")
                var total = pdf.outputStream().use { copy(document.getString("pdfPath"), it, MAX_PDF) }
                val id = UUID.randomUUID().toString()
                document.remove("pdfPath")
                val count = verifyPdf(pdf)
                document.put("id", id).put("pageCount", count).put("updatedAt", System.currentTimeMillis())
                project.getJSONObject("view")
                project.getJSONArray("pages").objects().forEach { require(it.getInt("page") in 1..count) }
                project.getJSONArray("templates").objects().forEach {
                    it.getString("id"); it.getString("task"); it.getString("name"); it.getString("body")
                }
                val notes = project.getJSONArray("notes").objects()
                val noteIds = HashSet<String>()
                notes.forEach { note ->
                    require(noteIds.add(note.getString("id"))) { "项目便签重复。" }
                    val anchor = note.getJSONObject("anchor")
                    require(anchor.getInt("page") in 1..count) { "便签页码超出范围。" }
                    anchor.getJSONArray("rects")
                    note.getJSONArray("messages").objects().forEach { message ->
                        require(message.getString("role") in listOf("user", "assistant")) { "项目包含无效消息。" }
                        if (message.optString("status") == "streaming") message.put("status", "stopped")
                        message.optJSONArray("sources")?.objects()?.forEach { source ->
                            require(source.getInt("page") in 1..count)
                        }
                    }
                }
                val assets = project.optJSONArray("assets").objects()
                require(assets.size <= 2_000) { "项目附件数量超过限制。" }
                if (assets.isNotEmpty()) File(stage, "assets").mkdir()
                assets.forEachIndexed { position, asset ->
                    val archivePath = asset.getString("archivePath")
                    require(archivePath.startsWith("assets/") && !archivePath.split('/').contains("..")) { "项目附件路径无效。" }
                    require(asset.getString("mime").startsWith("image/")) { "项目附件必须是图片。" }
                    total += File(stage, "assets/$position").outputStream()
                        .use { copy(archivePath, it, minOf(MAX_ASSET, MAX_EXTRACTED - total)) }
                    asset.remove("archivePath")
                    asset.put("localAsset", position.toString())
                }
                return commit(stage, id, project, folder)
            }
        } finally {
            uploaded.delete()
            stage.deleteRecursively()
        }
    }

    private fun commit(stage: File, id: String, project: JSONObject, folder: String?): PdfItem {
        write(AtomicFile(File(stage, "project.json")), project)
        val destination = projectDir(id)
        check(!destination.exists() && stage.renameTo(destination)) { "无法保存 PDF 到资料库。" }
        if (folder != null) move(setOf(id), folder)
        return project.getJSONObject("document").toItem()
    }

    fun exportArchive(id: String, uri: Uri) {
        val project = project(id)
        if (project.getJSONArray("templates").length() == 0) project.put("templates", defaultTemplates)
        val document = project.getJSONObject("document").put("pdfPath", "document.pdf")
        require(document.getInt("pageCount") > 0)
        val destination = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入项目。")
        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry("document.pdf"))
            pdfFile(id).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            project.optJSONArray("assets").objects().forEachIndexed { position, asset ->
                val key = asset.getString("localAsset")
                require(key.matches(Regex("[0-9]{1,4}")))
                val name = asset.optString("name").replace(Regex("[^a-zA-Z0-9._-]"), "-").take(80)
                val path = "assets/$position-${name.ifEmpty { "asset" }}"
                zip.putNextEntry(ZipEntry(path))
                File(projectDir(id), "assets/$key").inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                asset.remove("localAsset")
                asset.put("archivePath", path)
            }
            val manifest = JSONObject().put("format", "inkask").put("formatVersion", 1).put("project", project)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun exportNotes(id: String, uri: Uri, directory: Boolean) {
        val project = project(id)
        if (!directory) {
            val markdown = core.markdown(project)
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入导出文件。")
            output.bufferedWriter().use { it.write(markdown) }
            return
        }
        val plan = core.notes(project)
        val root = plan.getString("root")
        val assets = project.getJSONArray("assets").objects().associateBy { it.getString("id") }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入导出文件。")
        ZipOutputStream(output).use { zip ->
            for (file in plan.getJSONArray("files").objects()) {
                zip.putNextEntry(ZipEntry("$root/${file.getString("path")}"))
                zip.write(file.getString("text").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("$root/document.pdf"))
            pdfFile(id).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            for (entry in plan.getJSONArray("assets").objects()) {
                zip.putNextEntry(ZipEntry("$root/${entry.getString("path")}"))
                assetFile(id, assets.getValue(entry.getString("id"))).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun stage() = File.createTempFile("import-", ".tmp", root).also {
        check(it.delete() && it.mkdir()) { "无法创建本机暂存目录。" }
    }

    private fun verifyPdf(file: File): Int {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
        return renderer.use {
            it.pageCount.also { count -> require(count > 0) { "PDF 没有可读取页面。" } }
        }
    }

    companion object {
        private const val MAX_PDF = 100L * 1024 * 1024
        private const val MAX_ARCHIVE = 128L * 1024 * 1024
        private const val MAX_ASSET = 64L * 1024 * 1024
        private const val MAX_METADATA = 64L * 1024 * 1024
        private const val MAX_EXTRACTED = 512L * 1024 * 1024
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { getString(it) }

private fun JSONObject.toItem() =
    PdfItem(getString("id"), getString("name"), getInt("pageCount"), getLong("updatedAt"))

private fun JSONObject.folderParent() = optString("parentId").ifEmpty { null }

private fun read(file: AtomicFile): JSONObject =
    try { JSONObject(file.readFully().toString(Charsets.UTF_8)) }
    catch (_: java.io.FileNotFoundException) { JSONObject() }

private fun write(file: AtomicFile, value: JSONObject) {
    val output = file.startWrite()
    try {
        output.write(value.toString().toByteArray(Charsets.UTF_8))
        file.finishWrite(output)
    } catch (error: Exception) {
        file.failWrite(output)
        throw error
    }
}

private fun InputStream.copyLimited(output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val size = read(buffer)
        if (size == -1) return total
        total += size
        require(total <= limit) { "所选文件超过大小限制。" }
        output.write(buffer, 0, size)
    }
}

private fun File.sha256(): String {
    val hash = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = stream.read(buffer)
            if (size == -1) break
            hash.update(buffer, 0, size)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
