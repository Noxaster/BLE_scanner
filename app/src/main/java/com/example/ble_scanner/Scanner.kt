package com.example.ble_scanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScannerState(
    val devices: List<Device> = emptyList(),
    val isScanning: Boolean = false,
)

class Scanner(ctx: Context) : ViewModel() {
    private val bluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val scanner = adapter?.bluetoothLeScanner

    private var scanJob: Job? = null

    private val _state = MutableStateFlow(ScannerState())
    val state = _state.asStateFlow()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(serviceUUID: ParcelUuid? = null) {
        if (scanner == null || _state.value.isScanning) return
        _state.value = ScannerState()

        scanJob = viewModelScope.launch {
            callbackFlow<Device> {
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
            }.collect { device ->
                _state.update { state ->
                    if (state.devices.any { it.result.device.address == device.result.device.address }) state
                    else state.copy(state.devices + device)
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