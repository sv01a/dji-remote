package com.dji360.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Locale

/** Data-collection helpers for the STEP 1/2 diagnostic APK ("Hello DJI Remote"). */
object Diagnostics {

    fun systemLines(ctx: Context): List<String> = buildList {
        add("MODEL     : ${Build.MANUFACTURER} ${Build.MODEL}")
        add("ANDROID   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        add("ABI       : ${Build.SUPPORTED_ABIS.joinToString(",")}")
        add("BUILD     : ${Build.FINGERPRINT.take(72)}")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        add("RAM       : %.2f GB total, %.2f GB avail".format(mi.totalMem / 1e9, mi.availMem / 1e9))
    }

    @Suppress("DEPRECATION")
    fun screenLines(activity: Activity): List<String> {
        val dm = activity.resources.displayMetrics
        var realW: Int; var realH: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = activity.windowManager.maximumWindowMetrics.bounds
            realW = b.width(); realH = b.height()
        } else {
            val m = android.util.DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
            realW = m.widthPixels; realH = m.heightPixels
        }
        if (realH < realW) { val t = realW; realW = realH; realH = t }
        val refreshHz = if (Build.VERSION.SDK_INT >= 30) activity.display?.refreshRate
                        else activity.windowManager.defaultDisplay?.refreshRate
        val reportedPx = "${dm.widthPixels}x${dm.heightPixels}"
        val refresh = "%.0f".format(refreshHz ?: -1f)
        return listOf(
            "SCREEN    : ${realW}x${realH} px (reported $reportedPx)",
            "DENSITY   : ${dm.densityDpi} dpi (scale ${dm.density}), refresh $refresh Hz"
        )
    }

    fun batteryPercent(ctx: Context): Int? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (lvl < 0 || scale <= 0) null else (lvl * 100f / scale).toInt()
    }

    @SuppressLint("MissingPermission") // caller guarantees runtime grants
    fun bluetoothLines(ctx: Context): List<String> {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad: BluetoothAdapter? = mgr?.adapter
        return buildList {
            add("BT MANAGER: ${if (mgr == null) "MISSING" else "present"}")
            add("ADAPTER   : " + when {
                ad == null -> "null (no BT hardware?)"
                ad.isEnabled -> "enabled"
                else -> "DISABLED — enable Bluetooth in watch settings and retry"
            })
            add("LE SCAN   : " + (if (ad?.bluetoothLeScanner != null) "available" else "unavailable"))
        }
    }

    fun vibratorOf(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** One short buzz = success feedback pattern preview. */
    fun shortBuzz(ctx: Context): String {
        val v = vibratorOf(ctx) ?: return "vibrator MISSING on this device"
        if (!v.hasVibrator()) return "hasVibrator()==false"
        v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        return "short buzz sent (80 ms)"
    }

    /** Double buzz = error feedback pattern preview. */
    fun doubleBuzz(ctx: Context): String {
        val v = vibratorOf(ctx) ?: return "vibrator MISSING on this device"
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 120, 70), -1))
        return "double buzz sent (70/120/70 ms)"
    }

    class DevRow(
        var name: String?, var rssi: Int,
        val mfr: MutableMap<Int, ByteArray> = LinkedHashMap(),
        val svcs: MutableSet<String> = LinkedHashSet()
    ) {
        /** Official demo advertising signature: mfr 0xAA(0x08..xx) with data[4]==0xFA. */
        val isDjiAdv: Boolean
            get() = mfr.entries.any { (code, data) ->
                code in 0xAA00..0xAAFF && data.size >= 5 &&
                    data[0] == 0xAA.toByte() && (data[1].toInt() and 0xFF) == 0x08 &&
                    (data[4].toInt() and 0xFF) == 0xFA
            }
        val isDjiName: Boolean
            get() = name?.contains("OSMO", true) == true || name?.contains("DJI", true) == true
        val isDji: Boolean get() = isDjiAdv || isDjiName
    }

    /** Minimal BLE scan probe used by the [SCAN] button (seed of STEP 3). */
    class BleScanTest(private val context: Context) {

        class Listener(val onProgress: (String) -> Unit, val onFinished: (List<String>) -> Unit)

        private val handler = Handler(Looper.getMainLooper())
        private val rows = LinkedHashMap<String, DevRow>()
        private var listener: Listener? = null
        private var running = false
        var onTargetFound: ((String, DevRow) -> Unit)? = null

        @SuppressLint("MissingPermission") // caller guarantees runtime grants
        fun start(durationMs: Long, targetMac: String? = null, l: Listener): Boolean {
            this.listener = l
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val scanner = adapter?.bluetoothLeScanner ?: run {
                l.onProgress("BLE scanner unavailable"); return false
            }
            rows.clear(); running = true
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, callback)
                l.onProgress(String.format(Locale.US, "scanning %ds…", durationMs / 1000))
            } catch (se: SecurityException) {
                running = false; l.onProgress("scan blocked: ${se.message}"); return false
            }
            handler.postDelayed({ stopAndReport(false) }, durationMs)
            return true
        }

        fun cancelIfRunning() { if (running) stopAndReport(true) }

        /** DJI/Osmo candidates seen so far (by adv signature or name), best-RSSI order. */
        fun djiCandidates(): Map<String, DevRow> =
            rows.filter { it.value.isDji }.entries
                .sortedByDescending { it.value.rssi }
                .associate { it.toPair() }

        /** All devices seen so far. */
        fun allSeen(): Map<String, DevRow> = rows.toMap()

        private val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = record(r)
            override fun onBatchScanResults(rs: MutableList<ScanResult>) { rs.forEach { record(it) } }
            override fun onScanFailed(errorCode: Int) {
                running = false
                listener?.onFinished(listOf("SCAN FAILED code=$errorCode"))
            }
        }

        private fun record(r: ScanResult) {
            val addr = r.device.address
            val row = rows.getOrPut(addr) {
                try { DevRow(r.scanRecord?.deviceName ?: r.device.name, r.rssi) }
                catch (e: SecurityException) { DevRow(null, r.rssi) }
            }
            if (r.rssi > row.rssi) row.rssi = r.rssi
            if (row.name == null) {
                try { row.name = r.scanRecord?.deviceName ?: r.device.name } catch (e: SecurityException) {}
            }
            val specific = r.scanRecord?.manufacturerSpecificData
            if (specific != null && specific.size() > 0) {
                for (i in 0 until specific.size()) {
                    val value = specific.valueAt(i) ?: continue
                    row.mfr[specific.keyAt(i)] = value
                }
            }
            r.scanRecord?.serviceUuids?.forEach { u -> row.svcs += u.uuid.toString().uppercase(Locale.US) }
            if (row.isDji) {
                onTargetFound?.invoke(addr, row)
            }
        }

        @SuppressLint("MissingPermission")
        private fun stopAndReport(cancelled: Boolean) {
            running = false
            try {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (e: Exception) {}
            val out = mutableListOf(if (cancelled) "SCAN CANCELLED" else "SCAN DONE")
            out += "devices seen: ${rows.size}"
            rows.entries.sortedByDescending { it.value.rssi }.take(12).forEach { (addr, row) ->
                val n = row.name ?: "?"
                val mark = if (row.isDjiAdv) "D" else if (row.isDjiName) "*" else " "
                val svc = row.svcs.firstOrNull()?.let { u ->
                    if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805F9B34FB")) u.substring(4, 8)
                    else u.takeLast(8)
                } ?: "-"
                val mfr = row.mfr.entries.joinToString(" ") { "%04X=%s".format(it.key, hex(it.value)) }.trim()
                out += "$mark${addr}  rssi=${row.rssi}  svc=$svc  name=$n" +
                        (if (mfr.isEmpty()) "" else "  mfr[$mfr]")
            }
            out += "(*) name looks like DJI/Osmo. Official demo filters service 0xFFF0."
            listener?.onFinished(out)
        }
    }

    fun hex(b: ByteArray, max: Int = b.size): String =
        b.take(max).joinToString("") { "%02X".format(it) }
}
