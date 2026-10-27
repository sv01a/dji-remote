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

        fun wrapCommand(dumlFrame: ByteArray, sessionId: Int, seq: Int): ByteArray {
            val routing = routingHeader(seq)
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

        fun buildAckPacket(sessionId: Int, seq: Int, videoCursor: Int, baseSeq: Int): ByteArray {
            fun grp(v: Int): ByteArray {
                val b = ByteArray(8)
                b[0] = (v and 0xFF).toByte()
                b[1] = ((v ushr 8) and 0xFF).toByte()
                b[2] = (v and 0xFF).toByte()
                b[3] = ((v ushr 8) and 0xFF).toByte()
                return b
            }
            val payload = grp(videoCursor) + grp(baseSeq) + grp(baseSeq) + byteArrayOf(0, 0)
            val header = transportHeader(PKT_ACK, payload.size, sessionId, seq)
            return header + payload
        }
    }

    private var socket: DatagramSocket? = null
    private var socket10k: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var rxJob: Job? = null
    private var rxJob10k: Job? = null
    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile var isRunning: Boolean = false
        private set

    @Volatile var latestVideoSeq: Int = 0
        private set

    private var baseSeq: Int = 0x0068
    private var cmdSeq: Int = 0x1000
    private var sessionId: Int = 0x1234
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
                val s = DatagramSocket(null)
                val s2 = DatagramSocket(null)
                try {
                    network?.bindSocket(s)
                    network?.bindSocket(s2)
                    log("Datalink: Bound dual DatagramSockets to network")
                } catch (e: Exception) {
                    log("Datalink: bindSocket warning: ${e.message}")
                }
                s.bind(InetSocketAddress(0))
                s2.bind(InetSocketAddress(0))
                try {
                    s.connect(InetAddress.getByName(cameraIp), 9004)
                    log("Datalink: UDP 9004 connected from ${s.localAddress?.hostAddress}:${s.localPort} -> $cameraIp:9004")
                } catch (e: Exception) {
                    log("Datalink: UDP 9004 connect error: ${e.message}")
                }
                try {
                    s2.connect(InetAddress.getByName(cameraIp), 10004)
                    log("Datalink: UDP 10004 connected from ${s2.localAddress?.hostAddress}:${s2.localPort} -> $cameraIp:10004")
                } catch (e: Exception) {
                    log("Datalink: UDP 10004 connect error: ${e.message}")
                }
                socket = s
                socket10k = s2

                baseSeq = (Random().nextInt(0x7F00) and 0xFFF8) or 0x0008
                sessionId = Random().nextInt(0xFFFF)
                cmdSeq = baseSeq + 100

                rxJob = launch(Dispatchers.IO) { receiveLoop(s, 9004) }
                rxJob10k = launch(Dispatchers.IO) { receiveLoop(s2, 10004) }

                // Burst handshake packets until camera replies or up to 8 attempts
                launch(Dispatchers.IO) {
                    var hsAttempt = 0
                    while (isActive && isRunning && latestVideoSeq == 0 && hsAttempt < 8) {
                        val hsPacket = buildHandshakePacket(sessionId, hsAttempt, baseSeq)
                        sendUdp(hsPacket)
                        log("Datalink: Sent handshake #$hsAttempt (baseSeq=0x%04X)".format(baseSeq))
                        hsAttempt++
                        delay(250)
                    }
                }

                delay(150)
                sendRegistration()
                sendSessionWake()
                sendLiveGate()
                sendLiveEntryPrep()
                sendLiveViewEnable(0x41)
                sendLiveViewEnable(DumlFrame.RX_VIDEO_TRANS)
                sendLiveViewEnable(DumlFrame.RX_CAMERA)

                startAckPump()
                startHeartbeat()

                // Kickstart retry at 2s if no video packets received yet
                launch(Dispatchers.IO) {
                    delay(2000)
                    if (isRunning && latestVideoSeq == 0) {
                        log("Datalink: No video packets after 2s, resending LiveView enable burst...")
                        sendSessionWake()
                        sendLiveGate()
                        sendLiveViewEnable(0x41)
                        sendLiveViewEnable(DumlFrame.RX_VIDEO_TRANS)
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
        rxJob10k?.cancel(); rxJob10k = null
        pumpJob?.cancel(); pumpJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        try {
            socket10k?.close()
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
                    PKT_TELEMETRY -> {}
                }
            } catch (e: Exception) {
                if (!isRunning) break
                log("Datalink: receive exception: ${e.message}")
            }
        }
    }

    private fun sendUdp(data: ByteArray) {
        socket?.let { s ->
            if (!s.isClosed) {
                try {
                    val p = if (s.isConnected) DatagramPacket(data, data.size)
                    else DatagramPacket(data, data.size, InetAddress.getByName(cameraIp), 9004)
                    s.send(p)
                } catch (e: Exception) {
                    log("Datalink: UDP 9004 send error: ${e.message}")
                }
            }
        }
        socket10k?.let { s2 ->
            if (!s2.isClosed) {
                try {
                    val p = if (s2.isConnected) DatagramPacket(data, data.size)
                    else DatagramPacket(data, data.size, InetAddress.getByName(cameraIp), 10004)
                    s2.send(p)
                } catch (e: Exception) {
                    log("Datalink: UDP 10004 send error: ${e.message}")
                }
            }
        }
    }

    private fun sendRegistration() {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        val regFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, cmdSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_COMMON, 0x88,
            byteArrayOf(0x17) + "APP".toByteArray()
        )
        val datagram = wrapCommand(regFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Registration (0x00/0x88)")
    }

    private fun sendSessionWake() {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, cmdSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_COMMON, 0x2B,
            byteArrayOf(0x01)
        )
        val datagram = wrapCommand(dumlFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Session Wake (0x00/0x2B)")
    }

    private fun sendLiveGate() {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x03)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, cmdSeq,
            DumlFrame.FLAG_REQUEST, 0x02, 0x09,
            payload
        )
        val datagram = wrapCommand(dumlFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Live Gate (0x02/0x09)")
    }

    private fun sendLiveEntryPrep() {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        val payload = byteArrayOf(0x08)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, cmdSeq,
            DumlFrame.FLAG_REQUEST, 0x02, 0x68,
            payload
        )
        val datagram = wrapCommand(dumlFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Live Entry Prep (0x02/0x68)")
    }

    private fun sendLiveViewEnable(rcv: Int) {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        val payload = byteArrayOf(0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, rcv, cmdSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_LIVE_VIEW, DumlFrame.CMD_LIVE_VIEW_ENABLE,
            payload
        )
        val datagram = wrapCommand(dumlFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Live View Enable (0x09/0xA8 to rcv=0x%02X, len=${datagram.size})".format(rcv))
    }

    private fun startAckPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch(Dispatchers.IO) {
            var ackSeq = 0
            while (isActive && isRunning) {
                ackSeq = (ackSeq + 1) and 0xFFFF
                val ack = buildAckPacket(sessionId, ackSeq, latestVideoSeq, baseSeq)
                sendUdp(ack)
                delay(30)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                delay(1000)
                if (!isRunning) break
                cmdSeq = (cmdSeq + 1) and 0xFFFF
                val regFrame = DumlFrame.build(
                    DumlFrame.SENDER_APP, DumlFrame.RX_CAMERA, cmdSeq,
                    DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_COMMON, 0x88,
                    byteArrayOf(0x17) + "APP".toByteArray()
                )
                val datagram = wrapCommand(regFrame, sessionId, cmdSeq)
                sendUdp(datagram)
            }
        }
    }
}
