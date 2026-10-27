package com.dji360.watch.dji

import com.dji360.watch.ble.BleClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * DJI R SDK protocol link on top of [BleClient].
 * Implements the handshake exactly as logic/connect_logic.c of the official demo:
 *
 *  1) we send 0x00/0x19 connection request (verify_mode from constructor),
 *  2) camera acks (0x20-frame) — or goes straight to its own command frame,
 *  3) camera sends its own 0x00/0x19 command with verify_mode=2;
 *     verify_data 0 = accepted, 1 = rejected,
 *  4) we answer with an ack (ret_code=0, reserved=camera number).
 *  → PROTOCOL_CONNECTED. Then 0x1D/0x05 status subscription (push_mode=3, 2 Hz).
 */
class DjiLink(
    private val ble: BleClient,
    private val scope: CoroutineScope,
    private val localMacBytes: ByteArray,
    private val log: (String) -> Unit
) {
    enum class Phase { IDLE, BLE_READY, REQUEST_SENT, WAITING_CAMERA, CONNECTED, REJECTED, FAILED }

    data class CameraState(
        val mode: Int = 0,
        val status: Int = 0,
        val resolution: Int = 0,
        val fpsIdx: Int = 0,
        val recordTimeSec: Int = 0,
        val remainCapacityMb: Long = 0,
        val remainPhotoNum: Long = 0,
        val remainRecordSec: Long = 0,
        val powerMode: Int = 0,
        val tempState: Int = 0,
        val batteryPercent: Int = -1,
        /** true only after we actually parsed a 0x1D02 status push. */
        val hasStatus: Boolean = false
    ) {
        val isRecording: Boolean get() = status == 0x03 || status == 0x05
    }

    var deviceName: String = ""
    var cameraDeviceId: Long = DjiCommands.DEVICE_ID_OSMO_360.toLong()
    var verifyMode: Int = 0
        private set

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    private val _camera = MutableStateFlow(CameraState())
    val camera: StateFlow<CameraState> = _camera

    private val _modeName = MutableStateFlow<String?>(null)
    val modeName: StateFlow<String?> = _modeName

    private val _modeParam = MutableStateFlow<String?>(null)
    val modeParam: StateFlow<String?> = _modeParam

    private var seq = 0
    private var collectJob: Job? = null
    private var lastMode = -1

    // Single in-flight command at a time (rule #37).
    private val cmdMutex = Mutex()
    private val dumlWaiters = ConcurrentHashMap<Int, CompletableDeferred<DumlFrame>>()

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFFFF
        return seq
    }

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            ble.notifications.collect { bytes ->
                if (bytes.isEmpty()) {
                    // disconnect marker from BleClient
                    if (_phase.value == Phase.CONNECTED) {
                        log("DJI: BLE lost → IDLE")
                        _phase.value = Phase.IDLE
                    }
                    return@collect
                }
                onFrame(bytes)
            }
        }
    }

    fun stop() {
        collectJob?.cancel(); collectJob = null
        _phase.value = Phase.IDLE
        lastMode = -1
        _modeName.value = null
        _modeParam.value = null
        dumlWaiters.values.forEach { it.cancel() }
        dumlWaiters.clear()
    }

    /** Feed one notification; frames never span BLE packets in practice (MTU negotiated). */
    private fun onFrame(bytes: ByteArray) {
        if (bytes.isNotEmpty() && bytes[0] == DumlFrame.SOF) {
            onDumlFrame(bytes)
            return
        }
        val f = try {
            DjiFrame.parse(bytes)
        } catch (e: DjiFrame.FrameException) {
            log("DJI RX parse: ${e.message}")
            return
        }
        log("DJI RX: ${DjiFrame.hex(bytes)}")
        if (f.isAck && f.cmdId != DjiCommands.CMD_CONNECTION_REQUEST) {
            // Response to one of OUR commands — store for seq matching in sendCommandWaitAck.
            if (acks.size > 16) acks.clear()
            acks[f.seq] = f.payload
        }
        when {
            f.cmdSet == DjiCommands.CMDSET_COMMON && f.cmdId == DjiCommands.CMD_CONNECTION_REQUEST ->
                handleConnectionFrame(f)
            // Camera status pushes arrive as COMMAND frames (not ACKs) —
            // matches dji_protocol_data_descriptors.c of the official demo.
            f.cmdSet == DjiCommands.CMDSET_CAMERA && f.cmdId == DjiCommands.CMD_CAMERA_STATUS_PUSH ->
                parseStatusPush(f.payload)
            f.cmdSet == DjiCommands.CMDSET_CAMERA && f.cmdId == DjiCommands.CMD_NEW_STATUS_PUSH ->
                parseNewStatusPush(f.payload)
        }
    }

    private fun onDumlFrame(bytes: ByteArray) {
        val f = try {
            DumlFrame.parse(bytes)
        } catch (e: Exception) {
            log("DUML RX parse error: ${e.message}")
            return
        }
        log("DUML RX: [0x%02X->0x%02X] cmd=0x%02X/0x%02X seq=%d flags=0x%02X len=%d".format(
            f.sender, f.receiver, f.cmdSet, f.cmdId, f.seq, f.flags, f.payload.size
        ))
        val key = (f.cmdSet shl 8) or f.cmdId
        val waiter = dumlWaiters.remove(key)
        waiter?.complete(f)
    }

    private fun handleConnectionFrame(f: DjiFrame.Parsed) {
        if (!f.isAck) {
            // Command frame from the camera (33 bytes expected)
            if (f.payload.size < 33) {
                log("DJI: camera conn cmd too short: ${f.payload.size}")
                return
            }
            // Layout per docs: device_id@0..3, mac_len@4, mac@5..20, fw@21..24,
            // conidx@25, verify_mode@26, verify_data@27..28 (confirmed live on Osmo 360)
            val camVerifyMode = f.payload[26].toInt() and 0xFF
            val camVerifyData = (f.payload[27].toInt() and 0xFF) or (f.payload[28].toInt() and 0xFF shl 8)
            val camDeviceId = readU32(f.payload, 0)
            log("DJI: camera conn cmd: verify_mode=$camVerifyMode verify_data=$camVerifyData dev=0x%04X".format(camDeviceId))
            if (camVerifyMode != 0 && camVerifyMode != 2) {
                log("DJI: unexpected camera verify_mode=$camVerifyMode")
                return
            }
            camDeviceId.takeIf { it != 0L }?.let { cameraDeviceId = it }
            if (camVerifyData == 0) {
                // accepted → send our ack with camera number 0 (single camera)
                val payload = DjiCommands.connectionResponse(cameraDeviceId, 0, 0)
                val ack = DjiFrame.build(
                    DjiCommands.CMDSET_COMMON, DjiCommands.CMD_CONNECTION_REQUEST,
                    DjiFrame.ACK_NO_RESPONSE, f.seq, payload
                )
                scope.launch {
                    ble.write(ack, withResponse = false)
                    kotlinx.coroutines.delay(200)
                    log("DJI: PROTOCOL_CONNECTED (device_id=0x%04X)".format(cameraDeviceId))
                    _phase.value = Phase.CONNECTED
                    subscribeStatus()
                }
            } else {
                log("DJI: camera REJECTED the connection")
                _phase.value = Phase.REJECTED
            }
        } else {
            // Ack to OUR request: payload = device_id(4) + ret_code(1) + reserved(4)
            if (f.payload.size >= 5) {
                val ret = f.payload[4].toInt() and 0xFF
                log("DJI: conn request ack: ret_code=$ret seq=${f.seq}")
                if (_phase.value == Phase.REQUEST_SENT) _phase.value = Phase.WAITING_CAMERA
            }
        }
    }

    /**
     * Run the full DJI handshake. Call after ble.connect() succeeded.
     * firstPairing=true → verify_mode=1 (camera shows code popup);
     * false → verify_mode=0 (decide by saved pairing history).
     */
    suspend fun handshake(firstPairing: Boolean, timeoutMs: Long = 10_000): Boolean {
        start() // Ensure notification collection is active
        if (!ble.isReady) {
            log("DJI: BLE not ready")
            return false
        }
        _phase.value = Phase.BLE_READY
        verifyMode = if (firstPairing) 1 else 0
        val verifyData = (0..9999).random()
        val payload = DjiCommands.connectionRequest(cameraDeviceId, localMacBytes, verifyMode, verifyData)
        val frame = DjiFrame.build(
            DjiCommands.CMDSET_COMMON, DjiCommands.CMD_CONNECTION_REQUEST,
            DjiFrame.CMD_WAIT_RESULT, nextSeq(), payload
        )
        log("DJI TX: ${DjiFrame.hex(frame)}")
        _phase.value = Phase.REQUEST_SENT
        if (!ble.write(frame, withResponse = true)) {
            log("DJI: write failed")
            _phase.value = Phase.FAILED
            return false
        }
        // Ack or direct camera command arrives via notification flow.
        val ok = withTimeoutOrNull(timeoutMs) {
            while (_phase.value != Phase.CONNECTED && _phase.value != Phase.REJECTED) {
                kotlinx.coroutines.delay(100)
            }
            _phase.value == Phase.CONNECTED
        } ?: false
        if (!ok && _phase.value != Phase.REJECTED) {
            log("DJI: handshake timeout")
            _phase.value = Phase.FAILED
        }
        return ok
    }

    /** 0x1D/0x05 subscription: push_mode=3 (periodic + on-change), 2 Hz. */
    private fun subscribeStatus() {
        val frame = DjiFrame.build(
            DjiCommands.CMDSET_CAMERA, DjiCommands.CMD_STATUS_SUBSCRIPTION,
            DjiFrame.CMD_NO_RESPONSE, nextSeq(), DjiCommands.statusSubscription()
        )
        scope.launch {
            log("DJI TX sub: ${DjiFrame.hex(frame)}")
            ble.write(frame, withResponse = false)
            // Retry once after 500ms to guarantee camera receives subscription
            kotlinx.coroutines.delay(500)
            if (_phase.value == Phase.CONNECTED) {
                ble.write(frame, withResponse = false)
            }
        }
    }

    /** Generic command send, serialized; returns response payload or null (no ack within timeout). */
    private suspend fun sendCommandWaitAck(
        cmdSet: Int, cmdId: Int, payload: ByteArray,
        timeoutMs: Long = 3000
    ): ByteArray? = cmdMutex.withLock {
        if (_phase.value != Phase.CONNECTED) return null
        val mySeq = nextSeq()
        val frame = DjiFrame.build(cmdSet, cmdId, DjiFrame.CMD_RESPONSE_OR_NOT, mySeq, payload)
        log("DJI TX: ${DjiFrame.hex(frame)}")
        if (!ble.write(frame, withResponse = true)) return null
        // wait for ack frame with same seq
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            acks[mySeq]?.let {
                acks.remove(mySeq)
                return it
            }
            kotlinx.coroutines.delay(20)
        }
        return null
    }

    // ack frames matched by seq (filled from onFrame)
    private val acks = HashMap<Int, ByteArray>()

    suspend fun startRecording(): Boolean {
        val r = sendCommandWaitAck(
            DjiCommands.CMDSET_CAMERA, DjiCommands.CMD_RECORD_CONTROL,
            DjiCommands.recordControl(cameraDeviceId, start = true)
        )
        val ok = r != null && (r.size < 1 || r[0].toInt() == 0)
        log("DJI startRecording → ${if (ok) "OK" else "no/err ack"}")
        return ok
    }

    suspend fun stopRecording(): Boolean {
        val r = sendCommandWaitAck(
            DjiCommands.CMDSET_CAMERA, DjiCommands.CMD_RECORD_CONTROL,
            DjiCommands.recordControl(cameraDeviceId, start = false)
        )
        val ok = r != null && (r.size < 1 || r[0].toInt() == 0)
        log("DJI stopRecording → ${if (ok) "OK" else "no/err ack"}")
        return ok
    }

    suspend fun switchMode(mode: Int): Boolean {
        val r = sendCommandWaitAck(
            DjiCommands.CMDSET_CAMERA, DjiCommands.CMD_MODE_SWITCH,
            DjiCommands.modeSwitch(cameraDeviceId, mode)
        )
        val ok = r != null && (r.size < 1 || r[0].toInt() == 0)
        log("DJI switchMode(0x%02X) → %s".format(mode, if (ok) "OK" else "no/err ack"))
        return ok
    }

    /** GPS Data Push 0x00/0x17 — fire and forget (CMD_NO_RESPONSE, ack optional). */
    suspend fun pushGps(payload: ByteArray): Boolean = cmdMutex.withLock {
        if (_phase.value != Phase.CONNECTED) return false
        val frame = DjiFrame.build(
            DjiCommands.CMDSET_COMMON, DjiCommands.CMD_GPS_PUSH,
            DjiFrame.CMD_NO_RESPONSE, nextSeq(), payload
        )
        ble.write(frame, withResponse = false)
    }

    /** Key reporting 0x00/0x11 — used for SNAPSHOT (photo) / QS. */
    suspend fun keyReport(keyCode: Int): Boolean {
        val r = sendCommandWaitAck(
            DjiCommands.CMDSET_COMMON, DjiCommands.CMD_KEY_REPORT,
            DjiCommands.keyReport(keyCode)
        )
        val ok = r != null && (r.size < 1 || r[0].toInt() == 0)
        log("DJI keyReport(0x%02X) → %s".format(keyCode, if (ok) "OK" else "no/err ack"))
        return ok
    }

    // ---------- DUML requests (WiFi, liveview, settings) ----------

    suspend fun sendDumlRequest(
        cmdSet: Int,
        cmdId: Int,
        frameBuilder: (seq: Int) -> ByteArray,
        timeoutMs: Long = 4000
    ): DumlFrame? = cmdMutex.withLock {
        if (!ble.isReady) return null
        val key = (cmdSet shl 8) or cmdId
        val deferred = CompletableDeferred<DumlFrame>()
        dumlWaiters[key] = deferred
        val s = nextSeq()
        val frame = frameBuilder(s)
        log("DUML TX: ${DumlFrame.hex(frame)}")
        val written = ble.write(frame, withResponse = false)
        if (!written) {
            dumlWaiters.remove(key)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            dumlWaiters.remove(key)
        }
    }

    suspend fun queryWifiSsid(timeoutMs: Long = 4000): String? {
        val reply = sendDumlRequest(DumlFrame.CMDSET_WIFI, DumlFrame.CMD_WIFI_GET_SSID, { seq ->
            DumlFrame.queryWifiSsid(seq)
        }, timeoutMs) ?: return null
        return DumlFrame.unpackStatusString(reply.payload)
    }

    suspend fun queryWifiPassword(timeoutMs: Long = 4000): String? {
        val reply = sendDumlRequest(DumlFrame.CMDSET_WIFI, DumlFrame.CMD_WIFI_GET_PASSWORD, { seq ->
            DumlFrame.queryWifiPassword(seq)
        }, timeoutMs) ?: return null
        return DumlFrame.unpackStatusString(reply.payload)
    }

    suspend fun queryWifiMac(timeoutMs: Long = 4000): String? {
        val reply = sendDumlRequest(DumlFrame.CMDSET_WIFI, DumlFrame.CMD_WIFI_GET_MAC, { seq ->
            DumlFrame.queryWifiMac(seq)
        }, timeoutMs) ?: return null
        if (reply.payload.size >= 7 && reply.payload[0] == 0.toByte()) {
            return reply.payload.copyOfRange(1, 7).joinToString(":") { "%02X".format(it) }
        }
        return null
    }

    suspend fun sendLiveViewEnableBle(): Boolean {
        log("DJI: Sending Live View Enable over BLE (0x09/0xA8)...")
        val payload = byteArrayOf(0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val s1 = nextSeq()
        val frame1 = DumlFrame.build(DumlFrame.SENDER_APP, 0x41, s1, DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_LIVE_VIEW, DumlFrame.CMD_LIVE_VIEW_ENABLE, payload)
        ble.write(frame1, withResponse = false)
        kotlinx.coroutines.delay(50)
        val s2 = nextSeq()
        val frame2 = DumlFrame.build(DumlFrame.SENDER_APP, DumlFrame.RX_VIDEO_TRANS, s2, DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_LIVE_VIEW, DumlFrame.CMD_LIVE_VIEW_ENABLE, payload)
        ble.write(frame2, withResponse = false)
        return true
    }

    suspend fun queryWifiCredentials(timeoutMs: Long = 8000): Pair<String, String>? {
        log("DJI: Querying camera Wi-Fi SSID over BLE...")
        val ssid = queryWifiSsid(timeoutMs / 2)
        if (ssid == null) {
            log("DJI: No Wi-Fi SSID reply from camera")
            return null
        }
        log("DJI: Camera Wi-Fi SSID = '$ssid'")
        kotlinx.coroutines.delay(200) // Settle BLE GATT queue between writes
        log("DJI: Querying camera Wi-Fi password over BLE...")
        val password = queryWifiPassword(timeoutMs / 2) ?: ""
        log("DJI: Camera Wi-Fi password received (len=${password.length})")
        return ssid to password
    }

    // ---------- parsers (layouts from dji_protocol_data_structures.h) ----------

    private fun readU16(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8)
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        if (off + 3 >= b.size) return 0
        return (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun parseStatusPush(p: ByteArray) {
        // Packed layout of camera_status_push_command_frame (total 38 bytes):
        // mode@0 status@1 res@2 fps@3 eis@4 rec_time@5..6 fov@7 ratio@8 countdown@9..10
        // tl_int@11..12 tl_dur@13..14 remain_cap@15..18 remain_photo@19..22 remain_time@23..26
        // user_mode@27 power_mode@28 next_flag@29 temp@30 photo_cd@31..34 loop@35..36 battery@37
        if (p.size < 38) {
            log("DJI 1D02 unexpected size=${p.size}: ${DjiFrame.hex(p)}")
            return
        }
        val mode = p[0].toInt() and 0xFF
        // Numeric mode changed → the 0x1D06 name we hold is now stale; drop it
        // so the UI doesn't keep showing the previous mode's name.
        if (lastMode != -1 && mode != lastMode) {
            _modeName.value = null
            _modeParam.value = null
        }
        lastMode = mode
        val s = CameraState(
            mode = mode,
            status = p[1].toInt() and 0xFF,
            resolution = p[2].toInt() and 0xFF,
            fpsIdx = p[3].toInt() and 0xFF,
            recordTimeSec = readU16(p, 5),
            remainCapacityMb = readU32(p, 15),
            remainPhotoNum = readU32(p, 19),
            remainRecordSec = readU32(p, 23),
            powerMode = p[28].toInt() and 0xFF,
            tempState = p[30].toInt() and 0xFF,
            batteryPercent = p[37].toInt() and 0xFF,
            hasStatus = true
        )
        _camera.value = s
        log("DJI status: mode=0x%02X st=0x%02X rec=%ds bat=%d%%".format(
            s.mode, s.status, s.recordTimeSec, s.batteryPercent))
    }

    private fun parseNewStatusPush(p: ByteArray) {
        if (p.size < 46) {
            log("DJI 1D06 too short: ${p.size}")
            return
        }
        // [0]=type 0x01, [1]=len, [2..22] name; [23]=type 0x02, [24]=len, [25..45] param
        val nameLen = p[1].toInt() and 0xFF
        val name = p.copyOfRange(2, 2 + nameLen.coerceIn(0, 21)).toString(Charsets.US_ASCII)
        val paramLen = p[24].toInt() and 0xFF
        val param = if (paramLen in 1..21) p.copyOfRange(25, 25 + paramLen).toString(Charsets.US_ASCII) else ""
        _modeName.value = name.trim()
        _modeParam.value = param.trim()
        log("DJI 1D06: name='$name' param='$param'")
    }

    companion object {
        /**
         * Icon for the mode, resolved from the New Camera Status Push name (0x1D06),
         * which is the authoritative source for Osmo 360. Falls back to the numeric
         * mode code (0x1D02) only when no name is available.
         */
        fun modeIconFor(modeName: String?, mode: Int): String {
            val n = modeName?.trim()?.lowercase() ?: ""
            val is360 = n.contains("360") || n.contains("panoram")
            val isPhoto = n.contains("photo") || n.contains("photos")
            return when {
                n.contains("supernight") || n.contains("night") ->
                    if (is360) "🌌" else "🌙"
                is360 && isPhoto -> "🌐📷"
                is360 -> "🌐"
                n.contains("selfie") -> "🤳"
                n.contains("boost") -> "🚀"
                n.contains("vortex") -> "🪐"
                n.contains("slow") || n.contains("slow motion") -> "🐢"
                n.contains("hyperlapse") -> "🌀"
                n.contains("timelapse") -> "⏱️"
                isPhoto -> "📷"
                n.contains("video") -> "🎥"
                n.contains("follow") || n.contains("track") -> "🎯"
                n.contains("live") -> "🔴"
                n.contains("uvc") -> "🖥️"
                n.isNotEmpty() -> "🎥"
                else -> modeIcon(mode)
            }
        }

        /** Emoji icon per camera mode (compact watch UI). */
        fun modeIcon(mode: Int): String = when (mode) {
            0x00 -> "🐢"
            0x01 -> "🎥"
            0x02 -> "⏱️"
            0x05 -> "📷"
            0x0A -> "⏩"
            0x1A -> "🔴"
            0x23 -> "🖥️"
            0x28 -> "🌙"
            0x34 -> "🎯"
            0x38 -> "🌐"
            0x3A -> "🌀"
            0x3C -> "🤳"
            0x3F -> "🌐📷"
            0x41 -> "🚀"
            0x43 -> "🪐"
            0x44 -> "🌌"
            0x4A -> "🌟"
            else -> "🎥"
        }

        /** Human-readable camera mode names (standard + Osmo 360 specific). */
        fun modeToString(mode: Int): String = when (mode) {
            0x00 -> "SlowMotion"
            0x01 -> "Video"
            0x02 -> "Timelapse"
            0x05 -> "Photo"
            0x0A -> "Hyperlapse"
            0x1A -> "Live"
            0x23 -> "UVC"
            0x28 -> "SuperNight"
            0x34 -> "Follow"
            0x38 -> "360 Video"
            0x3A -> "Hyperlapse360"
            0x3C -> "Selfie"
            0x3F -> "360 Photo"
            0x41 -> "Boost"
            0x43 -> "Vortex"
            0x44 -> "360 Night"
            0x4A -> "1L Night"
            else -> String.format(Locale.US, "0x%02X", mode)
        }
    }
}
