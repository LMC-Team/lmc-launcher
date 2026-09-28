package com.lemwoodmc.launcher.ui.versions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemwoodmc.launcher.ui.components.PageTitle
import com.lemwoodmc.launcher.ui.theme.LmcGreen
import com.lemwoodmc.launcher.ui.theme.LmcGreenGlow
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/**
 * 版本管理页：
 *  - 本地已扫描版本（files/versions/）；
 *  - Mojang 官方源远程版本列表，点击下载 client.jar + libraries（跳过 LWJGL，
 *    由 Pojav 替换件提供），完成后自动选为当前版本。
 */
@Composable
fun VersionScreen(vm: LauncherViewModel) {
    val versions by vm.versions.collectAsState()
    val selected by vm.settings.collectAsState()
    val remote by vm.remoteVersions.collectAsState()
    val remoteLoading by vm.remoteLoading.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val downloading by vm.downloadingVersion.collectAsState()
    val error by vm.downloadError.collectAsState()

    // 首次进入拉取官方清单
    LaunchedEffect(Unit) { if (remote.isEmpty()) vm.fetchRemoteVersions() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { PageTitle("版本", "选择或从官方源下载游戏版本") }
            TextButton(
                onClick = { vm.refreshVersions() },
                colors = ButtonDefaults.textButtonColors(contentColor = LmcGreen),
                modifier = Modifier.padding(end = 16.dp),
            ) { Text("刷新", fontWeight = FontWeight.Bold) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 错误提示 ----
            error?.let { msg ->
                item {
                    Text(msg, color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- 本地版本 ----
            items(versions, key = { "local:${it.id}" }) { v ->
                val isCurrent = selected.selectedVersionId == v.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.id, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) LmcGreenGlow else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (v.installed) "已安装 · ${v.path}" else "不完整（缺 client.jar）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { vm.selectVersion(v.id) },
                            enabled = v.installed,
                            colors = ButtonDefaults.textButtonColors(contentColor = LmcGreen),
                        ) { Text(if (isCurrent) "使用中" else "使用", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // ---- 下载进度 ----
            if (downloading != null) {
                item {
                    Card(shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("正在下载 ${downloading ?: ""} · ${progress?.label ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LmcGreenGlow)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    val p = progress
                                    if (p == null || p.totalBytes <= 0) 0f
                                    else (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                                },
                                color = LmcGreen,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- 远程版本列表（release 最新在前） ----
            item {
                Text(
                    "官方源（Mojang piston-meta）" + if (remoteLoading) " · 拉取中…" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            val locals = versions.map { it.id }.toSet()
            items(remote.filter { it.type == "release" }, key = { "remote:${it.id}" }) { rv ->
                val installed = rv.id in locals
                val isDownloading = downloading == rv.id
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rv.id, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                when {
                                    installed -> "已安装"
                                    isDownloading -> "下载中…"
                                    else -> rv.releaseTime.take(10)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { vm.downloadRemoteVersion(rv.id) },
                            enabled = !installed && !isDownloading && downloading == null,
                            colors = ButtonDefaults.buttonColors(containerColor = LmcGreen,
                                contentColor = MaterialTheme.colorScheme.onPrimary),
                        ) { Text(if (installed) "已装" else "下载") }
                    }
                }
            }
        }
    }
}
