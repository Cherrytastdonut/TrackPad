package com.example.wintrackpad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidLogicTest {

    // ---------------- 리포트 바이트 ----------------

    @Test
    fun mouseReportIsLittleEndianAndNineBytes() {
        val r = HidReports.mouse(HidManager.BTN_LEFT, -1, 300, 120, -120)
        assertEquals(HidReports.MOUSE_REPORT_SIZE, r.size)
        assertArrayEquals(
            byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0x2C, 0x01, 0x78, 0x00, 0x88.toByte(), 0xFF.toByte()),
            r
        )
    }

    @Test
    fun keyboardReport() {
        val r = HidReports.keyboard(HidManager.MOD_WIN, HidManager.KEY_TAB)
        assertArrayEquals(byteArrayOf(0x08, 0, 0x2B, 0, 0, 0, 0, 0), r)
    }

    @Test
    fun featureParsingWithAndWithoutReportId() {
        assertEquals(5, HidReports.parseFeature(byteArrayOf(1, 5), 1))   // ID 포함
        assertEquals(5, HidReports.parseFeature(byteArrayOf(5), 1))      // ID 없음
        assertEquals(0, HidReports.parseFeature(byteArrayOf(1, 0), 1))
        assertNull(HidReports.parseFeature(byteArrayOf(), 1))
    }

    // ---------------- 휠 누적 ----------------

    @Test
    fun hiResPassesUnitsThrough() {
        val a = ScrollAccumulator()
        assertEquals(3, a.add(3.4f, hiRes = true))
        assertEquals(1, a.add(0.7f, hiRes = true)) // 0.4 + 0.7 = 1.1
    }

    @Test
    fun lowResAccumulatesToWholeNotches() {
        val a = ScrollAccumulator()
        assertEquals(0, a.add(100f, hiRes = false))
        assertEquals(1, a.add(30f, hiRes = false))  // 130 → 1칸, 10 남음
        assertEquals(0, a.add(100f, hiRes = false)) // 110
        assertEquals(1, a.add(10f, hiRes = false))  // 120
    }

    @Test
    fun lowResNegativeAndReset() {
        val a = ScrollAccumulator()
        assertEquals(-2, a.add(-250f, hiRes = false))
        a.reset()
        assertEquals(0, a.add(-100f, hiRes = false))
    }

    // ---------------- HID 디스크립터 구조 ----------------

    /** 디스크립터를 파싱해 Report ID별 Input/Feature 비트 수를 센다. */
    private fun reportBits(desc: ByteArray): Map<Pair<Int, String>, Int> {
        val bits = HashMap<Pair<Int, String>, Int>()
        var i = 0
        var reportId = 0
        var size = 0
        var count = 0
        var depth = 0
        val stack = ArrayDeque<Pair<Int, Int>>()
        while (i < desc.size) {
            val prefix = desc[i].toInt() and 0xFF
            val len = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var value = 0
            for (k in 0 until len) value = value or ((desc[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            when (prefix and 0xFC) {
                0x84 -> reportId = value
                0x74 -> size = value
                0x94 -> count = value
                0xA4 -> stack.addLast(size to count)
                0xB4 -> stack.removeLast().let { size = it.first; count = it.second }
                0x80 -> bits.merge(reportId to "in", size * count, Int::plus)
                0xB0 -> bits.merge(reportId to "feature", size * count, Int::plus)
                0xA0 -> depth++
                0xC0 -> depth--
            }
            i += 1 + len
        }
        assertEquals("컬렉션 열고 닫기 균형", 0, depth)
        return bits
    }

    @Test
    fun descriptorMatchesReportSizes() {
        val bits = reportBits(HidManager.DESCRIPTOR)
        assertEquals(HidReports.MOUSE_REPORT_SIZE * 8, bits[HidManager.ID_MOUSE to "in"])
        assertEquals(HidReports.KEYBOARD_REPORT_SIZE * 8, bits[HidManager.ID_KEYBOARD to "in"])
        assertEquals(8, bits[HidManager.ID_MOUSE to "feature"])
    }
}
