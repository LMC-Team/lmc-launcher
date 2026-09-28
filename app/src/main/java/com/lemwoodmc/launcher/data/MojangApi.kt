package com.lemwoodmc.launcher.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mojang 官方版本源（piston-meta）下载器：
 *   版本清单 → 版本 JSON → client.jar + 纯 Java libraries。
 *
 * 约定（骨架版，真实验证路径）：
 *  - 跳过 org.lwjgl.* 全家：官方 LWJGL 没有 Android/arm64 natives，
 *    由 Pojav 生态的 lwjgl3-android 替换件提供（后续阶段）；
 *  - 跳过带 native classifier 的库；
 *  - assets 资源文件不在本阶段（启动到 LWJGL 断点不需要）。
 *
 * 落盘布局（与 GameViewModel 的 classpath 扫描约定一致）：
 *   files/versions/<id>/client.jar
 *   files/versions/<id>/libs/<相对路径>.jar
 *   files/versions/<id>/version.json
 */
object MojangApi {

    private const val TAG = "LMC"

    /** 版本清单里的一条远程版本 */
    data class RemoteVersion(val id: String, val url: String, val type: String, val releaseTime: String)

    /** 单个下载任务（用于进度展示） */
    data class DownloadProgress(val label: String, val doneBytes: Long, val totalBytes: Long, val finished: Boolean)

    /** 解析出的版本元数据 */
    data class VersionMeta(
        val mainClass: String,
        val clientUrl: String,
        val libs: List<Pair<String, String>>, // url→相对路径
        val assetIndexId: String?,            // 资源索引名（MC --assetsIndex 参数）
        val assetIndexUrl: String?,           // 资源索引 json 下载地址（很小，随版本一并下载）
    )

    // ------------------------------------------------------------------
    // HTTP 基础
    // ------------------------------------------------------------------

    private fun httpGet(urlStr: String): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

    private fun String.httpText(): String {
        val conn = httpGet(this)
        return conn.inputStream.use { it.readBytes().decodeToString() }.also { conn.disconnect() }
    }

    // ------------------------------------------------------------------
    // 版本清单
    // ------------------------------------------------------------------

    /** 拉取官方版本清单（只保留 release 与 snapshot） */
    suspend fun fetchManifest(): List<RemoteVersion> = withContext(Dispatchers.IO) {
        val json = JSONObject("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json".httpText())
        json.getJSONArray("versions").let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val v = arr.getJSONObject(i)
                val type = v.getString("type")
                if (type == "release" || type == "snapshot") {
                    RemoteVersion(v.getString("id"), v.getString("url"), type, v.optString("releaseTime"))
                } else null
            }
        }
    }

    // ------------------------------------------------------------------
    // 版本元数据
    // ------------------------------------------------------------------

    /** 拉取并解析版本 JSON：主类 + client.jar 下载地址 + 允许的 libraries + assets 索引 */
    suspend fun fetchVersionMeta(versionUrl: String): VersionMeta = withContext(Dispatchers.IO) {
        val json = JSONObject(versionUrl.httpText())
        val mainClass = json.getString("mainClass")
        val clientUrl = json.getJSONObject("downloads").getJSONObject("client").getString("url")
        val assetIndex = json.optJSONObject("assetIndex")
        val assetIndexId = assetIndex?.optString("id")
        val assetIndexUrl = assetIndex?.optString("url")

        val libs = mutableListOf<Pair<String, String>>()
        val libArr = json.getJSONArray("libraries")
        for (i in 0 until libArr.length()) {
            val lib = libArr.getJSONObject(i)
            if (!rulesAllow(lib.optJSONObject("rules"))) continue
            val name = lib.getString("name")
            // LWJGL 的 natives 分类器跳过：官方无 Android/arm64 natives，
            // 由 Pojav 生态的 bionic 构建替换（GLFW 类与接口 jar 保留）
            if (name.contains(":natives-")) continue
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val url = artifact.getString("url")
            if (url.isEmpty()) continue
            val relPath = artifact.getString("path") // 如 com/google/gson/gson/2.10/gson-2.10.jar
            libs.add(url to relPath)
        }
        VersionMeta(mainClass, clientUrl, libs, assetIndexId, assetIndexUrl)
    }

    /** Mojang rules 判定：无 rules = 允许；有则要求 os.name == "linux" 被允许 */
    private fun rulesAllow(rules: JSONObject?): Boolean {
        if (rules == null) return true
        val arr = rules.getJSONArray("rules")
        var allowed = false
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val os = r.optJSONObject("os")
            val matches = os == null || os.optString("name") == "linux"
            if (matches) allowed = r.getString("action") == "allow"
        }
        return allowed
    }

    // ------------------------------------------------------------------
    // 下载落盘
    // ------------------------------------------------------------------

    /** 下载 URL 到目标文件，经 onProgress 汇报进度（字节级节流） */
    private suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".part")
            val conn = httpGet(url)
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { // 每 512KB 汇报一次
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
            conn.disconnect()
            if (total > 0 && tmp.length() != total) {
                tmp.delete()
                throw IllegalStateException("下载不完整: $url (${tmp.length()}/$total)")
            }
            tmp.renameTo(dest)
            Log.i(TAG, "下载完成: ${dest.name} (${dest.length()} 字节)")
        }

    /**
     * 完整下载一个版本：client.jar + 全部允许的 libraries。
     * onProgress 的 label 形如 "gson-2.10.jar" / "client.jar"。
     */
    suspend fun downloadVersion(
        meta: VersionMeta,
        versionDir: File,
        onProgress: (DownloadProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        versionDir.mkdirs()

        // 1. client.jar（大文件）
        val clientJar = File(versionDir, "client.jar")
        if (!clientJar.isFile) {
            download(meta.clientUrl, clientJar) { d, t ->
                onProgress(DownloadProgress("client.jar", d, t, false))
            }
        }
        onProgress(DownloadProgress("client.jar", 1, 1, true))

        // 2. assets 索引 json（很小；资源本体 objects 按需另行下载）
        if (meta.assetIndexUrl != null && meta.assetIndexId != null) {
            val idx = File(versionDir, "assets/indexes/${meta.assetIndexId}.json")
            if (!idx.isFile) download(meta.assetIndexUrl, idx) { d, t ->
                onProgress(DownloadProgress("assets/indexes/${meta.assetIndexId}.json", d, t, false))
            }
            onProgress(DownloadProgress("assets index", 1, 1, true))
        }

        // 3. libraries（纯 Java jar，跳过已存在的）
        meta.libs.forEachIndexed { idx, (url, relPath) ->
            val dest = File(versionDir, "libs/$relPath")
            if (!dest.isFile) {
                download(url, dest) { d, t ->
                    onProgress(DownloadProgress(relPath.substringAfterLast('/'), d, t, false))
                }
            }
            onProgress(DownloadProgress(relPath.substringAfterLast('/'), 1, 1, true))
        }

        Log.i(TAG, "版本下载完成：libs=${meta.libs.size} 个")
    }
}
