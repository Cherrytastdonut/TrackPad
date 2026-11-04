package com.example.wintrackpad

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

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
        get() = bool("naturalScroll", true)
        set(v) = putBool("naturalScroll", v)

    /** 관성 스크롤 */
    var inertia: Boolean
        get() = bool("inertia", true)
        set(v) = putBool("inertia", v)

    /** 1손가락 탭 = 왼쪽 클릭 */
    var tapToClick: Boolean
        get() = bool("tapToClick", true)
        set(v) = putBool("tapToClick", v)

    /** 탭 후 바로 눌러 끌기 = 드래그 */
    var tapDrag: Boolean
        get() = bool("tapDrag", true)
        set(v) = putBool("tapDrag", v)

    /** 2손가락 탭 = 오른쪽 클릭 */
    var twoFingerRightClick: Boolean
        get() = bool("twoFingerRightClick", true)
        set(v) = putBool("twoFingerRightClick", v)

    /** 3손가락 탭 = 가운데 클릭 */
    var threeFingerMiddleClick: Boolean
        get() = bool("threeFingerMiddleClick", true)
        set(v) = putBool("threeFingerMiddleClick", v)

    /** 화면 아래 큰 클릭 버튼 줄 (탭 클릭을 끄면 자동으로 표시) */
    var showClickButtons: Boolean
        get() = bool("showClickButtons", false)
        set(v) = putBool("showClickButtons", v)

    /** 실제로 버튼 줄을 보여줄지: 탭 클릭이 꺼져 있으면 클릭 수단이 없어지므로 강제로 표시 */
    val clickButtonsVisible: Boolean get() = showClickButtons || !tapToClick

    var hapticFeedback: Boolean
        get() = bool("haptic", true)
        set(v) = putBool("haptic", v)

    /** true = Windows 11 (알림 센터 Win+N), false = Windows 10 (알림 센터 Win+A) */
    var windows11: Boolean
        get() = bool("windows11", true)
        set(v) = putBool("windows11", v)

    /** 마지막으로 연결한 PC의 블루투스 주소 (자동 재연결용) */
    var lastHost: String?
        get() = p.getString("lastHost", null)
        set(v) = p.edit().putString("lastHost", v).apply()
}
