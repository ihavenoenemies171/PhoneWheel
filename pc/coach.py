"""
PhoneWheel driving coach.

Records every lap against track position (2 m grid) and compares the current lap with your best
valid lap on the same track + car:

  * live delta        - time gained/lost vs best lap, at every point of the lap
  * sector splits     - message at 1/3, 2/3 and the line
  * brake markers     - event slightly before each of your best lap's braking points (phone ticks)
  * brake coach       - after each braking zone: braking point, peak pressure, release, lock-ups
  * exit coach        - after each corner exit: time to full throttle, throttle lifts, wheelspin

Best laps are saved per track + car, so markers work from the first lap of your next session.
"""
import bisect
import json
import math
import os
import re
import time

GRID = 2.0  # metres between stored samples


def _safe(name):
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", name or "unknown").strip("_")[:60] or "unknown"


# ----------------------------------------------------------------------------- lap data
class LapData:
    """One lap resampled every GRID metres."""

    def __init__(self, track_len):
        self.track_len = track_len
        self.d, self.t, self.v, self.thr, self.brk, self.abs, self.slip = [], [], [], [], [], [], []
        self.x, self.z = [], []      # world position (for the track map); None where unknown
        self.meta = {}               # game / track / car / source, for sharing laps
        self.lap_time = None
        self.valid = True
        self.in_pit = False
        self.number = 0
        self.zones = []

    def add(self, d, t, v, thr, brk, absv, slip, x=None, z=None):
        if self.d and d <= self.d[-1]:
            # same grid cell: keep the strongest event values
            self.abs[-1] = max(self.abs[-1], absv)
            self.slip[-1] = max(self.slip[-1], slip)
            return
        self.d.append(d); self.t.append(t); self.v.append(v)
        self.thr.append(thr); self.brk.append(brk); self.abs.append(absv); self.slip.append(slip)
        self.x.append(x); self.z.append(z)

    def has_map(self):
        return len(self.x) == len(self.d) and sum(1 for v in self.x if v is not None) > 0.8 * len(self.d) > 0

    def complete(self):
        """True if the lap covers the whole track without big gaps."""
        if len(self.d) < 20 or self.track_len <= 0:
            return False
        if self.d[0] > 0.08 * self.track_len or self.d[-1] < 0.92 * self.track_len:
            return False
        gaps = max(b - a for a, b in zip(self.d, self.d[1:]))
        return gaps < 150

    def time_at(self, d):
        """Lap time when the car was at distance d (linear interpolation)."""
        ds = self.d
        if not ds:
            return None
        i = bisect.bisect_left(ds, d)
        if i <= 0:
            return self.t[0]
        if i >= len(ds):
            return self.t[-1]
        d0, d1 = ds[i - 1], ds[i]
        k = (d - d0) / (d1 - d0) if d1 > d0 else 0
        return self.t[i - 1] + k * (self.t[i] - self.t[i - 1])

    def speed_at(self, d):
        i = min(max(bisect.bisect_left(self.d, d), 0), len(self.d) - 1)
        return self.v[i] if self.d else 0.0

    def to_json(self):
        r = lambda xs, n: [round(x, n) for x in xs]
        rn = lambda xs: [None if x is None else round(x, 1) for x in xs]
        return dict(format="phonewheel-lap-1", meta=self.meta, track_len=self.track_len, lap_time=self.lap_time,
                    d=r(self.d, 1), t=r(self.t, 3), v=r(self.v, 1), thr=r(self.thr, 2), brk=r(self.brk, 2),
                    abs=r(self.abs, 2), slip=r(self.slip, 2), x=rn(self.x), z=rn(self.z))

    @classmethod
    def from_json(cls, j):
        lap = cls(float(j["track_len"]))
        lap.lap_time = float(j["lap_time"])
        lap.d, lap.t, lap.v = j["d"], j["t"], j["v"]
        lap.thr, lap.brk, lap.abs, lap.slip = j["thr"], j["brk"], j["abs"], j["slip"]
        lap.x, lap.z = j.get("x") or [], j.get("z") or []
        lap.meta = j.get("meta") or {}
        n = len(lap.d)
        if not (n == len(lap.t) == len(lap.v) == len(lap.thr) == len(lap.brk)) or n < 20:
            raise ValueError("not a valid PhoneWheel lap")
        lap.zones = find_zones(lap)
        return lap


# ----------------------------------------------------------------------------- braking zones
class Zone:
    __slots__ = ("idx", "start_d", "start_t", "peak", "end_d", "end_t", "release_s", "lock", "trail",
                 "apex_d", "min_v", "full_t", "lifts", "spin", "exit_d")

    def __init__(self):
        self.idx = 0
        self.trail = None
        self.lock = False
        self.spin = False
        self.lifts = 0
        self.full_t = None
        self.apex_d = self.min_v = self.exit_d = None

    def to_dict(self):
        return {k: getattr(self, k) for k in self.__slots__}


def find_zones(lap):
    """Braking zones of a finished lap, each with its corner exit."""
    zones = []
    n = len(lap.d)
    i = 0
    while i < n:
        if lap.brk[i] >= 0.15:
            s = i
            while s > 0 and lap.brk[s - 1] > 0.03:
                s -= 1
            z = Zone()
            z.start_d, z.start_t = lap.d[s], lap.t[s]
            peak, peak_i = 0.0, s
            j = s
            quiet = None
            while j < n:
                if lap.brk[j] > peak:
                    peak, peak_i = lap.brk[j], j
                if lap.abs[j] > 0.3:
                    z.lock = True
                if lap.brk[j] < 0.03:
                    if quiet is None:
                        quiet = j
                    if lap.t[j] - lap.t[quiet] > 0.15:
                        break
                else:
                    quiet = None
                j += 1
            end = quiet if quiet is not None else min(j, n - 1)
            z.peak, z.end_d, z.end_t = peak, lap.d[end], lap.t[end]
            z.release_s = max(0.0, lap.t[end] - lap.t[peak_i])
            z.trail = trail_score(lap.t[peak_i:end + 1], lap.brk[peak_i:end + 1], peak)
            if peak >= 0.25 and z.end_t - z.start_t >= 0.25:
                _fill_exit(lap, z, end)
                zones.append(z)
            i = max(j, i + 1)
        else:
            i += 1
    for k, z in enumerate(zones):
        z.idx = k + 1
    return zones


def trail_score(ts, bs, peak):
    """0-100: how cleanly the brake was released from its peak to zero.
    Loses points for releasing too fast (a cliff), re-applying the brake, and a very short release."""
    if peak <= 0 or len(ts) < 2:
        return None
    rel = ts[-1] - ts[0]
    # biggest drop within any 0.1 s window, as a share of the peak
    cliff = 0.0
    j = 0
    for i in range(len(ts)):
        while ts[i] - ts[j] > 0.1:
            j += 1
        cliff = max(cliff, (max(bs[j:i + 1]) - bs[i]) / peak)
    rises = sum(max(0.0, b1 - b0) for b0, b1 in zip(bs, bs[1:])) / peak
    pen = 45 * clamp((cliff - 0.35) / 0.45) + 25 * clamp(rises / 0.15) + 30 * clamp((0.45 - rel) / 0.35)
    return int(round(max(0.0, 100 - pen)))


def clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else hi if v > hi else v


def theoretical_best(ref, laps):
    """Best time for every stretch of track across all laps (split just before each braking zone).
    Returns (total, segments) where segments = [(start_d, end_d, best_time, lap_number, ref_time)]."""
    if ref is None or not ref.zones or ref.track_len <= 0:
        return None, []
    L = ref.track_len
    cuts = sorted({0.0} | {max(0.0, z.start_d - 50) for z in ref.zones}) + [L]
    pool = [l for l in laps if l.valid and l.complete()]
    if ref not in pool and ref.complete():
        pool.append(ref)
    segs = []
    total = 0.0
    for a, b in zip(cuts, cuts[1:]):
        if b - a < 20:
            continue
        best_t, best_n = None, None
        for l in pool:
            ta, tb = l.time_at(a), l.time_at(b)
            if b >= L - 1:
                tb = l.lap_time if l.lap_time else tb
            if ta is None or tb is None:
                continue
            dt = tb - ta
            if dt > 0 and (best_t is None or dt < best_t):
                best_t, best_n = dt, l.number
        if best_t is None:
            return None, []
        rt = (ref.lap_time if b >= L - 1 else ref.time_at(b)) - ref.time_at(a)
        segs.append((a, b, best_t, best_n, rt))
        total += best_t
    return total, segs


def _fill_exit(lap, z, end_i):
    """Apex = slowest point near the end of braking; exit = until full throttle (max 6 s)."""
    n = len(lap.d)
    a = end_i
    k = end_i
    while k < n and lap.d[k] < z.end_d + 250:
        if lap.v[k] < lap.v[a]:
            a = k
        if lap.thr[k] > 0.9 and lap.d[k] > z.end_d + 20:
            break
        k += 1
    z.apex_d, z.min_v = lap.d[a], lap.v[a]
    top = 0.0
    k = a
    while k < n and lap.t[k] - lap.t[a] < 6.0:
        thr = lap.thr[k]
        if top > 0.5 and thr < top - 0.15:
            z.lifts += 1
            top = thr
        top = max(top, thr) if thr >= top else top
        if lap.slip[k] > 0.3:
            z.spin = True
        if thr >= 0.95:
            z.full_t = lap.t[k] - lap.t[a]
            break
        if lap.brk[k] >= 0.15 and lap.d[k] > z.apex_d + 30:  # next braking zone started
            break
        k += 1
    z.exit_d = lap.d[min(k, n - 1)]


# ----------------------------------------------------------------------------- live coach
class Coach:
    def __init__(self, data_dir):
        self.data_dir = data_dir
        self.markers_on = True
        self.lead_s = 0.5
        self.messages_on = True
        self.key = None
        self.best = None          # your own best lap (LapData)
        self.imported = None      # a reference lap loaded from a file (someone else's or an old one)
        self.cur = None           # LapData being recorded
        self.last_lap_counter = None
        self.last_d = None
        self.next_marker = 0
        self.events = []          # outgoing: ("m",) or ("c", text, tone)
        self.delta = None
        self.session = None       # Session (for the report)
        self._live = None         # live zone state
        self._sector_next = 1
        self._sector_delta = 0.0
        self._pending_exit = None

    # ---- settings from the phone: cfg,<markers 0/1>,<lead ms>,<messages 0/1>
    def configure(self, line):
        p = line.split(",")
        try:
            self.markers_on = p[1] == "1"
            self.lead_s = max(0.0, min(2.0, int(p[2]) / 1000.0))
            self.messages_on = p[3] == "1"
        except (IndexError, ValueError):
            pass

    @property
    def reference(self):
        """Lap used for delta, brake markers and corner notes: an imported reference if you loaded one."""
        return self.imported or self.best

    def _say(self, text, tone="info"):
        if self.messages_on:
            self.events.append(("c", text, tone))

    # ---- persistence
    def _ref_path(self, key):
        return os.path.join(self.data_dir, "best_laps", _safe(key[0]) + "__" + _safe(key[1]) + "__" + _safe(key[2]) + ".json")

    def _load_ref(self, key, suffix=""):
        try:
            path = self._ref_path(key)
            if suffix:
                path = path[:-5] + suffix + ".json"
            with open(path) as fh:
                return LapData.from_json(json.load(fh))
        except (OSError, ValueError, KeyError, TypeError):
            return None

    # ---- sharing laps
    def import_lap(self, data):
        """Install a shared lap (JSON text) as the reference for its game / track / car. Returns a message."""
        j = json.loads(data)
        lap = LapData.from_json(j)
        m = lap.meta
        if not m.get("game") or not m.get("track"):
            raise ValueError("this lap file has no game/track information")
        key = (m.get("game"), m.get("track"), m.get("car") or "car")
        path = self._ref_path(key)[:-5] + "__reference.json"
        os.makedirs(os.path.dirname(path), exist_ok=True)
        lap.meta["source"] = m.get("source") or "imported"
        with open(path, "w") as fh:
            json.dump(lap.to_json(), fh)
        if key == self.key:
            self.imported = lap
        mm, ss = divmod(lap.lap_time, 60)
        return f"Reference lap {int(mm)}:{ss:06.3f} installed for {key[1]} / {key[2]} ({key[0]})"

    def remove_reference(self, filename):
        path = os.path.join(self.data_dir, "best_laps", os.path.basename(filename))
        if path.endswith("__reference.json") and os.path.isfile(path):
            os.remove(path)
            if self.key and self._ref_path(self.key)[:-5] + "__reference.json" == path:
                self.imported = None

    def _save_ref(self, lap):
        try:
            path = self._ref_path(self.key)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as fh:
                json.dump(lap.to_json(), fh)
        except OSError:
            pass

    # ---- main entry, called for every telemetry frame (~100 Hz)
    def update(self, f):
        if f is None or not f.live or f.lap_dist is None or f.lap_dist < 0 or f.track_len <= 0:
            return
        key = (f.game, f.track or "track", f.car or "car")
        if key != self.key:
            self.key = key
            self._announced_tb = None
            self.best = self._load_ref(key)
            self.imported = self._load_ref(key, "__reference")
            self.cur = None
            self.last_lap_counter = None
            from report import Session  # local import keeps report optional
            self.session = Session(self.data_dir, f.game, f.track, f.car, f.track_len, self.best)
            self.session.imported = self.imported
        d = f.lap_dist
        L = f.track_len

        # lap finished?
        if self.last_lap_counter is not None and f.lap != self.last_lap_counter and self.cur is not None:
            self._finish_lap(f)
        if self.last_lap_counter is None or f.lap != self.last_lap_counter:
            self.cur = LapData(L)
            self.cur.number = max(0, f.lap) + 1
            self.cur.meta = {"game": f.game, "track": f.track, "car": f.car, "source": "own"}
            self.next_marker = 0
            self._live = None
            self._pending_exit = None
            self._sector_next = 1
            self._sector_delta = 0.0
            self.last_d = None
        self.last_lap_counter = f.lap

        if f.lap_time is None or f.lap_time < 0:
            return
        cell = math.floor(d / GRID) * GRID
        self.cur.add(cell, f.lap_time, f.speed, f.throttle, f.brake, f.v_abs, f.v_slip, f.pos_x, f.pos_z)
        if f.in_pit:
            self.cur.in_pit = True
        if not f.valid:
            self.cur.valid = False

        ref = self.reference
        if ref is not None:
            rt = ref.time_at(d)
            self.delta = (f.lap_time - rt) if rt is not None else None
            self._markers(d, f.speed)
            self._sectors(d, L, f.lap_time)
        else:
            self.delta = None
        self._live_zone(f, d)
        self.last_d = d

    # ---- features
    def _markers(self, d, speed_kmh):
        if not self.markers_on or self.cur.in_pit:
            return
        zones = self.reference.zones
        while self.next_marker < len(zones):
            z = zones[self.next_marker]
            lead = max(15.0, speed_kmh / 3.6 * self.lead_s)
            md = z.start_d - lead
            if d >= md:
                if d - md < 60:   # don't fire for markers we skipped long ago
                    self.events.append(("m",))
                self.next_marker += 1
            else:
                break

    def _sectors(self, d, L, lap_time):
        if self._sector_next > 2 or self.delta is None:
            return
        if d >= L * self._sector_next / 3.0:
            prev = self._sector_delta if self._sector_next > 1 else 0.0
            sec = self.delta - prev
            self._sector_delta = self.delta
            if abs(sec) >= 0.05:
                tone = "good" if sec < 0 else "bad"
                self._say(f"Sector {self._sector_next} {sec:+.2f}s  (lap {self.delta:+.2f})", tone)
            self._sector_next += 1

    def _live_zone(self, f, d):
        """Detects braking zones on the fly and compares them with the best lap."""
        lz = self._live
        b = f.brake
        if lz is None:
            if b >= 0.15:
                self._pending_exit = None  # previous corner's exit ended by this braking zone
                self._live = lz = dict(start_d=d, start_t=f.lap_time, peak=b, peak_t=f.lap_time, lock=False,
                                       quiet=None, start_delta=self.delta, ts=[], bs=[])
            # pending exit evaluation from previous zone
            self._exit_step(f, d)
            return
        if b > lz["peak"]:
            lz["peak"], lz["peak_t"] = b, f.lap_time
            lz["ts"], lz["bs"] = [], []          # release trace starts at the peak
        lz["ts"].append(f.lap_time)
        lz["bs"].append(b)
        if f.v_abs > 0.3:
            lz["lock"] = True
        if b < 0.03:
            if lz["quiet"] is None:
                lz["quiet"] = f.lap_time
            if f.lap_time - lz["quiet"] > 0.15:
                self._brake_report(lz, d)
                self._live = None
                self._pending_exit = dict(apex_d=d, apex_t=f.lap_time, min_v=f.speed, top=0.0, lifts=0,
                                          spin=False, ref=lz.get("ref"), start_delta=lz["start_delta"])
        else:
            lz["quiet"] = None

    def _match_ref(self, start_d):
        if self.reference is None:
            return None
        best, dist = None, 1e9
        for z in self.reference.zones:
            dd = abs(z.start_d - start_d)
            if dd < dist:
                best, dist = z, dd
        return best if dist < 120 else None

    def _brake_report(self, lz, d):
        if lz["peak"] < 0.25:
            return
        ref = self._match_ref(lz["start_d"])
        lz["ref"] = ref
        if ref is None:
            return
        parts = []
        dd = lz["start_d"] - ref.start_d
        if abs(dd) >= 6:
            parts.append(f"{abs(dd):.0f} m {'later' if dd > 0 else 'earlier'}")
        pk, rpk = lz["peak"] * 100, ref.peak * 100
        if abs(pk - rpk) >= 8:
            parts.append(f"peak {pk:.0f}% (best {rpk:.0f}%)")
        rel = max(0.0, lz["quiet"] - lz["peak_t"]) if lz["quiet"] else 0.0
        if rel - ref.release_s > 0.3:
            parts.append("released slower")
        elif ref.release_s - rel > 0.3:
            parts.append("released too quickly")
        if lz["lock"] and not ref.lock:
            parts.append("locked up")
        score = trail_score(lz["ts"], lz["bs"], lz["peak"])
        if score is not None and score < 60 and (ref.trail is None or score < ref.trail - 15):
            parts.append(f"trail release {score}/100" + (f" (best {ref.trail})" if ref.trail is not None else ""))
        if not parts:
            return  # braked like your best lap: nothing to say
        tone = "bad" if (dd < -10 or lz["lock"] or pk < rpk - 12) else "good" if dd > 3 else "info"
        self._say(f"T{ref.idx} brake: " + " · ".join(parts), tone)

    def _exit_step(self, f, d):
        pe = self._pending_exit
        if pe is None:
            return
        if f.speed < pe["min_v"]:
            pe["min_v"], pe["apex_d"], pe["apex_t"] = f.speed, d, f.lap_time
        thr = f.throttle
        if pe["top"] > 0.5 and thr < pe["top"] - 0.15:
            pe["lifts"] += 1
            pe["top"] = thr
        pe["top"] = max(pe["top"], thr)
        if f.v_slip > 0.3:
            pe["spin"] = True
        done_full = thr >= 0.95
        timeout = f.lap_time - pe["apex_t"] > 6.0
        if not (done_full or timeout):
            return
        self._pending_exit = None
        ref = pe["ref"]
        if ref is None:
            return
        parts = []
        full_t = f.lap_time - pe["apex_t"] if done_full else None
        if full_t is not None and ref.full_t is not None and full_t - ref.full_t > 0.25:
            parts.append(f"full throttle {full_t - ref.full_t:.1f}s later")
        if pe["lifts"] > ref.lifts:
            parts.append(f"{pe['lifts']} throttle lift{'s' if pe['lifts'] > 1 else ''}")
        if pe["spin"] and not ref.spin:
            parts.append("wheelspin")
        if ref.min_v and pe["min_v"] < ref.min_v - 4:
            parts.append(f"min speed {pe['min_v']:.0f} (best {ref.min_v:.0f})")
        gained = None
        if self.delta is not None and pe["start_delta"] is not None:
            gained = self.delta - pe["start_delta"]
        if not parts and (gained is None or abs(gained) < 0.05):
            return
        head = f"T{ref.idx}"
        if gained is not None:
            head += f" {gained:+.2f}s"
        if parts:
            self._say(head + ": " + " · ".join(parts), "bad")
        elif gained is not None and gained <= -0.05:
            self._say(head + " ✓ faster than your best", "good")
        elif gained is not None and gained >= 0.05:
            self._say(head + " slower than your best", "bad")

    def _finish_lap(self, f):
        lap = self.cur
        if f.last_lap and f.last_lap > 0:
            lap.lap_time = f.last_lap
        elif lap.t:
            lap.lap_time = lap.t[-1]
        good = lap.complete() and lap.valid and not lap.in_pit and lap.lap_time and lap.lap_time > 10
        lap.valid = bool(good)
        lap.zones = find_zones(lap) if lap.complete() else []
        if self.session is not None:
            self.session.laps.append(lap)
        if good and (self.best is None or lap.lap_time < self.best.lap_time - 1e-3):
            had = self.best is not None
            old = self.best.lap_time if had else None
            self.best = lap
            self._save_ref(lap)
            if self.session is not None:
                self.session.best = lap
            mm, ss = divmod(lap.lap_time, 60)
            txt = f"New best lap {int(mm)}:{ss:06.3f}"
            if had:
                txt += f"  ({lap.lap_time - old:+.3f})"
            self._say(txt, "good")
        elif lap.lap_time and self.best is not None and lap.complete():
            mm, ss = divmod(lap.lap_time, 60)
            self._say(f"Lap {int(mm)}:{ss:06.3f}  ({lap.lap_time - self.best.lap_time:+.3f})"
                      + ("" if good else "  invalid"), "info" if good else "bad")
        self._theoretical()
        if self.session is not None:
            self.session.add_lap(None)   # rewrite the report

    def _theoretical(self):
        if self.session is None:
            return
        total, segs = theoretical_best(self.best, self.session.laps)
        self.session.theoretical = (total, segs)
        prev = getattr(self, "_announced_tb", None)
        if total and self.best and self.best.lap_time - total > 0.05 and (prev is None or total < prev - 0.005):
            self._announced_tb = total
            mm, ss = divmod(total, 60)
            self._say(f"Theoretical best {int(mm)}:{ss:06.3f}  ({total - self.best.lap_time:+.2f} vs your best)", "info")

    def pop_events(self):
        ev, self.events = self.events, []
        return ev
