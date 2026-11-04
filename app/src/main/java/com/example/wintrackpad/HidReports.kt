package com.example.wintrackpad

/**
 * Android에 의존하지 않는 순수 로직 (단위 테스트 대상).
 */
object HidReports {
    const val MOUSE_REPORT_SIZE = 9
    const val KEYBOARD_REPORT_SIZE = 8

    /** [버튼, X lo/hi, Y lo/hi, 휠 lo/hi, 가로휠 lo/hi] */
    fun mouse(buttons: Int, x: Int, y: Int, wheel: Int, pan: Int) = byteArrayOf(
        buttons.toByte(),
        (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
        (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
        (wheel and 0xFF).toByte(), ((wheel shr 8) and 0xFF).toByte(),
        (pan and 0xFF).toByte(), ((pan shr 8) and 0xFF).toByte()
    )

    /** [수정키, 0, 키, 0, 0, 0, 0, 0] */
    fun keyboard(mods: Int, key: Int) =
        byteArrayOf(mods.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)

    /**
     * 호스트가 보낸 Feature(SET_REPORT) 데이터에서 배율 값을 꺼낸다.
     * 스택에 따라 앞에 Report ID가 붙기도 하고 안 붙기도 한다.
     */
    fun parseFeature(data: ByteArray, reportId: Int): Int? {
        if (data.isEmpty()) return null
        val v = if (data.size >= 2 && data[0].toInt() == reportId) data[1] else data[0]
        return v.toInt() and 0x0F
    }
}

/**
 * 1/120칸 단위 스크롤 양을 받아, 고해상도 모드면 그대로, 아니면 정수 칸으로 바꿔 보낸다.
 * 남는 소수는 다음 호출로 넘긴다.
 */
class ScrollAccumulator {
    private var acc = 0f

    fun add(units: Float, hiRes: Boolean): Int {
        acc += units
        val div = if (hiRes) 1 else UNITS_PER_NOTCH
        val out = (acc / div).toInt()
        acc -= out * div
        return out
    }

    fun reset() {
        acc = 0f
    }

    companion object {
        const val UNITS_PER_NOTCH = 120
    }
}
