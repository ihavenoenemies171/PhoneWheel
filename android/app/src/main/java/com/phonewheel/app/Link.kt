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
    @Volatile var brk = 0f
    @Volatile var mask = 0

    fun zero() {
        steer = 0f; thr = 0f; brk = 0f; mask = 0
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
class Link(private val st: InputState, private val onStatus: (LinkStatus) -> Unit) {
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
        @Volatile private var pingMs = -1
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

        fun run() {
            publish()
            while (alive) {
                val now = SystemClock.elapsedRealtime()
                try {
                    ensureTransport(now)
                    val msg = String.format(
                        Locale.US, "%.4f,%.4f,%.4f,%d",
                        st.steer, st.thr, st.brk, st.mask
                    )
                    // send on change, plus a keepalive so the PC watchdog stays happy
                    if (msg != lastMsg || now - lastSendT >= 40) {
                        if (sendRaw("d,${seq++},$msg")) {
                            lastMsg = msg
                            lastSendT = now
                        }
                    }
                    if (now - lastPingT >= 1000) {
                        lastPingT = now
                        sendRaw("p," + System.nanoTime())
                    }
                    // PC address may have changed: rediscover after a long silence
                    if (mode == Prefs.MODE_AUTO && target != null && lastPong != 0L && now - lastPong > 5000) {
                        target = null
                        lastPong = 0L
                    }
                    lastError = ""
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    closeTcp()
                    if (mode == Prefs.MODE_MANUAL) target = null
                }
                if (now - lastStatusT >= 250) {
                    lastStatusT = now
                    publish()
                }
                synchronized(lock) {
                    if (!kicked) {
                        try { lock.wait(8) } catch (_: InterruptedException) {}
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
                return true
            }
            val t = target ?: return false
            val sock = udp ?: return false
            val b = s.toByteArray(Charsets.US_ASCII)
            sock.send(DatagramPacket(b, b.size, t))
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
                        if (line.startsWith("PW!") && mode == Prefs.MODE_AUTO && target == null) {
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
            when {
                line.startsWith("q,") -> {
                    val t = line.substring(2).toLongOrNull() ?: return
                    pingMs = ((System.nanoTime() - t) / 1_000_000L).toInt()
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
                        (if (pingMs >= 0) "  ${pingMs} ms" else "")
                mode == Prefs.MODE_USB -> "USB: run usb_android.bat on PC"
                mode == Prefs.MODE_MANUAL && manualIp.isEmpty() -> "Set PC address in SETUP"
                mode == Prefs.MODE_MANUAL -> "Connecting to $manualIp…"
                else -> "Searching WiFi for PC…"
            }
            onStatus(LinkStatus(connected, text, pingMs, pcButtons))
        }
    }
}
