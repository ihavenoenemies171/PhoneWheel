package com.phonewheel.app

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var wheel: WheelView
    private lateinit var link: Link
    private lateinit var haptics: Haptics
    private val st = InputState()
    private val tel = Telem()
    private val rotMatrix = FloatArray(9)

    private lateinit var sensors: SensorManager
    private var tiltSensor: Sensor? = null
    private var vibrator: Vibrator? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // steering state
    private var rawDeg: Float? = null
    private var centerDeg: Float? = null
    private var smoothDeg = 0f
    private var lastTs = 0L
    private var rotation = 0
    private var rotationCheckedAt = 0L
    private var lastButtonWarn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        wheel = WheelView(this, prefs, st, tel)
        setContentView(wheel)

        link = Link(st, tel) { s -> runOnUiThread { onLinkStatus(s) } }
        wheel.onInput = { link.kick() }
        wheel.onCenter = { centerSteering() }
        wheel.onSettings = { openSettings() }
        wheel.onHaptic = { ms -> buzz(ms) }
        wheel.onDetent = { haptics.detent() }
        wheel.onSteerModeChanged = { updateSteer() }

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pickSensor()
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        haptics = Haptics(vibrator, prefs, tel, st)

        // Stop Android's WiFi power saving while the wheel is open: this is the main cause of
        // high and jumpy ping. LOW_LATENCY (Android 10+) also prioritises this app's traffic.
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "PhoneWheel:lowlatency").apply { setReferenceCounted(false) }
        } catch (_: Exception) {}

        if (tiltSensor == null) toast("This phone has no motion sensor. Drag the steering bar to steer.")
    }

    /** Fused = gyroscope + accelerometer (smoother, faster, ignores bumps); falls back to gravity. */
    private fun pickSensor() {
        val fused = if (prefs.fusedSensor) sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) else null
        tiltSensor = fused
            ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun registerSensor() {
        sensors.unregisterListener(this)
        tiltSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        centerDeg = null // re-center on the first reading
        lastTs = 0L
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        registerSensor()
        try { wifiLock?.acquire() } catch (_: Exception) {}
        st.brakeReleaseMs = prefs.brakeRelease
        link.cfgLine = prefs.coachCfgLine()
        link.start(prefs.mode, prefs.manualIp)
        haptics.start()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        haptics.stop()
        wheel.releaseAll()
        st.zero()
        st.bindAxis = 0
        link.stop() // sends "release everything" to the PC
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ------------------------------------------------------------------ steering
    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - rotationCheckedAt > 300) {
            rotationCheckedAt = now
            rotation = currentRotation()
        }
        val x: Float
        val y: Float
        val minLen: Double
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            // "up" direction in phone coordinates = 3rd row of the rotation matrix
            SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
            x = rotMatrix[6]
            y = rotMatrix[7]
            minLen = 0.15
        } else {
            x = e.values[0]
            y = e.values[1]
            minLen = 1.5
        }
        // gravity vector in screen coordinates
        val a = Math.toRadians(rotation * 90.0)
        val sx = x * cos(a) - y * sin(a)
        val sy = x * sin(a) + y * cos(a)
        if (hypot(sx, sy) < minLen) return // phone lying flat: no usable roll angle
        val deg = (-Math.toDegrees(atan2(sx, sy))).toFloat() // clockwise = positive = right

        val dt = if (lastTs == 0L) 0.016f else ((e.timestamp - lastTs) / 1e9f).coerceIn(0f, 0.1f)
        lastTs = e.timestamp
        rawDeg = deg
        if (centerDeg == null) {
            centerDeg = deg
            smoothDeg = 0f
        }
        var d = wrap180(deg - centerDeg!!)
        if (prefs.invert) d = -d
        // time-based smoothing so it behaves the same at 50 Hz or 400 Hz sensors
        val tau = prefs.smoothing * 0.08f
        val k = if (tau <= 0f) 1f else (1f - exp(-dt / tau))
        smoothDeg += (d - smoothDeg) * k
        updateSteer()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateSteer() {
        val manual = wheel.manualSteer
        st.steer = when {
            wheel.locked -> 0f
            manual != null -> manual
            rawDeg == null -> 0f
            else -> {
                // speed-sensitive: more phone rotation needed at high speed = calmer steering
                val now = SystemClock.elapsedRealtime()
                val speedK = if (tel.dashFresh(now)) (tel.speed / 250f).coerceIn(0f, 1f) else 0f
                val half = prefs.range / 2f * (1f + prefs.speedSens * speedK)
                var m = abs(smoothDeg)
                m = if (m <= prefs.deadzone) 0f else (m - prefs.deadzone) / max(1f, half - prefs.deadzone)
                m = m.coerceIn(0f, 1f).pow(prefs.linearity)
                sign(smoothDeg) * m
            }
        }
        link.kick()
        wheel.postInvalidateOnAnimation()
    }

    private fun centerSteering() {
        rawDeg?.let { centerDeg = it }
        smoothDeg = 0f
        updateSteer()
        toast("Steering centered")
    }

    private fun wrap180(d: Float): Float {
        var v = (d + 540f) % 360f
        if (v < 0) v += 360f
        return v - 180f
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation

    // ------------------------------------------------------------------ status / settings
    private fun onLinkStatus(s: LinkStatus) {
        wheel.status = s
        if (s.connected && s.pcButtons in 1..31 && !lastButtonWarn) {
            lastButtonWarn = true
            toast("vJoy on the PC has only ${s.pcButtons} buttons. Set 32 in 'Configure vJoy'.")
        }
    }

    private fun openSettings() {
        val oldMode = prefs.mode
        val oldIp = prefs.manualIp
        val oldFused = prefs.fusedSensor
        SettingsDialog.show(this, prefs, SettingsDialog.Actions(
            testVibration = { haptics.test() },
            bind = { axis ->
                st.bindAxis = axis
                st.bindStartMs = SystemClock.elapsedRealtime()
                link.kick()
            },
            openReport = { openReport() },
            vibrationInfo = when {
                !haptics.supported -> "This phone has no vibration motor."
                !haptics.amplitudeControl -> "This phone's motor can only switch on/off, so effects are simpler."
                else -> "This phone supports variable vibration strength."
            },
        )) {
            if (prefs.mode != oldMode || prefs.manualIp != oldIp) link.start(prefs.mode, prefs.manualIp)
            if (prefs.fusedSensor != oldFused) { pickSensor(); registerSensor() }
            st.brakeReleaseMs = prefs.brakeRelease
            link.cfgLine = prefs.coachCfgLine()
            wheel.relayout()
            hideSystemUi()
        }
    }

    private fun buzz(ms: Long) = haptics.pulse(ms)

    private fun openReport() {
        val host = link.pcHost
        if (host == null) {
            toast("Connect to the PC first")
            return
        }
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("http://$host:8765/report")))
        } catch (_: Exception) {
            toast("Open http://$host:8765/report in a browser")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}
