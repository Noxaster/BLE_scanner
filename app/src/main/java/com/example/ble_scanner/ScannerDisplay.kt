package com.example.ble_scanner

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat


@Composable
fun BleDeviceListScreen(
    activity: ComponentActivity,
    scanner: Scanner,
    client: BLEClient
) {
    val context = LocalContext.current
    val scannerState = scanner.state.collectAsState().value
    val clientState = client.state.collectAsState().value

    val connectingTo = client.isConnection.collectAsState().value

//    var connectingTo by remember { mutableStateOf<Device?>(null) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row {
                OutlinedButton(enabled = connectingTo == null, onClick = {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        scanner.toggleScan()
                    } else {
                        ActivityCompat.requestPermissions(
                            activity, arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ), 1
                        )
                    }
                }) {
                    Text(text = if (scannerState.isScanning) "Stop Scan" else "Start Scan")
                }

                OutlinedButton(onClick = {
                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        client.disconnect()
                    }
                }) {
                    Text(text = "Disconnect")
                }
            }
        }

        clientState?.let { state ->
            val readValues = state.readValues
            val service = state.service

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row {
                    Text(
                        text = state.service.identifier,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedButton(onClick = {
                        service.characteristics.forEach {
                            client.readCharacteristic(it)
                        }
                    }) { Text("Update all") }
                }

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



        Text(
            text = "Found Devices",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(scannerState.devices) {
                DeviceCard(it, connectingTo, clientState != null) {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        client.connect(
                            context,
                            device = it,
                            onConnect = {},
                            onInvalid = {}
                        )
                    } else {
                        ActivityCompat.requestPermissions(
                            activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: Device,
    connectingTo: Device?,
    connected: Boolean,
    onClick: () -> Unit
) {
    val connectingToThis = connectingTo == device

    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.identifier ?: "Unknown device",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MAC: ${device.result.device.address}",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "Signal: ${device.result.rssi} dBm",
                    fontSize = 14.sp,
                    color = if (device.result.rssi > -60) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(enabled = connectingTo == null || connectingToThis, onClick = onClick) {
                Text(if (connectingToThis) if (connected) "Connected" else "Connecting" else "Connect")
            }
        }
    }
}