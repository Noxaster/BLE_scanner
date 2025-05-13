package com.example.ble_scanner

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
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
import androidx.navigation.NavHostController


@Composable
fun BleDeviceListScreen(
    activity: ComponentActivity,
    scanner: Scanner,
    client: BLEClient,
    nav: NavHostController
) {
    val context = LocalContext.current
    val scannerState = scanner.state.collectAsState().value
    val clientState = client.state.collectAsState().value

    var connectingTo by remember { mutableStateOf<Device?>(null) }

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
                OutlinedButton(onClick = {
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

                OutlinedButton(enabled = clientState != null, onClick = {
                    connectingTo = null
                    client.disconnect()
                }) {
                    Text(text = "Disconnect")
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
                    if (clientState != null) {
                        nav.navigate(Screen.Client.name)
                    }

                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        connectingTo = it

                        client.connect(
                            device = it.result.device,
                            onConnect = {},
                            onInvalid = {
                                connectingTo = null
                                Toast.makeText(context, "Failed to connect.", Toast.LENGTH_SHORT)
                                    .show()
                            }
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