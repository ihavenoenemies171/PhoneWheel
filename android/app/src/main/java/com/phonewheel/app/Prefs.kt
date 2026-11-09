package com.phonewheel.app

import android.content.Context

/** All user settings, stored in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phonewheel", Context.MODE_PRIVATE)

    // connection
    var mode = MODE_AUTO
    var manualIp = ""

    // steering
    var range = 90f        // phone rotation lock-to-lock, degrees
    var deadzone = 0.5f    // degrees
    var smoothing = 0.2f   // 0..0.9
    var linearity = 1f
    var invert = false

    // pedals
    var pedalRelative = false
    var pedalDead = 5f     // %
    var pedalSat = 8f      // %
    var thrCurve = 1f
    var brkCurve = 1f

    // misc
    var haptics = true
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
        pedalRelative = sp.getBoolean("pedalRelative", false)
        pedalDead = sp.getFloat("pedalDead", 5f)
        pedalSat = sp.getFloat("pedalSat", 8f)
        thrCurve = sp.getFloat("thrCurve", 1f)
        brkCurve = sp.getFloat("brkCurve", 1f)
        haptics = sp.getBoolean("haptics", true)
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
            .putBoolean("pedalRelative", pedalRelative)
            .putFloat("pedalDead", pedalDead)
            .putFloat("pedalSat", pedalSat)
            .putFloat("thrCurve", thrCurve)
            .putFloat("brkCurve", brkCurve)
            .putBoolean("haptics", haptics)
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
    }
}
