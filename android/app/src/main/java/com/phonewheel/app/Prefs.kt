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
    var matchLock = false    // set the phone range from each car's steering lock (RaceRoom, LMU)
    var lockRatio = 6f       // car wheel degrees per degree of phone rotation

    // pedals
    var pedalRelative = false
    var pedalDead = 5f       // %
    var pedalSat = 8f        // %
    var thrCurve = 1f
    var brkCurve = 1f
    var brakePressure = 0f   // 0..100 % blend of thumb contact size into the brake
    var brakeRelease = 0f    // ms; slows the brake release for smoother trail braking
    var brakeDetents = 3     // 0 = off, else number of notches felt across the brake range
    var hDetent = 0.6f       // detent click strength
    var brakeTrace = true    // draw the last brake application on the brake strip

    // buttons
    var lookToggle = true    // look left/right/back latch on tap instead of needing a hold

    // haptics (0..1 gains)
    var hapticsOn = true
    var hPreset = PRESET_ESSENTIAL
    var hOff = 0.7f
    var hMaster = 1f
    var hAbs = 0.9f
    var hKerb = 0.7f
    var hSlip = 0.5f
    var hUnder = 0.55f
    var hOver = 0.9f
    var hRoad = 0.35f
    var hImpact = 1f
    var hShift = 0.6f
    var hLimiter = 0.5f
    var hButtons = 0.6f

    // coach
    var coachMarkers = true  // tick in the thumb before your best lap's braking points
    var markerLead = 500f    // ms before the braking point
    var hMarker = 0.85f
    var coachMessages = true

    // dashboard
    var showDash = true
    var ledStart = 82f       // % of shift rpm where the first LED lights

    // visual feedback: FB_VIBRATE / FB_FLASH / FB_BOTH per signal
    var fbMarker = FB_BOTH
    var fbAbs = FB_VIBRATE
    var fbSlip = FB_VIBRATE
    var fbUnder = FB_BOTH
    var fbOver = FB_BOTH
    var flashShift = true     // screen edge blinks blue at the shift point
    var flashCoach = true     // short green/red glow with coach messages
    var flashBright = 0.8f

    // power
    var maxFps = 60f          // screen redraw cap
    var sensorHz = 200f       // steering sensor rate
    var brightness = -1f      // -1 = phone setting, else 0.05..1 while driving

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
        matchLock = sp.getBoolean("matchLock", false)
        lockRatio = sp.getFloat("lockRatio", 6f)
        pedalRelative = sp.getBoolean("pedalRelative", false)
        pedalDead = sp.getFloat("pedalDead", 5f)
        pedalSat = sp.getFloat("pedalSat", 8f)
        thrCurve = sp.getFloat("thrCurve", 1f)
        brkCurve = sp.getFloat("brkCurve", 1f)
        brakePressure = sp.getFloat("brakePressure", 0f)
        brakeRelease = sp.getFloat("brakeRelease", 0f)
        brakeDetents = sp.getInt("brakeDetents", 3)
        hDetent = sp.getFloat("hDetent", 0.6f)
        brakeTrace = sp.getBoolean("brakeTrace", true)
        lookToggle = sp.getBoolean("lookToggle", true)
        hapticsOn = sp.getBoolean("hapticsOn", sp.getBoolean("haptics", true))
        hPreset = sp.getInt("hPreset", PRESET_ESSENTIAL)
        hOff = sp.getFloat("hOff", 0.7f)
        hMaster = sp.getFloat("hMaster", 1f)
        hAbs = sp.getFloat("hAbs", 0.9f)
        hKerb = sp.getFloat("hKerb", 0.7f)
        hSlip = sp.getFloat("hSlip", 0.5f)
        hUnder = sp.getFloat("hUnder", 0.55f)
        hOver = sp.getFloat("hOver", 0.9f)
        hRoad = sp.getFloat("hRoad", 0.35f)
        hImpact = sp.getFloat("hImpact", 1f)
        hShift = sp.getFloat("hShift", 0.6f)
        hLimiter = sp.getFloat("hLimiter", 0.5f)
        hButtons = sp.getFloat("hButtons", 0.6f)
        coachMarkers = sp.getBoolean("coachMarkers", true)
        markerLead = sp.getFloat("markerLead", 500f)
        hMarker = sp.getFloat("hMarker", 0.85f)
        coachMessages = sp.getBoolean("coachMessages", true)
        showDash = sp.getBoolean("showDash", true)
        ledStart = sp.getFloat("ledStart", 82f)
        fbMarker = sp.getInt("fbMarker", FB_BOTH)
        fbAbs = sp.getInt("fbAbs", FB_VIBRATE)
        fbSlip = sp.getInt("fbSlip", FB_VIBRATE)
        fbUnder = sp.getInt("fbUnder", FB_BOTH)
        fbOver = sp.getInt("fbOver", FB_BOTH)
        flashShift = sp.getBoolean("flashShift", true)
        flashCoach = sp.getBoolean("flashCoach", true)
        flashBright = sp.getFloat("flashBright", 0.8f)
        showNums = sp.getBoolean("showNums", false)
        maxFps = sp.getFloat("maxFps", 60f)
        sensorHz = sp.getFloat("sensorHz", 200f)
        brightness = sp.getFloat("brightness", -1f)
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
            .putBoolean("matchLock", matchLock)
            .putFloat("lockRatio", lockRatio)
            .putBoolean("pedalRelative", pedalRelative)
            .putFloat("pedalDead", pedalDead)
            .putFloat("pedalSat", pedalSat)
            .putFloat("thrCurve", thrCurve)
            .putFloat("brkCurve", brkCurve)
            .putFloat("brakePressure", brakePressure)
            .putFloat("brakeRelease", brakeRelease)
            .putInt("brakeDetents", brakeDetents)
            .putFloat("hDetent", hDetent)
            .putBoolean("brakeTrace", brakeTrace)
            .putBoolean("lookToggle", lookToggle)
            .putBoolean("hapticsOn", hapticsOn)
            .putInt("hPreset", hPreset)
            .putFloat("hOff", hOff)
            .putFloat("hMaster", hMaster)
            .putFloat("hAbs", hAbs)
            .putFloat("hKerb", hKerb)
            .putFloat("hSlip", hSlip)
            .putFloat("hUnder", hUnder)
            .putFloat("hOver", hOver)
            .putFloat("hRoad", hRoad)
            .putFloat("hImpact", hImpact)
            .putFloat("hShift", hShift)
            .putFloat("hLimiter", hLimiter)
            .putFloat("hButtons", hButtons)
            .putBoolean("coachMarkers", coachMarkers)
            .putFloat("markerLead", markerLead)
            .putFloat("hMarker", hMarker)
            .putBoolean("coachMessages", coachMessages)
            .putBoolean("showDash", showDash)
            .putFloat("ledStart", ledStart)
            .putInt("fbMarker", fbMarker)
            .putInt("fbAbs", fbAbs)
            .putInt("fbSlip", fbSlip)
            .putInt("fbUnder", fbUnder)
            .putInt("fbOver", fbOver)
            .putBoolean("flashShift", flashShift)
            .putBoolean("flashCoach", flashCoach)
            .putFloat("flashBright", flashBright)
            .putBoolean("showNums", showNums)
            .putFloat("maxFps", maxFps)
            .putFloat("sensorHz", sensorHz)
            .putFloat("brightness", brightness)
            .apply()
    }

    fun coachCfgLine() = "cfg,${if (coachMarkers) 1 else 0},${markerLead.toInt()},${if (coachMessages) 1 else 0}"

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
        const val FB_VIBRATE = 0
        const val FB_FLASH = 1
        const val FB_BOTH = 2
        fun vibrates(mode: Int) = mode != FB_FLASH
        fun flashes(mode: Int) = mode != FB_VIBRATE
    }
}
