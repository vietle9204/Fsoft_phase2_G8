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
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var txtSpeed: TextView
    private lateinit var txtDoorStatus: TextView
    private lateinit var txtTireStatus: TextView
    private lateinit var txtStatusLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnBrake: ImageView
    private lateinit var btnStop: ImageView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val handler = Handler(Looper.getMainLooper())

    private val targetName = "HC-05"
    private val targetMac = "00:23:11:A0:22:F2"
    private val REQUEST_BT_PERMISSION = 1001
    private val UUID_SPP: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    // Buffer để ghép dữ liệu
    private val incomingBuffer = StringBuilder()

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

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        btnConnect.setOnClickListener { checkPermissionsAndConnect() }
        btnBrake.setOnClickListener { sendBluetoothMessage("BRAKE") }
        btnStop.setOnClickListener { sendBluetoothMessage("STOP") }
    }

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
                ActivityCompat.requestPermissions(
                    this,
                    missing.toTypedArray(),
                    REQUEST_BT_PERMISSION
                )
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
            val device = pairedDevices.find { it.address.equals(targetMac, ignoreCase = true) }
                ?: pairedDevices.find { it.name == targetName }

            if (device == null) {
                updateUiStatus("❌ Không tìm thấy $targetName — hãy pair trước.")
                setConnectButtonState(false)
                return@Thread
            }

            // ✅ báo tìm thấy
            updateUiStatus("✅ Đã tìm thấy $targetName (${device.address})")
            Log.d("BT_CONNECT", "Đã tìm thấy $targetName - MAC: ${device.address}")

            // giữ 0.5s để hiển thị log
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
                    break
                }
            }
        }.start()
    }

    private fun parseIncomingMessage(message: String) {
        Log.d("BT_READ", "Chuỗi đầy đủ: $message")
        val parts = message.split(";")
        for (part in parts) {
            when {
                part.startsWith("SPEED:") -> {
                    val value = part.substringAfter("SPEED:").trim()
                    txtSpeed.text = "SPEED: $value RPS"
                }
                part.startsWith("DOOR:") -> {
                    val value = part.substringAfter("DOOR:").trim()
                    txtDoorStatus.text = "DOOR: $value"
                }
                part.startsWith("TIRE:") -> {
                    val value = part.substringAfter("TIRE:").trim()
                    txtTireStatus.text = "TIRE: $value"
                }
            }
        }
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
