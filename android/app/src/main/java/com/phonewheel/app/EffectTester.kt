package com.phonewheel.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Test effects" panel: play every effect in a row, or any single one, and see which one is playing.
 * Docked at the bottom without dimming, so the wheel's flashes (screen edges, pedal strips) stay visible.
 */
object EffectTester {

    fun show(act: Activity, prefs: Prefs, haptics: Haptics, redraw: () -> Unit, onClose: () -> Unit) {
        val dp = act.resources.displayMetrics.density
        val dlg = Dialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xF2101317.toInt()); cornerRadius = 16 * dp; setStroke((1 * dp).toInt(), 0xFF2A2F37.toInt()) }
        }

        // ---- "now playing"
        val title = TextView(act).apply { setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val desc = TextView(act).apply { setTextColor(0xFF8A94A3.toInt()); textSize = 13f }
        val progress = TextView(act).apply { setTextColor(0xFFFFCC00.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        root.addView(progress); root.addView(title); root.addView(desc)

        // ---- one button per effect
        val buttons = HashMap<String, Button>()
        val grid = GridLayout(act).apply { columnCount = 4; setPadding(0, (8 * dp).toInt(), 0, 0) }
        for (e in haptics.effects) {
            val b = Button(act).apply {
                text = e.name; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
                minHeight = (40 * dp).toInt(); minimumHeight = (40 * dp).toInt()
                background = pill(0xFF22262D.toInt(), dp)
                setOnClickListener { haptics.play(e.id) }
            }
            buttons[e.id] = b
            grid.addView(b, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
            })
        }
        root.addView(grid)

        // ---- controls
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
        fun ctl(label: String, color: Int, action: () -> Unit) = row.addView(Button(act).apply {
            text = label; isAllCaps = false; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (color == 0xFF22262D.toInt()) Color.WHITE else 0xFF0B0D10.toInt())
            background = pill(color, dp)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) })
        ctl("▶  Play all", 0xFF2BD96B.toInt()) { haptics.playAll() }
        ctl("■  Stop", 0xFF22262D.toInt()) { haptics.stopTest() }
        ctl("Close", 0xFF22262D.toInt()) { dlg.dismiss() }
        root.addView(row)

        // ---- live status
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val p = haptics.playing()
                if (p == null) {
                    progress.text = if (prefs.hapticsOn) "READY" else "VIBRATION IS OFF (SETUP → Vibration on) – flashes still show"
                    title.text = "Tap an effect to feel it, or Play all"
                    desc.text = "Each one plays with your current strength and Vibrate / Flash settings."
                } else {
                    val e = p.effect
                    val m = e.mode()
                    val how = when {
                        m == null -> "vibration"
                        m == Prefs.FB_FLASH -> "flash only"
                        m == Prefs.FB_BOTH -> "vibration + flash"
                        else -> "vibration"
                    } + if (e.id == "shift" && prefs.flashShift) " (+ blue edge at the shift point)" else ""
                    progress.text = (if (p.total > 1) "PLAYING ${p.index + 1} / ${p.total}" else "PLAYING") + "  ·  $how"
                    title.text = e.name
                    desc.text = e.desc
                }
                if (p != null) redraw()   // keep the wheel's flash + banner animating
                for ((id, b) in buttons) {
                    val active = p != null && p.effect.id == id
                    b.background = pill(if (active) 0xFFFFCC00.toInt() else 0xFF22262D.toInt(), dp)
                    b.setTextColor(if (active) 0xFF0B0D10.toInt() else Color.WHITE)
                }
                handler.postDelayed(this, 80)
            }
        }

        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            // keep the pedal strips (screen edges) visible for their flashes
            w.setLayout((act.resources.displayMetrics.widthPixels * 0.62f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.setOnDismissListener {
            handler.removeCallbacks(tick)
            haptics.stopTest()
            redraw()
            onClose()
        }
        dlg.show()
        handler.post(tick)
    }

    private fun pill(color: Int, dp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = 10 * dp }
}
