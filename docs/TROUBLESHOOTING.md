# Troubleshooting

Start by looking at the **PhoneWheel.exe window**. Its top lines say whether vJoy works, and the bottom line shows live inputs, the update rate, the connected phone and the detected game.

## PhoneWheel.exe says "DRY RUN"

In DRY RUN mode the phone connects but **games won't see any input**. The line above it gives the reason:

| Message | Fix |
|---|---|
| `vJoy is not installed` | Install vJoy 2.2.2.0 (see [Setup](SETUP.md#1-install-vjoy-the-virtual-wheel-driver)), restart the PC, start PhoneWheel again. |
| `vJoy is installed but switched off` | PhoneWheel tries to switch it on itself; click **Yes** when Windows asks. Otherwise open *Configure vJoy*, tick **Enable vJoy** and click **Apply**. vJoy sometimes switches itself off after a restart. Turning off Windows *Fast Startup* (Control Panel → Power Options → Choose what the power buttons do) can stop that. |
| `vJoy device 1 does not exist` | In *Configure vJoy*, select device 1, set axes X/Y/Z and 32 buttons, and click **Apply**. |
| `vJoy device 1 is used by another program` | Close vJoy Feeder, SimHub, Joystick Gremlin or anything else using vJoy device 1. |
| `found vJoyInterface.dll but Windows could not load it` | Reinstall vJoy 2.2.2.0 (64-bit). |

## "PhoneWheel is ALREADY RUNNING in another window"

Only one copy can run at a time. Use the window that's already open (it may be minimised). If you can't find it, end **PhoneWheel.exe** in Task Manager (Ctrl+Shift+Esc), then start it again.

## The app can't find the PC ("Searching WiFi for PC…")

- Check that the phone and PC are on the **same network**. Guest WiFi networks usually block devices from talking to each other.
- Run **`network_setup.bat`** once (click Yes). It allows PhoneWheel through the Windows Firewall on every network type.
- Choose **SETUP → Connection → WiFi – type PC address** and enter the address PhoneWheel.exe prints.
- Some routers have *AP / client isolation* turned on. Turn it off, or use USB tethering instead.

## High or jumpy ping

- Use **USB tethering** ([Setup](SETUP.md#b-usb-tethering-recommended)). It's the easiest low-lag option.
- On WiFi: use 5 GHz, move the phone closer to the router, and connect the PC by cable.
- Turn off Battery Saver on the phone. Some phones also have per-app battery optimisation; set PhoneWheel to *Unrestricted*.

## Game doesn't see vJoy / inputs

- Start PhoneWheel.exe **before** the game, or restart the game. Most games only look for controllers when they start.
- Check `joy.cpl` (Win+R → `joy.cpl`): **vJoy Device → Properties** should move when you use the phone.
- Make sure the PhoneWheel window says **vJoy device 1 acquired**, not DRY RUN.

## Steering goes the wrong way / is off-centre / drifts

- **Wrong way:** turn on **SETUP → Invert steering**.
- **Off-centre:** hold the phone level and tap **CENTER**.
- **Slow drift:** turn off **Gyro-fused sensor**.
- **Too sensitive:** increase **Steering range** or **Linearity**, or lower the game's steering sensitivity.

## A button or paddle seems stuck

Update to the latest app version, which fixed sticky paddles. Lifting all your fingers off the screen always releases every button. Latched look buttons are the exception: tap them again.

## Look left / right / back snaps back instantly

- In the app, look buttons are toggles by default (**SETUP → Buttons**). Check `joy.cpl`: the button should stay lit.
- If the button stays lit but the game still snaps back, something else is resetting the camera:
  - Close programs that create **virtual gamepads**, such as phone-as-controller apps and remote-play receivers. Their centred sticks can reset the camera in ACC.
  - Check that look left/right isn't **bound to anything else** in the game.

## No vibration, dashboard or coach from the game

- The PhoneWheel window must show the game name in brackets, such as `[ACC]`, while you're on track.
- **LMU:** install the rF2 Shared Memory plugin ([Game setup](GAMES.md#le-mans-ultimate-lmu)).
- Check **SETUP → Vibration on** and the preset (not *Off*). The phone's own vibration setting must also be on.
- The coach needs **one clean, complete lap** before it can compare.

## Vibration feels like too much

Use the **Essential** preset, lower **Overall strength**, or switch ABS and wheelspin to **Flash** in *Vibrate or flash*.

## Can't install the APK

- Allow *Install unknown apps* for the app you're opening it from (Chrome or Files).
- If Play Protect blocks it, tap **More details → Install anyway**.
- If you see *"App not installed"* when updating, uninstall the old version first. This happens when you built your own APK with a different signing key.

## Still stuck?

Open an issue on GitHub: https://github.com/ihavenoenemies171/PhoneWheel/issues. Include:
- your phone model and Android version;
- the game;
- a screenshot of the PhoneWheel.exe window.
