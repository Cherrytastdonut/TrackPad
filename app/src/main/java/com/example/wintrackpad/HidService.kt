package com.example.wintrackpad

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 블루투스 HID 연결을 가진 포그라운드 서비스.
 * 화면을 끄거나 다른 앱으로 가도 연결이 유지되고, 알림을 누르면 앱으로 돌아온다.
 * (Android는 포그라운드가 아닌 앱의 HID 등록을 해제하므로 포그라운드 서비스가 필요하다.)
 */
@SuppressLint("MissingPermission")
class HidService : Service(), HidManager.Listener {

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 1
        const val ACTION_DISCONNECT = "com.example.wintrackpad.DISCONNECT"
        const val ACTION_QUIT = "com.example.wintrackpad.QUIT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HidService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        val service: HidService get() = this@HidService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings

    var hid: HidManager? = null
        private set

    /** 화면(Activity)이 붙어 있으면 이벤트를 전달받는다 */
    var client: HidManager.Listener? = null
        set(v) {
            field = v
            v?.let { replayState(it) }
        }

    /** Activity가 종료 요청을 받았을 때 */
    var onQuit: (() -> Unit)? = null

    var lastStatus: String = "블루투스 HID 준비 중…"
        private set
    private var autoConnectTried = false
    private var userDisconnected = false

    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannel()
        goForeground()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }
        startHid()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_QUIT -> {
                quit()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            unregisterReceiver(btStateReceiver)
        } catch (_: Exception) {
        }
        hid?.stop()
        hid = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 제어
    // ------------------------------------------------------------------

    fun disconnect() {
        userDisconnected = true
        hid?.disconnect()
    }

    fun reconnectLast(): Boolean {
        val h = hid ?: return false
        val dev = lastHostDevice() ?: return false
        userDisconnected = false
        return h.connect(dev)
    }

    fun lastHostDevice(): BluetoothDevice? {
        val addr = settings.lastHost ?: return null
        return try {
            hid?.adapter?.bondedDevices?.firstOrNull { it.address == addr }
        } catch (e: SecurityException) {
            null
        }
    }

    fun quit() {
        hid?.stop()
        hid = null
        onQuit?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 앱 복귀·블루투스 재활성화 시 HID 상태 복구 */
    fun recoverIfNeeded() {
        val adapter = (hid?.adapter) ?: (getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter)
        if (adapter == null || !adapter.isEnabled) return
        val h = hid
        when {
            h == null -> startHid()
            h.isProxyLost -> {
                h.stop()
                hid = null
                startHid()
            }
            !h.isRegistered -> h.ensureRegistered()
        }
    }

    private fun startHid() {
        if (hid != null) return
        val h = HidManager(this, this)
        hid = h
        autoConnectTried = false
        if (!h.start()) {
            h.stop()
            hid = null
            onHidStatus("이 기기는 블루투스 HID(키보드/마우스) 기능을 지원하지 않습니다.")
            return
        }
        onHidStatus("블루투스 HID 준비 중…")
        main.postDelayed({
            if (hid === h && !h.isRegistered) {
                onHidStatus(
                    "HID 등록이 되지 않습니다.\n" +
                        "• 다른 블루투스 키보드/마우스 앱이 실행 중이면 종료하세요.\n" +
                        "• 일부 제조사 폰은 이 기능을 막아 두었습니다."
                )
            }
        }, 5000)
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    val wasConnected = hid?.isConnected == true
                    hid?.stop()
                    hid = null
                    if (wasConnected) client?.onHostDisconnected()
                    onHidStatus("블루투스가 꺼졌습니다. 블루투스를 켜면 자동으로 다시 준비합니다.")
                }
                BluetoothAdapter.STATE_ON -> main.postDelayed({ recoverIfNeeded() }, 500)
            }
        }
    }

    // ------------------------------------------------------------------
    // HidManager.Listener → 알림 갱신 + 화면으로 전달
    // ------------------------------------------------------------------

    override fun onHidStatus(message: String) {
        lastStatus = message
        updateNotification()
        client?.onHidStatus(message)
    }

    override fun onHidReady() {
        lastStatus = "준비 완료"
        updateNotification()
        client?.onHidReady()
        // 마지막으로 쓰던 PC에 자동 재연결 (서비스 시작마다 한 번)
        val h = hid ?: return
        if (autoConnectTried || h.isConnected) return
        autoConnectTried = true
        val dev = lastHostDevice() ?: return
        if (h.connect(dev)) onHidStatus("${h.deviceName(dev)}에 다시 연결하는 중…")
    }

    override fun onHidUnregistered() {
        client?.onHidUnregistered()
        lastStatus = "HID 다시 등록하는 중…"
        updateNotification()
        main.postDelayed({ recoverIfNeeded() }, 800)
    }

    override fun onHostConnected(device: BluetoothDevice) {
        settings.lastHost = device.address
        userDisconnected = false
        lastStatus = "${hid?.deviceName(device) ?: "PC"}에 연결됨"
        updateNotification()
        client?.onHostConnected(device)
    }

    override fun onHostDisconnected() {
        lastStatus = if (userDisconnected) "연결을 끊었습니다" else "연결이 끊어졌습니다"
        updateNotification()
        client?.onHostDisconnected()
    }

    override fun onSendHealthChanged(healthy: Boolean) {
        client?.onSendHealthChanged(healthy)
    }

    private fun replayState(c: HidManager.Listener) {
        val h = hid
        val host = h?.host
        if (h != null && host != null) c.onHostConnected(host)
        else c.onHidStatus(lastStatus)
    }

    // ------------------------------------------------------------------
    // 알림
    // ------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "트랙패드 연결", NotificationManager.IMPORTANCE_LOW).apply {
            description = "백그라운드에서 PC와의 연결을 유지합니다"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun serviceAction(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, HidService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.ic_stat_trackpad)
            .setContentTitle("윈도우 트랙패드")
            .setContentText(lastStatus.lineSequence().first())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (hid?.isConnected == true) {
            b.addAction(Notification.Action.Builder(null, "연결 끊기", serviceAction(ACTION_DISCONNECT, 1)).build())
        }
        b.addAction(Notification.Action.Builder(null, "종료", serviceAction(ACTION_QUIT, 2)).build())
        return b.build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }
}
