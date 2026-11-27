package app.inkask.tablet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderPopover(
    anchor: Rect?, beside: Boolean, title: String, dismiss: () -> Unit,
    width: Dp = 266.dp, maxHeight: Dp = 470.dp, content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = dismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null, onClick = dismiss)
            .semantics { contentDescription = "关闭${title}" })
        ReaderAnchoredPanel(anchor, beside) {
            Surface(
                Modifier.width(width).heightIn(max = maxHeight).semantics { paneTitle = title },
                color = Paper, shape = ReaderCardShape, border = BorderStroke(1.dp, Line), shadowElevation = 8.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp), content = content)
            }
        }
    }
}
