package com.dji360.watch.stream

import android.net.Network
import com.dji360.watch.dji.DumlFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random

class DatalinkClient(
    private val scope: CoroutineScope,
    private val network: Network? = null,
    private val cameraIp: String = CAMERA_IP,
    private val onVideoFrame: (ByteArray) -> Unit,
    private val onStats: ((Int) -> Unit)? = null,
    private val log: (String) -> Unit
) {
    companion object {
        const val CAMERA_IP = "192.168.2.1"
        const val CAMERA_UDP_PORT = 9004
        const val CAMERA_TCP_POKE_PORT = 7001

        const val PKT_HANDSHAKE: Byte = 0x00
        const val PKT_TELEMETRY: Byte = 0x01
        const val PKT_VIDEO: Byte = 0x02
        const val PKT_ACKED_DATA: Byte = 0x03
        const val PKT_ACK: Byte = 0x04
        const val PKT_COMMAND: Byte = 0x05

        fun transportHeader(pktType: Int, payloadLen: Int, sessionId: Int, seq: Int): ByteArray {
            return transportHeader(pktType.toByte(), payloadLen, sessionId, seq)
        }

        fun transportHeader(pktType: Byte, payloadLen: Int, sessionId: Int, seq: Int): ByteArray {
            val total = 8 + payloadLen
            val w0 = 0x8000 or (total and 0x3FFF)
            val b = ByteArray(8)
            b[0] = (w0 and 0xFF).toByte()
            b[1] = ((w0 ushr 8) and 0xFF).toByte()
            b[2] = (sessionId and 0xFF).toByte()
            b[3] = ((sessionId ushr 8) and 0xFF).toByte()
            b[4] = (seq and 0xFF).toByte()
            b[5] = ((seq ushr 8) and 0xFF).toByte()
            b[6] = pktType
            var xor = 0
            for (i in 0 until 7) {
                xor = xor xor (b[i].toInt() and 0xFF)
            }
            b[7] = (xor and 0xFF).toByte()
            return b
        }

        fun routingHeader(seq: Int, cmdCounter: Int = 1): ByteArray {
            val ack = (seq - 8) and 0xFFFF
            val b = ByteArray(12)
            b[0] = (ack and 0xFF).toByte()
            b[1] = ((ack ushr 8) and 0xFF).toByte()
            b[2] = (seq and 0xFF).toByte()
            b[3] = ((seq ushr 8) and 0xFF).toByte()
            b[8] = (cmdCounter and 0xFF).toByte()
            b[9] = 0x01
            b[10] = 0x00
            b[11] = 0x00
            return b
        }

        fun wrapCommand(dumlFrame: ByteArray, sessionId: Int, seq: Int, counter: Int = 1): ByteArray {
            val routing = routingHeader(seq, counter)
            val payload = routing + dumlFrame
            val header = transportHeader(PKT_COMMAND, payload.size, sessionId, seq)
            return header + payload
        }

        fun buildHandshakePacket(sessionId: Int, seq: Int, baseSeq: Int): ByteArray {
            val payload = ByteArray(40)
            val template = byteArrayOf(
                0x00, 0x00, 0x64, 0x00, 0x64, 0x00, 0xC0.toByte(), 0x05, 0x14, 0x00, 0x00, 0x64, 0x00, 0x00,
                0x01, 0x90.toByte(), 0x01, 0xC0.toByte(), 0x05, 0x14, 0x00, 0x00, 0x64, 0x00, 0x14, 0x00,
                0x64, 0x00, 0xC0.toByte(), 0x05, 0x14, 0x00, 0x00, 0x64, 0x00, 0x01, 0x01, 0x04, 0x01, 0x02
            )
            template.copyInto(payload)
            payload[0] = (baseSeq and 0xFF).toByte()
            payload[1] = ((baseSeq ushr 8) and 0xFF).toByte()

            val header = transportHeader(PKT_HANDSHAKE, payload.size, sessionId, seq)
            return header + payload
        }

        fun buildAckPacket(sessionId: Int, videoCursor: Int, ackedSeq: Int, extraSeq: Int): ByteArray {
            fun grp(v: Int): ByteArray {
                val b = ByteArray(8)
                b[0] = (v and 0xFF).toByte()
                b[1] = ((v ushr 8) and 0xFF).toByte()
                b[2] = (v and 0xFF).toByte()
                b[3] = ((v ushr 8) and 0xFF).toByte()
                return b
            }
            val payload = grp(videoCursor) + grp(ackedSeq) + grp(extraSeq) + byteArrayOf(0, 0)
            val header = transportHeader(PKT_ACK, payload.size, sessionId, 0)
            return header + payload
        }
    }

    private var socket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var rxJob: Job? = null
    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile var isRunning: Boolean = false
        private set

    @Volatile var latestVideoSeq: Int = 0
        private set

    @Volatile var handshakeAcked: Boolean = false
        private set

    private var baseSeq: Int = 0x0068
    private var cmdSeq: Int = 0
    private var dumlSeq: Int = 0xA000
    private var cmdCounter: Int = 0
    private var sessionId: Int = 0x1234
    @Volatile private var ackedDataSeq: Int = 0x0068
    @Volatile private var extraCursor: Int = 0x0068
    private val sendLock = Any()
    private val depacketizer = VideoDepacketizer(onVideoFrame)

    fun start() {
        if (isRunning) return
        isRunning = true
        depacketizer.reset()

        scope.launch(Dispatchers.IO) {
            launch(Dispatchers.IO) {
                probeCameraPorts()
                log("Datalink: Poking TCP port $CAMERA_TCP_POKE_PORT at $cameraIp...")
                openTcpPoke()
            }

            try {
                val s = DatagramSocket(0)
                try {
                    network?.bindSocket(s)
                    log("Datalink: Bound DatagramSocket to network $network (local port ${s.localPort})")
                } catch (e: Exception) {
                    log("Datalink: bindSocket warning: ${e.message}")
                }
                socket = s
                log("Datalink: UDP ready -> target $cameraIp:9004")

                baseSeq = (Random().nextInt(0x7F00) and 0xFFF8) or 0x0008
                sessionId = Random().nextInt(0xFFFF)
                cmdSeq = baseSeq + 100

                rxJob = launch(Dispatchers.IO) { receiveLoop(s, 9004) }

                // Burst handshake packets until camera window is seeded
                launch(Dispatchers.IO) {
                    var hsAttempt = 0
                    while (isActive && isRunning && !handshakeAcked && hsAttempt < 20) {
                        hsAttempt++
                        val hsPacket = buildHandshakePacket(sessionId, hsAttempt, baseSeq)
                        sendUdp(hsPacket)
                        log("Datalink: Sent handshake #$hsAttempt (baseSeq=0x%04X)".format(baseSeq))
                        delay(150)
                    }
                }

                startAckPump()
                startHeartbeat()

                // Once handshake is acked and cmdSeq is seeded, run standard registration and single LiveView start
                launch(Dispatchers.IO) {
                    var waitCount = 0
                    while (isActive && isRunning && !handshakeAcked && waitCount < 30) {
                        delay(100)
                        waitCount++
                    }
                    log("Datalink: Starting registration pipeline (cmdSeq=$cmdSeq)...")
                    sendAppDeviceInfo()
                    delay(50)
                    sendAppPresence()
                    delay(50)
                    sendGimbalInit()
                    delay(150) // Settle after registration as required by DJI firmware

                    log("Datalink: Arming live video and sending LiveView enable...")
                    sendLiveEntryPrep()
                    sendLiveViewEnable(0x08) // Pocket target
                    sendLiveViewEnable(0x41) // Nano/Action target

                    // Bounded watchdog check (only resend after 2.5s if no video received yet)
                    var retryCount = 0
                    while (isActive && isRunning && latestVideoSeq == 0 && retryCount < 3) {
                        delay(2500)
                        if (latestVideoSeq == 0 && isRunning) {
                            retryCount++
                            log("Datalink: Video pending, retry enable #$retryCount...")
                            sendLiveViewEnable(0x08)
                            sendLiveViewEnable(0x41)
                        }
                    }
                    if (latestVideoSeq != 0) {
                        log("Datalink: Video stream ACTIVE! latestVideoSeq=$latestVideoSeq")
                    }
                }

            } catch (e: Exception) {
                log("Datalink: Start error: ${e.message}")
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        rxJob?.cancel(); rxJob = null
        pumpJob?.cancel(); pumpJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        tcpSocket = null
        depacketizer.reset()
        log("Datalink: Stopped and sockets closed")
    }

    private fun probeCameraPorts() {
        val ports = listOf(7001, 80, 8080, 554, 10004)
        val openPorts = mutableListOf<Int>()
        for (p in ports) {
            try {
                val sock = if (network != null) network.socketFactory.createSocket() else Socket()
                sock.connect(InetSocketAddress(cameraIp, p), 350)
                sock.close()
                openPorts.add(p)
            } catch (e: Exception) {}
        }
        log("Datalink: Open ports on $cameraIp: $openPorts")
    }

    private fun openTcpPoke() {
        try {
            val s = if (network != null) network.socketFactory.createSocket() else Socket()
            s.connect(InetSocketAddress(cameraIp, CAMERA_TCP_POKE_PORT), 1500)
            tcpSocket = s
            log("Datalink: TCP 7001 connected and held open")
            try {
                val pinPayload = byteArrayOf(0x04, 0x6F, 0x73, 0x6D, 0x6F) // packString("osmo")
                val pinFrame = DumlFrame.build(
                    DumlFrame.SENDER_APP, DumlFrame.RX_WIFI, 1,
                    DumlFrame.FLAG_REQUEST, 0x07, 0x45, pinPayload
                )
                s.getOutputStream().write(pinFrame)
                s.getOutputStream().flush()
                log("Datalink: Sent SetPairingPIN(osmo) to TCP 7001")
            } catch (e: Exception) {
                log("Datalink: TCP 7001 PIN warning: ${e.message}")
            }
        } catch (e: Exception) {
            log("Datalink: TCP 7001 closed (${e.message}) - continuing UDP")
        }
    }

    private fun receiveLoop(s: DatagramSocket, port: Int) {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        var receivedVideoCount = 0

        while (isRunning && !s.isClosed) {
            try {
                s.receive(packet)
                val len = packet.length
                if (len < 8) continue

                val pktType = buf[6]
                val seq = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)

                log("Datalink: RX [$port] pkt=0x%02X seq=%d len=%d".format(pktType, seq, len))
                when (pktType) {
                    PKT_VIDEO -> {
                        latestVideoSeq = seq
                        receivedVideoCount++
                        if (receivedVideoCount % 5 == 1) {
                            onStats?.invoke(receivedVideoCount)
                        }
                        depacketizer.feed(buf.copyOf(len))
                    }
                    PKT_HANDSHAKE -> {
                        log("Datalink: Received Handshake ACK (seq=$seq)")
                        onStats?.invoke(receivedVideoCount)
                    }
                    PKT_ACKED_DATA -> {
                        ackedDataSeq = seq
                        val hex = buf.take(minOf(len, 24)).joinToString(" ") { "%02X".format(it) }
                        log("Datalink: RX REPLY seq=$seq len=$len: $hex")
                    }
                    PKT_TELEMETRY -> {
                        if (pumpJob == null) startAckPump()
                        if (len == 34) {
                            val vidCur = (buf[10].toInt() and 0xFF) or ((buf[11].toInt() and 0xFF) shl 8)
                            val ackCur = (buf[18].toInt() and 0xFF) or ((buf[19].toInt() and 0xFF) shl 8)
                            val extCur = (buf[26].toInt() and 0xFF) or ((buf[27].toInt() and 0xFF) shl 8)
                            extraCursor = extCur
                            if (!handshakeAcked || cmdSeq == 0) {
                                val baseWindow = (buf[8].toInt() and 0xFF) or ((buf[9].toInt() and 0xFF) shl 8)
                                cmdSeq = (baseWindow + 8) and 0xFFFF
                                ackedDataSeq = ackCur
                                handshakeAcked = true
                                log("Datalink: Handshake ACKED! vid=$vidCur ack=$ackCur ext=$extCur -> cmdSeq=$cmdSeq")
                            }
                        }
                        val dumlOffset = (0 until minOf(len - 4, 32)).firstOrNull { buf[it] == 0x55.toByte() }
                        if (dumlOffset != null) {
                            try {
                                val duml = DumlFrame.parse(buf.copyOfRange(dumlOffset, len))
                                val ret = if (duml.payload.isNotEmpty()) "0x%02X".format(duml.payload[0]) else "none"
                                log("Datalink: DUML 0x%02X/0x%02X ret=%s (rcv=0x%02X) len=%d".format(
                                    duml.cmdSet, duml.cmdId, ret, duml.receiver, len
                                ))
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
                log("Datalink: receive exception: ${e.message}")
            }
        }
    }

    private fun sendUdp(data: ByteArray) {
        val dest = InetAddress.getByName(cameraIp)
        socket?.let { s ->
            if (!s.isClosed) {
                try {
                    val p = DatagramPacket(data, data.size, dest, 9004)
                    s.send(p)
                } catch (e: Exception) {
                    log("Datalink: UDP 9004 send error: ${e.message}")
                }
            }
        }
    }

    private fun sendCommand(dumlBytes: ByteArray) {
        synchronized(sendLock) {
            cmdCounter = (cmdCounter + 1) and 0xFF
            val datagram = wrapCommand(dumlBytes, sessionId, cmdSeq, cmdCounter)
            sendUdp(datagram)
            cmdSeq = (cmdSeq + 8) and 0xFFFF
        }
    }
    private fun sendAppDeviceInfo() {
        val b = ByteArray(62)
        b[1] = 0x41.toByte() // 'A'
        b[2] = 0x50.toByte() // 'P'
        b[3] = 0x50.toByte() // 'P'
        b[41] = 0x02.toByte()
        b[50] = 0x02.toByte()
        b[51] = 0x08.toByte()
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val frame = DumlFrame.build(
            DumlFrame.SENDER_APP, 0x48, dumlSeq,
            0x80, DumlFrame.CMDSET_COMMON, 0x81,
            b
        )
        sendCommand(frame)
        log("Datalink: Sent AppDeviceInfo (0x00/0x81 seq=$cmdSeq)")
    }

    private fun sendAppPresence() {
        val b = byteArrayOf(
            0x17, 0x00, 0x46, 0x23, 0x7C, 0x41, 0x50, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02
        )
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val frame = DumlFrame.build(
            DumlFrame.SENDER_APP, 0x28, dumlSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_COMMON, 0x88,
            b
        )
        sendCommand(frame)
        log("Datalink: Sent AppPresence (0x00/0x88 seq=$cmdSeq)")
    }

    private fun sendGimbalInit() {
        val b = byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val frame = DumlFrame.build(
            DumlFrame.SENDER_APP, 0x03, dumlSeq,
            DumlFrame.FLAG_REQUEST, 0x03, 0xDA.toByte().toInt() and 0xFF,
            b
        )
        sendCommand(frame)
        log("Datalink: Sent GimbalInit (0x03/0xDA seq=$cmdSeq)")
    }

    private fun sendLiveEntryPrep() {
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val payload = byteArrayOf(0x08)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, dumlSeq,
            DumlFrame.FLAG_REQUEST, 0x02, 0x68,
            payload
        )
        sendCommand(dumlFrame)
        log("Datalink: Sent Live Entry Prep (0x02/0x68 seq=$cmdSeq)")
    }

    private fun sendLiveViewEnable(rcv: Int) {
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        val payload = byteArrayOf(0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, rcv, dumlSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_LIVE_VIEW, DumlFrame.CMD_LIVE_VIEW_ENABLE,
            payload
        )
        sendCommand(dumlFrame)
        log("Datalink: Sent Live View Enable (0x09/0xA8 rcv=0x%02X seq=$cmdSeq)".format(rcv))
    }

    private fun startAckPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                val ack = buildAckPacket(sessionId, latestVideoSeq, ackedDataSeq, extraCursor)
                sendUdp(ack)
                delay(25) // 40 Hz window ACK pump
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                delay(1000)
                if (!isRunning) break
                sendAppPresence()
            }
        }
    }
}
