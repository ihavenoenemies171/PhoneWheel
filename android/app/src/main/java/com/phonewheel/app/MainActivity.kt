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
    private val diag = Diag()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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

        wheel = WheelView(this, prefs, st, tel, diag)
        setContentView(wheel)

        link = Link(st, tel, diag) { s -> runOnUiThread { onLinkStatus(s) } }
        wheel.onInput = { link.kick() }
        tel.onUpdate = { wheel.requestFrame() }
        wheel.onCenter = { centerSteering() }
        wheel.onSettings = { openSettings() }
        wheel.onHaptic = { ms -> buzz(ms) }
        wheel.onDetent = { haptics.detent() }
        wheel.tester = { haptics.playing() }
        wheel.onSteerModeChanged = { updateSteer() }
        wheel.onEditTile = { page, item, isNew -> editTile(page, item, isNew) }
        wheel.onEditOptions = {
            LayoutDialogs.options(this, wheel.layout, wheel.page, prefs.showDash,
                onChange = { pi, dash -> prefs.showDash = dash; wheel.setPage(pi); wheel.layoutChanged() },
                onReset = { prefs.layoutJson = ""; prefs.save(); wheel.reloadLayout() })
        }
        wheel.onEditDone = { hideSystemUi(); toast("Layout saved in profile '${prefs.activeProfile().name}'") }
        st.role = prefs.role

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
        diag.sensorName = when (tiltSensor?.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "gyro-fused rotation"
            Sensor.TYPE_GRAVITY -> "gravity"
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            else -> "none"
        }
    }

    private fun registerSensor() {
        sensors.unregisterListener(this)
        if (prefs.role == Prefs.ROLE_BOX) return   // button box: no steering, no sensor battery use
        // 200 Hz by default (5 ms): plenty for steering; FASTEST (400+ Hz) mostly burned battery
        val periodUs = (1_000_000f / prefs.sensorHz.coerceIn(50f, 400f)).toInt()
        tiltSensor?.let { sensors.registerListener(this, it, periodUs) }
        centerDeg = null // re-center on the first reading
        lastTs = 0L
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        applyBrightness()
        registerSensor()
        try { wifiLock?.acquire() } catch (_: Exception) {}
        st.brakeReleaseMs = prefs.brakeRelease
        link.cfgLine = prefs.coachCfgLine()
        link.start(prefs.mode, prefs.manualIp)
        haptics.start()
        handler.post(powerCheck)
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        handler.removeCallbacks(powerCheck)
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
        diag.sensorEvents++
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
        diag.rawSteerDeg = centerDeg?.let { wrap180(deg - it) } ?: 0f
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
                // optional: phone range follows the car's real steering lock (constant wheel°/phone° ratio)
                val baseRange = if (prefs.matchLock && tel.steerLock > 0)
                    (tel.steerLock / prefs.lockRatio).coerceIn(45f, 200f) else prefs.range
                val half = baseRange / 2f * (1f + prefs.speedSens * speedK)
                var m = abs(smoothDeg)
                m = if (m <= prefs.deadzone) 0f else (m - prefs.deadzone) / max(1f, half - prefs.deadzone)
                m = m.coerceIn(0f, 1f).pow(prefs.linearity)
                sign(smoothDeg) * m
            }
        }
        link.kick()
        // only redraw when the visible steering value moved (sensor runs much faster than the screen)
        val shown = (st.steer * 250f).toInt()
        if (shown != lastShownSteer) {
            lastShownSteer = shown
            wheel.requestFrame()
        }
    }

    private var lastShownSteer = Int.MIN_VALUE

    // ---- battery / heat warnings (checked every 30 s while driving)
    private var warnedLevel = 101
    private var lastHeatWarn = 0L
    private val powerCheck = object : Runnable {
        override fun run() {
            val pw = Power.read(this@MainActivity)
            if (!pw.charging && pw.level in 0..15) {
                val step = when { pw.level <= 5 -> 5; pw.level <= 10 -> 10; else -> 15 }
                if (step < warnedLevel) {
                    warnedLevel = step
                    toast("Phone battery ${pw.level}% – plug in a charger (USB tethering charges while you drive)")
                }
            } else if (pw.charging || pw.level > 20) warnedLevel = 101
            val now = SystemClock.elapsedRealtime()
            if ((pw.thermal >= 2 || pw.tempC >= 43f) && now - lastHeatWarn > 5 * 60_000) {
                lastHeatWarn = now
                toast("Phone is getting hot (${pw.tempC.toInt()}°C). Lower screen brightness or frame rate in SETUP → Battery")
            }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness = if (prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                              else prefs.brightness.coerceIn(0.05f, 1f)
        window.attributes = lp
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
        autoProfile()
        if (s.connected && s.pcButtons in 1..31 && !lastButtonWarn) {
            lastButtonWarn = true
            toast("vJoy on the PC has only ${s.pcButtons} buttons. Set 32 in 'Configure vJoy'.")
        }
    }

    private fun openSettings() {
        val oldMode = prefs.mode
        val oldIp = prefs.manualIp
        val oldFused = prefs.fusedSensor
        val oldHz = prefs.sensorHz
        val oldRole = prefs.role
        SettingsDialog.show(this, prefs, SettingsDialog.Actions(
            testVibration = {
                EffectTester.show(this, prefs, haptics, redraw = { wheel.requestFrame() }) { hideSystemUi() }
                wheel.requestFrame()
            },
            bind = { axis ->
                st.bindAxis = axis
                st.bindStartMs = SystemClock.elapsedRealtime()
                link.kick()
            },
            openReport = { openReport() },
            openLaps = { openReport("laps") },
            openDiag = { DiagScreen.show(this, prefs, st, tel, diag, link, haptics) },
            editLayout = { wheel.startEditing() },
            profileChanged = { applyProfile() },
            currentGame = if (tel.dashFresh(SystemClock.elapsedRealtime())) tel.game else "",
            currentCar = if (tel.dashFresh(SystemClock.elapsedRealtime())) tel.car else "",
            vibrationInfo = when {
                !haptics.supported -> "This phone has no vibration motor."
                !haptics.amplitudeControl -> "This phone's motor can only switch on/off, so effects are simpler."
                else -> "This phone supports variable vibration strength."
            },
        )) {
            if (prefs.mode != oldMode || prefs.manualIp != oldIp) link.start(prefs.mode, prefs.manualIp)
            if (prefs.fusedSensor != oldFused || prefs.sensorHz != oldHz || prefs.role != oldRole) { pickSensor(); registerSensor() }
            st.role = prefs.role
            applyBrightness()
            st.brakeReleaseMs = prefs.brakeRelease
            link.cfgLine = prefs.coachCfgLine()
            wheel.relayout()
            hideSystemUi()
        }
    }

    private fun buzz(ms: Long) = haptics.pulse(ms)

    // ---- layout editor
    private fun editTile(page: LPage, item: LItem, isNew: Boolean) {
        LayoutDialogs.editItem(this, wheel.layout, page, item, isNew,
            onSave = { e ->
                if (isNew) {
                    if (wheel.layout.fits(page, e.row, e.col, e.rs, e.cs)) page.items.add(e)
                } else {
                    item.type = e.type; item.num = e.num; item.label = e.label; item.sub = e.sub
                    item.color = e.color; item.rs = e.rs; item.cs = e.cs
                }
                wheel.layoutChanged()
                hideSystemUi()
            },
            onDelete = { page.items.remove(item); wheel.layoutChanged(); hideSystemUi() })
    }

    // ---- profiles
    /** Re-apply everything after the active profile changed. */
    private fun applyProfile() {
        st.brakeReleaseMs = prefs.brakeRelease
        st.role = prefs.role
        link.cfgLine = prefs.coachCfgLine()
        pickSensor(); registerSensor()
        applyBrightness()
        wheel.reloadLayout()
        hideSystemUi()
        toast("Profile: ${prefs.activeProfile().name}")
    }

    private var autoKey = ""

    /** Switch to the profile linked to the detected car or game (checked a few times a second). */
    private fun autoProfile() {
        if (!tel.dashFresh(SystemClock.elapsedRealtime()) || tel.game.isEmpty()) return
        val key = tel.game + "|" + tel.car
        if (key == autoKey) return
        autoKey = key
        val want = prefs.profileFor(tel.game, tel.car) ?: return
        if (want.file != prefs.profileFile && !wheel.editing) {
            prefs.switchTo(want.file)
            applyProfile()
        }
    }

    private fun openReport(page: String = "report") {
        val host = link.pcHost
        if (host == null) {
            toast("Connect to the PC first")
            return
        }
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("http://$host:8765/$page")))
        } catch (_: Exception) {
            toast("Open http://$host:8765/$page in a browser")
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
