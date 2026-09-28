package com.lemwoodmc.launcher.ui.game

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemwoodmc.launcher.viewmodel.GameViewModel

/**
 * 游戏画面（SurfaceView 内嵌路径）：
 *  - SurfaceView 通过 AndroidView 嵌入 Compose 层级，游戏在其上渲染；
 *  - Compose 悬浮控制层叠加在游戏画面之上，可整体隐藏（UI 零开销）；
 *  - 极致性能路径请使用 GameActivity（NativeActivity + AInputQueue 直连）。
 */
@Composable
fun GameScreen(onExit: () -> Unit) {
    val vm: GameViewModel = viewModel()
    val state by vm.state.collectAsState()
    val controlVisible by vm.controlVisible.collectAsState()
    val controlOpacity by vm.controlOpacity.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 游戏渲染层：SurfaceView（绕过 Compose 绘制，零合成开销） ----
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback2 {
                        override fun surfaceCreated(holder: SurfaceHolder) {}
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // surface 就绪 → 触发完整启动链（渲染环境 → 音频 → oom_adj → 绑核 → JVM）
                            vm.launchOnSurface(holder.surface, width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.onSurfaceDestroyed()
                        }
                        override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}
                    })
                    setZOrderMediaOverlay(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- 状态角标（JVM 启动中 / 温度预警）：半透明胶囊 ----
        M3Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    text = when (state.phase) {
                        GameViewModel.Phase.PREPARING -> "准备运行时…"
                        GameViewModel.Phase.JVM_STARTING -> "启动 JVM（AppCDS）…"
                        GameViewModel.Phase.RUNNING, GameViewModel.Phase.RENDERING -> "运行中"
                        GameViewModel.Phase.CRASHED -> "游戏崩溃（exit=${state.exitCode}）"
                        GameViewModel.Phase.EXITED -> "已退出"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                if (state.thermalSeverity >= 1) {
                    Text(
                        "温度预警：预测 ${state.thermalPredictedC.toInt()}°C，已降低渲染分辨率",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB300),
                    )
                }
            }
        }

        // ---- 退出按钮 ----
        Button(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 8.dp),
        ) { Text("退出") }

        // ---- 悬浮控制层（可整体隐藏 → Compose 树移除，游戏帧率不受 UI 影响） ----
        if (controlVisible) {
            ControlOverlay(
                opacity = controlOpacity,
                onKey = vm::sendKey,
                onPointer = vm::sendPointer,
                onHide = vm::toggleControlLayer,
                onOpacityChange = vm::setControlOpacity,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
