"""
PhoneWheel session report: a self-contained HTML page (no internet needed) written after every lap.

Shows lap times and consistency, speed / throttle / brake traces of your best and last lap with the
time delta between them, and a per-corner table that points at where the most time is lost.
"""
import datetime
import html
import math
import os
import statistics

LATEST = {"path": None}   # most recent report, served at /report


def fmt_lap(t):
    if t is None or t <= 0:
        return "–"
    m, s = divmod(t, 60)
    return f"{int(m)}:{s:06.3f}"


class Session:
    def __init__(self, data_dir, game, track, car, track_len, best):
        self.dir = os.path.join(data_dir, "reports")
        self.game, self.track, self.car, self.track_len = game, track or "Unknown track", car or "", track_len
        self.best = best                # LapData (may come from an earlier session)
        self.laps = []
        self.started = datetime.datetime.now()
        name = f"{self.started:%Y-%m-%d_%H%M}_{_slug(self.track)}.html"
        self.path = os.path.join(self.dir, name)

    def add_lap(self, lap):
        self.laps.append(lap)
        try:
            self.write()
        except Exception as e:  # never let the report break driving
            print(f"\n[report] could not write report: {e}")

    # ------------------------------------------------------------------ analysis
    def _corner_rows(self):
        best = self.best
        if best is None or not best.zones:
            return []
        rows = []
        valid = [l for l in self.laps if l.valid and l is not best] or [l for l in self.laps if l is not best]
        for z in best.zones:
            a = max(0.0, z.start_d - 50)
            b = z.exit_d if z.exit_d else z.end_d + 150
            losses, mins, locks, spins, starts = [], [], 0, 0, []
            for lap in valid:
                if len(lap.d) < 20:
                    continue
                la, lb = lap.time_at(a), lap.time_at(b)
                ra, rb = best.time_at(a), best.time_at(b)
                if None in (la, lb, ra, rb):
                    continue
                losses.append((lb - la) - (rb - ra))
                m = _match(lap.zones, z.start_d)
                if m is not None:
                    mins.append(m.min_v or 0)
                    starts.append(m.start_d - z.start_d)
                    locks += 1 if m.lock else 0
                    spins += 1 if m.spin else 0
            rows.append(dict(
                z=z, loss=statistics.mean(losses) if losses else None,
                min_avg=statistics.mean(mins) if mins else None,
                start_avg=statistics.mean(starts) if starts else None,
                locks=locks, spins=spins, n=len(losses)))
        return rows

    # ------------------------------------------------------------------ html
    def write(self):
        os.makedirs(self.dir, exist_ok=True)
        laps = self.laps
        valid_times = [l.lap_time for l in laps if l.valid and l.lap_time]
        best_t = self.best.lap_time if self.best else (min(valid_times) if valid_times else None)
        session_best = min(valid_times) if valid_times else None
        near = [t for t in valid_times if best_t and t <= best_t * 1.07]
        consistency = statistics.pstdev(near) if len(near) >= 2 else None
        last = laps[-1] if laps else None
        rows = self._corner_rows()
        top = sorted([r for r in rows if r["loss"] is not None and r["loss"] > 0.02], key=lambda r: -r["loss"])[:3]

        e = html.escape
        out = []
        out.append(f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>PhoneWheel report – {e(self.track)}</title>
<style>{CSS}</style></head><body><main>
<header><div class="kicker">PhoneWheel session report · {e(self.game)} · {self.started:%d %b %Y, %H:%M}</div>
<h1>{e(self.track)}</h1><div class="sub">{e(self.car)}</div></header>
<section class="tiles">
 <div class="tile"><span>Best lap (all time)</span><b>{fmt_lap(best_t)}</b></div>
 <div class="tile"><span>Best this session</span><b>{fmt_lap(session_best)}</b></div>
 <div class="tile"><span>Laps (valid)</span><b>{len(laps)} ({len(valid_times)})</b></div>
 <div class="tile"><span>Consistency (std dev)</span><b>{f"{consistency:.3f}s" if consistency is not None else "–"}</b></div>
</section>""")

        if top:
            out.append('<section><h2>Where you lose the most time</h2><ol class="tips">')
            for r in top:
                z = r["z"]
                why = []
                if r["start_avg"] is not None and r["start_avg"] < -8:
                    why.append(f"braking {abs(r['start_avg']):.0f} m earlier than your best on average")
                if r["min_avg"] is not None and z.min_v and r["min_avg"] < z.min_v - 3:
                    why.append(f"minimum speed {r['min_avg']:.0f} vs {z.min_v:.0f} km/h")
                if r["locks"]:
                    why.append(f"locked up in {r['locks']} lap(s)")
                if r["spins"]:
                    why.append(f"wheelspin on exit in {r['spins']} lap(s)")
                out.append(f"<li><b>T{z.idx}</b> – losing <b>{r['loss']:.2f}s</b> per lap on average"
                           + (f": {', '.join(why)}" if why else "") + "</li>")
            out.append("</ol></section>")

        # lap times chart + table
        if laps:
            out.append("<section><h2>Lap times</h2>")
            pts = [(l.number, l.lap_time) for l in laps if l.lap_time]
            out.append(bar_chart(pts, best_t, [l.valid for l in laps if l.lap_time]))
            out.append('<table><tr><th>Lap</th><th>Time</th><th>vs best</th><th>S1</th><th>S2</th><th>S3</th>'
                       '<th>Lock-ups</th><th>Wheelspin</th><th>Top speed</th><th></th></tr>')
            for l in laps:
                secs = [("" if x is None else f"{x:.2f}") for x in sectors(l)]
                gap = (l.lap_time - best_t) if (l.lap_time and best_t) else None
                gap_s = "" if gap is None else f"{gap:+.3f}"
                tag = "best" if self.best is l else ("" if l.valid else "invalid")
                cls = "best" if self.best is l else ("" if l.valid else "inv")
                locks = sum(1 for z in l.zones if z.lock)
                spins = sum(1 for z in l.zones if z.spin)
                out.append(f"<tr class='{cls}'><td>{l.number}</td><td>{fmt_lap(l.lap_time)}</td>"
                           f"<td>{gap_s}</td>"
                           + "".join(f"<td>{x}</td>" for x in secs)
                           + f"<td>{locks}</td><td>{spins}</td><td>{max(l.v) if l.v else 0:.0f}</td>"
                           f"<td>{tag}</td></tr>")
            out.append("</table></section>")

        # traces
        if self.best is not None and last is not None and len(last.d) > 20:
            b = self.best
            L = self.track_len
            zones = b.zones
            label_last = f"Lap {last.number}"
            out.append(f"<section><h2>Best lap vs {e(label_last)}</h2>"
                       "<p class='note'>Distance along the lap. Grey lines mark your best lap's braking points (T1, T2 …).</p>")
            out.append(line_chart("Speed (km/h)", L, [(b.d, b.v, "best"), (last.d, last.v, "last")], zones))
            out.append(line_chart("Brake (%)", L, [(b.d, [x * 100 for x in b.brk], "best"),
                                                   (last.d, [x * 100 for x in last.brk], "last")], zones, ymax=100))
            out.append(line_chart("Throttle (%)", L, [(b.d, [x * 100 for x in b.thr], "best"),
                                                      (last.d, [x * 100 for x in last.thr], "last")], zones, ymax=100))
            dd, dv = [], []
            for d, t in zip(last.d, last.t):
                rt = b.time_at(d)
                if rt is not None:
                    dd.append(d); dv.append(t - rt)
            out.append(line_chart("Time vs best (s) – going up = losing time", L, [(dd, dv, "delta")], zones, signed=True))
            out.append("</section>")

        # corners
        if rows:
            out.append("<section><h2>Corners</h2><table><tr><th>Corner</th><th>Best: brake at</th><th>Best: peak</th>"
                       "<th>Best: min speed</th><th>Avg time lost</th><th>Avg braking point</th><th>Avg min speed</th>"
                       "<th>Lock-ups</th><th>Exit wheelspin</th></tr>")
            for r in rows:
                z = r["z"]
                loss = r["loss"]
                cls = "hot" if r in top else ""
                loss_s = "" if loss is None else f"{loss:+.2f}s"
                start_s = "" if r["start_avg"] is None else f"{r['start_avg']:+.0f} m"
                min_s = "" if r["min_avg"] is None else f"{r['min_avg']:.0f}"
                out.append(f"<tr class='{cls}'><td>T{z.idx}</td><td>{z.start_d:.0f} m</td><td>{z.peak*100:.0f}%</td>"
                           f"<td>{(z.min_v or 0):.0f}</td><td>{loss_s}</td><td>{start_s}</td><td>{min_s}</td>"
                           f"<td>{r['locks']}</td><td>{r['spins']}</td></tr>")
            out.append("</table><p class='note'>Braking point: + means later than your best lap (good if you still make the corner). "
                       "Time lost is measured from 50 m before braking to the end of the corner exit.</p></section>")

        if not laps:
            out.append("<section><p>Drive a full lap to see your first analysis.</p></section>")
        out.append("<footer>Updates after every lap · this page refreshes itself every 30 s</footer></main></body></html>")
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write("".join(out))
        os.replace(tmp, self.path)
        LATEST["path"] = self.path


def _match(zones, start_d):
    best, dist = None, 1e9
    for z in zones:
        d = abs(z.start_d - start_d)
        if d < dist:
            best, dist = z, d
    return best if dist < 120 else None


def _slug(s):
    return "".join(c if c.isalnum() else "_" for c in s)[:40].strip("_") or "track"


def sectors(lap):
    L = lap.track_len
    if not lap.d or L <= 0 or not lap.complete():
        return [None, None, None]
    t1, t2 = lap.time_at(L / 3), lap.time_at(2 * L / 3)
    end = lap.lap_time or lap.t[-1]
    if t1 is None or t2 is None:
        return [None, None, None]
    return [t1, t2 - t1, end - t2]


# ---------------------------------------------------------------------- charts (inline SVG)
W, H, PL, PR, PT, PB = 1000, 220, 46, 12, 14, 26
COLORS = {"best": "#2f8cff", "last": "#ff8a00", "delta": "#e9edf2"}


def _downsample(xs, ys, n=900):
    if len(xs) <= n:
        return xs, ys
    step = len(xs) / n
    idx = [int(i * step) for i in range(n)]
    return [xs[i] for i in idx], [ys[i] for i in idx]


def line_chart(title, L, series, zones, ymax=None, signed=False):
    allv = [v for _, ys, _ in series for v in ys]
    if not allv:
        return ""
    if signed:
        m = max(0.2, max(abs(v) for v in allv))
        lo, hi = -m, m
    else:
        lo, hi = 0.0, ymax or max(allv) * 1.05 or 1
    sx = lambda x: PL + (W - PL - PR) * (x / L if L else 0)
    sy = lambda y: PT + (H - PT - PB) * (1 - (y - lo) / ((hi - lo) or 1))
    g = [f'<figure><figcaption>{html.escape(title)}</figcaption><svg viewBox="0 0 {W} {H}" role="img">']
    for k in range(5):
        v = lo + (hi - lo) * k / 4
        y = sy(v)
        g.append(f'<line x1="{PL}" x2="{W-PR}" y1="{y:.1f}" y2="{y:.1f}" class="grid"/>'
                 f'<text x="{PL-6}" y="{y+4:.1f}" class="ax" text-anchor="end">{(f"{v:+.1f}" if signed else f"{v:.0f}")}</text>')
    for z in zones:
        x = sx(z.start_d)
        g.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{PT}" y2="{H-PB}" class="zone"/>'
                 f'<text x="{x+3:.1f}" y="{PT+10}" class="zl">T{z.idx}</text>')
    for xs, ys, name in series:
        xs, ys = _downsample(xs, ys)
        if len(xs) < 2:
            continue
        pts = " ".join(f"{sx(x):.1f},{sy(max(lo, min(hi, y))):.1f}" for x, y in zip(xs, ys))
        g.append(f'<polyline points="{pts}" fill="none" stroke="{COLORS.get(name, "#fff")}" stroke-width="1.6"/>')
    g.append(f'<text x="{W-PR}" y="{H-6}" class="ax" text-anchor="end">{L/1000:.2f} km</text>')
    legend = " ".join(f'<span><i style="background:{COLORS.get(n, "#fff")}"></i>{n}</span>' for _, _, n in series)
    g.append(f"</svg><div class='legend'>{legend}</div></figure>")
    return "".join(g)


def bar_chart(pts, best, valid):
    if not pts:
        return ""
    times = [t for _, t in pts]
    lo = min(times) - 0.5
    hi = max(times) + 0.5
    n = len(pts)
    pl = 72  # room for m:ss.sss labels
    bw = (W - pl - PR) / max(n, 1)
    sy = lambda y: PT + (H - PT - PB) * (1 - (y - lo) / ((hi - lo) or 1))
    g = [f'<figure><svg viewBox="0 0 {W} {H}" role="img">']
    for k in range(5):
        v = lo + (hi - lo) * k / 4
        g.append(f'<line x1="{pl}" x2="{W-PR}" y1="{sy(v):.1f}" y2="{sy(v):.1f}" class="grid"/>'
                 f'<text x="{pl-6}" y="{sy(v)+4:.1f}" class="ax" text-anchor="end">{fmt_lap(v)}</text>')
    for i, ((num, t), ok) in enumerate(zip(pts, valid)):
        x = pl + i * bw + bw * 0.15
        y = sy(t)
        col = "#2bd96b" if best and abs(t - best) < 1e-3 else ("#2f8cff" if ok else "#5a6370")
        g.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw*0.7:.1f}" height="{H-PB-y:.1f}" rx="2" fill="{col}">'
                 f'<title>Lap {num}: {fmt_lap(t)}</title></rect>')
        if n <= 40:
            g.append(f'<text x="{x+bw*0.35:.1f}" y="{H-8}" class="ax" text-anchor="middle">{num}</text>')
    if best:
        y = sy(best)
        g.append(f'<line x1="{pl}" x2="{W-PR}" y1="{y:.1f}" y2="{y:.1f}" class="bestline"/>')
    g.append("</svg></figure>")
    return "".join(g)


CSS = """
:root{--bg:#0b0d10;--panel:#14181d;--line:#2a2f37;--text:#e9edf2;--dim:#8a94a3;--accent:#ffcc00}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,Segoe UI,Roboto,sans-serif}
main{max-width:1100px;margin:0 auto;padding:24px 16px 48px}
.kicker{color:var(--dim);font-size:13px;letter-spacing:.04em;text-transform:uppercase}
h1{margin:4px 0 0;font-size:30px}.sub{color:var(--dim)}h2{font-size:18px;margin:28px 0 10px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;margin-top:18px}
.tile{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
.tile span{display:block;color:var(--dim);font-size:12px;text-transform:uppercase;letter-spacing:.06em}.tile b{font-size:24px;font-variant-numeric:tabular-nums}
table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums;font-size:14px;display:block;overflow-x:auto}
th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th{color:var(--dim);font-weight:600}
td:first-child,th:first-child{text-align:left}tr.best td{color:#2bd96b;font-weight:700}tr.inv td{color:#5a6370}tr.hot td{background:#ff3b3014}
figure{margin:10px 0 18px;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:10px}
figcaption{color:var(--dim);font-size:13px;margin:0 0 4px 4px}svg{width:100%;height:auto;display:block}
.grid{stroke:#ffffff14}.ax{fill:#8a94a3;font-size:11px}.zone{stroke:#ffffff30;stroke-dasharray:3 4}.zl{fill:#8a94a3;font-size:10px}
.bestline{stroke:#2bd96b;stroke-dasharray:6 4}.legend{display:flex;gap:16px;color:var(--dim);font-size:12px;margin:4px 0 0 4px}
.legend i{display:inline-block;width:14px;height:3px;margin-right:6px;vertical-align:middle;border-radius:2px}
.tips li{margin:6px 0}.note{color:var(--dim);font-size:13px}footer{color:#5a6370;font-size:12px;margin-top:30px}
"""
