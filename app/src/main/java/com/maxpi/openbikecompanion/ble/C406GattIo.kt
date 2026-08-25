package com.maxpi.openbikecompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic

object C406GattIo {

    enum class StartResult {
        STARTED,
        NOT_STARTED,
        PERMISSION_DENIED
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(gatt: BluetoothGatt): StartResult {
        return try {
            if (gatt.discoverServices()) {
                StartResult.STARTED
            } else {
                StartResult.NOT_STARTED
            }
        } catch (_: SecurityException) {
            StartResult.PERMISSION_DENIED
        }
    }

    @SuppressLint("MissingPermission")
    fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): StartResult {
        return try {
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value

            if (gatt.writeCharacteristic(characteristic)) {
                StartResult.STARTED
            } else {
                StartResult.NOT_STARTED
            }
        } catch (_: SecurityException) {
            StartResult.PERMISSION_DENIED
        }
    }
}
