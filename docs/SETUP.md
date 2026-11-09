# Setup guide

This guide takes you from nothing to driving. You need:

- an **Android phone**, Android 8.0 or newer, with a gyroscope (almost every phone has one);
- a **Windows 10 or 11 PC** with ACC, RaceRoom, Le Mans Ultimate or any other sim that supports wheels;
- optionally, a **USB cable** for the lowest lag.

---

## Easiest: the installer

Run **[PhoneWheel-Setup.exe](https://github.com/ihavenoenemies171/PhoneWheel/releases/latest/download/PhoneWheel-Setup.exe)**. It does steps 1 and 2 below for you:
- installs vJoy 2.2.2.0;
- sets up device 1 with axes X, Y, Z, Rx and Ry, 32 buttons and no POV hats, and switches it on;
- installs PhoneWheel;
- opens the Windows Firewall for it on all network types;
- adds Start-menu and desktop shortcuts, plus an optional start-with-Windows shortcut.

Restart the PC if it asks, then go to [step 3](#3-install-the-phone-app). The installed version saves laps, reports and logs in **Documents\PhoneWheel**.

## 1. Install vJoy (the virtual wheel driver)

PhoneWheel creates a virtual game controller on your PC through **vJoy**.

1. Download **vJoy 2.2.2.0** from https://github.com/BrunnerInnovation/vJoy/releases. Under *Assets*, pick the file ending in **.exe**.
   > Use this version. It's signed for Windows 11. Older vJoy versions (2.1.x) often fail to install on Windows 11.
2. Run the installer and restart the PC if it asks.
3. Open **Configure vJoy** from the Start menu.
4. Select device **1** at the top and set:
   - **Axes:** tick **X**, **Y** and **Z**. Also tick **Rx** and **Ry** if you want the analog clutch and handbrake levers.
   - **Number of buttons:** **32**
   - **POV hat switches:** **0**
   - Bottom left: **Enable vJoy** must be ticked.
5. Click **Apply** and close the window.

You **don't** need to keep any vJoy program open. Don't run *vJoy Feeder*, because it takes over device 1.

To check that it worked, press **Win+R**, type `joy.cpl` and press Enter. **vJoy Device** should be listed.

## 2. Install the PC program

1. Download **[PhoneWheel-PC.zip](https://github.com/ihavenoenemies171/PhoneWheel/releases/latest/download/PhoneWheel-PC.zip)**.
2. Unzip it into any folder, for example `Documents\PhoneWheel`. Inside you'll find:
   - `PhoneWheel.exe`: the program
   - `network_setup.bat`: one-time helper for USB tethering and hotspot connections
   - `usb_android.bat`: helper for the advanced adb USB connection
   - `README.md`
3. Double-click **PhoneWheel.exe**.
   - **SmartScreen** ("Windows protected your PC"): click **More info → Run anyway**. It shows up because the program isn't code-signed, and that's normal for small open-source tools.
   - **Windows Firewall:** click **Allow**, and tick *Private networks*.
4. The window should say **`vJoy device 1 acquired (32 buttons)`**. If it says *DRY RUN* instead, see [Troubleshooting](TROUBLESHOOTING.md#phonewheelexe-says-dry-run).

Leave PhoneWheel.exe running while you drive. It shows a live line with steering, throttle, brake, pressed buttons, the update rate and the game it detected.

> PhoneWheel.exe saves your best laps (`best_laps` folder) and session reports (`reports` folder) next to itself, so keep it in a folder you can write to. Your Documents folder is fine; Program Files isn't.

## 3. Install the phone app

1. On the phone, open **[PhoneWheel.apk](https://github.com/ihavenoenemies171/PhoneWheel/releases/latest/download/PhoneWheel.apk)** in Chrome. You can also download it on the PC and copy it over with a cable.
2. Tap the downloaded file. Android asks you to allow **Install unknown apps** for Chrome (or your file manager). Allow it.
3. If **Play Protect** warns about an unknown developer, tap **More details → Install anyway**. The APK is built automatically from this repository's source code by GitHub Actions.
4. Open **PhoneWheel**. It runs in landscape and full screen.

To update later, install the new APK over the old one. Your settings are kept.

> **One-time note:** when releases switch to being signed with the project's own key instead of a generic test key, Android may say *"App not installed"* or *"conflicts with an existing package"* when you update. If it does, uninstall the old PhoneWheel first, then install the new one. After that, updates install normally again.

## 4. Connect the phone to the PC

Pick **one** of these methods. The app's connection setting is in **SETUP → Connection**.

| Method | Lag | Effort | Notes |
|---|---|---|---|
| **WiFi** | Low to medium | Easiest | Same network as the PC. 5 GHz WiFi is best. |
| **USB tethering** | Lowest | Easy | Recommended. No developer mode needed. |
| **Phone hotspot** | Low | Easy | Only if your PC has WiFi. |
| **USB via adb** | Lowest | Advanced | Needs developer mode and Google's platform-tools. |

The number next to the green dot in the app is the **ping**, the round-trip time to the PC. A "±" after it means the connection is jumping around.

### A. WiFi
1. Connect the phone and the PC to the **same WiFi network**.
2. Start PhoneWheel.exe, then open the app with the connection set to **Automatic**.
3. The status turns green and shows your PC's name and the ping.

If it stays on *"Searching WiFi for PC…"*:
- Choose **SETUP → Connection → WiFi – type PC address** and enter the address PhoneWheel.exe prints, such as `192.168.1.7`.
- Make sure Windows Firewall allows PhoneWheel. Running `network_setup.bat` once fixes this for every network type.
- Guest WiFi networks often block devices from talking to each other. Use your main network.

**For lower WiFi lag:**
- Use 5 GHz WiFi and keep the phone near the router.
- Connect the PC to the router by cable if you can.
- The app turns on Android's low-latency WiFi mode automatically.

### B. USB tethering (recommended)
1. Plug the phone into the PC with a USB cable that carries data, not just charge.
2. On the phone, open **Settings → Network & internet / Connections → Hotspot & tethering**. Turn on **USB tethering**.
3. **First time only:** in the PhoneWheel folder, double-click **`network_setup.bat`** and click **Yes** when Windows asks for permission. It does two things:
   - Lets PhoneWheel through the firewall on all network types. Windows calls the tethering connection "Public", which blocks it by default.
   - Gives the tethering connection low priority, so your PC keeps using its own internet instead of your phone's mobile data.
4. Start PhoneWheel.exe, then open the app with the connection set to **Automatic**.

Tip: turn off mobile data on the phone while tethering if you want to be sure it never uses data.

### C. Phone hotspot
1. Turn on the phone's **hotspot**.
2. Connect the PC's WiFi to it.
3. Run `network_setup.bat` once, start PhoneWheel.exe, and open the app with **Automatic**.

### D. USB via adb (advanced)
1. On the phone, enable **Developer options**: go to *Settings → About phone* and tap **Build number** 7 times. Then turn on **USB debugging**.
2. Download **SDK Platform-Tools for Windows** from https://developer.android.com/tools/releases/platform-tools. Unzip it so the folder `platform-tools\adb.exe` sits next to PhoneWheel.exe.
3. Plug in the phone and run **`usb_android.bat`**. Accept the *Allow USB debugging?* prompt on the phone.
4. In the app, choose **SETUP → Connection → USB cable**.
5. Run `usb_android.bat` again every time you re-plug the phone.

## 5. First drive

1. Start PhoneWheel.exe, then the app, then the game.
2. Hold the phone level, the way you'd hold the wheel at straight ahead, and tap **CENTER**.
3. Bind the controls in the game. See **[Game setup](GAMES.md)** for each game.
   - **Steering, throttle, brake:** in the game's controls menu, click the control you want to assign. Then on the phone tap **SETUP → Bind controls → Steering / Throttle / Brake**. The app slowly moves only that axis for 4 seconds, so the game can't grab the wrong one.
   - **Buttons:** click the action in the game, then tap the wheel button on the phone.
   - Turn on **LOCK** (top bar) while binding buttons, so small wobbles of the phone don't get picked up as steering.
4. Open **SETUP → Vibration → Test all effects** once to learn what each effect feels like.
5. Drive one clean lap. That lap becomes your reference for the driving coach.

## 6. Recommended starting settings

| Setting (in SETUP) | Start with | Why |
|---|---|---|
| Steering range | 90° | Tilting the phone 45° each way gives full lock |
| Smoothing | 20 % | Steady without noticeable lag |
| Gyro-fused sensor | On | Smoother and not thrown off by bumps |
| Brake curve | 1.5 | More control in the 0–40 % range, where trail braking happens |
| Brake release smoothing | 80–120 ms | Steadier trail braking with a thumb |
| Brake detents | 3 | Ticks at 25 / 50 / 75 % brake |
| Vibration preset | Essential | One clear effect at a time |
| Brake markers | On, 0.5 s | A cue just before your usual braking points |

In the game, keep its own steering and pedal curves **linear** (gamma 1.0) so only one curve shapes your input.
