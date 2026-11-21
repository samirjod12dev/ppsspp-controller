package com.pspcontroller.simple

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Protocol (same for Wi-Fi TCP and Bluetooth RFCOMM), one line per state change:
 *   "<buttonMask> <ax> <ay>\n"   ax/ay in -100..100, ay positive = down
 * Bits: 1 UP, 2 DOWN, 4 LEFT, 8 RIGHT, 16 CROSS, 32 CIRCLE, 64 SQUARE, 128 TRIANGLE,
 *       256 L, 512 R, 1024 START, 2048 SELECT
 */
class MainActivity : Activity() {
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var out: OutputStream? = null
    private var closer: (() -> Unit)? = null
    private var statusTv: TextView? = null
    private var onPad = false
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showConnect()
    }

    private fun status(msg: String) = runOnUiThread { statusTv?.text = msg }

    private fun showConnect() {
        onPad = false
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
            setPadding(64, 24, 64, 24)
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "PSP Controller"; textSize = 24f; setTextColor(Color.WHITE)
        }
        val ip = EditText(this).apply {
            hint = "PC IP (shown in the Windows receiver)"
            setText(prefs.getString("ip", ""))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine()
        }
        val wifi = Button(this).apply { text = "Connect via Wi-Fi" }
        val bt = Button(this).apply { text = "Connect via Bluetooth (paired PC)" }
        val st = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 14f }
        statusTv = st
        wifi.setOnClickListener {
            val addr = ip.text.toString().trim()
            if (addr.isEmpty()) { st.text = "Enter the PC IP first"; return@setOnClickListener }
            prefs.edit().putString("ip", addr).apply()
            connectWifi(addr)
        }
        bt.setOnClickListener { pickBluetooth() }
        listOf(title, ip, wifi, bt, st).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun connectWifi(addr: String) {
        statusTv?.text = "Connecting…"
        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(addr, 7777), 4000)
                out = s.getOutputStream()
                closer = { try { s.close() } catch (_: Exception) {} }
                runOnUiThread { showPad() }
            } catch (e: Exception) {
                status("Wi-Fi failed: ${e.message}")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun pickBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status("Turn Bluetooth on first"); return }
        val devs = adapter.bondedDevices.toList()
        if (devs.isEmpty()) { status("Pair this phone with your PC in Bluetooth settings first"); return }
        AlertDialog.Builder(this)
            .setTitle("Select your PC")
            .setItems(devs.map { it.name ?: it.address }.toTypedArray()) { _, i -> connectBt(devs[i]) }
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) pickBluetooth()
        else status("Bluetooth permission denied")
    }

    @SuppressLint("MissingPermission")
    private fun connectBt(d: BluetoothDevice) {
        status("Connecting to ${d.name}…")
        Thread {
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.cancelDiscovery()
                val s = d.createRfcommSocketToServiceRecord(spp)
                s.connect()
                out = s.outputStream
                closer = { try { s.close() } catch (_: Exception) {} }
                runOnUiThread { showPad() }
            } catch (e: Exception) {
                status("Bluetooth failed: ${e.message}")
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun showPad() {
        onPad = true
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(PadView(this))
        send("0 0 0\n")
    }

    fun send(line: String) {
        val o = out ?: return
        sender.execute {
            try { o.write(line.toByteArray()); o.flush() } catch (_: Exception) { disconnect() }
        }
    }

    private fun disconnect() {
        try { closer?.invoke() } catch (_: Exception) {}
        closer = null; out = null
        if (onPad) runOnUiThread { showConnect() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (onPad) disconnect() else super.onBackPressed() }

    override fun onDestroy() { super.onDestroy(); try { closer?.invoke() } catch (_: Exception) {} }

    private inner class PadView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private class Btn(val bit: Int, val label: String, val color: Int) {
            var cx = 0f; var cy = 0f; var r = 0f
        }
        private val grey = 0xFF9AA0A6.toInt()
        private val btns = listOf(
            Btn(1, "▲", grey), Btn(2, "▼", grey), Btn(4, "◀", grey), Btn(8, "▶", grey),   // 0-3
            Btn(16, "✕", 0xFF5B8DEF.toInt()), Btn(32, "○", 0xFFEF5B5B.toInt()),           // 4,5
            Btn(64, "□", 0xFFE58FD0.toInt()), Btn(128, "△", 0xFF4CD08A.toInt()),          // 6,7
            Btn(256, "L", grey), Btn(512, "R", grey),                                     // 8,9
            Btn(1024, "START", grey), Btn(2048, "SELECT", grey)                           // 10,11
        )
        private var sx = 0f; private var sy = 0f; private var sr = 0f
        private var thx = 0f; private var thy = 0f
        private var mask = 0; private var ax = 0; private var ay = 0
        private var last = ""

        private fun place(i: Int, x: Float, y: Float, r: Float) { btns[i].cx = x; btns[i].cy = y; btns[i].r = r }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val W = w.toFloat(); val H = h.toFloat()
            val d = H * 0.15f; val r = H * 0.11f
            val dx = W * 0.17f; val dy = H * 0.40f
            place(0, dx, dy - d, r); place(1, dx, dy + d, r); place(2, dx - d, dy, r); place(3, dx + d, dy, r)
            val fx = W * 0.83f; val fy = H * 0.45f
            place(7, fx, fy - d, r); place(4, fx, fy + d, r); place(6, fx - d, fy, r); place(5, fx + d, fy, r)
            place(8, W * 0.07f, H * 0.10f, H * 0.08f); place(9, W * 0.93f, H * 0.10f, H * 0.08f)
            place(11, W * 0.44f, H * 0.88f, H * 0.07f); place(10, W * 0.56f, H * 0.88f, H * 0.07f)
            sx = W * 0.17f; sy = H * 0.85f; sr = H * 0.12f
            thx = sx; thy = sy
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            var m = 0; var nax = 0; var nay = 0
            var tx = sx; var ty = sy
            val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
            val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            if (!ending) {
                for (i in 0 until e.pointerCount) {
                    if (i == skip) continue
                    val x = e.getX(i); val y = e.getY(i)
                    val ds = hypot(x - sx, y - sy)
                    if (ds <= sr * 1.4f) {
                        var vx = x - sx; var vy = y - sy
                        if (ds > sr) { vx = vx / ds * sr; vy = vy / ds * sr }
                        nax = (vx / sr * 100).roundToInt(); nay = (vy / sr * 100).roundToInt()
                        tx = sx + vx; ty = sy + vy
                        continue
                    }
                    for (b in btns) if (hypot(x - b.cx, y - b.cy) <= b.r * 1.3f) m = m or b.bit
                }
            }
            thx = tx; thy = ty
            if ((m and mask.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            mask = m; ax = nax; ay = nay
            val line = "$mask $ax $ay\n"
            if (line != last) { last = line; send(line) }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF101014.toInt())
            for (b in btns) {
                val on = (mask and b.bit) != 0
                paint.style = Paint.Style.FILL; paint.color = b.color; paint.alpha = if (on) 255 else 70
                canvas.drawCircle(b.cx, b.cy, b.r, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE; paint.alpha = 120
                canvas.drawCircle(b.cx, b.cy, b.r, paint)
                paint.style = Paint.Style.FILL; paint.alpha = 255; paint.textAlign = Paint.Align.CENTER
                paint.textSize = if (b.label.length > 1) b.r * 0.5f else b.r * 0.9f
                canvas.drawText(b.label, b.cx, b.cy - (paint.descent() + paint.ascent()) / 2, paint)
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.WHITE; paint.alpha = 120
            canvas.drawCircle(sx, sy, sr, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = 200
            canvas.drawCircle(thx, thy, sr * 0.45f, paint)
        }
    }
}
