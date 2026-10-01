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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
    val context = androidx.compose.ui.platform.LocalContext.current

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

        // ---- 虚拟鼠标光标(zl2 鼠标层核心行为):跟随触摸移动的可见箭头 ----
        val cursor = vm.cursorPosition.value
        if (cursor != null && state.phase == GameViewModel.Phase.RENDERING) {
            val cx = cursor.x
            val cy = cursor.y
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(0.dp)
            ) {
                //MC 经典箭头形(白色填充 + 黑描边),锚点在箭头尖
                val s = 18.dp.toPx()
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(cx, cy)
                path.lineTo(cx + s * 0.42f, cy + s * 0.72f)
                path.lineTo(cx + s * 0.60f, cy + s * 0.52f)
                path.lineTo(cx + s * 0.78f, cy + s * 0.94f)
                path.lineTo(cx + s * 0.94f, cy + s * 0.86f)
                path.lineTo(cx + s * 0.76f, cy + s * 0.46f)
                path.lineTo(cx + s * 0.98f, cy + s * 0.30f)
                path.close()
                drawPath(path, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawPath(path, color = androidx.compose.ui.graphics.Color.White)
            }
        }

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

        // 旧自制 ControlOverlay 已由 zl2 控件布局引擎(LayerController)替代,
        // 控件样式/位置由 control_layouts/*.json 布局文件驱动,可在编辑器中自定义。

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

        // ---- zl2 同款：悬浮球 + 两侧菜单 ----
        var ballX by remember { mutableStateOf(-1f) }
        var ballY by remember { mutableStateOf(0f) }
        var menuOpen by remember { mutableStateOf(false) }
        if (ballX < 0f) {
            //默认顶部居中
            val density = androidx.compose.ui.platform.LocalDensity.current
            ballX = with(density) { 240.dp.toPx() }
        }
        GameBall(
            position = Offset(ballX, ballY),
            onPositionChanged = { ballX = it.x; ballY = it.y },
            onClick = { menuOpen = !menuOpen },
            opened = menuOpen,
            modifier = Modifier.fillMaxSize(),
        )
        DualMenuSubscreen(
            visible = menuOpen,
            close = { menuOpen = false },
            leftTitle = "启动器控制",
            leftContent = {
                MenuSwitchRow(
                    "显示悬浮控制层",
                    checked = controlVisible,
                    onChange = vm::setControlVisible,
                )
                MenuSliderRow(
                    "控制层不透明度",
                    value = controlOpacity,
                    range = 0.2f..1f,
                    onChange = vm::setControlOpacity,
                )
                MenuRowButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "退出游戏（返回启动器）",
                    onClick = onExit,
                )
            },
            rightTitle = "游戏诊断",
            rightContent = {
                MenuRowButton(
                    icon = Icons.Filled.Info,
                    label = "切换 F3 调试屏",
                    onClick = { vm.sendKey(292, true); vm.sendKey(292, false) },
                )
                MenuRowButton(
                    icon = Icons.Filled.Settings,
                    label = "发送 Esc（关闭当前 GUI）",
                    onClick = { vm.sendKey(256, true); vm.sendKey(256, false) },
                )
                MenuRowButton(
                    icon = Icons.Filled.Build,
                    label = "强制关闭游戏进程",
                    onClick = {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            },
        )

        // ---- zl2 控件布局引擎渲染(LayerController):从布局文件加载可编辑控件 ----
        if (controlVisible) {
            LaunchedEffect(Unit) {
                com.lemwoodmc.launcher.game.control.LmcControlManager
                    .checkDefaultAndRelease(context)
                com.lemwoodmc.launcher.game.control.LmcControlManager
                    .loadControlLayout(context.filesDir)
            }
            val observed = com.lemwoodmc.launcher.game.control.LmcControlManager.observableLayout
            com.movtery.layer_controller.ControlBoxLayout(
                modifier = Modifier.fillMaxSize(),
                observedLayout = observed,
                eventHandler = com.lemwoodmc.launcher.game.control.LmcControlManager.eventHandler,
                isCursorGrabbing = false,
                checkOccupiedPointers = { false },
                opacity = controlOpacity,
                isDark = true,
            ) {}
        }
    }
}

