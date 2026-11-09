package com.phonewheel.app

import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.concurrent.thread

/** Shared input state, written by the UI/sensor thread and read by the network thread. */
class InputState {
    @Volatile var steer = 0f
    @Volatile var thr = 0f
    @Volatile var brkIn = 0f      // brake as the thumb asks for it
    @Volatile var brk = 0f        // brake actually sent (after release smoothing)
    @Volatile var mask = 0
    @Volatile var clutch = 0f
    @Volatile var hbrake = 0f
    @Volatile var role = Prefs.ROLE_WHEEL
    @Volatile var brakeReleaseMs = 0f

    // binding helper: sweep one axis so the game can detect it
    @Volatile var bindAxis = 0    // 0 off, 1 steering, 2 throttle, 3 brake
    @Volatile var bindStartMs = 0L

    fun zero() {
        steer = 0f; thr = 0f; brkIn = 0f; brk = 0f; mask = 0; clutch = 0f; hbrake = 0f
    }
}

/** Live game data streamed back from the PC (dashboard + vibration). */
class Telem {
    @Volatile var game = ""
    @Volatile var live = false
    @Volatile var gear = 0
    @Volatile var rpm = 0
    @Volatile var shiftRpm = 0
    @Volatile var maxRpm = 0
    @Volatile var speed = 0
    @Volatile var tc = -1
    @Volatile var tc2 = -1
    @Volatile var abs = -1
    @Volatile var map = -1
    @Volatile var bb = -1f
    @Volatile var fuel = -1f
    @Volatile var fuelLaps = -1f
    @Volatile var pitLimiter = false
    @Volatile var absActive = false
    @Volatile var tcActive = false
    @Volatile var deltaMs = Int.MIN_VALUE
    @Volatile var car = ""
    @Volatile var dashAt = 0L

    @Volatile var vAbs = 0f
    @Volatile var vKerb = 0f
    @Volatile var vSlip = 0f
    @Volatile var vRoad = 0f
    @Volatile var vOff = 0f
    @Volatile var vUnder = 0f      // front tyres sliding (understeer) 0..1
    @Volatile var vOver = 0f       // rear tyres sliding (oversteer) 0..1
    @Volatile var steerLock = 0    // car's steering wheel lock, degrees (0 = unknown)

    /** Called (from the network thread) when new dashboard data or coach events arrive. */
    @Volatile var onUpdate: (() -> Unit)? = null
    @Volatile var impactSeq = 0
    @Volatile var impactMag = 0f
    @Volatile var shiftSeq = 0
    @Volatile var limiter = false
    @Volatile var vibeAt = 0L

    // coach
    @Volatile var markerSeq = 0
    @Volatile var coachText = ""
    @Volatile var coachTone = "info"
    @Volatile var coachAt = 0L

    fun dashFresh(now: Long) = game.isNotEmpty() && now - dashAt < 1500
    fun vibeFresh(now: Long) = now - vibeAt < 300

    fun parse(line: String) {
        val p = line.split(",")
        val now = SystemClock.elapsedRealtime()
        fun i(k: Int) = p.getOrNull(k)?.toIntOrNull() ?: -1
        fun f(k: Int) = p.getOrNull(k)?.toFloatOrNull() ?: -1f
        if (p[0] == "t") {
            game = p.getOrNull(1) ?: ""
            live = p.getOrNull(2) == "1"
            if (game.isEmpty()) { dashAt = now; return }
            gear = p.getOrNull(3)?.toIntOrNull() ?: 0
            rpm = i(4); shiftRpm = i(5); maxRpm = i(6); speed = i(7)
            tc = i(8); tc2 = i(9); abs = i(10); map = i(11)
            bb = f(12); fuel = f(13); fuelLaps = f(14)
            pitLimiter = p.getOrNull(15) == "1"
            absActive = p.getOrNull(16) == "1"
            tcActive = p.getOrNull(17) == "1"
            deltaMs = p.getOrNull(18)?.toIntOrNull() ?: Int.MIN_VALUE
            steerLock = p.getOrNull(19)?.toIntOrNull() ?: 0
            car = p.getOrNull(20) ?: ""
            dashAt = now
            onUpdate?.invoke()
        } else if (p[0] == "v" && p.size >= 9) {
            vAbs = f(1).coerceAtLeast(0f); vKerb = f(2).coerceAtLeast(0f)
            vSlip = f(3).coerceAtLeast(0f); vRoad = f(4).coerceAtLeast(0f)
            impactSeq = i(5); impactMag = f(6).coerceAtLeast(0f)
            shiftSeq = i(7); limiter = p[8] == "1"
            vOff = if (p.size > 9) f(9).coerceAtLeast(0f) else 0f
            vUnder = if (p.size > 10) f(10).coerceAtLeast(0f) else 0f
            vOver = if (p.size > 11) f(11).coerceAtLeast(0f) else 0f
            vibeAt = now
        }
    }
}

data class LinkStatus(
    val connected: Boolean,
    val text: String,
    val pingMs: Int = -1,
    val pcButtons: Int = 0,
)

/**
 * Sends input to the PhoneWheel PC server.
 *  - WiFi: UDP datagrams (lowest latency), PC found by broadcast discovery or typed address
 *  - USB : TCP to 127.0.0.1, forwarded to the PC by `adb reverse tcp:8767 tcp:8767`
 */
class Link(
    private val st: InputState,
    private val tel: Telem,
    private val diag: Diag,
    private val onStatus: (LinkStatus) -> Unit,
) {
    private val lock = Object()
    private var kicked = false
    private var worker: Worker? = null
    private var workerThread: Thread? = null

    fun start(mode: Int, manualIp: String) {
        stop()
        val w = Worker(mode, manualIp.trim())
        worker = w
        workerThread = thread(name = "pw-link", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            w.run()
        }
    }

    fun stop() {
        worker?.alive = false
        kick()
        try { workerThread?.join(700) } catch (_: InterruptedException) {}
        worker = null
        workerThread = null
    }

    /** Coach settings sent to the PC (resent every 2 s so a restarted PC gets them too). */
    @Volatile var cfgLine = ""

    /** Host name/IP of the PC, for opening the session report. */
    @Volatile var pcHost: String? = null

    /** Wake the sender immediately (call whenever input changes). */
    fun kick() {
        synchronized(lock) {
            kicked = true
            lock.notifyAll()
        }
    }

    private inner class Worker(val mode: Int, val manualIp: String) {
        @Volatile var alive = true

        private var udp: DatagramSocket? = null
        private var tcp: Socket? = null
        private var tcpOut: OutputStream? = null
        @Volatile private var target: InetSocketAddress? = null

        @Volatile private var lastPong = 0L
        @Volatile private var rediscovering = false
        @Volatile private var pingMs = -1
        @Volatile private var jitterMs = -1
        private val pings = ArrayDeque<Int>()
        @Volatile private var pcName = ""
        @Volatile private var pcButtons = 0

        private var seq = 0
        private var lastMsg = ""
        private var lastSendT = 0L
        private var lastPingT = 0L
        private var lastDiscT = 0L
        private var lastStatusT = 0L
        private var lastTcpTry = 0L
        private var lastError = ""
        private var lastShapeT = 0L
        private var lastCfgT = 0L

        fun run() {
            publish()
            while (alive) {
                val now = SystemClock.elapsedRealtime()
                try {
                    ensureTransport(now)
                    shapeBrake(now)
                    var steer = st.steer
                    var thr = st.thr
                    var brk = st.brk
                    var mask = st.mask
                    var clutch = st.clutch
                    var hb = st.hbrake
                    if (st.bindAxis != 0) {
                        // binding helper: move only one axis, slowly, everything else at rest
                        val t = (now - st.bindStartMs) / 1000f
                        if (t > 4f) {
                            st.bindAxis = 0
                        } else {
                            val wave = 0.5f - 0.5f * kotlin.math.cos(t / 1.6f * 2f * Math.PI.toFloat())
                            steer = if (st.bindAxis == 1) kotlin.math.sin(t / 1.6f * 2f * Math.PI.toFloat()) else 0f
                            thr = if (st.bindAxis == 2) wave else 0f
                            brk = if (st.bindAxis == 3) wave else 0f
                            clutch = if (st.bindAxis == 4) wave else 0f
                            hb = if (st.bindAxis == 5) wave else 0f
                            mask = 0
                        }
                    }
                    val box = st.role == Prefs.ROLE_BOX
                    val msg = if (box) mask.toString() else fmt(steer, thr, brk, mask) + "," + f3(clutch) + "," + f3(hb)
                    // send on change, plus a keepalive so the PC watchdog stays happy
                    if (msg != lastMsg || now - lastSendT >= 40) {
                        if (sendRaw((if (box) "b," else "d,") + "${seq++},$msg")) {
                            lastMsg = msg
                            lastSendT = now
                        }
                    }
                    if (now - lastPingT >= 1000) {
                        lastPingT = now
                        sendRaw("p," + System.nanoTime())
                    }
                    if (cfgLine.isNotEmpty() && now - lastCfgT >= 2000) {
                        lastCfgT = now
                        sendRaw(cfgLine)
                    }
                    pcHost = if (mode == Prefs.MODE_USB) "localhost" else target?.address?.hostAddress
                    // no replies for a while: the PC's address may have changed. Look for it again,
                    // but KEEP sending to the old address meanwhile, so a slow WiFi patch never
                    // leaves a gap in the input stream (a gap makes the PC let go of the controls)
                    if (mode == Prefs.MODE_AUTO && target != null && lastPong != 0L && now - lastPong > 5000 &&
                        now - lastDiscT > 1000) {
                        lastDiscT = now
                        rediscovering = true
                        discover()
                    }
                    lastError = ""
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    closeTcp()
                    if (mode == Prefs.MODE_MANUAL) target = null
                }
                diag.tickRates(now)
                diag.sample(now, st, tel, pingMs)
                if (now - lastStatusT >= 250) {
                    lastStatusT = now
                    publish()
                }
                // wake on every input change (kick); otherwise only for the 40 ms keepalive,
                // unless a sweep or brake release is animating
                val busy = st.bindAxis != 0 || st.brk != st.brkIn
                synchronized(lock) {
                    if (!kicked) {
                        try { lock.wait(if (busy) 8L else 40L) } catch (_: InterruptedException) {}
                    }
                    kicked = false
                }
            }
            // tell the PC to let go of everything
            try {
                st.zero()
                sendRaw("d,${seq++},0,0,0,0")
                sendRaw("bye")
            } catch (_: Exception) {}
            closeTcp()
            try { udp?.close() } catch (_: Exception) {}
            udp = null
        }

        private val sb = StringBuilder(48)

        /** "s,t,b,mask" with 4 decimals, without String.format's allocations (runs ~200x/s). */
        private fun fmt(s: Float, t: Float, b: Float, mask: Int): String {
            sb.setLength(0)
            fixed4(s); sb.append(','); fixed4(t); sb.append(','); fixed4(b); sb.append(',').append(mask)
            return sb.toString()
        }

        private fun f3(v: Float): String = if (v <= 0f) "0" else (Math.round(v * 1000f) / 1000f).toString()

        private fun fixed4(v: Float) {
            var n = Math.round(v * 10000f)
            if (n < 0) { sb.append('-'); n = -n }
            sb.append(n / 10000).append('.')
            val f = n % 10000
            if (f < 1000) sb.append('0')
            if (f < 100) sb.append('0')
            if (f < 10) sb.append('0')
            sb.append(f)
        }

        /** Optional slow brake release: helps hold a steady trail-brake with a thumb. */
        private fun shapeBrake(now: Long) {
            val dt = if (lastShapeT == 0L) 0f else (now - lastShapeT) / 1000f
            lastShapeT = now
            val target = st.brkIn
            val rel = st.brakeReleaseMs / 1000f
            st.brk = if (rel <= 0f || target >= st.brk) target
            else maxOf(target, st.brk - dt / rel)
        }

        private fun ensureTransport(now: Long) {
            if (mode == Prefs.MODE_USB) {
                if (tcp == null && now - lastTcpTry > 1000) {
                    lastTcpTry = now
                    connectTcp()
                }
                return
            }
            if (udp == null) openUdp()
            if (target == null) {
                if (mode == Prefs.MODE_MANUAL) {
                    if (manualIp.isNotEmpty() && now - lastDiscT > 1000) {
                        lastDiscT = now
                        target = InetSocketAddress(InetAddress.getByName(manualIp), Prefs.PORT)
                        sendRaw("PW?")
                    }
                } else if (now - lastDiscT > 1000) {
                    lastDiscT = now
                    discover()
                }
            }
        }

        private fun sendRaw(s: String): Boolean {
            if (mode == Prefs.MODE_USB) {
                val out = tcpOut ?: return false
                out.write((s + "\n").toByteArray(Charsets.US_ASCII))
                diag.sent++
                return true
            }
            val t = target ?: return false
            val sock = udp ?: return false
            val b = s.toByteArray(Charsets.US_ASCII)
            sock.send(DatagramPacket(b, b.size, t))
            diag.sent++
            return true
        }

        // ---------------------------------------------------------------- UDP
        private fun openUdp() {
            val s = DatagramSocket()
            s.broadcast = true
            s.soTimeout = 500
            try { s.trafficClass = 0xB8 } catch (_: Exception) {} // DSCP EF: ask WiFi for low latency
            udp = s
            thread(name = "pw-udp-rx", isDaemon = true) {
                val buf = ByteArray(256)
                while (alive && !s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val line = String(p.data, 0, p.length, Charsets.US_ASCII)
                        if (line.startsWith("PW!") && mode == Prefs.MODE_AUTO && (target == null || rediscovering)) {
                            rediscovering = false
                            target = InetSocketAddress(p.address, Prefs.PORT)
                            lastPong = SystemClock.elapsedRealtime()
                            kick()
                        }
                        onLine(line)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        if (!alive) break
                    }
                }
            }
        }

        private fun discover() {
            val s = udp ?: return
            val b = "PW?".toByteArray(Charsets.US_ASCII)
            val targets = LinkedHashSet<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val bc = ia.broadcast
                        if (ia.address is Inet4Address && bc != null) targets.add(bc)
                    }
                }
            } catch (_: Exception) {}
            for (t in targets) {
                try { s.send(DatagramPacket(b, b.size, t, Prefs.PORT)) } catch (_: Exception) {}
            }
        }

        // ---------------------------------------------------------------- TCP (USB)
        private fun connectTcp() {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", Prefs.PORT), 800)
            tcp = s
            tcpOut = s.getOutputStream()
            thread(name = "pw-tcp-rx", isDaemon = true) {
                try {
                    val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    while (alive) {
                        val line = r.readLine() ?: break
                        onLine(line)
                    }
                } catch (_: Exception) {
                }
                if (tcp === s) closeTcp()
            }
            sendRaw("PW?")
        }

        private fun closeTcp() {
            try { tcp?.close() } catch (_: Exception) {}
            tcp = null
            tcpOut = null
        }

        // ---------------------------------------------------------------- replies
        private fun onLine(raw: String) {
            val line = raw.trim()
            diag.recv++
            when {
                line == "m" -> {
                    tel.markerSeq++
                    tel.onUpdate?.invoke()
                }
                line.startsWith("c,") -> {
                    val k = line.indexOf(',', 2)
                    if (k > 0) {
                        tel.coachTone = line.substring(2, k)
                        tel.coachText = line.substring(k + 1)
                        tel.coachAt = SystemClock.elapsedRealtime()
                        tel.onUpdate?.invoke()
                    }
                }
                line.startsWith("t,") || line.startsWith("v,") -> {
                    try { tel.parse(line) } catch (_: Exception) {}
                    lastPong = SystemClock.elapsedRealtime()
                }
                line.startsWith("q,") -> {
                    val t = line.substring(2).toLongOrNull() ?: return
                    val ms = ((System.nanoTime() - t) / 1_000_000L).toInt()
                    diag.addPing(ms)
                    synchronized(pings) {
                        pings.addLast(ms)
                        while (pings.size > 6) pings.removeFirst()
                        val sorted = pings.sorted()
                        pingMs = sorted[sorted.size / 2]                 // median: ignores one-off spikes
                        jitterMs = sorted.last() - sorted.first()
                    }
                    lastPong = SystemClock.elapsedRealtime()
                }
                line.startsWith("PW!") -> {
                    val p = line.split(",")
                    pcName = p.getOrNull(1) ?: ""
                    pcButtons = p.getOrNull(2)?.toIntOrNull() ?: 0
                    lastPong = SystemClock.elapsedRealtime()
                }
            }
        }

        private fun publish() {
            val now = SystemClock.elapsedRealtime()
            val connected = alive && lastPong != 0L && now - lastPong < 2500
            val text = when {
                connected -> (if (pcName.isNotEmpty()) pcName else "PC") +
                        (if (pingMs >= 0) "  ${pingMs} ms" + (if (jitterMs > 4) " ±${jitterMs / 2}" else "") else "")
                mode == Prefs.MODE_USB -> "USB: run usb_android.bat on PC"
                mode == Prefs.MODE_MANUAL && manualIp.isEmpty() -> "Set PC address in SETUP"
                mode == Prefs.MODE_MANUAL -> "Connecting to $manualIp…"
                else -> "Searching WiFi for PC…"
            }
            onStatus(LinkStatus(connected, text, pingMs, pcButtons))
        }
    }
}
