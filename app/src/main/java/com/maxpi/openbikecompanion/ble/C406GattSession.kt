package com.maxpi.openbikecompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.maxpi.openbikecompanion.ble.C406GattIo.StartResult
import com.maxpi.openbikecompanion.protocol.C406Protocol.CC02_UUID
import com.maxpi.openbikecompanion.protocol.C406Protocol.CCCD_UUID
import com.maxpi.openbikecompanion.protocol.C406Protocol.CC_SERVICE_UUID

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class C406GattSession(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onStatus: (String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onGattError: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onWriteResult: (success: Boolean, status: Int) -> Unit,
    private val onValueReceived: (ByteArray) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var cc02Characteristic: BluetoothGattCharacteristic? = null
    private var connected = false

    var negotiatedMtu: Int = 23
        private set

    val isConnected: Boolean
        get() = connected

    val isReady: Boolean
        get() = connected && cc02Characteristic != null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connected = false
                cc02Characteristic = null
                negotiatedMtu = 23
                onGattError(status)
                closeGatt(gatt)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    negotiatedMtu = 23

                    onConnected(gatt.device.address)
                    onStatus("Connected; negotiating MTU...")

                    val mtuRequested = try {
                        gatt.requestMtu(247)
                    } catch (_: SecurityException) {
                        false
                    }

                    if (!mtuRequested) {
                        discoverServices(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    cc02Characteristic = null
                    negotiatedMtu = 23
                    onDisconnected()
                    closeGatt(gatt)
                }
            }
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                onStatus("MTU $mtu; discovering services...")
            } else {
                onStatus("MTU request failed; discovering services...")
            }

            discoverServices(gatt)
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(CC_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CC02_UUID)

            if (service == null || characteristic == null) {
                onStatus("C406 command service not found")
                return
            }

            cc02Characteristic = characteristic

            val notifyEnabled = try {
                gatt.setCharacteristicNotification(characteristic, true)
            } catch (_: SecurityException) {
                false
            }

            if (!notifyEnabled) {
                onStatus("Could not enable CC02 notifications")
                return
            }

            val cccd = characteristic.getDescriptor(CCCD_UUID)

            if (cccd == null) {
                onStatus("CC02 notification descriptor not found")
                return
            }

            try {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = gatt.writeDescriptor(cccd)

                onStatus(
                    if (started) {
                        "Enabling notifications..."
                    } else {
                        "Could not write notification descriptor"
                    }
                )
            } catch (_: SecurityException) {
                onStatus("Bluetooth permission lost")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Notification setup failed: $status")
                return
            }

            onStatus("Connected; reading Pages...")
            onReady()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CC02_UUID) return

            onWriteResult(
                status == BluetoothGatt.GATT_SUCCESS,
                status
            )
        }

        @Deprecated(
            "Deprecated in newer Android APIs; required for API 24 compatibility"
        )
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != CC02_UUID) return

            characteristic.value?.let(onValueReceived)
        }
    }

    fun connect(address: String) {
        val device = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }

        if (device == null) {
            onStatus("Could not get Bluetooth device")
            return
        }

        bluetoothGatt = try {
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (_: SecurityException) {
            null
        }

        if (bluetoothGatt == null) {
            onStatus("Could not start GATT connection")
        }
    }

    fun disconnect() {
        val gatt = bluetoothGatt

        bluetoothGatt = null
        cc02Characteristic = null
        connected = false
        negotiatedMtu = 23

        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
            }

            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }
    }

    fun write(value: ByteArray): StartResult {
        val gatt = bluetoothGatt
        val characteristic = cc02Characteristic

        if (gatt == null || characteristic == null) {
            return StartResult.NOT_STARTED
        }

        return C406GattIo.write(
            gatt = gatt,
            characteristic = characteristic,
            value = value
        )
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        when (C406GattIo.discoverServices(gatt)) {
            StartResult.STARTED ->
                onStatus("Discovering services...")

            StartResult.NOT_STARTED ->
                onStatus("Could not start service discovery")

            StartResult.PERMISSION_DENIED ->
                onStatus("Bluetooth permission lost")
        }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
        }

        try {
            gatt.close()
        } catch (_: Exception) {
        }
    }
}
