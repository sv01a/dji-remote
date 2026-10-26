package com.dji360.watch.stream

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

/**
 * DJI Datalink client over UDP (port 9004) for Live Viewfinder video stream.
 *
 * Architecture:
 *   1. Poke TCP port 7001 on the camera to wake its socket listener.
 *   2. Bind local UDP socket to 0.0.0.0:0 (ephemeral port).
 *   3. Send 48-byte Handshake packet (pktType = 0x00) with fresh 8-aligned baseSeq.
 *   4. Receive Handshake ACK from camera.
 *   5. Send LiveView Enable DUML command (0x09/0xA8).
 *   6. Start periodic 1 Hz session registration heartbeat (0x00/0x88) and 30-40 Hz ACK pump (pktType 0x04).
 *   7. Dispatch incoming video packets (pktType = 0x02) to [VideoDepacketizer].
 */
class DatalinkClient(
    private val scope: CoroutineScope,
    private val onVideoFrame: (ByteArray) -> Unit,
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
    }

    private var socket: DatagramSocket? = null
    private var rxJob: Job? = null
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

    /**
     * Start the datalink live view session.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        depacketizer.reset()

        scope.launch(Dispatchers.IO) {
            log("Datalink: Poking TCP port $CAMERA_TCP_POKE_PORT...")
            pokeTcpPort()

            try {
                val s = DatagramSocket(0) // bind to ephemeral port (0.0.0.0:0)
                socket = s
                log("Datalink: Local UDP socket bound to port ${s.localPort}")

                // Random 8-aligned baseSeq
                baseSeq = (Random().nextInt(0x7F00) and 0xFFF8) or 0x0008
                sessionId = Random().nextInt(0xFFFF)
                cmdSeq = baseSeq + 100

                // Start receiver loop
                rxJob = launch(Dispatchers.IO) { receiveLoop(s) }

                // Send Handshake packet (48 bytes)
                val hsPacket = buildHandshakePacket(sessionId, 0, baseSeq)
                sendUdp(hsPacket)
                log("Datalink: Sent 48-byte handshake (baseSeq=0x%04X)".format(baseSeq))

                // Short delay before enabling liveview
                delay(200)

                // Send LiveView Enable command (0x09/0xA8)
                sendLiveViewEnable()

                // Start periodic ACK pump (~35 Hz) and Heartbeat (1 Hz)
                startAckPump()
                startHeartbeat()

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
        } catch (_: Exception) {}
        socket = null
        depacketizer.reset()
        log("Datalink: Stopped and socket closed")
    }

    /**
     * Connect briefly to TCP port 7001 to wake camera listener.
     */
    private fun pokeTcpPort() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(CAMERA_IP, CAMERA_TCP_POKE_PORT), 1000)
            s.close()
            log("Datalink: TCP 7001 poke successful")
        } catch (e: Exception) {
            log("Datalink: TCP 7001 poke ignored/failed: ${e.message}")
        }
    }

    /**
     * Inbound UDP packet receiver.
     */
    private fun receiveLoop(s: DatagramSocket) {
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

                when (pktType) {
                    PKT_VIDEO -> {
                        latestVideoSeq = seq
                        receivedVideoCount++
                        if (receivedVideoCount % 100 == 1) {
                            log("Datalink: Receiving video packets (count=$receivedVideoCount, seq=$seq)")
                        }
                        depacketizer.feed(buf.copyOf(len))
                    }
                    PKT_HANDSHAKE -> {
                        log("Datalink: Received Handshake ACK (seq=$seq)")
                    }
                    PKT_TELEMETRY -> {
                        // Periodic status / telemetry from camera
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
                log("Datalink: receive exception: ${e.message}")
            }
        }
    }

    /**
     * Sends a raw UDP packet to the camera.
     */
    private fun sendUdp(data: ByteArray) {
        val s = socket ?: return
        if (s.isClosed) return
        try {
            val addr = InetAddress.getByName(CAMERA_IP)
            val p = DatagramPacket(data, data.size, addr, CAMERA_UDP_PORT)
            s.send(p)
        } catch (e: Exception) {
            log("Datalink: UDP send error: ${e.message}")
        }
    }

    /**
     * Send Live View Enable command (DUML 0x09 / 0xA8).
     */
    private fun sendLiveViewEnable() {
        cmdSeq = (cmdSeq + 1) and 0xFFFF
        // DUML Frame: App(0x02) -> VideoTrans(0x08), CmdSet 0x09, CmdId 0xA8
        // Payload: [0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
        val payload = byteArrayOf(0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val dumlFrame = DumlFrame.build(
            DumlFrame.SENDER_APP, DumlFrame.RX_VIDEO_TRANS, cmdSeq,
            DumlFrame.FLAG_REQUEST, DumlFrame.CMDSET_LIVE_VIEW, DumlFrame.CMD_LIVE_VIEW_ENABLE,
            payload
        )
        val datagram = wrapCommand(dumlFrame, sessionId, cmdSeq)
        sendUdp(datagram)
        log("Datalink: Sent Live View Enable (0x09/0xA8, len=${datagram.size})")
    }

    /**
     * ACK Pump running at ~35 Hz to maintain camera transmit window.
     */
    private fun startAckPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch(Dispatchers.IO) {
            var ackSeq = 0
            while (isActive && isRunning) {
                ackSeq = (ackSeq + 1) and 0xFFFF
                val ack = buildAckPacket(sessionId, ackSeq, latestVideoSeq, baseSeq)
                sendUdp(ack)
                delay(30) // ~33 Hz
            }
        }
    }

    /**
     * 1 Hz Heartbeat to register session and keep video streaming.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                delay(1000)
                if (!isRunning) break
                // 0x00/0x88 APP registration frame
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

    companion object Helper {
        /**
         * 8-byte transport header: [u16le 0x8000 | (8+len)][sessionId][seq][pktType][xor]
         */
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

        /**
         * 12-byte routing header for command packet (pktType = 0x05).
         */
        fun routingHeader(seq: Int, cmdCounter: Int = 1): ByteArray {
            val ack = (seq - 8) and 0xFFFF
            val b = ByteArray(12)
            b[0] = (ack and 0xFF).toByte()
            b[1] = ((ack ushr 8) and 0xFF).toByte()
            b[2] = (seq and 0xFF).toByte()
            b[3] = ((seq ushr 8) and 0xFF).toByte()
            // 4..7 remain 0
            b[8] = (cmdCounter and 0xFF).toByte()
            b[9] = 0x01
            b[10] = 0x00
            b[11] = 0x00
            return b
        }

        /**
         * Wrap a DUML frame in a complete UDP Datalink command packet.
         */
        fun wrapCommand(dumlFrame: ByteArray, sessionId: Int, seq: Int): ByteArray {
            val routing = routingHeader(seq)
            val payload = routing + dumlFrame
            val header = transportHeader(PKT_COMMAND, payload.size, sessionId, seq)
            return header + payload
        }

        /**
         * 48-byte Handshake packet (8-byte transport + 40-byte payload).
         */
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

        /**
         * 34-byte Window ACK packet (8-byte transport + 26-byte payload).
         */
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
}
