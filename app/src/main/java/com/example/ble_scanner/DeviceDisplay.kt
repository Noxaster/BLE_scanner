package com.example.ble_scanner

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import java.util.UUID

@Composable
fun DeviceDisplay(
    activity: ComponentActivity,
    client: BLEClient,
    connectingTo: MutableState<Device?>
) {
    val state = client.state.collectAsState().value

    if (state == null) {
        Text("No connection :(")
        return
    }

    val readValues = state.readValues
    val service = state.service

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = state.service.identifier,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(service.characteristics.toList()) { uuid ->
                val characteristic = characteristics[uuid]!!
                var notifying by remember { mutableStateOf(false) }

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Value",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                            Text(
                                text = readValues[uuid]!!,
                                fontSize = 24.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Row(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            if (characteristic.read != null) {
                                OutlinedButton(onClick = {
                                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        client.readCharacteristic(uuid)
                                    }
                                }) { Text("Update") }
                            }

                            if (characteristic.doesNotification) {
                                OutlinedButton(onClick = {
                                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifying = !notifying
                                        client.enableNotifications(notifying, uuid)
                                    }
                                }) {
                                    Text(if (notifying) "Stop" else "Notify")
                                }
                            }

                            if (characteristic.write != null) {
                                WriteDialogLauncher(
                                    activity,
                                    client,
                                    CharacteristicType.Intensity.uuid
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WriteDialogLauncher(
    activity: ComponentActivity,
    client: BLEClient,
    characteristicUUID: UUID
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    if (show) {
        WriteDialog(
            onSubmit = { value ->
                if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    client.writeCharacteristic(context, characteristicUUID, value)
                    show = false
                }
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
        confirmButton = { Button(onClick = { onSubmit(value) }) { Text("Submit") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
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
