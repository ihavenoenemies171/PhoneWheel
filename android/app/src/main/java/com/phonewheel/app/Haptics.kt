package com.phonewheel.app

import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.max
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
 * IMMERSIVE layers everything (road texture, shifts, rev limiter too).
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
    @Volatile private var testStart = 0L

    // events
    private var lastImpactSeq = -1
    private var lastShiftSeq = -1
    private var lastMarkerSeq = -1
    private var markerAt = 0L
    private var impactUntil = 0L
    private var impactAmp = 0f
    private var shiftUntil = 0L

    // essential-mode state
    private var slipSince = 0L
    private var slipQuietSince = 0L
    private var kerbOn = false
    private var kerbLastHit = 0L
    private var kerbBurstUntil = 0L
    private var offOn = false
    private var offLastBurst = 0L
    private var offBurstUntil = 0L

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
        pulseUntil = SystemClock.elapsedRealtime() + 14
        wakeUp()
    }

    /** Plays each effect once, in order, so you can learn how they feel. */
    fun test() {
        testStart = SystemClock.elapsedRealtime()
        wakeUp()
    }

    // ------------------------------------------------------------------ patterns
    private fun absPulse(now: Long) = (now / 30) % 2 == 0L                 // ~16 Hz kick-back
    private fun rattle(now: Long) = if ((now / 20) % 2 == 0L) 1f else 0.25f // rough, fast

    /** Returns true while anything could be vibrating (keeps the fast 100 Hz loop). */
    private fun tick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        var amp = if (now < pulseUntil) pulseAmp else 0f

        // brake marker: two crisp ticks ("tick-tick, brake!"), above every other effect
        if (lastMarkerSeq >= 0 && tel.markerSeq != lastMarkerSeq) markerAt = now
        lastMarkerSeq = tel.markerSeq
        val mt = now - markerAt
        if (markerAt != 0L && prefs.hapticsOn && Prefs.vibrates(prefs.fbMarker) && (mt in 0..30 || mt in 100..130)) {
            drive(prefs.hMarker.coerceIn(0f, 1f), now)
            return true
        }

        // event edges (always tracked so nothing fires late after switching presets)
        val fresh = tel.vibeFresh(now)
        if (fresh) {
            if (lastImpactSeq >= 0 && tel.impactSeq != lastImpactSeq) {
                impactUntil = now + 150
                impactAmp = 0.6f + 0.4f * tel.impactMag.coerceIn(0f, 1f)
            }
            if (lastShiftSeq >= 0 && tel.shiftSeq != lastShiftSeq) shiftUntil = now + 40
            lastImpactSeq = tel.impactSeq
            lastShiftSeq = tel.shiftSeq
        }

        val tt = now - testStart
        val game = when {
            !prefs.hapticsOn -> 0f
            testStart != 0L && tt in 0..7600 -> demo(tt, now)
            !fresh || prefs.hPreset == Prefs.PRESET_OFF -> 0f
            prefs.hPreset == Prefs.PRESET_IMMERSIVE -> immersive(now)
            else -> essential(now)
        }
        amp = max(amp, game * prefs.hMaster)
        drive(amp.coerceIn(0f, 1f), now)
        return on || fresh || now < pulseUntil || (testStart != 0L && tt in 0..7600) || mt in 0..200
    }

    // ------------------------------------------------------------------ ESSENTIAL
    private fun essential(now: Long): Float {
        val braking = st.brk > 0.08f
        val onThrottle = !braking && st.thr > 0.15f

        // keep event state up to date even when a higher priority effect wins
        val kerbHitNow = tel.vKerb > 0.15f && !kerbOn && now - kerbLastHit > 500
        if (kerbHitNow) { kerbLastHit = now; kerbBurstUntil = now + 130 }
        kerbOn = tel.vKerb > 0.1f
        if (tel.vOff > 0.2f) {
            if (!offOn || now - offLastBurst > 2000) { offLastBurst = now; offBurstUntil = now + 250 }
            offOn = true
        } else {
            offOn = false
        }

        // 1. impact
        if (now < impactUntil) return prefs.hImpact * impactAmp

        // 2. ABS / lock-up, only meaningful while braking
        if (braking && tel.vAbs > 0.15f && Prefs.vibrates(prefs.fbAbs)) {
            return if (absPulse(now)) prefs.hAbs * (0.6f + 0.4f * tel.vAbs) else 0f
        }

        // 3. oversteer: sharp, urgent buzz - the rear is going
        if (tel.vOver > 0.2f && Prefs.vibrates(prefs.fbOver)) {
            return prefs.hOver * (0.6f + 0.4f * tel.vOver) * (if ((now / 15) % 2 == 0L) 1f else 0.5f)
        }
        // 4. understeer: soft, slow pulse that grows - the front has run out of grip ("steering goes light")
        if (tel.vUnder > 0.2f && Prefs.vibrates(prefs.fbUnder)) {
            val phase = (now % 160) / 160f                  // ~6 Hz
            return prefs.hUnder * (0.35f + 0.65f * tel.vUnder) * (if (phase < 0.45f) 1f else 0f)
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

        // 4. kerb: short burst as you hit it
        if (now < kerbBurstUntil) return prefs.hKerb * rattle(now)

        // 5. off-track: burst when you leave the track, reminder every 2 s
        if (now < offBurstUntil) return prefs.hOff * (0.6f + 0.4f * rattle(now))

        return 0f
    }

    // ------------------------------------------------------------------ IMMERSIVE
    private fun immersive(now: Long): Float {
        var a = 0f
        if (tel.vAbs > 0.04f && absPulse(now) && Prefs.vibrates(prefs.fbAbs)) a = max(a, prefs.hAbs * (0.55f + 0.45f * tel.vAbs))
        if (tel.vKerb > 0.04f) a = max(a, prefs.hKerb * tel.vKerb * rattle(now) * (0.85f + 0.15f * Random.nextFloat()))
        if (tel.vSlip > 0.04f && Prefs.vibrates(prefs.fbSlip)) a = max(a, prefs.hSlip * tel.vSlip * 0.9f)
        if (tel.vOver > 0.1f && Prefs.vibrates(prefs.fbOver)) a = max(a, prefs.hOver * tel.vOver * (if ((now / 15) % 2 == 0L) 1f else 0.5f))
        if (tel.vUnder > 0.1f && Prefs.vibrates(prefs.fbUnder) && (now % 160) < 72) a = max(a, prefs.hUnder * (0.35f + 0.65f * tel.vUnder))
        if (tel.vOff > 0.04f) a = max(a, prefs.hOff * tel.vOff * (0.5f + 0.5f * Random.nextFloat()))
        if (tel.vRoad > 0.04f) a = max(a, prefs.hRoad * tel.vRoad * (0.6f + 0.4f * Random.nextFloat()))
        if (tel.limiter && (now / 25) % 2 == 0L) a = max(a, prefs.hLimiter * 0.8f)
        if (now < shiftUntil) a = max(a, prefs.hShift)
        if (now < impactUntil) a = max(a, prefs.hImpact * impactAmp)
        return a
    }

    // ------------------------------------------------------------------ demo
    private fun demo(tt: Long, now: Long): Float = when {
        tt < 1000 -> if (absPulse(now)) prefs.hAbs else 0f                              // ABS / lock-up
        tt < 1400 -> 0f
        tt < 1530 || tt in 1800..1930 || tt in 2200..2330 -> prefs.hKerb * rattle(now)  // 3 kerb hits
        tt < 2700 -> 0f
        tt < 3700 -> prefs.hSlip * (0.45f + 0.55f * ((tt - 2700) / 1000f))              // wheelspin building
        tt < 4000 -> 0f
        tt < 4250 -> prefs.hOff * (0.6f + 0.4f * rattle(now))                           // off-track
        tt < 4600 -> 0f
        tt < 4750 -> prefs.hImpact                                                      // impact
        tt < 5050 -> 0f
        tt < 5090 -> prefs.hShift                                                       // shift tick
        tt < 5400 -> 0f
        tt < 6400 -> if ((now % 160) < 72) prefs.hUnder * 0.8f else 0f                   // understeer
        tt < 6700 -> 0f
        tt < 7500 -> prefs.hOver * (if ((now / 15) % 2 == 0L) 1f else 0.5f)              // oversteer
        else -> 0f
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
