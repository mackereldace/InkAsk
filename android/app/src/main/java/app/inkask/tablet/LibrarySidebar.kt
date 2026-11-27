package app.inkask.tablet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SidebarPaper = Color(0xFFF2F4ED)
internal val SidebarSelected = Color(0xFFE0E7D9)
internal val SidebarRowShape = RoundedCornerShape(7.dp)

@Composable
internal fun LibrarySidebar(
    visible: Boolean, library: Library, folderId: String?, activeDocument: String?,
    query: String, onSearch: (String) -> Unit, busy: Boolean, onDismiss: () -> Unit,
    onFolder: (String?) -> Unit, onDocument: (PdfItem) -> Unit, openPdf: () -> Unit,
    importArchive: () -> Unit, openSettings: () -> Unit,
) {
    val searching = query.isNotBlank()
    val folders = remember(library.folders, folderId) { sidebarFolders(library.folders, folderId) }
    val documents = remember(library, folderId, query) {
        val term = query.trim()
        library.documents.filter { item ->
            if (term.isEmpty()) library.membership[item.id] == folderId else item.name.contains(term, ignoreCase = true)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 520.dp
        SidebarFrame(visible, "资料侧栏", onDismiss) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 6.dp)) {
                item {
                    SidebarBrand(onHome = { onFolder(null) }, onDismiss = onDismiss)
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(SidebarRowShape).clickable(enabled = !busy) { onFolder(null) }
                            .heightIn(min = 48.dp).padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp).background(SidebarSelected, SidebarRowShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("我", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        Text("我的阅读空间", Modifier.weight(1f), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        SidebarIcon(R.drawable.inkask_ic_chevron_right, 14)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = openPdf, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        shape = SidebarRowShape, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.inkask_ic_plus), null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("打开 PDF", Modifier.weight(1f), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(19.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("资料库", Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            library.documents.size.toString(),
                            Modifier.background(SidebarSelected, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Muted, fontSize = 11.sp,
                        )
                    }
                    SidebarSearch(query, onSearch)
                    if (!searching) {
                        SidebarRow(
                            "阅读空间", R.drawable.inkask_ic_folder_open,
                            active = folderId == null && activeDocument == null, enabled = !busy,
                        ) { onFolder(null) }
                    }
                }
                if (!searching) {
                    items(folders, key = { "folder-${it.folder.id}" }) { (folder, depth) ->
                        SidebarRow(
                            folder.name, R.drawable.inkask_ic_folder_open,
                            active = folder.id == folderId, enabled = !busy, inset = depth,
                        ) { onFolder(folder.id) }
                    }
                }
                items(documents, key = { "pdf-${it.id}" }) { item ->
                    SidebarRow(
                        item.name, R.drawable.inkask_ic_file_text, active = item.id == activeDocument,
                        enabled = !busy, inset = if (folderId == null || searching) 0 else 1,
                    ) { onDocument(item) }
                }
                if (documents.isEmpty() && searching) item {
                    Text("没有找到资料", Modifier.padding(horizontal = 9.dp, vertical = 10.dp), color = Muted, fontSize = 12.sp)
                }
                if (compact) item { SidebarFooter(busy, importArchive, openSettings) }
            }
            if (!compact) SidebarFooter(busy, importArchive, openSettings)
        }
    }
}

@Composable
internal fun SidebarFrame(
    visible: Boolean, title: String, onBackdrop: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = minOf(280.dp, maxWidth * .88f)
        if (onBackdrop != null) AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize().background(Ink.copy(alpha = .13f)).clickable(enabled = visible, onClick = onBackdrop)
                    .semantics { contentDescription = "收起资料侧栏" },
            )
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally(tween(180)) { -it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(180)),
        ) {
            Surface(
                Modifier.padding(12.dp).width(width).fillMaxHeight().semantics {
                    paneTitle = title
                },
                color = SidebarPaper, shape = ReaderCardShape, border = BorderStroke(1.dp, Line), shadowElevation = 16.dp,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), content = content)
            }
        }
    }
}

private data class SidebarFolder(val folder: Folder, val depth: Int)

private fun sidebarFolders(folders: List<Folder>, active: String?): List<SidebarFolder> {
    val byId = folders.associateBy(Folder::id)
    val children = folders.groupBy(Folder::parentId)
    val trail = generateSequence(active) { byId[it]?.parentId }.take(folders.size).toSet()
    return buildList {
        fun addChildren(parent: String?, depth: Int) {
            children[parent].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Folder::name)).forEach { folder ->
                add(SidebarFolder(folder, depth))
                if (folder.id in trail) addChildren(folder.id, depth + 1)
            }
        }
        addChildren(null, 0)
    }
}

@Composable
internal fun SidebarBrand(onHome: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(SidebarRowShape).clickable(onClick = onHome).padding(start = 7.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(width = 30.dp, height = 36.dp)) {
                Box(
                    Modifier.size(width = 28.dp, height = 34.dp).rotate(-7f)
                        .background(Green, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomEnd = 10.dp, bottomStart = 2.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("i", color = Paper, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 28.sp)
                }
                Text("✳", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-8).dp),
                    fontSize = 14.sp, color = Color(0xFFA7BC94))
            }
            Text("inkask", color = Ink, fontSize = 28.sp, letterSpacing = (-1.2).sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = onDismiss, modifier = Modifier.size(44.dp).semantics { contentDescription = "关闭资料侧栏" },
        ) { SidebarIcon(R.drawable.inkask_ic_close, 17) }
    }
}

@Composable
internal fun SidebarSearch(query: String, search: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = query, onValueChange = search,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索资料" },
        textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 13.sp),
        singleLine = true, cursorBrush = SolidColor(Green),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { input ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidebarIcon(R.drawable.inkask_ic_search, 15)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索资料…", color = Muted, fontSize = 13.sp)
                    input()
                }
            }
        },
    )
}

@Composable
internal fun SidebarRow(
    label: String, icon: Int, active: Boolean = false, enabled: Boolean = true, inset: Int = 0,
    split: (() -> Unit)? = null, action: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = (inset.coerceAtMost(4) * 9).dp).clip(SidebarRowShape)
            .background(if (active) SidebarSelected else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clip(SidebarRowShape).semantics { selected = active }
            .clickable(enabled = enabled, onClick = action).heightIn(min = 42.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SidebarIcon(icon, 16)
            Text(label, color = if (active) Green else Ink, fontSize = 13.sp,
                maxLines = if (split == null) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (split != null) {
            IconButton(onClick = split, enabled = enabled, modifier = Modifier.size(42.dp)) {
                Icon(painterResource(R.drawable.inkask_ic_columns), "分屏打开 $label", tint = Muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
internal fun SidebarIcon(id: Int, size: Int) {
    Icon(painterResource(id), null, modifier = Modifier.size(size.dp), tint = Muted)
}

@Composable
private fun SidebarFooter(busy: Boolean, importArchive: () -> Unit, openSettings: () -> Unit) {
    Column {
        SidebarRow("导入 InkAsk 项目", R.drawable.inkask_ic_folder_open, enabled = !busy, action = importArchive)
        HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Line)
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFF79A17E), CircleShape))
            Text("文档保存在你的设备上", color = Muted, fontSize = 11.sp)
        }
        SidebarRow("API 与模板", R.drawable.inkask_ic_settings, action = openSettings)
    }
}
