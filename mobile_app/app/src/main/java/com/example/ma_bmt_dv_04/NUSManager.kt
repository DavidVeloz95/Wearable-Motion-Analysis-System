package com.example.ma_bmt_dv_04

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.experimental.xor
import kotlinx.coroutines.*

class NUSManager(
    private val context: Context,
    // private val onImuLine: (String) -> Unit,    // si los datos vienen como CSV/text
    private val onImuSample: (ImuSample) -> Unit,
    private val onConnected: (Boolean) -> Unit
) {
    private val TAG = "NUSManager"

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val bleRxBuffer = ByteArrayOutputStream()

    private var bleScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

        private var sampleCounter = 0

    /** NORDIC UART SERVICE UUIDs **/
    private val NUS_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX_CHAR = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write from mobile
    private val NUS_TX_CHAR = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify to mobile

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            Log.d(TAG, "Found device: ${dev.address} / ${dev.name}")
            // filtro por name o por service UUID
            if (dev.name?.contains("XIAO", true) == true || result.scanRecord?.serviceUuids?.any { it.uuid == NUS_SERVICE } == true) {
                // parar escaneo y conectar
                Log.d(TAG, "XIAO found → stopping scan and connecting...")
                scanner?.stopScan(this)
                connectToDevice(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG,"Scan failed: $errorCode")
        }
    }

    data class ImuSample(
        val t: Int,
        val ax: Short,
        val ay: Short,
        val az: Short,
        val gx: Short,
        val gy: Short,
        val gz: Short,
        val temp: Short
    )

    /** Start BLE scan **/
    @SuppressLint("MissingPermission")
    fun startScan() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE)) // opcional: filtra por NUS
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG,"Scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        Log.d(TAG, "Scan stopped.")
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    /** GATT Callback **/
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG,"Connected, discovering services")
                onConnected(true)
                bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG,"Disconnected")
                onConnected(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG,"Services discovered: ${gatt.services.size}")
            val service = gatt.getService(NUS_SERVICE)
            if (service != null) {
                val txChar = service.getCharacteristic(NUS_TX_CHAR)
                // activar notificaciones
                gatt.setCharacteristicNotification(txChar, true)
                // descriptor CCC to enable notifs
                val ccc = txChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                ccc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (ccc != null) gatt.writeDescriptor(ccc)
            } else {
                Log.w(TAG,"NUS service not found; list services to debug")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            if (data.isEmpty()) return
            // Log.d("DATA", data.joinToString(",") { it.toUByte().toString() })
            bleScope.launch{ parseBlePacket(data.clone()) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG,"Descriptor written: ${descriptor.uuid} status:$status")
        }
    }

    /** Write to NUS (optional, not needed for IMU read) **/
    @SuppressLint("MissingPermission")
    fun sendText(text: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(NUS_SERVICE) ?: return
        val rxChar = service.getCharacteristic(NUS_RX_CHAR) ?: return

        rxChar.value = text.toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(rxChar)
    }

    /** Disconnect **/
    @SuppressLint("MissingPermission")
    fun disconnect() {
        bleScope.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.d(TAG, "Disconnected from GATT.")
    }

    private fun parseBlePacket(data: ByteArray) {
        // DEBUG
        // Log.d("SYSTEM", "Byte0 raw=${data[0]} unsigned=${data[0].toInt() and 0xFF}")
        // Log.d("SYSTEM", "Byte1 raw=${data[1]} unsigned=${data[1].toInt() and 0xFF}")

        if ((data[0].toInt() and 0xFF) != 0xA5) return
        if ((data[1].toInt() and 0xFF) != 1) return
        if (data.size < 20) return

        var offset = 2

        val t = ByteBuffer.wrap(data, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        offset += 4

        val ax = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2
        val ay = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2
        val az = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2

        val gx = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2
        val gy = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2
        val gz = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        offset += 2

        val temp = ByteBuffer.wrap(data, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short

        handleImuSample(
            ImuSample(t, ax, ay, az, gx, gy, gz, temp)
        )

    }

    private fun handleImuSample(s: ImuSample) {
        sampleCounter++

        if (sampleCounter % 100 == 0) {
            Log.d("IMU", "T=${s.t} AX=${s.ax}")
        }

        CoroutineScope(Dispatchers.Main).launch {
            onImuSample(s)
        }

    }
}