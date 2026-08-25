package com.maxpi.openbikecompanion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.maxpi.openbikecompanion.ui.AppScreen
import com.maxpi.openbikecompanion.ui.theme.OpenBikeCompanionTheme
import com.maxpi.openbikecompanion.model.BleDeviceUi
import com.maxpi.openbikecompanion.model.PageFieldUi
import com.maxpi.openbikecompanion.model.PageUi
import com.maxpi.openbikecompanion.model.C406RiderProfile
import com.maxpi.openbikecompanion.protocol.C406Protocol.ALLOWED_METRICS_BY_FIELD
import com.maxpi.openbikecompanion.protocol.C406Protocol.FIELD_POSITIONS
import com.maxpi.openbikecompanion.protocol.C406Protocol.METRIC_NAMES
import com.maxpi.openbikecompanion.protocol.C406PagesCodec
import com.maxpi.openbikecompanion.protocol.C406ProfileCodec
import com.maxpi.openbikecompanion.ble.BleScanner
import com.maxpi.openbikecompanion.ble.C406GattSession
import com.maxpi.openbikecompanion.ble.C406GattIo.StartResult


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

    private var deviceProfile by mutableStateOf<C406RiderProfile?>(null)
    private var editedProfile by mutableStateOf<C406RiderProfile?>(null)
    private var rawProfileResponse by mutableStateOf("")

    private var pendingProfileApply: C406RiderProfile? = null
    private var profileWriteAwaitingGattResult = false
    private var profileVerifyPending = false

    private var writeInProgress by mutableStateOf(false)


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

    private val bleScanner: BleScanner by lazy {
        BleScanner(
            bluetoothAdapter = bluetoothAdapter,
            onDeviceFound = { item ->
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
            },
            onScanFailed = { errorCode ->
                runOnUiThread {
                    isScanning = false
                    statusText = "BLE scan failed: $errorCode"
                }
            }
        )
    }


    private val c406Session: C406GattSession by lazy {
        C406GattSession(
            context = this,
            bluetoothAdapter = bluetoothAdapter,
            onStatus = { message ->
                runOnUiThread {
                    statusText = message
                }
            },
            onConnected = { address ->
                runOnUiThread {
                    connectedAddress = address
                }
            },
            onDisconnected = {
                runOnUiThread {
                    statusText = "Disconnected"
                    connectedAddress = null
                    writeInProgress = false
                }

                clearPendingOperation()
            },
            onGattError = { status ->
                runOnUiThread {
                    statusText = "GATT error: $status"
                    connectedAddress = null
                    writeInProgress = false
                }

                clearPendingOperation()
            },
            onReady = {
                sendPagesRead(
                    purpose = ReadPurpose.NORMAL
                )
            },
            onWriteResult = { success, status ->
                handleGattWriteResult(
                    success = success,
                    status = status
                )
            },
            onValueReceived = { value ->
                handleCharacteristicValue(value)
            }
        )
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
                    deviceProfile = deviceProfile,
                    editedProfile = editedProfile,
                    rawProfileResponse = rawProfileResponse,
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
                    onApplyToC406 = ::beginSafeApply,
                    onUpdateProfile = ::updateLocalProfile,
                    onResetProfileChanges = ::resetLocalProfileChanges,
                    onApplyProfileToC406 = ::beginProfileApply
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

        if (!bleScanner.isAvailable()) {
            statusText = "BLE scanner unavailable"
            return
        }

        devices.clear()
        statusText = "Scanning..."
        isScanning = true
        bleScanner.start()
    }
    private fun stopScan() {
        bleScanner.stop()

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
        deviceProfile = null
        editedProfile = null
        rawProfileResponse = ""
        statusText = "Connecting..."

        c406Session.connect(address)
    }
    private fun sendPagesRead(
        purpose: ReadPurpose
    ) {
        if (!c406Session.isReady) {
            runOnUiThread {
                statusText = "CC02 unavailable"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        readPurpose = purpose

        when (
            c406Session.write(
                byteArrayOf(0x40, 0x42)
            )
        ) {
            StartResult.STARTED -> Unit

            StartResult.NOT_STARTED -> {
                readPurpose = ReadPurpose.NORMAL

                runOnUiThread {
                    statusText = "Could not send 40 42"
                    writeInProgress = false
                }

                if (purpose != ReadPurpose.NORMAL) {
                    clearPendingOperation()
                }
            }

            StartResult.PERMISSION_DENIED -> {
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
    }
    private fun sendPagesWrite(
        pages: List<PageUi>,
        purpose: WritePurpose
    ) {
        if (!c406Session.isReady) {
            runOnUiThread {
                statusText = "CC02 unavailable"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        val packet = C406PagesCodec.buildPagesWritePacket(pages)

        if (packet == null) {
            runOnUiThread {
                statusText = "Invalid Pages configuration; write cancelled"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        val maxPayload = c406Session.negotiatedMtu - 3

        if (packet.size > maxPayload) {
            runOnUiThread {
                statusText =
                    "MTU ${c406Session.negotiatedMtu} too small for ${packet.size}-byte Pages write"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        writePurpose = purpose

        when (c406Session.write(packet)) {
            StartResult.STARTED -> Unit

            StartResult.NOT_STARTED -> {
                writePurpose = WritePurpose.NONE

                runOnUiThread {
                    statusText = "Could not queue 40 43; checking C406..."
                }

                sendPagesRead(
                    purpose =
                        if (purpose == WritePurpose.APPLY) {
                            ReadPurpose.RECOVER_APPLY_ERROR
                        } else {
                            ReadPurpose.RECOVER_ROLLBACK_ERROR
                        }
                )
            }

            StartResult.PERMISSION_DENIED -> {
                writePurpose = WritePurpose.NONE

                runOnUiThread {
                    statusText = "Bluetooth permission lost"
                    writeInProgress = false
                }

                clearPendingOperation()
            }
        }
    }
    private fun sendProfileRead() {
        when (c406Session.write(C406ProfileCodec.READ_COMMAND)) {
            StartResult.STARTED -> {
                runOnUiThread {
                    statusText = "Reading Rider Profile..."
                }
            }

            StartResult.NOT_STARTED -> {
                runOnUiThread {
                    statusText = "Could not send 40 40"
                    writeInProgress = false
                }
                clearPendingOperation()
            }

            StartResult.PERMISSION_DENIED -> {
                runOnUiThread {
                    statusText = "Bluetooth permission lost"
                    writeInProgress = false
                }
                clearPendingOperation()
            }
        }
    }

    private fun sendProfileWrite(profile: C406RiderProfile) {
        val packet = C406ProfileCodec.buildWritePacket(profile)

        if (packet == null) {
            runOnUiThread {
                statusText = "Invalid Rider Profile; write cancelled"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        pendingProfileApply = profile
        profileWriteAwaitingGattResult = true
        profileVerifyPending = false
        writeInProgress = true

        when (c406Session.write(packet)) {
            StartResult.STARTED -> Unit

            StartResult.NOT_STARTED -> {
                profileWriteAwaitingGattResult = false

                runOnUiThread {
                    statusText = "Could not send 40 41"
                    writeInProgress = false
                }

                clearPendingOperation()
            }

            StartResult.PERMISSION_DENIED -> {
                profileWriteAwaitingGattResult = false

                runOnUiThread {
                    statusText = "Bluetooth permission lost"
                    writeInProgress = false
                }

                clearPendingOperation()
            }
        }
    }
    private fun handleGattWriteResult(
        success: Boolean,
        status: Int
    ) {
        if (profileWriteAwaitingGattResult) {
            profileWriteAwaitingGattResult = false

            if (success) {
                runOnUiThread {
                    statusText = "40 41 sent; waiting for C406 ACK..."
                }
            } else {
                runOnUiThread {
                    statusText = "Profile GATT write failed: $status"
                    writeInProgress = false
                }

                clearPendingOperation()
            }

            return
        }

        val currentWritePurpose = writePurpose

        if (currentWritePurpose == WritePurpose.NONE) {
            return
        }

        if (success) {
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
                purpose =
                    if (currentWritePurpose == WritePurpose.APPLY) {
                        ReadPurpose.RECOVER_APPLY_ERROR
                    } else {
                        ReadPurpose.RECOVER_ROLLBACK_ERROR
                    }
            )
        }
    }
    private fun handleCharacteristicValue(value: ByteArray) {

        val hex = value.toHexString()

        if (
            value.size >= 3 &&
            value[0].toInt() and 0xFF == 0x40 &&
            value[1].toInt() and 0xFF == 0x41
        ) {
            handleProfileWriteAck(
                value = value,
                hex = hex
            )
            return
        }

        if (
            value.size >= 3 &&
            value[0].toInt() and 0xFF == 0x40 &&
            value[1].toInt() and 0xFF == 0x40
        ) {
            val decodedProfile =
                C406ProfileCodec.decodeReadResponse(value)

            if (decodedProfile == null) {
                runOnUiThread {
                    rawProfileResponse = hex
                    statusText = "Invalid Rider Profile response"
                    writeInProgress = false
                }

                clearPendingOperation()
                return
            }

            runOnUiThread {
                rawProfileResponse = hex
            }

            handleDecodedProfile(decodedProfile)
            return
        }

        if (
            value.size >= 3 &&
            value[0].toInt() and 0xFF == 0x40 &&
            value[1].toInt() and 0xFF == 0x43
        ) {
            handlePagesWriteAck(
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
            val decodedPages = C406PagesCodec.decodePagesResponse(value)

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

    private fun handleProfileWriteAck(
        value: ByteArray,
        hex: String
    ) {
        runOnUiThread {
            rawProfileResponse = hex
        }

        val expectedProfile = pendingProfileApply

        if (expectedProfile == null) {
            runOnUiThread {
                statusText = "Unexpected 40 41 response: $hex"
                writeInProgress = false
            }
            clearPendingOperation()
            return
        }

        val success =
            value.size == 3 &&
                    value[2].toInt() and 0xFF == 0x00

        if (success) {
            profileVerifyPending = true

            runOnUiThread {
                statusText = "Profile ACK OK; verifying readback..."
            }

            sendProfileRead()
        } else {
            runOnUiThread {
                statusText = "C406 rejected 40 41: $hex"
                writeInProgress = false
            }

            clearPendingOperation()
        }
    }

    private fun handleDecodedProfile(profile: C406RiderProfile) {
        val verifyPending = profileVerifyPending
        val expectedProfile = pendingProfileApply

        if (verifyPending) {
            profileVerifyPending = false

            runOnUiThread {
                deviceProfile = profile
                editedProfile = profile
                writeInProgress = false

                statusText =
                    if (expectedProfile != null && profile == expectedProfile) {
                        "Profile verified — C406 updated"
                    } else {
                        "Profile verify failed; editor refreshed from C406"
                    }
            }

            clearPendingOperation()
            return
        }

        runOnUiThread {
            deviceProfile = profile
            editedProfile = profile
            statusText = "Rider Profile read"
        }
    }
    private fun handlePagesWriteAck(
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

                sendProfileRead()
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




    private fun beginSafeApply() {
        if (!c406Session.isConnected || connectedAddress == null) {
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

    private fun updateLocalProfile(profile: C406RiderProfile) {
        if (writeInProgress) return
        editedProfile = profile
    }

    private fun resetLocalProfileChanges() {
        if (writeInProgress) return
        editedProfile = deviceProfile
    }

    private fun beginProfileApply() {
        if (!c406Session.isConnected || connectedAddress == null) {
            statusText = "Not connected"
            return
        }

        if (writeInProgress) {
            return
        }

        val current = deviceProfile
        val edited = editedProfile

        if (current == null || edited == null) {
            statusText = "No Rider Profile loaded"
            return
        }

        if (edited == current) {
            statusText = "No Rider Profile changes to apply"
            return
        }

        statusText = "Writing Rider Profile..."
        sendProfileWrite(edited)
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

        pendingProfileApply = null
        profileWriteAwaitingGattResult = false
        profileVerifyPending = false
    }

    private fun disconnectGatt() {
        c406Session.disconnect()
        clearPendingOperation()
        connectedAddress = null
        writeInProgress = false
        statusText = "Disconnected"
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(" ") {
        "%02X".format(it.toInt() and 0xFF)
    }
}
