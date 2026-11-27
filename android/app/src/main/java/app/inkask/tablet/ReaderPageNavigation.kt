package app.inkask.tablet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun ReaderPageButton(
    page: Int, count: Int, vertical: Boolean, onPosition: (Rect) -> Unit,
    onHold: (Int) -> Unit, onScrub: (Int) -> Unit, onRelease: () -> Unit, onClick: () -> Unit,
) {
    val currentPage by rememberUpdatedState(page)
    val hold by rememberUpdatedState(onHold)
    val scrub by rememberUpdatedState(onScrub)
    val release by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current
    val gesture = if (count <= 1) Modifier else Modifier.pointerInput(count, vertical, haptics) {
        val pixelsPerPage = 18.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed || down.type == PointerType.Mouse) return@awaitEachGesture
            var previous = down.position
            val stopped = withTimeoutOrNull<Unit>(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                    if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } != 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) return@withTimeoutOrNull
                    previous = change.position
                }
            }
            if (stopped != null) return@awaitEachGesture
            var target = currentPage
            var position = target.toFloat()
            hold(target)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val delta = change.position - previous
                    previous = change.position
                    position = (position + (if (vertical) -delta.y else delta.x) / pixelsPerPage)
                        .coerceIn(1f, count.toFloat())
                    val next = position.roundToInt()
                    if (next != target) {
                        target = next
                        scrub(next)
                    }
                    if (!change.pressed) break
                }
            } finally {
                release()
            }
        }
    }
    ReaderButton(
        "页码", label = page.toString(), state = "第 $page 页，共 $count 页", active = true,
        modifier = Modifier.onGloballyPositioned { onPosition(it.boundsInWindow()) }.then(gesture),
        action = onClick,
    )
}

@Composable
internal fun ReaderPageScrubIndicator(page: Int, count: Int, anchor: Rect?, vertical: Boolean) {
    PageNavigationOverlay(anchor, vertical) {
        Surface(color = Green, shape = CircleShape, shadowElevation = 5.dp) {
            Text("第 $page / $count 页", Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ReaderPageJump(
    page: Int, count: Int, anchor: Rect?, vertical: Boolean, onDismiss: () -> Unit, onJump: (Int) -> Unit,
) {
    val limit = count.toString().length
    var input by remember { mutableStateOf(TextFieldValue(page.toString())) }
    var editingNumber by remember { mutableStateOf(false) }
    val target = input.text.toIntOrNull()?.takeIf { it in 1..count }
    val selected = target ?: page
    fun select(number: Int) {
        val text = number.toString()
        input = TextFieldValue(text, TextRange(text.length))
    }
    fun submit() {
        target?.let(onJump)
    }
    val numberStyle = LocalTextStyle.current.copy(
        color = if (target == null && input.text.isNotEmpty()) MaterialTheme.colorScheme.error else Ink,
        textAlign = TextAlign.End, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
    )

    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Ink.copy(alpha = .14f))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .semantics { contentDescription = "关闭页码跳转" },
        )
        PageNavigationOverlay(anchor, vertical) {
            Surface(
                modifier = Modifier.widthIn(max = 308.dp).fillMaxWidth().semantics { paneTitle = "跳转页码" },
                shape = ReaderCardShape, color = Paper, border = BorderStroke(1.dp, Line), shadowElevation = 8.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Text("跳转页码", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReaderButton("上一页", R.drawable.inkask_ic_chevron_left, enabled = selected > 1) {
                            select(selected - 1)
                        }
                        val editOnTap = if (editingNumber) Modifier else Modifier.clickable {
                            input = input.copy(selection = TextRange(0, input.text.length))
                            editingNumber = true
                        }.semantics { contentDescription = "目标页码" }
                        Row(
                            Modifier.weight(1f).heightIn(min = 44.dp).background(Bar, ControlShape)
                                .border(1.dp, if (editingNumber) Green else Line, ControlShape)
                                .then(editOnTap).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editingNumber) {
                                val fieldFocus = remember { FocusRequester() }
                                val keyboard = LocalSoftwareKeyboardController.current
                                LaunchedEffect(fieldFocus) {
                                    fieldFocus.requestFocus()
                                    keyboard?.show()
                                }
                                BasicTextField(
                                    input,
                                    { value ->
                                        if (value.text.length <= limit && value.text.all { it in '0'..'9' }) input = value
                                    },
                                    modifier = Modifier.weight(1f).focusRequester(fieldFocus)
                                        .semantics { contentDescription = "目标页码" },
                                    textStyle = numberStyle, singleLine = true, cursorBrush = SolidColor(Green),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { submit() }),
                                )
                            } else Text(input.text, Modifier.weight(1f), style = numberStyle, maxLines = 1)
                            Text(" / $count", color = Muted, fontSize = 13.sp, maxLines = 1)
                        }
                        ReaderButton("下一页", R.drawable.inkask_ic_chevron_right, enabled = selected < count) {
                            select(selected + 1)
                        }
                    }
                    if (count > 1) {
                        Slider(
                            value = selected.toFloat(), onValueChange = { select(it.roundToInt()) },
                            valueRange = 1f..count.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Line,
                            ),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "拖动选择页码" },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (count > 1) {
                            Control("首页", enabled = selected != 1) { select(1) }
                            Control("末页", enabled = selected != count) { select(count) }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = ::submit, enabled = target != null, shape = ControlShape) {
                            Text("跳转")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageNavigationOverlay(anchor: Rect?, vertical: Boolean, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = minOf(12.dp.roundToPx(), width / 2, height / 2)
        val panel = children.single().measure(constraints.copy(
            minWidth = 0, minHeight = 0, maxWidth = width - 2 * gap, maxHeight = height - 2 * gap,
        ))
        val centerX = anchor?.center?.x ?: width / 2f
        val centerY = anchor?.center?.y ?: height / 2f
        val x = when {
            anchor == null || !vertical -> centerX - panel.width / 2f
            centerX < width / 2f -> anchor.right + gap
            else -> anchor.left - gap - panel.width
        }
        val y = when {
            anchor == null || vertical -> centerY - panel.height / 2f
            centerY < height / 2f -> anchor.bottom + gap
            else -> anchor.top - gap - panel.height
        }
        layout(width, height) {
            panel.place(
                x.roundToInt().coerceIn(gap, width - gap - panel.width),
                y.roundToInt().coerceIn(gap, height - gap - panel.height),
            )
        }
    }
}
