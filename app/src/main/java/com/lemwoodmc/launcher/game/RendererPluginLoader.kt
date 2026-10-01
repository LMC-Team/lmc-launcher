package com.lemwoodmc.launcher.game

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * FCL/Zalith 渲染器插件协议（对齐 ZalithLauncher RendererPluginManager，裁剪版）。
 *
 * 插件 APK 的 AndroidManifest meta-data 契约：
 *   fclPlugin=true（或 zalithRendererPlugin=true）
 *   renderer   = <id>:<glName>:<eglName>     glName → -Dorg.lwjgl.opengl.libname
 *   pojavEnv   = KEY=VAL:KEY=VAL:...          POJAV_RENDERER 特殊（覆盖 id）、
 *                                             DLOPEN 特殊（逗号分隔的 dlopen 列表）、
 *                                             其余原样进环境变量
 *
 * 典型插件：MobileGlues（com.fcl.plugin.mobileglues）——
 *   renderer = MobileGlues:libmobileglues.so:libmobileglues.so
 *   pojavEnv = LIBGL_ES=3:POJAV_RENDERER=opengles3:POJAVEXEC_EGL=libmobileglues.so:
 *              LIBGL_EGL=libmobileglues.so:MG_COUNT_LAUNCH=1
 */
object RendererPluginLoader {

    data class Plugin(
        val packageName: String,
        val rendererId: String,
        val glName: String,
        val eglName: String,
        val nativeLibraryDir: String,
        val env: Map<String, String>,
        val dlopen: List<String>,
    )

    fun scan(context: Context): List<Plugin> {
        val pm = context.packageManager
        val result = mutableListOf<Plugin>()
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        for (app in apps) {
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val md = app.metaData ?: continue
            if (!md.getBoolean("fclPlugin", false) && !md.getBoolean("zalithRendererPlugin", false)) continue
            val renderer = md.getString("renderer") ?: continue
            val pojavEnv = md.getString("pojavEnv") ?: continue
            val parts = renderer.split(":")
            if (parts.size < 3) continue

            var id = parts[0]
            val envMap = mutableMapOf<String, String>()
            val dlopen = mutableListOf<String>()
            for (entry in pojavEnv.split(":")) {
                val kv = entry.split("=", limit = 2)
                if (kv.size != 2) continue
                when (kv[0]) {
                    "POJAV_RENDERER" -> id = kv[1]
                    "DLOPEN" -> dlopen += kv[1].split(",")
                    else -> envMap[kv[0]] = kv[1]
                }
            }
            result += Plugin(
                packageName = app.packageName,
                rendererId = id,
                glName = parts[1],
                eglName = parts[2],
                nativeLibraryDir = app.nativeLibraryDir,
                env = envMap,
                dlopen = dlopen,
            )
        }
        return result
    }

    /** 取指定插件（按包名），无则取扫描到的第一个。 */
    fun pick(context: Context, preferredPackage: String? = null): Plugin? {
        val list = scan(context)
        return preferredPackage?.let { p -> list.find { it.packageName == p } } ?: list.firstOrNull()
    }
}
