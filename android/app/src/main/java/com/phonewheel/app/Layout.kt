package com.phonewheel.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ============================================================================ model
/** One tile on a button page: a vJoy button (num 1..32) or an analog lever (clutch / handbrake). */
class LItem(
    var type: String,          // TYPE_BUTTON, TYPE_CLUTCH, TYPE_HANDBRAKE
    var num: Int,              // vJoy button number for buttons
    var label: String,
    var sub: String,
    var color: Int,
    var row: Int,
    var col: Int,
    var rs: Int = 1,           // row span
    var cs: Int = 1,           // column span
) {
    fun copy() = LItem(type, num, label, sub, color, row, col, rs, cs)

    fun toJson(): JSONObject = JSONObject().put("t", type).put("n", num).put("l", label).put("s", sub)
        .put("c", color).put("r", row).put("k", col).put("rs", rs).put("cs", cs)

    companion object {
        const val TYPE_BUTTON = "btn"
        const val TYPE_CLUTCH = "clutch"
        const val TYPE_HANDBRAKE = "hbrake"

        fun fromJson(o: JSONObject) = LItem(o.optString("t", TYPE_BUTTON), o.optInt("n", 3), o.optString("l", ""),
            o.optString("s", ""), o.optInt("c", Palette.WHITE), o.optInt("r", 0), o.optInt("k", 0),
            o.optInt("rs", 1).coerceAtLeast(1), o.optInt("cs", 1).coerceAtLeast(1))
    }
}

class LPage(var name: String, val items: MutableList<LItem> = ArrayList())

/** Everything about how the wheel screen is arranged. Stored as JSON in the active profile. */
class LayoutModel(
    var cols: Int = 4,
    var rows: Int = 4,
    var pedalPct: Float = 14f,     // width of each pedal strip, % of screen width
    var paddlePct: Float = 9f,     // width of each paddle, % of screen width
    var swapPaddles: Boolean = false,
    var dashPct: Float = 25f,      // dashboard height, % of screen height
    val pages: MutableList<LPage> = ArrayList(),
) {
    fun toJson(): String {
        val o = JSONObject().put("v", 1).put("cols", cols).put("rows", rows).put("pedal", pedalPct.toDouble())
            .put("paddle", paddlePct.toDouble()).put("swap", swapPaddles).put("dash", dashPct.toDouble())
        val ps = JSONArray()
        for (p in pages) {
            val items = JSONArray()
            for (it in p.items) items.put(it.toJson())
            ps.put(JSONObject().put("name", p.name).put("items", items))
        }
        return o.put("pages", ps).toString()
    }

    /** True if the area is inside the grid and free (ignoring [except]). */
    fun fits(page: LPage, row: Int, col: Int, rs: Int, cs: Int, except: LItem? = null): Boolean {
        if (row < 0 || col < 0 || row + rs > rows || col + cs > cols) return false
        for (o in page.items) {
            if (o === except) continue
            if (row < o.row + o.rs && o.row < row + rs && col < o.col + o.cs && o.col < col + cs) return false
        }
        return true
    }

    fun itemAt(page: LPage, row: Int, col: Int): LItem? =
        page.items.firstOrNull { row >= it.row && row < it.row + it.rs && col >= it.col && col < it.col + it.cs }

    /** After changing the grid size: pull tiles back inside, shrink if needed, drop what no longer fits. */
    fun clampToGrid() {
        for (p in pages) {
            val keep = ArrayList<LItem>()
            for (it in p.items) {
                it.cs = it.cs.coerceIn(1, cols); it.rs = it.rs.coerceIn(1, rows)
                it.col = it.col.coerceIn(0, cols - it.cs); it.row = it.row.coerceIn(0, rows - it.rs)
                if (keep.none { o -> it.row < o.row + o.rs && o.row < it.row + it.rs && it.col < o.col + o.cs && o.col < it.col + it.cs }) keep.add(it)
            }
            p.items.clear(); p.items.addAll(keep)
        }
    }

    companion object {
        fun fromJson(s: String?): LayoutModel {
            if (s.isNullOrBlank()) return default()
            return try {
                val o = JSONObject(s)
                val m = LayoutModel(o.optInt("cols", 4).coerceIn(2, 6), o.optInt("rows", 4).coerceIn(2, 6),
                    o.optDouble("pedal", 14.0).toFloat(), o.optDouble("paddle", 9.0).toFloat(),
                    o.optBoolean("swap", false), o.optDouble("dash", 25.0).toFloat())
                val ps = o.getJSONArray("pages")
                for (i in 0 until ps.length()) {
                    val po = ps.getJSONObject(i)
                    val page = LPage(po.optString("name", "PAGE ${i + 1}"))
                    val items = po.getJSONArray("items")
                    for (j in 0 until items.length()) page.items.add(LItem.fromJson(items.getJSONObject(j)))
                    m.pages.add(page)
                }
                if (m.pages.isEmpty()) m.pages.add(LPage("DRIVE"))
                m
            } catch (_: Exception) { default() }
        }

        /** The standard GT3 layout (same as before the editor existed). */
        fun default(): LayoutModel {
            val m = LayoutModel()
            val drive = LPage("DRIVE")
            val d = listOf(3 to (0 to 0), 4 to (0 to 1), 5 to (0 to 2), 6 to (0 to 3),
                8 to (1 to 0), 10 to (1 to 1), 12 to (1 to 2), 14 to (1 to 3),
                9 to (2 to 0), 11 to (2 to 1), 13 to (2 to 2), 15 to (2 to 3),
                18 to (3 to 0), 16 to (3 to 1), 17 to (3 to 2), 19 to (3 to 3))
            for ((n, rc) in d) drive.items.add(Catalog.item(n, rc.first, rc.second))
            val mfd = LPage("MFD")
            val f = listOf(20 to (0 to 0), 25 to (0 to 1), 30 to (0 to 2), 31 to (0 to 3),
                27 to (1 to 0), 29 to (1 to 1), 28 to (1 to 2),
                24 to (2 to 0), 26 to (2 to 1), 7 to (2 to 2),
                21 to (3 to 0), 22 to (3 to 3))
            for ((n, rc) in f) mfd.items.add(Catalog.item(n, rc.first, rc.second))
            mfd.items.add(Catalog.item(32, 1, 3).also { it.rs = 2 })
            mfd.items.add(Catalog.item(23, 3, 1).also { it.cs = 2 })
            m.pages.add(drive); m.pages.add(mfd)
            return m
        }
    }
}

object Palette {
    val RED = 0xFFFF3B30.toInt()
    val GREEN = 0xFF2BD96B.toInt()
    val YELLOW = 0xFFFFCC00.toInt()
    val ORANGE = 0xFFFF8A00.toInt()
    val BLUE = 0xFF2F8CFF.toInt()
    val PURPLE = 0xFFB06BFF.toInt()
    val WHITE = 0xFFDFE5EC.toInt()
    val CYAN = 0xFF2FD6E0.toInt()
    val ALL = intArrayOf(RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, WHITE)
}

/** What each vJoy button means by default (matches the docs' button map). */
object Catalog {
    class Fn(val num: Int, val label: String, val sub: String, val color: Int, val name: String)

    val buttons = listOf(
        Fn(1, "UP", "SHIFT", Palette.WHITE, "Upshift (also the left paddle)"),
        Fn(2, "DOWN", "SHIFT", Palette.WHITE, "Downshift (also the right paddle)"),
        Fn(3, "PIT", "LIMITER", Palette.BLUE, "Pit limiter"),
        Fn(4, "LIGHTS", "", Palette.WHITE, "Headlights"),
        Fn(5, "FLASH", "", Palette.WHITE, "Flash lights"),
        Fn(6, "WIPER", "", Palette.CYAN, "Wipers"),
        Fn(7, "RAIN", "LIGHT", Palette.CYAN, "Rain light"),
        Fn(8, "TC +", "", Palette.YELLOW, "TC +"),
        Fn(9, "TC −", "", Palette.YELLOW, "TC −"),
        Fn(10, "ABS +", "", Palette.ORANGE, "ABS +"),
        Fn(11, "ABS −", "", Palette.ORANGE, "ABS −"),
        Fn(12, "MAP +", "ENGINE", Palette.BLUE, "Engine map +"),
        Fn(13, "MAP −", "ENGINE", Palette.BLUE, "Engine map −"),
        Fn(14, "BB +", "BIAS FWD", Palette.PURPLE, "Brake bias + (front)"),
        Fn(15, "BB −", "BIAS REAR", Palette.PURPLE, "Brake bias − (rear)"),
        Fn(16, "IGN", "IGNITION", Palette.RED, "Ignition"),
        Fn(17, "START", "ENGINE", Palette.GREEN, "Starter"),
        Fn(18, "◀ IND", "LEFT", Palette.ORANGE, "Indicator left"),
        Fn(19, "IND ▶", "RIGHT", Palette.ORANGE, "Indicator right"),
        Fn(20, "HAZARD", "", Palette.RED, "Hazards"),
        Fn(21, "LOOK", "LEFT", Palette.YELLOW, "Look left"),
        Fn(22, "LOOK", "RIGHT", Palette.YELLOW, "Look right"),
        Fn(23, "LOOK", "BACK", Palette.YELLOW, "Look back"),
        Fn(24, "CAM", "CAMERA", Palette.PURPLE, "Camera"),
        Fn(25, "▲", "MFD UP", Palette.WHITE, "MFD up"),
        Fn(26, "▼", "MFD DOWN", Palette.WHITE, "MFD down"),
        Fn(27, "◀", "MFD LEFT", Palette.WHITE, "MFD left"),
        Fn(28, "▶", "MFD RIGHT", Palette.WHITE, "MFD right"),
        Fn(29, "OK", "ENTER", Palette.GREEN, "MFD OK / enter"),
        Fn(30, "PAGE", "MFD CYCLE", Palette.CYAN, "MFD page cycle"),
        Fn(31, "ESC", "MENU", Palette.RED, "ESC / menu"),
        Fn(32, "HUD", "CYCLE", Palette.BLUE, "HUD cycle"),
    )

    fun item(num: Int, row: Int, col: Int): LItem {
        val f = buttons.first { it.num == num }
        return LItem(LItem.TYPE_BUTTON, num, f.label, f.sub, f.color, row, col)
    }

    /** Choices in the editor: 32 buttons + 2 analog levers. */
    fun choices(): List<String> =
        buttons.map { "${it.num} · ${it.name}" } + listOf("Clutch (analog lever)", "Handbrake (analog lever)")

    fun choiceIndex(it: LItem) = when (it.type) {
        LItem.TYPE_CLUTCH -> buttons.size
        LItem.TYPE_HANDBRAKE -> buttons.size + 1
        else -> (it.num - 1).coerceIn(0, buttons.size - 1)
    }
}

// ============================================================================ editor dialogs
object LayoutDialogs {

    /** Edit (or create, when [isNew]) a tile. [onSave] gets the edited copy; [onDelete] removes it. */
    fun editItem(act: Activity, model: LayoutModel, page: LPage, item: LItem, isNew: Boolean,
                 onSave: (LItem) -> Unit, onDelete: () -> Unit) {
        val dp = act.resources.displayMetrics.density
        val e = item.copy()
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        fun label(t: String) = root.addView(TextView(act).apply { text = t; setTextColor(0xFFFFCC00.toInt()); textSize = 12f; setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt()) })

        label("FUNCTION")
        val spinner = Spinner(act)
        spinner.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, Catalog.choices())
        spinner.setSelection(Catalog.choiceIndex(e))
        root.addView(spinner)

        label("LABEL")
        val lab = EditText(act).apply { setText(e.label); setTextColor(Color.WHITE); isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        root.addView(lab)
        val sub = EditText(act).apply { setText(e.sub); hint = "small text under the label (optional)"; setHintTextColor(0xFF5A6370.toInt()); setTextColor(Color.WHITE); isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        root.addView(sub)

        var userEditedLabel = !isNew
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (userEditedLabel) { userEditedLabel = false; return }  // keep custom label on first open
                when {
                    pos < Catalog.buttons.size -> { val f = Catalog.buttons[pos]; lab.setText(f.label); sub.setText(f.sub); e.color = f.color }
                    pos == Catalog.buttons.size -> { lab.setText("CLUTCH"); sub.setText("SLIDE UP"); e.color = Palette.CYAN }
                    else -> { lab.setText("HANDBRAKE"); sub.setText("SLIDE UP"); e.color = Palette.RED }
                }
                colorRow?.let { refreshColors(it, e) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        label("COLOUR")
        val colors = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        colorRow = colors
        for (c in Palette.ALL) {
            colors.addView(View(act).apply {
                tag = c
                layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()).apply { rightMargin = (8 * dp).toInt() }
                setOnClickListener { e.color = c; refreshColors(colors, e) }
            })
        }
        refreshColors(colors, e)
        root.addView(colors)

        label("SIZE (cells)")
        val sizeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sizeText = TextView(act).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }
        fun upd() { sizeText.text = "${e.cs} wide × ${e.rs} tall" }
        fun step(t: String, f: () -> Unit) = sizeRow.addView(Button(act).apply { text = t; isAllCaps = false; setOnClickListener { f(); upd() } })
        step("− W") { if (e.cs > 1) e.cs-- }
        step("+ W") { if (model.fits(page, e.row, e.col, e.rs, e.cs + 1, item)) e.cs++ }
        step("− H") { if (e.rs > 1) e.rs-- }
        step("+ H") { if (model.fits(page, e.row, e.col, e.rs + 1, e.cs, item)) e.rs++ }
        sizeRow.addView(sizeText)
        upd()
        root.addView(sizeRow)
        root.addView(TextView(act).apply { text = "Tip: drag tiles on the wheel to move them; drop on another tile to swap."; setTextColor(0xFF8A94A3.toInt()); textSize = 12f; setPadding(0, (10 * dp).toInt(), 0, 0) })

        val b = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (isNew) "Add tile" else "Edit tile")
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                val pos = spinner.selectedItemPosition
                when {
                    pos < Catalog.buttons.size -> { e.type = LItem.TYPE_BUTTON; e.num = Catalog.buttons[pos].num }
                    pos == Catalog.buttons.size -> { e.type = LItem.TYPE_CLUTCH; e.num = 0 }
                    else -> { e.type = LItem.TYPE_HANDBRAKE; e.num = 0 }
                }
                e.label = lab.text.toString().trim().ifEmpty { " " }
                e.sub = sub.text.toString().trim()
                onSave(e)
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("Delete") { _, _ -> onDelete() }
        b.show()
    }

    private var colorRow: LinearLayout? = null

    private fun refreshColors(row: LinearLayout, e: LItem) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val c = v.tag as Int
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(if (c == e.color) 6 else 0, Color.WHITE)
            }
        }
    }

    /** Grid size, pedal/paddle widths, dashboard height, pages. */
    fun options(act: Activity, model: LayoutModel, pageIndex: Int, showDash: Boolean,
                onChange: (pageIndex: Int, showDash: Boolean) -> Unit, onReset: () -> Unit) {
        val dp = act.resources.displayMetrics.density
        var dash = showDash
        var current = pageIndex
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }

        fun slider(title: String, lo: Int, hi: Int, v: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
            val t = TextView(act).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, (10 * dp).toInt(), 0, 0); text = "$title: ${fmt(v)}" }
            root.addView(t)
            root.addView(SeekBar(act).apply {
                max = hi - lo; progress = v - lo
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { set(p + lo); t.text = "$title: ${fmt(p + lo)}" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        slider("Grid columns", 2, 6, model.cols, { "$it" }) { model.cols = it }
        slider("Grid rows", 2, 6, model.rows, { "$it" }) { model.rows = it }
        slider("Pedal strip width", 8, 24, model.pedalPct.roundToInt(), { "$it% of screen" }) { model.pedalPct = it.toFloat() }
        slider("Paddle width", 5, 16, model.paddlePct.roundToInt(), { "$it% of screen" }) { model.paddlePct = it.toFloat() }
        slider("Dashboard height", 15, 40, model.dashPct.roundToInt(), { "$it% of screen" }) { model.dashPct = it.toFloat() }
        root.addView(CheckBox(act).apply { text = "Show dashboard"; setTextColor(Color.WHITE); isChecked = dash; setOnCheckedChangeListener { _, c -> dash = c } })
        root.addView(CheckBox(act).apply { text = "Swap paddles (upshift on the right)"; setTextColor(Color.WHITE); isChecked = model.swapPaddles; setOnCheckedChangeListener { _, c -> model.swapPaddles = c } })

        root.addView(TextView(act).apply { text = "PAGES"; setTextColor(0xFFFFCC00.toInt()); textSize = 12f; setPadding(0, (14 * dp).toInt(), 0, 0) })
        val pageName = EditText(act).apply { setText(model.pages[current].name); setTextColor(Color.WHITE); isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        root.addView(TextView(act).apply { text = "Name of this page"; setTextColor(0xFF8A94A3.toInt()); textSize = 12f })
        root.addView(pageName)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        var dlg: AlertDialog? = null
        row.addView(Button(act).apply {
            text = "Add page"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                model.pages[current].name = pageName.text.toString().trim().ifEmpty { model.pages[current].name }
                if (model.pages.size < 6) { model.pages.add(LPage("PAGE ${model.pages.size + 1}")); current = model.pages.size - 1 }
                model.clampToGrid(); dlg?.dismiss(); onChange(current, dash)
            }
        })
        row.addView(Button(act).apply {
            text = "Delete this page"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (model.pages.size > 1) { model.pages.removeAt(current); current = 0 }
                model.clampToGrid(); dlg?.dismiss(); onChange(current, dash)
            }
        })
        root.addView(row)
        root.addView(Button(act).apply {
            text = "Reset to the standard GT3 layout"; isAllCaps = false
            setOnClickListener { dlg?.dismiss(); onReset() }
        })

        dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Layout options")
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton("Done") { _, _ ->
                model.pages[current].name = pageName.text.toString().trim().ifEmpty { model.pages[current].name }
                model.clampToGrid()
                onChange(current, dash)
            }
            .create()
        dlg.show()
    }
}
