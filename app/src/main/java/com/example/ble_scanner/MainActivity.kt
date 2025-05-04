package com.example.ble_scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ble_scanner.ui.theme.BLE_scannerTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

data class Device (
    val name: String,
    val address: String,
    val uuid: String,
    val rssi: Int,
    val device: android.bluetooth.BluetoothDevice?
)

enum class Screen() {
    Scanner
}

val scannerStateFlow = MutableStateFlow(false)

fun Context.scannerFlow(
    filters: List<ScanFilter> = emptyList(),
    settings: ScanSettings = defaultScanSettings()
): Flow<List<Device>> = callbackFlow {

    val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
    val adapter = bluetoothManager.adapter
    val scanner = adapter.bluetoothLeScanner

    val scannedDevices = mutableMapOf<String, Device>()

    val callback = object : ScanCallback() {
        override fun onScanResult(cbType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address
            val uuid = result.scanRecord?.serviceUuids?.firstOrNull()?.toString() ?: "Unknown Service"
            val rssi = result.rssi

            scannedDevices[deviceAddress] = Device(deviceName, deviceAddress, uuid, rssi, device)
            trySend(scannedDevices.values.toList()).isSuccess
        }
    }
    scanner.startScan(filters, settings, callback)

    awaitClose { scanner.stopScan(callback) }
}.buffer(Channel.UNLIMITED)

fun defaultScanSettings(): ScanSettings =
    ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

class MainActivity : ComponentActivity() {

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = bluetoothPermissions.filter {
            checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                1
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBluetoothPermissions()

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager.adapter

        if (adapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(this, enableBtIntent, 1, null)
        }

        enableEdgeToEdge()
        setContent {
            BLE_scannerTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentScreen = Screen.Scanner
                Scaffold(
                    topBar = { TopBarDisplay(currentScreen) },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    val context = LocalContext.current

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Scanner.name,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                            .fillMaxSize()
                    ) {
                        composable(route = Screen.Scanner.name) {
                            val isScanningState = scannerStateFlow.collectAsState()
                            val isScanning = isScanningState.value

                            val devicesFlow = remember {
                                scannerStateFlow.flatMapLatest { isScanning ->
                                    if (isScanning) context.scannerFlow() else emptyFlow()
                                }
                            }
                            val devices by devicesFlow.collectAsState(initial = emptyList())

                            BleDeviceListScreen(
                                devices = devices,
                                isScanning = isScanning,
                                onToggleScan = {
                                    scannerStateFlow.value = !scannerStateFlow.value
                                }
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
fun TopBarDisplay(screen: Screen) {
    val showDialog = remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(text = "BLE Scanner") },
    )
}

@Composable
fun BleDeviceListScreen(
    devices: List<Device>,
    isScanning: Boolean,
    onToggleScan: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(onClick = onToggleScan) {
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
                        Toast.makeText(context, "IPVSWeather", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        Toast.makeText(context, "Other Devices", Toast.LENGTH_SHORT)
                            .show()
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