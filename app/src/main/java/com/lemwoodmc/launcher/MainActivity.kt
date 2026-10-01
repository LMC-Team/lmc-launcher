package com.lemwoodmc.launcher

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lemwoodmc.launcher.ui.nav.LmcNavHost
import com.lemwoodmc.launcher.ui.theme.LmcTheme

/**
 * 启动器主界面：全部页面（主页 / 设置 / 版本管理 / 账户管理）由 Compose Material 3 绘制，
 * 侧边 NavigationRail 切换，视觉为深色游戏风。
 * 游戏画面有两种呈现方式：
 *  1) 普通模式：主页内嵌 SurfaceView（AndroidView）+ Compose 悬浮控制层叠加；
 *  2) 极致模式：跳转 [com.lemwoodmc.launcher.game.GameActivity]（NativeActivity），
 *     ANativeWindow 与 AInputQueue 全程由 native 层直连，Java 侧零参与。
 *
 * 锁定传感器横屏：游戏 Surface 的尺寸与 MC 窗口（overrideWidth/Height、
 * pojavexec 的 bridge window）在启动时一次性绑定，中途旋转会导致画面
 * 撕裂错位（真机 2026-10-01：竖屏启动 + 横屏运行 → 左右分区错位）。
 *
 * 全屏沉浸：隐藏状态栏/导航条（sticky，滑动临时呼出），游戏与 UI 一体沉浸；
 * onWindowFocusChanged 重挂 —— 系统在焦点变化后会自行恢复系统栏。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 深色 UI 固定使用浅色（白）系统栏图标，不跟随系统亮暗
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // 内容绘制进刘海/挖孔区域（横屏时摄像头开孔在侧边）
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemBars()
        setContent {
            LmcTheme {
                LmcNavHost()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
