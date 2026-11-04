package com.example.wintrackpad

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 포인터 속도 배율 (0.4 ~ 2.5) */
    var pointerSpeed: Float
        get() = p.getFloat("pointerSpeed", 1.0f)
        set(v) = p.edit().putFloat("pointerSpeed", v).apply()

    /** 스크롤 속도 배율 (0.3 ~ 3.0) */
    var scrollSpeed: Float
        get() = p.getFloat("scrollSpeed", 1.0f)
        set(v) = p.edit().putFloat("scrollSpeed", v).apply()

    /** 맥처럼 손가락 방향 = 콘텐츠 이동 방향 */
    var naturalScroll: Boolean
        get() = p.getBoolean("naturalScroll", true)
        set(v) = p.edit().putBoolean("naturalScroll", v).apply()

    var tapToClick: Boolean
        get() = p.getBoolean("tapToClick", true)
        set(v) = p.edit().putBoolean("tapToClick", v).apply()

    var hapticFeedback: Boolean
        get() = p.getBoolean("haptic", true)
        set(v) = p.edit().putBoolean("haptic", v).apply()

    /** 마지막으로 연결한 PC의 블루투스 주소 (자동 재연결용) */
    var lastHost: String?
        get() = p.getString("lastHost", null)
        set(v) = p.edit().putString("lastHost", v).apply()
}
