package com.genshinpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private lateinit var pad: ControllerView

    // Standard HID gamepad: 16 buttons + X, Y, Z, Rz axes (left stick, right stick)
    private val desc = intArrayOf(
        0x05,0x01,0x09,0x05,0xA1,0x01,0x85,0x01,
        0x05,0x09,0x19,0x01,0x29,0x10,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x10,0x81,0x02,
        0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x32,0x09,0x35,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x04,0x81,0x02,
        0xC0).map { it.toByte() }.toByteArray()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        pad = ControllerView(this) { m, lx, ly, rx, ry -> send(m, lx, ly, rx, ry) }
        pad.onConnectTap = { pick() }
        setContentView(pad)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        else start()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { start() }

    private fun start() {
        val ad = getSystemService(BluetoothManager::class.java).adapter
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { hid = proxy as BluetoothHidDevice; register() }
            override fun onServiceDisconnected(p: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings("Genshin Pad", "Touch gamepad", "GenshinPad", 0x08.toByte(), desc)
        hid?.registerApp(sdp, null, null, mainExecutor, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(d: BluetoothDevice?, registered: Boolean) {
                runOnUiThread { pad.status = if (registered) "Tap Connect" else "Not ready" ; pad.invalidate() }
            }
            override fun onConnectionStateChanged(d: BluetoothDevice?, state: Int) {
                runOnUiThread {
                    if (state == BluetoothProfile.STATE_CONNECTED) { host = d; pad.status = "Connected: ${d?.name}" }
                    else if (state == BluetoothProfile.STATE_DISCONNECTED) { host = null; pad.status = "Disconnected" }
                    pad.invalidate()
                }
            }
        })
    }

    private fun pick() {
        val list = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        AlertDialog.Builder(this).setTitle("Choose your iPad (pair it first)")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i -> hid?.connect(list[i]) }
            .show()
    }

    private fun send(m: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        val d = host ?: return
        hid?.sendReport(d, 1, byteArrayOf(m.toByte(), (m shr 8).toByte(), lx.toByte(), ly.toByte(), rx.toByte(), ry.toByte()))
    }
}
