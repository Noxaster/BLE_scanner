package com.example.ble_scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ble_scanner.ui.theme.BLE_scannerTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Screen() {
    Scanner
}

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            BLE_scannerTheme {
                val navController = rememberNavController()

                val scannerViewModel: Scanner = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                )
                val connectViewModel: BLEClient = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                )

                Scaffold(
                    topBar = { TopBarDisplay() },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Scanner.name,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                            .fillMaxSize()
                    ) {
                        composable(route = Screen.Scanner.name) {
                            BleDeviceListScreen(
                                this@MainActivity,
                                scannerViewModel,
                                connectViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarDisplay() {
    TopAppBar(
        title = { Text(text = "BLE Scanner") },
    )
}

@Composable
fun BleDeviceListScreen(activity: ComponentActivity, scanner: Scanner, client: BLEClient) {
    val context = LocalContext.current
    val scannerState = scanner.state.collectAsState().value
    val connectState = client.state.collectAsState().value

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(onClick = {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    scanner.toggleScan()
                } else {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ), 1
                    )
                }
            }) {
                Text(text = if (scannerState.isScanning) "Stop Scan" else "Start Scan")
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
            items(scannerState.devices) { DeviceCard(activity, it, client) }
        }
    }
}

@Composable
fun DeviceCard(activity: ComponentActivity, device: Device, client: BLEClient) {
    val context = LocalContext.current

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

                OutlinedButton(onClick = {
                    // client.enableNotifications(true, CharacteristicType.Temperature)
                    client.writeCharacteristic(CharacteristicType.Intensity, ubyteArrayOf(0x00u, 0xFFu).asByteArray())
                }) {
                    Text(text = "Notifications")
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(onClick = {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    client.connect(device.result.device, {})
                } else {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ), 1
                    )
                }
            }) {
                Text(text = "Connect")
            }

            OutlinedButton(onClick = {
                client.disconnect()
            }) {
                Text(text = "Disconnect")
            }
        }
    }
}