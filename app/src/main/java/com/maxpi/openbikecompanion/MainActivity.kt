package com.maxpi.openbikecompanion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maxpi.openbikecompanion.ui.theme.OpenBikeCompanionTheme
import com.maxpi.openbikecompanion.model.BleDeviceUi
import com.maxpi.openbikecompanion.model.PageFieldUi
import com.maxpi.openbikecompanion.model.PageUi
import com.maxpi.openbikecompanion.protocol.C406Protocol.ALLOWED_METRICS_BY_FIELD
import com.maxpi.openbikecompanion.protocol.C406Protocol.CC02_UUID
import com.maxpi.openbikecompanion.protocol.C406Protocol.CCCD_UUID
import com.maxpi.openbikecompanion.protocol.C406Protocol.CC_SERVICE_UUID
import com.maxpi.openbikecompanion.protocol.C406Protocol.FIELD_POSITIONS
import com.maxpi.openbikecompanion.protocol.C406Protocol.METRIC_NAMES
import java.util.UUID


private enum class ReadPurpose {
    NORMAL,
    PRE_WRITE,
    VERIFY_WRITE,
    VERIFY_ROLLBACK,
    RECOVER_APPLY_ERROR,
    RECOVER_ROLLBACK_ERROR
}

private enum class WritePurpose {
    NONE,
    APPLY,
    ROLLBACK
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val devices = mutableStateListOf<BleDeviceUi>()

    private var isScanning by mutableStateOf(false)
    private var statusText by mutableStateOf("Ready")
    private var bluetoothEnabled by mutableStateOf(false)

    private var connectedAddress by mutableStateOf<String?>(null)

    // Last configuration positively confirmed by a 40 42 read.
    private var devicePages by mutableStateOf<List<PageUi>>(emptyList())

    // User's local working copy.
    private var editedPages by mutableStateOf<List<PageUi>>(emptyList())

    private var rawPagesResponse by mutableStateOf("")

    private var writeInProgress by mutableStateOf(false)

    private var bluetoothGatt: BluetoothGatt? = null
    private var cc02Characteristic: BluetoothGattCharacteristic? = null

    private var negotiatedMtu = 23

    @Volatile
    private var readPurpose = ReadPurpose.NORMAL

    @Volatile
    private var writePurpose = WritePurpose.NONE

    private var pendingOriginalPages: List<PageUi>? = null
    private var pendingApplyPages: List<PageUi>? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "(unnamed)"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "(unnamed)"
            }

            val item = BleDeviceUi(
                address = device.address,
                name = name,
                rssi = result.rssi
            )

            runOnUiThread {
                val index = devices.indexOfFirst { it.address == item.address }

                if (index >= 0) {
                    devices[index] = item
                } else {
                    devices.add(item)
                }

                val sorted = devices.sortedByDescending { it.rssi }
                devices.clear()
                devices.addAll(sorted)


            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                statusText = "BLE scan failed: $errorCode"
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusText = "GATT error: $status"
                    connectedAddress = null
                    writeInProgress = false
                }
                clearPendingOperation()
                closeGatt(gatt)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    negotiatedMtu = 23

                    runOnUiThread {
                        connectedAddress = gatt.device.address
                        statusText = "Connected; negotiating MTU..."
                    }

                    val mtuRequested = try {
                        gatt.requestMtu(247)
                    } catch (_: SecurityException) {
                        false
                    }

                    if (!mtuRequested) {
                        discoverServicesSafe(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread {
                        statusText = "Disconnected"
                        connectedAddress = null
                        cc02Characteristic = null
                        writeInProgress = false
                    }

                    clearPendingOperation()
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
            }

            runOnUiThread {
                statusText =
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        "MTU $mtu; discovering services..."
                    } else {
                        "MTU request failed; discovering services..."
                    }
            }

            discoverServicesSafe(gatt)
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusText = "Service discovery failed: $status"
                }
                return
            }

            val service = gatt.getService(CC_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CC02_UUID)

            if (service == null || characteristic == null) {
                runOnUiThread {
                    statusText = "C406 command service not found"
                }
                return
            }

            cc02Characteristic = characteristic

            val notifyEnabled = try {
                gatt.setCharacteristicNotification(characteristic, true)
            } catch (_: SecurityException) {
                false
            }

            if (!notifyEnabled) {
                runOnUiThread {
                    statusText = "Could not enable CC02 notifications"
                }
                return
            }

            val cccd = characteristic.getDescriptor(CCCD_UUID)

            if (cccd == null) {
                runOnUiThread {
                    statusText = "CC02 notification descriptor not found"
                }
                return
            }

            try {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = gatt.writeDescriptor(cccd)

                runOnUiThread {
                    statusText =
                        if (started) {
                            "Enabling notifications..."
                        } else {
                            "Could not write notification descriptor"
                        }
                }
            } catch (_: SecurityException) {
                runOnUiThread {
                    statusText = "Bluetooth permission lost"
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusText = "Notification setup failed: $status"
                }
                return
            }

            runOnUiThread {
                statusText = "Connected; reading Pages..."
            }

            sendPagesRead(
                gatt = gatt,
                purpose = ReadPurpose.NORMAL
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CC02_UUID) return

            val currentWritePurpose = writePurpose

            if (currentWritePurpose == WritePurpose.NONE) {
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusText =
                        when (currentWritePurpose) {
                            WritePurpose.APPLY ->
                                "40 43 sent; waiting for C406 ACK..."
                            WritePurpose.ROLLBACK ->
                                "Rollback sent; waiting for C406 ACK..."
                            WritePurpose.NONE ->
                                statusText
                        }
                }
            } else {
                writePurpose = WritePurpose.NONE

                runOnUiThread {
                    statusText =
                        "GATT write failed: $status; checking C406..."
                }

                sendPagesRead(
                    gatt = gatt,
                    purpose =
                        if (currentWritePurpose == WritePurpose.APPLY) {
                            ReadPurpose.RECOVER_APPLY_ERROR
                        } else {
                            ReadPurpose.RECOVER_ROLLBACK_ERROR
                        }
                )
            }
        }

        @Deprecated(
            "Deprecated in newer Android APIs; required for API 24 compatibility"
        )
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(
                gatt = gatt,
                uuid = characteristic.uuid,
                value = characteristic.value
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothEnabled = isBluetoothEnabledSafe()

        setContent {
            OpenBikeCompanionTheme {
                AppScreen(
                    devices = devices,
                    devicePages = devicePages,
                    editedPages = editedPages,
                    rawPagesResponse = rawPagesResponse,
                    isScanning = isScanning,
                    statusText = statusText,
                    bluetoothEnabled = bluetoothEnabled,
                    connectedAddress = connectedAddress,
                    writeInProgress = writeInProgress,
                    hasPermissions = ::hasRequiredPermissions,
                    onPermissionsChanged = {
                        bluetoothEnabled = isBluetoothEnabledSafe()
                    },
                    onRequestBluetoothEnable = ::requestBluetoothEnable,
                    onStartScan = ::startScan,
                    onStopScan = ::stopScan,
                    onConnect = ::connectToDevice,
                    onDisconnect = ::disconnectGatt,
                    onUpdateField = ::updateLocalField,
                    onResetLocalChanges = ::resetLocalChanges,
                    onApplyToC406 = ::beginSafeApply
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bluetoothEnabled = isBluetoothEnabledSafe()
    }

    override fun onStop() {
        super.onStop()

        if (isScanning) {
            stopScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectGatt()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBluetoothEnabledSafe(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return bluetoothAdapter?.isEnabled == true
    }

    private fun requestBluetoothEnable() {
        if (!hasRequiredPermissions()) {
            statusText = "Bluetooth permission required"
            return
        }

        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothEnabled = true
            return
        }

        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun startScan() {
        if (!hasRequiredPermissions()) {
            statusText = "Bluetooth permission required"
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            statusText = "Turn Bluetooth on"
            bluetoothEnabled = false
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (scanner == null) {
            statusText = "BLE scanner unavailable"
            return
        }

        devices.clear()
        statusText = "Scanning..."
        isScanning = true
        scanner.startScan(scanCallback)
    }

    private fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }

        isScanning = false

        if (statusText == "Scanning...") {
            statusText = "Scan stopped"
        }
    }

    private fun connectToDevice(address: String) {
        if (!hasRequiredPermissions()) {
            statusText = "Bluetooth permission required"
            return
        }

        stopScan()
        disconnectGatt()

        devicePages = emptyList()
        editedPages = emptyList()
        rawPagesResponse = ""
        statusText = "Connecting..."

        val device = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }

        if (device == null) {
            statusText = "Could not get Bluetooth device"
            return
        }

        bluetoothGatt = try {
            device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (_: SecurityException) {
            null
        }

        if (bluetoothGatt == null) {
            statusText = "Could not start GATT connection"
        }
    }

    private fun discoverServicesSafe(gatt: BluetoothGatt) {
        try {
            val started = gatt.discoverServices()

            runOnUiThread {
                statusText =
                    if (started) {
                        "Discovering services..."
                    } else {
                        "Could not start service discovery"
                    }
            }
        } catch (_: SecurityException) {
            runOnUiThread {
                statusText = "Bluetooth permission lost"
            }
        }
    }

    private fun sendPagesRead(
        gatt: BluetoothGatt,
        purpose: ReadPurpose
    ) {
        val characteristic = cc02Characteristic

        if (characteristic == null) {
            runOnUiThread {
                statusText = "CC02 unavailable"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        readPurpose = purpose

        try {
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = byteArrayOf(0x40, 0x42)

            val started = gatt.writeCharacteristic(characteristic)

            if (!started) {
                readPurpose = ReadPurpose.NORMAL

                runOnUiThread {
                    statusText = "Could not send 40 42"
                    writeInProgress = false
                }

                if (purpose != ReadPurpose.NORMAL) {
                    clearPendingOperation()
                }
            }
        } catch (_: SecurityException) {
            readPurpose = ReadPurpose.NORMAL

            runOnUiThread {
                statusText = "Bluetooth permission lost"
                writeInProgress = false
            }

            if (purpose != ReadPurpose.NORMAL) {
                clearPendingOperation()
            }
        }
    }

    private fun sendPagesWrite(
        gatt: BluetoothGatt,
        pages: List<PageUi>,
        purpose: WritePurpose
    ) {
        val characteristic = cc02Characteristic

        if (characteristic == null) {
            runOnUiThread {
                statusText = "CC02 unavailable"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        val packet = buildPagesWritePacket(pages)

        if (packet == null) {
            runOnUiThread {
                statusText = "Invalid Pages configuration; write cancelled"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        val maxPayload = negotiatedMtu - 3

        if (packet.size > maxPayload) {
            runOnUiThread {
                statusText =
                    "MTU $negotiatedMtu too small for ${packet.size}-byte Pages write"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        writePurpose = purpose

        try {
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = packet

            val started = gatt.writeCharacteristic(characteristic)

            if (!started) {
                writePurpose = WritePurpose.NONE

                runOnUiThread {
                    statusText = "Could not queue 40 43; checking C406..."
                }

                sendPagesRead(
                    gatt = gatt,
                    purpose =
                        if (purpose == WritePurpose.APPLY) {
                            ReadPurpose.RECOVER_APPLY_ERROR
                        } else {
                            ReadPurpose.RECOVER_ROLLBACK_ERROR
                        }
                )
            }
        } catch (_: SecurityException) {
            writePurpose = WritePurpose.NONE

            runOnUiThread {
                statusText = "Bluetooth permission lost"
                writeInProgress = false
            }

            clearPendingOperation()
        }
    }

    private fun handleCharacteristicValue(
        gatt: BluetoothGatt,
        uuid: UUID,
        value: ByteArray?
    ) {
        if (uuid != CC02_UUID || value == null) return

        val hex = value.toHexString()

        if (
            value.size >= 3 &&
            value[0].toInt() and 0xFF == 0x40 &&
            value[1].toInt() and 0xFF == 0x43
        ) {
            handlePagesWriteAck(
                gatt = gatt,
                value = value,
                hex = hex
            )
            return
        }

        if (
            value.size >= 4 &&
            value[0].toInt() and 0xFF == 0x40 &&
            value[1].toInt() and 0xFF == 0x42 &&
            value[2].toInt() and 0xFF == 0x00
        ) {
            val decodedPages = decodePagesResponse(value)

            if (decodedPages == null) {
                runOnUiThread {
                    rawPagesResponse = hex
                    statusText = "Invalid Pages response length"
                    writeInProgress = false
                }

                clearPendingOperation()
                return
            }

            val purpose = readPurpose
            readPurpose = ReadPurpose.NORMAL

            runOnUiThread {
                rawPagesResponse = hex
            }

            handleDecodedPages(
                gatt = gatt,
                pages = decodedPages,
                purpose = purpose
            )

            return
        }

        runOnUiThread {
            rawPagesResponse = hex
            statusText = "RX: $hex"
        }
    }

    private fun handlePagesWriteAck(
        gatt: BluetoothGatt,
        value: ByteArray,
        hex: String
    ) {
        val purpose = writePurpose
        writePurpose = WritePurpose.NONE

        runOnUiThread {
            rawPagesResponse = hex
        }

        if (purpose == WritePurpose.NONE) {
            runOnUiThread {
                statusText = "Unexpected 40 43 response: $hex"
            }
            return
        }

        val success =
            value.size == 3 &&
                    value[2].toInt() and 0xFF == 0x00

        if (success) {
            runOnUiThread {
                statusText =
                    if (purpose == WritePurpose.APPLY) {
                        "C406 ACK OK; verifying readback..."
                    } else {
                        "Rollback ACK OK; verifying restore..."
                    }
            }

            sendPagesRead(
                gatt = gatt,
                purpose =
                    if (purpose == WritePurpose.APPLY) {
                        ReadPurpose.VERIFY_WRITE
                    } else {
                        ReadPurpose.VERIFY_ROLLBACK
                    }
            )
        } else {
            runOnUiThread {
                statusText = "C406 rejected 40 43; checking actual Pages..."
            }

            sendPagesRead(
                gatt = gatt,
                purpose =
                    if (purpose == WritePurpose.APPLY) {
                        ReadPurpose.RECOVER_APPLY_ERROR
                    } else {
                        ReadPurpose.RECOVER_ROLLBACK_ERROR
                    }
            )
        }
    }

    private fun handleDecodedPages(
        gatt: BluetoothGatt,
        pages: List<PageUi>,
        purpose: ReadPurpose
    ) {
        when (purpose) {
            ReadPurpose.NORMAL -> {
                runOnUiThread {
                    devicePages = pages
                    editedPages = pages
                    statusText = "Pages read: ${pages.size}"
                }
            }

            ReadPurpose.PRE_WRITE -> {
                val original = pendingOriginalPages
                val proposed = pendingApplyPages

                if (original == null || proposed == null) {
                    runOnUiThread {
                        statusText = "Internal apply state lost; cancelled"
                        writeInProgress = false
                    }
                    clearPendingOperation()
                    return
                }

                if (pages != original) {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText =
                            "C406 Pages changed since edit; apply cancelled and editor refreshed"
                        writeInProgress = false
                    }

                    clearPendingOperation()
                    return
                }

                runOnUiThread {
                    statusText = "Safety read OK; writing Pages..."
                }

                sendPagesWrite(
                    gatt = gatt,
                    pages = proposed,
                    purpose = WritePurpose.APPLY
                )
            }

            ReadPurpose.VERIFY_WRITE -> {
                val proposed = pendingApplyPages
                val original = pendingOriginalPages

                if (proposed != null && pages == proposed) {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText = "Apply verified — C406 Pages updated"
                        writeInProgress = false
                    }

                    clearPendingOperation()
                } else if (original != null) {
                    runOnUiThread {
                        statusText =
                            "VERIFY FAILED; restoring original Pages..."
                    }

                    sendPagesWrite(
                        gatt = gatt,
                        pages = original,
                        purpose = WritePurpose.ROLLBACK
                    )
                } else {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText =
                            "VERIFY FAILED and original snapshot is unavailable"
                        writeInProgress = false
                    }

                    clearPendingOperation()
                }
            }

            ReadPurpose.VERIFY_ROLLBACK -> {
                val original = pendingOriginalPages
                val proposed = pendingApplyPages

                if (original != null && pages == original) {
                    runOnUiThread {
                        devicePages = pages

                        if (proposed != null) {
                            editedPages = proposed
                        }

                        statusText =
                            "Apply verification failed; original Pages restored. Local edits kept."
                        writeInProgress = false
                    }
                } else {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText =
                            "CRITICAL: rollback verification failed; editor refreshed from actual C406"
                        writeInProgress = false
                    }
                }

                clearPendingOperation()
            }

            ReadPurpose.RECOVER_APPLY_ERROR -> {
                val original = pendingOriginalPages

                if (original != null && pages == original) {
                    runOnUiThread {
                        devicePages = pages
                        statusText =
                            "Write failed/rejected; C406 is unchanged. Local edits kept."
                        writeInProgress = false
                    }

                    clearPendingOperation()
                } else if (original != null) {
                    runOnUiThread {
                        statusText =
                            "Unexpected C406 change after write error; restoring original..."
                    }

                    sendPagesWrite(
                        gatt = gatt,
                        pages = original,
                        purpose = WritePurpose.ROLLBACK
                    )
                } else {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText =
                            "Write error; actual C406 Pages re-read"
                        writeInProgress = false
                    }

                    clearPendingOperation()
                }
            }

            ReadPurpose.RECOVER_ROLLBACK_ERROR -> {
                val original = pendingOriginalPages
                val proposed = pendingApplyPages

                if (original != null && pages == original) {
                    runOnUiThread {
                        devicePages = pages

                        if (proposed != null) {
                            editedPages = proposed
                        }

                        statusText =
                            "Rollback transport error, but original Pages are intact. Local edits kept."
                        writeInProgress = false
                    }
                } else {
                    runOnUiThread {
                        devicePages = pages
                        editedPages = pages
                        statusText =
                            "CRITICAL: rollback failed; editor refreshed from actual C406"
                        writeInProgress = false
                    }
                }

                clearPendingOperation()
            }
        }
    }

    private fun decodePagesResponse(
        value: ByteArray
    ): List<PageUi>? {
        if (value.size < 4) return null

        val pageCount = value[3].toInt() and 0xFF
        val expectedLength = 4 + pageCount * 7

        if (value.size != expectedLength) {
            return null
        }

        return buildList {
            for (pageIndex in 0 until pageCount) {
                val fields = buildList {
                    for (fieldIndex in 0 until 7) {
                        val offset = 4 + pageIndex * 7 + fieldIndex
                        val code = value[offset].toInt() and 0xFF

                        add(
                            PageFieldUi(
                                position = FIELD_POSITIONS[fieldIndex],
                                code = code,
                                name =
                                    METRIC_NAMES[code]
                                        ?: "Unknown 0x%02X".format(code)
                            )
                        )
                    }
                }

                add(
                    PageUi(
                        number = pageIndex + 1,
                        fields = fields
                    )
                )
            }
        }
    }

    private fun buildPagesWritePacket(
        pages: List<PageUi>
    ): ByteArray? {
        if (pages.isEmpty() || pages.size > 255) {
            return null
        }

        val sortedPages = pages.sortedBy { it.number }

        if (sortedPages.any { it.fields.size != 7 }) {
            return null
        }

        val packet = ByteArray(3 + sortedPages.size * 7)

        packet[0] = 0x40
        packet[1] = 0x43
        packet[2] = sortedPages.size.toByte()

        var offset = 3

        for (page in sortedPages) {
            for (field in page.fields) {
                packet[offset++] = field.code.toByte()
            }
        }

        return packet
    }

    private fun beginSafeApply() {
        val gatt = bluetoothGatt

        if (gatt == null || connectedAddress == null) {
            statusText = "Not connected"
            return
        }

        if (writeInProgress) {
            return
        }

        if (devicePages.isEmpty() || editedPages.isEmpty()) {
            statusText = "No Pages loaded"
            return
        }

        if (editedPages == devicePages) {
            statusText = "No local changes to apply"
            return
        }

        pendingOriginalPages = devicePages
        pendingApplyPages = editedPages
        writeInProgress = true

        statusText = "Safety read before write..."

        sendPagesRead(
            gatt = gatt,
            purpose = ReadPurpose.PRE_WRITE
        )
    }

    private fun updateLocalField(
        pageNumber: Int,
        fieldIndex: Int,
        newCode: Int
    ) {
        if (writeInProgress) return

        val pageIndex = editedPages.indexOfFirst {
            it.number == pageNumber
        }

        if (pageIndex < 0) return

        val page = editedPages[pageIndex]

        if (fieldIndex !in page.fields.indices) return

        val allowed = ALLOWED_METRICS_BY_FIELD[fieldIndex].orEmpty()

        if (newCode !in allowed) return

        val newFields = page.fields.toMutableList()

        newFields[fieldIndex] = PageFieldUi(
            position = FIELD_POSITIONS[fieldIndex],
            code = newCode,
            name = METRIC_NAMES[newCode] ?: "Unknown 0x%02X".format(newCode)
        )

        val newPages = editedPages.toMutableList()

        newPages[pageIndex] = page.copy(
            fields = newFields
        )

        editedPages = newPages
    }

    private fun resetLocalChanges() {
        if (writeInProgress) return
        editedPages = devicePages
    }

    private fun clearPendingOperation() {
        readPurpose = ReadPurpose.NORMAL
        writePurpose = WritePurpose.NONE
        pendingOriginalPages = null
        pendingApplyPages = null
    }

    private fun disconnectGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        cc02Characteristic = null
        negotiatedMtu = 23

        clearPendingOperation()

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

        connectedAddress = null
        writeInProgress = false
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

private fun ByteArray.toHexString(): String {
    return joinToString(" ") {
        "%02X".format(it.toInt() and 0xFF)
    }
}

@Composable
private fun AppScreen(
    devices: List<BleDeviceUi>,
    devicePages: List<PageUi>,
    editedPages: List<PageUi>,
    rawPagesResponse: String,
    isScanning: Boolean,
    statusText: String,
    bluetoothEnabled: Boolean,
    connectedAddress: String?,
    writeInProgress: Boolean,
    hasPermissions: () -> Boolean,
    onPermissionsChanged: () -> Unit,
    onRequestBluetoothEnable: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onUpdateField: (Int, Int, Int) -> Unit,
    onResetLocalChanges: () -> Unit,
    onApplyToC406: () -> Unit
) {
    var permissionsGranted by remember {
        mutableStateOf(hasPermissions())
    }

    var selectedPageNumber by remember {
        mutableStateOf(1)
    }

    var editingFieldIndex by remember {
        mutableStateOf<Int?>(null)
    }

    var showApplyConfirmation by remember {
        mutableStateOf(false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsGranted = grants.values.all { it }
        onPermissionsChanged()
    }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val hasLocalChanges =
        devicePages.isNotEmpty() &&
                editedPages != devicePages

    val selectedPage =
        editedPages.firstOrNull {
            it.number == selectedPageNumber
        } ?: editedPages.firstOrNull()

    val selectedPageActualNumber =
        selectedPage?.number ?: selectedPageNumber

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "OpenBike Companion",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Status: $statusText")
            }

            if (writeInProgress) {
                item {
                    Text(
                        text = "Write/verify in progress — do not disconnect",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!permissionsGranted) {
                item {
                    Button(
                        onClick = {
                            permissionLauncher.launch(permissions)
                        }
                    ) {
                        Text("Grant Bluetooth permission")
                    }
                }
            }

            if (permissionsGranted && !bluetoothEnabled) {
                item {
                    Button(onClick = onRequestBluetoothEnable) {
                        Text("Turn Bluetooth on")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled =
                            permissionsGranted &&
                                    bluetoothEnabled &&
                                    !isScanning &&
                                    connectedAddress == null &&
                                    !writeInProgress,
                        onClick = onStartScan
                    ) {
                        Text("Scan")
                    }

                    Button(
                        enabled = isScanning,
                        onClick = onStopScan
                    ) {
                        Text("Stop")
                    }

                    Button(
                        enabled =
                            connectedAddress != null &&
                                    !writeInProgress,
                        onClick = onDisconnect
                    ) {
                        Text("Disconnect")
                    }
                }
            }

            if (connectedAddress == null) {
                if (devices.isEmpty()) {
                    item {
                        Text("No BLE devices found yet.")
                    }
                } else {
                    items(
                        items = devices,
                        key = { it.address }
                    ) { device ->
                        DeviceCard(
                            device = device,
                            onConnect = onConnect
                        )
                    }
                }
            }

            if (editedPages.isNotEmpty()) {
                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        text = "Pages editor",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text =
                            if (hasLocalChanges) {
                                "Local changes only — NOT sent to C406 yet"
                            } else {
                                "Showing configuration confirmed on C406"
                            },
                        fontWeight =
                            if (hasLocalChanges) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            }
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        editedPages.forEach { page ->
                            if (page.number == selectedPageActualNumber) {
                                Button(
                                    enabled = !writeInProgress,
                                    onClick = {
                                        selectedPageNumber = page.number
                                    }
                                ) {
                                    Text("Page ${page.number}")
                                }
                            } else {
                                OutlinedButton(
                                    enabled = !writeInProgress,
                                    onClick = {
                                        selectedPageNumber = page.number
                                    }
                                ) {
                                    Text("Page ${page.number}")
                                }
                            }
                        }
                    }
                }

                if (selectedPage != null) {
                    item {
                        C406PageEditor(
                            page = selectedPage,
                            enabled = !writeInProgress,
                            onFieldClick = { fieldIndex ->
                                editingFieldIndex = fieldIndex
                            }
                        )
                    }
                }

                if (hasLocalChanges) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled =
                                    connectedAddress != null &&
                                            !writeInProgress,
                                onClick = {
                                    showApplyConfirmation = true
                                }
                            ) {
                                Text("Apply to C406")
                            }

                            OutlinedButton(
                                enabled = !writeInProgress,
                                onClick = onResetLocalChanges
                            ) {
                                Text("Discard local changes")
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Raw: $rawPagesResponse",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    val fieldIndex = editingFieldIndex
    val dialogPage = selectedPage

    if (
        fieldIndex != null &&
        dialogPage != null &&
        fieldIndex in dialogPage.fields.indices
    ) {
        val currentField = dialogPage.fields[fieldIndex]
        val allowedCodes =
            ALLOWED_METRICS_BY_FIELD[fieldIndex].orEmpty()

        MetricPickerDialog(
            title = currentField.position,
            currentCode = currentField.code,
            allowedCodes = allowedCodes,
            onSelect = { newCode ->
                onUpdateField(
                    dialogPage.number,
                    fieldIndex,
                    newCode
                )
                editingFieldIndex = null
            },
            onDismiss = {
                editingFieldIndex = null
            }
        )
    }

    if (showApplyConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showApplyConfirmation = false
            },
            title = {
                Text("Apply Pages to C406?")
            },
            text = {
                Text(
                    "The app will first re-read the current Pages, " +
                            "then write 40 43, then read back and verify. " +
                            "If verification fails, it will attempt to restore " +
                            "the original Pages automatically."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApplyConfirmation = false
                        onApplyToC406()
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showApplyConfirmation = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DeviceCard(
    device: BleDeviceUi,
    onConnect: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium
            )

            Text(device.address)
            Text("RSSI: ${device.rssi} dBm")

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onConnect(device.address)
                }
            ) {
                Text("Connect & Identify")
            }
        }
    }
}

@Composable
private fun C406PageEditor(
    page: PageUi,
    enabled: Boolean,
    onFieldClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Page ${page.number}",
                style = MaterialTheme.typography.titleMedium
            )

            FieldCell(
                field = page.fields[0],
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = {
                    onFieldClick(0)
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FieldCell(
                    field = page.fields[1],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(1)
                    }
                )

                FieldCell(
                    field = page.fields[2],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(2)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FieldCell(
                    field = page.fields[3],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(3)
                    }
                )

                FieldCell(
                    field = page.fields[4],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(4)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FieldCell(
                    field = page.fields[5],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(5)
                    }
                )

                FieldCell(
                    field = page.fields[6],
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = {
                        onFieldClick(6)
                    }
                )
            }
        }
    }
}

@Composable
private fun FieldCell(
    field: PageFieldUi,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(78.dp)
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = field.position,
                style = MaterialTheme.typography.labelMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = field.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "0x%02X".format(field.code),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MetricPickerDialog(
    title: String,
    currentCode: Int,
    allowedCodes: List<Int>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Column {
                Text(
                    text = "Swipe up/down to see all available metrics",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.height(420.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        allowedCodes.forEach { code ->
                            val name =
                                METRIC_NAMES[code]
                                    ?: "Unknown 0x%02X".format(code)

                            val selected = code == currentCode

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(code)
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Text(
                                        text =
                                            if (selected) {
                                                "✓ $name"
                                            } else {
                                                name
                                            },
                                        fontWeight =
                                            if (selected) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                    )

                                    Text(
                                        text = "0x%02X".format(code),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    ScrollIndicator(
                        scrollState = scrollState,
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ScrollIndicator(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val thumbColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val radius = 3.dp.toPx()

        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius)
        )

        if (scrollState.maxValue <= 0) {
            return@Canvas
        }

        val contentHeight =
            size.height + scrollState.maxValue.toFloat()

        val visibleFraction =
            if (contentHeight > 0f) {
                size.height / contentHeight
            } else {
                1f
            }

        val minimumThumb = 28.dp.toPx()

        val thumbHeight =
            (size.height * visibleFraction)
                .coerceAtLeast(minimumThumb)
                .coerceAtMost(size.height)

        val travel = size.height - thumbHeight

        val progress =
            scrollState.value.toFloat() /
                    scrollState.maxValue.toFloat()

        val top = travel * progress

        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

