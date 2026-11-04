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
import android.os.Looper

/**
 * 폰을 블루투스 HID 장치(마우스 + 키보드 복합 장치)로 등록하고 리포트를 보낸다.
 * Windows는 이 폰을 일반 블루투스 마우스/키보드로 인식하므로 PC에 별도 프로그램이 필요 없다.
 *
 * 마우스 리포트(ID 1, 9바이트): [버튼, X(16bit), Y(16bit), 휠(16bit), 가로휠(16bit)]
 * 키보드 리포트(ID 2, 8바이트): [수정키, 0, 키1..키6]
 *
 * 순서·안정성 규칙
 *  - 모든 전송은 단일 sender 스레드에서 순서대로 실행된다.
 *  - 연결마다 session 번호가 바뀌며, 이전 연결에서 쌓인 작업은 새 연결로 보내지 않는다.
 *  - 종료/연결 해제 시 버튼·키 해제 리포트를 먼저 보낸 뒤 정리한다.
 *  - 버튼·키 상태 리포트는 실패 시 한 번 재전송한다(상태값이라 중복 동작이 생기지 않음).
 *    이동량이 담긴 리포트는 재전송하지 않는다(커서가 두 번 움직이므로).
 */
@SuppressLint("MissingPermission")
class HidManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onHidStatus(message: String)
        fun onHidReady()
        fun onHidUnregistered()
        fun onHostConnected(device: BluetoothDevice)
        fun onHostDisconnected()
        /** 연속 전송 실패(false) / 회복(true) */
        fun onSendHealthChanged(healthy: Boolean)
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
        const val KEY_N = 0x11
        const val KEY_TAB = 0x2B
        const val KEY_RIGHT = 0x4F
        const val KEY_LEFT = 0x50

        const val UNITS_PER_NOTCH = ScrollAccumulator.UNITS_PER_NOTCH

        private const val FAIL_STREAK_UNHEALTHY = 5

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
    @Volatile var isRegistered = false
        private set
    /** 프로필 프록시가 끊겨 이 객체로는 복구할 수 없는 상태 */
    @Volatile var isProxyLost = false
        private set
    @Volatile private var stopped = false

    /** 연결이 바뀔 때마다 증가. 작업은 만들어질 때의 값을 들고 다닌다. */
    @Volatile private var session = 0

    private val senderThread = HandlerThread("hid-sender").also { it.start() }
    private val sender = Handler(senderThread.looper)
    private val main = Handler(Looper.getMainLooper())

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
    private var mods = 0
    private var epoch = 0
    private var flushPosted = false

    // 메인 스레드 전용
    private val wheelAcc = ScrollAccumulator()
    private val panAcc = ScrollAccumulator()

    // sender 스레드 전용
    private var failStreak = 0
    private var unhealthy = false

    // ------------------------------------------------------------------
    // 시작 / 종료 / 연결
    // ------------------------------------------------------------------

    fun start(): Boolean {
        val a = adapter ?: return false
        return a.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    /** 등록이 해제된 경우(예: 앱이 백그라운드로 갔을 때) 다시 등록한다. */
    fun ensureRegistered(): Boolean {
        if (stopped || isRegistered) return isRegistered
        val h = hid ?: return false
        return registerApp(h)
    }

    /** 버튼·키를 모두 뗀 뒤 등록 해제와 정리를 같은 순서 큐에서 실행한다. */
    fun stop() {
        if (stopped) return
        releaseAll()
        stopped = true
        val h = hid
        sender.post {
            sleepQuietly(30)
            try {
                h?.unregisterApp()
            } catch (_: Exception) {
            }
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
            } catch (_: Exception) {
            }
            hid = null
            host = null
            senderThread.quitSafely()
        }
    }

    fun connect(device: BluetoothDevice): Boolean = try {
        hid?.connect(device) ?: false
    } catch (e: Exception) {
        false
    }

    /** 버튼·키 해제가 전송된 뒤에 연결을 끊는다. */
    fun disconnect() {
        releaseAll()
        val h = hid ?: return
        val d = host ?: return
        sender.post {
            sleepQuietly(20)
            try {
                h.disconnect(d)
            } catch (_: Exception) {
            }
        }
    }

    fun deviceName(d: BluetoothDevice?): String = try {
        d?.name ?: d?.address ?: "알 수 없는 기기"
    } catch (e: SecurityException) {
        d?.address ?: "알 수 없는 기기"
    }

    private fun registerApp(h: BluetoothHidDevice): Boolean {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Phone Trackpad",
            "Android phone as a Windows trackpad",
            "WinTrackpad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR
        )
        return try {
            h.registerApp(sdp, null, null, context.mainExecutor, hidCallback)
        } catch (e: Exception) {
            false
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || stopped) return
            val h = proxy as BluetoothHidDevice
            hid = h
            isProxyLost = false
            if (!registerApp(h)) {
                listener.onHidStatus("HID 등록 요청이 거부되었습니다. 다른 블루투스 키보드/마우스 앱을 종료한 뒤 다시 시도하세요.")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (stopped) return
            resetConnectionState()
            hid = null
            isRegistered = false
            isProxyLost = true
            val hadHost = host != null
            host = null
            if (hadHost) listener.onHostDisconnected()
            listener.onHidUnregistered()
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (stopped) return
            isRegistered = registered
            if (registered) {
                listener.onHidReady()
            } else {
                resetConnectionState()
                val hadHost = host != null
                host = null
                if (hadHost) listener.onHostDisconnected()
                listener.onHidUnregistered()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (stopped) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    resetConnectionState()
                    host = device
                    listener.onHostConnected(device)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    listener.onHidStatus("연결 중: ${deviceName(device)}")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val cur = host
                    if (cur == null || cur.address == device.address) {
                        resetConnectionState()
                        host = null
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
                        h.replyReport(device, type, id, ByteArray(HidReports.MOUSE_REPORT_SIZE))
                    type == BluetoothHidDevice.REPORT_TYPE_INPUT && rid == ID_KEYBOARD ->
                        h.replyReport(device, type, id, ByteArray(HidReports.KEYBOARD_REPORT_SIZE))
                    else -> h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            } catch (_: Exception) {
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            val h = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id.toInt() == ID_MOUSE) {
                HidReports.parseFeature(data, ID_MOUSE)?.let { featureValue = it }
            }
            try {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {
            }
        }
    }

    /** 새 연결/끊김 시: 대기 입력 폐기, 상태 초기화, 이전 세션 작업 무효화 */
    private fun resetConnectionState() {
        synchronized(lock) {
            pdx = 0; pdy = 0; pWheel = 0; pPan = 0
            buttons = 0; mods = 0
            epoch++
            flushPosted = false
            session++
        }
        featureValue = 0
        wheelAcc.reset()
        panAcc.reset()
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
        val w = wheelAcc.add(vUnits, hiResWheel)
        val p = panAcc.add(hUnits, hiResPan)
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
        wheelAcc.reset()
        panAcc.reset()
    }

    val currentButtons: Int get() = synchronized(lock) { buttons }

    fun setButtons(b: Int) {
        synchronized(lock) {
            if (b == buttons) return
            val pending = takeMouseReportLocked()
            buttons = b
            val btnReport = HidReports.mouse(b, 0, 0, 0, 0)
            postOrderedLocked { s ->
                pending?.let { send(ID_MOUSE, it, s, retry = false) }
                send(ID_MOUSE, btnReport, s, retry = true)
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
        if (states.isEmpty()) return
        val reports = states.map { HidReports.keyboard(it.first, it.second) }
        synchronized(lock) {
            mods = states.last().first
            val pending = takeMouseReportLocked()
            postOrderedLocked { s ->
                pending?.let { send(ID_MOUSE, it, s, retry = false) }
                reports.forEachIndexed { i, r ->
                    if (i > 0) sleepQuietly(6)
                    send(ID_KEYBOARD, r, s, retry = true)
                }
            }
        }
    }

    /** 눌린 버튼·수정키를 모두 뗀다. 이미 떼져 있으면 아무것도 보내지 않는다. */
    fun releaseAll() {
        setButtons(0)
        val needKeyRelease = synchronized(lock) { mods != 0 }
        if (needKeyRelease) keyboard(0)
    }

    // ------------------------------------------------------------------
    // 내부: 리포트 묶어서 보내기
    // ------------------------------------------------------------------

    private fun scheduleFlushLocked() {
        if (flushPosted) return
        flushPosted = true
        val e = epoch
        val s = session
        sender.post { flush(e, s) }
    }

    private fun flush(e: Int, s: Int) {
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
        if (report != null) send(ID_MOUSE, report, s, retry = false)
    }

    /** 대기 중인 이동을 먼저 보내고, 그다음 block을 실행하도록 순서를 보장한다. */
    private fun postOrderedLocked(block: (session: Int) -> Unit) {
        epoch++
        flushPosted = false
        val s = session
        sender.post { block(s) }
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
        return HidReports.mouse(buttons, x, y, w, p)
    }

    /** sender 스레드에서만 호출 */
    private fun send(id: Int, data: ByteArray, s: Int, retry: Boolean) {
        if (s != session) return // 이전 연결의 작업
        val h = hid ?: return
        val d = host ?: return
        var ok = trySend(h, d, id, data)
        if (!ok && retry) {
            sleepQuietly(25)
            if (s == session) ok = trySend(h, d, id, data)
        }
        recordResult(ok)
    }

    private fun trySend(h: BluetoothHidDevice, d: BluetoothDevice, id: Int, data: ByteArray) = try {
        h.sendReport(d, id, data)
    } catch (_: Exception) {
        false
    }

    private fun recordResult(ok: Boolean) {
        if (ok) {
            failStreak = 0
            if (unhealthy) {
                unhealthy = false
                main.post { if (!stopped) listener.onSendHealthChanged(true) }
            }
        } else {
            failStreak++
            if (!unhealthy && failStreak >= FAIL_STREAK_UNHEALTHY) {
                unhealthy = true
                main.post { if (!stopped) listener.onSendHealthChanged(false) }
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
