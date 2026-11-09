package com.phonewheel.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings screen, built in code (no layout XML / libraries needed). */
object SettingsDialog {

    fun show(act: Activity, p: Prefs, onDone: () -> Unit) {
        val dp = act.resources.displayMetrics.density
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
                setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
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
            row.addView(TextView(act).apply { text = sub; setTextColor(0xFF8A94A3.toInt()); textSize = 12f })
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

        fun check(label: String, sub: String, value: Boolean, set: (Boolean) -> Unit) {
            root.addView(CheckBox(act).apply {
                text = "$label\n$sub"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = value
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnCheckedChangeListener { _, c -> set(c) }
            })
        }

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
            "WiFi – find PC automatically",
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
        hint("The PhoneWheel PC program shows this PC's address when it starts.")

        // ---------------------------------------------------------- steering
        header("STEERING")
        slider("Steering range", "How far you rotate the phone, lock to lock", 30f, 360f, 5f, p.range,
            { "${it.roundToInt()}°" }) { p.range = it }
        slider("Center deadzone", "Degrees ignored around center", 0f, 5f, 0.1f, p.deadzone,
            { String.format("%.1f°", it) }) { p.deadzone = it }
        slider("Smoothing", "Higher = steadier but slightly more lag", 0f, 0.9f, 0.05f, p.smoothing,
            { "${(it * 100).roundToInt()}%" }) { p.smoothing = it }
        slider("Linearity", ">1 softer near center, <1 sharper", 0.5f, 2.5f, 0.05f, p.linearity,
            { String.format("%.2f", it) }) { p.linearity = it }
        check("Invert steering", "Use if steering goes the wrong way", p.invert) { p.invert = it }

        // ---------------------------------------------------------- pedals
        header("PEDALS")
        check("Relative pedals", "Off: height on the strip = amount. On: slide up from where you touch", p.pedalRelative) { p.pedalRelative = it }
        slider("Pedal deadzone", "Bottom part ignored (rest your thumb)", 0f, 30f, 1f, p.pedalDead,
            { "${it.roundToInt()}%" }) { p.pedalDead = it }
        slider("Pedal saturation", "Top part that already counts as 100%", 0f, 30f, 1f, p.pedalSat,
            { "${it.roundToInt()}%" }) { p.pedalSat = it }
        slider("Throttle curve", ">1 finer control at low throttle", 0.4f, 3f, 0.05f, p.thrCurve,
            { String.format("%.2f", it) }) { p.thrCurve = it }
        slider("Brake curve", ">1 finer control for trail braking", 0.4f, 3f, 0.05f, p.brkCurve,
            { String.format("%.2f", it) }) { p.brkCurve = it }

        // ---------------------------------------------------------- other
        header("OTHER")
        check("Vibration", "Buzz when pressing buttons", p.haptics) { p.haptics = it }
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
        dlg.show()
    }
}
