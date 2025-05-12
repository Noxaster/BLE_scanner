package com.example.ble_scanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

@Composable
fun DeviceDialog(
    client: BLEClient,
    onDismiss: () -> Unit
) {
    val state = client.state.collectAsState().value ?: return
    val readValues = state.readValues
    val service = state.service

    var notifying by remember { mutableStateOf(false) }

    LaunchedEffect(notifying) {
        if (notifying) {
            while (isActive) {
                service.characteristics.forEach { uuid ->
                    client.readCharacteristic(uuid)
                }
                delay(3_000)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            notifying = false
            onDismiss()
        },
        confirmButton = { },
        title = { Text(state.gatt.device.name ?: "Unknown Device") },
        text = {
            Column {
                service.characteristics.forEach { uuid ->
                    Text(
                        "${characteristics[uuid]?.identifier}: ${readValues[uuid] ?: "-"}",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        service.characteristics.forEach { uuid ->
                            client.readCharacteristic(uuid)
                        }
                    }) { Text("Update") }

                    Button(onClick = { notifying = !notifying }) {
                        Text(if (notifying) "Stop" else "Notify")
                    }

                    if (service.identifier == ServiceType.IPVSLight.name) {
                        WriteDialogLauncher(client, CharacteristicType.Intensity.uuid)
                    }
                }
            }
        }
    )
}

@Composable
private fun WriteDialogLauncher(
    client: BLEClient,
    characteristicUUID: UUID
) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        WriteDialog(
            onSubmit = { value ->
                client.writeCharacteristic(characteristicUUID, value)
                show = false
            },
            onDismiss = { show = false }
        )
    }
    OutlinedButton(onClick = { show = true }) { Text("Write") }
}

@Composable
fun WriteDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSubmit(value) }) { Text("Submit") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
