package com.lemwoodmc.launcher.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Delay
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 游戏日志悬浮框（Compose）：
 *  - 拉取 MC 的 latest.log 尾部（tail 模式），按行追加；
 *  - 可展开/收起，收起时仅显示最后一行摘要；
 *  - 背景半透明黑，等宽字体，不遮挡游戏操作区。
 *
 * 数据源：GameViewModel 通过 [onReadLines] 回调按行读取设备端日志文件
 * （run-as cat 的内容由调用方预读取后传入 lines 列表）。
 */
@Composable
fun LogOverlay(
    lines: List<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) {
        // 收起态：最后一行摘要
        val last = lines.lastOrNull() ?: return
        Text(
            text = last.take(120),
            color = Color(0xCC9BE8A0),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier
                .background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        return
    }
    // 展开态：最近 N 行
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Column(
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .heightIn(max = 200.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 184.dp),
        ) {
            items(lines.size) { i ->
                Text(
                    text = lines[i],
                    color = logColor(lines[i]),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

/** 按日志级别着色：ERROR 红、WARN 黄、INFO 白、DEBUG 灰 */
private fun logColor(line: String): Color = when {
    "ERROR" in line || "FATAL" in line -> Color(0xFFEF5350)
    "WARN" in line -> Color(0xFFFFD54F)
    "DEBUG" in line -> Color(0xFF90A4AE)
    else -> Color.White
}

/** 从设备端 latest.log 读取尾部 N 行（调用方在工作线程调用，返回行列表） */
fun readLogTail(logFile: File, maxLines: Int = 60): List<String> {
    if (!logFile.isFile) return emptyList()
    return try {
        val all = logFile.readLines()
        if (all.size > maxLines) all.takeLast(maxLines) else all
    } catch (t: Throwable) {
        emptyList()
    }
}
