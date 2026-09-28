package com.lemwoodmc.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemwoodmc.launcher.ui.components.ConfigRow
import com.lemwoodmc.launcher.ui.components.InfoCard
import com.lemwoodmc.launcher.ui.theme.LmcBlue
import com.lemwoodmc.launcher.ui.theme.LmcGreen
import com.lemwoodmc.launcher.ui.theme.LmcGreenDeep
import com.lemwoodmc.launcher.ui.theme.LmcGreenGlow
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/**
 * 主页 —— 启动器视觉核心：
 *   Hero 标题区 → 启动卡（版本/账户 + 渐变开始按钮）→ 设备与性能信息卡。
 * 深色底 + 草方块绿渐变，信息层级：启动操作 > 设备状态 > 配置概览。
 */
@Composable
fun HomeScreen(vm: LauncherViewModel, onStartGame: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val versions by vm.versions.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val topology by vm.cpuTopology.collectAsState()

    val version = versions.firstOrNull { it.id == settings.selectedVersionId }
    val account = accounts.firstOrNull { it.id == settings.selectedAccountId }
    val canStart = versionInstalled(versions, settings.selectedVersionId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        // ---- Hero：品牌标题 ----
        Text("LMC", fontSize = 44.sp, fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground)
        Text("LAUNCHER", fontSize = 44.sp, fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp),
            color = LmcGreen)
        Spacer(Modifier.height(4.dp))
        Text("高性能 Minecraft Java 版启动器 · ARM64 / Vulkan 原生后端",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))

        // ---- 启动卡：版本 / 账户 + 开始按钮 ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                // 版本选择行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = LmcGreenGlow,
                        modifier = Modifier.height(18.dp))
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(version?.id ?: "未安装版本",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Text(if (canStart) "就绪" else "未安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canStart) LmcGreenGlow else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                // 账户行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, null, tint = LmcBlue,
                        modifier = Modifier.height(18.dp))
                    Spacer(Modifier.padding(start = 6.dp))
                    Text("${account?.name ?: "未登录"} · ${account?.type ?: "-"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(18.dp))

                // 渐变开始按钮（视觉焦点）：单个 Button，渐变画在其背景上
                val enabled = canStart
                val brush = if (enabled) {
                    Brush.horizontalGradient(listOf(LmcGreenDeep, LmcGreen))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFF2A313A), Color(0xFF333B45)))
                }
                val contentTint = if (enabled) Color(0xFF08130A) else Color(0xFF6B7684)
                androidx.compose.material3.Button(
                    onClick = onStartGame,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentTint,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(brush, RoundedCornerShape(16.dp)),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = contentTint)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(
                        if (enabled) "开始游戏" else "版本未安装",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 设备信息卡：native 层识别的 big.LITTLE 拓扑 ----
        InfoCard(title = "设备 · CPU 拓扑") {
            if (topology.isEmpty()) {
                Text("读取中…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val cores = Regex("\"coreCount\":(\\d+)").find(topology)?.groupValues?.get(1)
                val bigs = Regex("\"bigCpus\":\\[([^]]*)]").find(topology)?.groupValues?.get(1)
                Text("$cores 核 · 大核 [$bigs]", style = MaterialTheme.typography.bodyMedium,
                    color = LmcGreenGlow)
                Spacer(Modifier.height(6.dp))
                Text(topology, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 性能配置概览 ----
        InfoCard(title = "性能配置") {
            ConfigRow("渲染", renderLabel(settings.renderModeId))
            ConfigRow("GC", if (settings.gcId == 0) "ZGC 分代" else "ParallelGC")
            ConfigRow("堆", "${settings.heapSizeMb}MB（固定）")
            ConfigRow("AppCDS", if (settings.appCds) "开启" else "关闭")
            ConfigRow("主线程绑大核", if (settings.pinMainThread) "开启" else "关闭")
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun versionInstalled(versions: List<LauncherViewModel.GameVersion>, id: String) =
    versions.any { it.id == id && it.installed }

private fun renderLabel(id: Int) = when (id) {
    0 -> "Mesa Zink + Turnip"
    1 -> "原生 Vulkan"
    else -> "gl4es 保底"
}
