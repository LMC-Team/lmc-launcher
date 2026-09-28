package com.lemwoodmc.launcher

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lemwoodmc.launcher.ui.nav.LmcNavHost
import com.lemwoodmc.launcher.ui.theme.LmcTheme

/**
 * 启动器主界面：全部页面（主页 / 设置 / 版本管理 / 账户管理）由 Compose Material 3 绘制，
 * 底部导航切换，视觉为深色游戏风。
 * 游戏画面有两种呈现方式：
 *  1) 普通模式：主页内嵌 SurfaceView（AndroidView）+ Compose 悬浮控制层叠加；
 *  2) 极致模式：跳转 [com.lemwoodmc.launcher.game.GameActivity]（NativeActivity），
 *     ANativeWindow 与 AInputQueue 全程由 native 层直连，Java 侧零参与。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色 UI 固定使用浅色（白）系统栏图标，不跟随系统亮暗
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            LmcTheme {
                LmcNavHost()
            }
        }
    }
}
