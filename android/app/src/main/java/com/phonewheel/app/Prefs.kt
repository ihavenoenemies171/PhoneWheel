package com.phonewheel.app

import android.content.Context

/** All user settings, stored in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phonewheel", Context.MODE_PRIVATE)

    // connection
    var mode = MODE_AUTO
    var manualIp = ""

    // steering
    var range = 90f          // phone rotation lock-to-lock, degrees
    var deadzone = 0.5f      // degrees
    var smoothing = 0.2f     // 0..0.9
    var linearity = 1f
    var invert = false
    var fusedSensor = true   // gyro-fused rotation sensor instead of plain gravity
    var speedSens = 0f       // 0..1: extra steering range at high speed

    // pedals
    var pedalRelative = false
    var pedalDead = 5f       // %
    var pedalSat = 8f        // %
    var thrCurve = 1f
    var brkCurve = 1f
    var brakePressure = 0f   // 0..100 % blend of thumb contact size into the brake
    var brakeRelease = 0f    // ms; slows the brake release for smoother trail braking

    // haptics (0..1 gains)
    var hapticsOn = true
    var hPreset = PRESET_ESSENTIAL
    var hOff = 0.7f
    var hMaster = 1f
    var hAbs = 0.9f
    var hKerb = 0.7f
    var hSlip = 0.5f
    var hRoad = 0.35f
    var hImpact = 1f
    var hShift = 0.6f
    var hLimiter = 0.5f
    var hButtons = 0.6f

    // dashboard
    var showDash = true
    var ledStart = 82f       // % of shift rpm where the first LED lights

    // misc
    var showNums = false

    init { load() }

    fun load() {
        mode = sp.getInt("mode", MODE_AUTO)
        manualIp = sp.getString("manualIp", "") ?: ""
        range = sp.getFloat("range", 90f)
        deadzone = sp.getFloat("deadzone", 0.5f)
        smoothing = sp.getFloat("smoothing", 0.2f)
        linearity = sp.getFloat("linearity", 1f)
        invert = sp.getBoolean("invert", false)
        fusedSensor = sp.getBoolean("fusedSensor", true)
        speedSens = sp.getFloat("speedSens", 0f)
        pedalRelative = sp.getBoolean("pedalRelative", false)
        pedalDead = sp.getFloat("pedalDead", 5f)
        pedalSat = sp.getFloat("pedalSat", 8f)
        thrCurve = sp.getFloat("thrCurve", 1f)
        brkCurve = sp.getFloat("brkCurve", 1f)
        brakePressure = sp.getFloat("brakePressure", 0f)
        brakeRelease = sp.getFloat("brakeRelease", 0f)
        hapticsOn = sp.getBoolean("hapticsOn", sp.getBoolean("haptics", true))
        hPreset = sp.getInt("hPreset", PRESET_ESSENTIAL)
        hOff = sp.getFloat("hOff", 0.7f)
        hMaster = sp.getFloat("hMaster", 1f)
        hAbs = sp.getFloat("hAbs", 0.9f)
        hKerb = sp.getFloat("hKerb", 0.7f)
        hSlip = sp.getFloat("hSlip", 0.5f)
        hRoad = sp.getFloat("hRoad", 0.35f)
        hImpact = sp.getFloat("hImpact", 1f)
        hShift = sp.getFloat("hShift", 0.6f)
        hLimiter = sp.getFloat("hLimiter", 0.5f)
        hButtons = sp.getFloat("hButtons", 0.6f)
        showDash = sp.getBoolean("showDash", true)
        ledStart = sp.getFloat("ledStart", 82f)
        showNums = sp.getBoolean("showNums", false)
    }

    fun save() {
        sp.edit()
            .putInt("mode", mode)
            .putString("manualIp", manualIp)
            .putFloat("range", range)
            .putFloat("deadzone", deadzone)
            .putFloat("smoothing", smoothing)
            .putFloat("linearity", linearity)
            .putBoolean("invert", invert)
            .putBoolean("fusedSensor", fusedSensor)
            .putFloat("speedSens", speedSens)
            .putBoolean("pedalRelative", pedalRelative)
            .putFloat("pedalDead", pedalDead)
            .putFloat("pedalSat", pedalSat)
            .putFloat("thrCurve", thrCurve)
            .putFloat("brkCurve", brkCurve)
            .putFloat("brakePressure", brakePressure)
            .putFloat("brakeRelease", brakeRelease)
            .putBoolean("hapticsOn", hapticsOn)
            .putInt("hPreset", hPreset)
            .putFloat("hOff", hOff)
            .putFloat("hMaster", hMaster)
            .putFloat("hAbs", hAbs)
            .putFloat("hKerb", hKerb)
            .putFloat("hSlip", hSlip)
            .putFloat("hRoad", hRoad)
            .putFloat("hImpact", hImpact)
            .putFloat("hShift", hShift)
            .putFloat("hLimiter", hLimiter)
            .putFloat("hButtons", hButtons)
            .putBoolean("showDash", showDash)
            .putFloat("ledStart", ledStart)
            .putBoolean("showNums", showNums)
            .apply()
    }

    /** Restores defaults but keeps the connection settings. */
    fun resetTuning() {
        val m = mode
        val ip = manualIp
        sp.edit().clear().apply()
        load()
        mode = m
        manualIp = ip
        save()
    }

    companion object {
        const val MODE_AUTO = 0    // WiFi, find the PC automatically
        const val MODE_USB = 1     // USB cable via adb reverse
        const val MODE_MANUAL = 2  // WiFi, typed PC address
        const val PORT = 8767
        const val PRESET_ESSENTIAL = 0  // only actionable feedback, one effect at a time
        const val PRESET_IMMERSIVE = 1  // everything, layered
        const val PRESET_OFF = 2        // no game effects (button clicks still work)
    }
}
