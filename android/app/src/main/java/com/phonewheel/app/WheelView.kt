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
    private val diag: Diag,
) : View(ctx) {

    // ------------------------------------------------------------------ callbacks
    var onInput: () -> Unit = {}
    var onCenter: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onHaptic: (Long) -> Unit = {}
    var onDetent: () -> Unit = {}
    /** Effect currently playing in the effect tester (for its flash + the TESTING banner). */
    var tester: () -> Haptics.Playing? = { null }

    // ------------------------------------------------------------------ layout (editable, per profile)
    var layout: LayoutModel = LayoutModel.fromJson(prefs.layoutJson)
        private set

    /** A tile of the current layout, positioned on screen. */
    private class Tile(val item: LItem, val pageIdx: Int) { val rect = RectF() }
    private var tiles: List<Tile> = emptyList()

    private val red = Palette.RED
    private val green = Palette.GREEN
    private val yellow = Palette.YELLOW
    private val orange = Palette.ORANGE
    private val blue = Palette.BLUE
    private val boxMode get() = prefs.role == Prefs.ROLE_BOX

    fun reloadLayout() {
        layout = LayoutModel.fromJson(prefs.layoutJson)
        if (page >= layout.pages.size) page = 0
        relayout()
    }

    private fun saveLayout() {
        prefs.layoutJson = layout.toJson()
        prefs.save()
    }

    // ------------------------------------------------------------------ hit targets
    private enum class Kind { BRAKE, THROTTLE, PADDLE_UP, PADDLE_DOWN, BUTTON, AXIS, CENTER, LOCK, PAGE, SETUP, STEERBAR,
        E_PREV, E_NEXT, E_OPTIONS, E_DONE, E_TILE, E_EMPTY }
    private class Target(val kind: Kind, val tile: Tile? = null, val row: Int = 0, val col: Int = 0)

    private val rBrake = RectF()
    private val rThrottle = RectF()
    private val rPadUp = RectF()
    private val rPadDown = RectF()
    private val rCenterBtn = RectF()
    private val rLock = RectF()
    private val rPage = RectF()
    private val rGrid = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private val rSetup = RectF()
    private val rSteerBar = RectF()
    private val rDash = RectF()

    private val pointers = HashMap<Int, Target>()
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
        set(v) { val changedText = field != v; field = v; if (changedText) requestFrame() }

    // ------------------------------------------------------------------ frame scheduling
    // The view only redraws when something changed or an animation is running, capped at
    // prefs.maxFps. (Redrawing every display refresh was the app's biggest battery drain.)
    @Volatile private var framePending = false
    private var lastFrameT = 0L

    fun requestFrame() {
        if (framePending) return
        framePending = true
        val minMs = (1000f / prefs.maxFps.coerceIn(15f, 120f)).toLong()
        val wait = minMs - (android.os.SystemClock.elapsedRealtime() - lastFrameT)
        if (wait <= 0) postInvalidateOnAnimation() else postInvalidateDelayed(wait)
    }

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
        val lay = layout
        var cl: Float
        var cr: Float
        if (boxMode) {
            // second device: no pedals or paddles, just dashboard + buttons
            rBrake.setEmpty(); rThrottle.setEmpty(); rPadUp.setEmpty(); rPadDown.setEmpty()
            cl = pad; cr = w - pad
        } else {
            val pedalW = max(70 * dp, w * lay.pedalPct / 100f)
            val paddleW = max(44 * dp, w * lay.paddlePct / 100f)
            var x = pad
            rBrake.set(x, pad, x + pedalW, h - pad); x += pedalW + gap
            val leftPaddle = RectF(x, pad, x + paddleW, h - pad)
            x = w - pad
            rThrottle.set(x - pedalW, pad, x, h - pad); x -= pedalW + gap
            val rightPaddle = RectF(x - paddleW, pad, x, h - pad)
            if (lay.swapPaddles) { rPadUp.set(rightPaddle); rPadDown.set(leftPaddle) } else { rPadUp.set(leftPaddle); rPadDown.set(rightPaddle) }
            cl = leftPaddle.right + gap
            cr = rightPaddle.left - gap
        }
        // top bar: CENTER | LOCK | PAGE | SETUP   (edit mode: ◀ | PAGE | ▶ | OPTIONS | DONE)
        val tbH = 36 * dp
        val tg = 5 * dp
        val unit = min(70 * dp, (cr - cl - 4 * tg) / 5.2f)
        var r = cr
        rSetup.set(r - unit, pad, r, pad + tbH); r -= unit + tg
        rPage.set(r - unit * 1.4f, pad, r, pad + tbH); r -= unit * 1.4f + tg
        if (boxMode) { rLock.setEmpty(); rCenterBtn.set(cl, pad, r, pad + tbH) }
        else { rLock.set(r - unit, pad, r, pad + tbH); r -= unit + tg; rCenterBtn.set(cl, pad, r, pad + tbH) }

        var y = pad + tbH + 5 * dp
        if (prefs.showDash) {
            val pct = if (boxMode) lay.dashPct * 1.5f else lay.dashPct
            val dashH = (h * pct / 100f).coerceIn(70 * dp, h * 0.6f)
            rDash.set(cl, y, cr, y + dashH)
            y += dashH + 5 * dp
        } else {
            rDash.setEmpty()
        }
        if (boxMode) rSteerBar.set(cl, y, cr, y + 22 * dp) else rSteerBar.set(cl, y, cr, y + 22 * dp)

        val gTop = rSteerBar.bottom + gap
        layoutW = w; layoutH = h
        val gBottom = h - pad
        rGrid.set(cl, gTop, cr, gBottom)
        cellW = (cr - cl - (lay.cols - 1) * gap) / lay.cols
        cellH = (gBottom - gTop - (lay.rows - 1) * gap) / lay.rows
        val list = ArrayList<Tile>()
        lay.pages.forEachIndexed { pi, pg ->
            for (it in pg.items) {
                val t = Tile(it, pi)
                val l = cl + it.col * (cellW + gap)
                val tp = gTop + it.row * (cellH + gap)
                t.rect.set(l, tp, l + it.cs * cellW + (it.cs - 1) * gap, tp + it.rs * cellH + (it.rs - 1) * gap)
                list.add(t)
            }
        }
        tiles = list

        bgShader = LinearGradient(0f, 0f, 8 * dp, 8 * dp, 0xFF121519.toInt(), 0xFF0D0F12.toInt(), Shader.TileMode.MIRROR)

        // stop Android's edge back-gesture from stealing the pedals (system caps this at 200dp per edge)
        if (Build.VERSION.SDK_INT >= 29 && !boxMode) {
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
        if (editing) return editHitTest(x, y)
        if (rBrake.contains(x, y)) return Target(Kind.BRAKE)
        if (rThrottle.contains(x, y)) return Target(Kind.THROTTLE)
        if (rPadUp.contains(x, y)) return Target(Kind.PADDLE_UP)
        if (rPadDown.contains(x, y)) return Target(Kind.PADDLE_DOWN)
        if (!boxMode && rCenterBtn.contains(x, y)) return Target(Kind.CENTER)
        if (rLock.contains(x, y)) return Target(Kind.LOCK)
        if (rPage.contains(x, y)) return Target(Kind.PAGE)
        if (rSetup.contains(x, y)) return Target(Kind.SETUP)
        if (!boxMode && rSteerBar.contains(x, y)) return Target(Kind.STEERBAR)
        for (t in tiles) if (t.pageIdx == page && t.rect.contains(x, y)) {
            return Target(if (t.item.type == LItem.TYPE_BUTTON) Kind.BUTTON else Kind.AXIS, t)
        }
        return null
    }

    // ------------------------------------------------------------------ layout editor
    var editing = false
        private set
    var onEditTile: (page: LPage, item: LItem, isNew: Boolean) -> Unit = { _, _, _ -> }
    var onEditOptions: () -> Unit = {}
    var onEditDone: () -> Unit = {}
    private var dragTile: Tile? = null
    private var dragDX = 0f
    private var dragDY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var dragMoved = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    fun startEditing() {
        releaseAll()
        editing = true
        requestFrame()
    }

    fun stopEditing() {
        editing = false
        dragTile = null
        saveLayout()
        relayout()
    }

    fun setPage(i: Int) { page = i.coerceIn(0, layout.pages.size - 1); requestFrame() }

    /** Called after a tile was edited / added / deleted from the dialog. */
    fun layoutChanged() {
        saveLayout()
        relayout()
    }

    private fun editHitTest(x: Float, y: Float): Target? {
        if (rCenterBtn.contains(x, y) || rLock.contains(x, y)) {
            // left part of the top bar: ◀ prev page  /  ▶ next page
            val mid = (rCenterBtn.left + (if (rLock.isEmpty) rCenterBtn.right else rLock.right)) / 2
            return Target(if (x < mid) Kind.E_PREV else Kind.E_NEXT)
        }
        if (rPage.contains(x, y)) return Target(Kind.E_OPTIONS)
        if (rSetup.contains(x, y)) return Target(Kind.E_DONE)
        for (t in tiles) if (t.pageIdx == page && t.rect.contains(x, y)) return Target(Kind.E_TILE, t)
        if (rGrid.contains(x, y)) {
            val gap = 6 * dp
            val col = ((x - rGrid.left) / (cellW + gap)).toInt().coerceIn(0, layout.cols - 1)
            val row = ((y - rGrid.top) / (cellH + gap)).toInt().coerceIn(0, layout.rows - 1)
            return Target(Kind.E_EMPTY, row = row, col = col)
        }
        return null
    }

    private fun editTouch(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val t = editHitTest(x, y) ?: return true
                when (t.kind) {
                    Kind.E_PREV -> { setPage((page - 1 + layout.pages.size) % layout.pages.size); onHaptic(10) }
                    Kind.E_NEXT -> { setPage((page + 1) % layout.pages.size); onHaptic(10) }
                    Kind.E_OPTIONS -> onEditOptions()
                    Kind.E_DONE -> { stopEditing(); onEditDone() }
                    Kind.E_TILE -> {
                        dragTile = t.tile; dragMoved = false
                        dragStartX = x; dragStartY = y; dragX = x; dragY = y
                        dragDX = x - t.tile!!.rect.left; dragDY = y - t.tile.rect.top
                    }
                    Kind.E_EMPTY -> {
                        val pg = layout.pages[page]
                        val it = Catalog.item(3, t.row, t.col)
                        onEditTile(pg, it, true)
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> dragTile?.let {
                dragX = x; dragY = y
                if (kotlin.math.hypot(x - dragStartX, y - dragStartY) > 12 * dp) dragMoved = true
                requestFrame()
            }
            MotionEvent.ACTION_UP -> {
                val d = dragTile ?: return true
                dragTile = null
                val pg = layout.pages[page]
                if (!dragMoved) {
                    onEditTile(pg, d.item, false)
                } else {
                    // snap: target cell under the tile's top-left corner
                    val gap = 6 * dp
                    val left = x - dragDX + cellW / 2
                    val top = y - dragDY + cellH / 2
                    val col = ((left - rGrid.left) / (cellW + gap)).toInt().coerceIn(0, layout.cols - d.item.cs)
                    val row = ((top - rGrid.top) / (cellH + gap)).toInt().coerceIn(0, layout.rows - d.item.rs)
                    val it = d.item
                    if (layout.fits(pg, row, col, it.rs, it.cs, it)) {
                        it.row = row; it.col = col
                    } else {
                        // swap with the tile that's there, if each fits in the other's place
                        val other = layout.itemAt(pg, row, col)
                        if (other != null && other !== it) {
                            val or = other.row; val oc = other.col
                            other.row = it.row; other.col = it.col
                            val r0 = it.row; val c0 = it.col
                            it.row = or; it.col = oc
                            if (!layout.fits(pg, it.row, it.col, it.rs, it.cs, it) || !layout.fits(pg, other.row, other.col, other.rs, other.cs, other)) {
                                other.row = or; other.col = oc; it.row = r0; it.col = c0   // didn't fit: undo
                            }
                        }
                    }
                    onHaptic(15)
                    layoutChanged()
                }
                requestFrame()
            }
            MotionEvent.ACTION_CANCEL -> { dragTile = null; requestFrame() }
        }
        return true
    }

    private fun drawEditOverlay(c: Canvas) {
        // empty cells
        val gap = 6 * dp
        stroke.color = 0x55FFFFFF
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
        val pg = layout.pages[page]
        for (r in 0 until layout.rows) for (k in 0 until layout.cols) {
            if (layout.itemAt(pg, r, k) != null) continue
            val l = rGrid.left + k * (cellW + gap)
            val t = rGrid.top + r * (cellH + gap)
            tmp.set(l, t, l + cellW, t + cellH)
            c.drawRoundRect(tmp, 10 * dp, 10 * dp, stroke)
            text.color = 0x66FFFFFF; text.textSize = 22 * dp
            c.drawText("+", tmp.centerX(), tmp.centerY() + 8 * dp, text)
        }
        stroke.pathEffect = null
        // dragged tile follows the finger
        dragTile?.let { d ->
            if (dragMoved) {
                val w = d.rect.width(); val h = d.rect.height()
                tmp.set(dragX - dragDX, dragY - dragDY, dragX - dragDX + w, dragY - dragDY + h)
                fill.color = 0xCC2A2F37.toInt()
                c.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
                stroke.color = yellow; stroke.strokeWidth = 3 * dp
                c.drawRoundRect(tmp, 10 * dp, 10 * dp, stroke)
                stroke.strokeWidth = 1 * dp
                text.color = Color.WHITE; text.textSize = 16 * dp
                c.drawText(d.item.label, tmp.centerX(), tmp.centerY() + 6 * dp, text)
            }
        }
        // hint
        text.textSize = 12 * dp; text.color = yellow; text.typeface = condensed
        c.drawText("EDITING: drag to move/swap · tap a tile to edit · tap + to add", rSteerBar.centerX(), rSteerBar.centerY() + 4 * dp, text)
        text.typeface = condensedBold
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editing) return editTouch(ev)
        val action = ev.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) clearStale()   // first finger: nothing can still be held
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                pointers.remove(id)?.let { release(it, id) }            // id reused before we saw it lift
                val t = hitTest(ev.getX(i), ev.getY(i))
                if (t != null) {
                    lastDownSize = ev.getTouchMajor(i)
                    pointers[id] = t
                    press(t, id, ev.getX(i), ev.getY(i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val t = pointers[id] ?: continue
                    when (t.kind) {
                        Kind.BRAKE -> if (id == brakeOwner) { st.brkIn = brakeValue(ev.getY(i), ev.getTouchMajor(i)); changed() }
                        Kind.THROTTLE -> if (id == throttleOwner) { st.thr = pedalValue(rThrottle, ev.getY(i), throttleOriginY, prefs.thrCurve); changed() }
                        Kind.STEERBAR -> if (id == steerOwner) setManualSteer(ev.getX(i))
                        Kind.AXIS -> setAxis(t.tile!!, ev.getY(i))
                        else -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                pointers.remove(id)?.let { release(it, id) }
                if (action == MotionEvent.ACTION_UP) clearStale()      // last finger lifted: release everything
            }
            // Android took the touch away (edge gesture, palm rejection): lift the fingers,
            // but keep a latched look button - it was switched on deliberately
            MotionEvent.ACTION_CANCEL -> releaseAll(keepLatch = true)
        }
        // safety net: forget any finger Android no longer reports
        if (action != MotionEvent.ACTION_CANCEL && pointers.isNotEmpty()) {
            val alive = HashSet<Int>()
            val lifting = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) ev.getPointerId(ev.actionIndex) else -1
            for (i in 0 until ev.pointerCount) {
                val pid = ev.getPointerId(i)
                if (pid != lifting) alive.add(pid)
            }
            val gone = pointers.keys.filter { it !in alive }
            for (pid in gone) pointers.remove(pid)?.let { release(it, pid) }
        }
        updateButtonMask()
        return true
    }

    /** Releases pedals/buttons left over from touches Android never finished. Latched look buttons stay. */
    private fun clearStale() {
        for ((pid, t) in pointers.entries.toList()) release(t, pid)
        pointers.clear()
        if (brakeOwner != -1) { brakeOwner = -1; st.brkIn = 0f; detentZone = 0 }
        if (throttleOwner != -1) { throttleOwner = -1; st.thr = 0f }
        updateButtonMask()
    }

    /** Button state is rebuilt from the fingers currently on the screen, so it can never get stuck. */
    private fun updateButtonMask() {
        var held = 0
        for (t in pointers.values) {
            when (t.kind) {
                Kind.PADDLE_UP -> held = held or 1
                Kind.PADDLE_DOWN -> held = held or 2
                Kind.BUTTON -> { val n = t.tile!!.item.num; if (!isLatch(n)) held = held or (1 shl (n - 1)) }
                else -> {}
            }
        }
        val m = held or latchMask
        if (m != st.mask) { st.mask = m; changed() }
    }

    private fun press(t: Target, id: Int, x: Float, y: Float) {
        when (t.kind) {
            Kind.BRAKE -> { brakeOwner = id; brakeOriginY = y; st.brkIn = brakeValue(y, lastDownSize); changed() }
            Kind.THROTTLE -> { throttleOwner = id; throttleOriginY = y; st.thr = pedalValue(rThrottle, y, y, prefs.thrCurve); changed() }
            Kind.PADDLE_UP -> pressButton(1, 25)
            Kind.PADDLE_DOWN -> pressButton(2, 25)
            Kind.BUTTON -> pressButton(t.tile!!.item.num, 15)
            Kind.AXIS -> { setAxis(t.tile!!, y); onHaptic(10) }
            Kind.CENTER -> { onCenter(); onHaptic(30) }
            Kind.LOCK -> { locked = !locked; onHaptic(20); onCenterLockChanged() }
            Kind.PAGE -> { page = (page + 1) % layout.pages.size; onHaptic(10); requestFrame() }
            Kind.SETUP -> { releaseAll(); onSettings() }
            Kind.STEERBAR -> { steerOwner = id; setManualSteer(x) }
            else -> {}
        }
    }

    private fun release(t: Target, id: Int) {
        when (t.kind) {
            Kind.BRAKE -> if (id == brakeOwner) { brakeOwner = -1; st.brkIn = 0f; detentZone = 0; changed() }
            Kind.THROTTLE -> if (id == throttleOwner) { throttleOwner = -1; st.thr = 0f; changed() }
            Kind.PADDLE_UP -> releaseButton(1)
            Kind.PADDLE_DOWN -> releaseButton(2)
            Kind.BUTTON -> releaseButton(t.tile!!.item.num)
            Kind.AXIS -> { if (t.tile!!.item.type == LItem.TYPE_CLUTCH) st.clutch = 0f else st.hbrake = 0f; changed() }
            Kind.STEERBAR -> if (id == steerOwner) { steerOwner = -1; manualSteer = null; onCenterLockChanged() }
            else -> {}
        }
    }

    fun releaseAll(keepLatch: Boolean = false) {
        pointers.clear()
        brakeOwner = -1; throttleOwner = -1; steerOwner = -1
        manualSteer = null
        if (!keepLatch) latchMask = 0
        st.thr = 0f; st.brkIn = 0f; st.brk = 0f; st.mask = latchMask; st.clutch = 0f; st.hbrake = 0f
        changed()
    }

    // look left / right / back latch on tap (games only look while the button is held)
    private val lookButtons = intArrayOf(21, 22, 23)
    private var latchMask = 0

    private fun isLatch(num: Int) = prefs.lookToggle && num in lookButtons

    private fun pressButton(num: Int, vib: Long) {
        val bit = 1 shl (num - 1)
        if (isLatch(num)) {
            val wasOn = latchMask and bit != 0
            latchMask = if (wasOn) 0 else bit           // only one look direction at a time
        }
        onHaptic(vib)
        updateButtonMask()
    }

    private fun releaseButton(@Suppress("UNUSED_PARAMETER") num: Int) {
        // nothing to do: the mask is rebuilt from the fingers still on the screen
    }

    /** Clutch / handbrake tile: slide up inside the tile for more. */
    private fun setAxis(t: Tile, y: Float) {
        val r = t.rect
        val v = ((r.bottom - y) / r.height() * 1.15f - 0.05f).coerceIn(0f, 1f)
        if (t.item.type == LItem.TYPE_CLUTCH) st.clutch = v else st.hbrake = v
        changed()
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
            diag.touchMin = sizeMin; diag.touchMax = sizeMax
        }
        // only blend once the screen has shown it really reports contact size
        if (w > 0f && sizeMin > 0f && sizeMax > sizeMin * 1.15f) {
            val p = ((size - sizeMin) / (sizeMax - sizeMin)).coerceIn(0f, 1f)
            v = v * (1f - w) + p * w
        }
        val out = v.coerceIn(0f, 1f).pow(prefs.brkCurve)
        checkDetent(out)
        return out
    }

    // --- brake detents: a tick each time the brake passes 25 / 50 / 75 % (or the chosen spacing)
    private var detentZone = 0

    private fun checkDetent(v: Float) {
        val n = prefs.brakeDetents
        if (n <= 0) { detentZone = 0; return }
        val step = 1f / (n + 1)
        // hysteresis: only change zone once clearly past a notch, so a resting thumb doesn't chatter
        val lower = detentZone * step - 0.02f
        val upper = (detentZone + 1) * step + 0.02f
        if (v in lower..upper && detentZone in 0..n) return
        val z = (v / step).toInt().coerceIn(0, n)
        if (z != detentZone) {
            detentZone = z
            onDetent()
        }
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
        requestFrame()
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        framePending = false
        lastFrameT = android.os.SystemClock.elapsedRealtime()
        diag.frames++
        fill.shader = bgShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        if (!boxMode) {
            drawPedal(c, rBrake, st.brk, "BRAKE", red, -90f)
            if (prefs.brakeTrace) drawBrakeTrace(c)
            drawPedal(c, rThrottle, st.thr, "THROTTLE", green, 90f)
            drawPaddle(c, rPadUp, "▲", "UP", st.mask and 1 != 0)
            drawPaddle(c, rPadDown, "▼", "DOWN", st.mask and 2 != 0)
        }
        val pageName = layout.pages.getOrNull(page)?.name ?: ""
        val pageLabel = if (layout.pages.size > 1) "$pageName ▸" else pageName
        if (editing) {
            if (rLock.isEmpty) {
                drawTopButton(c, rCenterBtn, "◀   PAGE   ▶", false)
            } else {
                tmp2.set(rCenterBtn.left, rCenterBtn.top, rLock.right, rCenterBtn.bottom)
                drawTopButton(c, tmp2, "◀   PAGE ${page + 1}/${layout.pages.size}   ▶", false)
            }
            drawTopButton(c, rPage, "OPTIONS", false)
            drawTopButton(c, rSetup, "DONE", true, green)
        } else {
            if (!boxMode) drawTopButton(c, rCenterBtn, "CENTER", false) else drawTopButton(c, rCenterBtn, "BUTTON BOX", false)
            if (!rLock.isEmpty) drawTopButton(c, rLock, "LOCK", locked, yellow)
            drawTopButton(c, rPage, pageLabel, false, 0xFF3A414B.toInt(), Color.WHITE)
            drawTopButton(c, rSetup, "SETUP", false)
        }
        drawSteerBar(c)

        for (t in tiles) if (t.pageIdx == page && t !== dragTile.takeIf { dragMoved }) drawTile(c, t)
        if (editing) drawEditOverlay(c)
        if (!rDash.isEmpty) drawDash(c)
        drawFlashes(c)
        if (st.bindAxis != 0) drawBindBanner(c)
        scheduleAnimation()
    }

    /** Keeps redrawing only while something on screen is moving or blinking. */
    private fun scheduleAnimation() {
        val now = android.os.SystemClock.elapsedRealtime()
        val dashLive = tel.dashFresh(now)
        val shift = if (tel.shiftRpm > 0) tel.shiftRpm else tel.maxRpm
        val animating =
            st.bindAxis != 0 || tester() != null ||
            traceActive || (traceN > 1 && prefs.brakeTrace && now - traceEndAt < 6000) ||
            (flashMarkerAt != 0L && now - flashMarkerAt < 320) ||
            (flashCoachAt != 0L && now - flashCoachAt < 520) ||
            (dashLive && (tel.pitLimiter || (shift > 0 && tel.rpm >= shift))) ||
            (tel.vibeFresh(now) && (tel.vAbs > 0.15f || tel.vSlip > 0.3f || tel.vUnder > 0.2f || tel.vOver > 0.2f)) ||
            st.brk != st.brkIn
        if (animating) {
            requestFrame()
        } else if (tel.coachAt != 0L && now - tel.coachAt < 4000) {
            postInvalidateDelayed(4000 - (now - tel.coachAt) + 20)   // hide the coach note on time
        }
    }

    // ------------------------------------------------------------------ visual feedback
    // Big, bright cues meant to be caught in peripheral vision while you look at the monitor.
    private var flashMarkerSeq = -1
    private var flashMarkerAt = 0L
    private var flashCoachAt = 0L

    private fun drawFlashes(c: Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        val bright = prefs.flashBright.coerceIn(0.1f, 1f)
        tester()?.let { p -> drawTestFlash(c, p, now, bright); return }
        val fresh = tel.vibeFresh(now)

        // brake marker: two full-screen edge flashes, in time with the ticks
        if (flashMarkerSeq >= 0 && tel.markerSeq != flashMarkerSeq) flashMarkerAt = now
        flashMarkerSeq = tel.markerSeq
        val mt = now - flashMarkerAt
        if (flashMarkerAt != 0L && Prefs.flashes(prefs.fbMarker) && (mt in 0..110 || mt in 170..280)) {
            fill.color = Color.argb((90 * bright).toInt(), 255, 255, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            edge(c, 0xFFFFFFFF.toInt(), 18 * dp, bright)
            return
        }

        // shift point: whole edge blinks blue
        if (prefs.flashShift && tel.dashFresh(now)) {
            val shift = if (tel.shiftRpm > 0) tel.shiftRpm else tel.maxRpm
            if (shift > 0 && tel.rpm >= shift && tel.gear > 0 && (now / 70) % 2 == 0L) edge(c, blue, 14 * dp, bright)
        }

        // ABS / lock-up: brake strip pulses orange (only while braking, like the vibration)
        if (fresh && Prefs.flashes(prefs.fbAbs) && st.brk > 0.08f && tel.vAbs > 0.15f && (now / 60) % 2 == 0L) {
            glow(c, rBrake, orange, bright)
        }
        // oversteer: both side edges flash red (the rear is going)
        if (fresh && Prefs.flashes(prefs.fbOver) && tel.vOver > 0.2f && (now / 50) % 2 == 0L) {
            fill.color = red
            fill.alpha = (255 * bright).toInt()
            val t = 16 * dp
            c.drawRect(0f, 0f, t, height.toFloat(), fill)
            c.drawRect(width - t, 0f, width.toFloat(), height.toFloat(), fill)
            fill.alpha = 255
        }
        // understeer: top edge glows blue, stronger as the front slides more
        if (fresh && Prefs.flashes(prefs.fbUnder) && tel.vUnder > 0.2f) {
            fill.color = blue
            fill.alpha = (255 * bright * (0.4f + 0.6f * tel.vUnder)).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, width.toFloat(), 14 * dp, fill)
            fill.alpha = 255
        }
        // wheelspin: throttle strip glows yellow
        if (fresh && Prefs.flashes(prefs.fbSlip) && st.brk <= 0.08f && tel.vSlip > 0.3f) {
            glow(c, rThrottle, yellow, bright * (0.5f + 0.5f * tel.vSlip.coerceIn(0f, 1f)))
        }

        // coach message: short green / red / blue glow around the screen
        if (prefs.flashCoach && prefs.coachMessages && tel.coachAt != 0L && tel.coachAt != flashCoachAt) {
            flashCoachAt = tel.coachAt
        }
        val ct = now - flashCoachAt
        if (prefs.flashCoach && flashCoachAt != 0L && ct in 0..500) {
            val col = when (tel.coachTone) { "good" -> green; "bad" -> red; else -> blue }
            edge(c, col, 10 * dp, bright * (1f - ct / 500f))
        }
    }

    /** Flash for the effect being tested (only if that effect is set to Flash or Both), plus a banner. */
    private fun drawTestFlash(c: Canvas, p: Haptics.Playing, now: Long, bright: Float) {
        val e = p.effect
        val t = p.t
        val active = t < e.ms
        val mode = e.mode()
        if (active && mode != null && Prefs.flashes(mode)) {
            when (e.id) {
                "marker" -> if (t in 0..110 || t in 170..280) {
                    fill.color = Color.argb((90 * bright).toInt(), 255, 255, 255)
                    c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                    edge(c, 0xFFFFFFFF.toInt(), 18 * dp, bright)
                }
                "abs" -> if ((now / 60) % 2 == 0L) glow(c, rBrake, orange, bright)
                "slip" -> glow(c, rThrottle, yellow, bright * (0.5f + 0.5f * t / e.ms))
                "under" -> {
                    fill.color = blue; fill.alpha = (255 * bright * 0.8f).toInt()
                    c.drawRect(0f, 0f, width.toFloat(), 14 * dp, fill); fill.alpha = 255
                }
                "over" -> if ((now / 50) % 2 == 0L) {
                    fill.color = red; fill.alpha = (255 * bright).toInt()
                    c.drawRect(0f, 0f, 16 * dp, height.toFloat(), fill)
                    c.drawRect(width - 16 * dp, 0f, width.toFloat(), height.toFloat(), fill); fill.alpha = 255
                }
            }
        }
        // banner at the top of the wheel (stays visible above the tester panel)
        val label = (if (p.total > 1) "TESTING ${p.index + 1}/${p.total} · " else "TESTING · ") + e.name.uppercase()
        text.textSize = 16 * dp
        val w = text.measureText(label) + 36 * dp
        tmp.set(width / 2f - w / 2, 20 * dp, width / 2f + w / 2, 54 * dp)
        fill.color = 0xEE000000.toInt()
        c.drawRoundRect(tmp, 12 * dp, 12 * dp, fill)
        stroke.color = yellow
        c.drawRoundRect(tmp, 12 * dp, 12 * dp, stroke)
        text.color = yellow
        c.drawText(label, tmp.centerX(), tmp.centerY() + 6 * dp, text)
    }

    private fun edge(c: Canvas, color: Int, thick: Float, alpha: Float) {
        fill.color = color
        fill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, thick, fill)
        c.drawRect(0f, h - thick, w, h, fill)
        c.drawRect(0f, 0f, thick, h, fill)
        c.drawRect(w - thick, 0f, w, h, fill)
        fill.alpha = 255
    }

    private fun glow(c: Canvas, r: RectF, color: Int, alpha: Float) {
        fill.color = color
        fill.alpha = (150 * alpha).toInt().coerceIn(0, 255)
        c.drawRoundRect(r, 12 * dp, 12 * dp, fill)
        fill.alpha = 255
        stroke.color = color
        stroke.strokeWidth = 4 * dp
        c.drawRoundRect(r, 12 * dp, 12 * dp, stroke)
        stroke.strokeWidth = 1 * dp
    }

    // ------------------------------------------------------------------ brake trace
    // Records each brake application (press -> release) and draws it on the brake strip as a
    // line: time runs left -> right, height = brake amount. Stays visible after you let go
    // and fades out, so you can see your peak and how smoothly you released.
    private val traceT = LongArray(600)
    private val traceV = FloatArray(600)
    private var traceN = 0
    private var traceActive = false
    private var traceEndAt = 0L
    private var traceQuietSince = 0L
    private var tracePeak = 0f

    private fun recordTrace(now: Long) {
        val b = st.brk
        if (!traceActive) {
            if (b > 0.02f) { traceActive = true; traceN = 0; tracePeak = 0f; traceQuietSince = 0L }
            else return
        }
        if (traceN < traceT.size) { traceT[traceN] = now; traceV[traceN] = b; traceN++ }
        tracePeak = max(tracePeak, b)
        if (b <= 0.02f) {
            if (traceQuietSince == 0L) traceQuietSince = now
            if (now - traceQuietSince > 400) { traceActive = false; traceEndAt = now }
        } else {
            traceQuietSince = 0L
        }
    }

    private fun drawBrakeTrace(c: Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        recordTrace(now)
        if (traceN < 2) return
        val alpha = if (traceActive) 1f else (1f - (now - traceEndAt) / 6000f)
        if (alpha <= 0f) return
        val r = rBrake
        val inset = 6 * dp
        val t0 = traceT[0]
        val dur = max(1500L, traceT[traceN - 1] - t0).toFloat()
        val left = r.left + inset
        val w = r.width() - 2 * inset
        stroke.color = Color.argb((alpha * 230).toInt(), 255, 255, 255)
        stroke.strokeWidth = 2.5f * dp
        var px = left
        var py = r.bottom - traceV[0] * r.height()
        for (k in 1 until traceN) {
            val x = left + (traceT[k] - t0) / dur * w
            val y = r.bottom - traceV[k] * r.height()
            c.drawLine(px, py, x, y, stroke)
            px = x; py = y
        }
        stroke.strokeWidth = 1f * dp
        // peak marker
        val py2 = r.bottom - tracePeak * r.height()
        fill.color = Color.argb((alpha * 255).toInt(), 255, 204, 0)
        c.drawRect(r.left, py2 - 1.5f * dp, r.left + 10 * dp, py2 + 1.5f * dp, fill)
        if (!traceActive) {
            text.textSize = 11 * dp
            text.color = Color.argb((alpha * 255).toInt(), 255, 204, 0)
            text.typeface = condensed
            c.drawText("peak ${(tracePeak * 100).roundToInt()}%", r.centerX(), r.bottom - 8 * dp, text)
            text.typeface = condensedBold
        }
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

        // coach message: covers the chips for 4 s
        if (prefs.coachMessages && tel.coachText.isNotEmpty() && now - tel.coachAt < 4000) {
            tmp.set(chipsL, top, chipsR, top + bodyH)
            fill.color = 0xF2101317.toInt()
            c.drawRoundRect(tmp, 6 * dp, 6 * dp, fill)
            val toneColor = when (tel.coachTone) { "good" -> green; "bad" -> orange; else -> blue }
            fill.color = toneColor
            c.drawRoundRect(tmp.left, tmp.top, tmp.left + 4 * dp, tmp.bottom, 2 * dp, 2 * dp, fill)
            drawWrapped(c, tel.coachText, tmp.left + 10 * dp, tmp.top, tmp.right - 4 * dp, tmp.bottom)
        }
    }

    /** Draws text left-aligned, wrapped to fit the box (shrinks the font if needed). */
    private fun drawWrapped(c: Canvas, s: String, l: Float, t: Float, r: Float, b: Float) {
        val maxW = r - l
        var size = min(16 * dp, (b - t) / 2.6f)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        text.typeface = condensedBold
        var lines: List<String>
        while (true) {
            text.textSize = size
            lines = wrap(s, maxW)
            if (lines.size * size * 1.2f <= (b - t) - 4 * dp || size <= 9 * dp) break
            size -= 1 * dp
        }
        val total = lines.size * size * 1.2f
        var y = t + ((b - t) - total) / 2 + size
        for (ln in lines) {
            c.drawText(ln, l, y, text)
            y += size * 1.2f
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in s.split(" ")) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (text.measureText(trial) <= maxW || cur.isEmpty()) cur = trial
            else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun drawBindBanner(c: Canvas) {
        val name = when (st.bindAxis) { 1 -> "STEERING"; 2 -> "THROTTLE"; 3 -> "BRAKE"; 4 -> "CLUTCH"; else -> "HANDBRAKE" }
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

    private val tmp2 = RectF()

    private fun drawTile(c: Canvas, t: Tile) {
        val it = t.item
        if (it.type == LItem.TYPE_BUTTON) { drawButton(c, t); return }
        // analog lever tile (clutch / handbrake): fills from the bottom like a pedal
        val r = t.rect
        val v = if (it.type == LItem.TYPE_CLUTCH) st.clutch else st.hbrake
        val rad = 10 * dp
        fill.color = 0xFF14181D.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        if (v > 0f) {
            c.save(); c.clipRect(r.left, r.bottom - r.height() * v, r.right, r.bottom)
            fill.color = it.color; c.drawRoundRect(r, rad, rad, fill); c.restore()
        }
        stroke.color = it.color
        c.drawRoundRect(r, rad, rad, stroke)
        text.textSize = min(16 * dp, r.width() / 6f).coerceAtLeast(10 * dp)
        text.color = Color.WHITE
        c.drawText(it.label, r.centerX(), r.centerY(), text)
        text.typeface = condensed; text.textSize *= 0.7f; text.color = 0xFF8A94A3.toInt()
        c.drawText(if (v > 0f) "${(v * 100).roundToInt()}%" else it.sub, r.centerX(), r.centerY() + text.textSize * 1.6f, text)
        text.typeface = condensedBold
    }

    private fun drawButton(c: Canvas, tile: Tile) {
        val b = tile.item
        val r = tile.rect
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
        if (prefs.showNums || editing) {
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
