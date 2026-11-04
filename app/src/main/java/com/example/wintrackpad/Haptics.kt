package com.example.wintrackpad

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 동작별로 구분되는 진동. View.performHapticFeedback을 쓰므로
 * Android의 '터치 진동' 시스템 설정을 그대로 따른다(별도 권한 불필요).
 */
enum class Haptic { CLICK, DRAG_START, DRAG_END, GESTURE, CONFIRM, ERROR }

object Haptics {
    fun perform(view: View, kind: Haptic, enabled: Boolean) {
        if (!enabled) return
        val c = when (kind) {
            Haptic.CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.DRAG_START -> HapticFeedbackConstants.LONG_PRESS
            Haptic.DRAG_END -> HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
            Haptic.GESTURE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_START
                else HapticFeedbackConstants.CLOCK_TICK
            Haptic.CONFIRM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.LONG_PRESS
            Haptic.ERROR ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
                else HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(c)
    }
}
