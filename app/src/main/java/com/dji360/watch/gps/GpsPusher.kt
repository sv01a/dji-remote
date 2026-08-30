package com.dji360.watch.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.dji360.watch.dji.DjiCommands
import com.dji360.watch.dji.DjiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Streams watch GPS fixes to the camera via DJI GPS Data Push (0x00/0x17).
 * Uses plain LocationManager (no Google Play Services). Sends the last known
 * fix once per second while the DJI link is CONNECTED — same cadence as the
 * official demo's gps task.
 */
class GpsPusher(
    private val context: Context,
    private val link: DjiLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onSatellites: (Int) -> Unit = {},
    private val onFixState: (Boolean) -> Unit = {},
    private val onLocation: (Double, Double) -> Unit = { _, _ -> }
) {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var senderJob: Job? = null
    private var lastFix: Location? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var gnssReported = false
    private var hasGnssFix = false
    // Authoritative satellite count from GnssStatus callback (fix.extras is often 0).
    private var satCount = 0

    /** true = GPS listening started (providers exist, updates registered). */
    fun start(): Boolean {
        if (listener != null) return true
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null ||
            !(manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        ) {
            log("GPS: no location providers enabled")
            return false
        }
        lm = manager
        var firstFixLogged = false
        val l = LocationListener { loc ->
            if (!firstFixLogged) {
                firstFixLogged = true
                log("GPS fix: %.5f,%.5f alt=%.0fm acc=%.0fm sats=${loc.extras?.getInt("satellites", 0)}"
                    .format(loc.latitude, loc.longitude, loc.altitude, loc.accuracy))
            }
            lastFix = loc
            onLocation(loc.latitude, loc.longitude)
            // Many firmwares never fill the "satellites" extra — the GnssStatus
            // callback below is the authoritative source. Only fall back to the
            // extra when the callback hasn't reported anything yet.
            if (!gnssReported) {
                satCount = loc.extras?.getInt("satellites", 0) ?: 0
                onSatellites(satCount)
            }
        }
        listener = l
        // Authoritative satellite count: count SVs actually in view (tracked),
        // falling back to SVs used in the fix.
        try {
            gnssReported = false
            hasGnssFix = false
            val cb: GnssStatus.Callback = object : GnssStatus.Callback() {
                override fun onStarted() {}
                override fun onStopped() {
                    // GNSS engine stopped → reset so the count can't go stale.
                    hasGnssFix = false
                    satCount = 0
                    onSatellites(0)
                    onFixState(false)
                }
                override fun onFirstFix(ttffMillis: Int) {
                    hasGnssFix = true
                    onFixState(true)
                }
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    gnssReported = true
                    var used = 0
                    var tracked = 0
                    for (i in 0 until status.satelliteCount) {
                        // Ignore junk rows: only count satellites with a known
                        // constellation (GPS/GLONASS/BeiDou/Galileo/...).
                        if (status.getConstellationType(i) != GnssStatus.CONSTELLATION_UNKNOWN) tracked++
                        if (status.usedInFix(i)) used++
                    }
                    // Count ALL satellites in view for display: some Watch/HarmonyOS
                    // compat layers never report usedInFix=true even while the
                    // receiver is actively tracking satellites, so `used` alone
                    // would stay clamped at 0 forever.
                    satCount = if (tracked > 0) tracked else used
                    onSatellites(satCount)
                    // onFirstFix isn't guaranteed on every device — if any satellite
                    // contributes to a fix, treat the fix as acquired.
                    if (used > 0 && !hasGnssFix) {
                        hasGnssFix = true
                        onFixState(true)
                    }
                }
            }
            gnssCallback = cb
            // registerGnssStatusCallback(Executor, …) exists only since API 30;
            // minSdk is 28, so fall back to the main-thread variant below API 30.
            val registered = if (Build.VERSION.SDK_INT >= 30) {
                lm?.registerGnssStatusCallback(
                    java.util.concurrent.Executor { r -> r.run() }, cb
                ) ?: false
            } else {
                @Suppress("DEPRECATION")
                lm?.registerGnssStatusCallback(cb) ?: false
            }
            if (!registered) {
                log("GPS: GnssStatus registration returned false")
                gnssCallback = null
            }
        } catch (t: Throwable) {
            // Throwable: NoSuchMethodError is an Error, not an Exception.
            log("GPS: GnssStatus callback failed: ${t.message}")
            gnssCallback = null
        }
        try {
            val providers = buildList {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
            providers.forEach { p ->
                manager.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper())
            }
            // seed with last known fix so pushes start immediately
            lastFix = providers.firstNotNullOfOrNull { manager.getLastKnownLocation(it) }
            log("GPS: listening on $providers")
        } catch (e: Exception) {
            log("GPS: requestLocationUpdates failed: ${e.message}")
            return false
        }
        senderJob = scope.launch {
            while (true) {
                if (link.phase.value == DjiLink.Phase.CONNECTED) {
                    lastFix?.let { fix ->
                        val payload = buildPayload(fix)
                        link.pushGps(payload)
                    }
                }
                delay(1000)
            }
        }
        return true
    }

    fun stop() {
        listener?.let { l ->
            try { lm?.removeUpdates(l) } catch (_: Exception) {}
        }
        listener = null
        gnssCallback?.let { cb ->
            try { lm?.unregisterGnssStatusCallback(cb) } catch (_: Exception) {}
        }
        gnssCallback = null
        gnssReported = false
        hasGnssFix = false
        satCount = 0
        lm = null
        senderJob?.cancel(); senderJob = null
        lastFix = null
        onSatellites(0)
        onFixState(false)
    }

    private fun buildPayload(fix: Location): ByteArray {
        val cal = Calendar.getInstance().apply { timeInMillis = fix.time }
        val ymd = (cal.get(Calendar.YEAR) * 10000) +
                ((cal.get(Calendar.MONTH) + 1) * 100) + cal.get(Calendar.DAY_OF_MONTH)
        // Demo documents Beijing time (hour+8); camera firmware expects that convention.
        val hms = ((cal.get(Calendar.HOUR_OF_DAY) + 8) % 24 * 10000) +
                (cal.get(Calendar.MINUTE) * 100) + cal.get(Calendar.SECOND)
        val speedMs = fix.speed
        val bearing = Math.toRadians(fix.bearing.toDouble())
        val speedNorth = (speedMs * kotlin.math.cos(bearing) * 100).toFloat()
        val speedEast = (speedMs * kotlin.math.sin(bearing) * 100).toFloat()
        val vertAcc = if (Build.VERSION.SDK_INT >= 26 && fix.hasVerticalAccuracy())
            (fix.verticalAccuracyMeters * 1000).toLong() else 0L
        val speedAcc = if (Build.VERSION.SDK_INT >= 26 && fix.hasSpeedAccuracy())
            (fix.speedAccuracyMetersPerSecond * 100).toLong() else 0L
        val sats = if (gnssReported) satCount
                   else fix.extras?.getInt("satellites", 0) ?: 0
        return DjiCommands.gpsData(
            yearMonthDay = ymd,
            hourMinuteSecond = hms,
            longitudeE7 = (fix.longitude * 1e7).toLong(),
            latitudeE7 = (fix.latitude * 1e7).toLong(),
            heightMm = (fix.altitude * 1000).toLong(),
            speedNorthCms = speedNorth,
            speedEastCms = speedEast,
            speedDownCms = 0f,
            verticalAccuracyMm = vertAcc,
            horizontalAccuracyMm = (fix.accuracy * 1000).toLong(),
            speedAccuracyCms = speedAcc,
            satellites = sats.toLong()
        )
    }
}
