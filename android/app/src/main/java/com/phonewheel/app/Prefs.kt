package com.phonewheel.app

import android.content.Context

/**
 * All user settings. Connection + device role are global; everything else (tuning, feedback,
 * layout) belongs to the active *profile*, and profiles can switch automatically per game / car.
 */
class Prefs(private val ctx: Context) {
    private val global = ctx.getSharedPreferences("phonewheel_global", Context.MODE_PRIVATE)
    var profileFile: String = global.getString("activeFile", DEFAULT_FILE) ?: DEFAULT_FILE
        private set
    private var sp = ctx.getSharedPreferences(profileFile, Context.MODE_PRIVATE)

    // device role (global)
    var role = ROLE_WHEEL

    // layout (per profile, JSON; empty = standard layout)
    var layoutJson = ""

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
    var hImpact = 1f
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
        // connection: global (falls back to the old per-file values from earlier versions)
        mode = global.getInt("mode", sp.getInt("mode", MODE_AUTO))
        manualIp = global.getString("manualIp", null) ?: (sp.getString("manualIp", "") ?: "")
        role = global.getInt("role", ROLE_WHEEL)
        layoutJson = sp.getString("layout", "") ?: ""
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
        hImpact = sp.getFloat("hImpact", 1f)
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
        global.edit().putInt("mode", mode).putString("manualIp", manualIp).putInt("role", role).apply()
        sp.edit()
            .putString("layout", layoutJson)
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
            .putFloat("hImpact", hImpact)
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

    /** Restores default tuning in this profile (keeps the layout and connection settings). */
    fun resetTuning() {
        val lay = layoutJson
        sp.edit().clear().apply()
        load()
        layoutJson = lay
        save()
    }

    // ------------------------------------------------------------------ profiles
    data class Profile(val file: String, var name: String, var game: String, var car: String)

    fun profiles(): MutableList<Profile> {
        val out = ArrayList<Profile>()
        try {
            val a = org.json.JSONArray(global.getString("profiles", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Profile(o.getString("file"), o.optString("name", "Profile"), o.optString("game", ""), o.optString("car", "")))
            }
        } catch (_: Exception) {}
        if (out.none { it.file == DEFAULT_FILE }) out.add(0, Profile(DEFAULT_FILE, "Default", "", ""))
        return out
    }

    private fun storeProfiles(list: List<Profile>) {
        val a = org.json.JSONArray()
        for (p in list) a.put(org.json.JSONObject().put("file", p.file).put("name", p.name).put("game", p.game).put("car", p.car))
        global.edit().putString("profiles", a.toString()).apply()
    }

    fun activeProfile(): Profile = profiles().firstOrNull { it.file == profileFile } ?: Profile(DEFAULT_FILE, "Default", "", "")

    fun switchTo(file: String) {
        if (file == profileFile) return
        save()
        profileFile = file
        global.edit().putString("activeFile", file).apply()
        sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
        load()
    }

    /** New profile starting as a copy of the current one; becomes active. */
    fun createProfile(name: String): Profile {
        save()
        val file = "pw_profile_" + System.currentTimeMillis()
        val dst = ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
        for ((k, v) in sp.all) when (v) {
            is Int -> dst.putInt(k, v); is Float -> dst.putFloat(k, v); is Boolean -> dst.putBoolean(k, v)
            is String -> dst.putString(k, v); is Long -> dst.putLong(k, v)
        }
        dst.commit()
        val p = Profile(file, name, "", "")
        storeProfiles(profiles() + p)
        switchTo(file)
        return p
    }

    fun updateProfile(p: Profile) {
        storeProfiles(profiles().map { if (it.file == p.file) p else it })
    }

    fun deleteProfile(file: String) {
        if (file == DEFAULT_FILE) return
        storeProfiles(profiles().filter { it.file != file })
        if (profileFile == file) switchTo(DEFAULT_FILE)
        ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Best profile for this game/car: one linked to the car, else the game, else Default. */
    fun profileFor(game: String, car: String): Profile? {
        val list = profiles()
        return list.firstOrNull { it.car.isNotEmpty() && it.car == car }
            ?: list.firstOrNull { it.car.isEmpty() && it.game.isNotEmpty() && it.game == game }
    }

    companion object {
        const val MODE_AUTO = 0    // WiFi, find the PC automatically
        const val MODE_USB = 1     // USB cable via adb reverse
        const val MODE_MANUAL = 2  // WiFi, typed PC address
        const val PORT = 8767
        const val DEFAULT_FILE = "phonewheel"
        const val ROLE_WHEEL = 0
        const val ROLE_BOX = 1      // second device: buttons + dashboard only
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
