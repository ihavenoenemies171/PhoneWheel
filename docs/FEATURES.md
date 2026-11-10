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

## Layout editor

Go to **SETUP → Edit button layout.** The wheel switches to edit mode:
- **Drag** a tile to move it. Drop it on another tile to **swap** them.
- **Tap** a tile to change its function (any of the 32 buttons, an analog **clutch** or an analog **handbrake**), label, small text, colour and size.
- **Tap a `+`** cell to add a tile.
- **◀ PAGE ▶** switches pages.
- **OPTIONS** sets the grid size (2–6 columns and rows), pedal-strip and paddle widths, dashboard height, and whether the paddles swap sides. It's also where you add, rename and delete pages, or reset to the standard GT3 layout.
- **DONE** saves the layout in the current profile.

Clutch and handbrake tiles are analog: slide up inside the tile for more. They drive vJoy axes **Rx** and **Ry**.

## Profiles

A profile holds **all settings plus the layout**. Manage them in **SETUP → Profile**:
- **Switch:** change to another profile.
- **New:** copy the current profile under a new name.
- **Rename:** give the current profile a new name.
- **Link to game / car:** while a game is running, use the profile automatically whenever that **car** (or that **game**) is detected. For example, a GT3 profile with a 90° range and a rally profile with a handbrake lever.
- **Delete:** remove the profile.

When a car or game is detected, the app first uses a profile linked to that car, then one linked to the game, and otherwise keeps your current profile. The connection settings are shared by all profiles.

## Second device: button box + dashboard

On a second phone or tablet, install the app and choose **SETUP → Layout → Second device: button box + dashboard**:
- It shows a bigger dashboard plus your button pages, with no steering or pedals.
- Its buttons are **combined** with the wheel's, so either device can press any button.
- Both devices get vibration and dashboard data.
- The tilt sensor stays off on the button box, which saves battery.

## Steering

| Setting | What it does |
|---|---|
| Steering range | How far you rotate the phone from full left to full right (30–360°). Start at 90°. |
| Gyro-fused sensor | Uses the gyroscope and accelerometer together: smoother, faster, and not affected by bumps or moving in your chair. Turn it off only if steering slowly drifts. |
| Speed-sensitive steering | Needs more phone rotation at high speed, for calmer steering on straights. Needs a game with telemetry. |
| Match each car's steering lock | RaceRoom and LMU report each car's real steering lock. The phone range follows it, so the same tilt always means the same wheel angle. *Wheel degrees per phone degree* sets the ratio: at 6, a 540° car needs 90° of phone rotation. |
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
  - Priority order: impact, then ABS / lock-up, then oversteer, then understeer, then wheelspin / slide, then kerb hit, then off-track.
  - ABS and lock-up only play while you're braking. Wheelspin only plays when you're off the brake. Small amounts of slip are ignored.
  - Kerbs and leaving the track give a short burst, not constant buzzing. A slide that keeps going fades after about a second.
- **Immersive:** every effect layered at once, without priorities.
- **Off:** no game vibration. Button clicks still work.

**Effects and how they feel**

Every effect has its own rhythm, so you can tell them apart without looking:

| Effect | Rhythm | What it means / what to do |
|---|---|---|
| ABS / lock-up | Fast, even pulses, like the pedal kicking back | Too much brake for the grip: ease off |
| Understeer | A soft, smooth wobble that never feels sharp | The steering is "going light": slow down or unwind the wheel |
| Oversteer | A strong "ba-dum … ba-dum" heartbeat | The rear is stepping out: catch it |
| Wheelspin / slide | A steady, unbroken rumble that grows with the spin | Feed the throttle in more gently |
| Kerbs | Three distinct knocks, "da-da-da", like a rumble strip | You're on the kerb |
| Off track | An irregular random crackle, like gravel; reminder every 2 s | You're off the track |
| Impacts | One heavy thud that dies away | Contact |
| Brake marker | Two tiny crisp ticks, "tick-tick" | Your braking point is coming |
| Brake detent | One tiny tick at 25 / 50 / 75 % brake | Where you are on the pedal |
| Clicks | Paddles: a short firm clunk. Buttons: a light tap | Your press registered |

There are no gear-shift, rev-limiter or road-texture vibrations. The paddle click already confirms a shift, the blue shift-point flash covers the limiter, and constant road grain only hides the signals that matter.

Each effect has a strength slider. To learn how they feel, open **SETUP → Vibration → Test effects**. A panel opens at the bottom of the screen:
- **Play all** goes through every effect in order.
- **Tap any effect** to play just that one, as often as you like.
- The panel shows the name of the effect that's playing, what it means, and whether it's set to vibration, flash or both.
- A **TESTING** banner appears at the top of the wheel, and flashes show on the screen edges and pedal strips exactly as they would while driving. Phones that can only switch their motor on and off (no strength control) get simpler effects.

## Vibrate or flash

For each signal, choose **Vibrate**, **Flash** or **Both**:

| Signal | Flash |
|---|---|
| Brake marker | The whole screen flashes white twice |
| ABS / lock-up | The brake strip pulses orange |
| Wheelspin / slide | The throttle strip glows yellow |
| Understeer | The top edge glows blue |
| Oversteer | Both side edges flash red |
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

- **Which best:** by default, your **best lap of this session**, the same lap the game's own delta uses. In ACC the phone then shows the game's delta number exactly. Until you've set a lap this session, it uses your all-time best. Turn on **SETUP → Driving coach → Compare with my all-time best** to chase your personal record instead. That setting applies to the delta, brake markers and corner notes.
- **Saving:** your all-time best for every track and car is saved by PhoneWheel.exe and **replaced every time you beat it** ("New best lap"). A faster lap that isn't an all-time best still becomes your session best ("Session best").
- **Which laps count:** invalid laps (track limits), laps that go through the pits and incomplete laps don't count, just like in the game.
- **Starting out:** drive one clean lap to set the reference.

| Feature | Example |
|---|---|
| Live delta | `−0.24` under the speed, updated continuously |
| Brake markers | Tick-tick (and/or a flash) about 0.5 s before each best-lap braking point. Adjust with *Marker timing*. |
| Brake note | `T3 brake: 9 m earlier · peak 74% (best 88%) · released too quickly · locked up` |
| Corner note | `T3 +0.21s: full throttle 0.4s later · 1 throttle lift · wheelspin` or `T3 −0.08s ✓ faster than your best` |
| Sector split | `Sector 2 −0.12s (lap −0.30)` |
| Lap | `New best lap 1:47.312 (−0.204)` |

The coach **stays quiet** when you did something the same as your best lap. Notes appear on the dashboard for 4 seconds and are also printed in the PhoneWheel.exe window.

### Theoretical best lap

The coach splits the track into stretches, cutting just before each braking zone, and adds up your **fastest time on each stretch from any lap**. That's your theoretical best: what you'd do if you put your best corners together. The coach announces it when it improves ("Theoretical best 1:46.81 (−0.42 vs your best)"). The report shows which lap each fastest stretch came from, and where those stretches beat your best lap, there's time to find.

### Trail-brake score

Every braking zone gets a score from 0 to 100 for how cleanly you released the brake from its peak. It loses points for:
- dropping it suddenly (a "cliff");
- re-applying it;
- a stab release that's too short.

You see it in the report's corner table. The coach adds it to a brake note when it's clearly worse than your best lap, for example "trail release 48/100 (best 85)".

### Reference laps

Your best lap for every game, track and car is saved, and you can **share** it. Open **http://localhost:8765/laps** on the PC, or tap **SETUP → Reference laps** on the phone. There you can:
- **Download** a lap file to send to a friend;
- **Load** a lap file as your **reference**. The live delta, brake markers and corner notes then compare you with *that* lap (a faster friend's, or an old lap of yours) instead of your own best;
- **Remove** the reference to go back to your own best.

### Report

Open **http://localhost:8765/report** on the PC, or tap **SETUP → Open session report** on the phone. There is **one report per track and car**, with **every lap you've driven there**, across all sessions, grouped by session (newest first). It updates after every lap and shows:

- best lap (all time and this session), laps this session and in total, and consistency (standard deviation of this session's laps within 107 % of the best);
- **Where you lose the most time:** the top three corners, with the reasons (braking earlier, lower minimum speed, lock-ups, wheelspin);
- lap times chart (last 60 laps) and a table of every lap with sectors, lock-ups, wheelspin and top speed;
- speed, brake and throttle traces of your best lap against your last lap, with the braking points marked;
- a time-vs-best graph: when the line goes up, you're losing time there;
- a **track map** coloured by where you gain (green) and lose (red) time on average over your last 30 laps, with corner labels;
- the **theoretical best** table;
- a corner table: braking point, peak pressure, minimum speed and average time lost per corner.

All your tracks and cars are listed at http://localhost:8765/reports. The files are in the `reports` folder (next to PhoneWheel.exe, or Documents\PhoneWheel for the installed version): `<game>__<track>__<car>.html` is the report and `.laps.jsonl` next to it holds the laps. Reports from older versions (one file per session) stay in the list.

**A good routine:**
1. Drive 10 laps.
2. Open the report.
3. Pick the **one** corner at the top of *Where you lose the most time*.
4. Work only on that corner, using the brake markers and notes, until it's no longer at the top.

## Battery and heat

The app redraws the screen only when something changes, and runs the tilt sensor at 200 Hz instead of the phone's maximum. Settings in **SETUP → Battery**:
- **Screen brightness while driving:** your eyes are on the monitor, so 20–40 % is plenty and saves the most battery.
- **Screen frame rate:** 30, 45, 60, 75 or 90 fps.
- **Steering sensor rate:** 100 or 200 Hz.

You get a warning at 15 %, 10 % and 5 % battery when you're not charging. USB tethering charges the phone while you drive. You also get a warning if the phone gets hot.

## Diagnostics

**SETUP → Help → Diagnostics** shows:
- **Connection:** ping (with a graph), packet rates, sensor rate and frame rate;
- **Phone:** battery level, temperature and thermal state;
- **Touch and vibration:** touch-size (pressure) support and vibration support;
- **Game data:** every live game channel, including understeer and oversteer.

**Send debug log to PC** saves a phone log and a matching PC log in the `logs` folder: next to PhoneWheel.exe, or **Documents\PhoneWheel\logs** for the installed version. Attach both when you report a problem.

## Browser version (no app)

PhoneWheel.exe also serves a simple browser version, which is useful for iPhones:
- **WiFi:** `https://<PC-IP>:8766`. Accept the certificate warning the first time.
- **USB:** `http://localhost:8765`, after running `usb_android.bat`.

It has tilt steering, pedals and buttons, but no game vibration, dashboard or coach.
