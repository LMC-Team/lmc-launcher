package com.lemwoodmc.launcher.game

import android.app.NativeActivity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.lemwoodmc.launcher.bridge.NativeBridge
import com.lemwoodmc.launcher.ui.game.ControlOverlay

/**
 * 游戏 Activity —— 极致性能路径（NativeActivity）。
 *
 * 与 SurfaceView 内嵌路径（GameScreen）的区别：
 *  - 框架把 ANativeWindow 直接下发给 native 渲染桥（无 Surface/SurfaceHolder 中转）；
 *  - 输入事件通过 AInputQueue 在 native 层直接处理（core/src/input/input_queue.cpp），
 *    完全不经过 Java 事件管线；
 *  - Compose 悬浮控制层由 [GameComposeOverlayHost] 注入，可整体隐藏 → UI 零开销；
 *  - 按 BACK（native 侧 AKEYCODE_BACK）可重新呼出已隐藏的控制层。
 *
 * native 侧入口：liblmc_core.so 中的 ANativeActivity_onCreate（见
 * core/src/input/native_activity_glue.cpp）。
 */
class GameActivity : NativeActivity() {

    private var overlayHandle: GameComposeOverlayHost.Handle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        if (overlayHandle == null) showOverlay()
    }

    override fun onPause() {
        if (overlayHandle != null) hideOverlay() // 切后台时移除 UI，回到游戏帧循环优先
        super.onPause()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 悬浮控制层管理
    // ------------------------------------------------------------------

    private fun showOverlay() {
        overlayHandle = GameComposeOverlayHost.attach(this) { GameOverlayContent() }
    }

    private fun hideOverlay() {
        overlayHandle?.detach()
        overlayHandle = null
    }

    private fun toggleOverlay() {
        if (overlayHandle == null) showOverlay() else hideOverlay()
    }

    /** native 层（AInputQueue 收到 AKEYCODE_BACK）回调：呼出/隐藏控制层。
     *  ANativeActivity.clz 即本 Activity 实例，native 侧用 GetMethodID 直接调用实例方法。 */
    fun onNativeToggleOverlay() {
        runOnUiThread { toggleOverlay() }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    companion object {
        /** native 胶水层通过 JNI 调用需要实例引用 */
        @JvmStatic var instance: GameActivity? = null
    }
}

/**
 * NativeActivity 路径的悬浮控制层内容。
 * 直接调用 NativeBridge（不经过 ViewModel，降低一层间接），
 * 行为与 GameScreen 中 ControlOverlay 完全一致。
 *
 * 背景转发：panel 窗口拦截了触摸，这里把空白区域的拖动转发回 native InputHub，
 * 保证控制层呼出期间视角转动等游戏输入不中断；控制层隐藏后由 AInputQueue 直读接管。
 */
@Composable
private fun GameOverlayContent() {
    var opacity by remember { mutableStateOf(0.6f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var lastX = 0f
                var lastY = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        lastX = offset.x; lastY = offset.y
                    },
                    onDrag = { change, _ ->
                        val dx = change.position.x - lastX
                        val dy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        change.consume()
                        NativeBridge.nativeInjectPointer(2, dx, dy, 0) // MOVE
                    },
                )
            },
    ) {
        ControlOverlay(
            opacity = opacity,
            onKey = { code, pressed -> NativeBridge.nativeInjectKey(code, pressed) },
            onPointer = { action, x, y, button -> NativeBridge.nativeInjectPointer(action, x, y, button) },
            onHide = { GameActivity.instance?.onNativeToggleOverlay() },
            onOpacityChange = { opacity = it },
        )
    }
}
