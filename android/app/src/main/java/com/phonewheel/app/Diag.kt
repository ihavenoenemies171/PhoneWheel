package com.phonewheel.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Live numbers for the diagnostics screen and the debug log. All fields are written by worker threads. */
class Diag {
    // rates (per second), updated once a second
    @Volatile var sendRate = 0
    @Volatile var recvRate = 0
    @Volatile var sensorRate = 0
    @Volatile var frameRate = 0
    @Volatile var sensorName = ""
    @Volatile var rawSteerDeg = 0f
    @Volatile var touchMin = 0f
    @Volatile var touchMax = 0f

    // counters
    @Volatile var sent = 0
    @Volatile var recv = 0
    @Volatile var sensorEvents = 0
    @Volatile var frames = 0
    private var lastRateT = 0L

    // ping history (ms), newest last
    val pings = IntArray(120)
    @Volatile var pingN = 0

    fun addPing(ms: Int) {
        synchronized(pings) {
            if (pingN < pings.size) pings[pingN++] = ms
            else { System.arraycopy(pings, 1, pings, 0, pings.size - 1); pings[pings.size - 1] = ms }
        }
    }

    fun tickRates(now: Long) {
        if (lastRateT == 0L) { lastRateT = now; return }
        val dt = now - lastRateT
        if (dt < 1000) return
        val k = 1000f / dt
        sendRate = (sent * k).toInt(); sent = 0
        recvRate = (recv * k).toInt(); recv = 0
        sensorRate = (sensorEvents * k).toInt(); sensorEvents = 0
        frameRate = (frames * k).toInt(); frames = 0
        lastRateT = now
    }

    // 30 s of samples at 20 Hz for the debug log
    private val log = ArrayDeque<String>()
    private var lastSampleT = 0L

    fun sample(now: Long, st: InputState, tel: Telem, ping: Int) {
        if (now - lastSampleT < 50) return
        lastSampleT = now
        val line = String.format(Locale.US, "%d,%.3f,%.3f,%.3f,%d,%d,%s,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
            now, st.steer, st.thr, st.brk, st.mask, ping, tel.game, tel.gear, tel.speed, tel.rpm,
            tel.vAbs, tel.vKerb, tel.vSlip, tel.vRoad, tel.vOff, tel.vUnder, tel.vOver)
        synchronized(log) {
            log.addLast(line)
            while (log.size > 600) log.removeFirst()
        }
    }

    fun samples(): List<String> = synchronized(log) { log.toList() }
}

/** Battery level / temperature / charging, and the phone's thermal state. */
object Power {
    data class State(val level: Int, val tempC: Float, val charging: Boolean, val thermal: Int)

    fun read(ctx: Context): State {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        return State(if (lvl >= 0) lvl * 100 / scale.coerceAtLeast(1) else -1, temp, plugged, thermal)
    }

    fun thermalName(t: Int) = when (t) {
        0 -> "normal"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"
        else -> "?"
    }
}

/** Full-screen diagnostics page with an "export debug log" button. */
object DiagScreen {

    fun show(act: Activity, prefs: Prefs, st: InputState, tel: Telem, diag: Diag, link: Link, haptics: Haptics) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dp = act.resources.displayMetrics.density
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D10.toInt())
        }
        val view = DiagView(act, prefs, st, tel, diag, link, haptics)
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (6 * dp).toInt()) }
        fun btn(label: String, action: () -> Unit) = bar.addView(Button(act).apply {
            text = label; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        })
        btn("Send debug log to PC") { exportLog(act, prefs, st, tel, diag, link, haptics) }
        btn("Close") { dlg.dismiss() }
        root.addView(bar)
        dlg.setContentView(root)
        dlg.show()
    }

    private fun buildLog(act: Activity, prefs: Prefs, tel: Telem, diag: Diag, link: Link, haptics: Haptics): String {
        val sb = StringBuilder()
        val ver = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (_: Exception) { "?" }
        val pw = Power.read(act)
        sb.append("PhoneWheel debug log ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("app ").append(ver).append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("screen ").append(act.resources.displayMetrics.widthPixels).append('x').append(act.resources.displayMetrics.heightPixels)
            .append(" density ").append(act.resources.displayMetrics.density).append('\n')
        sb.append("battery ").append(pw.level).append("% ").append(pw.tempC).append("C charging=").append(pw.charging)
            .append(" thermal=").append(Power.thermalName(pw.thermal)).append('\n')
        sb.append("sensor ").append(diag.sensorName).append(" ").append(diag.sensorRate).append(" Hz, frames ")
            .append(diag.frameRate).append(" fps, send ").append(diag.sendRate).append("/s, recv ").append(diag.recvRate).append("/s\n")
        sb.append("touch size min/max ").append(diag.touchMin).append('/').append(diag.touchMax)
            .append(", vibrator=").append(haptics.supported).append(" amplitude=").append(haptics.amplitudeControl).append('\n')
        sb.append("connection mode ").append(prefs.mode).append(" pc ").append(link.pcHost).append('\n')
        sb.append("pings ").append(synchronized(diag.pings) { diag.pings.copyOf(diag.pingN).joinToString(",") }).append('\n')
        sb.append("game ").append(tel.game).append(" live=").append(tel.live).append(" steerLock=").append(tel.steerLock).append('\n')
        sb.append("\n[settings]\n")
        for ((k, v) in act.getSharedPreferences("phonewheel", Context.MODE_PRIVATE).all.toSortedMap()) {
            sb.append(k).append('=').append(v).append('\n')
        }
        sb.append("\n[samples] time,steer,thr,brk,mask,ping,game,gear,kmh,rpm,abs,kerb,slip,road,off,under,over\n")
        for (l in diag.samples()) sb.append(l).append('\n')
        return sb.toString()
    }

    private fun exportLog(act: Activity, prefs: Prefs, st: InputState, tel: Telem, diag: Diag, link: Link, haptics: Haptics) {
        val text = buildLog(act, prefs, tel, diag, link, haptics)
        val host = link.pcHost
        if (host == null) {
            share(act, text)
            return
        }
        thread(isDaemon = true) {
            val ok = try {
                val c = URL("http://$host:8765/log").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 2000
                c.readTimeout = 4000
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                c.disconnect()
                code == 200
            } catch (_: Exception) { false }
            act.runOnUiThread {
                if (ok) android.widget.Toast.makeText(act,
                    "Debug log saved on the PC in the 'logs' folder next to PhoneWheel.exe", android.widget.Toast.LENGTH_LONG).show()
                else share(act, text)
            }
        }
    }

    private fun share(act: Activity, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PhoneWheel debug log")
            putExtra(Intent.EXTRA_TEXT, text.take(400_000))
        }
        try { act.startActivity(Intent.createChooser(i, "Share PhoneWheel debug log")) } catch (_: Exception) {}
    }
}

@SuppressLint("ViewConstructor")
private class DiagView(
    ctx: Context,
    private val prefs: Prefs,
    private val st: InputState,
    private val tel: Telem,
    private val diag: Diag,
    private val link: Link,
    private val haptics: Haptics,
) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL) }
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val normal = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private var power = Power.read(ctx)
    private var powerAt = 0L

    override fun onDraw(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        if (now - powerAt > 5000) { power = Power.read(context); powerAt = now }
        val w = width.toFloat()
        val col = w / 3f
        val pad = 14 * dp
        var y: Float

        fun head(x: Float, yy: Float, s: String): Float {
            p.typeface = bold; p.textSize = 14 * dp; p.color = 0xFFFFCC00.toInt()
            c.drawText(s, x, yy, p); return yy + 20 * dp
        }
        fun row(x: Float, yy: Float, k: String, v: String, warn: Boolean = false): Float {
            p.typeface = normal; p.textSize = 13 * dp; p.color = 0xFF8A94A3.toInt()
            c.drawText(k, x, yy, p)
            p.typeface = bold; p.color = if (warn) 0xFFFF8A00.toInt() else Color.WHITE
            c.drawText(v, x + col * 0.48f, yy, p)
            return yy + 18 * dp
        }
        fun bar(x: Float, yy: Float, k: String, v: Float, color: Int): Float {
            p.typeface = normal; p.textSize = 13 * dp; p.color = 0xFF8A94A3.toInt()
            c.drawText(k, x, yy, p)
            val bx = x + col * 0.4f; val bw = col * 0.5f
            p.color = 0xFF1C2026.toInt(); c.drawRect(bx, yy - 10 * dp, bx + bw, yy, p)
            p.color = color; c.drawRect(bx, yy - 10 * dp, bx + bw * v.coerceIn(0f, 1f), yy, p)
            return yy + 17 * dp
        }

        // --- column 1: connection
        var x = pad
        y = head(x, 28 * dp, "CONNECTION")
        val pings = synchronized(diag.pings) { diag.pings.copyOf(diag.pingN) }
        val sorted = pings.sorted()
        val med = if (sorted.isNotEmpty()) sorted[sorted.size / 2] else -1
        val mode = when (prefs.mode) { Prefs.MODE_USB -> "USB (adb)"; Prefs.MODE_MANUAL -> "WiFi (typed)"; else -> "Automatic" }
        y = row(x, y, "Mode", mode)
        y = row(x, y, "PC", link.pcHost ?: "not found", link.pcHost == null)
        y = row(x, y, "Ping (median)", if (med >= 0) "$med ms" else "–", med > 20)
        y = row(x, y, "Ping spread", if (sorted.size > 2) "${sorted.last() - sorted.first()} ms" else "–", sorted.size > 2 && sorted.last() - sorted.first() > 15)
        y = row(x, y, "Sent", "${diag.sendRate}/s")
        y = row(x, y, "Received", "${diag.recvRate}/s")
        // ping graph
        val gTop = y; val gH = 70 * dp; val gW = col - 2 * pad
        p.color = 0xFF14181D.toInt(); c.drawRect(x, gTop, x + gW, gTop + gH, p)
        if (pings.isNotEmpty()) {
            val maxP = maxOf(20, pings.maxOrNull() ?: 20).toFloat()
            p.color = 0xFF2BD96B.toInt(); p.strokeWidth = 2 * dp
            for (i in 1 until pings.size) {
                val x0 = x + gW * (i - 1) / (pings.size - 1).coerceAtLeast(1)
                val x1 = x + gW * i / (pings.size - 1).coerceAtLeast(1)
                c.drawLine(x0, gTop + gH * (1 - pings[i - 1] / maxP), x1, gTop + gH * (1 - pings[i] / maxP), p)
            }
            p.typeface = normal; p.textSize = 10 * dp; p.color = 0xFF8A94A3.toInt()
            c.drawText("${maxP.toInt()} ms", x + 4 * dp, gTop + 12 * dp, p)
        }
        y = gTop + gH + 22 * dp
        y = head(x, y, "PHONE")
        y = row(x, y, "Battery", "${power.level}%${if (power.charging) " charging" else ""}", power.level in 0..15 && !power.charging)
        y = row(x, y, "Battery temp", String.format(Locale.US, "%.1f °C", power.tempC), power.tempC > 42f)
        y = row(x, y, "Thermal state", Power.thermalName(power.thermal), power.thermal >= 2)
        y = row(x, y, "Screen redraw", "${diag.frameRate} fps (cap ${prefs.maxFps.toInt()})")

        // --- column 2: inputs
        x = col + pad
        y = head(x, 28 * dp, "INPUTS")
        y = row(x, y, "Sensor", diag.sensorName.ifEmpty { "none" })
        y = row(x, y, "Sensor rate", "${diag.sensorRate} Hz", diag.sensorRate in 1..40)
        y = row(x, y, "Tilt angle", String.format(Locale.US, "%+.1f°", diag.rawSteerDeg))
        y = bar(x, y, "Steering", (st.steer + 1f) / 2f, 0xFFFFCC00.toInt())
        y = bar(x, y, "Throttle", st.thr, 0xFF2BD96B.toInt())
        y = bar(x, y, "Brake", st.brk, 0xFFFF3B30.toInt())
        y = row(x, y, "Buttons", if (st.mask == 0) "–" else (1..32).filter { st.mask and (1 shl (it - 1)) != 0 }.joinToString(" "))
        y = row(x, y, "Touch size seen", if (diag.touchMax > 0) String.format(Locale.US, "%.0f – %.0f px", diag.touchMin, diag.touchMax) else "touch the brake")
        y = row(x, y, "Pressure brake", if (diag.touchMax > diag.touchMin * 1.15f && diag.touchMin > 0) "supported" else "not detected yet")
        y = row(x, y, "Vibration", when { !haptics.supported -> "none"; haptics.amplitudeControl -> "variable strength"; else -> "on/off only" })

        // --- column 3: game
        x = 2 * col + pad
        y = head(x, 28 * dp, "GAME DATA")
        val fresh = tel.dashFresh(now)
        y = row(x, y, "Game", if (fresh) tel.game + if (tel.live) " (on track)" else " (menu/pause)" else "none", !fresh)
        y = row(x, y, "Speed / gear", if (fresh) "${tel.speed} km/h  /  ${tel.gear}" else "–")
        y = row(x, y, "Car steering lock", if (tel.steerLock > 0) "${tel.steerLock}°" else "not reported")
        y += 6 * dp
        y = bar(x, y, "ABS / lock", tel.vAbs, 0xFFFF8A00.toInt())
        y = bar(x, y, "Understeer", tel.vUnder, 0xFF2F8CFF.toInt())
        y = bar(x, y, "Oversteer", tel.vOver, 0xFFFF3B30.toInt())
        y = bar(x, y, "Wheelspin", tel.vSlip, 0xFFFFCC00.toInt())
        y = bar(x, y, "Kerb", tel.vKerb, 0xFFDFE5EC.toInt())
        y = bar(x, y, "Off track", tel.vOff, 0xFF8A6A3A.toInt())
        y = bar(x, y, "Road texture", tel.vRoad, 0xFF8A94A3.toInt())

        postInvalidateDelayed(100)  // 10 fps is plenty here
    }
}
