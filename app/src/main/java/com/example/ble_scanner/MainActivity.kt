package com.example.ble_scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
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
import com.bluetoothlowenergy.BLEClient
import com.example.ble_scanner.ui.theme.BLE_scannerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.UUID

enum class Screen() {
    Scanner
}

data class Device(
    val identifier: String?,
    val found: Boolean,
    val result: ScanResult,
)

data class Characteristic(
    val identifier: String,
    val read: ((ByteArray) -> String)?,
    val write: ((Float) -> ByteArray)?,
)

enum class CharacteristicType(val uuid: UUID) {
    Temperature(uuid = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")),
    Humidity(uuid = UUID.fromString("00002a6f-0000-1000-8000-00805f9b34fb")),
    Intensity(uuid = UUID.fromString("10000001-0000-0000-FDFD-FDFDFDFDFDFD")),
}

enum class Service(
    val uuid: UUID?,
    val descriptorUUID: UUID?,
    val characteristics: Map<UUID, Characteristic>
) {
    IPVSWeather(
        uuid = UUID.fromString("00000002-0000-0000-FDFD-FDFDFDFDFDFD"),
        descriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        characteristics = mapOf(
            CharacteristicType.Temperature.uuid to Characteristic(
                identifier = "Temperature",
                read = {
                    String(it, Charset.defaultCharset())
                },
                write = null
            ),
            CharacteristicType.Humidity.uuid to Characteristic(
                identifier = "Humidity",
                read = {
                    String(it, Charset.defaultCharset())
                },
                write = null
            ),
        )
    ),
    IPVSLight(
        uuid = UUID.fromString("00000001-0000-0000-FDFD-FDFDFDFDFDFD"),
        descriptorUUID = null,
        characteristics = mapOf(
            CharacteristicType.Intensity.uuid to Characteristic(
                identifier = "Intensity",
                read = {
                    String(it, Charset.defaultCharset())
                },
                write = {
                    "Bruh".toByteArray()
                }
            ),
        )
    ),
    Other(
        uuid = null,
        descriptorUUID = null,
        characteristics = mapOf(),
    )
}

@RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
fun Context.scannerFlow(serviceUUID: ParcelUuid?): Flow<Device> = callbackFlow {
    val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
    val adapter = bluetoothManager.adapter
    val scanner = adapter.bluetoothLeScanner

    val callback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(cbType: Int, result: ScanResult) {
            trySend(
                when (cbType) {
                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH ->
                        Device(result.device.name, true, result)
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST ->
                        Device(null, false, result)
                    else -> return // Should not occur, however ignore if does
                }
            )
        }
    }

    val filters = serviceUUID
        ?.let { listOf(ScanFilter.Builder().setServiceUuid(it).build()) }
        ?: emptyList()
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
        .build()

    scanner.startScan(filters, settings, callback)
    awaitClose { scanner.stopScan(callback) }
}

class MainActivity : ComponentActivity() {
    suspend fun doWithPermission(perms: Array<String>, action: suspend () -> Unit) {
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            requestPermissions(perms, 1)
        }
    }

    @SuppressLint("MissingPermission")
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


                            var bleClient: BLEClient? = remember { null }

                            BleDeviceListScreen(
                                results = devices,
                                onToggleScan = {
                                    if (it) {
                                        devices.clear()
                                        scanJob = scope.launch {
                                            doWithPermission(
                                                arrayOf(
                                                    Manifest.permission.BLUETOOTH_SCAN,
                                                    Manifest.permission.BLUETOOTH_CONNECT,
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                )
                                            ) {
                                                scannerFlow().distinctUntilChangedBy { it.result.device.address }
                                                    .filter { it.identifier != null }
                                                    .collect { devices += it }
                                            }
                                        }
                                    } else {
                                        scanJob?.cancel()
                                    }
                                },
                                onSelect = {
                                    val uuids = it.result.scanRecord?.serviceUuids

                                    if (uuids?.any { it.uuid.equals(Service.IPVSLight.uuid) } == true) {
                                        bleClient = BLEClient(context, Service.IPVSLight)
                                        bleClient!!.connect(
                                            device = it.result.device,
                                            onConnect = {
                                                Log.d("BLE", "Connected")
                                            },
                                            readCallback = { uuid, string ->
                                                Log.d("BLE", "[${uuid.toString()}]: ${string}")
                                            }
                                        )

                                        bleClient!!.readCharacteristic(CharacteristicType.Intensity.uuid)
                                    } else if (uuids?.any { it.uuid.equals(Service.IPVSWeather.uuid) } == true) {
                                        bleClient = BLEClient(context, Service.IPVSWeather)
                                    } else {
                                        Toast.makeText(context, "Other Devices", Toast.LENGTH_SHORT)
                                            .show()
                                        Log.d(
                                            "BLE",
                                            uuids?.joinToString { it.uuid.toString() }
                                                ?: "No uuids")
                                    }
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
fun TopBarDisplay() {
    TopAppBar(
        title = { Text(text = "BLE Scanner") },
    )
}

@Composable
fun BleDeviceListScreen() {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val devices = remember { mutableStateListOf<Device>() }
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
                if (isScanning) {
                    isScanning = false
                    devices.clear()
                } else {
                    isScanning = true

                }
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
            items(results) {
                DeviceCard(it) { onSelect(it) }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device, onClick: () -> Unit) {
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

            OutlinedButton(onClick = { onClick() }) {
                Text(text = "Connect")
            }
        }
    }
}