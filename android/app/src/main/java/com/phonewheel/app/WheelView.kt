package com.phonewheel.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The whole wheel UI, drawn on one canvas so multi-touch is handled in one place:
 * brake (left edge) | upshift paddle | buttons | downshift paddle | throttle (right edge)
 */
@SuppressLint("ViewConstructor")
class WheelView(
    ctx: Context,
    private val prefs: Prefs,
    private val st: InputState,
    private val tel: Telem,
) : View(ctx) {

    // ------------------------------------------------------------------ callbacks
    var onInput: () -> Unit = {}
    var onCenter: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onHaptic: (Long) -> Unit = {}

    // ------------------------------------------------------------------ button map (vJoy 1..32)
    private class Btn(
        val num: Int, val label: String, val sub: String, val color: Int,
        val page: Int, val row: Int, val col: Int, val rowSpan: Int = 1, val colSpan: Int = 1,
    ) {
        val rect = RectF()
    }

    private val red = 0xFFFF3B30.toInt()
    private val green = 0xFF2BD96B.toInt()
    private val yellow = 0xFFFFCC00.toInt()
    private val orange = 0xFFFF8A00.toInt()
    private val blue = 0xFF2F8CFF.toInt()
    private val purple = 0xFFB06BFF.toInt()
    private val white = 0xFFDFE5EC.toInt()
    private val cyan = 0xFF2FD6E0.toInt()

    private val buttons = listOf(
        // DRIVE page
        Btn(3, "PIT", "LIMITER", blue, 0, 0, 0),
        Btn(4, "LIGHTS", "", white, 0, 0, 1),
        Btn(5, "FLASH", "", white, 0, 0, 2),
        Btn(6, "WIPER", "", cyan, 0, 0, 3),
        Btn(8, "TC +", "", yellow, 0, 1, 0),
        Btn(10, "ABS +", "", orange, 0, 1, 1),
        Btn(12, "MAP +", "ENGINE", blue, 0, 1, 2),
        Btn(14, "BB +", "BIAS FWD", purple, 0, 1, 3),
        Btn(9, "TC −", "", yellow, 0, 2, 0),
        Btn(11, "ABS −", "", orange, 0, 2, 1),
        Btn(13, "MAP −", "ENGINE", blue, 0, 2, 2),
        Btn(15, "BB −", "BIAS REAR", purple, 0, 2, 3),
        Btn(18, "◀ IND", "LEFT", orange, 0, 3, 0),
        Btn(16, "IGN", "IGNITION", red, 0, 3, 1),
        Btn(17, "START", "ENGINE", green, 0, 3, 2),
        Btn(19, "IND ▶", "RIGHT", orange, 0, 3, 3),
        // MFD page
        Btn(20, "HAZARD", "", red, 1, 0, 0),
        Btn(25, "▲", "MFD UP", white, 1, 0, 1),
        Btn(30, "PAGE", "MFD CYCLE", cyan, 1, 0, 2),
        Btn(31, "ESC", "MENU", red, 1, 0, 3),
        Btn(27, "◀", "MFD LEFT", white, 1, 1, 0),
        Btn(29, "OK", "ENTER", green, 1, 1, 1),
        Btn(28, "▶", "MFD RIGHT", white, 1, 1, 2),
        Btn(32, "HUD", "CYCLE", blue, 1, 1, 3, rowSpan = 2),
        Btn(24, "CAM", "CAMERA", purple, 1, 2, 0),
        Btn(26, "▼", "MFD DOWN", white, 1, 2, 1),
        Btn(7, "RAIN", "LIGHT", cyan, 1, 2, 2),
        Btn(21, "LOOK", "LEFT", yellow, 1, 3, 0),
        Btn(23, "LOOK", "BACK", yellow, 1, 3, 1, colSpan = 2),
        Btn(22, "LOOK", "RIGHT", yellow, 1, 3, 3),
    )

    // ------------------------------------------------------------------ hit targets
    private enum class Kind { BRAKE, THROTTLE, PADDLE_UP, PADDLE_DOWN, BUTTON, CENTER, LOCK, TAB0, TAB1, SETUP, STEERBAR }
    private class Target(val kind: Kind, val btn: Btn? = null)

    private val rBrake = RectF()
    private val rThrottle = RectF()
    private val rPadUp = RectF()
    private val rPadDown = RectF()
    private val rCenterBtn = RectF()
    private val rLock = RectF()
    private val rTab0 = RectF()
    private val rTab1 = RectF()
    private val rSetup = RectF()
    private val rSteerBar = RectF()
    private val rDash = RectF()

    private val pointers = HashMap<Int, Target>()
    private val pressCount = IntArray(33)
    private var brakeOwner = -1
    private var throttleOwner = -1
    private var brakeOriginY = 0f
    private var throttleOriginY = 0f
    private var steerOwner = -1

    // ------------------------------------------------------------------ public state
    var page = 0
        private set
    var locked = false
        private set
    var manualSteer: Float? = null
        private set
    var status = LinkStatus(false, "Starting…")
        set(v) { field = v; invalidate() }

    // ------------------------------------------------------------------ paints
    private val dp = resources.displayMetrics.density
    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * resources.displayMetrics.density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensedBold; textAlign = Paint.Align.CENTER }
    private val tmp = RectF()
    private val tmpRect = Rect()
    private var bgShader: Shader? = null

    init {
        isHapticFeedbackEnabled = false
        setBackgroundColor(Color.BLACK)
    }

    // ------------------------------------------------------------------ layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 6 * dp
        val gap = 6 * dp
        val pedalW = max(84 * dp, w * 0.14f)
        val paddleW = max(58 * dp, w * 0.09f)
        var x = pad
        rBrake.set(x, pad, x + pedalW, h - pad); x += pedalW + gap
        rPadUp.set(x, pad, x + paddleW, h - pad)
        x = w - pad
        rThrottle.set(x - pedalW, pad, x, h - pad); x -= pedalW + gap
        rPadDown.set(x - paddleW, pad, x, h - pad)

        val cl = rPadUp.right + gap
        val cr = rPadDown.left - gap
        // top bar: CENTER | LOCK | DRIVE MFD | SETUP  (right to left)
        val tbH = 36 * dp
        val tg = 5 * dp
        val unit = min(64 * dp, (cr - cl - 4 * tg) / 5.2f)
        var r = cr
        rSetup.set(r - unit, pad, r, pad + tbH); r -= unit + tg
        rTab1.set(r - unit, pad, r, pad + tbH); r -= unit
        rTab0.set(r - unit, pad, r, pad + tbH); r -= unit + tg
        rLock.set(r - unit, pad, r, pad + tbH); r -= unit + tg
        rCenterBtn.set(cl, pad, r, pad + tbH)

        var y = pad + tbH + 5 * dp
        if (prefs.showDash) {
            val dashH = (h * 0.25f).coerceIn(76 * dp, 120 * dp)
            rDash.set(cl, y, cr, y + dashH)
            y += dashH + 5 * dp
        } else {
            rDash.setEmpty()
        }
        rSteerBar.set(cl, y, cr, y + 22 * dp)

        val gTop = rSteerBar.bottom + gap
        layoutW = w; layoutH = h
        val gBottom = h - pad
        val cw = (cr - cl - 3 * gap) / 4f
        val ch = (gBottom - gTop - 3 * gap) / 4f
        for (b in buttons) {
            val l = cl + b.col * (cw + gap)
            val t = gTop + b.row * (ch + gap)
            b.rect.set(l, t, l + b.colSpan * cw + (b.colSpan - 1) * gap, t + b.rowSpan * ch + (b.rowSpan - 1) * gap)
        }

        bgShader = LinearGradient(0f, 0f, 8 * dp, 8 * dp, 0xFF121519.toInt(), 0xFF0D0F12.toInt(), Shader.TileMode.MIRROR)

        // stop Android's edge back-gesture from stealing the pedals (system caps this at 200dp per edge)
        if (Build.VERSION.SDK_INT >= 29) {
            val cy = h / 2
            val half = (100 * dp).toInt()
            systemGestureExclusionRects = listOf(
                Rect(0, cy - half, rBrake.right.toInt(), cy + half),
                Rect(rThrottle.left.toInt(), cy - half, w, cy + half),
            )
        }
    }

    private var layoutW = 0
    private var layoutH = 0

    /** Re-run layout after settings change (e.g. dashboard on/off). */
    fun relayout() {
        if (width > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    // ------------------------------------------------------------------ touch
    private fun hitTest(x: Float, y: Float): Target? {
        if (rBrake.contains(x, y)) return Target(Kind.BRAKE)
        if (rThrottle.contains(x, y)) return Target(Kind.THROTTLE)
        if (rPadUp.contains(x, y)) return Target(Kind.PADDLE_UP)
        if (rPadDown.contains(x, y)) return Target(Kind.PADDLE_DOWN)
        if (rCenterBtn.contains(x, y)) return Target(Kind.CENTER)
        if (rLock.contains(x, y)) return Target(Kind.LOCK)
        if (rTab0.contains(x, y)) return Target(Kind.TAB0)
        if (rTab1.contains(x, y)) return Target(Kind.TAB1)
        if (rSetup.contains(x, y)) return Target(Kind.SETUP)
        if (rSteerBar.contains(x, y)) return Target(Kind.STEERBAR)
        for (b in buttons) if (b.page == page && b.rect.contains(x, y)) return Target(Kind.BUTTON, b)
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val t = hitTest(ev.getX(i), ev.getY(i)) ?: return true
                lastDownSize = ev.getTouchMajor(i)
                pointers[id] = t
                press(t, id, ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val t = pointers[id] ?: continue
                    when (t.kind) {
                        Kind.BRAKE -> if (id == brakeOwner) { st.brkIn = brakeValue(ev.getY(i), ev.getTouchMajor(i)); changed() }
                        Kind.THROTTLE -> if (id == throttleOwner) { st.thr = pedalValue(rThrottle, ev.getY(i), throttleOriginY, prefs.thrCurve); changed() }
                        Kind.STEERBAR -> if (id == steerOwner) setManualSteer(ev.getX(i))
                        else -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                pointers.remove(id)?.let { release(it, id) }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun press(t: Target, id: Int, x: Float, y: Float) {
        when (t.kind) {
            Kind.BRAKE -> { brakeOwner = id; brakeOriginY = y; st.brkIn = brakeValue(y, lastDownSize); changed() }
            Kind.THROTTLE -> { throttleOwner = id; throttleOriginY = y; st.thr = pedalValue(rThrottle, y, y, prefs.thrCurve); changed() }
            Kind.PADDLE_UP -> pressButton(1, 25)
            Kind.PADDLE_DOWN -> pressButton(2, 25)
            Kind.BUTTON -> pressButton(t.btn!!.num, 15)
            Kind.CENTER -> { onCenter(); onHaptic(30) }
            Kind.LOCK -> { locked = !locked; onHaptic(20); onCenterLockChanged() }
            Kind.TAB0 -> { page = 0; onHaptic(10); invalidate() }
            Kind.TAB1 -> { page = 1; onHaptic(10); invalidate() }
            Kind.SETUP -> { releaseAll(); onSettings() }
            Kind.STEERBAR -> { steerOwner = id; setManualSteer(x) }
        }
    }

    private fun release(t: Target, id: Int) {
        when (t.kind) {
            Kind.BRAKE -> if (id == brakeOwner) { brakeOwner = -1; st.brkIn = 0f; changed() }
            Kind.THROTTLE -> if (id == throttleOwner) { throttleOwner = -1; st.thr = 0f; changed() }
            Kind.PADDLE_UP -> releaseButton(1)
            Kind.PADDLE_DOWN -> releaseButton(2)
            Kind.BUTTON -> releaseButton(t.btn!!.num)
            Kind.STEERBAR -> if (id == steerOwner) { steerOwner = -1; manualSteer = null; onCenterLockChanged() }
            else -> {}
        }
    }

    fun releaseAll() {
        pointers.clear()
        pressCount.fill(0)
        brakeOwner = -1; throttleOwner = -1; steerOwner = -1
        manualSteer = null
        st.thr = 0f; st.brkIn = 0f; st.brk = 0f; st.mask = 0
        changed()
    }

    private fun pressButton(num: Int, vib: Long) {
        pressCount[num]++
        st.mask = st.mask or (1 shl (num - 1))
        onHaptic(vib)
        changed()
    }

    private fun releaseButton(num: Int) {
        if (pressCount[num] > 0) pressCount[num]--
        if (pressCount[num] == 0) {
            st.mask = st.mask and (1 shl (num - 1)).inv()
            changed()
        }
    }

    private fun pedalValue(r: RectF, y: Float, originY: Float, curve: Float): Float =
        shape(pedalTravel(r, y, originY)).pow(curve)

    private fun pedalTravel(r: RectF, y: Float, originY: Float): Float =
        if (prefs.pedalRelative) (originY - y) / (r.height() * 0.6f) else (r.bottom - y) / r.height()

    private fun shape(v: Float): Float {
        val lo = prefs.pedalDead / 100f
        val hi = 1f - prefs.pedalSat / 100f
        return ((v - lo) / max(0.05f, hi - lo)).coerceIn(0f, 1f)
    }

    // --- pressure-sensitive brake: a harder press flattens the thumb, so the contact area grows
    private var lastDownSize = 0f
    private var sizeMin = 0f
    private var sizeMax = 0f

    private fun brakeValue(y: Float, size: Float): Float {
        var v = shape(pedalTravel(rBrake, y, brakeOriginY))
        val w = prefs.brakePressure / 100f
        if (size > 0f) {
            if (sizeMin == 0f || size < sizeMin) sizeMin = size
            if (size > sizeMax) sizeMax = size
        }
        // only blend once the screen has shown it really reports contact size
        if (w > 0f && sizeMin > 0f && sizeMax > sizeMin * 1.15f) {
            val p = ((size - sizeMin) / (sizeMax - sizeMin)).coerceIn(0f, 1f)
            v = v * (1f - w) + p * w
        }
        return v.coerceIn(0f, 1f).pow(prefs.brkCurve)
    }

    private fun setManualSteer(x: Float) {
        manualSteer = (((x - rSteerBar.left) / rSteerBar.width()) * 2f - 1f).coerceIn(-1f, 1f)
        onCenterLockChanged()
    }

    /** Asks the activity to recompute steering (lock / manual changed). */
    var onSteerModeChanged: () -> Unit = {}
    private fun onCenterLockChanged() { onSteerModeChanged(); changed() }

    private fun changed() {
        onInput()
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        fill.shader = bgShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        drawPedal(c, rBrake, st.brk, "BRAKE", red, -90f)
        drawPedal(c, rThrottle, st.thr, "THROTTLE", green, 90f)
        drawPaddle(c, rPadUp, "▲", "UP", st.mask and 1 != 0)
        drawPaddle(c, rPadDown, "▼", "DOWN", st.mask and 2 != 0)

        drawTopButton(c, rCenterBtn, "CENTER", false)
        drawTopButton(c, rLock, "LOCK", locked, yellow)
        drawTopButton(c, rTab0, "DRIVE", page == 0, 0xFF3A414B.toInt(), Color.WHITE)
        drawTopButton(c, rTab1, "MFD", page == 1, 0xFF3A414B.toInt(), Color.WHITE)
        drawTopButton(c, rSetup, "SETUP", false)
        drawSteerBar(c)

        for (b in buttons) if (b.page == page) drawButton(c, b)
        if (!rDash.isEmpty) drawDash(c)
        if (st.bindAxis != 0) drawBindBanner(c)
        postInvalidateOnAnimation() // live dashboard / pedals
    }

    // ------------------------------------------------------------------ dashboard
    private val ledColors = IntArray(15) { i ->
        when {
            i < 5 -> green
            i < 10 -> 0xFFFFB000.toInt()
            else -> red
        }
    }

    private fun drawDash(c: Canvas) {
        val r = rDash
        val rad = 10 * dp
        val now = android.os.SystemClock.elapsedRealtime()
        fill.color = 0xFF07090B.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = 0xFF2A2F37.toInt()
        c.drawRoundRect(r, rad, rad, stroke)

        val fresh = tel.dashFresh(now)
        val inner = 8 * dp
        // ---- shift lights
        val ledTop = r.top + 6 * dp
        val ledH = 9 * dp
        val n = ledColors.size
        val lgap = 3 * dp
        val lw = (r.width() - 2 * inner - (n - 1) * lgap) / n
        val shift = if (tel.shiftRpm > 0) tel.shiftRpm else tel.maxRpm
        val start = shift * prefs.ledStart / 100f
        val frac = if (fresh && shift > 0 && tel.rpm > start) ((tel.rpm - start) / (shift - start)) else 0f
        val atShift = fresh && shift > 0 && tel.rpm >= shift
        val blinkOn = (now / 70) % 2 == 0L
        for (i in 0 until n) {
            val x = r.left + inner + i * (lw + lgap)
            tmp.set(x, ledTop, x + lw, ledTop + ledH)
            fill.color = when {
                fresh && tel.pitLimiter -> if (((now / 250) % 2 == 0L) == (i % 2 == 0)) blue else 0xFF161A20.toInt()
                atShift -> if (blinkOn) blue else 0xFF161A20.toInt()
                frac * n > i -> ledColors[i]
                else -> 0xFF161A20.toInt()
            }
            c.drawRoundRect(tmp, 3 * dp, 3 * dp, fill)
        }

        val top = ledTop + ledH + 4 * dp
        val bodyH = r.bottom - top - 4 * dp
        if (!fresh) {
            text.color = 0xFF5A6370.toInt()
            text.typeface = condensed
            text.textSize = 14 * dp
            val msg = if (status.connected) "Start ACC, RaceRoom or LMU to see live data" else "Not connected to PC"
            c.drawText(msg, r.centerX(), top + bodyH / 2 + 5 * dp, text)
            text.typeface = condensedBold
            return
        }

        // ---- left: speed + delta
        val leftW = r.width() * 0.26f
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = (bodyH * 0.42f).coerceAtMost(34 * dp)
        val lx = r.left + inner + leftW / 2
        c.drawText(tel.speed.coerceAtLeast(0).toString(), lx, top + bodyH * 0.45f, text)
        text.typeface = condensed
        text.textSize = 10 * dp
        text.color = 0xFF8A94A3.toInt()
        c.drawText("KM/H", lx, top + bodyH * 0.45f + 12 * dp, text)
        if (tel.deltaMs != Int.MIN_VALUE) {
            val d = tel.deltaMs / 1000f
            text.typeface = condensedBold
            text.textSize = 14 * dp
            text.color = if (d > 0) red else green
            c.drawText(String.format(java.util.Locale.US, "%+.2f", d), lx, top + bodyH - 2 * dp, text)
        }
        text.typeface = condensedBold

        // ---- center: gear
        val gearW = r.width() * 0.2f
        val gx = r.left + inner + leftW + gearW / 2
        text.textSize = bodyH * 0.95f
        text.color = if (atShift) blue else Color.WHITE
        val g = when {
            tel.gear < 0 -> "R"
            tel.gear == 0 -> "N"
            else -> tel.gear.toString()
        }
        c.drawText(g, gx, top + bodyH * 0.84f, text)

        // ---- right: chips
        val chipsL = r.left + inner + leftW + gearW + 4 * dp
        val chipsR = r.right - inner
        val cw = (chipsR - chipsL - 2 * 4 * dp) / 3
        val ch = (bodyH - 4 * dp) / 2
        fun chip(col: Int, row: Int, label: String, value: String, hot: Int?) {
            val x = chipsL + col * (cw + 4 * dp)
            val yy = top + row * (ch + 4 * dp)
            tmp.set(x, yy, x + cw, yy + ch)
            fill.color = hot ?: 0xFF14181D.toInt()
            c.drawRoundRect(tmp, 6 * dp, 6 * dp, fill)
            text.typeface = condensed
            text.textSize = (ch * 0.28f).coerceAtMost(11 * dp)
            text.color = if (hot != null) 0xCC0B0D10.toInt() else 0xFF8A94A3.toInt()
            c.drawText(label, tmp.centerX(), yy + ch * 0.36f, text)
            text.typeface = condensedBold
            text.textSize = (ch * 0.48f).coerceAtMost(20 * dp)
            text.color = if (hot != null) 0xFF0B0D10.toInt() else Color.WHITE
            c.drawText(value, tmp.centerX(), yy + ch * 0.86f, text)
        }
        fun iv(v: Int) = if (v >= 0) v.toString() else "–"
        fun fv(v: Float) = if (v >= 0f) String.format(java.util.Locale.US, "%.1f", v) else "–"
        chip(0, 0, "TC", iv(tel.tc), if (tel.tcActive) yellow else null)
        chip(1, 0, "TC2", iv(tel.tc2), null)
        chip(2, 0, "ABS", iv(tel.abs), if (tel.absActive) orange else null)
        chip(0, 1, "MAP", iv(tel.map), null)
        chip(1, 1, "BB", fv(tel.bb), null)
        val fuelLabel = if (tel.fuelLaps > 0f) String.format(java.util.Locale.US, "FUEL %.1f LAPS", tel.fuelLaps) else "FUEL L"
        chip(2, 1, fuelLabel, fv(tel.fuel), if (tel.fuelLaps > 0f && tel.fuelLaps < 1.5f) red else null)
    }

    private fun drawBindBanner(c: Canvas) {
        val name = when (st.bindAxis) { 1 -> "STEERING"; 2 -> "THROTTLE"; else -> "BRAKE" }
        val w = width.toFloat()
        tmp.set(w * 0.25f, height * 0.4f, w * 0.75f, height * 0.6f)
        fill.color = 0xEE000000.toInt()
        c.drawRoundRect(tmp, 14 * dp, 14 * dp, fill)
        stroke.color = yellow
        c.drawRoundRect(tmp, 14 * dp, 14 * dp, stroke)
        text.color = yellow
        text.textSize = 20 * dp
        c.drawText("BINDING: SWEEPING $name", tmp.centerX(), tmp.centerY() - 2 * dp, text)
        text.typeface = condensed
        text.textSize = 13 * dp
        text.color = Color.WHITE
        c.drawText("Click the control in the game now", tmp.centerX(), tmp.centerY() + 20 * dp, text)
        text.typeface = condensedBold
    }

    private fun drawPedal(c: Canvas, r: RectF, v: Float, label: String, color: Int, rot: Float) {
        val rad = 12 * dp
        fill.color = 0xFF0F1114.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        c.save()
        tmp.set(r)
        c.clipRect(tmp)
        if (v > 0f) {
            fill.color = color
            tmp.set(r.left, r.bottom - r.height() * v, r.right, r.bottom)
            c.drawRoundRect(tmp, rad, rad, fill)
        }
        // 10% ticks
        fill.color = 0x16FFFFFF
        for (i in 1..9) {
            val y = r.bottom - r.height() * i / 10f
            c.drawRect(r.left, y - 0.5f * dp, r.right, y + 0.5f * dp, fill)
        }
        c.restore()
        stroke.color = if (v > 0f) 0x66FFFFFF else 0xFF2A2F37.toInt()
        c.drawRoundRect(r, rad, rad, stroke)

        text.color = Color.WHITE
        text.textSize = 24 * dp
        c.drawText((v * 100).roundToInt().toString(), r.centerX(), r.top + 34 * dp, text)

        c.save()
        c.rotate(rot, r.centerX(), r.centerY())
        text.color = 0xB0FFFFFF.toInt()
        text.textSize = 15 * dp
        text.letterSpacing = 0.3f
        c.drawText(label, r.centerX(), r.centerY() + 5 * dp, text)
        text.letterSpacing = 0f
        c.restore()
    }

    private fun drawPaddle(c: Canvas, r: RectF, arrow: String, label: String, on: Boolean) {
        val rad = 12 * dp
        fill.color = if (on) 0xFFDFE5EC.toInt() else 0xFF22262D.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = 0xFF3A414B.toInt()
        c.drawRoundRect(r, rad, rad, stroke)
        text.color = if (on) 0xFF111111.toInt() else 0xFFC9D1DC.toInt()
        text.textSize = 28 * dp
        c.drawText(arrow, r.centerX(), r.centerY() - 2 * dp, text)
        text.textSize = 14 * dp
        text.letterSpacing = 0.12f
        c.drawText(label, r.centerX(), r.centerY() + 22 * dp, text)
        text.letterSpacing = 0f
    }

    private fun drawTopButton(c: Canvas, r: RectF, label: String, on: Boolean, onColor: Int = yellow, onText: Int = 0xFF111111.toInt()) {
        val rad = 8 * dp
        fill.color = if (on) onColor else 0xFF22262D.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = 0xFF2A2F37.toInt()
        c.drawRoundRect(r, rad, rad, stroke)
        text.color = if (on) onText else 0xFFC9D1DC.toInt()
        text.textSize = 13 * dp
        text.letterSpacing = 0.06f
        drawCentered(c, label, r)
        text.letterSpacing = 0f
    }

    private fun drawSteerBar(c: Canvas) {
        val r = rSteerBar
        val rad = 6 * dp
        fill.color = 0xFF0F1114.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        val s = st.steer
        fill.color = 0x55FFCC00
        val mid = r.centerX()
        if (s >= 0) c.drawRect(mid, r.top, mid + s * r.width() / 2, r.bottom, fill)
        else c.drawRect(mid + s * r.width() / 2, r.top, mid, r.bottom, fill)
        fill.color = 0x99FFFFFF.toInt()
        c.drawRect(mid - 1 * dp, r.top, mid + 1 * dp, r.bottom, fill)
        stroke.color = 0xFF2A2F37.toInt()
        c.drawRoundRect(r, rad, rad, stroke)

        // connection status (left)
        val cy = r.centerY()
        fill.color = if (status.connected) green else red
        c.drawCircle(r.left + 10 * dp, cy, 4.5f * dp, fill)
        text.textAlign = Paint.Align.LEFT
        text.typeface = condensed
        text.textSize = 13 * dp
        text.color = if (status.connected) 0xFFE9EDF2.toInt() else 0xFFFFB3AD.toInt()
        c.drawText(status.text, r.left + 20 * dp, cy + 4.5f * dp, text)
        // steering value (right)
        text.textAlign = Paint.Align.RIGHT
        text.typeface = condensedBold
        text.color = Color.WHITE
        val tag = when {
            locked -> "LOCKED "
            manualSteer != null -> "TOUCH "
            else -> ""
        }
        val pct = (s * 100).roundToInt()
        c.drawText(tag + (if (pct > 0) "+" else "") + pct + "%", r.right - 8 * dp, cy + 4.5f * dp, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawButton(c: Canvas, b: Btn) {
        val r = b.rect
        val on = st.mask and (1 shl (b.num - 1)) != 0
        val rad = 10 * dp
        fill.color = if (on) b.color else 0xFF1C2026.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        if (!on) {
            // colored base strip like the LED rings on a GT3 wheel
            c.save()
            c.clipRect(r.left, r.bottom - 4 * dp, r.right, r.bottom)
            fill.color = b.color
            c.drawRoundRect(r, rad, rad, fill)
            c.restore()
        }
        stroke.color = if (on) b.color else 0xFF333A44.toInt()
        c.drawRoundRect(r, rad, rad, stroke)

        val mainSize = min(18 * dp, r.width() / 5.2f).coerceAtLeast(11 * dp)
        text.textSize = mainSize
        text.color = if (on) 0xFF0B0D10.toInt() else 0xFFE9EDF2.toInt()
        val hasSub = b.sub.isNotEmpty()
        val baseY = r.centerY() + mainSize * 0.35f - (if (hasSub) mainSize * 0.4f else 0f)
        c.drawText(b.label, r.centerX(), baseY, text)
        if (hasSub) {
            text.typeface = condensed
            text.textSize = mainSize * 0.62f
            text.color = if (on) 0xAA0B0D10.toInt() else 0xFF8A94A3.toInt()
            text.letterSpacing = 0.08f
            c.drawText(b.sub, r.centerX(), baseY + mainSize * 0.95f, text)
            text.letterSpacing = 0f
            text.typeface = condensedBold
        }
        if (prefs.showNums) {
            text.textSize = 10 * dp
            text.color = 0x66FFFFFF
            text.textAlign = Paint.Align.RIGHT
            c.drawText(b.num.toString(), r.right - 6 * dp, r.top + 13 * dp, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawCentered(c: Canvas, s: String, r: RectF) {
        text.getTextBounds(s, 0, s.length, tmpRect)
        c.drawText(s, r.centerX(), r.centerY() - tmpRect.exactCenterY(), text)
    }
}
