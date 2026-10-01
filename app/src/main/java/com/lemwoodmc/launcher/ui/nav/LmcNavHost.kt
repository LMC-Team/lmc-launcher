package com.lemwoodmc.launcher.ui.nav

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lemwoodmc.launcher.ui.accounts.AccountScreen
import com.lemwoodmc.launcher.ui.game.GameScreen
import com.lemwoodmc.launcher.ui.home.HomeScreen
import com.lemwoodmc.launcher.ui.settings.SettingsScreen
import com.lemwoodmc.launcher.ui.versions.VersionScreen
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/** 路由表 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val VERSIONS = "versions"
    const val ACCOUNTS = "accounts"
    const val GAME = "game"
}

/** 导航项 */
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "主页", Icons.Filled.Home),
    Tab(Routes.VERSIONS, "版本", Icons.Filled.PlayArrow),
    Tab(Routes.ACCOUNTS, "账户", Icons.Filled.Person),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

/**
 * 应用骨架：自适应导航 ——
 *  - 宽屏（≥600dp，横屏常态）：左侧 [NavigationRail]（M3 横屏手机/平板标准导航）；
 *  - 窄屏（竖屏兜底）：底部 [NavigationBar]。
 * 游戏画面（GAME）隐藏导航，全屏呈现。
 *
 * LauncherViewModel 挂在 Activity 级（而非各 destination）：
 * 版本下载后的列表/选中状态必须跨页面共享，否则主页与版本页
 * 会各持一份互不同步的实例（真机踩坑）。
 */
@Composable
fun LmcNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val launcherVm: LauncherViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current
            as? androidx.activity.ComponentActivity
            ?: error("LauncherViewModel 需要 ComponentActivity 作用域"),
    )

    fun goTab(route: String) {
        if (currentRoute != route) {
            // 切 tab 时恢复各自状态，避免重复入栈
            nav.navigate(route) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // 内容区：GAME 路由独占全屏（沉浸），其余路由共享导航侧栏
    val content: @Composable () -> Unit = {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(launcherVm, onStartGame = { nav.navigate(Routes.GAME) }) }
            composable(Routes.VERSIONS) { VersionScreen(launcherVm) }
            composable(Routes.ACCOUNTS) { AccountScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.GAME) { GameScreen(onExit = { nav.popBackStack() }) }
        }
    }

    if (currentRoute == Routes.GAME) {
        content()
    } else {
        BoxWithConstraints {
            val wide = maxWidth >= 600.dp
            if (wide) {
                // ---- 横屏：左侧 NavigationRail + 内容区 ----
                Row {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        TABS.forEach { tab ->
                            NavigationRailItem(
                                selected = currentRoute == tab.route,
                                onClick = { goTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content() }
                }
            } else {
                // ---- 竖屏兜底：底部导航 ----
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            TABS.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { goTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { content() }
                }
            }
        }
    }
}
