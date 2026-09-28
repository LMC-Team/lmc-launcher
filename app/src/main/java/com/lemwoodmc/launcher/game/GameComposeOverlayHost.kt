package com.lemwoodmc.launcher.game

import android.app.Activity
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose 悬浮层注入宿主（独立 panel 窗口方案）。
 *
 * 为什么必须是独立窗口：NativeActivity 的游戏窗口通过 takeInputQueue 把全部输入
 * 交给 native 层（AInputQueue 直读），叠加在其上的普通 View/ComposeView **收不到**
 * 触摸事件。因此控制层使用 TYPE_APPLICATION_PANEL 独立窗口——它拥有自己的
 * InputChannel，事件走自己的视图分发（Compose 正常交互），且 NOT_FOCUSABLE
 * 保证按键（BACK 等）仍走游戏窗口的 native 路径。
 *
 * 输入路由设计（两层窗口）：
 *   - 控制层隐藏：整层窗口移除，触摸全部落入游戏窗口 → AInputQueue → native（零 UI 开销）；
 *   - 控制层呼出：panel 窗口拦截触摸，按钮直接注入；背景触摸由 Compose 根布局
 *     转发进 native InputHub（视角转动等游戏内输入不中断）。
 *
 * NativeActivity 不是 ComponentActivity，这里手工为 ComposeView 提供
 * LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner 三类 ViewTree owner。
 */
object GameComposeOverlayHost {

    /** 生命周期持有者：三类 owner 合一（internal：Handle 的 internal 构造器需要引用它） */
    internal class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        init {
            savedStateController.performRestore(null)
        }

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun moveTo(state: Lifecycle.State) {
            if (registry.currentState != state) registry.currentState = state
        }
    }

    /**
     * 创建并显示悬浮层窗口。
     * @param content Compose 内容（控制层）
     * @return 句柄，用于控制显隐
     */
    fun attach(activity: Activity, content: @Composable () -> Unit): Handle {
        val owner = OverlayOwner()
        owner.moveTo(Lifecycle.State.CREATED)

        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent(content)
        }

        val wm = activity.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // 应用面板窗口：挂在游戏窗口的 token 上，跟随其生命周期
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            // NOT_FOCUSABLE：按键事件继续走游戏窗口（native BACK 处理）
            // NOT_TOUCH_MODAL：窗口外触摸穿透（面板本身全屏，实际由 Compose 转发）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.token = activity.window.decorView.applicationWindowToken

        wm.addView(composeView, params)
        owner.moveTo(Lifecycle.State.RESUMED)

        return Handle(wm, composeView, params, owner)
    }

    /** 悬浮层句柄：隐藏 = 移除窗口（UI 零开销），显示 = 重新挂回 */
    class Handle internal constructor(
        private val wm: WindowManager,
        private val view: ComposeView,
        private val params: WindowManager.LayoutParams,
        private val owner: OverlayOwner,
    ) {
        private var attached = true

        fun detach() {
            if (!attached) return
            owner.moveTo(Lifecycle.State.CREATED)
            runCatching { wm.removeView(view) } // 窗口 token 失效时忽略
            attached = false
        }

        fun show() {
            if (attached) return
            wm.addView(view, params)
            owner.moveTo(Lifecycle.State.RESUMED)
            attached = true
        }

        fun isShown() = attached
    }
}
