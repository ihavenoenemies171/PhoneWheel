"""
PhoneWheel PC server
--------------------
Serves the phone wheel web app and turns its input into a vJoy virtual wheel.

  Android app : UDP 8767 (WiFi, auto-discovery)  /  TCP 8767 (USB via adb reverse)
  Browser     : HTTP 8765 (USB, adb reverse)      /  HTTPS 8766 (WiFi)

Run:  PhoneWheel.exe   or   python server.py      (add --dry-run to test without vJoy)
"""
import argparse
import asyncio
import datetime
import ipaddress
import json
import os
import socket
import ssl
import sys
import time

from aiohttp import web, WSMsgType

import coach as coach_mod
import report
import telemetry
import vjoy

FROZEN = getattr(sys, "frozen", False)
HERE = os.path.dirname(os.path.abspath(__file__))
RES_DIR = getattr(sys, "_MEIPASS", HERE)                       # bundled read-only files
EXE_DIR = os.path.dirname(sys.executable) if FROZEN else HERE


def _writable_dir():
    """Where laps, reports, logs and the certificate are saved: next to the program if possible
    (portable zip), otherwise Documents\\PhoneWheel (installed in Program Files)."""
    try:
        test = os.path.join(EXE_DIR, ".pw_write_test")
        with open(test, "w") as fh:
            fh.write("x")
        os.remove(test)
        return EXE_DIR
    except OSError:
        docs = os.path.join(os.path.expanduser("~"), "Documents", "PhoneWheel")
        os.makedirs(docs, exist_ok=True)
        return docs


APP_DIR = _writable_dir()
WEB = os.path.join(RES_DIR, "web")
CERT_DIR = os.path.join(APP_DIR, "cert")
APP_PORT = 8767
# If the phone goes quiet (WiFi hiccup, app closed, phone died):
WATCHDOG_THR_S = 0.40  # ... this long: throttle, clutch and handbrake go to zero (no runaway car)
WATCHDOG_S = 1.50      # ... this long: release everything (steering centred, buttons let go)
# Steering, brake and buttons (e.g. a latched look-left) are HELD through short hiccups, so a WiFi
# stall doesn't snap the wheel to centre and back.


# ---------------------------------------------------------------- networking
def local_ipv4s():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def ensure_cert(ips):
    """Self-signed cert so phone browsers allow motion sensors over WiFi."""
    os.makedirs(CERT_DIR, exist_ok=True)
    crt, key, meta = (os.path.join(CERT_DIR, n) for n in ("cert.pem", "key.pem", "ips.json"))
    try:
        with open(meta) as f:
            if set(ips) <= set(json.load(f)) and os.path.isfile(crt) and os.path.isfile(key):
                return crt, key
    except (OSError, ValueError):
        pass
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PhoneWheel")])
    san = [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
    san += [x509.IPAddress(ipaddress.ip_address(ip)) for ip in ips]
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(k.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName(san), critical=False)
            .sign(k, hashes.SHA256()))
    with open(key, "wb") as f:
        f.write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                serialization.NoEncryption()))
    with open(crt, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(meta, "w") as f:
        json.dump(ips, f)
    return crt, key


def print_qr(url):
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.make()
        qr.print_ascii(invert=True)
    except Exception:
        pass


# ---------------------------------------------------------------- state
class Wheel:
    BOX_TIMEOUT = 1.0   # a button box that goes quiet has its buttons released

    def __init__(self, dev):
        self.dev = dev
        self.steer = 0.0
        self.thr = 0.0
        self.brk = 0.0
        self.clutch = 0.0
        self.hbrake = 0.0
        self.btn = 0            # buttons from the wheel phone
        self.boxes = {}         # second devices: peer -> (button mask, last seen)
        self.last = 0.0
        self.client = None
        self.rate = 0
        self._count = 0
        self.gaps = 0           # data gaps over 0.2 s this session
        self.gap_max = 0.0
        self._look_on = {}
        self.apply()

    @property
    def buttons(self):
        m = self.btn
        for mask, _t in self.boxes.values():
            m |= mask
        return m

    LOOK_BUTTONS = (21, 22, 23)

    def _watch_look(self, old, new):
        """Log look buttons that turn off very quickly - helps tell app-side flicker from game-side snap-back."""
        now = time.monotonic()
        for n in self.LOOK_BUTTONS:
            bit = 1 << (n - 1)
            if new & bit and not old & bit:
                self._look_on[n] = now
            elif old & bit and not new & bit and n in self._look_on:
                held = now - self._look_on.pop(n)
                if held < 0.4:
                    print(f"\n[i] look button {n} released after only {held * 1000:.0f} ms")

    def apply(self):
        self.dev.set_axis(vjoy.AXIS_X, (self.steer + 1.0) / 2.0)
        self.dev.set_axis(vjoy.AXIS_Y, self.thr)
        self.dev.set_axis(vjoy.AXIS_Z, self.brk)
        self.dev.set_axis(vjoy.AXIS_RX, self.clutch)
        self.dev.set_axis(vjoy.AXIS_RY, self.hbrake)
        self.dev.set_buttons(self.buttons)

    def update(self, s, t, b, mask, c=0.0, h=0.0):
        self.steer = max(-1.0, min(1.0, s))
        self.thr = max(0.0, min(1.0, t))
        self.brk = max(0.0, min(1.0, b))
        self.clutch = max(0.0, min(1.0, c))
        self.hbrake = max(0.0, min(1.0, h))
        self._watch_look(self.btn, mask & 0xFFFFFFFF)
        self.btn = mask & 0xFFFFFFFF
        now = time.monotonic()
        gap = now - self.last if self.last else 0.0
        if gap > 0.2:
            self.gaps += 1
            self.gap_max = max(self.gap_max, gap)
            held = "held" if gap < WATCHDOG_S else "had been released"
            print(f"\n[i] {gap * 1000:.0f} ms gap in data from the phone (WiFi hiccup) - steering and buttons {held}")
        self.last = now
        self._count += 1
        self.apply()

    def update_box(self, peer, mask):
        self.boxes[peer] = (mask & 0xFFFFFFFF, time.monotonic())
        self.apply()

    def expire_boxes(self):
        now = time.monotonic()
        old = [p for p, (_m, t) in self.boxes.items() if now - t > self.BOX_TIMEOUT]
        for p in old:
            del self.boxes[p]
        if old:
            self.apply()

    def cut_power(self):
        """Short silence: no throttle, but keep steering, brake and buttons where they were."""
        self.thr, self.clutch, self.hbrake = 0.0, 0.0, 0.0
        self.apply()

    def release(self):
        self.steer, self.thr, self.brk, self.btn, self.clutch, self.hbrake = 0.0, 0.0, 0.0, 0, 0.0, 0.0
        self.apply()


# ---------------------------------------------------------------- native app protocol
# Text messages (one per UDP datagram, or one per line over TCP):
#   PW?                     discovery          -> PW!,<pc name>,<buttons>
#   p,<t>                   latency ping       -> q,<t>
#   d,<seq>,<s>,<t>,<b>,<m> input: steer -1..1, throttle 0..1, brake 0..1, button mask
#   bye                     release everything
class AppLink:
    """Native app protocol. Several devices can be connected: one wheel (steering, pedals, buttons)
    plus button boxes (buttons only). Dashboard / vibration data goes to all of them."""

    def __init__(self, wheel):
        self.wheel = wheel
        self.last_seq = {}
        self.targets = {}       # peer -> [reply function, last seen]
        self.on_cfg = None      # coach settings from the app

    def push(self, line):
        now = time.monotonic()
        for peer, (reply, seen) in list(self.targets.items()):
            if now - seen > 3.0:
                del self.targets[peer]
                continue
            try:
                reply(line)
            except Exception:
                pass

    @property
    def has_targets(self):
        return bool(self.targets)

    def _seq_ok(self, peer, seq, kind):
        if kind != "wifi":
            return True
        last = self.last_seq.get(peer)          # UDP can reorder: drop stale packets
        if last is not None and 0 < last - seq < 50:
            return False
        self.last_seq[peer] = seq
        return True

    def handle(self, line, peer, reply, kind):
        line = line.strip()
        if not line:
            return
        if line.startswith("d,"):
            parts = line.split(",")
            if len(parts) < 6:
                return
            try:
                seq = int(parts[1])
                vals = [float(parts[2]), float(parts[3]), float(parts[4]), int(parts[5])]
                if len(parts) >= 8:
                    vals += [float(parts[6]), float(parts[7])]
            except ValueError:
                return
            if not self._seq_ok(peer, seq, kind):
                return
            self.targets[peer] = [reply, time.monotonic()]
            name = f"{peer} ({kind})"
            if self.wheel.client != name:
                self.wheel.client = name
                print(f"\n[+] PhoneWheel wheel connected: {name}")
            self.wheel.update(*vals)
        elif line.startswith("b,"):
            parts = line.split(",")
            try:
                seq, mask = int(parts[1]), int(parts[2])
            except (IndexError, ValueError):
                return
            if not self._seq_ok(peer, seq, kind):
                return
            if peer not in self.wheel.boxes:
                print(f"\n[+] Button box connected: {peer} ({kind})")
            self.targets[peer] = [reply, time.monotonic()]
            self.wheel.update_box(peer, mask)
        elif line.startswith("p,"):
            reply("q," + line[2:])
        elif line.startswith("cfg,"):
            if self.on_cfg:
                self.on_cfg(line)
        elif line.startswith("PW?"):
            reply(f"PW!,{socket.gethostname()},{self.wheel.dev.button_count}")
        elif line == "bye":
            self.last_seq.pop(peer, None)
            self.targets.pop(peer, None)
            if peer in self.wheel.boxes:
                del self.wheel.boxes[peer]
                self.wheel.apply()
                print(f"\n[-] Button box left ({peer})")
            elif self.wheel.client and self.wheel.client.startswith(str(peer)):
                self.wheel.release()
                self.wheel.client = None
                print(f"\n[-] PhoneWheel wheel left ({peer}) - inputs released")


class UdpProto(asyncio.DatagramProtocol):
    def __init__(self, link):
        self.link = link
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        msg = data.decode("ascii", "ignore")
        self.link.handle(msg, f"{addr[0]}:{addr[1]}", lambda r: self.transport.sendto(r.encode(), addr), "wifi")

    def error_received(self, exc):
        pass


def make_tcp_handler(link, wheel):
    async def handle(reader, writer):
        peer = writer.get_extra_info("peername")
        peer = f"{peer[0]}:{peer[1]}" if peer else "?"
        sock = writer.get_extra_info("socket")
        if sock is not None:
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass

        def reply(r):
            if not writer.is_closing():
                writer.write((r + "\n").encode())

        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                link.handle(line.decode("ascii", "ignore"), peer, reply, "usb")
        except (ConnectionError, OSError):
            pass
        finally:
            link.targets.pop(peer, None)
            if peer in wheel.boxes:
                del wheel.boxes[peer]
                wheel.apply()
            name = f"{peer} (usb)"
            if wheel.client == name:
                wheel.client = None
                wheel.release()
                print(f"\n[-] PhoneWheel app disconnected ({name}) - inputs released")
            try:
                writer.close()
            except Exception:
                pass
    return handle


# ---------------------------------------------------------------- web
async def ws_handler(request):
    wheel: Wheel = request.app["wheel"]
    ws = web.WebSocketResponse(heartbeat=5.0, max_msg_size=4096)
    await ws.prepare(request)
    sock = request.transport.get_extra_info("socket") if request.transport else None
    if sock is not None:
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
    peer = request.remote
    wheel.client = peer
    print(f"\n[+] Phone connected from {peer}")
    await ws.send_str("hello," + json.dumps({"buttons": wheel.dev.button_count}))
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            data = msg.data
            if data.startswith("p,"):  # latency ping
                await ws.send_str("q," + data[2:])
                continue
            parts = data.split(",")
            if len(parts) >= 4:
                try:
                    wheel.update(float(parts[0]), float(parts[1]), float(parts[2]), int(parts[3]))
                except ValueError:
                    pass
    finally:
        if wheel.client == peer:
            wheel.client = None
            wheel.release()
        print(f"\n[-] Phone disconnected ({peer}) - inputs released")
    return ws


PC_LOG = []   # last 30 s of PC-side samples for debug logs (20 Hz)


def pc_sample(app, frame):
    w = app["wheel"]
    f = frame
    line = (f"{time.monotonic():.2f},{w.steer:+.3f},{w.thr:.3f},{w.brk:.3f},{w.btn},{w.rate},{w.client or '-'},"
            + (f"{f.game},{int(f.live)},{f.speed:.0f},{f.rpm:.0f},{f.gear},{f.lap},{f.lap_dist:.0f},{f.throttle:.2f},"
               f"{f.brake:.2f},{f.v_abs:.2f},{f.v_kerb:.2f},{f.v_slip:.2f},{f.v_road:.2f},{f.v_off:.2f},"
               f"{getattr(f, 'v_under', 0):.2f},{getattr(f, 'v_over', 0):.2f}" if f is not None else "no game"))
    PC_LOG.append(line)
    if len(PC_LOG) > 600:
        del PC_LOG[:len(PC_LOG) - 600]


async def upload_log(request):
    """The app's 'Send debug log to PC' button: saves the phone log and a PC log side by side."""
    text = await request.text()
    folder = os.path.join(APP_DIR, "logs")
    os.makedirs(folder, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M%S")
    with open(os.path.join(folder, f"{stamp}_phone.txt"), "w", encoding="utf-8") as fh:
        fh.write(text[:2_000_000])
    tel = request.app["shared"].get("telemetry")
    wheel = request.app["wheel"]
    with open(os.path.join(folder, f"{stamp}_pc.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"PhoneWheel PC log {stamp}\n")
        fh.write(f"python {sys.version.split()[0]} frozen={FROZEN} vjoy={getattr(wheel.dev, 'dll_path', '?')} "
                 f"buttons={wheel.dev.button_count} missing_axes={wheel.dev.missing_axes}\n")
        fh.write(f"game={tel.game if tel else ''} last_error={tel.last_error if tel else ''}\n")
        fh.write("\n[samples] t,steer,thr,brk,btn,rate,client,game,live,kmh,rpm,gear,lap,dist,thr_game,brk_game,"
                 "abs,kerb,slip,road,off,under,over\n")
        fh.write("\n".join(PC_LOG))
    print(f"\n[log] debug logs saved: {os.path.join(folder, stamp)}_phone.txt / _pc.txt")
    return web.Response(text="ok")


def _laps_dir():
    return os.path.join(APP_DIR, "best_laps")


async def laps_page(request, message=""):
    """Share / load reference laps."""
    folder = _laps_dir()
    files = sorted(f for f in os.listdir(folder) if f.endswith(".json")) if os.path.isdir(folder) else []
    rows = []
    for f in files:
        label = f[:-5].replace("__", " · ").replace("_", " ")
        is_ref = f.endswith("__reference.json")
        try:
            with open(os.path.join(folder, f)) as fh:
                lt = json.load(fh).get("lap_time")
            m, sec = divmod(float(lt), 60)
            t = f"{int(m)}:{sec:06.3f}"
        except Exception:
            t = "?"
        action = (f"<form method=post action='/laps/remove' style='display:inline'><input type=hidden name=f value='{f}'>"
                  f"<button>Remove reference</button></form>" if is_ref else f"<a href='/laps/file/{f}' download>Download to share</a>")
        rows.append(f"<tr><td>{'⭐ reference: ' if is_ref else ''}{label.replace(' · reference', '')}</td><td>{t}</td><td>{action}</td></tr>")
    body = "".join(rows) or "<tr><td colspan=3>No laps yet. Drive a clean lap first.</td></tr>"
    msg = f"<p style='color:#2bd96b'>{message}</p>" if message else ""
    html = f"""<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>PhoneWheel laps</title>
<style>body{{background:#0b0d10;color:#e9edf2;font:15px system-ui;padding:20px;max-width:900px;margin:auto}}
td,th{{padding:6px 10px;border-bottom:1px solid #2a2f37;text-align:left}}a,button{{color:#ffcc00}}
button{{background:#22262d;border:1px solid #2a2f37;border-radius:6px;padding:4px 10px}}.box{{background:#14181d;border:1px solid #2a2f37;border-radius:12px;padding:14px;margin:16px 0}}</style>
<h1>Laps</h1>{msg}
<p>Your best lap for each game / track / car is saved here. <b>Download</b> one to share it with a friend.
Load someone else's lap (or an old one of yours) as a <b>reference</b>: the live delta, brake markers and corner notes then
compare you against that lap instead of your own best. Remove it to go back to your own best.</p>
<div class=box><form method=post action='/laps/upload' enctype='multipart/form-data'>
<b>Load a reference lap</b> (.json from PhoneWheel)<br><br><input type=file name=lap accept='.json' required> <button>Load</button></form></div>
<table><tr><th>Game · track · car</th><th>Lap</th><th></th></tr>{body}</table>
<p><a href='/report'>Session report</a></p>"""
    return web.Response(text=html, content_type="text/html")


async def laps_file(request):
    name = os.path.basename(request.match_info["name"])
    path = os.path.join(_laps_dir(), name)
    if not name.endswith(".json") or not os.path.isfile(path):
        raise web.HTTPNotFound()
    return web.FileResponse(path, headers={"Content-Disposition": f'attachment; filename="{name}"'})


async def laps_upload(request):
    coach = request.app["shared"].get("coach")
    try:
        form = await request.post()
        field = form.get("lap")
        data = field.file.read().decode("utf-8") if hasattr(field, "file") else str(field)
        msg = coach.import_lap(data) if coach else "PhoneWheel isn't ready yet"
    except Exception as e:
        msg = f"Couldn't load that file: {e}"
    print(f"\n[laps] {msg}")
    return await laps_page(request, msg)


async def laps_remove(request):
    coach = request.app["shared"].get("coach")
    form = await request.post()
    f = os.path.basename(str(form.get("f", "")))
    if coach:
        coach.remove_reference(f)
    return await laps_page(request, "Reference removed: you're compared with your own best lap again.")


async def report_page(request):
    path = report.LATEST["path"]
    if path and os.path.isfile(path):
        return web.FileResponse(path, headers={"Cache-Control": "no-cache"})
    return web.Response(text=REPORT_EMPTY, content_type="text/html")


async def reports_list(request):
    folder = os.path.join(APP_DIR, "reports")
    files = sorted((f for f in os.listdir(folder) if f.endswith(".html")), reverse=True) if os.path.isdir(folder) else []
    items = "".join(f'<li><a href="/reports/{f}">{f[:-5]}</a></li>' for f in files) or "<li>No sessions yet</li>"
    return web.Response(text=f"<!doctype html><meta name=viewport content='width=device-width'><title>PhoneWheel sessions</title>"
                             f"<body style='background:#0b0d10;color:#e9edf2;font:16px system-ui;padding:20px'>"
                             f"<h1>Sessions</h1><ul>{items}</ul>", content_type="text/html")


async def report_file(request):
    name = os.path.basename(request.match_info["name"])
    path = os.path.join(APP_DIR, "reports", name)
    if not name.endswith(".html") or not os.path.isfile(path):
        raise web.HTTPNotFound()
    return web.FileResponse(path)


REPORT_EMPTY = ("<!doctype html><meta name=viewport content='width=device-width'><meta http-equiv=refresh content=15>"
                "<title>PhoneWheel report</title><body style='background:#0b0d10;color:#e9edf2;font:16px system-ui;padding:24px'>"
                "<h1>No session yet</h1><p>Drive at least one full lap in ACC, RaceRoom or LMU. "
                "This page refreshes by itself. <a style='color:#ffcc00' href='/reports'>Older sessions</a></p>")


async def index(request):
    return web.FileResponse(os.path.join(WEB, "index.html"),
                            headers={"Cache-Control": "no-cache"})


async def watchdog(app):
    wheel: Wheel = app["wheel"]
    last_print = 0.0
    while True:
        await asyncio.sleep(0.05)
        now = time.monotonic()
        quiet = now - wheel.last if wheel.last else 0.0
        if wheel.last and quiet > WATCHDOG_S and (wheel.thr or wheel.brk or wheel.btn or wheel.steer
                                                  or wheel.clutch or wheel.hbrake):
            wheel.release()
            print("\n[!] No data from phone for 1.5 s - all inputs released")
        elif wheel.last and quiet > WATCHDOG_THR_S and (wheel.thr or wheel.clutch or wheel.hbrake):
            wheel.cut_power()
        wheel.expire_boxes()
        if wheel.client and wheel.client.endswith("(wifi)") and now - wheel.last > 2.0:
            print(f"\n[-] {wheel.client} went quiet")
            wheel.client = None
        if now - last_print >= 0.25:
            wheel.rate = wheel._count * 4
            wheel._count = 0
            last_print = now
            pressed = [str(i + 1) for i in range(32) if wheel.buttons & (1 << i)]
            tel = app["shared"].get("telemetry")
            game = f"  [{tel.game}]" if tel is not None and tel.game else ""
            line = (f"\r  steer {wheel.steer:+.2f}  thr {wheel.thr:4.0%}  brk {wheel.brk:4.0%}  "
                    f"btn [{' '.join(pressed) or '-'}]  {wheel.rate:3d} Hz  "
                    f"{'connected ' + str(wheel.client) if wheel.client else 'waiting for phone...'}{game}")
            sys.stdout.write(line.ljust(110))
            sys.stdout.flush()


async def telemetry_loop(app):
    """Reads the running game's telemetry and streams dashboard + vibration data to the app."""
    tel = telemetry.Telemetry()
    app["shared"]["telemetry"] = tel
    coach = coach_mod.Coach(APP_DIR)
    app["shared"]["coach"] = coach
    last_game = ""
    next_dash = 0.0
    sample_tick = 0
    while True:
        await asyncio.sleep(0.01)
        try:
            frame = tel.poll()
        except Exception:
            frame = None
        if tel.game != last_game:
            print(f"\n[i] Game telemetry: {tel.game or 'no game running'}")
            last_game = tel.game
        sample_tick = (sample_tick + 1) % 5          # 100 Hz loop -> 20 Hz log
        if sample_tick == 0:
            pc_sample(app, frame)
        try:
            coach.update(frame)
        except Exception as e:
            print(f"\n[coach] {e}")
        events = coach.pop_events()
        for ev in events:
            if ev[0] == "c":
                print(f"\n[coach] {ev[1]}")
        link = app["shared"].get("link")
        if link is None or not link.has_targets:
            continue
        push = link.push
        if link.on_cfg is None:
            link.on_cfg = coach.configure
        try:
            if frame is None:
                now = time.monotonic()
                if now >= next_dash:
                    next_dash = now + 1.0
                    push("t,,0")
                continue
            for ev in events:
                if ev[0] == "m":
                    push("m")
                elif ev[0] == "c":
                    push(f"c,{ev[2]},{ev[1]}")
            if coach.delta is not None:
                frame.delta_ms = int(coach.delta * 1000)
            push(tel.vibe_packet(frame))
            now = time.monotonic()
            if now >= next_dash:
                next_dash = now + 1 / 30
                push(tel.dash_packet(frame))
        except Exception:
            pass


async def on_startup(app):
    app["watchdog"] = asyncio.create_task(watchdog(app))
    app["telemetry_task"] = asyncio.create_task(telemetry_loop(app))


async def on_cleanup(app):
    app["watchdog"].cancel()
    app["telemetry_task"].cancel()
    app["wheel"].release()
    app["wheel"].dev.close()


def already_running(port):
    """True if another PhoneWheel answers on the app port of this PC."""
    try:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.settimeout(0.6)
        u.sendto(b"PW?", ("127.0.0.1", port))
        data, _ = u.recvfrom(256)
        u.close()
        return data.startswith(b"PW!")
    except OSError:
        return False


async def start_site(runner, port, label, ssl_context=None):
    """Starts an HTTP(S) site; a busy port only disables that feature instead of stopping PhoneWheel."""
    try:
        await web.TCPSite(runner, "0.0.0.0", port, ssl_context=ssl_context).start()
        return True
    except OSError:
        print(f"\n  Note: port {port} is used by another program, so the {label} is off. "
              "The app works normally.")
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", type=int, default=1, help="vJoy device number (default 1)")
    ap.add_argument("--http-port", type=int, default=8765)
    ap.add_argument("--https-port", type=int, default=8766)
    ap.add_argument("--app-port", type=int, default=APP_PORT, help="UDP+TCP port for the Android app")
    ap.add_argument("--dry-run", action="store_true", help="run without vJoy (UI testing)")
    args = ap.parse_args()

    print("=" * 64)
    print("  PhoneWheel  -  phone steering wheel for ACC / RaceRoom / LMU")
    print("=" * 64)
    if already_running(args.app_port):
        print("\n  PhoneWheel is ALREADY RUNNING in another window (maybe minimised).")
        print("  Use that window, or close it first and start PhoneWheel again.")
        if FROZEN:
            input("\n  Press Enter to close this window...")
        return
    if args.dry_run:
        dev = vjoy.DummyDevice()
        print("  DRY RUN: inputs are shown here but NOT sent to games.")
    else:
        try:
            dev = vjoy.VJoyDevice(args.device)
            print(f"  vJoy device {args.device} acquired ({dev.button_count} buttons)  [{dev.dll_path}]")
            if dev.button_count < 32:
                print(f"  WARNING: device has only {dev.button_count} buttons. Set it to 32 in 'Configure vJoy'.")
            if dev.missing_axes:
                print(f"  WARNING: missing axes {dev.missing_axes}. Enable X, Y, Z in 'Configure vJoy'.")
            if getattr(dev, "missing_extra", None):
                print(f"  Note: axes {dev.missing_extra} are off in vJoy, so clutch / handbrake levers won't work. "
                      "Tick Rx and Ry in 'Configure vJoy' if you want them.")
        except vjoy.VJoyError as e:
            print(f"  vJoy problem: {e}")
            print("  >>> Falling back to DRY RUN: the phone connects, but GAMES WILL NOT SEE INPUT. <<<")
            dev = vjoy.DummyDevice()

    app = web.Application()
    app["wheel"] = Wheel(dev)
    app["shared"] = {}  # mutable state filled in after startup
    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/report", report_page)
    app.router.add_post("/log", upload_log)
    app.router.add_get("/laps", laps_page)
    app.router.add_get("/laps/file/{name}", laps_file)
    app.router.add_post("/laps/upload", laps_upload)
    app.router.add_post("/laps/remove", laps_remove)
    app.router.add_get("/reports", reports_list)
    app.router.add_get("/reports/{name}", report_file)
    app.router.add_static("/", WEB, show_index=False)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    ips = local_ipv4s()
    ssl_ctx = None
    try:
        crt, key = ensure_cert(ips)
        ssl_ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ssl_ctx.load_cert_chain(crt, key)
    except Exception as e:
        print(f"  HTTPS disabled ({e}). Install 'cryptography' for WiFi steering.")

    print("\n  PhoneWheel ANDROID APP:")
    print("     WiFi : just open the app - it finds this PC automatically")
    if ips:
        print(f"            (or type this PC's address in the app: {', '.join(ips)})")
    print("     USB tethering (easy, low lag): phone Settings > Hotspot & tethering > USB tethering ON,")
    print("            app connection 'Automatic'. First time: run network_setup.bat once.")
    print("     USB (adb): run usb_android.bat, then choose 'USB cable' in the app")
    print("\n  Browser version (no app):")
    print(f"     USB  : http://localhost:{args.http_port}  (after usb_android.bat)")
    if ssl_ctx:
        print("     WiFi :")
        for ip in ips:
            print(f"            https://{ip}:{args.https_port}")
        print("            (first time: tap 'Advanced' -> 'Proceed' on the certificate warning)")
    print(f"\n  Session report (updates every lap): http://localhost:{args.http_port}/report")
    print(f"  Laps, reports and logs are saved in: {APP_DIR}")
    print("\n  Ctrl+C to quit.\n")

    async def run():
        runner = web.AppRunner(app, access_log=None)
        await runner.setup()
        link = AppLink(app["wheel"])
        app["shared"]["link"] = link
        loop = asyncio.get_running_loop()
        await loop.create_datagram_endpoint(lambda: UdpProto(link), local_addr=("0.0.0.0", args.app_port))
        await asyncio.start_server(make_tcp_handler(link, app["wheel"]), "0.0.0.0", args.app_port)
        # browser version + session report: optional
        await start_site(runner, args.http_port, "browser version / session report page")
        if ssl_ctx:
            await start_site(runner, args.https_port, "WiFi browser version", ssl_ctx)
        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            await runner.cleanup()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        print("\nBye.")
    except OSError as e:
        print(f"\n  Could not open PhoneWheel's port {args.app_port} ({e}).")
        print("  Another program is using it. Close other PhoneWheel windows, or end PhoneWheel.exe in")
        print("  Task Manager (Ctrl+Shift+Esc), then start PhoneWheel again.")
        if FROZEN:
            input("  Press Enter to close...")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # keep the window open when double-clicked
        import traceback
        traceback.print_exc()
        if FROZEN:
            input("\nPress Enter to close...")
        raise
