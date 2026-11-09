package com.phonewheel.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Settings screen, built in code (no layout XML / libraries needed). */
object SettingsDialog {

    class Actions(
        val testVibration: () -> Unit,
        val bind: (axis: Int) -> Unit,
        val vibrationInfo: String,
        val openReport: () -> Unit,
        val openLaps: () -> Unit,
        val openDiag: () -> Unit,
        val editLayout: () -> Unit,
        val profileChanged: () -> Unit,
        val currentGame: String,
        val currentCar: String,
    )

    fun show(act: Activity, p: Prefs, actions: Actions, onDone: () -> Unit) {
        val dp = act.resources.displayMetrics.density
        var dialog: AlertDialog? = null
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }

        fun header(t: String) {
            root.addView(TextView(act).apply {
                text = t
                setTextColor(0xFFFFCC00.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }

        fun hint(t: String) {
            root.addView(TextView(act).apply {
                text = t
                setTextColor(0xFF8A94A3.toInt())
                textSize = 12f
            })
        }

        fun slider(label: String, sub: String, lo: Float, hi: Float, step: Float, value: Float,
                   fmt: (Float) -> String, set: (Float) -> Unit) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
            val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(act).apply {
                text = label; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val out = TextView(act).apply { text = fmt(value); setTextColor(0xFFFFCC00.toInt()); textSize = 15f }
            top.addView(name); top.addView(out)
            row.addView(top)
            if (sub.isNotEmpty()) row.addView(TextView(act).apply { text = sub; setTextColor(0xFF8A94A3.toInt()); textSize = 12f })
            val steps = ((hi - lo) / step).roundToInt()
            row.addView(SeekBar(act).apply {
                max = steps
                progress = ((value - lo) / step).roundToInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        val v = lo + prog * step
                        out.text = fmt(v)
                        set(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            root.addView(row)
        }

        fun pct(label: String, sub: String, value: Float, set: (Float) -> Unit) =
            slider(label, sub, 0f, 1f, 0.05f, value, { "${(it * 100).roundToInt()}%" }, set)

        fun check(label: String, sub: String, value: Boolean, set: (Boolean) -> Unit) {
            root.addView(CheckBox(act).apply {
                text = if (sub.isEmpty()) label else "$label\n$sub"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = value
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnCheckedChangeListener { _, c -> set(c) }
            })
        }

        fun buttonRow(vararg items: Pair<String, () -> Unit>) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
            for ((label, action) in items) {
                row.addView(Button(act).apply {
                    text = label
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { action() }
                })
            }
            root.addView(row)
        }

        fun f1(v: Float) = String.format(Locale.US, "%.1f", v)
        fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

        // ---------------------------------------------------------- profile
        val prof = p.activeProfile()
        header("PROFILE")
        val linkText = when {
            prof.car.isNotEmpty() -> "used automatically for the car ${prof.car}"
            prof.game.isNotEmpty() -> "used automatically in ${prof.game}"
            else -> "not linked to a game or car"
        }
        root.addView(TextView(act).apply {
            text = "${prof.name}  ·  $linkText"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        hint("A profile holds all settings below plus your button layout. Link one to a game or car and the " +
             "app switches to it automatically when that game/car is detected.")
        fun ask(title: String, initial: String, done: (String) -> Unit) {
            val box = EditText(act).apply { setText(initial); setTextColor(Color.WHITE); isSingleLine = true }
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box)
                .setPositiveButton("OK") { _, _ -> val t = box.text.toString().trim(); if (t.isNotEmpty()) done(t) }
                .setNegativeButton("Cancel", null).show()
        }
        buttonRow(
            "Switch…" to {
                val list = p.profiles()
                AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Switch profile")
                    .setItems(list.map { it.name + (if (it.file == p.profileFile) "  ✓" else "") }.toTypedArray()) { _, i ->
                        p.switchTo(list[i].file); dialog?.dismiss(); actions.profileChanged()
                    }.show()
            },
            "New…" to { ask("New profile (copy of this one)", "") { n -> p.createProfile(n); dialog?.dismiss(); actions.profileChanged() } },
            "Rename…" to { ask("Rename profile", prof.name) { n -> p.updateProfile(prof.copy(name = n)); dialog?.dismiss(); actions.profileChanged() } },
        )
        buttonRow(
            "Link to game / car…" to {
                val opts = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
                opts.add("Not linked"); acts.add { p.updateProfile(prof.copy(game = "", car = "")) }
                if (actions.currentGame.isNotEmpty()) { opts.add("This game: ${actions.currentGame}"); acts.add { p.updateProfile(prof.copy(game = actions.currentGame, car = "")) } }
                if (actions.currentCar.isNotEmpty()) { opts.add("This car: ${actions.currentCar}"); acts.add { p.updateProfile(prof.copy(game = actions.currentGame, car = actions.currentCar)) } }
                AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(if (opts.size == 1) "Start a game to link it" else "Use '${prof.name}' automatically for…")
                    .setItems(opts.toTypedArray()) { _, i -> acts[i](); dialog?.dismiss(); actions.profileChanged() }.show()
            },
            "Delete" to {
                if (prof.file == Prefs.DEFAULT_FILE) android.widget.Toast.makeText(act, "The Default profile can't be deleted", android.widget.Toast.LENGTH_SHORT).show()
                else AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Delete '${prof.name}'?")
                    .setPositiveButton("Delete") { _, _ -> p.deleteProfile(prof.file); dialog?.dismiss(); actions.profileChanged() }
                    .setNegativeButton("Cancel", null).show()
            },
        )

        // ---------------------------------------------------------- layout & role
        header("LAYOUT")
        buttonRow("Edit button layout" to { dialog?.dismiss(); actions.editLayout() })
        hint("Move, resize, recolour and rename buttons, add pages, add analog CLUTCH / HANDBRAKE levers, " +
             "change pedal and paddle widths. Saved in this profile.")
        val roleGroup = RadioGroup(act)
        val roleIds = IntArray(2)
        listOf("This phone is the wheel", "Second device: button box + dashboard (no steering/pedals)").forEachIndexed { i, l ->
            val rb = RadioButton(act).apply { id = View.generateViewId(); text = l; setTextColor(Color.WHITE); textSize = 14f }
            roleIds[i] = rb.id; roleGroup.addView(rb)
        }
        roleGroup.check(roleIds[p.role.coerceIn(0, 1)])
        roleGroup.setOnCheckedChangeListener { _, c -> p.role = roleIds.indexOf(c).coerceAtLeast(0) }
        root.addView(roleGroup)
        hint("Use an old phone or tablet as an extra button box / big dashboard next to your screen. Its buttons are " +
             "combined with the wheel's; both get vibration and dashboard data.")

        // ---------------------------------------------------------- connection
        header("CONNECTION")
        val ipBox = EditText(act).apply {
            hint = "PC address, e.g. 192.168.1.20"
            setText(p.manualIp)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5A6370.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val rg = RadioGroup(act)
        val labels = listOf(
            "Automatic – WiFi, phone hotspot or USB tethering (finds the PC)",
            "USB cable (run usb_android.bat on the PC)",
            "WiFi – type PC address",
        )
        val ids = IntArray(3)
        labels.forEachIndexed { i, l ->
            val rb = RadioButton(act).apply {
                id = View.generateViewId()
                text = l
                setTextColor(Color.WHITE)
                textSize = 14f
            }
            ids[i] = rb.id
            rg.addView(rb)
        }
        rg.check(ids[p.mode.coerceIn(0, 2)])
        ipBox.visibility = if (p.mode == Prefs.MODE_MANUAL) View.VISIBLE else View.GONE
        rg.setOnCheckedChangeListener { _, checked ->
            p.mode = ids.indexOf(checked).coerceAtLeast(0)
            ipBox.visibility = if (p.mode == Prefs.MODE_MANUAL) View.VISIBLE else View.GONE
        }
        root.addView(rg)
        root.addView(ipBox)
        hint("Lowest lag: USB tethering (phone Settings → Hotspot & tethering → USB tethering) or the PC " +
             "joined to the phone's hotspot. Both work with 'Automatic'. The PC program shows its address when it starts.")

        // ---------------------------------------------------------- binding helper
        header("BIND CONTROLS IN A GAME")
        hint("In the game's controls menu, click the control to assign, then tap a button here. " +
             "The app sweeps only that axis for 4 seconds so the game picks the right one. " +
             "For buttons, just press the button on the wheel.")
        fun bind(axis: Int) { actions.bind(axis); dialog?.dismiss() }
        buttonRow("Steering" to { bind(1) }, "Throttle" to { bind(2) }, "Brake" to { bind(3) })
        buttonRow("Clutch" to { bind(4) }, "Handbrake" to { bind(5) })
        hint("Clutch and handbrake are vJoy axes Rx and Ry (enable them in Configure vJoy). Add their levers in Edit button layout.")

        // ---------------------------------------------------------- vibration
        header("VIBRATION (FORCE FEEDBACK)")
        hint(actions.vibrationInfo)
        val presetGroup = RadioGroup(act)
        val presetLabels = listOf(
            "Essential – only what you can act on, one effect at a time (recommended)",
            "Immersive – everything layered: road texture, shifts, rev limiter",
            "Off – no game vibration (button clicks still work)",
        )
        val presetIds = IntArray(3)
        presetLabels.forEachIndexed { i, l ->
            val rb = RadioButton(act).apply {
                id = View.generateViewId(); text = l; setTextColor(Color.WHITE); textSize = 14f
            }
            presetIds[i] = rb.id
            presetGroup.addView(rb)
        }
        presetGroup.check(presetIds[p.hPreset.coerceIn(0, 2)])
        presetGroup.setOnCheckedChangeListener { _, checked -> p.hPreset = presetIds.indexOf(checked).coerceAtLeast(0) }
        root.addView(presetGroup)
        hint("Essential: lock-up/ABS only while braking, wheelspin only off the brake, a short burst " +
             "when you hit a kerb or leave the track, a jolt on impacts. Long slides fade after a second.")
        buttonRow("Test all effects" to { actions.testVibration() })
        check("Vibration on", "Master switch, including button clicks", p.hapticsOn) { p.hapticsOn = it }
        pct("Overall strength", "", p.hMaster) { p.hMaster = it }
        pct("ABS / wheel lock-up", "Fast pulses", p.hAbs) { p.hAbs = it }
        pct("Wheelspin / slide", "Smooth rumble", p.hSlip) { p.hSlip = it }
        pct("Understeer", "Soft slow pulse when the front tyres run out of grip", p.hUnder) { p.hUnder = it }
        pct("Oversteer", "Sharp buzz when the rear starts to slide", p.hOver) { p.hOver = it }
        pct("Kerbs", "Short rough rattle", p.hKerb) { p.hKerb = it }
        pct("Off track", "Grass / gravel", p.hOff) { p.hOff = it }
        pct("Impacts", "One hard jolt", p.hImpact) { p.hImpact = it }
        pct("Road texture", "Immersive only", p.hRoad) { p.hRoad = it }
        pct("Gear shift", "Immersive only – single tick", p.hShift) { p.hShift = it }
        pct("Rev limiter", "Immersive only", p.hLimiter) { p.hLimiter = it }
        pct("Button clicks", "Buzz when pressing wheel buttons", p.hButtons) { p.hButtons = it }

        // ---------------------------------------------------------- dashboard
        header("VIBRATE OR FLASH")
        hint("Choose how each signal reaches you. Flashes are big screen-edge / pedal-strip lights, " +
             "made to be noticed from the corner of your eye while you watch the monitor.")
        fun modeRow(label: String, value: Int, set: (Int) -> Unit) {
            root.addView(TextView(act).apply {
                text = label; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            val g = RadioGroup(act).apply { orientation = RadioGroup.HORIZONTAL }
            val ids = IntArray(3)
            listOf("Vibrate", "Flash", "Both").forEachIndexed { i, l ->
                val rb = RadioButton(act).apply { id = View.generateViewId(); text = l; setTextColor(Color.WHITE); textSize = 14f }
                ids[i] = rb.id
                g.addView(rb)
            }
            g.check(ids[value.coerceIn(0, 2)])
            g.setOnCheckedChangeListener { _, checked -> set(ids.indexOf(checked).coerceAtLeast(0)) }
            root.addView(g)
        }
        modeRow("Brake marker", p.fbMarker) { p.fbMarker = it }
        modeRow("ABS / lock-up", p.fbAbs) { p.fbAbs = it }
        modeRow("Wheelspin / slide", p.fbSlip) { p.fbSlip = it }
        modeRow("Understeer (blue top edge)", p.fbUnder) { p.fbUnder = it }
        modeRow("Oversteer (red side edges)", p.fbOver) { p.fbOver = it }
        check("Flash at shift point", "Screen edge blinks blue when it's time to shift", p.flashShift) { p.flashShift = it }
        check("Flash with coach messages", "Short green (better) / red (worse) glow", p.flashCoach) { p.flashCoach = it }
        pct("Flash brightness", "", p.flashBright) { p.flashBright = it }

        header("DRIVING COACH")
        hint("Compares every lap with your best lap on the same track and car (saved on the PC, so it " +
             "carries over to your next session). Drive one clean lap to set the reference.")
        check("Brake markers", "Two quick ticks in your thumb just before each braking point of your best lap",
            p.coachMarkers) { p.coachMarkers = it }
        slider("Marker timing", "How early the ticks come before the braking point", 200f, 1200f, 50f, p.markerLead,
            { String.format(Locale.US, "%.2f s", it / 1000f) }) { p.markerLead = it }
        pct("Marker strength", "", p.hMarker) { p.hMarker = it }
        check("Coach messages", "Short notes on the dashboard: braking point, peak, release, lock-ups, " +
              "corner exits, sector times, new best laps", p.coachMessages) { p.coachMessages = it }
        buttonRow("Open session report" to { actions.openReport() }, "Reference laps" to { actions.openLaps() })
        hint("The report (lap times, traces vs your best lap, where you lose time) also opens on the PC at " +
             "http://localhost:8765/report")

        header("DASHBOARD")
        check("Show dashboard", "Shift lights, gear, speed, TC/ABS/MAP/BB, fuel, delta", p.showDash) { p.showDash = it }
        slider("Shift lights start", "First LED lights at this % of the shift point", 50f, 98f, 1f, p.ledStart,
            { "${it.roundToInt()}%" }) { p.ledStart = it }

        // ---------------------------------------------------------- steering
        header("STEERING")
        slider("Steering range", "How far you rotate the phone, lock to lock", 30f, 360f, 5f, p.range,
            { "${it.roundToInt()}°" }) { p.range = it }
        check("Match each car's steering lock", "RaceRoom and LMU report the car's steering lock; the phone " +
              "range follows it so the same tilt always means the same wheel angle", p.matchLock) { p.matchLock = it }
        slider("Wheel degrees per phone degree", "Used with 'match steering lock'. 6 = a 540° car needs 90° of phone rotation",
            3f, 10f, 0.5f, p.lockRatio, { String.format(Locale.US, "%.1f", it) }) { p.lockRatio = it }
        check("Gyro-fused sensor", "Smoother, faster and ignores bumps. Turn off if steering drifts.", p.fusedSensor) { p.fusedSensor = it }
        pct("Speed-sensitive steering", "Calmer steering at high speed (needs a running game)", p.speedSens) { p.speedSens = it }
        slider("Center deadzone", "Degrees ignored around center", 0f, 5f, 0.1f, p.deadzone,
            { f1(it) + "°" }) { p.deadzone = it }
        slider("Smoothing", "Higher = steadier but slightly more lag", 0f, 0.9f, 0.05f, p.smoothing,
            { "${(it * 100).roundToInt()}%" }) { p.smoothing = it }
        slider("Linearity", ">1 softer near center, <1 sharper", 0.5f, 2.5f, 0.05f, p.linearity,
            { f2(it) }) { p.linearity = it }
        check("Invert steering", "Use if steering goes the wrong way", p.invert) { p.invert = it }

        // ---------------------------------------------------------- pedals
        header("PEDALS")
        slider("Brake detents", "Ticks you feel as the brake passes evenly spaced points (3 = 25/50/75%)",
            0f, 4f, 1f, p.brakeDetents.toFloat(), { if (it < 0.5f) "off" else "${it.roundToInt()}" }) { p.brakeDetents = it.roundToInt() }
        pct("Detent strength", "", p.hDetent) { p.hDetent = it }
        check("Brake trace", "Shows your last brake application on the brake strip: peak, and how you released", p.brakeTrace) { p.brakeTrace = it }
        slider("Brake pressure feel", "Pressing harder adds brake (uses thumb contact size; not every phone reports it)",
            0f, 100f, 5f, p.brakePressure, { "${it.roundToInt()}%" }) { p.brakePressure = it }
        slider("Brake release smoothing", "Brake lets go more gently for steadier trail braking", 0f, 300f, 10f,
            p.brakeRelease, { if (it <= 0f) "off" else "${it.roundToInt()} ms" }) { p.brakeRelease = it }
        check("Relative pedals", "Off: height on the strip = amount. On: slide up from where you touch", p.pedalRelative) { p.pedalRelative = it }
        slider("Pedal deadzone", "Bottom part ignored (rest your thumb)", 0f, 30f, 1f, p.pedalDead,
            { "${it.roundToInt()}%" }) { p.pedalDead = it }
        slider("Pedal saturation", "Top part that already counts as 100%", 0f, 30f, 1f, p.pedalSat,
            { "${it.roundToInt()}%" }) { p.pedalSat = it }
        slider("Throttle curve", ">1 finer control at low throttle", 0.4f, 3f, 0.05f, p.thrCurve,
            { f2(it) }) { p.thrCurve = it }
        slider("Brake curve", ">1 finer control for trail braking", 0.4f, 3f, 0.05f, p.brkCurve,
            { f2(it) }) { p.brkCurve = it }

        // ---------------------------------------------------------- other
        header("BUTTONS")
        check("Look buttons toggle", "Tap LOOK left/right/back once to look, tap again to return. Off = hold to look", p.lookToggle) { p.lookToggle = it }

        header("BATTERY")
        hint("Your eyes are on the monitor, so the phone screen can be dim. Lower frame rate and sensor rate " +
             "save more battery and heat on long stints.")
        slider("Screen brightness while driving", "0 = use the phone's own setting", 0f, 100f, 5f,
            if (p.brightness < 0f) 0f else p.brightness * 100f, { if (it < 1f) "phone" else "${it.roundToInt()}%" }) {
            p.brightness = if (it < 1f) -1f else it / 100f
        }
        slider("Screen frame rate", "Lower = less battery; pedals and dashboard stay responsive", 30f, 90f, 15f, p.maxFps,
            { "${it.roundToInt()} fps" }) { p.maxFps = it }
        slider("Steering sensor rate", "100 Hz saves battery, 200 Hz is smoother", 100f, 200f, 100f, p.sensorHz,
            { "${it.roundToInt()} Hz" }) { p.sensorHz = it }

        header("HELP")
        buttonRow("Diagnostics" to { dialog?.dismiss(); actions.openDiag() })
        hint("Live connection, sensor, battery and game-data readouts, and a button that sends a debug log to the PC " +
             "(attach it when you report a problem).")

        header("OTHER")
        check("Show button numbers", "vJoy button number on each key (for binding)", p.showNums) { p.showNums = it }
        root.addView(TextView(act).apply {
            text = "PhoneWheel • vJoy buttons: 1 upshift, 2 downshift, 3–32 wheel keys"
            setTextColor(0xFF5A6370.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        })

        val scroll = ScrollView(act).apply { addView(root) }
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PhoneWheel setup")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset tuning") { _, _ ->
                p.manualIp = ipBox.text.toString().trim()
                p.resetTuning()
            }
            .setOnDismissListener {
                p.manualIp = ipBox.text.toString().trim()
                p.save()
                onDone()
            }
            .create()
        dialog = dlg
        dlg.show()
    }
}
