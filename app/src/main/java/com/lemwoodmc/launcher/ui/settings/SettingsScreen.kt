package com.lemwoodmc.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemwoodmc.launcher.ui.components.ConfigRow
import com.lemwoodmc.launcher.ui.components.InfoCard
import com.lemwoodmc.launcher.ui.components.PageTitle
import com.lemwoodmc.launcher.ui.theme.LmcGreen
import com.lemwoodmc.launcher.ui.theme.LmcGreenGlow
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/**
 * 设置页：JVM / 渲染 / 调度 / 控制层 分组卡片。
 * 所有修改即时写入 DataStore，启动时由 GameViewModel 汇编为 native 启动配置。
 */
@Composable
fun SettingsScreen() {
    val vm: LauncherViewModel = viewModel()
    val s by vm.settings.collectAsState()
    val topology by vm.cpuTopology.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PageTitle("设置", "即时生效，下次启动游戏时应用")

        // ---------- JVM 运行时层 ----------
        InfoCard("JVM 运行时") {
            Text("堆大小：${s.heapSizeMb}MB（固定 Xms = Xmx）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = s.heapSizeMb.toFloat(),
                onValueChange = { vm.setHeapSize((it / 256).toInt() * 256) },
                valueRange = 1024f..8192f,
                colors = SliderDefaults.colors(
                    thumbColor = LmcGreen,
                    activeTrackColor = LmcGreen,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.gcId == 0, onClick = { vm.setGc(0) },
                    label = { Text("ZGC 分代") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LmcGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                FilterChip(
                    selected = s.gcId == 1, onClick = { vm.setGc(1) },
                    label = { Text("ParallelGC") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LmcGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            SettingSwitch("AppCDS 类存档（加速启动）", s.appCds) { vm.setAppCds(it) }
            SettingSwitch("GraalVM Native Image 极致模式（预留）", s.graalNativeImage) { vm.setGraalNative(it) }
            Spacer(Modifier.height(10.dp))
            // 游戏主类（高级）：vanilla 默认 net.minecraft.client.main.Main，测试/模组场景可自定义
            var mainClassText by androidx.compose.runtime.remember(s.mainClass) {
                androidx.compose.runtime.mutableStateOf(s.mainClass)
            }
            androidx.compose.material3.OutlinedTextField(
                value = mainClassText,
                onValueChange = { mainClassText = it; vm.setMainClass(it) }, // 即时写入 DataStore
                label = { Text("游戏主类") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "修改后即时保存；类必须存在于 classpath 中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        GroupGap()

        // ---------- 渲染层 ----------
        InfoCard("渲染层") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.renderModeId == 0, onClick = { vm.setRenderMode(0) },
                    label = { Text("Zink 主路径") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LmcGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                FilterChip(
                    selected = s.renderModeId == 1, onClick = { vm.setRenderMode(1) },
                    label = { Text("原生 Vulkan") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LmcGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                FilterChip(
                    selected = s.renderModeId == 2, onClick = { vm.setRenderMode(2) },
                    label = { Text("gl4es") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LmcGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingSwitch("Vulkan pipeline cache 持久化", s.vulkanPipelineCache) { vm.setPipelineCache(it) }
        }
        GroupGap()

        // ---------- CPU 调度层 ----------
        InfoCard("CPU 调度") {
            SettingSwitch("游戏主线程绑定大核", s.pinMainThread) { vm.setPinMainThread(it) }
            Spacer(Modifier.height(6.dp))
            val summary = if (topology.isNotEmpty()) {
                val cores = Regex("\"coreCount\":(\\d+)").find(topology)?.groupValues?.get(1)
                val bigs = Regex("\"bigCpus\":\\[([^]]*)]").find(topology)?.groupValues?.get(1)
                "$cores 核 · 大核 [$bigs]"
            } else "拓扑读取中…"
            ConfigRow("native 识别结果", summary, valueColor = LmcGreenGlow)
        }
        GroupGap()

        // ---------- 悬浮控制层 ----------
        InfoCard("游戏内控制层") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("不透明度", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${(s.controlOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium, color = LmcGreenGlow)
            }
            Slider(
                value = s.controlOpacity,
                onValueChange = { vm.setControlOpacity(it) },
                valueRange = 0.2f..1f,
                colors = SliderDefaults.colors(thumbColor = LmcGreen, activeTrackColor = LmcGreen),
            )
            SettingSwitch("进入游戏时显示控制层（可随时隐藏）", s.controlVisible) { vm.setControlVisible(it) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupGap() { Spacer(Modifier.height(16.dp)) }

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = LmcGreen,
            ),
        )
    }
}
