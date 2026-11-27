package app.inkask.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ReaderSidebarNote(val id: String, val title: String, val page: Int)
internal data class ReaderSidebarContent(val page: Int, val notes: List<ReaderSidebarNote>, val activeNote: String?)

internal data class ReaderSidebarActions(
    val content: ReaderSidebarContent,
    val openNote: (String) -> Unit,
    val splitNote: (ReaderSidebarNote) -> Unit,
    val splitDocument: (PdfItem) -> Unit,
)

@Composable
internal fun ReaderSidebar(
    visible: Boolean, library: Library, activeDocument: String, history: ReaderHistory, busy: Boolean,
    onDismiss: () -> Unit, onDocument: (PdfItem) -> Unit, reader: ReaderSidebarActions,
    dismissOnOutside: Boolean = true,
) {
    var notesTab by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    SidebarFrame(visible, "阅读侧栏", onDismiss.takeIf { dismissOnOutside }) {
        SidebarBrand(onHome = { notesTab = false; query = "" }, onDismiss = onDismiss)
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SidebarTab("资料库", !notesTab, Modifier.weight(1f)) { notesTab = false }
            SidebarTab("本书追问", notesTab, Modifier.weight(1f)) { notesTab = true }
        }
        if (notesTab) key(activeDocument) { SidebarNotes(reader, busy) }
        else SidebarDocuments(library, activeDocument, history, busy, query, { query = it }, onDocument, reader.splitDocument)
    }
}

private data class SidebarTreeRow(val folder: Folder?, val file: PdfItem?, val depth: Int)

@Composable
private fun ColumnScope.SidebarDocuments(
    library: Library, activeDocument: String, history: ReaderHistory, busy: Boolean,
    query: String, onSearch: (String) -> Unit, open: (PdfItem) -> Unit, split: (PdfItem) -> Unit,
) {
    val folders = remember(library.folders) { library.folders.associateBy(Folder::id) }
    val activePath = remember(folders, library.membership, activeDocument) {
        generateSequence(library.membership[activeDocument]?.let(folders::get)) { folders[it.parentId] }
            .take(folders.size).map(Folder::id).toSet()
    }
    var expanded by remember { mutableStateOf(activePath) }
    LaunchedEffect(activeDocument, activePath) { expanded = expanded + activePath }
    val recent = history.documents
    val tree = history.showTree
    val term = query.trim()
    val rows = remember(library, expanded, tree, recent, term) {
        val files = library.documents.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, PdfItem::name))
        if (!tree || term.isNotEmpty()) {
            val byId = files.associateBy(PdfItem::id)
            val opened = recent.toSet()
            (recent.mapNotNull(byId::get) + files.filter { it.id !in opened })
                .filter { it.name.contains(term, ignoreCase = true) }.map { SidebarTreeRow(null, it, 0) }
        } else {
            val subfolders = library.folders.groupBy { it.parentId?.takeIf(folders::containsKey) }
            val children = files.groupBy { library.membership[it.id]?.takeIf(folders::containsKey) }
            buildList {
                fun append(parent: String?, depth: Int) {
                    subfolders[parent].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Folder::name)).forEach { folder ->
                        add(SidebarTreeRow(folder, null, depth))
                        if (folder.id in expanded) append(folder.id, depth + 1)
                    }
                    children[parent].orEmpty().forEach { add(SidebarTreeRow(null, it, depth)) }
                }
                append(null, 0)
            }
        }
    }
    SidebarSearch(query, onSearch)
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SidebarTab("文件树", tree, Modifier.weight(1f)) { history.setTree(true) }
        SidebarTab("最近打开", !tree, Modifier.weight(1f)) { history.setTree(false) }
    }
    if (rows.isEmpty()) {
        Text(if (term.isEmpty()) "资料库还没有 PDF" else "没有找到资料",
            Modifier.padding(horizontal = 9.dp, vertical = 12.dp), color = Muted, fontSize = 13.sp)
    }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 6.dp)) {
        items(rows, key = { row -> row.folder?.let { "folder-${it.id}" } ?: "pdf-${checkNotNull(row.file).id}" }) { row ->
            val folder = row.folder
            if (folder != null) {
                val openFolder = folder.id in expanded
                Row(
                    Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(4) * 9).dp)
                        .clip(SidebarRowShape).clickable {
                            expanded = if (openFolder) expanded - folder.id else expanded + folder.id
                        }.semantics { stateDescription = if (openFolder) "已展开" else "已折叠" }
                        .heightIn(min = 40.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.inkask_ic_chevron_right), null,
                        Modifier.size(13.dp).rotate(if (openFolder) 90f else 0f), tint = Muted)
                    SidebarIcon(R.drawable.inkask_ic_folder_open, 16)
                    Text(folder.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val file = checkNotNull(row.file)
                SidebarRow(file.name, R.drawable.inkask_ic_file_text, active = file.id == activeDocument,
                    enabled = !busy, inset = row.depth, split = { split(file) }) { open(file) }
            }
        }
    }
}

@Composable
private fun SidebarTab(label: String, active: Boolean, modifier: Modifier, action: () -> Unit) {
    Box(modifier.clip(SidebarRowShape).background(if (active) SidebarSelected else Color.Transparent)
        .semantics { selected = active }.clickable(onClick = action).heightIn(min = 38.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Green else Muted, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun SidebarNotes(reader: ReaderSidebarActions, busy: Boolean) {
    val notes = reader.content.notes
    if (notes.isEmpty()) {
        Text("本书还没有追问", Modifier.padding(horizontal = 9.dp, vertical = 12.dp), color = Muted, fontSize = 13.sp)
        return
    }
    val groups = remember(notes) { notes.groupBy(ReaderSidebarNote::page).toSortedMap() }
    var filterPage by remember { mutableStateOf<Int?>(null) }
    var choosePage by remember { mutableStateOf(false) }
    val current = filterPage?.takeIf { it in groups }
    val list = current?.let { mapOf(it to groups.getValue(it)) } ?: groups
    Box(Modifier.padding(bottom = 6.dp)) {
        TextButton(onClick = { choosePage = true }) {
            Text(current?.let { "第 $it 页 ▾" } ?: "全部页码 ▾", fontSize = 12.sp)
        }
        DropdownMenu(expanded = choosePage, onDismissRequest = { choosePage = false },
            modifier = Modifier.heightIn(max = 296.dp), shape = ReaderCardShape, containerColor = Paper,
        ) {
            DropdownMenuItem(text = { Text("全部页码") }, onClick = { filterPage = null; choosePage = false })
            groups.keys.forEach { page ->
                DropdownMenuItem(
                    text = { Text(if (page == reader.content.page) "第 $page 页 · 当前页" else "第 $page 页") },
                    onClick = { filterPage = page; choosePage = false },
                )
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 6.dp)) {
        list.forEach { (page, entries) ->
            if (current == null) item(key = "page-$page") {
                Text("第 $page 页", Modifier.padding(start = 9.dp, top = 9.dp, bottom = 4.dp), color = Muted, fontSize = 12.sp)
            }
            items(entries, key = ReaderSidebarNote::id) { note ->
                SidebarRow(note.title, R.drawable.inkask_ic_message_square, active = note.id == reader.content.activeNote,
                    enabled = !busy, split = { reader.splitNote(note) }) { reader.openNote(note.id) }
            }
        }
    }
}
