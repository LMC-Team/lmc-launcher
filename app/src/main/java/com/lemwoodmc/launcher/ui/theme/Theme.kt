package com.lemwoodmc.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// LMC 品牌视觉：深色游戏风启动器
// 主色取自草方块绿，背景为近黑的深空色，强调色用天空蓝做对比。
// 游戏启动器固定使用深色主题（不跟随系统），保证品牌一致性与暗光环境体验。
// ---------------------------------------------------------------------------

// 品牌色板
val LmcGreen = Color(0xFF5DBB63)       // 草方块绿（主色）
val LmcGreenDeep = Color(0xFF2E7D32)   // 深绿（渐变收尾）
val LmcGreenGlow = Color(0xFF8AE68C)   // 高亮绿（文字强调）
val LmcBlue = Color(0xFF64B5F6)        // 天空蓝（次要强调）
val LmcAmber = Color(0xFFFFB300)       // 琥珀（温度预警等警示）
val LmcBg = Color(0xFF0D1117)          // 深空背景
val LmcBgBottom = Color(0xFF101A10)    // 背景渐变收尾（带一缕绿意）
val LmcSurface = Color(0xFF161C24)     // 卡片面
val LmcSurfaceBorder = Color(0xFF232B36) // 卡片描边

private val LmcDarkColors = darkColorScheme(
    primary = LmcGreen,
    onPrimary = Color(0xFF08130A),
    primaryContainer = Color(0xFF1F3A22),
    onPrimaryContainer = LmcGreenGlow,
    secondary = LmcBlue,
    onSecondary = Color(0xFF08131A),
    tertiary = LmcAmber,
    background = LmcBg,
    onBackground = Color(0xFFE6EDF3),
    surface = LmcSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1E252E),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = LmcSurfaceBorder,
)

/** 应用主题：始终使用品牌深色（游戏启动器不跟随系统亮色） */
@Composable
fun LmcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LmcDarkColors, content = content)
}
