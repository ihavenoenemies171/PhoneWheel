# PhoneWheel: an Android app that turns your phone into a GT3 wheel

For **Assetto Corsa Competizione**, **RaceRoom** and **Le Mans Ultimate**.

- **Steering:** tilt the phone like a wheel.
- **Brake:** big analog strip on the **left edge**.
- **Throttle:** big analog strip on the **right edge**. Slide your thumb higher to press harder; it's gradual, not on/off.
- **Upshift paddle on the left, downshift paddle on the right.**
- **Wheel buttons:** two pages of GT3 controls (pit limiter, lights, wipers, TC, ABS, engine map, brake bias, ignition, starter, indicators, MFD keys, look left/right/back, and more).
- **Connection:** WiFi (finds your PC automatically) or USB cable.
- **Vibration "force feedback"** from live game data: ABS/lock-ups, kerbs, wheelspin, road surface, impacts, gear shifts and the rev limiter.
- **Dashboard:** shift lights, gear, speed, TC/TC2/ABS/MAP/BB, fuel and lap delta.
- **Brake extras:** optional pressure feel (press harder = more brake) and release smoothing for trail braking.
- **Steering extras:** gyro-fused sensor and speed-sensitive steering.
- **Binding helper:** sweeps one axis so the game picks the right control.

On the PC, `PhoneWheel.exe` feeds a **vJoy** virtual controller. Windows and the games see an ordinary analog wheel (X = steering, Y = throttle, Z = brake, 32 buttons).

```
PhoneWheel app ──WiFi (UDP) or USB──► PhoneWheel.exe ──► vJoy virtual wheel ──► ACC / RaceRoom / LMU
```

---

## Step 1: Build the app and the .exe (free, about 10 minutes, one time)

GitHub builds both files for you.

1. Create a free account at **github.com** if you don't have one.
2. Click **+ → New repository**. Name it `PhoneWheel`, choose **Public** (Actions are unlimited and free for public repositories), and click **Create repository**.
3. On the new empty repository page, click **"uploading an existing file"**.
4. Unzip `PhoneWheel-source.zip` on your PC. Open the `PhoneWheel` folder and select **everything inside it**: the `.github`, `android` and `pc` folders plus `README.md` and `.gitignore`. Drag it all into the browser, then click **Commit changes**.
   - Make sure the `.github` folder was uploaded. It contains the build instructions.
5. Open the **Actions** tab. A job called **Build PhoneWheel** starts automatically and takes about 5–8 minutes.
6. When it shows a green tick, go to the repository's **Releases** (right side of the main page). You'll find:
   - **PhoneWheel.apk**: the Android app
   - **PhoneWheel-PC.zip**: the Windows program

If a step goes red, open it, copy the error text, and send it to Claude.

## Step 2: Install

### Phone
Open the repository's **Releases** page in Chrome on the phone and download **PhoneWheel.apk**. Tap it to install, and allow **"Install unknown apps"** for Chrome when Android asks. (If Play Protect warns about an unknown developer, tap **Install anyway**. The app is yours, built from this code.)

### PC (Windows 10/11)
1. Install **vJoy 2.2.2.0**, the signed build that works on Windows 11: https://github.com/BrunnerInnovation/vJoy/releases
2. Open **Configure vJoy** from the Start menu and select device **1**:
   - Axes: tick **X, Y, Z**
   - Buttons: **32**
   - POV hats: **0**
   - Tick **Enable vJoy**, then click **Apply**.
3. Unzip **PhoneWheel-PC.zip** into any folder and double-click **PhoneWheel.exe**.
   - The first time, Windows SmartScreen may say "unrecognized app". Click **More info → Run anyway**.
   - When Windows Firewall asks, tick **Private networks** and click **Allow**. This is required for WiFi.
4. Close SimHub or any other program that might be using vJoy device 1.

## Step 3: Drive

### WiFi
- Put the phone and PC on the same WiFi network, start PhoneWheel.exe, then open the app.
- The status line turns **green** and shows your PC's name and the delay in ms.
- If it keeps searching, tap **SETUP → "WiFi – type PC address"** and type the address that PhoneWheel.exe prints. Some routers block discovery.

### USB tethering (easy and low lag, recommended)
No developer mode or adb needed.
1. Plug the phone into the PC.
2. On the phone, go to **Settings → Network / Connections → Hotspot & tethering** and turn on **USB tethering**.
3. **First time only:** on the PC, double-click **network_setup.bat** and click **Yes** when Windows asks for permission. It does two things:
   - lets PhoneWheel through the firewall, because Windows treats the tethering connection as "Public";
   - makes sure the PC keeps using its own internet instead of the phone's data.
4. Start PhoneWheel.exe and open the app. The connection setting **Automatic** finds the PC.

### Phone hotspot (also low lag)
If your PC has WiFi, turn on the phone's **hotspot** and join it from the PC. There's no router in the middle, so ping is lower and steadier. Leave the app on **Automatic**.

### Reducing WiFi lag
- The app switches the phone's WiFi to **low-latency mode** while it's open. Android's WiFi power saving was the main cause of ping spikes.
- Use **5 GHz** WiFi, keep the phone near the router, and connect the PC with a **cable** if you can.
- The number next to the green dot is the typical ping. A "±" after it means the ping is jumping around.

### USB cable via adb (advanced)
1. On the phone, enable **Developer options → USB debugging**. (Settings → About phone → tap **Build number** 7 times.)
2. Download **SDK Platform-Tools for Windows** from Google and unzip it so that `platform-tools\adb.exe` sits next to PhoneWheel.exe.
3. Plug in the phone, run **usb_android.bat**, and accept the prompt on the phone.
4. In the app, tap **SETUP → "USB cable"**.
5. Re-run `usb_android.bat` whenever you re-plug the phone.

### In the app
- Hold the phone level and tap **CENTER** to set straight ahead.
- **LOCK** freezes the steering. Use it while binding pedals and buttons in a game so the game doesn't grab the steering axis by mistake.
- Drag the steering bar with a finger to steer by touch (for testing).
- **DRIVE / MFD** switches between the two button pages.
- **SETUP** has the connection mode, steering range, deadzone, smoothing, linearity, invert, pedal deadzone, pedal curves, vibration, and "show button numbers".

---

## Vibration and dashboard (game telemetry)

PhoneWheel.exe reads live data from the game and sends it to the phone. The dashboard shows *"Start ACC, RaceRoom or LMU to see live data"* until a game is running and you're on track. The PC window also shows `[ACC]`, `[RaceRoom]` or `[LMU]` when it detects a game.

- **ACC:** works automatically.
- **RaceRoom:** works automatically.
- **Le Mans Ultimate:** needs the free **rF2 Shared Memory Map** plugin. SimHub and CrewChief install it for you. To install it by hand:
  1. Download `rFactor2SharedMemoryMapPlugin64.dll` from https://github.com/TheIronWolfModding/rF2SharedMemoryMapPlugin/releases
  2. Copy it into LMU's plugin folder: `Le Mans Ultimate\Plugins\`. On some versions it's `Le Mans Ultimate\Bin64\Plugins\`; use whichever exists.
  3. Start LMU once and close it. Then open `Le Mans Ultimate\UserData\player\CustomPluginVariables.json` and make sure the plugin's `" Enabled"` value is `1`.

| Effect | What you feel | ACC | RaceRoom | LMU |
|---|---|---|---|---|
| ABS / lock-up | Hard pulses while ABS works or the front wheels lock | ✓ | ✓ | ✓ (lock-up) |
| Kerbs | Fast rattle on rumble strips | ✓ | ✓ | ✓ |
| Wheelspin / slide | Smooth rumble | ✓ | ✓ | ✓ |
| Road surface | Grass, gravel, bumps (from the game's FFB signal) | ✓ | ✓ (off-track) | ✓ |
| Impacts | Strong jolt | ✓ | ✓ | ✓ |
| Gear shift | Short kick | ✓ | ✓ | ✓ |
| Rev limiter | Buzz | ✓ | ✓ | ✓ |

In the app, **SETUP → Vibration** has three presets:

- **Essential (default):** only feedback you can act on, and one effect at a time.
  - Priority order: impact, then lock-up/ABS, then wheelspin/slide, then kerb hit, then off-track.
  - Lock-up and ABS only play while you're braking. Wheelspin only plays when you're off the brake. Small amounts of slip are ignored.
  - Kerbs and leaving the track give a short burst, not constant buzzing. A slide that keeps going fades after about a second.
- **Immersive:** every effect layered, including road texture, gear shift ticks and the rev limiter.
- **Off:** no game vibration. Button clicks still work.

Each effect has its own strength slider. **Test all effects** plays each one in turn so you can learn what they feel like: ABS, 3 kerb hits, wheelspin building up, off-track, impact, then a shift tick.

The **dashboard** shows shift lights (they flash blue at the shift point, and alternate when the pit limiter is on), gear, speed, TC / TC2 / ABS / MAP / brake bias, fuel and laps left, and the lap delta in ACC. The TC and ABS boxes light up when those systems are working. A game that doesn't report a value shows "–".

## Driving coach

PhoneWheel.exe records every lap against track position and compares it with **your best clean lap** on the same track and car. Best laps are saved next to PhoneWheel.exe (`best_laps` folder), so the coach is ready from the first lap of your next session. Drive **one clean lap** to set the reference; it updates whenever you go faster. Laps in the pits, invalid laps and incomplete laps don't count.

| Feature | What it does |
|---|---|
| **Live delta** | The number under the speed on the dashboard: time vs your best lap at this exact point. Green means faster, red means slower. Works in all three games. |
| **Brake markers** | Two quick ticks in your thumb ("tick-tick, brake") just before each point where you braked on your best lap. Set the timing in SETUP → Driving coach (default 0.5 s before). |
| **Brake coach** | After each braking zone, a short note if something changed: "T3 brake: 9 m earlier · peak 74% (best 88%) · released too quickly · locked up". |
| **Exit coach** | After each corner exit, time gained or lost through the corner plus anything that went wrong: "T3 +0.21s: full throttle 0.4s later · 1 throttle lift · wheelspin". |
| **Sector splits** | "Sector 1 −0.12s" when you gain or lose at least 0.05 s. |
| **Lap summary** | "New best lap 1:47.312 (−0.204)" or "Lap 1:47.9 (+0.6)". |
| **Session report** | A page with lap times, sectors, consistency, speed, brake and throttle traces of your best vs last lap, a time-loss graph and a corner table that lists **where you lose the most time and why**. It updates after every lap. |

The coach stays quiet when you do something the same as your best lap. Messages appear on the dashboard for 4 seconds and are also printed in the PhoneWheel.exe window.

**Open the session report:** on the PC, go to http://localhost:8765/report. On the phone, tap **SETUP → Open session report**. Older sessions are listed at /reports and saved in the `reports` folder next to PhoneWheel.exe.

## More settings in the app (SETUP)

- **Bind controls in a game:** in the game's controls menu, click the control you want to assign. Then tap **Steering**, **Throttle** or **Brake** in SETUP. The app moves only that axis for 4 seconds, so the game can't pick up the wrong one.
- **Brake pressure feel:** pressing harder adds brake. It works by measuring how much of your thumb touches the screen. Not every phone reports this; if yours doesn't, the setting does nothing.
- **Brake detents:** you feel a light tick in your thumb as the brake passes 25 %, 50 % and 75 % (or another spacing you choose), like notches on a real pedal. You can tell how hard you're braking without looking.
- **Brake trace:** after each stop, the brake strip shows a white line of what you just did, plus a yellow mark and "peak xx%". Time runs left to right. It fades after a few seconds. Use it to check whether you braked hard enough at first and released smoothly down to zero.
- **Look buttons toggle:** LOOK LEFT, RIGHT and BACK latch. Tap once to look and tap again to return; tapping another look button switches straight to it. Games only look while the button is held, so a plain tap would flick the view straight back. You can turn this off in SETUP → Buttons.
- **Brake release smoothing:** the brake lets go more gently, which makes it easier to hold a steady trail brake with a thumb.
- **Gyro-fused sensor:** on by default. Steering is smoother and faster, and bumps or moving in your chair don't disturb it.
- **Speed-sensitive steering:** steering gets calmer at high speed. It needs a running game for the speed data.
- **Shift lights start:** sets how early the first LED lights.

## Button map (vJoy buttons)

| # | Button | # | Button | # | Button |
|---|---|---|---|---|---|
| 1 | **Upshift** (left paddle) | 12 | Engine map + | 23 | Look back |
| 2 | **Downshift** (right paddle) | 13 | Engine map − | 24 | Camera |
| 3 | Pit limiter | 14 | Brake bias + (fwd) | 25 | MFD up |
| 4 | Headlights | 15 | Brake bias − (rear) | 26 | MFD down |
| 5 | Flash | 16 | Ignition | 27 | MFD left |
| 6 | Wipers | 17 | Starter | 28 | MFD right |
| 7 | Rain light | 18 | Indicator left | 29 | MFD OK / enter |
| 8 | TC + | 19 | Indicator right | 30 | MFD page cycle |
| 9 | TC − | 20 | Hazards | 31 | ESC / menu |
| 10 | ABS + | 21 | Look left | 32 | HUD cycle |
| 11 | ABS − | 22 | Look right | | |

## Game setup

In every game: keep PhoneWheel.exe running and the app connected. To bind a pedal, turn **LOCK on** and slide your thumb all the way up the strip. To bind a button, tap it. Turn LOCK off when you bind steering.

- **ACC**: Options → Controls → new/custom wheel profile.
  - Steering = X; set steer lock to the car's real lock or auto.
  - Throttle = Y, brake = Z. Check in calibration that both reach 0–100 %.
  - Then bind gears, pit limiter, TC/ABS/MAP/BB, lights, wipers, ignition, starter and MFD.
- **RaceRoom**: Options → Controls → create a profile (Custom).
  - Steer = X, Accelerate = Y, Brake = Z, then gears and buttons.
  - Lower steering sensitivity if the car feels twitchy.
- **Le Mans Ultimate**: Settings → Controls.
  - Steering = X, Throttle = Y, Brake = Z, then shift up/down and buttons.

**Recommended starting point:** in-app steering range 90° (tilt the phone 45° each way for full lock), smoothing 20 %, pedal curves 1.0. For finer trail braking, raise the brake curve to about 1.4.

## Troubleshooting

- **App stuck on "Searching WiFi for PC…"**: allow PhoneWheel.exe through the firewall on Private networks, check that both devices are on the same network, or type the PC address in SETUP. Guest WiFi networks often block devices from reaching each other.
- **"USB: run usb_android.bat on PC"**: run the bat file again with the phone plugged in, and accept the USB debugging prompt.
- **PhoneWheel.exe says "falling back to DRY RUN"**: vJoy isn't installed or enabled, or device 1 is busy. The window prints the exact reason.
- **Steering goes the wrong way**: SETUP → Invert steering.
- **The game binds steering when you wanted a pedal**: turn on LOCK first.
- **Safety**: if the phone disconnects, the app is closed, or data stops for 0.3 s, the PC releases throttle, brake and all buttons.
- **No dashboard or vibration from the game**: check that the PC window shows the game name in `[ ]`. For LMU, install the plugin (see above). If the phone's settings have vibration turned off system-wide, the app can't vibrate either.
- **Real force feedback (pushing back) isn't possible.** A phone can't resist your hands; vibration is the substitute.

## Without the app (browser version)
PhoneWheel.exe also serves a browser version of the wheel:
- **WiFi:** open `https://<PC-IP>:8766` in Chrome on any phone, iPhone included. Accept the certificate warning the first time.
- **USB:** open `http://localhost:8765` after running usb_android.bat.

## Project layout
- `android/`: the Android app (Kotlin, no third-party libraries)
- `pc/`: PC server (`server.py`, `vjoy.py`, `telemetry.py`, `coach.py`, `report.py`) and the browser version (`web/`). You can run it with Python instead of the .exe: run `setup.bat`, then `start.bat`.
- `.github/workflows/build.yml`: builds `PhoneWheel.apk` and `PhoneWheel.exe` on GitHub
