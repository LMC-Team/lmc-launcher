package com.lemwoodmc.launcher.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * zl2(ZalithLauncher 2)同款游戏内交互形态的 lmc 移植:
 *  - [GameBall]:可拖动悬浮球(默认顶部居中),点击呼出两侧菜单
 *  - [DualMenuSubscreen]:左右各 1/3 宽的滑入菜单卡 + 全屏遮罩
 * 交互结构参照 zl2 的 FloatingBall(Draggabble.kt)/DualMenuSubscreen(Menu.kt)
 * (GPL 交互思想),实现按 lmc 深色主题重写。
 */

/** 悬浮球:拖动改位置(边界内),单击触发 onClick */
@Composable
fun GameBall(
    position: Offset,
    onPositionChanged: (Offset) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    opened: Boolean = false,
) {
    var ballSize by remember { mutableStateOf(IntSize.Zero) }
    var parent by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(p: Offset): Offset {
        if (ballSize == IntSize.Zero || parent == IntSize.Zero) return p
        return Offset(
            p.x.coerceIn(0f, max(0, parent.width - ballSize.width).toFloat()),
            p.y.coerceIn(0f, max(0, parent.height - ballSize.height).toFloat()),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                parent = it
                val c = clamp(position)
                if (c != position) onPositionChanged(c)
            }
            .pointerInput(Unit) {},
    ) {
        Surface(
            shape = RoundedCornerShape(21.dp),
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
            modifier = Modifier
                .alpha(0.9f)
                .onSizeChanged { ballSize = it }
                .offset {
                    val c = clamp(position)
                    IntOffset(c.x.roundToInt(), c.y.roundToInt())
                }
                .pointerInput(Unit) {
                    //zl2 FloatingBall 同款手势:拖动超 slop 判定为拖,否则抬起=点击
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPosition = down.position
                        var isDragging = false

                        val completed = drag(down.id) { change ->
                            val distanceFromStart =
                                (change.position - startPosition).getDistance()
                            if (!isDragging &&
                                distanceFromStart > viewConfiguration.touchSlop
                            ) isDragging = true
                            if (isDragging) {
                                onPositionChanged(clamp(position + change.positionChange()))
                            }
                            change.consume()
                        }
                        if (completed && !isDragging) onClick()
                    }
                },
        ) {
            Row(
                modifier = Modifier.padding(all = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (opened) Icons.Filled.Close else Icons.Filled.Menu,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 两侧菜单(zl2 DualMenuSubscreen 交互形态):遮罩 + 左右 1/3 宽卡片滑入 */
@Composable
fun DualMenuSubscreen(
    visible: Boolean,
    close: () -> Unit,
    leftTitle: String,
    leftContent: @Composable ColumnScope.() -> Unit,
    rightTitle: String,
    rightContent: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = close,
                    )
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(1f / 3f)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally { -it },
                exit = fadeOut() + slideOutHorizontally { -it },
            ) { MenuCard(title = leftTitle, content = leftContent) }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(1f / 3f)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally { it },
                exit = fadeOut() + slideOutHorizontally { it },
            ) { MenuCard(title = rightTitle, content = rightContent) }
        }
    }
}

@Composable
private fun MenuCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

/** 菜单行按钮 */
@Composable
fun MenuRowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 菜单内开关行 */
@Composable
fun MenuSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** 菜单内滑条行 */
@Composable
fun MenuSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * 触摸桥(放 ControlBoxLayout 的 content 内,zl2 MouseControlLayout 的架构位):
 * 控件层 Initial pass 未命中控件的触摸落到这里 → 转发游戏鼠标事件流。
 * 命中控件的触摸被控件层 consume,不会到这里。
 */
@Composable
fun BoxScope.TouchBridgeLayout(
    onTouch: (x: Float, y: Float, pressed: Boolean?) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = down.position
                    onTouch(down.position.x, down.position.y, true)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            onTouch(change.position.x, change.position.y, false)
                            break
                        }
                        if (change.position != last) {
                            onTouch(change.position.x, change.position.y, null)
                            last = change.position
                        }
                        // 不消费:保持对上层(悬浮球等)友好,且不干扰控件层状态机
                    }
                }
            }
    )
}
