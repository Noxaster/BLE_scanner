package com.example.ble_scanner

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Device(
    val identifier: String?,
    val result: ScanResult,
)

data class ScannerState(
    val devices: List<Device> = emptyList(),
    val isScanning: Boolean = false,
)

class Scanner(application: Application) : AndroidViewModel(application) {
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(ScannerState())
    val state = _state.asStateFlow()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun toggleScan(serviceUUID: ParcelUuid? = null) =
        if (_state.value.isScanning) stopScan() else startScan(serviceUUID)

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(serviceUUID: ParcelUuid? = null) {
        val ctx: Context = getApplication()
        val bluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner

        if (scanner == null || _state.value.isScanning) return
        _state.value = ScannerState(isScanning = true)

        scanJob = viewModelScope.launch {
            callbackFlow<Device> {
                val callback = object : ScanCallback() {
                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun onScanResult(cbType: Int, result: ScanResult) {
                        trySend(Device(result.device.name, result))
                    }
                }

                val filters = serviceUUID
                    ?.let { listOf(ScanFilter.Builder().setServiceUuid(it).build()) }
                    ?: emptyList()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build()

                scanner.startScan(filters, settings, callback)
                awaitClose { scanner.stopScan(callback) }
            }.collect { device ->
                _state.update { state ->
                    if (device.identifier == null ||
                        state.devices.any { it.result.device.address == device.result.device.address }) state
                    else state.copy(devices = state.devices + device)
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null

        _state.update { it.copy(isScanning = false) }
    }
}