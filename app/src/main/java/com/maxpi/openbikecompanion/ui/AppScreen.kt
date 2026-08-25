package com.maxpi.openbikecompanion.ui

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

private enum class RiderProfileField(
    val label: String,
    val unit: String
) {
    AGE("Age", "years"),
    HEIGHT("Height", "cm"),
    RIDER_WEIGHT("Rider Weight", "kg"),
    FTP("FTP", "W"),
    MAX_HEART_RATE("Max Heart Rate", "bpm"),
    LTHR("LTHR", "bpm"),
    VEHICLE_WEIGHT("Vehicle Weight", "kg")
}

private enum class DeviceScreen {
    DEVICE,
    PAGES,
    RIDER_PROFILE
}

@Composable
internal fun AppScreen(
    devices: List<BleDeviceUi>,
    devicePages: List<PageUi>,
    editedPages: List<PageUi>,
    rawPagesResponse: String,
    deviceProfile: C406RiderProfile?,
    editedProfile: C406RiderProfile?,
    rawProfileResponse: String,
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
    onApplyToC406: () -> Unit,
    onUpdateProfile: (C406RiderProfile) -> Unit,
    onResetProfileChanges: () -> Unit,
    onApplyProfileToC406: () -> Unit
) {
    var permissionsGranted by remember {
        mutableStateOf(hasPermissions())
    }

    var currentScreen by remember {
        mutableStateOf(DeviceScreen.DEVICE)
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

    var editingProfileField by remember {
        mutableStateOf<RiderProfileField?>(null)
    }

    var showProfileApplyConfirmation by remember {
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

    val hasProfileLocalChanges =
        deviceProfile != null &&
                editedProfile != null &&
                editedProfile != deviceProfile

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

            if (currentScreen != DeviceScreen.DEVICE) {
                item {
                    OutlinedButton(
                        enabled = !writeInProgress,
                        onClick = {
                            currentScreen = DeviceScreen.DEVICE
                            editingFieldIndex = null
                            editingProfileField = null
                        }
                    ) {
                        Text("Back to Device")
                    }
                }
            }

            if (currentScreen == DeviceScreen.DEVICE) {
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

                if (connectedAddress != null) {
                    item {
                        HorizontalDivider()
                    }

                    item {
                        Text(
                            text = "Device settings",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    item {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled =
                                editedPages.isNotEmpty() &&
                                        !writeInProgress,
                            onClick = {
                                currentScreen = DeviceScreen.PAGES
                            }
                        ) {
                            Text("Pages")
                        }
                    }

                    item {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled =
                                editedProfile != null &&
                                        !writeInProgress,
                            onClick = {
                                currentScreen = DeviceScreen.RIDER_PROFILE
                            }
                        ) {
                            Text("Rider Profile")
                        }
                    }
                }
            }

            if (
                currentScreen == DeviceScreen.PAGES &&
                editedPages.isNotEmpty()
            ) {
                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        text = "Pages",
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

            if (
                currentScreen == DeviceScreen.RIDER_PROFILE &&
                editedProfile != null
            ) {
                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        text = "Rider Profile",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text =
                            if (hasProfileLocalChanges) {
                                "Local changes only — NOT sent to C406 yet"
                            } else {
                                "Showing profile confirmed on C406"
                            },
                        fontWeight =
                            if (hasProfileLocalChanges) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            }
                    )
                }

                item {
                    RiderProfileEditor(
                        profile = editedProfile,
                        enabled = !writeInProgress,
                        onFieldClick = { field ->
                            editingProfileField = field
                        }
                    )
                }

                if (hasProfileLocalChanges) {
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
                                    showProfileApplyConfirmation = true
                                }
                            ) {
                                Text("Apply Profile")
                            }

                            OutlinedButton(
                                enabled = !writeInProgress,
                                onClick = onResetProfileChanges
                            ) {
                                Text("Discard changes")
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Profile raw: $rawProfileResponse",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    val profileField = editingProfileField
    val profileForDialog = editedProfile

    if (
        profileField != null &&
        profileForDialog != null
    ) {
        RiderProfileValueDialog(
            field = profileField,
            profile = profileForDialog,
            onSave = { updatedProfile ->
                onUpdateProfile(updatedProfile)
                editingProfileField = null
            },
            onDismiss = {
                editingProfileField = null
            }
        )
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

    if (showProfileApplyConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showProfileApplyConfirmation = false
            },
            title = {
                Text("Apply Rider Profile to C406?")
            },
            text = {
                Text(
                    "The app will write the Rider Profile with 40 41, " +
                            "wait for the C406 acknowledgement, then read 40 40 " +
                            "back and verify the stored values."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showProfileApplyConfirmation = false
                        onApplyProfileToC406()
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showProfileApplyConfirmation = false
                    }
                ) {
                    Text("Cancel")
                }
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
private fun RiderProfileEditor(
    profile: C406RiderProfile,
    enabled: Boolean,
    onFieldClick: (RiderProfileField) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            RiderProfileField.entries.forEachIndexed { index, field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) {
                            onFieldClick(field)
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = field.label,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = riderProfileFieldDisplayValue(
                            profile = profile,
                            field = field
                        )
                    )
                }

                if (index != RiderProfileField.entries.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RiderProfileValueDialog(
    field: RiderProfileField,
    profile: C406RiderProfile,
    onSave: (C406RiderProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by remember(field, profile) {
        mutableStateOf(
            riderProfileFieldEditValue(
                profile = profile,
                field = field
            )
        )
    }

    val updatedProfile =
        updateRiderProfileFromText(
            profile = profile,
            field = field,
            text = textValue
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(field.label)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                    },
                    singleLine = true,
                    label = {
                        Text(field.unit)
                    }
                )

                if (updatedProfile == null) {
                    Text(
                        text = "Enter a valid value",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = updatedProfile != null,
                onClick = {
                    updatedProfile?.let(onSave)
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun riderProfileFieldDisplayValue(
    profile: C406RiderProfile,
    field: RiderProfileField
): String {
    return when (field) {
        RiderProfileField.AGE ->
            "${profile.age} years"

        RiderProfileField.HEIGHT ->
            "${profile.heightCm} cm"

        RiderProfileField.RIDER_WEIGHT ->
            "%.2f kg".format(profile.riderWeightKg)

        RiderProfileField.FTP ->
            "${profile.ftpWatts} W"

        RiderProfileField.MAX_HEART_RATE ->
            "${profile.maxHeartRateBpm} bpm"

        RiderProfileField.LTHR ->
            "${profile.lthrBpm} bpm"

        RiderProfileField.VEHICLE_WEIGHT ->
            "%.2f kg".format(profile.vehicleWeightKg)
    }
}

private fun riderProfileFieldEditValue(
    profile: C406RiderProfile,
    field: RiderProfileField
): String {
    return when (field) {
        RiderProfileField.AGE ->
            profile.age.toString()

        RiderProfileField.HEIGHT ->
            profile.heightCm.toString()

        RiderProfileField.RIDER_WEIGHT ->
            "%.2f".format(profile.riderWeightKg)

        RiderProfileField.FTP ->
            profile.ftpWatts.toString()

        RiderProfileField.MAX_HEART_RATE ->
            profile.maxHeartRateBpm.toString()

        RiderProfileField.LTHR ->
            profile.lthrBpm.toString()

        RiderProfileField.VEHICLE_WEIGHT ->
            "%.2f".format(profile.vehicleWeightKg)
    }
}

private fun updateRiderProfileFromText(
    profile: C406RiderProfile,
    field: RiderProfileField,
    text: String
): C406RiderProfile? {
    return when (field) {
        RiderProfileField.AGE -> {
            val value = text.trim().toIntOrNull()
                ?: return null

            if (value !in 0..255) return null
            profile.copy(age = value)
        }

        RiderProfileField.HEIGHT -> {
            val value = text.trim().toIntOrNull()
                ?: return null

            if (value !in 0..255) return null
            profile.copy(heightCm = value)
        }

        RiderProfileField.FTP -> {
            val value = text.trim().toIntOrNull()
                ?: return null

            if (value !in 0..65535) return null
            profile.copy(ftpWatts = value)
        }

        RiderProfileField.MAX_HEART_RATE -> {
            val value = text.trim().toIntOrNull()
                ?: return null

            if (value !in 0..255) return null
            profile.copy(maxHeartRateBpm = value)
        }

        RiderProfileField.LTHR -> {
            val value = text.trim().toIntOrNull()
                ?: return null

            if (value !in 0..255) return null
            profile.copy(lthrBpm = value)
        }

        RiderProfileField.RIDER_WEIGHT -> {
            val value =
                text.trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?: return null

            val hundredths =
                kotlin.math.round(value * 100.0).toInt()

            if (hundredths !in 0..65535) return null

            profile.copy(
                riderWeightHundredthsKg = hundredths
            )
        }

        RiderProfileField.VEHICLE_WEIGHT -> {
            val value =
                text.trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?: return null

            val hundredths =
                kotlin.math.round(value * 100.0).toInt()

            if (hundredths !in 0..65535) return null

            profile.copy(
                vehicleWeightHundredthsKg = hundredths
            )
        }
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
