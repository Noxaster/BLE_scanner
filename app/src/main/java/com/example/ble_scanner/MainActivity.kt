package com.example.ble_scanner

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ble_scanner.ui.theme.BLE_scannerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

data class Device(
    val name: String,
    val address: String,
    val uuid: String,
    val rssi: Int,
    val device: BluetoothDevice
)

enum class Screen() {
    Scanner
}

@RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
fun Context.scannerFlow(): Flow<Device> = callbackFlow {
    val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
    val adapter = bluetoothManager.adapter
    val scanner = adapter.bluetoothLeScanner

    val callback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(cbType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address
            val uuid =
                result.scanRecord?.serviceUuids?.firstOrNull()?.toString() ?: "Unknown Service"
            val rssi = result.rssi

            Log.d("Bruh", deviceName)

            trySend(Device(deviceName, deviceAddress, uuid, rssi, device))
        }
    }

    val filters = emptyList<ScanFilter>()
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    scanner.startScan(filters, settings, callback)
    awaitClose { scanner.stopScan(callback) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            BLE_scannerTheme {
                val navController = rememberNavController()
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
                            val scope = rememberCoroutineScope()
                            var scanJob by remember { mutableStateOf<Job?>(null) }
                            val devices = remember { mutableStateListOf<Device>() }

                            BleDeviceListScreen(devices) {
                                if (it) {
                                    devices.clear()
                                    scanJob = scope.launch {
                                        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                                            scannerFlow().distinctUntilChangedBy { it.address }
                                                .collect { devices += it }
                                        } else {
                                            requestPermissions(
                                                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1
                                            )
                                        }
                                    }
                                } else {
                                    scanJob?.cancel()
                                }
                            }
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
fun BleDeviceListScreen(devices: List<Device>, onToggleScan: (Boolean) -> Unit) {
    val context = LocalContext.current
    var isScanning by remember { mutableStateOf(false) }

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
                isScanning = !isScanning
                onToggleScan(isScanning)
            }) {
                Text(text = if (isScanning) "Stop Scan" else "Start Scan")
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
            items(devices) { device ->
                DeviceList(device, onConnect = {
                    if (it.uuid.toString() == "00000002-0000-0000-FDFD-FDFDFDFDFDFD") {
                        Toast.makeText(context, "IPVSWeather", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Other Devices", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }

    }
}

@Composable
fun DeviceList(device: Device, onConnect: (Device) -> Unit) {
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
                    text = device.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MAC: ${device.address}",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "UUID: ${device.uuid}",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "Signal: ${device.rssi} dBm",
                    fontSize = 14.sp,
                    color = if (device.rssi > -60) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(onClick = { onConnect(device) }) {
                Text(text = "Connect")
            }
        }
    }
}