/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lemwoodmc.launcher.bridge.NativeBridge
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.plugin.ApkPlugin
import com.movtery.zalithlauncher.game.plugin.PluginLoader
import com.movtery.zalithlauncher.game.plugin.appCacheIcon
import com.movtery.zalithlauncher.library.LibraryInfo
import com.movtery.zalithlauncher.library.libraryData
import com.movtery.zalithlauncher.path.URL_COMMUNITY
import com.movtery.zalithlauncher.path.URL_MCMOD
import com.movtery.zalithlauncher.path.URL_OFFICIAL_WEBSITE
import com.movtery.zalithlauncher.path.URL_PROJECT
import com.movtery.zalithlauncher.path.URL_SUPPORT
import com.movtery.zalithlauncher.path.URL_WEBLATE
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedLazyColumn
import com.movtery.zalithlauncher.ui.components.CardTitleLayout
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.theme.itemColor
import com.movtery.zalithlauncher.ui.theme.onItemColor
import kotlinx.coroutines.delay

@Composable
fun AboutInfoScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?,
    checkUpdate: () -> Unit,
    openLicense: (raw: Int) -> Unit,
    openLink: (url: String) -> Unit
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.AboutInfo, settingsScreenKey, false)
    ) { isVisible ->
        AnimatedLazyColumn(
            modifier = Modifier.fillMaxSize(),
            isVisible = isVisible,
            contentPadding = PaddingValues(all = 12.dp)
        ) { scope ->
            // ---- LMC 核心状态(zl2-shell):实时展示绑核/拓扑/温度 ----
            animatedItem(scope) { yOffset ->
                ChunkLayout(
                    modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    title = "LMC 核心状态"
                ) {
                    LmcCoreStatusPanel()
                }
            }

            animatedItem(scope) { yOffset ->
                ChunkLayout(
                    modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    title = stringResource(R.string.about_launcher_title)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ButtonIconItem(
                            icon = painterResource(R.drawable.img_launcher),
                            title = BuildKeys.LAUNCHER_NAME,
                            text = stringResource(R.string.about_launcher_version, BuildConfig.VERSION_NAME),
                            button = {
                                Button(
                                    onClick = checkUpdate
                                ) {
                                    Text(text = stringResource(R.string.upgrade_title))
                                }
                                Button(
                                    onClick = { openLink(URL_PROJECT) }
                                ) {
                                    Text(text = stringResource(R.string.about_launcher_project_link))
                                }
                                Button(
                                    onClick = { openLink(URL_OFFICIAL_WEBSITE) }
                                ) {
                                    Text(text = stringResource(R.string.about_launcher_official_website))
                                }
                            }
                        )

                        ButtonIconItem(
                            icon = painterResource(R.drawable.img_avatar_movtery),
                            title = stringResource(R.string.about_launcher_author_movtery_title),
                            text = stringResource(R.string.about_launcher_author_movtery_text, BuildKeys.LAUNCHER_NAME),
                            button = {
                                Button(
                                    onClick = { openLink(URL_SUPPORT) }
                                ) {
                                    Text(text = stringResource(R.string.about_sponsor))
                                }
                            }
                        )
                    }
                }
            }

            animatedItem(scope) { yOffset ->
                ChunkLayout(
                    modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    title = stringResource(R.string.about_acknowledgements_title)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ButtonIconItem(
                            icon = painterResource(R.drawable.img_avatar_bangbang93),
                            title = "bangbang93",
                            text = stringResource(R.string.about_acknowledgements_bangbang93_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            button = {
                                Button(
                                    onClick = { openLink("https://ifdian.net/a/bangbang93") }
                                ) {
                                    Text(text = stringResource(R.string.about_sponsor))
                                }
                            }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_launcher_fcl),
                            title = "Fold Craft Launcher",
                            text = stringResource(R.string.about_acknowledgements_fcl_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            openLicense = { openLicense(R.raw.fcl_license) },
                            openLink = { openLink("https://github.com/FCL-Team/FoldCraftLauncher") }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_launcher_hmcl),
                            title = "Hello Minecraft! Launcher",
                            text = stringResource(R.string.about_acknowledgements_hmcl_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            openLicense = { openLicense(R.raw.hmcl_license) },
                            openLink = { openLink("https://github.com/HMCL-dev/HMCL") }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_platform_mcmod),
                            title = stringResource(R.string.about_acknowledgements_mcmod),
                            text = stringResource(R.string.about_acknowledgements_mcmod_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            openLink = { openLink(URL_MCMOD) }
                        )
                        ButtonIconItem(
                            icon = painterResource(R.drawable.img_avatar_mcim),
                            title = "mcmod-info-mirror",
                            text = stringResource(R.string.about_acknowledgements_mcim_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            button = {
                                Button(
                                    onClick = { openLink("https://www.mcimirror.top/sponsor") }
                                ) {
                                    Text(text = stringResource(R.string.about_sponsor))
                                }
                            }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_launcher_pcl2),
                            title = "Plain Craft Launcher 2",
                            text = stringResource(R.string.about_acknowledgements_pcl_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            openLink = { openLink("https://github.com/Meloong-Git/PCL") }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_launcher_pojav),
                            title = "PojavLauncher",
                            text = stringResource(R.string.about_acknowledgements_pojav_text, BuildKeys.LAUNCHER_SHORT_NAME),
                            openLicense = { openLicense(R.raw.lgpl_3_license) },
                            openLink = { openLink("https://github.com/PojavLauncherTeam/PojavLauncher") }
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.ic_github),
                            title = stringResource(R.string.about_acknowledgements_github_community),
                            text = stringResource(R.string.about_acknowledgements_github_community_text),
                            openLink = { openLink(URL_COMMUNITY) },
                            useImage = false
                        )
                        LinkIconItem(
                            icon = painterResource(R.drawable.img_platform_weblate),
                            title = stringResource(R.string.about_acknowledgements_weblate_community),
                            text = stringResource(R.string.about_acknowledgements_weblate_community_text),
                            openLink = { openLink(URL_WEBLATE) }
                        )
                    }
                }
            }

            //额外依赖库板块
            animatedItem(scope) { yOffset ->
                ChunkLayout(
                    modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    title = stringResource(R.string.about_library_title)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        libraryData.forEach { info ->
                            LibraryInfoItem(info = info, openLicense = openLicense, openLink = openLink)
                        }
                    }
                }
            }

            //已加载插件板块
            PluginLoader.allPlugins.takeIf { it.isNotEmpty() }?.let { allPlugins ->
                animatedItem(scope) { yOffset ->
                    ChunkLayout(
                        modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                        title = stringResource(R.string.about_plugin_title)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            allPlugins.forEach { apkPlugin ->
                                PluginInfoItem(apkPlugin = apkPlugin)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkLayout(
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable () -> Unit
) {
    SettingsCard(
        modifier = modifier,
        position = CardPosition.Single
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CardTitleLayout {
                Text(
                    modifier = Modifier.padding(all = 16.dp),
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LinkIconItem(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    text: String,
    openLicense: (() -> Unit)? = null,
    openLink: (() -> Unit)? = null,
    color: Color = itemColor(),
    contentColor: Color = onItemColor(),
    useImage: Boolean = true
) {
    Surface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = {}
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconModifier = Modifier
                .size(34.dp)
                .clip(shape = RoundedCornerShape(6.dp))
            if (useImage) {
                Image(
                    modifier = iconModifier,
                    painter = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    modifier = iconModifier,
                    painter = icon,
                    contentDescription = null
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    modifier = Modifier.alpha(0.7f),
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row {
                openLicense?.let {
                    IconButton(
                        onClick = it
                    ) {
                        Icon(
                            modifier = Modifier.size(22.dp),
                            painter = painterResource(R.drawable.ic_copyright_outlined),
                            contentDescription = "License"
                        )
                    }
                }
                openLink?.let {
                    IconButton(
                        onClick = it
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_link),
                            contentDescription = stringResource(R.string.generic_open_link)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonIconItem(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    text: String,
    button: @Composable RowScope.() -> Unit,
    color: Color = itemColor(),
    contentColor: Color = onItemColor(),
) {
    Surface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = {}
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                modifier = Modifier
                    .size(34.dp)
                    .clip(shape = RoundedCornerShape(6.dp)),
                painter = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    modifier = Modifier.alpha(0.7f),
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            button()
        }
    }
}

@Composable
private fun PluginInfoItem(
    apkPlugin: ApkPlugin,
    modifier: Modifier = Modifier,
    color: Color = itemColor(),
    contentColor: Color = onItemColor(),
) {
    Surface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = {}
    ) {
        val context = LocalContext.current
        Row(
            modifier = Modifier
                .padding(all = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val iconFile = appCacheIcon(apkPlugin.packageName)
            if (iconFile.exists()) {
                val model = remember(context, iconFile) {
                    ImageRequest.Builder(context)
                        .data(iconFile)
                        .build()
                }
                AsyncImage(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(shape = RoundedCornerShape(8.dp)),
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    modifier = Modifier.size(34.dp),
                    painter = painterResource(R.drawable.ic_unknown_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Fit
                )
            }

            Column(
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(
                    text = apkPlugin.appName,
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.alpha(0.7f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = apkPlugin.packageName,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (apkPlugin.appVersion.isNotEmpty()) {
                        Text(
                            text = apkPlugin.appVersion,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryInfoItem(
    info: LibraryInfo,
    modifier: Modifier = Modifier,
    color: Color = itemColor(),
    contentColor: Color = onItemColor(),
    openLicense: (Int) -> Unit,
    openLink: (url: String) -> Unit
) {
    Surface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Column(
                    modifier = Modifier.alpha(0.7f)
                ) {
                    info.copyrightInfo?.let { copyrightInfo ->
                        Text(
                            text = copyrightInfo,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        modifier = Modifier.clickable(
                            onClick = {
                                openLicense(info.license.raw)
                            }
                        ),
                        text = "Licensed under the ${info.license.name}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        )
                    )
                }
            }
            IconButton(
                modifier = Modifier.align(Alignment.CenterVertically),
                onClick = {
                    openLink(info.webUrl)
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_link),
                    contentDescription = null
                )
            }
        }
    }
}

// ==================================================================
// LMC 核心状态面板(zl2-shell 集成)
// 实时读取 liblmc_core.so 的拓扑/绑核/温度状态并展示。
// ==================================================================

private data class LmcStatus(
    val loaded: Boolean,
    val bigCores: Int,
    val littleCores: Int,
    val gameProcessRunning: Boolean,
    val gameCpus: String,
    val thermalRunning: Boolean,
    val currentC: Float,
    val predictedC: Float,
    val severity: Int,
)

@Composable
private fun LmcCoreStatusPanel() {
    val context = LocalContext.current
    // 2 秒轮询一次;离开界面时协程自动取消,不做无谓唤醒
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }

    val status = remember(tick) { readLmcStatus(context) }

    Column(modifier = Modifier.fillMaxWidth()) {
        val st = status
        if (st == null || !st.loaded) {
            Text(
                text = "lmc_core 未加载(环境异常)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            LmcStatusRow("核心状态", "已加载 ✓")
            LmcStatusRow("CPU 大核数", "${st.bigCores} 个")
            LmcStatusRow("CPU 小核数", "${st.littleCores} 个")
            LmcStatusRow(
                "游戏进程",
                if (st.gameProcessRunning) "运行中(:game)" else "未运行"
            )
            LmcStatusRow(
                "游戏线程绑核",
                st.gameCpus.takeIf { it.isNotEmpty() && it != "[]" }
                    ?: "未绑定(未运行游戏)"
            )
            LmcStatusRow("温度监控", if (st.thermalRunning) "运行中" else "未运行")
            LmcStatusRow(
                "当前温度",
                if (st.currentC > 0f) "%.1f °C".format(st.currentC) else "—"
            )
            if (st.severity > 0) {
                LmcStatusRow(
                    "温度预警",
                    when (st.severity) {
                        2 -> "严重(${"%.1f".format(st.predictedC)}°C 预测)"
                        1 -> "预警(${"%.1f".format(st.predictedC)}°C 预测)"
                        else -> "提醒"
                    }
                )
            }
        }
    }
}

@Composable
private fun LmcStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 读取 lmc 核心状态;native 库异常时返回 loaded=false,绝不抛出。
 *
 * 拓扑/核心数在本进程直接读取(任何进程都一致);
 * 游戏运行态(绑核/温度)由 :game 进程写入 lmc_status.json,主进程在此读取
 * (Android:VMActivity 声明 android:process=":game",native 静态状态不跨进程)。
 */
private fun readLmcStatus(context: android.content.Context): LmcStatus? = runCatching {
    val topo = NativeBridge.nativeDetectCpuTopology()
    val localBig = NativeBridge.nativeGetBigCoreCount()
    val little = jsonIntArray(topo, "littleCpus").size

    val statusFile = java.io.File(context.filesDir, "lmc_status.json")
    val gameAlive = isGameProcessAlive()
    val fresh = statusFile.exists() &&
        (System.currentTimeMillis() - statusFile.lastModified() < 30_000L)

    if (gameAlive && fresh) {
        val j = runCatching { statusFile.readText() }.getOrDefault("")
        LmcStatus(
            loaded = true,
            bigCores = localBig,
            littleCores = little,
            gameProcessRunning = true,
            gameCpus = jsonIntArray(j, "gameCpus").joinToString(",", "[", "]"),
            thermalRunning = jsonBool(j, "running"),
            currentC = jsonFloat(j, "currentC"),
            predictedC = jsonFloat(j, "predictedC"),
            severity = jsonInt(j, "severity"),
        )
    } else {
        LmcStatus(
            loaded = true,
            bigCores = localBig,
            littleCores = little,
            gameProcessRunning = false,
            gameCpus = "",
            thermalRunning = false,
            currentC = 0f,
            predictedC = 0f,
            severity = 0,
        )
    }
}.getOrElse {
    LmcStatus(false, 0, 0, false, "", false, 0f, 0f, 0)
}

/**
 * 游戏进程(:game,与启动器主进程同 UID)是否存活。
 *
 * zl2 游戏运行在独立进程,其运行态由 Launcher 写入 lmc_status.json;
 * 但游戏退出后文件仍残留,故用 /proc 判存活,避免把陈旧数据显示为"运行中"。
 */
private fun isGameProcessAlive(): Boolean = runCatching {
    val self = android.os.Process.myPid().toString()
    java.io.File("/proc").listFiles()?.any { f ->
        f.name.isNotEmpty() && f.name[0].isDigit() && f.name != self &&
            runCatching {
                java.io.File(f, "cmdline").readText().contains(":game")
            }.getOrDefault(false)
    } ?: false
}.getOrDefault(false)

private fun jsonFloat(json: String, key: String): Float =
    Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
        .find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

private fun jsonInt(json: String, key: String): Int =
    Regex("\"$key\"\\s*:\\s*(-?[0-9]+)")
        .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

private fun jsonBool(json: String, key: String): Boolean =
    Regex("\"$key\"\\s*:\\s*(true|false)")
        .find(json)?.groupValues?.get(1) == "true"

/** 解析形如 `"key":[1,2,3]` 的整型数组 */
private fun jsonIntArray(json: String, key: String): List<Int> {
    val body = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
        .find(json)?.groupValues?.get(1) ?: return emptyList()
    return body.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
}
