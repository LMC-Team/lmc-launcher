package com.lemwoodmc.launcher.game.control

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.movtery.layer_controller.event.EventHandler
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.EmptyControlLayout
import com.movtery.layer_controller.layout.loadLayoutFromFile
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.inputmap.keycodes.ControlEventKeycode
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * zl2 控件布局引擎接入 lmc 的胶水层:
 *  - 布局文件:files/control_layouts/<selected>.json(默认从 assets/default_layout.json 释放)
 *  - 事件:ClickEvent → GLFW 按键/鼠标事件流(对齐 zl2 onKeyEvent/lwjglEvent)
 */
object LmcControlManager {
    private const val TAG = "LmcControl"
    private val layoutMutex = Mutex()

    var observableLayout by mutableStateOf<ObservableControlLayout?>(null)
        private set

    var currentControlFile: File? = null
        private set

    /** 布局目录 */
    fun layoutsDir(filesDir: File) = File(filesDir, "control_layouts").apply { mkdirs() }

    /** 无布局时从 assets 释放默认布局(zl2 checkDefaultAndRefresh 精简版) */
    fun checkDefaultAndRelease(context: android.content.Context) {
        val dir = layoutsDir(context.filesDir)
        if (dir.listFiles().isNullOrEmpty()) {
            runCatching {
                val out = File(dir, "default_layout.json")
                context.assets.open("default_layout.json").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { android.util.Log.w(TAG, "释放默认布局失败: ${it.message}") }
        }
    }

    /** 优先取已选布局文件,否则目录第一个 */
    fun pickLayoutFile(filesDir: File): File? {
        val dir = layoutsDir(filesDir)
        return dir.listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() }
    }

    /** 加载布局为 Compose 可观察形式(zl2 loadControlLayout 同构) */
    suspend fun loadControlLayout(filesDir: File, layoutFile: File? = pickLayoutFile(filesDir)) {
        layoutMutex.withLock {
            withContext(Dispatchers.Main) { observableLayout = null }
            val layout: ControlLayout = withContext(Dispatchers.IO) {
                delay(10.milliseconds)
                currentControlFile = layoutFile
                layoutFile?.let {
                    runCatching { loadLayoutFromFile(it) }.onFailure {
                        android.util.Log.w(TAG, "布局加载失败: ${it.message}")
                    }.getOrNull()
                } ?: EmptyControlLayout
            }
            withContext(Dispatchers.Main) {
                observableLayout = ObservableControlLayout(layout)
            }
        }
    }

    /** 事件处理:zl2 onKeyEvent 精简版(Key/鼠标 → CallbackBridge 事件流) */
    val eventHandler = EventHandler { event, pressed ->
        android.util.Log.i("LmcControl", "控件事件: type=${event.type} key=${event.key} pressed=$pressed")
        onKeyEvent(event, pressed)
    }

    fun onKeyEvent(event: com.movtery.layer_controller.event.ClickEvent, pressed: Boolean) {
        val key = event.key
        when (event.type) {
            com.movtery.layer_controller.event.ClickEvent.Type.Key -> {
                lwjglEvent(
                    eventKey = key,
                    isMouse = key.startsWith("GLFW_MOUSE_", ignoreCase = false),
                    isPressed = pressed,
                )
            }
            else -> return
        }
    }

    /** zl2 lwjglEvent 同构:事件键名 → GLFW 按键 */
    private fun lwjglEvent(eventKey: String, isMouse: Boolean, isPressed: Boolean) {
        val keycode = ControlEventKeycode.getKeycodeFromEvent(eventKey)?.toInt() ?: return
        if (isMouse) {
            CallbackBridge.sendMouseButton(keycode, isPressed)
        } else {
            CallbackBridge.sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isPressed)
            CallbackBridge.setModifiers(keycode, isPressed)
        }
    }
}
