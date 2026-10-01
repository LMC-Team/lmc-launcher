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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    // 进入游戏画面锁定横屏（GL context 不因旋转重建）
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.LaunchedEffect(Unit) {
        activity?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    // 退出时恢复传感器方向
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // 系统返回键 = 退出游戏画面(替代已删除的"退出"按钮)
    androidx.activity.compose.BackHandler { onExit() }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 游戏渲染层：SurfaceView（绕过 Compose 绘制，零合成开销） ----
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback2 {
                        override fun surfaceCreated(holder: SurfaceHolder) {}
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (!vm.isJvmStarted) {
                                // surface 就绪 → 触发完整启动链（渲染环境 → 音频 → oom_adj → 绑核 → JVM）
                                vm.launchOnSurface(holder.surface, width, height)
                            } else {
                                // JVM 运行中的尺寸变化（旋转/重布局）：重注入桥窗口
                                vm.onSurfaceSizeChanged(holder.surface, width, height)
                            }
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.onSurfaceDestroyed()
                        }
                        override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}
                    })
                    setZOrderMediaOverlay(false)
                    // ---- 触摸桥：触摸 → CallbackBridge 事件流 → pojavexec 输入桥 ----
                    // 单指模拟鼠标：DOWN/MOVE = 移动+左键按下，UP = 左键抬起。
                    // 坐标 1:1（MC 窗口尺寸 = glfwstub.windowWidth/Height = Surface 物理尺寸）。
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                vm.sendTouch(event.x, event.y, true)
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                vm.sendTouch(event.x, event.y, null)
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                vm.sendTouch(event.x, event.y, false)
                            }
                        }
                        true
                    }
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

        // 退出按钮已移除(玩家反馈右上角白透明块挡画面):退出走系统返回键
        // (onBackPressed → GameScreen onDispose → onExit 恢复导航)

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

        // ---- 游戏日志悬浮框（左上角，可展开/收起，5 秒刷新） ----
        val gameLogLines = remember { mutableStateListOf<String>() }
        var logExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val logFile = java.io.File(
                com.lemwoodmc.launcher.bridge.NativeBridge.filesDirCompat,
                "minecraft/logs/latest.log")
            while (true) {
                val lines = readLogTail(logFile)
                gameLogLines.clear()
                gameLogLines.addAll(lines)
                kotlinx.coroutines.delay(3000)
            }
        }
        if (gameLogLines.isNotEmpty()) {
            LogOverlay(
                lines = gameLogLines,
                expanded = logExpanded,
                onToggleExpand = { logExpanded = !logExpanded },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 84.dp, start = 8.dp),
            )
        }
    }
}

