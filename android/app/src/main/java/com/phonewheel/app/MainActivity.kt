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
import android.os.VibrationEffect
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
    private val st = InputState()

    private lateinit var sensors: SensorManager
    private var tiltSensor: Sensor? = null
    private var vibrator: Vibrator? = null

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

        wheel = WheelView(this, prefs, st)
        setContentView(wheel)

        link = Link(st) { s -> runOnUiThread { onLinkStatus(s) } }
        wheel.onInput = { link.kick() }
        wheel.onCenter = { centerSteering() }
        wheel.onSettings = { openSettings() }
        wheel.onHaptic = { ms -> buzz(ms) }
        wheel.onSteerModeChanged = { updateSteer() }

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // gravity is the gyro-fused, low-noise version of the accelerometer
        tiltSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        if (tiltSensor == null) toast("This phone has no motion sensor. Drag the steering bar to steer.")
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        tiltSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        centerDeg = null // re-center on the first reading
        link.start(prefs.mode, prefs.manualIp)
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        wheel.releaseAll()
        st.zero()
        link.stop() // sends "release everything" to the PC
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
        val x = e.values[0]
        val y = e.values[1]
        // gravity vector in screen coordinates
        val a = Math.toRadians(rotation * 90.0)
        val sx = x * cos(a) - y * sin(a)
        val sy = x * sin(a) + y * cos(a)
        if (hypot(sx, sy) < 1.5) return // phone lying flat: no usable roll angle
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
                val half = prefs.range / 2f
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
        SettingsDialog.show(this, prefs) {
            if (prefs.mode != oldMode || prefs.manualIp != oldIp) link.start(prefs.mode, prefs.manualIp)
            wheel.invalidate()
            hideSystemUi()
        }
    }

    private fun buzz(ms: Long) {
        if (!prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
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
