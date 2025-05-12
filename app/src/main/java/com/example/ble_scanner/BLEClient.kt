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

enum class CharacteristicType(val uuid: UUID) {
    Temperature(uuid = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")),
    Humidity(uuid = UUID.fromString("00002a6f-0000-1000-8000-00805f9b34fb")),
    Intensity(uuid = UUID.fromString("10000001-0000-0000-FDFD-FDFDFDFDFDFD")),
}

enum class ServiceType(val uuid: UUID) {
    IPVSWeather(uuid = UUID.fromString("00000002-0000-0000-FDFD-FDFDFDFDFDFD")),
    IPVSLight(uuid = UUID.fromString("00000001-0000-0000-FDFD-FDFDFDFDFDFD")),
}

val characteristics: Map<UUID, Characteristic> = mapOf(
    CharacteristicType.Temperature.uuid to Characteristic(
        identifier = CharacteristicType.Temperature.name,
        read = {
            ieee11073ToFloat(it.sliceArray(1..it.size - 1), 0).toString() + "°C"
        },
        write = null
    ),
    CharacteristicType.Humidity.uuid to Characteristic(
        identifier = CharacteristicType.Humidity.name,
        read = {
            val str = ByteBuffer
                .wrap(it + byteArrayOf(0x00, 0x00))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt().toString()
            "${str.slice(0..str.length - 3)}.${str.slice(str.length - 2..str.length - 1)}%"
        },
        write = null
    ),
    CharacteristicType.Intensity.uuid to Characteristic(
        identifier = CharacteristicType.Intensity.name,
        read = {
            ByteBuffer
                .wrap(it + byteArrayOf(0x00, 0x00))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt().toString()
        },
        write = {
            it.toUIntOrNull()?.let {
                if (it > 0xFFFFu) throw RuntimeException("Number too large!")
                ByteBuffer.allocate(UInt.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(it.toInt()).array()
            } ?: throw RuntimeException("Number must be a valid 4 byte unsigned integer!")
        }
    )
)

data class Characteristic(
    val identifier: String,
    val read: ((ByteArray) -> String)?,
    val write: ((String) -> ByteArray)?,
)

val services: Map<UUID, Service> = mapOf(
    ServiceType.IPVSWeather.uuid to Service(
        identifier = ServiceType.IPVSWeather.name,
        characteristics = setOf(
            CharacteristicType.Temperature.uuid,
            CharacteristicType.Humidity.uuid,
        )
    ),
    ServiceType.IPVSLight.uuid to Service(
        identifier = ServiceType.IPVSLight.name,
        characteristics = setOf(
            CharacteristicType.Intensity.uuid,
        )
    ),
)

data class Service(
    val identifier: String,
    val characteristics: Set<UUID>,
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

    return (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
}

class BLEClient(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<BLEClientState?>(null)
    val state = _state.asStateFlow()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(characteristicUUID: UUID): Boolean {
        val state = _state.value ?: return false
        return state.gatt.getService(state.serviceUUID)
            ?.getCharacteristic(characteristicUUID)
            ?.let { state.gatt.readCharacteristic(it) } == true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCharacteristic(characteristicUUID: UUID, value: String): Boolean {
        val state = _state.value ?: return false

        val gattCharacteristic = state.gatt
            .getService(state.serviceUUID)
            ?.getCharacteristic(characteristicUUID) ?: return false

        return state.gatt.writeCharacteristic(
            gattCharacteristic,
            characteristics[characteristicUUID]!!.write!!(value),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) == BluetoothStatusCodes.SUCCESS
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableNotifications(enable: Boolean, characteristicUUID: UUID): Boolean {
        val state = _state.value ?: return false

        val gattCharacteristic = state.gatt
            .getService(state.serviceUUID)
            ?.getCharacteristic(characteristicUUID) ?: return false

        state.gatt.setCharacteristicNotification(gattCharacteristic, enable)
        return state.gatt.writeDescriptor(
            gattCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")),
            if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        ) == BluetoothStatusCodes.SUCCESS
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        device: BluetoothDevice,
        onConnect: () -> Unit,
        onInvalid: () -> Unit,
    ) {
        device.connectGatt(getApplication(), false, object : BluetoothGattCallback() {
            fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                _state.update {
                    if (it == null) return@update null
                    it.copy(readValues = it.readValues.toMutableMap().apply {
                        set(
                            characteristic.uuid,
                            characteristics[characteristic.uuid]?.read?.let { it(value) }
                                ?: throw RuntimeException("(Internal) Invalid characteristic.")
                        )
                    })
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(g, status)
                onConnect()

                val validService = g.services?.find { services.containsKey(it.uuid) }
                if (validService == null) {
                    disconnect()
                    onInvalid()
                    return
                }

                val service = services[validService.uuid]!!
                _state.value = BLEClientState(
                    gatt = g,
                    serviceUUID = validService.uuid,
                    service = service,
                    readValues = service.characteristics.associateWith { "-" })
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                write(characteristic, value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)
                write(characteristic, value)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(g, status, newState)

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        g.discoverServices()
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