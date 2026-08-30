package com.dji360.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Minimal single-connection BLE GATT client for the DJI camera profile.
 * UUIDs come from the official DJI demo (ble/ble.c):
 *   service 0xFFF0, notify char 0xFFF4, write char 0xFFF5, CCCD 0x2902.
 * connect() suspends until notifications are fully enabled (or timeout).
 */
class BleClient(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val STATE_IDLE = 0
        const val STATE_CONNECTING = 1
        const val STATE_DISCOVERING = 2
        const val STATE_READY = 3

        private const val TAG = "BleClient"
    }

    private val _connectionState = MutableStateFlow(STATE_IDLE)
    val connectionState: StateFlow<Int> = _connectionState

    /** Camera → watch bytes from 0xFFF4. Empty array is emitted once on unexpected disconnect. */
    private val _notifications = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<ByteArray> = _notifications

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var setupDone: CompletableDeferred<Boolean>? = null

    val isReady: Boolean get() = _connectionState.value == STATE_READY && writeChar != null

    @SuppressLint("MissingPermission") // caller guarantees runtime grants
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 12_000): Boolean {
        disconnect()
        _connectionState.value = STATE_CONNECTING
        val done = CompletableDeferred<Boolean>()
        setupDone = done
        val g = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
        gatt = g
        val ok = runCatching { withTimeoutOrNull(timeoutMs) { done.await() } }.getOrNull() == true
        setupDone = null
        if (!ok) {
            log("connect/discovery/CCCD timed out or failed")
            disconnect()
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.let {
                it.disconnect()
                it.close()
            }
        } catch (_: Exception) {}
        gatt = null
        writeChar = null
        setupDone?.complete(false)
        setupDone = null
        if (_connectionState.value != STATE_IDLE) _connectionState.value = STATE_IDLE
    }

    @SuppressLint("MissingPermission")
    suspend fun write(data: ByteArray, withResponse: Boolean = false): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        if (_connectionState.value != STATE_READY) return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            log("write error: ${e.message}")
            false
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("gatt: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = STATE_DISCOVERING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = _connectionState.value == STATE_READY
                    _connectionState.value = STATE_IDLE
                    setupDone?.complete(false); setupDone = null
                    if (wasReady) {
                        g.close()
                        if (gatt === g) gatt = null
                        writeChar = null
                        _notifications.tryEmit(ByteArray(0)) // disconnect marker
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discoverServices failed: $status")
                setupDone?.complete(false); setupDone = null
                return
            }
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                log("service FFF0 not found; have: " +
                        g.services.joinToString(",") { it.uuid.toString().takeLast(8) })
                setupDone?.complete(false); setupDone = null
                return
            }
            val notify = svc.getCharacteristic(NOTIFY_UUID)
            val write = svc.getCharacteristic(WRITE_UUID)
            log("chars found: notify=${notify != null} write=${write != null}")
            if (notify == null || write == null) {
                setupDone?.complete(false); setupDone = null
                return
            }
            writeChar = write
            if (Build.VERSION.SDK_INT >= 21) {
                if (!g.requestMtu(185)) enableNotifications(g)
            } else {
                enableNotifications(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu status=$status")
            enableNotifications(g)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) {
                log("CCCD written, status=$status")
                _connectionState.value = STATE_READY
                setupDone?.complete(true); setupDone = null
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == NOTIFY_UUID) c.value?.let { _notifications.tryEmit(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt) {
        val notify = g.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_UUID) ?: run {
            setupDone?.complete(false); setupDone = null
            return
        }
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            log("CCCD descriptor missing — finishing optimistically")
            _connectionState.value = STATE_READY
            setupDone?.complete(true); setupDone = null
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("writeDescriptor rejected — finishing optimistically")
                    _connectionState.value = STATE_READY
                    setupDone?.complete(true); setupDone = null
                }
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(cccd)) {
                    log("writeDescriptor rejected — finishing optimistically")
                    _connectionState.value = STATE_READY
                    setupDone?.complete(true); setupDone = null
                }
            }
        } catch (e: Exception) {
            log("CCCD write threw: ${e.message}")
            _connectionState.value = STATE_READY
            setupDone?.complete(true); setupDone = null
        }
    }

    private fun log(msg: String) = android.util.Log.d(TAG, msg)
}
