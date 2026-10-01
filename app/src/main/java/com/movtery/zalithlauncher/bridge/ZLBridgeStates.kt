package com.movtery.zalithlauncher.bridge

import androidx.compose.ui.input.pointer.PointerIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.view.PointerIcon as NativePointerIcon

/**
 * Zalith 2 桥状态机（对齐 ZalithLauncher ZLBridgeStates，供游戏画面
 * 读取指针模式/形状变化；窗口变更 key 供重组刷新）。
 */
object ZLBridgeStates {

    private val _cursorMode = MutableStateFlow(CURSOR_ENABLED)
    /** 状态：指针模式（启用、禁用） */
    val cursorMode = _cursorMode.asStateFlow()

    @JvmStatic
    fun changeCursorMode(mode: Int) {
        require(mode in 0..1)
        this._cursorMode.update { mode }
    }

    private val _cursorShape = MutableStateFlow(CursorShape.Arrow)
    /** 状态：指针形状 */
    val cursorShape = _cursorShape.asStateFlow()

    @JvmStatic
    fun changeCursorShape(shape: CursorShape) {
        _cursorShape.update { shape }
    }

    @JvmStatic
    private val _windowChangeKey = MutableStateFlow(false)
    /** 状态：窗口变更刷新 key */
    val windowChangeKey = _windowChangeKey.asStateFlow()

    fun onWindowChange() {
        this._windowChangeKey.update { old -> old.not() }
    }
}

/** 指针:启用 */
const val CURSOR_ENABLED = 1
/** 指针:禁用 */
const val CURSOR_DISABLED = 0

/**
 * 指针形状（箭头、输入、手型等，与 GLFW 光标形状一一对应）
 */
enum class CursorShape(
    val composeIcon: PointerIcon
) {
    Arrow(PointerIcon.Default),
    IBeam(PointerIcon.Text),
    Hand(PointerIcon.Hand),
    CrossHair(PointerIcon.Crosshair),
    ResizeNS(PointerIcon(NativePointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)),
    ResizeEW(PointerIcon(NativePointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)),
    ResizeAll(PointerIcon(NativePointerIcon.TYPE_ALL_SCROLL)),
    NotAllowed(PointerIcon(NativePointerIcon.TYPE_NO_DROP))
}
