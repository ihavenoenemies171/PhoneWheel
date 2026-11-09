package com.phonewheel.app

import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The phone's stand-in for force feedback. Turns game effects (from the PC) and button clicks
 * into one vibration signal, updated 100 times a second.
 *
 * ESSENTIAL preset (default) is built to inform, not overwhelm:
 *  - one effect at a time, by priority:
 *    impact > ABS/lock-up > oversteer > understeer > wheelspin/slide > kerb hit > off-track
 *  - context: lock-up only while braking, wheelspin only on throttle/coasting
 *  - events: kerbs and off-track are short bursts, not constant buzzing
 *  - adaptation: a slide that keeps going fades after ~1 s
 *  - thresholds: small amounts of slip are ignored
 * IMMERSIVE layers every effect at once, without priorities.
 *
 * Every effect has its own RHYTHM, so you can tell them apart without thinking:
 *   ABS / lock-up  fast even pulses (30 ms on / 30 ms off), the pedal kicking back
 *   Understeer     soft smooth wobble (4 Hz swell, never sharp): the steering going light
 *   Oversteer      strong "ba-dum ... ba-dum" heartbeat: alarm, the rear is going
 *   Wheelspin      steady unbroken rumble that grows with the spin
 *   Kerb           three distinct knocks: "da-da-da", like a rumble strip
 *   Off track      irregular random crackle: gravel / grass
 *   Impact         one heavy thud that dies away
 *   Brake marker   two tiny crisp ticks: "tick-tick"
 *   Detent         one tiny tick
 *   Paddle click   short firm clunk;  button click: light tap
 * (Gear shift, rev limiter and road texture were removed: the paddle click and the shift-light
 *  flash already say the same thing, and constant road grain only hides the signals that matter.)
 */
class Haptics(
    private val vibrator: Vibrator?,
    private val prefs: Prefs,
    private val tel: Telem,
    private val st: InputState,
) {
    private val hasVibrator = vibrator?.hasVibrator() == true
    private val hasAmplitude = hasVibrator && vibrator?.hasAmplitudeControl() == true

    @Volatile private var running = false
    private var worker: Thread? = null

    // button clicks
    @Volatile private var pulseUntil = 0L
    @Volatile private var pulseAmp = 0f

    // events
    private var lastImpactSeq = -1
    private var lastMarkerSeq = -1
    private var markerAt = 0L
    private var impactUntil = 0L
    private var impactAmp = 0f

    // essential-mode state
    private var slipSince = 0L
    private var slipQuietSince = 0L
    private var kerbOn = false
    private var kerbLastHit = 0L
    private var kerbAt = 0L
    private var offOn = false
    private var offLastBurst = 0L
    private var offAt = 0L

    // motor
    private var lastIssued = 0f
    private var lastIssueT = 0L
    private var on = false

    val supported get() = hasVibrator
    val amplitudeControl get() = hasAmplitude

    fun start() {
        if (running || !hasVibrator) return
        running = true
        worker = thread(name = "pw-haptics", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            while (running) {
                var busy = true
                try { busy = tick() } catch (_: Exception) {}
                // 100 Hz while effects can play; otherwise doze until a click or game data wakes us
                synchronized(wake) {
                    try { wake.wait(if (busy) 10L else 100L) } catch (_: InterruptedException) {}
                }
            }
            stopMotor()
        }
    }

    fun stop() {
        running = false
        try { worker?.join(200) } catch (_: InterruptedException) {}
        worker = null
        stopMotor()
    }

    private val wake = Object()
    private fun wakeUp() = synchronized(wake) { wake.notifyAll() }

    /** Short click for buttons and paddles. */
    fun pulse(ms: Long, strength: Float = 1f) {
        if (!prefs.hapticsOn) return
        pulseAmp = (strength * prefs.hButtons).coerceIn(0f, 1f)
        pulseUntil = SystemClock.elapsedRealtime() + ms
        wakeUp()
    }

    /** Brake detent: a crisp notch you feel in the thumb. */
    fun detent() {
        if (!prefs.hapticsOn || prefs.hDetent <= 0f) return
        pulseAmp = prefs.hDetent.coerceIn(0f, 1f)
        pulseUntil = SystemClock.elapsedRealtime() + 12
        wakeUp()
    }

    // ------------------------------------------------------------------ effect tester
    /** One testable effect. [mode] = its Vibrate/Flash/Both setting (null = vibration only). */
    class Effect(
        val id: String, val name: String, val desc: String, val ms: Long,
        val mode: () -> Int?, val amp: (t: Long, now: Long) -> Float,
    )

    val effects: List<Effect> = listOf(
        Effect("abs", "ABS / lock-up", "Fast even pulses, like the brake pedal kicking back. Ease off the brake.", 1200, { prefs.fbAbs }) { t, _ -> prefs.hAbs * absP(t) },
        Effect("under", "Understeer", "Soft smooth wobble: the steering going light. The front has no grip, so slow down or unwind the wheel.", 1500, { prefs.fbUnder }) { t, _ -> prefs.hUnder * 0.8f * underP(t) },
        Effect("over", "Oversteer", "Strong heartbeat \"ba-dum, ba-dum\": the rear is stepping out. Catch it.", 1300, { prefs.fbOver }) { t, _ -> prefs.hOver * overP(t) },
        Effect("slip", "Wheelspin / slide", "Steady unbroken rumble that grows with the spin. Feed the throttle in more gently.", 1200, { prefs.fbSlip }) { t, _ -> prefs.hSlip * (0.45f + 0.55f * (t / 1200f)) },
        Effect("kerb", "Kerbs", "Three distinct knocks \"da-da-da\" each time you hit a kerb (shown twice)", 1100, { null }) { t, _ -> prefs.hKerb * (kerbP(t) + kerbP(t - 700)) },
        Effect("off", "Off track", "Irregular crackle like gravel, with a reminder every 2 s while you're still off", 450, { null }) { t, n -> if (t < OFF_MS) prefs.hOff * offP(n) else 0f },
        Effect("impact", "Impact", "One heavy thud that dies away", 400, { null }) { t, _ -> prefs.hImpact * impactP(t) },
        Effect("marker", "Brake marker", "Two tiny crisp ticks \"tick-tick\" just before your best lap's braking point", 450, { prefs.fbMarker }) { t, _ -> prefs.hMarker * markerP(t) },
        Effect("detent", "Brake detents", "One tiny tick at each of 25 / 50 / 75 % brake", 750, { null }) { t, _ -> if (t in 0..11 || t in 300..311 || t in 600..611) prefs.hDetent else 0f },
    )

    private val GAP = 450L
    @Volatile private var queue: List<Effect> = emptyList()
    @Volatile private var queueStart = 0L

    /** Plays every effect in order. */
    fun playAll() { queue = effects; queueStart = SystemClock.elapsedRealtime(); wakeUp() }

    /** Plays one effect. */
    fun play(id: String) {
        val e = effects.firstOrNull { it.id == id } ?: return
        queue = listOf(e); queueStart = SystemClock.elapsedRealtime(); wakeUp()
    }

    fun stopTest() { queue = emptyList() }

    /** What the tester is doing right now: (effect, ms into it, index, total) or null when idle. */
    data class Playing(val effect: Effect, val t: Long, val index: Int, val total: Int)

    fun playing(now: Long = SystemClock.elapsedRealtime()): Playing? {
        val q = queue
        if (q.isEmpty()) return null
        var t = now - queueStart
        for ((i, e) in q.withIndex()) {
            if (t < e.ms) return Playing(e, t, i, q.size)
            t -= e.ms + GAP
            if (t < 0) return Playing(e, e.ms, i, q.size)   // in the gap after this effect
        }
        queue = emptyList()
        return null
    }

    // ------------------------------------------------------------------ patterns (0..1, t = ms)
    private fun absP(t: Long) = if ((t / 30) % 2 == 0L) 1f else 0f                         // ~17 Hz even pulses
    private fun underP(t: Long) = 0.45f + 0.55f * (0.5f + 0.5f * sin(2.0 * PI * t / 250.0).toFloat())  // 4 Hz soft swell
    private fun overP(t: Long): Float { val p = t % 280; return if (p < 40 || p in 85 until 125) 1f else 0f }  // ba-dum
    private fun kerbP(t: Long) = if (t in 0 until KERB_MS && t % 100 < 32) 1f else 0f          // da-da-da
    private fun impactP(t: Long) = if (t in 0 until IMPACT_MS) 1f - 0.75f * t / IMPACT_MS else 0f  // thud, decaying
    private fun markerP(t: Long) = if (t in 0..24 || t in 100..124) 1f else 0f                 // tick-tick
    private fun offP(now: Long): Float {                                                       // random crackle
        val slot = now / 20
        val r = (((slot * 1103515245L + 12345L) ushr 9) and 0xFF).toFloat() / 255f
        return if (r > 0.35f) 0.35f + 0.65f * r else 0f
    }
    private val KERB_MS = 300L
    private val OFF_MS = 400L
    private val IMPACT_MS = 220L

    /** Returns true while anything could be vibrating (keeps the fast 100 Hz loop). */
    private fun tick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        var amp = if (now < pulseUntil) pulseAmp else 0f

        // brake marker: two crisp ticks ("tick-tick, brake!"), above every other effect
        if (lastMarkerSeq >= 0 && tel.markerSeq != lastMarkerSeq) markerAt = now
        lastMarkerSeq = tel.markerSeq
        val mt = now - markerAt
        if (markerAt != 0L && prefs.hapticsOn && Prefs.vibrates(prefs.fbMarker) && markerP(mt) > 0f) {
            drive(prefs.hMarker.coerceIn(0f, 1f), now)
            return true
        }

        // event edges (always tracked so nothing fires late after switching presets)
        val fresh = tel.vibeFresh(now)
        if (fresh) {
            if (lastImpactSeq >= 0 && tel.impactSeq != lastImpactSeq) {
                impactUntil = now + IMPACT_MS
                impactAmp = 0.6f + 0.4f * tel.impactMag.coerceIn(0f, 1f)
            }
            lastImpactSeq = tel.impactSeq
        }

        val demo = playing(now)
        val game = when {
            !prefs.hapticsOn -> 0f
            demo != null -> if (demo.t < demo.effect.ms && Prefs.vibrates(demo.effect.mode() ?: Prefs.FB_VIBRATE))
                                demo.effect.amp(demo.t, now) else 0f
            !fresh || prefs.hPreset == Prefs.PRESET_OFF -> 0f
            prefs.hPreset == Prefs.PRESET_IMMERSIVE -> immersive(now)
            else -> essential(now)
        }
        amp = max(amp, game * prefs.hMaster)
        drive(amp.coerceIn(0f, 1f), now)
        return on || fresh || now < pulseUntil || demo != null || mt in 0..200
    }

    // ------------------------------------------------------------------ ESSENTIAL
    private fun essential(now: Long): Float {
        val braking = st.brk > 0.08f
        val onThrottle = !braking && st.thr > 0.15f

        // keep event state up to date even when a higher priority effect wins
        val kerbHitNow = tel.vKerb > 0.15f && !kerbOn && now - kerbLastHit > 500
        if (kerbHitNow) { kerbLastHit = now; kerbAt = now }
        kerbOn = tel.vKerb > 0.1f
        if (tel.vOff > 0.2f) {
            if (!offOn || now - offLastBurst > 2000) { offLastBurst = now; offAt = now }
            offOn = true
        } else {
            offOn = false
        }

        // 1. impact
        if (now < impactUntil) return prefs.hImpact * impactAmp * impactP(now - (impactUntil - IMPACT_MS))

        // 2. ABS / lock-up, only meaningful while braking
        if (braking && tel.vAbs > 0.15f && Prefs.vibrates(prefs.fbAbs)) {
            return prefs.hAbs * (0.65f + 0.35f * tel.vAbs) * absP(now)
        }

        // 3. oversteer: strong "ba-dum" heartbeat - the rear is going
        if (tel.vOver > 0.2f && Prefs.vibrates(prefs.fbOver)) {
            return prefs.hOver * (0.7f + 0.3f * tel.vOver) * overP(now)
        }
        // 4. understeer: soft smooth wobble that grows - the front has run out of grip ("steering goes light")
        if (tel.vUnder > 0.2f && Prefs.vibrates(prefs.fbUnder)) {
            return prefs.hUnder * (0.35f + 0.5f * tel.vUnder) * underP(now)
        }

        // 5. wheelspin / slide, only off the brake; fades if it just keeps going
        val slipThr = if (onThrottle) 0.25f else 0.35f
        if (!braking && tel.vSlip > slipThr && Prefs.vibrates(prefs.fbSlip)) {
            if (slipSince == 0L) slipSince = now
            slipQuietSince = 0L
            val held = now - slipSince
            val fade = if (held < 1200) 1f else max(0.35f, 1f - (held - 1200) / 1000f * 0.65f)
            val strength = 0.45f + 0.55f * ((tel.vSlip - slipThr) / (1f - slipThr)).coerceIn(0f, 1f)
            return prefs.hSlip * strength * fade
        } else if (slipSince != 0L) {
            if (slipQuietSince == 0L) slipQuietSince = now
            if (now - slipQuietSince > 300) slipSince = 0L
        }

        // 6. kerb: "da-da-da" as you hit it
        if (kerbP(now - kerbAt) > 0f && kerbAt != 0L) return prefs.hKerb
        if (now - kerbAt < KERB_MS) return 0f      // keep the gaps between knocks clean

        // 7. off-track: crackle when you leave the track, reminder every 2 s
        if (offAt != 0L && now - offAt < OFF_MS) return prefs.hOff * offP(now)

        return 0f
    }

    // ------------------------------------------------------------------ IMMERSIVE
    private fun immersive(now: Long): Float {
        var a = 0f
        if (tel.vAbs > 0.04f && Prefs.vibrates(prefs.fbAbs)) a = max(a, prefs.hAbs * (0.55f + 0.45f * tel.vAbs) * absP(now))
        if (tel.vKerb > 0.04f) a = max(a, prefs.hKerb * tel.vKerb * (if (now % 100 < 32) 1f else 0f))
        if (tel.vSlip > 0.04f && Prefs.vibrates(prefs.fbSlip)) a = max(a, prefs.hSlip * tel.vSlip * 0.9f)
        if (tel.vOver > 0.1f && Prefs.vibrates(prefs.fbOver)) a = max(a, prefs.hOver * (0.6f + 0.4f * tel.vOver) * overP(now))
        if (tel.vUnder > 0.1f && Prefs.vibrates(prefs.fbUnder)) a = max(a, prefs.hUnder * (0.35f + 0.5f * tel.vUnder) * underP(now))
        if (tel.vOff > 0.04f) a = max(a, prefs.hOff * tel.vOff * offP(now))
        if (now < impactUntil) a = max(a, prefs.hImpact * impactAmp * impactP(now - (impactUntil - IMPACT_MS)))
        return a
    }

    // ------------------------------------------------------------------ motor
    private fun drive(amp: Float, now: Long) {
        val v = vibrator ?: return
        if (amp < 0.04f) {
            if (on) stopMotor()
            return
        }
        if (!hasAmplitude) {
            // on/off motor: fake strength with duty cycle
            if (Random.nextFloat() > amp + 0.15f) {
                if (on) stopMotor()
                return
            }
        }
        if (on && kotlin.math.abs(amp - lastIssued) < 0.08f && now - lastIssueT < 20) return
        val a = if (hasAmplitude) (amp * 255f).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        try {
            v.vibrate(VibrationEffect.createOneShot(30, a))
            on = true
            lastIssued = amp
            lastIssueT = now
        } catch (_: Exception) {}
    }

    private fun stopMotor() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        on = false
        lastIssued = 0f
    }
}
