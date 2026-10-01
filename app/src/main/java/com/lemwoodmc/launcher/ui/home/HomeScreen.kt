package com.lemwoodmc.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.lemwoodmc.launcher.ui.components.ConfigRow
import com.lemwoodmc.launcher.ui.components.InfoCard
import com.lemwoodmc.launcher.ui.theme.LmcBlue
import com.lemwoodmc.launcher.ui.theme.LmcGreen
import com.lemwoodmc.launcher.ui.theme.LmcGreenDeep
import com.lemwoodmc.launcher.ui.theme.LmcGreenGlow
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/**
 * 主页 —— 启动器视觉核心。
 * 横屏双栏（M3 adaptive）：
 *   左栏（焦点）：品牌 Hero + 启动卡（版本/账户 + 渐变开始按钮）；
 *   右栏（参考）：设备 CPU 拓扑 + 性能配置概览。
 * 窄屏（<600dp）自动回落为纵向单列。
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ---- 左栏：品牌 + 启动操作（视觉焦点，占大头）----
                Column(
                    Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Hero()
                    Spacer(Modifier.height(20.dp))
                    LaunchCard(version?.id, account?.name, account?.type, canStart, onStartGame)
                }
                // ---- 右栏：信息参考 ----
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    DeviceCard(topology)
                    Spacer(Modifier.height(16.dp))
                    PerfCard(settings.renderModeId, settings.gcId, settings.heapSizeMb,
                        settings.appCds, settings.pinMainThread)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(28.dp))
                Hero()
                Spacer(Modifier.height(24.dp))
                LaunchCard(version?.id, account?.name, account?.type, canStart, onStartGame)
                Spacer(Modifier.height(16.dp))
                DeviceCard(topology)
                Spacer(Modifier.height(16.dp))
                PerfCard(settings.renderModeId, settings.gcId, settings.heapSizeMb,
                    settings.appCds, settings.pinMainThread)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 品牌 Hero 标题区 */
@Composable
private fun Hero() {
    Text("LMC", fontSize = 40.sp, fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onBackground)
    Text("LAUNCHER", fontSize = 40.sp, fontWeight = FontWeight.Black,
        letterSpacing = 6.sp,
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
        color = LmcGreen)
    Spacer(Modifier.height(4.dp))
    Text("高性能 Minecraft Java 版启动器 · ARM64 / Vulkan 原生后端",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 启动卡：版本 / 账户 + 渐变开始按钮 */
@Composable
private fun LaunchCard(
    versionId: String?,
    accountName: String?,
    accountType: String?,
    canStart: Boolean,
    onStartGame: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            // 版本选择行
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = LmcGreenGlow,
                    modifier = Modifier.height(18.dp))
                Spacer(Modifier.padding(start = 6.dp))
                Text(versionId ?: "未安装版本",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                Text(if (canStart) "就绪" else "未安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canStart) LmcGreenGlow else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            // 账户行
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, tint = LmcBlue,
                    modifier = Modifier.height(18.dp))
                Spacer(Modifier.padding(start = 6.dp))
                Text("${accountName ?: "未登录"} · ${accountType ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(18.dp))

            // 渐变开始按钮（视觉焦点）：单个 Button，渐变画在其背景上
            val brush = if (canStart) {
                Brush.horizontalGradient(listOf(LmcGreenDeep, LmcGreen))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFF2A313A), Color(0xFF333B45)))
            }
            val contentTint = if (canStart) Color(0xFF08130A) else Color(0xFF6B7684)
            androidx.compose.material3.Button(
                onClick = onStartGame,
                enabled = canStart,
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
                    if (canStart) "开始游戏" else "版本未安装",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 设备信息卡：native 层识别的 big.LITTLE 拓扑 */
@Composable
private fun DeviceCard(topology: String) {
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
}

/** 性能配置概览卡 */
@Composable
private fun PerfCard(
    renderModeId: Int,
    gcId: Int,
    heapSizeMb: Int,
    appCds: Boolean,
    pinMainThread: Boolean,
) {
    InfoCard(title = "性能配置") {
        ConfigRow("渲染", renderLabel(renderModeId))
        ConfigRow("GC", if (gcId == 0) "ZGC 分代" else "ParallelGC")
        ConfigRow("堆", "${heapSizeMb}MB（固定）")
        ConfigRow("AppCDS", if (appCds) "开启" else "关闭")
        ConfigRow("主线程绑大核", if (pinMainThread) "开启" else "关闭")
    }
}

private fun versionInstalled(versions: List<LauncherViewModel.GameVersion>, id: String) =
    versions.any { it.id == id && it.installed }

private fun renderLabel(id: Int) = when (id) {
    0 -> "Mesa Zink + Turnip"
    1 -> "原生 Vulkan"
    else -> "gl4es 保底"
}
