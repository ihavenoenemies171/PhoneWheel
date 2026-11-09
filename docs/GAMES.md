# Game setup

PhoneWheel shows up in games as a controller called **vJoy Device**:

| vJoy input | PhoneWheel control |
|---|---|
| **X axis** | Steering (centre = straight) |
| **Y axis** | Throttle (0 = released) |
| **Z axis** | Brake (0 = released) |
| **Rx axis** | Clutch lever (optional; add it in *Edit button layout*) |
| **Ry axis** | Handbrake lever (optional) |
| **Buttons 1–32** | Paddles and wheel buttons (see the map below) |

## General binding tips

- Keep **PhoneWheel.exe running** and the **app connected** (green dot) *before* you start the game. Most games only look for controllers when they start, so restart the game if it doesn't see vJoy.
- **Axes:** in the game, click the control you want to assign. Then tap **SETUP → Bind controls → Steering / Throttle / Brake / Clutch / Handbrake** on the phone. The app moves only that axis for 4 seconds.
- **Buttons:** turn on **LOCK** in the app's top bar (it freezes steering at centre), click the action in the game, then tap the button on the phone. Turn on **SETUP → Show button numbers** if a game asks for a button number.
- Set the game's own steering and pedal **gamma/linearity to 1.0 (linear)**. Do the shaping in the PhoneWheel app instead.

## Button map

| # | Button | # | Button | # | Button |
|---|---|---|---|---|---|
| 1 | **Upshift** (left paddle) | 12 | Engine map + | 23 | Look back |
| 2 | **Downshift** (right paddle) | 13 | Engine map − | 24 | Camera |
| 3 | Pit limiter | 14 | Brake bias + (front) | 25 | MFD up |
| 4 | Headlights | 15 | Brake bias − (rear) | 26 | MFD down |
| 5 | Flash lights | 16 | Ignition | 27 | MFD left |
| 6 | Wipers | 17 | Starter | 28 | MFD right |
| 7 | Rain light | 18 | Indicator left | 29 | MFD OK / enter |
| 8 | TC + | 19 | Indicator right | 30 | MFD page |
| 9 | TC − | 20 | Hazards | 31 | ESC / menu |
| 10 | ABS + | 21 | Look left | 32 | HUD cycle |
| 11 | ABS − | 22 | Look right | | |

Buttons 3–19 are on the **DRIVE** page and 20–32 on the **MFD** page; tap the tabs in the top bar to switch.

**Look left / right / back** work as toggles: tap once to look, and tap again (or tap another look button) to return. Games only look while the button is held, so a quick tap would snap straight back. You can switch them back to hold in **SETUP → Buttons**.

---

## Assetto Corsa Competizione (ACC)

**Controls**
1. Go to **Options → Controls**, then create or select a **custom** wheel preset. Don't use "Keyboard" or "Gamepad".
2. Bind **Steer** to the X axis using the binding helper, **Throttle** to Y and **Brake** to Z.
3. Set **Steer lock** to the car's real lock (or auto). Set **Steer linearity** and **Brake/Throttle gamma** to **1.0**.
4. Check in **Calibration** that throttle and brake both reach 0–100 %.
5. Bind **Gear up / down**, pit limiter, TC/ABS/MAP/BB ±, lights, wipers, ignition, starter, indicators and the MFD buttons using the map above.

**Telemetry:** built in, nothing to install. The PhoneWheel window shows `[ACC]` while you're on track.

**Tips**
- If look left/right snaps back straight away, close other programs that create virtual gamepads (phone-as-gamepad apps, remote-play receivers). ACC's camera reads their centred sticks. Also make sure look left/right isn't bound to anything else.
- While you're learning, a higher ABS setting forgives over-braking, and the ABS vibration tells you when you went too far.

## RaceRoom Racing Experience

**Controls**
1. Go to **Options → Controls** and create a new profile, choosing **Custom** as the controller type.
2. Bind **Steer left/right** to the X axis, **Accelerate** to Y and **Brake** to Z.
3. Bind **Shift up / down** and the other buttons.
4. If the car feels twitchy, lower **steering sensitivity** in RaceRoom or reduce **Steering range** / raise **Linearity** in the app.

**Telemetry:** built in (shared memory API v3). The PhoneWheel window shows `[RaceRoom]`.

## Le Mans Ultimate (LMU)

**Controls**
1. Go to **Settings → Controls** and select vJoy as the device.
2. Bind **Steering** to the X axis, **Throttle** to Y and **Brake** to Z.
3. Bind **Shift up / down** and the other buttons.
4. Set the steering lock and sensitivity in the game to taste.

**Telemetry:** LMU needs the free **rF2 Shared Memory Map plugin** to share live data. If you use **SimHub** or **CrewChief**, they install it for you. To install it by hand:
1. Download `rFactor2SharedMemoryMapPlugin64.dll` from https://github.com/TheIronWolfModding/rF2SharedMemoryMapPlugin/releases
2. Copy it into LMU's plugin folder, `Le Mans Ultimate\Plugins\`. On some versions it's `Le Mans Ultimate\Bin64\Plugins\`; use whichever exists.
3. Start LMU once and close it. Open `Le Mans Ultimate\UserData\player\CustomPluginVariables.json` and set the plugin's `" Enabled"` value to `1`.
4. Start LMU. The PhoneWheel window shows `[LMU]` while you're on track.

Without the plugin, controls still work, but there's no vibration from the game, no dashboard data and no coach.

## Other games

Any PC sim that supports DirectInput wheels can use PhoneWheel's controls: rFactor 2, Automobilista 2, iRacing, Assetto Corsa, BeamNG and others. Telemetry features (vibration effects, dashboard, coach) currently cover only ACC, RaceRoom and LMU. Pull requests for more games are welcome; see [BUILDING.md](BUILDING.md#adding-a-game).

---

## What feedback each game supports

| Effect | ACC | RaceRoom | LMU |
|---|---|---|---|
| ABS / lock-up | ✅ ABS + lock-up | ✅ ABS + lock-up | ✅ lock-up (from wheel slip) |
| Kerbs | ✅ | ✅ | ✅ |
| Wheelspin / slide | ✅ | ✅ | ✅ |
| Off-track (grass, gravel) | ✅ | ✅ | ✅ |
| Road texture (Immersive mode) | ✅ from the game's FFB | – | ✅ from the game's FFB |
| Understeer / oversteer | ✅ from tyre slip angles | ✅ from yaw rate vs steering | ✅ from tyre sliding |
| Impacts | ✅ | ✅ | ✅ |
| Gear shift / rev limiter | ✅ | ✅ | ✅ |
| Dashboard: TC / TC2 / ABS / MAP | ✅ | TC / ABS / MAP | – (shows "–") |
| Dashboard: brake bias, fuel | ✅ | ✅ | ✅ |
| Driving coach and session report | ✅ | ✅ | ✅ |
| Track map in the report | ✅ | ✅ | ✅ |
| Car steering lock (for *Match each car's steering lock*) | – | ✅ | ✅ |
