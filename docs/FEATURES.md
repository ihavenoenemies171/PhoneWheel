# Features & settings

## The screen

```
┌────────┬─────┬───────────────────────────────────────────┬─────┬──────────┐
│        │     │ CENTER │ LOCK │ DRIVE │ MFD │ SETUP        │     │          │
│        │     ├───────────────────────────────────────────┤     │          │
│ BRAKE  │ UP  │ ●●●●●●●●●●●●●●● shift lights               │DOWN │ THROTTLE │
│        │  ▲  │ 187 km/h │   4   │ TC 3 │ TC2 2 │ ABS 4    │  ▼  │          │
│ (left  │     │ −0.24    │ gear  │ MAP 1│ BB 56.5│ FUEL 41 │     │ (right   │
│  edge) │     ├───────────────────────────────────────────┤     │  edge)   │
│        │     │ ● PC 6 ms           steering bar    +12%  │     │          │
│        │     ├───────────────────────────────────────────┤     │          │
│        │     │  4 × 4 grid of wheel buttons              │     │          │
└────────┴─────┴───────────────────────────────────────────┴─────┴──────────┘
```

- **Brake / throttle strips:** slide your thumb up for more. The number at the top is the current amount, and the faint lines are 10 % steps.
- **UP / DOWN paddles:** upshift is on the left, downshift on the right.
- **CENTER:** sets the phone's current angle as straight ahead.
- **LOCK:** freezes steering at centre, for binding buttons.
- **DRIVE / MFD:** switches between the two button pages.
- **SETUP:** opens all settings. Inputs are released while it's open.
- **Steering bar:**
  - On the left: connection status and ping. Green means connected.
  - On the right: the current steering value.
  - Drag along the bar to steer by touch (for testing).
- **Dashboard:** shift lights, speed, live delta, gear and car settings. See below.

## Steering

| Setting | What it does |
|---|---|
| Steering range | How far you rotate the phone from full left to full right (30–360°). Start at 90°. |
| Gyro-fused sensor | Uses the gyroscope and accelerometer together: smoother, faster, and not affected by bumps or moving in your chair. Turn it off only if steering slowly drifts. |
| Speed-sensitive steering | Needs more phone rotation at high speed, for calmer steering on straights. Needs a game with telemetry. |
| Center deadzone | Degrees around centre that are ignored. |
| Smoothing | Filters sensor noise. Higher is steadier but adds a little lag. |
| Linearity | Above 1 is softer around centre, below 1 is sharper. |
| Invert steering | Use it if steering goes the wrong way on your phone. |

## Pedals

| Setting | What it does |
|---|---|
| Brake detents | You feel a tick each time the brake passes evenly spaced points (3 = 25 / 50 / 75 %). You can tell how hard you're braking without looking. |
| Detent strength | How strong the detent tick is. |
| Brake trace | After each stop, the brake strip shows a white line of what you did (time runs left to right) plus your peak in yellow. It fades after 6 s. A good trail brake is a quick rise and a steady slope down to zero. |
| Brake pressure feel | Pressing harder adds brake. It uses how much of your thumb touches the screen, which not every phone reports. |
| Brake release smoothing | The brake lets go more gently, so a quick thumb lift doesn't drop the brake suddenly. |
| Relative pedals | Off: the height on the strip sets the amount. On: the amount is how far you slide up from where you first touched. |
| Pedal deadzone / saturation | The bottom part is ignored (so you can rest a thumb there); the top part already counts as 100 %. |
| Throttle / brake curve | Above 1 gives finer control at low amounts. 1.5–1.8 is good for the brake. |

## Vibration (force feedback)

PhoneWheel.exe reads the game's live data and the phone turns it into vibration.

**Presets**
- **Essential (default):** only feedback you can act on, and one effect at a time.
  - Priority order: impact, then ABS / lock-up, then wheelspin / slide, then kerb hit, then off-track.
  - ABS and lock-up only play while you're braking. Wheelspin only plays when you're off the brake. Small amounts of slip are ignored.
  - Kerbs and leaving the track give a short burst, not constant buzzing. A slide that keeps going fades after about a second.
- **Immersive:** everything layered, including road texture, gear shift ticks and the rev limiter.
- **Off:** no game vibration. Button clicks still work.

**Effects and how they feel**

| Effect | Feel |
|---|---|
| ABS / lock-up | Fast, even pulses, like a pedal kicking back |
| Wheelspin / slide | A smooth rumble that builds up |
| Kerbs | A short, rough rattle |
| Off track | A rough burst, with a reminder every 2 s while you're still off |
| Impacts | One hard jolt |
| Brake marker | "Tick-tick" just before your braking point |
| Brake detent | A tiny tick at 25 / 50 / 75 % brake |
| Gear shift, rev limiter (Immersive) | A single tick, or a buzz |

Each effect has a strength slider. **Test all effects** plays them in order so you can learn them. Phones that can only switch their motor on and off (no strength control) get simpler effects.

## Vibrate or flash

For each signal, choose **Vibrate**, **Flash** or **Both**:

| Signal | Flash |
|---|---|
| Brake marker | The whole screen flashes white twice |
| ABS / lock-up | The brake strip pulses orange |
| Wheelspin / slide | The throttle strip glows yellow |
| Shift point | The screen edge blinks blue |
| Coach messages | A short green (better) or red (worse) glow |

The flashes are big and bright so you catch them in the corner of your eye while watching the monitor. Moving ABS and wheelspin to *Flash* leaves your thumb free for kerbs, impacts and brake markers.

## Dashboard

- **Shift lights:**
  - 15 LEDs, green then yellow then red.
  - They **flash blue** at the shift point.
  - They **alternate blue** when the pit limiter is on.
  - Set when they start in *Shift lights start*.
- **Speed** and **live delta** to your best lap: green means you're faster, red slower.
- **Gear:** R, N, 1–8.
- **TC, TC2, ABS, MAP:** the current car settings. TC and ABS light up while those systems are working.
- **BB:** brake bias, % front.
- **FUEL:** litres, plus laps left when the game reports it. It turns red under 1.5 laps.
- When there's no game data, it says *"Start ACC, RaceRoom or LMU to see live data"*.

## Driving coach

The coach compares every lap with **your best clean lap** on the same track and car:

- **Saving:** best laps are saved by PhoneWheel.exe, so they carry over between sessions.
- **Which laps count:** invalid laps, laps that go through the pits and incomplete laps don't count.
- **Starting out:** drive one clean lap to set the reference. It updates whenever you go faster.

| Feature | Example |
|---|---|
| Live delta | `−0.24` under the speed, updated continuously |
| Brake markers | Tick-tick (and/or a flash) about 0.5 s before each best-lap braking point. Adjust with *Marker timing*. |
| Brake note | `T3 brake: 9 m earlier · peak 74% (best 88%) · released too quickly · locked up` |
| Corner note | `T3 +0.21s: full throttle 0.4s later · 1 throttle lift · wheelspin` or `T3 −0.08s ✓ faster than your best` |
| Sector split | `Sector 2 −0.12s (lap −0.30)` |
| Lap | `New best lap 1:47.312 (−0.204)` |

The coach **stays quiet** when you did something the same as your best lap. Notes appear on the dashboard for 4 seconds and are also printed in the PhoneWheel.exe window.

### Session report

Open **http://localhost:8765/report** on the PC, or tap **SETUP → Open session report** on the phone. The report updates after every lap and shows:

- best lap (all time and this session), number of laps, and consistency (standard deviation of your laps within 107 % of the best);
- **Where you lose the most time:** the top three corners, with the reasons (braking earlier, lower minimum speed, lock-ups, wheelspin);
- lap times chart and table with sectors, lock-ups, wheelspin and top speed;
- speed, brake and throttle traces of your best lap against your last lap, with the braking points marked;
- a time-vs-best graph: when the line goes up, you're losing time there;
- a corner table: braking point, peak pressure, minimum speed and average time lost per corner.

Older sessions are at http://localhost:8765/reports and in the `reports` folder next to PhoneWheel.exe.

**A good routine:**
1. Drive 10 laps.
2. Open the report.
3. Pick the **one** corner at the top of *Where you lose the most time*.
4. Work only on that corner, using the brake markers and notes, until it's no longer at the top.

## Browser version (no app)

PhoneWheel.exe also serves a simple browser version, which is useful for iPhones:
- **WiFi:** `https://<PC-IP>:8766`. Accept the certificate warning the first time.
- **USB:** `http://localhost:8765`, after running `usb_android.bat`.

It has tilt steering, pedals and buttons, but no game vibration, dashboard or coach.
