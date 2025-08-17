package com.example.bletest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : Activity() {

    private lateinit var txtSpeed: TextView
    private lateinit var txtDoorStatus: TextView
    private lateinit var txtTireStatus: TextView
    private lateinit var txtStatusLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnBrake: Button
    private lateinit var btnStop: Button

    private val targetName = "HC-05"
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val REQUEST_CODE = 101

    // UUID thật từ HC-05 BLE mode
    private val SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtSpeed = findViewById(R.id.txtSpeed)
        txtDoorStatus = findViewById(R.id.txtDoorStatus)
        txtTireStatus = findViewById(R.id.txtTireStatus)
        txtStatusLog = findViewById(R.id.txtStatusLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnBrake = findViewById(R.id.btnBrake)
        btnStop = findViewById(R.id.btnStop)

        btnConnect.setOnClickListener { checkPermissionsAndStart() }
        btnBrake.setOnClickListener { sendCommand("BRAKE") }
        btnStop.setOnClickListener { sendCommand("STOP") }
    }

    private fun log(msg: String) {
        runOnUiThread { txtStatusLog.append("\n$msg") }
    }

    private fun checkPermissionsAndStart() {
        val perms = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE)
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            log("❌ Bluetooth tắt hoặc không hỗ trợ")
            return
        }
        scanner = adapter.bluetoothLeScanner
        log("🔍 Bắt đầu quét BLE...")
        scanner?.startScan(scanCallback)
        handler.postDelayed({
            scanner?.stopScan(scanCallback)
            log("⏹ Dừng quét")
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != null) {
                log("📡 Tìm thấy: ${device.name} (${device.address})")
                if (device.name == targetName) {
                    log("✅ Đúng thiết bị, kết nối...")
                    scanner?.stopScan(this)
                    device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("✅ Kết nối GATT thành công, khám phá service...")
                bluetoothGatt = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("⚠️ Mất kết nối GATT")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("📋 Services:")
            gatt.services.forEach { service ->
                log("Service UUID: ${service.uuid}")
                if (service.uuid == SERVICE_UUID) {
                    val characteristic = service.getCharacteristic(CHAR_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        // Bật notify qua CCCD
                        val descriptor = characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        log("📌 Đã bật notify characteristic")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val rawData = characteristic.value?.toString(Charsets.UTF_8)?.trim() ?: ""
            log("📥 Nhận dữ liệu: $rawData")

            // Ví dụ: SPEED:2;DOOR:CLOSED;TIRE:OK
            val parts = rawData.split(";")
            var speedVal = "--"
            var doorVal = "--"
            var tireVal = "--"

            for (part in parts) {
                when {
                    part.startsWith("SPEED:", ignoreCase = true) ->
                        speedVal = part.substringAfter("SPEED:").trim()
                    part.startsWith("DOOR:", ignoreCase = true) ->
                        doorVal = part.substringAfter("DOOR:").trim()
                    part.startsWith("TIRE:", ignoreCase = true) ->
                        tireVal = part.substringAfter("TIRE:").trim()
                }
            }

            runOnUiThread {
                txtSpeed.text = "Speed: $speedVal RPS"
                txtDoorStatus.text = "🚪 Door: $doorVal"
                txtTireStatus.text = "🔧 Tire: $tireVal"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(cmd: String) {
        log("📤 Gửi lệnh: $cmd")
        bluetoothGatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)?.let { char ->
            char.setValue(cmd)
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                bluetoothGatt?.close()
            } catch (_: Exception) {}
        }
    }
}
