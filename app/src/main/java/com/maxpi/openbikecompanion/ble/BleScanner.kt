package com.maxpi.openbikecompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.maxpi.openbikecompanion.model.BleDeviceUi

class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onDeviceFound: (BleDeviceUi) -> Unit,
    private val onScanFailed: (Int) -> Unit
) {
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "(unnamed)"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "(unnamed)"
            }

            onDeviceFound(
                BleDeviceUi(
                    address = device.address,
                    name = name,
                    rssi = result.rssi
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            onScanFailed(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun isAvailable(): Boolean {
        return bluetoothAdapter?.bluetoothLeScanner != null
    }

    @SuppressLint("MissingPermission")
    fun start() {
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }
}
