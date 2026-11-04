package com.example.wintrackpad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/**
 * 폰을 블루투스 HID 장치(마우스 + 키보드 복합 장치)로 등록하고 리포트를 보낸다.
 * Windows는 이 폰을 일반 블루투스 마우스/키보드로 인식하므로 PC에 별도 프로그램이 필요 없다.
 *
 * 마우스 리포트(ID 1, 9바이트): [버튼, X(16bit), Y(16bit), 휠(16bit), 가로휠(16bit)]
 * 키보드 리포트(ID 2, 8바이트): [수정키, 0, 키1..키6]
 *
 * 휠에는 "Resolution Multiplier"(고해상도 스크롤)를 넣어 두었다. Windows가 이를 켜면
 * 휠 1단위 = 1/120 칸이 되어 맥처럼 부드럽게 스크롤된다. 켜지 않으면 자동으로 칸 단위로 보낸다.
 */
@SuppressLint("MissingPermission")
class HidManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onHidStatus(message: String)
        fun onHidReady()
        fun onHostConnected(device: BluetoothDevice)
        fun onHostDisconnected()
    }

    companion object {
        const val ID_MOUSE = 1
        const val ID_KEYBOARD = 2

        const val BTN_LEFT = 0x01
        const val BTN_RIGHT = 0x02
        const val BTN_MIDDLE = 0x04

        const val MOD_CTRL = 0x01
        const val MOD_SHIFT = 0x02
        const val MOD_ALT = 0x04
        const val MOD_WIN = 0x08

        const val KEY_A = 0x04
        const val KEY_D = 0x07
        const val KEY_TAB = 0x2B
        const val KEY_RIGHT = 0x4F
        const val KEY_LEFT = 0x50

        /** 휠 1칸(notch)에 해당하는 고해상도 단위 */
        const val UNITS_PER_NOTCH = 120

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        val DESCRIPTOR: ByteArray = bytes(
            // ---------- 마우스 (Report ID 1) ----------
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x02,             // Usage (Mouse)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_MOUSE,         //   Report ID (1)
            0x09, 0x01,             //   Usage (Pointer)
            0xA1, 0x00,             //   Collection (Physical)
            // 버튼 3개
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03, // 패딩 5bit
            // X, Y (16bit 상대값)
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
            // 세로 휠 + Resolution Multiplier
            0xA1, 0x02,             //     Collection (Logical)
            0x09, 0x48,             //       Usage (Resolution Multiplier)
            0x15, 0x00, 0x25, 0x01, 0x35, 0x01, 0x45, 0x78, // 0..1 → x1..x120
            0x75, 0x02, 0x95, 0x01, 0xB1, 0x02, // Feature 2bit
            0x35, 0x00, 0x45, 0x00,
            0x09, 0x38,             //       Usage (Wheel)
            0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x06,
            0xC0,                   //     End Collection
            // 가로 휠(AC Pan) + Resolution Multiplier
            0xA1, 0x02,             //     Collection (Logical)
            0x09, 0x48,
            0x15, 0x00, 0x25, 0x01, 0x35, 0x01, 0x45, 0x78,
            0x75, 0x02, 0x95, 0x01, 0xB1, 0x02,
            0x35, 0x00, 0x45, 0x00,
            0x05, 0x0C, 0x0A, 0x38, 0x02, // Usage Page (Consumer), Usage (AC Pan)
            0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x06,
            0xC0,                   //     End Collection
            0x15, 0x00, 0x25, 0x01, 0x75, 0x04, 0x95, 0x01, 0xB1, 0x03, // Feature 패딩 4bit
            0xC0,                   //   End Collection (Physical)
            0xC0,                   // End Collection (Application)

            // ---------- 키보드 (Report ID 2) ----------
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x85, ID_KEYBOARD,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02, // 수정키 8bit
            0x95, 0x01, 0x75, 0x08, 0x81, 0x03, // 예약 1byte
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, // 키 6개
            0xC0
        )
    }

    val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile var host: BluetoothDevice? = null
        private set
    var isRegistered = false
        private set

    private val senderThread = HandlerThread("hid-sender").also { it.start() }
    private val sender = Handler(senderThread.looper)

    /** 호스트가 SET_REPORT(Feature)로 설정하는 값. bit0-1: 세로휠 배율, bit2-3: 가로휠 배율 */
    @Volatile private var featureValue = 0
    private val hiResWheel get() = (featureValue and 0x03) != 0
    private val hiResPan get() = ((featureValue shr 2) and 0x03) != 0

    // ---- 보낼 마우스 이동량 누적 (lock 보호) ----
    private val lock = Any()
    private var pdx = 0
    private var pdy = 0
    private var pWheel = 0
    private var pPan = 0
    private var buttons = 0
    private var epoch = 0
    private var flushPosted = false

    // 메인 스레드 전용: 칸 단위로 보낼 때의 소수점 잔여
    private var wheelAcc = 0f
    private var panAcc = 0f

    // ------------------------------------------------------------------
    // 시작 / 종료 / 연결
    // ------------------------------------------------------------------

    fun start(): Boolean {
        val a = adapter ?: return false
        return a.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        releaseAll()
        val h = hid
        sender.post {
            try {
                h?.unregisterApp()
            } catch (_: Exception) {
            }
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
            } catch (_: Exception) {
            }
            senderThread.quitSafely()
        }
        hid = null
        host = null
    }

    fun connect(device: BluetoothDevice): Boolean = try {
        hid?.connect(device) ?: false
    } catch (e: Exception) {
        false
    }

    fun disconnect() {
        releaseAll()
        val d = host ?: return
        try {
            hid?.disconnect(d)
        } catch (_: Exception) {
        }
    }

    fun deviceName(d: BluetoothDevice?): String = try {
        d?.name ?: d?.address ?: "알 수 없는 기기"
    } catch (e: SecurityException) {
        d?.address ?: "알 수 없는 기기"
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Phone Trackpad",
                "Android phone as a Windows trackpad",
                "WinTrackpad",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                DESCRIPTOR
            )
            val ok = try {
                h.registerApp(sdp, null, null, context.mainExecutor, hidCallback)
            } catch (e: Exception) {
                false
            }
            if (!ok) listener.onHidStatus("HID 등록 요청이 거부되었습니다. 다른 블루투스 키보드/마우스 앱을 종료한 뒤 다시 시도하세요.")
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            isRegistered = false
            host = null
            listener.onHidStatus("블루투스 HID 서비스가 중단되었습니다.")
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) listener.onHidReady()
            else listener.onHidStatus("HID 등록이 해제되었습니다.")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    synchronized(lock) {
                        pdx = 0; pdy = 0; pWheel = 0; pPan = 0; buttons = 0
                    }
                    wheelAcc = 0f; panAcc = 0f
                    listener.onHostConnected(device)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    listener.onHidStatus("연결 중: ${deviceName(device)}")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val cur = host
                    if (cur == null || cur.address == device.address) {
                        host = null
                        featureValue = 0
                        listener.onHostDisconnected()
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            val rid = id.toInt()
            try {
                when {
                    type == BluetoothHidDevice.REPORT_TYPE_FEATURE && rid == ID_MOUSE ->
                        h.replyReport(device, type, id, byteArrayOf(featureValue.toByte()))
                    type == BluetoothHidDevice.REPORT_TYPE_INPUT && rid == ID_MOUSE ->
                        h.replyReport(device, type, id, ByteArray(9))
                    type == BluetoothHidDevice.REPORT_TYPE_INPUT && rid == ID_KEYBOARD ->
                        h.replyReport(device, type, id, ByteArray(8))
                    else -> h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            } catch (_: Exception) {
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            val h = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id.toInt() == ID_MOUSE && data.isNotEmpty()) {
                // 스택에 따라 앞에 Report ID가 붙어 오기도 한다
                val v = if (data.size >= 2 && data[0].toInt() == ID_MOUSE) data[1] else data[0]
                featureValue = v.toInt() and 0x0F
            }
            try {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 마우스 (메인 스레드에서 호출)
    // ------------------------------------------------------------------

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            pdx += dx; pdy += dy
            scheduleFlushLocked()
        }
    }

    /** 스크롤. 단위는 1/120칸. v>0 = 위로, h>0 = 오른쪽으로 */
    fun scroll(vUnits: Float, hUnits: Float) {
        wheelAcc += vUnits
        panAcc += hUnits
        val wd = if (hiResWheel) 1 else UNITS_PER_NOTCH
        val pd = if (hiResPan) 1 else UNITS_PER_NOTCH
        val w = (wheelAcc / wd).toInt()
        val p = (panAcc / pd).toInt()
        wheelAcc -= w * wd
        panAcc -= p * pd
        if (w == 0 && p == 0) return
        synchronized(lock) {
            pWheel += w; pPan += p
            scheduleFlushLocked()
        }
    }

    /** 정확히 n칸 휠 (핀치 줌용) */
    fun scrollNotches(n: Int) {
        if (n == 0) return
        val u = if (hiResWheel) n * UNITS_PER_NOTCH else n
        synchronized(lock) {
            pWheel += u
            scheduleFlushLocked()
        }
    }

    fun resetScrollRemainder() {
        wheelAcc = 0f
        panAcc = 0f
    }

    fun setButtons(b: Int) {
        synchronized(lock) {
            if (b == buttons) return
            val pending = takeMouseReportLocked()
            buttons = b
            val btnReport = mouseReport(b, 0, 0, 0, 0)
            postOrderedLocked {
                pending?.let { send(ID_MOUSE, it) }
                send(ID_MOUSE, btnReport)
            }
        }
    }

    fun click(btn: Int) {
        setButtons(btn)
        setButtons(0)
    }

    // ------------------------------------------------------------------
    // 키보드
    // ------------------------------------------------------------------

    /** 현재 키 상태를 한 번 보낸다. (mods, key) — key=0 이면 키 없음 */
    fun keyboard(mods: Int, key: Int = 0) = keySequence(mods to key)

    /** 수정키 누름 → 키 누름 → 키 뗌 → 전부 뗌 (예: Win+Tab) */
    fun keyCombo(mods: Int, key: Int) =
        keySequence(mods to 0, mods to key, mods to 0, 0 to 0)

    fun keySequence(vararg states: Pair<Int, Int>) {
        val reports = states.map { kbReport(it.first, it.second) }
        synchronized(lock) {
            val pending = takeMouseReportLocked()
            postOrderedLocked {
                pending?.let { send(ID_MOUSE, it) }
                reports.forEachIndexed { i, r ->
                    if (i > 0) sleepQuietly(6)
                    send(ID_KEYBOARD, r)
                }
            }
        }
    }

    fun releaseAll() {
        setButtons(0)
        keyboard(0)
    }

    // ------------------------------------------------------------------
    // 내부: 리포트 묶어서 보내기
    // ------------------------------------------------------------------

    private fun scheduleFlushLocked() {
        if (flushPosted) return
        flushPosted = true
        val e = epoch
        sender.post { flush(e) }
    }

    private fun flush(e: Int) {
        val report: ByteArray? = synchronized(lock) {
            if (e != epoch) {
                null // 순서가 중요한 리포트가 끼어들어 무효화됨
            } else {
                flushPosted = false
                val r = takeMouseReportLocked()
                if (hasPendingLocked()) scheduleFlushLocked()
                r
            }
        }
        if (report != null) send(ID_MOUSE, report)
    }

    /** 대기 중인 이동을 먼저 보내고, 그다음 block을 실행하도록 순서를 보장한다. */
    private fun postOrderedLocked(block: () -> Unit) {
        epoch++
        flushPosted = false
        sender.post(block)
        if (hasPendingLocked()) scheduleFlushLocked()
    }

    private fun hasPendingLocked() = pdx != 0 || pdy != 0 || pWheel != 0 || pPan != 0

    private fun takeMouseReportLocked(): ByteArray? {
        if (!hasPendingLocked()) return null
        val x = pdx.coerceIn(-32767, 32767)
        val y = pdy.coerceIn(-32767, 32767)
        val w = pWheel.coerceIn(-32767, 32767)
        val p = pPan.coerceIn(-32767, 32767)
        pdx -= x; pdy -= y; pWheel -= w; pPan -= p
        return mouseReport(buttons, x, y, w, p)
    }

    private fun mouseReport(b: Int, x: Int, y: Int, w: Int, p: Int) = byteArrayOf(
        b.toByte(),
        (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
        (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
        (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte(),
        (p and 0xFF).toByte(), ((p shr 8) and 0xFF).toByte()
    )

    private fun kbReport(mods: Int, key: Int) =
        byteArrayOf(mods.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        try {
            h.sendReport(d, id, data)
        } catch (_: Exception) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
