package network.columba.app.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import network.columba.app.viewmodel.InterfaceConfigState

private val MESH_CONNECTION_MODES = listOf("ble" to "Bluetooth", "tcp" to "Wi-Fi", "usb" to "USB")

/**
 * Meshtastic: how to reach the node (Bluetooth / Wi-Fi / USB) and which node.
 * Shown outside "Advanced" since an interface without a node can't work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshtasticConnectionFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        "Runs Reticulum through a stock Meshtastic node (RNS over Meshtastic). " +
            "The node's radio settings are not changed. Disconnect the Meshtastic app from " +
            "the node first; it accepts one client at a time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        MESH_CONNECTION_MODES.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = configState.meshConnectionMode == mode,
                onClick = { onConfigUpdate(configState.copy(meshConnectionMode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(index, MESH_CONNECTION_MODES.size),
            ) { Text(label) }
        }
    }

    when (configState.meshConnectionMode) {
        "tcp" -> {
            OutlinedTextField(
                value = configState.meshTcpHost,
                onValueChange = { onConfigUpdate(configState.copy(meshTcpHost = it.trim())) },
                label = { Text("Node address *") },
                placeholder = { Text("e.g. 192.168.1.50 or meshtastic.local") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.targetHostError != null,
                supportingText = configState.targetHostError?.let { { Text(it) } },
            )
            OutlinedTextField(
                value = configState.meshTcpPort,
                onValueChange = { onConfigUpdate(configState.copy(meshTcpPort = it.trim())) },
                label = { Text("Port") },
                placeholder = { Text("4403") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.targetPortError != null,
                supportingText = configState.targetPortError?.let { { Text(it) } },
            )
        }

        "usb" -> MeshtasticUsbPicker(configState, onConfigUpdate)

        else -> MeshtasticBlePicker(configState, onConfigUpdate)
    }
}

/** Paired Bluetooth devices to pick from, plus a manual address field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshtasticBlePicker(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    val context = LocalContext.current
    val paired = remember { pairedDevices(context) }
    var expanded by remember { mutableStateOf(false) }

    if (paired.isNotEmpty()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = configState.meshDeviceName.ifEmpty { "Choose a paired node" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Paired node") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                paired.forEach { (name, address) ->
                    DropdownMenuItem(
                        text = { Text("$name ($address)") },
                        onClick = {
                            onConfigUpdate(configState.copy(meshDeviceName = name, meshDeviceAddress = address))
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    OutlinedTextField(
        value = configState.meshDeviceAddress,
        onValueChange = { onConfigUpdate(configState.copy(meshDeviceAddress = it.trim(), meshDeviceName = "")) },
        label = { Text("Bluetooth address *") },
        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.meshDeviceError != null,
        supportingText = {
            Text(
                configState.meshDeviceError
                    ?: "Pair the node in Android's Bluetooth settings first (PIN is shown on the node or is 123456).",
            )
        },
    )
}

/** USB devices currently attached; the one plugged in is pre-selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshtasticUsbPicker(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    val context = LocalContext.current
    val devices = remember { attachedUsbDevices(context) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(devices) {
        val only = devices.singleOrNull()
        if (only != null && configState.meshUsbVendorId == null) {
            onConfigUpdate(configState.copy(meshUsbVendorId = only.vendorId, meshUsbProductId = only.productId))
        }
    }
    val selected =
        devices.firstOrNull { it.vendorId == configState.meshUsbVendorId && it.productId == configState.meshUsbProductId }
    val label =
        when {
            selected != null -> selected.label
            configState.meshUsbVendorId != null ->
                "USB %04x:%04x (not attached)".format(configState.meshUsbVendorId, configState.meshUsbProductId ?: 0)
            devices.isEmpty() -> "No USB device attached"
            else -> "Choose the node"
        }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("USB node") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = configState.meshDeviceError != null,
            supportingText = { Text(configState.meshDeviceError ?: "Plug the node into this phone (USB OTG).") },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.label) },
                    onClick = {
                        onConfigUpdate(configState.copy(meshUsbVendorId = device.vendorId, meshUsbProductId = device.productId))
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Advanced: tunnel channel and hop limit. */
@Composable
fun MeshtasticAdvancedFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        "Meshtastic Configuration",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.meshChannel,
        onValueChange = { onConfigUpdate(configState.copy(meshChannel = it.trim())) },
        label = { Text("Channel index") },
        placeholder = { Text("0") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.meshChannelError != null,
        supportingText = {
            Column {
                configState.meshChannelError?.let { Text(it) }
                Text(
                    "0 is the node's primary channel. A secondary channel with its own key keeps " +
                        "Reticulum traffic off the public channel; every node must use the same one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.meshHopLimit,
        onValueChange = { onConfigUpdate(configState.copy(meshHopLimit = it.trim())) },
        label = { Text("Hop limit") },
        placeholder = { Text("3") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.meshHopLimitError != null,
        supportingText = {
            Column {
                configState.meshHopLimitError?.let { Text(it) }
                Text(
                    "How many Meshtastic relays may repeat each packet (0-7). Higher values reach " +
                        "further but every relay on the channel retransmits the traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private class UsbChoice(val vendorId: Int, val productId: Int, val label: String)

private fun attachedUsbDevices(context: Context): List<UsbChoice> {
    val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
    return usb.deviceList.values.map { d ->
        val name = runCatching { d.productName }.getOrNull() ?: d.deviceName
        UsbChoice(d.vendorId, d.productId, "%s (%04x:%04x)".format(name, d.vendorId, d.productId))
    }
}

/** (name, address) of bonded Bluetooth devices; empty without BLUETOOTH_CONNECT. */
@SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Pair<String, String>> =
    runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        adapter.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
    }.getOrDefault(emptyList())
