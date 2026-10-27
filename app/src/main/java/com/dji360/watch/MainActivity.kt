package com.dji360.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog
import com.dji360.watch.ble.BleClient
import com.dji360.watch.dji.DjiCommands
import com.dji360.watch.dji.DjiLink
import com.dji360.watch.gps.GpsPusher
import com.dji360.watch.stream.DatalinkClient
import com.dji360.watch.stream.LiveVideoDecoder
import com.dji360.watch.wifi.CameraWifiManager
import kotlinx.coroutines.CoroutineScope
import java.lang.Class
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Clean watch UI. States:
 *
 *   ○ DISCONNECTED   [ CONNECT ]
 *   ◌ CONNECTING…
 *   ● READY           🎥 360 Video    ( REC )   QS    ✕(corner)
 *   ● RECORDING       01:27     ( STOP )
 *
 * Mode: icon + name. Disconnect: small ✕ in bottom right corner.
 */
class MainActivity : Activity() {

    private enum class Ui { DISCONNECTED, SCANNING, CONNECTING, READY, RECORDING, REJECTED, FAILED }

    private lateinit var modeText: TextView
    private lateinit var bigText: TextView
    private lateinit var mainButton: Button
    private lateinit var qsBtn: Button
    private lateinit var batteryText: TextView
    private lateinit var gpsIcon: TextView
    private lateinit var coordText: TextView
    private lateinit var disconnectBtn: Button
    private lateinit var liveBtn: Button
    private lateinit var surfaceView: SurfaceView
    private lateinit var rootView: FrameLayout

    private lateinit var ble: BleClient
    private lateinit var link: DjiLink
    private lateinit var wifiManager: CameraWifiManager
    private var decoder: LiveVideoDecoder? = null
    private var datalinkClient: DatalinkClient? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var uiJob: Job? = null
    private var connectJob: Job? = null
    private var wifiJob: Job? = null
    private var reconnectAttempt = 0
    private lateinit var gps: GpsPusher
    private var gpsRunning = false
    private var satCount = 0
    @Volatile private var streamHudStatus: String = ""
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var gpsFix = false
    private var dndActive = false
    private var activeScan: Diagnostics.BleScanTest? = null

    private val accent get() = Color.argb(255, 80, 220, 120)
    private val red get() = Color.argb(255, 255, 92, 92)
    private val dim get() = Color.argb(255, 150, 150, 150)
    private val dimColor get() = Color.argb(255, 95, 100, 106)

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            android.util.Log.e("DJIRemote", "FATAL: " + e.message, e)
        }

        // Keep screen alive & show over lock screen (prevent return to watch face)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        buildUi()
        ble = BleClient(applicationContext)
        val mac = readOwnMac()
        val macBytes = mac.split(":").mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        link = DjiLink(ble, uiScope, macBytes) { msg -> android.util.Log.d("DJIRemote", msg) }
        link.start()
        wifiManager = CameraWifiManager(applicationContext) { msg -> android.util.Log.d("DJIRemote", msg) }
        gps = GpsPusher(applicationContext, link, uiScope, { msg -> android.util.Log.d("DJIRemote", msg) }, { sats ->
            satCount = sats
        }, onFixState = { fixed -> gpsFix = fixed }, onLocation = { lat, lng ->
            lastLat = lat; lastLng = lng
        })
        observe()
        if (askPermissionsIfRequired()) {
            startConnect()
        }
    }

    /**
     * Our own MAC for the DJI handshake. BluetoothAdapter.getAddress() needs the
     * BLUETOOTH_CONNECT runtime permission, which may not be granted yet during
     * onCreate — so read it from system properties (no BT permission required),
     * and fall back to 00:00:00:00:00:00 if unavailable.
     */
    private fun readOwnMac(): String {
        // Try BluetoothAdapter.getAddress() first (may throw without runtime permission).
        runCatching {
            val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val fromBt = ad?.address
            if (fromBt != null && fromBt.isNotBlank() && fromBt != "02:00:00:00:00:00") return fromBt
        }
        // Read system properties via reflection — ro.boot.wifimacaddr / net.wifi.wifimacaddr on HarmonyOS/Huawei.
        val propNames = listOf("ro.boot.wifimacaddr", "net.wifi.wifimacaddr", "ro.boot.btmacaddr")
        for (key in propNames) {
            val v = runCatching {
                @Suppress("DiscouragedApi")
                java.lang.Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
            }.getOrNull()
            if (!v.isNullOrBlank() && v.contains(":")) return v
        }
        return "00:00:00:00:00:00"
    }

    override fun onDestroy() {
        super.onDestroy()
        setDndMode(false)
        uiJob?.cancel(); connectJob?.cancel()
        stopLivePreview()
        gps.stop()
        link.stop()
        ble.disconnect()
    }

    /* ----------------------------- UI ----------------------------- */

    // UI is designed for a ~233dp-wide round watch face (Watch 4 Pro class).
    // uiScale adapts all dp-based sizes to the actual screen (emulator runs at
    // 440dpi → only 152dp wide, so fixed sizes overflow).
    private var uiScale = 1f

    private fun dp(v: Int) = (v * resources.displayMetrics.density * uiScale).toInt()

    private fun sp(v: Float) = v * uiScale

    private fun pill(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = sp(12f)
        isAllCaps = true
        setTextColor(Color.WHITE)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(255, 44, 44, 48))
        }
        setOnClickListener { onClick() }
    }

    private fun roundMain(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = sp(18f)
        gravity = Gravity.CENTER
        isAllCaps = true
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (color == Color.WHITE) Color.BLACK else Color.WHITE)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(0, dp(10), 0, dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        uiScale = resources.displayMetrics.widthPixels /
            (resources.displayMetrics.density * 233f)
        if (uiScale > 1f) uiScale = 1f   // never upscale beyond the design size

        rootView = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root = rootView

        // ---- BACKGROUND: SurfaceView for Live Viewfinder ----
        surfaceView = SurfaceView(this).apply {
            setZOrderMediaOverlay(false)
            visibility = View.VISIBLE
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    val dec = LiveVideoDecoder(h.surface) { msg ->
                        android.util.Log.d("DJIRemote", msg)
                    }
                    decoder = dec
                    dec.start()
                }
                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hPx: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    decoder?.stop()
                    decoder = null
                }
            })
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ---- TOP: battery + GPS, side by side ----
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        batteryText = TextView(this).apply {
            textSize = sp(12f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        gpsIcon = TextView(this).apply {
            textSize = sp(13f); gravity = Gravity.CENTER
            setPadding(dp(10), 0, 0, 0); visibility = View.GONE
        }
        topRow.addView(batteryText)
        topRow.addView(gpsIcon)
        root.addView(topRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(20) })

        // Debug-only: live watch coordinates (only rendered in debug builds).
        coordText = TextView(this).apply {
            textSize = sp(9f); setTextColor(dim); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0); visibility = View.GONE
            typeface = Typeface.MONOSPACE
        }
        root.addView(coordText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(50) })

        // ---- CENTER: main round button (REC/STOP/CONNECT) ----
        mainButton = roundMain("CONNECT", Color.argb(255, 40, 120, 220)) { onMainButton() }
        root.addView(mainButton, FrameLayout.LayoutParams(
            dp(84), dp(84)
        ).apply { gravity = Gravity.CENTER })

        // ---- LEFT: quick mode switch (0x00/0x11). Photo is deliberately absent (rule #21). ----
        qsBtn = pill("QS") { onQs() }
        qsBtn.visibility = View.GONE
        root.addView(qsBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; marginStart = dp(8) })

        // ---- RIGHT: disconnect (✕) ----
        disconnectBtn = Button(this).apply {
            text = "✕"
            textSize = sp(16f)
            isAllCaps = false
            setTextColor(Color.LTGRAY)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 40, 40, 44))
            }
            visibility = View.GONE
            setOnClickListener { onDisc() }
        }
        root.addView(disconnectBtn, FrameLayout.LayoutParams(
            dp(40), dp(40)
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; marginEnd = dp(8) })

        // ---- BOTTOM: recording timer + current mode ----
        liveBtn = pill("👁 LIVE") { onLiveButton() }
        liveBtn.setOnLongClickListener { showWifiConfigDialog(); true }
        liveBtn.visibility = View.GONE
        root.addView(liveBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(46) })

        bigText = TextView(this).apply {
            textSize = sp(20f); setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(bigText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(46) })

        modeText = TextView(this).apply {
            textSize = sp(13f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(modeText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })

        setContentView(root)
    }

    /* ------------------------- state rendering ------------------------- */

    private fun render(ui: Ui, camera: DjiLink.CameraState) {
        // Mode line: icon + name. For Osmo 360 the meaningful mode is the New Camera
        // Status Push name (0x1D06); the numeric 0x1D02 code is only a fallback.
        // Hide the line entirely until we've actually parsed at least one status.
        val connected = link.phase.value == DjiLink.Phase.CONNECTED
        val hasName = !link.modeName.value.isNullOrEmpty()
        val name = if (hasName) link.modeName.value!!
                   else if (connected && camera.hasStatus) DjiLink.modeToString(camera.mode) else ""
        val icon = if (connected && (hasName || camera.hasStatus))
            DjiLink.modeIconFor(link.modeName.value, camera.mode) else ""
        val mode = if (name.isEmpty() || icon.isEmpty()) "" else "$icon  $name"
        modeText.text = mode
        modeText.visibility = if (mode.isEmpty()) View.GONE else View.VISIBLE

         // Camera battery, only meaningful once the camera reported it.
        val bat = if (camera.batteryPercent in 0..100) "🔋 ${camera.batteryPercent}%" else ""
        batteryText.text = bat

        updateGpsIcon()

        // Live HUD: Show IP, connection and stream diagnostics
        val hud = streamHudStatus.ifEmpty {
            if (wifiManager.isConnected) "WiFi: ${wifiManager.getGatewayIp()} (Ready)"
            else if (lastLat != 0.0 || lastLng != 0.0) String.format(Locale.US, "%.5f %.5f", lastLat, lastLng)
            else ""
        }
        if (hud.isNotEmpty()) {
            coordText.text = hud
            coordText.visibility = View.VISIBLE
        } else {
            coordText.visibility = View.GONE
        }

        when (ui) {
            Ui.DISCONNECTED -> {
                bigText.visibility = View.GONE
                mainButton.visibility = View.VISIBLE
                restyleMain("CONNECT", Color.argb(255, 40, 120, 220), Color.WHITE)
                qsBtn.visibility = View.GONE
            }
            Ui.SCANNING, Ui.CONNECTING -> {
                // Button stays visible & enabled — tapping it cancels the scan.
                val label = if (ui == Ui.SCANNING) "STOP" else "…"
                bigText.visibility = View.GONE
                mainButton.visibility = View.VISIBLE
                restyleMain(label, dimColor, Color.WHITE)
                qsBtn.visibility = View.GONE
            }
            Ui.READY -> {
                bigText.visibility = View.GONE
                mainButton.visibility = View.VISIBLE
                restyleMain("REC", accent, Color.BLACK)
                qsBtn.visibility = View.VISIBLE
            }
            Ui.RECORDING -> {
                val t = camera.recordTimeSec
                bigText.text = String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
                bigText.visibility = View.VISIBLE
                mainButton.visibility = View.VISIBLE
                restyleMain("STOP", red, Color.WHITE)
                qsBtn.visibility = View.VISIBLE
            }
            Ui.REJECTED -> {
                bigText.visibility = View.GONE
                mainButton.visibility = View.VISIBLE
                restyleMain("RETRY", Color.argb(255, 40, 120, 220), Color.WHITE)
                qsBtn.visibility = View.GONE
            }
            Ui.FAILED -> {
                bigText.visibility = View.GONE
                mainButton.visibility = View.VISIBLE
                restyleMain("CONNECT", Color.argb(255, 40, 120, 220), Color.WHITE)
                qsBtn.visibility = View.GONE
            }
        }

        // Side buttons (QS left, ✕ right) only matter once the camera is connected.
        val sideVisible = ui == Ui.READY || ui == Ui.RECORDING
        qsBtn.visibility = if (sideVisible) View.VISIBLE else View.GONE
        disconnectBtn.visibility = if (sideVisible) View.VISIBLE else View.GONE

        // LIVE / Viewfinder button (visible when ready and not recording)
        if (ui == Ui.READY) {
            liveBtn.visibility = View.VISIBLE
            if (datalinkClient?.isRunning == true) {
                // Keep streaming indicators (do not clobber)
            } else if (wifiJob?.isActive != true) {
                liveBtn.text = if (wifiManager.isConnected) "🟢 AP" else "👁 LIVE"
            }
        } else {
            liveBtn.visibility = View.GONE
        }
    }

    private fun restyleMain(label: String, color: Int, textColor: Int) {
        mainButton.isEnabled = true
        if (mainButton.text.toString() != label) {
            mainButton.text = label
            mainButton.setTextColor(textColor)
            (mainButton.background as GradientDrawable).setColor(color)
        }
    }

    /**
     * GPS icon: visible while GPS is running; blinks while the receiver is still
     * searching for a fix.
     */
    private fun updateGpsIcon() {
        val searching = gpsRunning && !gpsFix
        gpsIcon.text = "🛰️ $satCount"
        gpsIcon.visibility = if (gpsRunning) View.VISIBLE else View.GONE
        val anim = gpsIcon.animation
        if (searching) {
            if (anim == null) {
                val blink = android.view.animation.AlphaAnimation(0.2f, 1f).apply {
                    duration = 500
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                gpsIcon.animation = blink
                gpsIcon.startAnimation(blink)
            }
        } else {
            if (anim != null) {
                gpsIcon.clearAnimation()
                gpsIcon.alpha = 1f
            }
        }
    }

    private fun currentUi(): Ui = when {
        link.phase.value == DjiLink.Phase.CONNECTED ->
            if (link.camera.value.isRecording) Ui.RECORDING else Ui.READY
        link.phase.value == DjiLink.Phase.REJECTED -> Ui.REJECTED
        connectJob?.isActive == true ->
            if (ble.connectionState.value == BleClient.STATE_READY) Ui.CONNECTING else Ui.SCANNING
        else -> Ui.DISCONNECTED
    }

    /** Re-render whenever phase, camera state or mode name changes. */
    private fun observe() {
        uiJob?.cancel()
        uiJob = uiScope.launch {
            var last = ""
            while (true) {
                val ui = currentUi()
                val cam = link.camera.value
                val isConn = link.phase.value == DjiLink.Phase.CONNECTED

                // Battery optimization: stop GPS immediately if disconnected from camera
                if (!isConn && gpsRunning) {
                    gps.stop()
                    gpsRunning = false
                }

                // Notifications management: suppress full-screen interruptions during recording
                val wantDnd = (ui == Ui.RECORDING)
                if (wantDnd != dndActive) {
                    dndActive = wantDnd
                    setDndMode(dndActive)
                }

                val key = "$ui|${cam.mode}|${cam.status}|${cam.recordTimeSec}|${cam.batteryPercent}|${cam.hasStatus}|${link.modeName.value}|$satCount|$gpsFix|$lastLat|$lastLng"
                if (key != last) {
                    last = key
                    render(ui, cam)
                }
                delay(200)
            }
        }
    }

    private fun setDndMode(enabled: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                val targetFilter = if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
                if (nm.currentInterruptionFilter != targetFilter) {
                    nm.setInterruptionFilter(targetFilter)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DJIRemote", "Failed to set DND filter: ${e.message}")
        }
    }

    /* --------------------------- actions --------------------------- */

    private fun askPermissionsIfRequired(): Boolean {
        val missing = currentPermList().filterNot { granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERM)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startConnect()
        } else if (code == REQ_PERM) {
            Diagnostics.doubleBuzz(applicationContext)
        }
    }

    private fun onMainButton() {
        when (currentUi()) {
            Ui.READY -> {
                uiScope.launch {
                    if (link.startRecording()) Diagnostics.shortBuzz(applicationContext)
                    else Diagnostics.doubleBuzz(applicationContext)
                }
            }
            Ui.RECORDING -> {
                uiScope.launch {
                    if (link.stopRecording()) Diagnostics.shortBuzz(applicationContext)
                    else Diagnostics.doubleBuzz(applicationContext)
                }
            }
            Ui.DISCONNECTED, Ui.REJECTED, Ui.FAILED -> startConnect()
            Ui.SCANNING, Ui.CONNECTING -> cancelConnect()
            else -> {}
        }
    }

    private fun onQs() {
        if (link.phase.value != DjiLink.Phase.CONNECTED) return
        uiScope.launch {
            if (link.keyReport(DjiCommands.KEY_QS)) Diagnostics.shortBuzz(applicationContext)
            else Diagnostics.doubleBuzz(applicationContext)
        }
    }

    private fun onLiveButton() {
        if (datalinkClient?.isRunning == true) {
            stopLivePreview()
            liveBtn.text = "👁 LIVE"
            Diagnostics.shortBuzz(applicationContext)
            return
        }

        if (wifiManager.isConnected) {
            liveBtn.text = "⏳ STREAM..."
            startLiveStream()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedSsid = prefs.getString(PREFS_WIFI_SSID, null)
        val savedPass = prefs.getString(PREFS_WIFI_PASS, null)
        val targetSsid = savedSsid?.takeIf { it.isNotEmpty() }
            ?: link.deviceName.takeIf { it.isNotEmpty() && it != "Osmo" }
            ?: "Osmo360"

        if (!savedPass.isNullOrEmpty()) {
            connectWifiAndStartStream(targetSsid, savedPass)
            return
        }

        liveBtn.text = "📶..."
        wifiJob?.cancel()
        wifiJob = uiScope.launch {
            val creds = link.queryWifiCredentials(timeoutMs = 2000)
            if (creds != null) {
                val (ssid, pass) = creds
                prefs.edit().putString(PREFS_WIFI_SSID, ssid).putString(PREFS_WIFI_PASS, pass).apply()
                connectWifiAndStartStream(ssid, pass)
            } else {
                liveBtn.text = "👁 LIVE"
                showWifiConfigDialog(targetSsid)
            }
        }
    }

    private fun connectWifiAndStartStream(ssid: String, pass: String) {
        liveBtn.text = "🔗 WI-FI..."
        wifiJob?.cancel()
        wifiJob = uiScope.launch {
            val ok = wifiManager.connectToCameraAp(ssid, pass)
            if (ok) {
                Diagnostics.shortBuzz(applicationContext)
                liveBtn.text = "⏳ STREAM..."
                startLiveStream()
            } else {
                Diagnostics.doubleBuzz(applicationContext)
                liveBtn.text = "❌ WIFI"
                delay(2000)
                liveBtn.text = "👁 LIVE"
            }
        }
    }

    private fun showWifiConfigDialog(defaultSsid: String = "") {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val currentSsid = prefs.getString(PREFS_WIFI_SSID, null)
            ?: defaultSsid.takeIf { it.isNotEmpty() }
            ?: link.deviceName.takeIf { it.isNotEmpty() && it != "Osmo" }
            ?: "Osmo360"
        val currentPass = prefs.getString(PREFS_WIFI_PASS, "") ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val ssidLabel = TextView(this).apply {
            text = "Camera Wi-Fi SSID:"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        val ssidInput = EditText(this).apply {
            setText(currentSsid)
            setTextColor(Color.WHITE)
            setSingleLine()
        }

        val passLabel = TextView(this).apply {
            text = "Password (from camera screen):"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        val passInput = EditText(this).apply {
            setText(currentPass)
            hint = "Password from camera menu"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setSingleLine()
        }

        layout.addView(ssidLabel)
        layout.addView(ssidInput)
        layout.addView(passLabel)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Camera Wi-Fi")
            .setMessage("On camera: Settings → Wireless Connection")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val s = ssidInput.text.toString().trim()
                val p = passInput.text.toString().trim()
                if (s.isNotEmpty()) {
                    prefs.edit()
                        .putString(PREFS_WIFI_SSID, s)
                        .putString(PREFS_WIFI_PASS, p)
                        .apply()
                    connectWifiAndStartStream(s, p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLiveStream() {
        surfaceView.visibility = View.VISIBLE
        if (::rootView.isInitialized) rootView.setBackgroundColor(Color.TRANSPARENT)
        liveBtn.text = "⏳ STREAM..."
        datalinkClient?.stop()
        val net = wifiManager.findWifiNetwork()
        val ip = wifiManager.getGatewayIp()
        var lastPkt = 0
        val client = DatalinkClient(
            scope = uiScope,
            network = net,
            cameraIp = ip,
            onVideoFrame = { nal ->
                decoder?.feedNalUnit(nal)
            },
            onStats = { pkts ->
                lastPkt = pkts
                val decFrames = decoder?.renderedFrames ?: 0L
                val mime = decoder?.codecMime ?: "init"
                val status = "IP: $ip | UDP: $pkts | DEC: $decFrames ($mime)"
                streamHudStatus = status
                uiScope.launch {
                    liveBtn.text = "🔴 $pkts"
                    coordText.text = status
                    coordText.visibility = View.VISIBLE
                }
            }
        ) { msg ->
            android.util.Log.d("DJIRemote", msg)
            if (msg.contains("handshake", ignoreCase = true) ||
                msg.contains("ack", ignoreCase = true) ||
                msg.contains("udp", ignoreCase = true) ||
                msg.contains("tcp", ignoreCase = true) ||
                msg.contains("video", ignoreCase = true) ||
                msg.contains("error", ignoreCase = true) ||
                msg.contains("fail", ignoreCase = true) ||
                msg.contains("bound", ignoreCase = true)) {
                uiScope.launch {
                    val shortMsg = msg.replace("Datalink: ", "").take(45)
                    streamHudStatus = shortMsg
                    coordText.text = shortMsg
                    coordText.visibility = View.VISIBLE
                }
            }
        }
        datalinkClient = client
        client.start()

    }

    private fun stopLivePreview() {
        wifiJob?.cancel(); wifiJob = null
        datalinkClient?.stop(); datalinkClient = null
        decoder?.stop(); decoder = null
        surfaceView.visibility = View.GONE
        if (::rootView.isInitialized) rootView.setBackgroundColor(Color.BLACK)
        liveBtn.text = "👁 LIVE"
    }

    private fun onDisc() {
        connectJob?.cancel(); connectJob = null
        activeScan?.cancelIfRunning(); activeScan = null
        stopLivePreview()
        liveBtn.text = "👁 LIVE"
        link.stop()
        ble.disconnect()
        gps.stop(); gpsRunning = false
        setDndMode(false)
        reconnectAttempt = 0
        render(Ui.DISCONNECTED, DjiLink.CameraState())
    }

    /** Tap on the button while scanning/connecting → cancel and go back to CONNECT. */
    private fun cancelConnect() {
        connectJob?.cancel(); connectJob = null
        activeScan?.cancelIfRunning(); activeScan = null
        stopLivePreview()
        liveBtn.text = "👁 LIVE"
        ble.disconnect()
        gps.stop(); gpsRunning = false
        setDndMode(false)
        reconnectAttempt = 0
        render(Ui.DISCONNECTED, DjiLink.CameraState())
    }

    /** Scan → best DJI device → GATT → DJI handshake (clean scan teardown before connect). */
    @SuppressLint("MissingPermission")
    private fun startConnect() {
        if (connectJob?.isActive == true) return
        if (!askPermissionsIfRequired()) return
        link.start()
        connectJob = uiScope.launch {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val lastMac = prefs.getString(PREFS_LAST_MAC, null)

            val test = Diagnostics.BleScanTest(applicationContext)
            activeScan = test
            val scanMs = 3500L
            test.start(scanMs, targetMac = lastMac, Diagnostics.BleScanTest.Listener(
                onProgress = { },
                onFinished = { _ -> }
            ))

            // Allow scan to collect BLE advertisement packets
            delay(scanMs + 100)
            test.cancelIfRunning()
            activeScan = null
            // Crucial for BLE stability: give radio 250ms to finish scan teardown before connectGatt
            delay(250)

            val dji = test.djiCandidates()
            if (dji.isEmpty()) {
                Diagnostics.doubleBuzz(applicationContext)
                render(Ui.FAILED, DjiLink.CameraState())
                return@launch
            }

            // Prefer last connected camera if present in scan results, otherwise pick strongest signal
            val (targetMac, best) = if (lastMac != null && dji.containsKey(lastMac)) {
                lastMac to dji[lastMac]!!
            } else {
                val bestEntry = dji.entries.maxByOrNull { it.value.rssi }!!
                bestEntry.key to bestEntry.value
            }

            val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val dev = ad?.getRemoteDevice(targetMac)
            if (dev == null) {
                render(Ui.FAILED, DjiLink.CameraState()); return@launch
            }
            link.deviceName = best.name ?: "Osmo360"
            if (!ble.connect(dev)) {
                Diagnostics.doubleBuzz(applicationContext)
                render(Ui.FAILED, DjiLink.CameraState())
                return@launch
            }
            // First-ever successful connection → verify_mode=1 (camera shows the
            // confirmation popup once). Every later connection uses verify_mode=0
            // so the camera decides by its saved pairing history.
            val firstPairing = !prefs.getBoolean(PREFS_PAIRED, false)
            val ok = link.handshake(firstPairing = firstPairing)
            if (ok) {
                prefs.edit().putBoolean(PREFS_PAIRED, true)
                    .putString(PREFS_LAST_MAC, targetMac)
                    .apply()
                reconnectAttempt = 0
                if (!gpsRunning) { gps.start(); gpsRunning = true }
                Diagnostics.shortBuzz(applicationContext)
            } else if (link.phase.value == DjiLink.Phase.REJECTED) {
                Diagnostics.doubleBuzz(applicationContext)
            } else {
                Diagnostics.doubleBuzz(applicationContext)
                ble.disconnect()
                render(Ui.FAILED, DjiLink.CameraState())
            }
        }
    }

    /* ----------------------------- misc ----------------------------- */

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun currentPermList(): List<String> =
        if (Build.VERSION.SDK_INT >= 31)
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private companion object {
        const val REQ_PERM = 41
        const val PREFS = "dji_remote"
        const val PREFS_PAIRED = "paired_once"
        const val PREFS_LAST_MAC = "last_mac"
        const val PREFS_WIFI_SSID = "wifi_ssid"
        const val PREFS_WIFI_PASS = "wifi_pass"
    }
}
