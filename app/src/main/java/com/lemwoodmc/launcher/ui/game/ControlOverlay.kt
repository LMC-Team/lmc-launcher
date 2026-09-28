package com.lemwoodmc.launcher.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 游戏内悬浮控制层（Compose 实现）：
 *  - 虚拟按键：按下/抬起 → [onKey] → native 输入中枢（InputHub）；
 *  - 虚拟鼠标区：拖动映射指针移动，单击为左键，专用右键按钮；
 *  - 支持自定义布局（编辑模式下拖动任意按钮）与全局透明度；
 *  - 由外层控制可见性：隐藏时整个 Composable 移出组合树，游戏帧率不受 UI 影响。
 *
 * 键位使用 GLFW 键码（Minecraft / LWJGL 原生约定）。
 */
private data class ControlButton(
    val label: String,
    val keyCode: Int,
    val xFrac: Float, // 默认位置（相对屏幕宽高比例，左上角锚点）
    val yFrac: Float,
)

// GLFW 键码：W=87 A=65 S=83 D=68 Space=32 Shift=340 E=69 Q=81
private val DEFAULT_BUTTONS = listOf(
    ControlButton("↑", 87, 0.09f, 0.60f),
    ControlButton("↓", 83, 0.09f, 0.78f),
    ControlButton("←", 65, 0.03f, 0.69f),
    ControlButton("→", 68, 0.15f, 0.69f),
    ControlButton("跳", 32, 0.88f, 0.64f),
    ControlButton("潜", 340, 0.88f, 0.80f),
    ControlButton("E", 69, 0.76f, 0.64f),
    ControlButton("Q", 81, 0.76f, 0.80f),
)

@Composable
fun ControlOverlay(
    opacity: Float,
    onKey: (keyCode: Int, pressed: Boolean) -> Unit,
    onPointer: (action: Int, x: Float, y: Float, button: Int) -> Unit,
    onHide: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editMode by remember { mutableStateOf(false) }
    var showOpacity by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 注意：offset{} 使用像素坐标，必须取 constraints（像素）而非 maxWidth.value（dp），
        // 否则高密度屏上所有按键会挤在屏幕左侧 1/density 区域内（真机踩坑）
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        // ---- 虚拟鼠标区（右侧 40% 宽）：拖动 = 移动指针，单击 = 左键 ----
        var lastX by remember { mutableStateOf(0f) }
        var lastY by remember { mutableStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.45f)
                .fillMaxWidth(0.40f)
                .background(Color.White.copy(alpha = 0.04f * opacity))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            lastX = offset.x; lastY = offset.y
                        },
                        onDrag = { change, _ ->
                            val dx = change.position.x - lastX
                            val dy = change.position.y - lastY
                            lastX = change.position.x; lastY = change.position.y
                            change.consume()
                            onPointer(2, dx, dy, 0) // action=2 MOVE（相对位移，native 侧累加）
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures {
                        onPointer(0, 0f, 0f, 0) // 左键 down
                        onPointer(1, 0f, 0f, 0) // 左键 up
                    }
                },
        )

        // ---- 自定义布局虚拟按键（编辑模式下可拖动微调位置） ----
        DEFAULT_BUTTONS.forEach { btn ->
            KeyButton(
                button = btn,
                opacity = opacity,
                editMode = editMode,
                onKey = onKey,
                baseOffset = IntOffset((btn.xFrac * w).roundToInt(), (btn.yFrac * h).roundToInt()),
            )
        }

        // ---- 右键按钮（配合虚拟鼠标区） ----
        KeyButton(
            button = ControlButton("右键", -2, 0.82f, 0.50f),
            opacity = opacity,
            editMode = editMode,
            onKey = { _, pressed -> onPointer(if (pressed) 0 else 1, 0f, 0f, 1) },
            baseOffset = IntOffset((0.82f * w).roundToInt(), (0.50f * h).roundToInt()),
        )

        // ---- 控制条：编辑模式 / 透明度 / 隐藏 ----
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp, start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OverlayChip("布局", editMode) { editMode = !editMode }
            OverlayChip("不透明度", showOpacity) { showOpacity = !showOpacity }
            OverlayChip("隐藏", false, onClick = onHide)
        }
        if (showOpacity) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 84.dp, start = 8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Slider(
                    value = opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0.2f..1f,
                    modifier = Modifier.width(160.dp),
                )
            }
        }
    }
}

/**
 * 单个虚拟按键：
 *  普通模式 —— 按压发送键码 down/up；
 *  编辑模式 —— 拖动调整位置（骨架版内存态；位置持久化由后续版本写入 DataStore）。
 */
@Composable
private fun KeyButton(
    button: ControlButton,
    opacity: Float,
    editMode: Boolean,
    onKey: (Int, Boolean) -> Unit,
    baseOffset: IntOffset,
) {
    var pressed by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(baseOffset.x + drag.x, baseOffset.y + drag.y) }
            .size(56.dp)
            .background(
                if (pressed) Color(0xFF4CAF50).copy(alpha = 0.85f * opacity)
                else Color.Black.copy(alpha = 0.45f * opacity),
                RoundedCornerShape(10.dp),
            )
            .let { base ->
                if (editMode) {
                    base.pointerInput(button.keyCode) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            drag = IntOffset(drag.x + amount.x.roundToInt(), drag.y + amount.y.roundToInt())
                        }
                    }
                } else {
                    base.pointerInput(button.keyCode) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                onKey(button.keyCode, true)
                                tryAwaitRelease()
                                pressed = false
                                onKey(button.keyCode, false)
                            },
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, color = Color.White)
    }
}

@Composable
private fun OverlayChip(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
        ),
        modifier = Modifier.height(36.dp),
    ) { Text(label) }
}
