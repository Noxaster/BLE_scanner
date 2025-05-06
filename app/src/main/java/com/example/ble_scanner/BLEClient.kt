package com.bluetoothlowenergy

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.example.ble_scanner.CharacteristicType
import com.example.ble_scanner.Service
import java.util.UUID

class BLEClient(private val context: Context, private val service: Service) {
    private var gatt: BluetoothGatt? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(characteristicUUID: UUID) {
        gatt?.getService(service.uuid)?.getCharacteristic(characteristicUUID)?.let {
            gatt?.readCharacteristic(it)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableNotifications(enable: Boolean, characteristic: CharacteristicType) {
        gatt?.getService(service.uuid)?.getCharacteristic(characteristic.uuid)?.let {
            gatt?.setCharacteristicNotification(it, enable)
            val desc = it.getDescriptor(service.descriptorUUID)

            val enableValue = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            gatt?.writeDescriptor(desc, enableValue)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        device: BluetoothDevice,
        onConnect: () -> Unit,
        readCallback: ((UUID, String?) -> Unit)? = null,
        notifyCallback: ((UUID, String?) -> Unit)? = null
    ) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(g, status)
                gatt = g
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                if (readCallback != null) {
                    readCallback(
                        characteristic.uuid,
                        service.characteristics[characteristic.uuid]?.read?.let { it(value) }
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)
                if (notifyCallback != null) {
                    notifyCallback(
                        characteristic.uuid,
                        service.characteristics[characteristic.uuid]?.read?.let { it(value) }
                    )
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
    fun disconnect() {
        gatt?.close()
        gatt = null
    }
}