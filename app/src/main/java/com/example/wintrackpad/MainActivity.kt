package com.example.wintrackpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Toast

@SuppressLint("MissingPermission", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), HidManager.Listener {

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_ENABLE_BT = 2
        const val REQ_DISCOVERABLE = 3
    }

    private lateinit var settings: Settings
    private var hid: HidManager? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var setupRoot: View
    private lateinit var statusText: TextView
    private lateinit var deviceList: LinearLayout

    private var trackpadShown = false
    private var lastBackPress = 0L
    private var autoConnectTried = false

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

    override fun onDestroy() {
        hid?.stop()
        hid = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && trackpadShown) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!trackpadShown) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            hid?.disconnect()
            showSetup()
        } else {
            lastBackPress = now
            Toast.makeText(this, "한 번 더 누르면 연결을 끊습니다", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 권한 / 블루투스 켜기
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else emptyArray()

    private fun missingPermissions() = requiredPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThenStart() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            status("블루투스 권한을 허용해 주세요.")
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
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
        startHid()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT ->
                if (resultCode == RESULT_OK) startHid() else status("블루투스가 꺼져 있습니다.")
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

    private fun startHid() {
        if (hid != null) return
        val h = HidManager(this, this)
        hid = h
        if (!h.start()) {
            status("이 기기는 블루투스 HID(키보드/마우스) 기능을 지원하지 않습니다.")
            hid = null
            return
        }
        status("블루투스 HID 준비 중…")
        main.postDelayed({
            if (hid?.isRegistered == false && !trackpadShown) {
                status(
                    "HID 등록이 되지 않습니다.\n" +
                        "• 다른 블루투스 키보드/마우스 앱이 실행 중이면 종료하세요.\n" +
                        "• 일부 제조사 폰은 이 기능을 막아 두었습니다."
                )
            }
        }, 5000)
    }

    // ------------------------------------------------------------------
    // HidManager.Listener
    // ------------------------------------------------------------------

    override fun onHidStatus(message: String) = status(message)

    override fun onHidReady() {
        status("준비 완료. 아래에서 PC를 선택하거나, 처음이면 '페어링'을 먼저 하세요.")
        refreshDevices()
        // 마지막으로 쓰던 PC에 자동 재연결
        val h = hid ?: return
        if (autoConnectTried || h.host != null) return
        autoConnectTried = true
        val addr = settings.lastHost ?: return
        val dev = bondedDevices().firstOrNull { it.address == addr } ?: return
        if (h.connect(dev)) status("${h.deviceName(dev)}에 다시 연결하는 중…")
    }

    override fun onHostConnected(device: BluetoothDevice) {
        settings.lastHost = device.address
        showTrackpad(device)
    }

    override fun onHostDisconnected() {
        if (trackpadShown) showSetup()
        status("연결이 끊어졌습니다. 아래에서 PC를 다시 선택하세요.")
        refreshDevices()
    }

    // ------------------------------------------------------------------
    // 화면 전환
    // ------------------------------------------------------------------

    private fun showTrackpad(device: BluetoothDevice) {
        val h = hid ?: return
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(
            TrackpadView(this, h, settings),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // 연결 직후 잠깐만 보이고 사라지는 안내 (터치는 아래 트랙패드로 그대로 전달됨)
        val label = TextView(this).apply {
            text = "${h.deviceName(device)} 연결됨  ·  뒤로 두 번 = 연결 끊기"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        frame.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { topMargin = px(36) }
        )
        label.animate().alpha(0f).setStartDelay(2500).setDuration(700).start()

        setContentView(frame)
        trackpadShown = true
        hideSystemBars()
    }

    private fun showSetup() {
        trackpadShown = false
        showSystemBars()
        setContentView(setupRoot)
        refreshDevices()
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
    // 연결 화면 UI (코드로 구성)
    // ------------------------------------------------------------------

    private fun bondedDevices(): List<BluetoothDevice> = try {
        hid?.adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun refreshDevices() {
        if (!::deviceList.isInitialized) return
        deviceList.removeAllViews()
        val h = hid
        val devices = bondedDevices().sortedByDescending {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
        }
        if (h == null || devices.isEmpty()) {
            deviceList.addView(bodyText("페어링된 기기가 없습니다. 위의 '페어링'을 먼저 진행하세요."))
            return
        }
        for (d in devices) {
            val isPc = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            val b = Button(this).apply {
                text = (if (isPc) "💻  " else "📱  ") + h.deviceName(d)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (!h.isRegistered) {
                        status("아직 HID 준비가 안 됐습니다. 잠시 후 다시 시도하세요.")
                    } else if (h.connect(d)) {
                        status("${h.deviceName(d)}에 연결하는 중…")
                    } else {
                        status("연결 요청 실패. PC의 블루투스가 켜져 있는지 확인하세요.")
                    }
                }
            }
            deviceList.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        col.addView(bodyText("폰을 Windows PC의 블루투스 트랙패드로 사용합니다. PC에는 아무것도 설치할 필요가 없습니다."))

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1F1F1F.toInt())
            setPadding(px(16), px(14), px(16), px(14))
            background = card(0xFFE8F0FE.toInt())
        }
        col.addView(statusText, marginTop(16))

        // 1. 페어링
        col.addView(sectionTitle("1. 처음 한 번: PC와 페어링"))
        col.addView(bodyText(
            "① 아래 버튼을 눌러 이 폰을 검색 가능하게 합니다.\n" +
                "② Windows에서 설정 → Bluetooth 및 장치 → 장치 추가 → Bluetooth\n" +
                "③ 목록에서 이 폰 이름을 선택하고 페어링을 수락합니다.\n\n" +
                "※ 예전에 이 폰을 PC와 '휴대폰'으로 페어링했다면, Windows에서 그 장치를 먼저 제거한 뒤 다시 추가하세요."
        ))
        col.addView(Button(this).apply {
            text = "PC에서 이 폰을 찾을 수 있게 하기"
            isAllCaps = false
            setOnClickListener { requestDiscoverable() }
        }, marginTop(8))

        // 2. 연결
        col.addView(sectionTitle("2. 연결할 PC 선택"))
        col.addView(bodyText("연결되면 화면이 흰색 트랙패드로 바뀝니다. 다음부터는 앱을 열면 마지막 PC에 자동으로 연결합니다."))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(deviceList, marginTop(8))
        col.addView(Button(this).apply {
            text = "목록 새로고침"
            isAllCaps = false
            setOnClickListener { refreshDevices() }
        })

        // 설정
        col.addView(sectionTitle("설정"))
        col.addView(slider("포인터 속도", 0.4f, 2.5f, settings.pointerSpeed) { settings.pointerSpeed = it })
        col.addView(slider("스크롤 속도", 0.3f, 3.0f, settings.scrollSpeed) { settings.scrollSpeed = it })
        col.addView(toggle("자연스러운 스크롤 (맥 방식)", settings.naturalScroll) { settings.naturalScroll = it })
        col.addView(toggle("탭하여 클릭", settings.tapToClick) { settings.tapToClick = it })
        col.addView(toggle("진동 피드백", settings.hapticFeedback) { settings.hapticFeedback = it })

        // 제스처 안내
        col.addView(sectionTitle("제스처"))
        col.addView(bodyText(
            "• 1손가락 이동: 커서 이동\n" +
                "• 1손가락 탭: 클릭  /  두 번 탭: 더블클릭\n" +
                "• 탭 후 바로 눌러서 이동: 드래그\n" +
                "• 2손가락 탭: 오른쪽 클릭\n" +
                "• 2손가락 이동: 스크롤 (튕기면 관성 스크롤)\n" +
                "• 2손가락 벌리기/모으기: 확대/축소\n" +
                "• 3손가락 탭: 가운데 클릭\n" +
                "• 3손가락 좌우: 앱 전환 (손을 떼면 선택)\n" +
                "• 3손가락 위: 작업 보기  /  아래: 바탕 화면\n" +
                "• 4손가락 좌우: 가상 데스크톱 전환\n" +
                "• 4손가락 탭: 알림 센터\n" +
                "• 뒤로 버튼 두 번: 연결 끊기"
        ))

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
