package com.example.wintrackpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

@SuppressLint("MissingPermission", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), HidManager.Listener {

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_ENABLE_BT = 2
        const val REQ_DISCOVERABLE = 3
    }

    private lateinit var settings: Settings

    /** 연결은 백그라운드 서비스가 가지고 있다 */
    private var service: HidService? = null
    private val hid: HidManager? get() = service?.hid
    private var bound = false

    private lateinit var setupRoot: View
    private lateinit var statusText: TextView
    private lateinit var connectedBox: LinearLayout
    private lateinit var connectedLabel: TextView
    private lateinit var reconnectButton: Button

    private var trackpad: TrackpadView? = null
    private var pauseButton: TextView? = null
    private var healthBanner: TextView? = null

    private var trackpadShown = false

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    // ------------------------------------------------------------------
    // 생명주기
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setupRoot = buildSetupUi()
        setContentView(setupRoot)
        ensurePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        service?.recoverIfNeeded()
        updateSetupState()
    }

    override fun onPause() {
        // 화면을 떠날 때 진행 중인 제스처·관성·눌린 버튼을 정리 (연결은 서비스가 유지)
        trackpad?.cancelAll()
        super.onPause()
    }

    override fun onDestroy() {
        trackpad?.cancelAll()
        service?.let {
            if (it.client === this) it.client = null
            it.onQuit = null
        }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && trackpadShown) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (trackpadShown) {
            // 연결은 유지한 채 설정 화면으로
            showSetup()
            return
        }
        // 앱을 닫아도 연결은 백그라운드에서 유지된다 (알림에서 종료 가능)
        moveTaskToBack(true)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as HidService.LocalBinder).service
            service = s
            s.onQuit = { finishAndRemoveTask() }
            s.client = this@MainActivity // 현재 상태를 바로 다시 알려 준다
            updateSetupState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            updateSetupState()
        }
    }

    // ------------------------------------------------------------------
    // 권한 / 블루투스 켜기 / 서비스 시작
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else emptyArray()

    private fun missingPermissions() = requiredPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThenStart() {
        val ask = missingPermissions().toMutableList()
        // 알림 권한은 선택: 거부해도 동작하지만 알림창에 표시되지 않을 수 있음
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !settings.notificationAsked
        ) {
            ask += Manifest.permission.POST_NOTIFICATIONS
            settings.notificationAsked = true
        }
        if (ask.isNotEmpty()) {
            status("블루투스 권한을 허용해 주세요.")
            requestPermissions(ask.toTypedArray(), REQ_PERMS)
        } else {
            ensureBluetoothOn()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (missingPermissions().isEmpty()) ensureBluetoothOn()
        else status("블루투스 권한이 없으면 사용할 수 없습니다.\n설정 → 앱 → 윈도우 트랙패드 → 권한(근처 기기)을 허용하세요.")
    }

    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            status("이 기기에는 블루투스가 없습니다.")
            return
        }
        if (!adapter.isEnabled) {
            status("블루투스를 켜 주세요.")
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            return
        }
        startAndBindService()
    }

    private fun startAndBindService() {
        HidService.start(this)
        if (!bound) {
            bound = bindService(Intent(this, HidService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT ->
                if (resultCode == RESULT_OK) startAndBindService() else status("블루투스가 꺼져 있습니다.")
            REQ_DISCOVERABLE ->
                if (resultCode != RESULT_CANCELED) {
                    val name = hid?.adapter?.name ?: "이 폰"
                    status(
                        "${resultCode}초 동안 PC에서 이 폰을 찾을 수 있습니다.\n" +
                            "Windows에서 설정 → Bluetooth 및 장치 → 장치 추가 → Bluetooth → '$name' 선택"
                    )
                }
        }
    }

    // ------------------------------------------------------------------
    // HidManager.Listener (서비스가 전달)
    // ------------------------------------------------------------------

    override fun onHidStatus(message: String) {
        status(message)
        updateSetupState()
    }

    override fun onHidReady() {
        status("준비 완료. 처음이면 '페어링'을, 이미 페어링했다면 PC에서 연결하거나 아래 '다시 연결'을 누르세요.")
        updateSetupState()
    }

    override fun onHidUnregistered() {
        trackpad?.cancelAll()
        if (trackpadShown) showSetup()
        status("HID 등록이 해제되어 다시 등록하는 중…")
        updateSetupState()
    }

    override fun onHostConnected(device: BluetoothDevice) {
        updateSetupState()
        if (!trackpadShown) showTrackpad()
        trackpad?.let { Haptics.perform(it, Haptic.CONFIRM, settings.hapticFeedback) }
    }

    override fun onHostDisconnected() {
        trackpad?.cancelAll()
        if (trackpadShown) showSetup()
        status("연결이 끊어졌습니다.")
        updateSetupState()
    }

    override fun onSendHealthChanged(healthy: Boolean) {
        healthBanner?.visibility = if (healthy) View.GONE else View.VISIBLE
        if (!healthy) {
            trackpad?.let { Haptics.perform(it, Haptic.ERROR, settings.hapticFeedback) }
            status("입력이 PC로 전달되지 않고 있습니다. PC와의 거리나 블루투스 상태를 확인하세요.")
        }
    }

    // ------------------------------------------------------------------
    // 트랙패드 화면 (가로 고정)
    // ------------------------------------------------------------------

    private fun showTrackpad() {
        val h = hid ?: return
        if (!h.isConnected) return
        trackpad?.cancelAll()

        // 가로로 조작 (폰을 어느 쪽으로 돌려도 가로 유지)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val pad = TrackpadView(this, h, settings)
        trackpad = pad

        frame.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 오른쪽 위: 일시정지 / 설정 (옅은 색, 작게)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        pauseButton = cornerButton("일시정지") { setPaused(!pad.paused) }.also { topBar.addView(it) }
        topBar.addView(cornerButton("설정") { showSetup() })
        frame.addView(
            topBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { topMargin = px(12); rightMargin = px(24) }
        )

        // 연결 직후 잠깐 보이는 안내 (터치는 아래 트랙패드로 그대로 전달됨)
        val label = TextView(this).apply {
            text = "${h.deviceName(h.host)} 연결됨"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        frame.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        label.animate().alpha(0f).setStartDelay(2000).setDuration(700).start()

        healthBanner = TextView(this).apply {
            text = "PC로 입력이 전달되지 않고 있습니다"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(px(12), px(8), px(12), px(8))
            background = card(0xFFD93025.toInt())
            visibility = View.GONE
        }
        frame.addView(
            healthBanner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = px(56) }
        )

        setContentView(frame)
        trackpadShown = true
        hideSystemBars()
    }

    private fun setPaused(p: Boolean) {
        val pad = trackpad ?: return
        pad.paused = p
        hid?.releaseAll()
        pauseButton?.text = if (p) "다시 시작" else "일시정지"
        pad.setBackgroundColor(if (p) 0xFFEDEDED.toInt() else Color.WHITE)
    }

    private fun cornerButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF9A9A9A.toInt())
        setPadding(px(12), px(8), px(12), px(8))
        setOnClickListener { onClick() }
    }

    private fun showSetup() {
        trackpad?.cancelAll()
        hid?.releaseAll()
        trackpadShown = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
        setContentView(setupRoot)
        updateSetupState()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
    }

    // ------------------------------------------------------------------
    // 설정 화면
    // ------------------------------------------------------------------

    /** 연결됨 상자 / 다시 연결 버튼 표시 상태 갱신 */
    private fun updateSetupState() {
        if (!::connectedBox.isInitialized) return
        val h = hid
        val host = h?.host
        if (h != null && host != null) {
            connectedLabel.text = "${h.deviceName(host)}에 연결되어 있습니다."
            connectedBox.visibility = View.VISIBLE
            reconnectButton.visibility = View.GONE
        } else {
            connectedBox.visibility = View.GONE
            val last = service?.lastHostDevice()
            if (h != null && h.isRegistered && last != null) {
                reconnectButton.text = "${h.deviceName(last)}에 다시 연결"
                reconnectButton.visibility = View.VISIBLE
            } else {
                reconnectButton.visibility = View.GONE
            }
        }
    }

    private fun requestDiscoverable() {
        val h = hid
        if (h == null || !h.isRegistered) {
            status("아직 HID 준비가 안 됐습니다. 잠시 후 다시 시도하세요.")
            return
        }
        if (missingPermissions().isNotEmpty()) {
            requestPermissions(missingPermissions().toTypedArray(), REQ_PERMS)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_DISCOVERABLE)
    }

    private fun status(msg: String) {
        if (::statusText.isInitialized) statusText.text = msg
    }

    private fun buildSetupUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(40))
        }

        col.addView(TextView(this).apply {
            text = "윈도우 트랙패드"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        col.addView(bodyText("폰을 Windows PC의 블루투스 트랙패드로 사용합니다. 앱을 닫아도 알림창에 남아 연결이 유지되며, 알림을 누르면 돌아옵니다."))

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1F1F1F.toInt())
            setPadding(px(16), px(14), px(16), px(14))
            background = card(0xFFE8F0FE.toInt())
        }
        col.addView(statusText, marginTop(16))

        // 연결 중일 때만 보이는 상자
        connectedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = card(0xFFE6F4EA.toInt())
            visibility = View.GONE
        }
        connectedLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1E4620.toInt())
        }
        connectedBox.addView(connectedLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "트랙패드로 돌아가기"
            isAllCaps = false
            setOnClickListener { showTrackpad() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "연결 끊기"
            isAllCaps = false
            setOnClickListener {
                service?.disconnect()
                status("연결을 끊는 중…")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectedBox.addView(row)
        col.addView(connectedBox, marginTop(12))

        // 연결이 끊겨 있을 때: 마지막 PC에 다시 연결
        reconnectButton = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                if (service?.reconnectLast() == true) status("다시 연결하는 중…")
                else status("연결 요청 실패. PC의 블루투스가 켜져 있는지 확인하세요.")
            }
        }
        col.addView(reconnectButton, marginTop(12))

        // 처음 페어링
        col.addView(sectionTitle("처음 한 번: PC와 페어링"))
        col.addView(bodyText(
            "① 아래 버튼을 눌러 이 폰을 검색 가능하게 합니다.\n" +
                "② Windows에서 설정 → Bluetooth 및 장치 → 장치 추가 → Bluetooth\n" +
                "③ 목록에서 이 폰 이름을 선택하고 페어링을 수락합니다.\n\n" +
                "페어링 후에는 앱을 열면 마지막 PC에 자동으로 연결됩니다. 다른 PC로 바꾸려면 그 PC의 블루투스 설정에서 이 폰을 연결하세요.\n\n" +
                "※ 예전에 이 폰을 PC와 '휴대폰'으로 페어링했다면, Windows에서 그 장치를 먼저 제거한 뒤 다시 추가하세요."
        ))
        col.addView(Button(this).apply {
            text = "PC에서 이 폰을 찾을 수 있게 하기"
            isAllCaps = false
            setOnClickListener { requestDiscoverable() }
        }, marginTop(8))

        // 설정
        col.addView(sectionTitle("포인터 및 스크롤"))
        col.addView(slider("포인터 속도", 0.4f, 2.5f, settings.pointerSpeed) { settings.pointerSpeed = it })
        col.addView(slider("스크롤 속도", 0.3f, 3.0f, settings.scrollSpeed) { settings.scrollSpeed = it })
        col.addView(toggle("자연스러운 스크롤 (맥 방식)", settings.naturalScroll) { settings.naturalScroll = it })
        col.addView(toggle("관성 스크롤", settings.inertia) { settings.inertia = it })

        col.addView(sectionTitle("클릭"))
        col.addView(toggle("1손가락 탭으로 클릭", settings.tapToClick) { settings.tapToClick = it })
        col.addView(toggle("꾹 눌러 끌기", settings.longPressDrag) { settings.longPressDrag = it })
        col.addView(bodyText("한 손가락으로 가만히 꾹 누르면 진동이 한 번 오고 왼쪽 버튼이 눌린 상태가 됩니다. 그대로 움직이면 끌기, 손가락을 떼면 놓기입니다."))
        col.addView(toggle("탭 후 눌러 끌기로 드래그", settings.tapDrag) { settings.tapDrag = it })
        col.addView(toggle("2손가락 탭으로 오른쪽 클릭", settings.twoFingerRightClick) { settings.twoFingerRightClick = it })
        col.addView(toggle("3손가락 탭으로 가운데 클릭", settings.threeFingerMiddleClick) { settings.threeFingerMiddleClick = it })

        col.addView(sectionTitle("기타"))
        col.addView(toggle("진동 피드백", settings.hapticFeedback) { settings.hapticFeedback = it })
        col.addView(toggle("Windows 11 사용 (끄면 Windows 10)", settings.windows11) { settings.windows11 = it })
        col.addView(bodyText("설정은 트랙패드 화면을 다시 열 때 적용됩니다."))

        // 제스처 안내
        col.addView(sectionTitle("제스처 (트랙패드는 가로 화면)"))
        col.addView(bodyText(
            "• 1손가락 이동: 커서 이동\n" +
                "• 1손가락 탭: 클릭  /  두 번 탭: 더블클릭\n" +
                "• 꾹 누르기(진동) 후 이동: 끌기, 떼면 놓기\n" +
                "• 탭 후 바로 눌러서 이동: 드래그\n" +
                "• 2손가락 탭: 오른쪽 클릭\n" +
                "• 2손가락 이동: 스크롤 (튕기면 관성 스크롤, 다시 닿으면 멈춤)\n" +
                "• 2손가락 벌리기/모으기: 확대/축소\n" +
                "• 3손가락 탭: 가운데 클릭\n" +
                "• 3손가락 좌우: 앱 전환 (손을 떼면 선택)\n" +
                "• 3손가락 위: 작업 보기  /  아래: 바탕 화면\n" +
                "• 4손가락 좌우: 가상 데스크톱 전환\n" +
                "• 4손가락 탭: 알림 센터\n" +
                "• 뒤로 버튼 또는 오른쪽 위 '설정': 연결을 유지한 채 이 화면으로\n" +
                "• 오른쪽 위 '일시정지': 연결은 유지하고 입력만 멈춤"
        ))

        // 완전 종료
        col.addView(Button(this).apply {
            text = "앱 완전히 종료 (연결 끊기)"
            isAllCaps = false
            setOnClickListener {
                val s = service
                if (s != null) s.quit() else finishAndRemoveTask()
            }
        }, marginTop(24))

        return ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(0xFFF6F7F9.toInt())
            addView(col)
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setPadding(0, px(28), 0, px(6))
    }

    private fun bodyText(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(0xFF555555.toInt())
        setLineSpacing(0f, 1.25f)
        setPadding(0, px(4), 0, px(4))
    }

    private fun card(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 14 * dp
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px(v) }

    private fun slider(label: String, lo: Float, hi: Float, value: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, px(4))
        }
        val title = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF222222.toInt())
        }
        fun show(v: Float) { title.text = "$label  ×${"%.1f".format(v)}" }
        show(value)
        val bar = SeekBar(this).apply {
            this.max = 100
            progress = (((value - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 100f
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(title)
        box.addView(bar)
        return box
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        isChecked = value
        setPadding(0, px(10), 0, px(10))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
