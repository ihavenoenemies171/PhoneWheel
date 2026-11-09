# Building from source & how it works

## Repository layout

```
.github/workflows/build.yml   CI: builds PhoneWheel.apk + PhoneWheel.exe and publishes a release on every push to main
android/                      Android app (Kotlin, plain Android framework, no third-party libraries)
  app/src/main/java/com/phonewheel/app/
    MainActivity.kt           lifecycle, tilt sensor → steering, WiFi low-latency lock, settings wiring
    WheelView.kt              the whole UI drawn on one Canvas: pedals, paddles, buttons, dashboard, flashes, multi-touch
    Link.kt                   networking (UDP over WiFi / TCP over USB), input state, telemetry/coach parsing
    Haptics.kt                vibration mixer (Essential / Immersive presets, markers, detents)
    SettingsDialog.kt         settings screen (built in code)
    Prefs.kt                  all settings (SharedPreferences)
pc/                           Windows PC program (Python)
  server.py                   entry point: network servers, vJoy output, watchdog, telemetry loop, report pages
  vjoy.py                     ctypes wrapper for vJoyInterface.dll
  telemetry.py                ACC / RaceRoom / LMU shared-memory readers → one common Frame
  coach.py                    lap recording, best-lap reference, live delta, brake markers, corner notes
  report.py                   HTML session report with inline SVG charts
  web/                        browser version of the wheel (for iPhones / no app)
  network_setup.bat           firewall + tethering route helper
  usb_android.bat             adb reverse helper
docs/                         user guides
```

## Building with GitHub Actions (easiest)

Fork the repository. Every push to `main` (or *Actions → Build PhoneWheel → Run workflow*) builds:
- `PhoneWheel.apk`: a release build, signed with the debug key so it installs directly;
- `PhoneWheel-PC.zip`: `PhoneWheel.exe` (PyInstaller one-file) plus the helper scripts and docs.

Both are published as a GitHub Release named `v1.0.<run number>`.

## Building the Android app locally

Requirements: JDK 17 and the Android SDK (platform 34). Android Studio installs both.

```bash
cd android
gradle assembleRelease        # or open the android/ folder in Android Studio and Build → Build APK
# output: android/app/build/outputs/apk/release/app-release.apk
```

The project uses AGP 8.5 and Kotlin 2.0, and needs Gradle 8.7 or newer. There are no external dependencies.

## Running / building the PC program locally

Requirements: Windows, Python 3.10+, vJoy installed.

```bat
cd pc
pip install -r requirements.txt
python server.py              :: add --dry-run to test without vJoy
```

To build the EXE:

```bat
pip install pyinstaller
pyinstaller --onefile --console --name PhoneWheel --icon icon.ico --add-data "web;web" server.py
```

Command-line options: `--device N` (vJoy device, default 1), `--http-port`, `--https-port`, `--app-port`, `--dry-run`.

`server.py --dry-run` also runs on Linux and macOS for UI and protocol testing; telemetry needs Windows.

## Network protocol

All messages are short ASCII text. The app uses **UDP port 8767** over WiFi (one message per datagram), or **TCP port 8767** over adb USB (one message per line, forwarded by `adb reverse`).

**App → PC**

| Message | Meaning |
|---|---|
| `PW?` | Discovery (also broadcast on every network interface). The PC answers `PW!,<pc name>,<vJoy buttons>` |
| `d,<seq>,<steer>,<thr>,<brk>,<mask>` | Input, sent on every change plus a keepalive every 40 ms. steer −1..1, throttle/brake 0..1, mask = 32-bit button mask. Over UDP, out-of-order packets are dropped by `seq`. |
| `p,<t>` | Ping. The PC echoes `q,<t>` |
| `cfg,<markers 0/1>,<lead ms>,<messages 0/1>` | Coach settings, resent every 2 s |
| `bye` | App closing: release everything |

**PC → App**

| Message | Meaning |
|---|---|
| `t,<game>,<live>,<gear>,<rpm>,<shift rpm>,<max rpm>,<kmh>,<tc>,<tc2>,<abs>,<map>,<bb>,<fuel>,<fuel laps>,<pit lim>,<abs active>,<tc active>,<delta ms>` | Dashboard data, ~30 Hz. `x` = not available. `t,,0` = no game running |
| `v,<abs>,<kerb>,<slip>,<road>,<impact seq>,<impact mag>,<shift seq>,<limiter>,<offtrack>` | Vibration channels 0..1, ~100 Hz |
| `m` | Brake marker: play the tick / flash now |
| `c,<good/bad/info>,<text>` | Coach note |

Safety: if no input arrives for 0.3 s, the PC releases all axes and buttons.

**Other ports:** 8765 HTTP (browser version over USB, session report) and 8766 HTTPS (browser version over WiFi; phones only allow motion sensors on secure pages).

## Telemetry

`telemetry.py` reads each game's shared memory through `OpenFileMappingW`. It never creates a mapping, so a missing game simply reads as "not running". Byte offsets were calculated from the official headers with a Windows x64 compiler:

| Game | Shared memory | Source of the layout |
|---|---|---|
| ACC | `Local\acpmf_physics`, `Local\acpmf_graphics`, `Local\acpmf_static` | Kunos `SharedFileOut.h` (cross-checked with PyAccSharedMemory) |
| RaceRoom | `$R3E` (API v3) | [sector3studios/r3e-api](https://github.com/sector3studios/r3e-api) `r3e.h` |
| LMU / rFactor 2 | `$rFactor2SMMP_Telemetry$`, `_Scoring$`, `_ForceFeedback$` | [rF2SharedMemoryMapPlugin](https://github.com/TheIronWolfModding/rF2SharedMemoryMapPlugin) `rF2State.h` |

Every reader produces the same `Frame`, which holds dashboard values, vibration channels and lap data. Everything downstream (vibration, coach, report) is game-independent.

### Adding a game

1. Add a reader class to `telemetry.py` with `name`, `__init__(opener)` (raise `OSError` if the game isn't running), `read()` returning a `Frame`, and `close()`.
2. Fill in what the game offers. For the coach, set `lap`, `lap_time`, `lap_dist`, `track_len`, `throttle`, `brake`, `valid`, `in_pit`, `track` and `car`.
3. Add it to `READERS`.
4. Test with synthetic buffers. See the approach in the existing tests, which pack values at the reader's offsets into `bytes` and wrap them in `View`.

## Coach internals (coach.py)

- Each lap is resampled onto a 2 m distance grid: time, speed, throttle, brake, ABS and slip.
- A lap counts as **valid** when it covers the whole track without gaps, the game marks it valid, it didn't enter the pits, and it's longer than 10 s.
- **Braking zones:** brake rises above 15 % and lasts at least 0.25 s with a peak of at least 25 %. Each zone gets an apex (minimum speed) and an exit (until full throttle, max 6 s).
- **Live delta** = current lap time − best lap's time at the same distance.
- **Brake marker** fires at `zone start − speed × lead time` (at least 15 m before).
- The best lap per game + track + car is stored as JSON in `best_laps/`.
