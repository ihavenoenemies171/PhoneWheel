package com.phonewheel.app

import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.random.Random

/**
 * The phone's stand-in for force feedback: mixes game effects (from the PC) and button clicks
 * into one vibration signal, updated 100 times a second.
 */
class Haptics(
    private val vibrator: Vibrator?,
    private val prefs: Prefs,
    private val tel: Telem,
) {
    private val hasVibrator = vibrator?.hasVibrator() == true
    private val hasAmplitude = hasVibrator && vibrator?.hasAmplitudeControl() == true

    @Volatile private var running = false
    private var worker: Thread? = null

    // one-shot events
    @Volatile private var pulseUntil = 0L
    @Volatile private var pulseAmp = 0f
    @Volatile private var testStart = 0L

    private var lastImpactSeq = -1
    private var lastShiftSeq = -1
    private var impactUntil = 0L
    private var impactAmp = 0f
    private var shiftUntil = 0L

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
                try { tick() } catch (_: Exception) {}
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
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

    /** Short click for buttons and paddles. */
    fun pulse(ms: Long, strength: Float = 1f) {
        if (!prefs.hapticsOn) return
        pulseAmp = (strength * prefs.hButtons).coerceIn(0f, 1f)
        pulseUntil = SystemClock.elapsedRealtime() + ms
    }

    /** Plays a 4-second demo: ABS, kerb, wheelspin, impact, shift. */
    fun test() {
        testStart = SystemClock.elapsedRealtime()
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        var amp = 0f

        if (now < pulseUntil) amp = max(amp, pulseAmp)

        var vAbs = 0f
        var vKerb = 0f
        var vSlip = 0f
        var vRoad = 0f
        var limiter = false
        if (tel.vibeFresh(now)) {
            vAbs = tel.vAbs; vKerb = tel.vKerb; vSlip = tel.vSlip; vRoad = tel.vRoad; limiter = tel.limiter
            if (lastImpactSeq >= 0 && tel.impactSeq != lastImpactSeq) {
                impactUntil = now + 150
                impactAmp = 0.6f + 0.4f * tel.impactMag.coerceIn(0f, 1f)
            }
            if (lastShiftSeq >= 0 && tel.shiftSeq != lastShiftSeq) shiftUntil = now + 45
            lastImpactSeq = tel.impactSeq
            lastShiftSeq = tel.shiftSeq
        }

        // demo sequence
        val tt = now - testStart
        if (testStart != 0L && tt in 0..4200) {
            when {
                tt < 900 -> vAbs = 0.8f
                tt < 1800 -> vKerb = 0.8f
                tt < 2700 -> vSlip = 0.8f
                tt < 3000 -> vRoad = 0.8f
                tt < 3200 -> if (impactUntil < now) { impactUntil = now + 150; impactAmp = 1f }
                tt < 3700 -> limiter = true
                else -> if (shiftUntil < now && tt < 3760) shiftUntil = now + 45
            }
        }

        if (prefs.hapticsOn) {
            // ABS / lock-up: hard pulses ~16 Hz, like a pedal kicking back
            if (vAbs > 0.04f && (now / 30) % 2 == 0L) amp = max(amp, prefs.hAbs * (0.55f + 0.45f * vAbs))
            // kerbs: fast rough rattle
            if (vKerb > 0.04f) {
                val phase = if ((now / 20) % 2 == 0L) 1f else 0.3f
                amp = max(amp, prefs.hKerb * vKerb * phase * (0.85f + 0.15f * Random.nextFloat()))
            }
            // wheelspin / sliding: smooth continuous rumble
            if (vSlip > 0.04f) amp = max(amp, prefs.hSlip * vSlip * 0.9f)
            // road texture / off-track
            if (vRoad > 0.04f) amp = max(amp, prefs.hRoad * vRoad * (0.6f + 0.4f * Random.nextFloat()))
            // rev limiter buzz
            if (limiter && (now / 25) % 2 == 0L) amp = max(amp, prefs.hLimiter * 0.8f)
            if (now < shiftUntil) amp = max(amp, prefs.hShift)
            if (now < impactUntil) amp = max(amp, prefs.hImpact * impactAmp)
            amp *= prefs.hMaster
        } else {
            amp = 0f
        }
        drive(amp.coerceIn(0f, 1f), now)
    }

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
