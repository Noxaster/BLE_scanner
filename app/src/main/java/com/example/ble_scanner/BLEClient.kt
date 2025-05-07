package com.example.ble_scanner

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.pow

data class Characteristic(
    val identifier: String,
    val readSupported: Boolean,
    val writeSupported: Boolean,
)

enum class CharacteristicType(val uuid: UUID) {
    Temperature(uuid = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")),
    Humidity(uuid = UUID.fromString("00002a6f-0000-1000-8000-00805f9b34fb")),
    Intensity(uuid = UUID.fromString("10000001-0000-0000-FDFD-FDFDFDFDFDFD")),
}

data class Service(
    val characteristics: Map<UUID, Characteristic>,
)

enum class ServiceType(val uuid: UUID?) {
    IPVSWeather(uuid = UUID.fromString("00000002-0000-0000-FDFD-FDFDFDFDFDFD")),
    IPVSLight(uuid = UUID.fromString("00000001-0000-0000-FDFD-FDFDFDFDFDFD")),
}

val characteristics: Map<UUID, (ByteArray) -> String> = mapOf(
    CharacteristicType.Temperature.uuid to {
        ieee11073ToFloat(it.sliceArray(1..it.size - 1), 0).toString() + "°C"
    }
)

val services: Map<UUID, Service> = mapOf(
    ServiceType.IPVSWeather.uuid!! to Service(
        characteristics = mapOf(
            CharacteristicType.Temperature.uuid to Characteristic(
                identifier = "Temperature",
                readSupported = true,
                writeSupported = false,
            ),
            CharacteristicType.Humidity.uuid to Characteristic(
                identifier = "Humidity",
                read = {
                    val str = ByteBuffer
                        .wrap(it + byteArrayOf(0x00, 0x00))
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt().toString()
                    "${str.slice(0..str.length - 3)}.${str.slice(str.length - 2..str.length - 1)}%"
                },
            ),
        )
    ),
    ServiceType.IPVSLight.uuid!! to Service(
        characteristics = mapOf(
            CharacteristicType.Intensity.uuid to Characteristic(
                identifier = "Intensity",
                read = {
                    String(it, Charset.defaultCharset())
                },
            ),
        )
    ),
)

data class BLEClientState(
    val gatt: BluetoothGatt,
    val serviceUUID: UUID,
    val service: Service,
    val readValues: Map<UUID, String>,
)

fun ieee11073ToFloat(data: ByteArray, offset: Int): Float {
    var mantissa = (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    val exponent = data[offset + 3]

    if ((mantissa and 0x800000) != 0) {
        mantissa = mantissa or -0x1000000 // sign extend to 32 bits
    }
    return if (exponent < 0) {
        (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
    } else {
        (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
    }
}

class BLEClient(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<BLEClientState?>(null)
    val state = _state.asStateFlow()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(characteristic: CharacteristicType): Boolean {
        val state = _state.value ?: return false
        return state.gatt.getService(state.serviceUUID)
            ?.getCharacteristic(characteristic.uuid)
            ?.let { state.gatt.readCharacteristic(it) } == true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCharacteristic(characteristic: CharacteristicType, value: ByteArray): Boolean {
        val state = _state.value ?: return false

        val gattCharacteristic = state.gatt
            .getService(state.serviceUUID)
            ?.getCharacteristic(characteristic.uuid) ?: return false

        return state.gatt.writeCharacteristic(
            gattCharacteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) == BluetoothStatusCodes.SUCCESS
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableNotifications(enable: Boolean, characteristic: CharacteristicType): Boolean {
        val state = _state.value ?: return false

        val gattCharacteristic = state.gatt
            .getService(state.serviceUUID)
            ?.getCharacteristic(characteristic.uuid) ?: return false

        state.gatt.setCharacteristicNotification(gattCharacteristic, enable)
        return state.gatt.writeDescriptor(
            gattCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")),
            if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        ) == BluetoothStatusCodes.SUCCESS
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        device: BluetoothDevice,
        onConnect: () -> Unit,
    ) {
        device.connectGatt(getApplication(), false, object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(g, status)

                val validService = g.services?.find { services.containsKey(it.uuid) }
                if (validService == null) {
                    disconnect()
                    return
                }

                val service = services[validService.uuid]!!
                _state.value = BLEClientState(
                    gatt = g,
                    serviceUUID = validService.uuid,
                    service = service,
                    readValues = service.characteristics.keys.associateWith { "-" })
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)

                _state.update {
                    if (it == null) return@update null
                    val convVal =
                        it.service.characteristics[characteristic.uuid]?.read?.let { r -> r(value) }
                    Log.d("BLE", "${convVal!!}")
                    it.copy(readValues = it.readValues.toMutableMap().apply {
                        set(characteristic.uuid, convVal)
                    })
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)

                _state.update {
                    if (it == null) return@update null
                    val convVal = it.service.characteristics[characteristic.uuid]!!.read!!(value)
                    Log.d("BLE", "${convVal}")
                    it.copy(readValues = it.readValues.toMutableMap().apply {
                        set(characteristic.uuid, convVal)
                    })
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(g, status, newState)

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        g.discoverServices()
                        onConnect()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> disconnect()
                }
            }
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() = _state.update {
        if (it == null) return@update it
        it.gatt.close()
        return@update null
    }
}