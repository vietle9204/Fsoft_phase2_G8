package com.example.mydemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI chính
    private lateinit var txtLeftSpeed: TextView
    private lateinit var txtRightSpeed: TextView
    private lateinit var txtBrakeLeft: TextView
    private lateinit var txtBrakeRight: TextView
    private lateinit var txtStatusLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnBrakeLeft: ImageView
    private lateinit var btnBrakeRight: ImageView
    private lateinit var btnStop: ImageView
    private lateinit var btnApplySpeed: Button
    private lateinit var inputLeftSpeed: EditText
    private lateinit var inputRightSpeed: EditText
    private lateinit var btnSensors: Button
    private lateinit var btnDevices: Button

    // View trong BottomSheet Sensors
    private var sensorDistance: TextView? = null
    private var sensorLight: TextView? = null
    private var sensorHumidity: TextView? = null
    private var sensorDoor: TextView? = null

    // View trong BottomSheet Devices
    private var deviceLight: TextView? = null
    private var deviceAC: TextView? = null
    private var deviceWiper: TextView? = null

    // ✅ Biến lưu trạng thái cuối cùng (không reset khi đóng/mở bottomsheet)
    private var lastDistance = "--"
    private var lastLightSensor = "--"
    private var lastHumidity = "--"
    private var lastDoor = "--"

    private var lastDeviceLight = "--"
    private var lastAC = "--"
    private var lastWiper = "--"

    // Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val handler = Handler(Looper.getMainLooper())
    private val incomingBuffer = StringBuilder()

    private val targetName = "HC-05"
    private val targetMac = "00:23:11:A0:22:F2"
    private val REQUEST_BT_PERMISSION = 1001
    private val UUID_SPP: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Mapping View
        txtLeftSpeed = findViewById(R.id.txtLeftSpeed)
        txtRightSpeed = findViewById(R.id.txtRightSpeed)
        txtBrakeLeft = findViewById(R.id.txtBrakeLeft)
        txtBrakeRight = findViewById(R.id.txtBrakeRight)
        txtStatusLog = findViewById(R.id.txtStatusLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnBrakeLeft = findViewById(R.id.btnBrakeLeft)
        btnBrakeRight = findViewById(R.id.btnBrakeRight)
        btnStop = findViewById(R.id.btnStop)
        btnApplySpeed = findViewById(R.id.btnApplySpeed)
        inputLeftSpeed = findViewById(R.id.inputLeftSpeed)
        inputRightSpeed = findViewById(R.id.inputRightSpeed)
        btnSensors = findViewById(R.id.btnSensors)
        btnDevices = findViewById(R.id.btnDevices)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Nút Connect
        btnConnect.setOnClickListener { checkPermissionsAndConnect() }
        // Nút Brake
        btnBrakeLeft.setOnClickListener { sendBluetoothMessage("BRAKE LEFT") }
        btnBrakeRight.setOnClickListener { sendBluetoothMessage("BRAKE RIGHT") }
        // Nút Stop
        btnStop.setOnClickListener { sendBluetoothMessage("STOP") }
        // Nút Apply Speed
        btnApplySpeed.setOnClickListener {
            val leftStr = inputLeftSpeed.text.toString().ifBlank { "0" }
            val rightStr = inputRightSpeed.text.toString().ifBlank { "0" }

            try {
                val left = leftStr.toDouble()
                val right = rightStr.toDouble()

                if (left < -100 || left > 100 || right < -100 || right > 100) {
                    showToast("⚠️ Speed phải nằm trong khoảng -100 đến 100 RPS")
                    return@setOnClickListener
                }

                sendBluetoothMessage("SET SPEED:$left/$right")
            } catch (e: NumberFormatException) {
                showToast("⚠️ Giá trị nhập không hợp lệ")
            }
        }
        // Nút mở BottomSheet
        btnSensors.setOnClickListener { showSensorsBottomSheet() }
        btnDevices.setOnClickListener { showDevicesBottomSheet() }
    }

    // ================== Bluetooth ===================
    private fun checkPermissionsAndConnect() {
        if (bluetoothAdapter == null) {
            updateUiStatus("❌ Thiết bị không hỗ trợ Bluetooth")
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
            updateUiStatus("❌ Bluetooth chưa bật trên điện thoại")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionsNeeded = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = permissionsNeeded.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT_PERMISSION)
                return
            }
        }
        connectToBluetoothDevice()
    }

    @SuppressLint("MissingPermission")
    private fun connectToBluetoothDevice() {
        Thread {
            updateUiStatus("🔍 Đang tìm thiết bị $targetName / $targetMac ...")
            setConnectButtonState(true)

            val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val device: BluetoothDevice? =
                pairedDevices.find { it.address.equals(targetMac, true) }
                    ?: pairedDevices.find { it.name == targetName }

            if (device == null) {
                updateUiStatus("❌ Không tìm thấy $targetName — hãy pair trước.")
                setConnectButtonState(false)
                return@Thread
            }

            updateUiStatus("✅ Đã tìm thấy $targetName (${device.address})")
            Thread.sleep(500)
            updateUiStatus("⏳ Chờ HC-05 sẵn sàng...")
            Thread.sleep(500)

            try {
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID_SPP)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream

                updateUiStatus("✅ Kết nối thành công (SPP)")
                showToast("Kết nối thành công (SPP)")

                handler.post {
                    btnConnect.text = "Connected"
                    btnConnect.isEnabled = false
                }
                startListeningForData()
            } catch (e: IOException) {
                updateUiStatus("❌ Kết nối thất bại: ${e.message}")
                setConnectButtonState(false)
            }
        }.start()
    }

    private fun sendBluetoothMessage(message: String) {
        if (outputStream == null) {
            showToast("Chưa kết nối Bluetooth")
            return
        }
        try {
            outputStream?.write(message.toByteArray())
            showToast("Đã gửi: $message")
        } catch (e: IOException) {
            showToast("Lỗi gửi: ${e.message}")
        }
    }

    private fun startListeningForData() {
        Thread {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: break
                    if (bytes > 0) {
                        val chunk = String(buffer, 0, bytes)
                        synchronized(incomingBuffer) {
                            incomingBuffer.append(chunk)
                            var newlineIndex: Int
                            while (true) {
                                newlineIndex = incomingBuffer.indexOf("\n")
                                if (newlineIndex == -1) break
                                val line = incomingBuffer.substring(0, newlineIndex).trim()
                                incomingBuffer.delete(0, newlineIndex + 1)
                                handler.post { parseIncomingMessage(line) }
                            }
                        }
                    }
                } catch (e: IOException) {
                    // 🔴 Khi HC-05 tắt hoặc mất kết nối → xử lý ở đây
                    onConnectionLost()
                    break
                }
            }
        }.start()
    }

    // ================== Parse dữ liệu ===================
    private fun parseIncomingMessage(message: String) {
        Log.d("BT_READ", "Chuỗi: $message")

        when {
            message.startsWith("SPEED:") -> {
                val parts = message.removePrefix("SPEED:").split(";")
                if (parts.size == 2) {
                    txtLeftSpeed.text = "Speed 1: ${parts[0]} RPS"
                    txtRightSpeed.text = "Speed 2: ${parts[1]} RPS"
                }
            }
            message.startsWith("BRAKE:") -> {
                val parts = message.removePrefix("BRAKE:").split(";")
                if (parts.size == 2) {
                    txtBrakeLeft.text = "Brake Left: ${parts[0]}"
                    txtBrakeRight.text = "Brake Right: ${parts[1]}"
                }
            }
            message.contains("DISTANCE:") -> {
                val parts = message.split(";")
                for (p in parts) {
                    when {
                        p.startsWith("DISTANCE:") -> {
                            lastDistance = p.substringAfter(":")
                            sensorDistance?.text = "📏 Distance: $lastDistance"
                        }
                        p.startsWith("LIGHT:") -> {
                            lastLightSensor = p.substringAfter(":")
                            sensorLight?.text = "💡 Light: $lastLightSensor"
                        }
                        p.startsWith("HUMIDITY:") -> {
                            lastHumidity = p.substringAfter(":")
                            sensorHumidity?.text = "💧 Humidity: $lastHumidity"
                        }
                        p.startsWith("DOOR:") -> {
                            lastDoor = p.substringAfter(":")
                            sensorDoor?.text = "🚪 Door: $lastDoor"
                        }
                    }
                }
            }
            message.contains("AC:") || message.contains("WIPER:") -> {
                val parts = message.split(";")
                for (p in parts) {
                    when {
                        p.startsWith("LIGHT:") -> {
                            lastDeviceLight = p.substringAfter(":")
                            deviceLight?.text = "💡 Light: $lastDeviceLight"
                        }
                        p.startsWith("AC:") -> {
                            lastAC = p.substringAfter(":")
                            deviceAC?.text = "❄️ Air Condition: $lastAC"
                        }
                        p.startsWith("WIPER:") -> {
                            lastWiper = p.substringAfter(":")
                            deviceWiper?.text = "🧹 Wiper: $lastWiper"
                        }
                    }
                }
            }
        }
    }

    // ================== BottomSheets ===================
    private fun showSensorsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_sensors, null)
        sensorDistance = view.findViewById(R.id.txtDistance)
        sensorLight = view.findViewById(R.id.txtLight)
        sensorHumidity = view.findViewById(R.id.txtHumidity)
        sensorDoor = view.findViewById(R.id.txtDoorOpen)

        // 👉 Set lại dữ liệu cuối cùng ngay khi mở
        sensorDistance?.text = "📏 Distance: $lastDistance"
        sensorLight?.text = "💡 Light: $lastLightSensor"
        sensorHumidity?.text = "💧 Humidity: $lastHumidity"
        sensorDoor?.text = "🚪 Door: $lastDoor"

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showDevicesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_devices, null)
        deviceLight = view.findViewById(R.id.txtLightStatus)
        deviceAC = view.findViewById(R.id.txtACStatus)
        deviceWiper = view.findViewById(R.id.txtWiperStatus)

        // 👉 Set lại dữ liệu cuối cùng ngay khi mở
        deviceLight?.text = "💡 Light: $lastDeviceLight"
        deviceAC?.text = "❄️ Air Condition: $lastAC"
        deviceWiper?.text = "🧹 Wiper: $lastWiper"

        dialog.setContentView(view)
        dialog.show()
    }

    // ================== Helper ===================
    private fun onConnectionLost() {
        closeSocket()
        updateUiStatus("❌ Mất kết nối Bluetooth")
        handler.post {
            btnConnect.text = "Connect"
            btnConnect.isEnabled = true
        }
        showToast("Mất kết nối Bluetooth")
    }

    private fun closeSocket() {
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        bluetoothSocket = null
        outputStream = null
        inputStream = null
    }

    private fun showToast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun updateUiStatus(status: String) {
        handler.post { txtStatusLog.text = "Trạng thái: $status" }
    }

    private fun setConnectButtonState(loading: Boolean) {
        handler.post {
            btnConnect.text = if (loading) "Connecting..." else "Connect"
            btnConnect.isEnabled = !loading
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSocket()
    }
}
