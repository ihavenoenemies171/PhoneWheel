"""
Minimal ctypes wrapper around vJoyInterface.dll (vJoy 2.x).
Exposes one virtual DirectInput wheel/joystick that games see as real hardware.
"""
import ctypes
import os
import sys

# HID usage IDs for axes
AXIS_X, AXIS_Y, AXIS_Z = 0x30, 0x31, 0x32
AXIS_RX, AXIS_RY, AXIS_RZ = 0x33, 0x34, 0x35
AXIS_SL0, AXIS_SL1 = 0x36, 0x37

AXIS_MIN, AXIS_MAX = 1, 0x8000

VJD_STAT_OWN, VJD_STAT_FREE, VJD_STAT_BUSY, VJD_STAT_MISS = 0, 1, 2, 3

DLL_CANDIDATES = [
    os.environ.get("VJOY_DLL", ""),
    os.path.join(os.path.dirname(sys.executable), "vJoyInterface.dll") if getattr(sys, "frozen", False) else "",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "vJoyInterface.dll"),
    r"C:\Program Files\vJoy\x64\vJoyInterface.dll",
    r"C:\Program Files\vJoy\vJoyInterface.dll",
    r"C:\Program Files (x86)\vJoy\x64\vJoyInterface.dll",
]


def _registry_dlls():
    """vJoy's installer records where it put the DLL in the Windows uninstall registry."""
    found = []
    try:
        import winreg
    except ImportError:
        return found
    roots = [
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
    ]
    for hive, path in roots:
        try:
            base = winreg.OpenKey(hive, path)
        except OSError:
            continue
        i = 0
        while True:
            try:
                sub = winreg.EnumKey(base, i)
            except OSError:
                break
            i += 1
            try:
                k = winreg.OpenKey(base, sub)
            except OSError:
                continue

            def val(name):
                try:
                    return str(winreg.QueryValueEx(k, name)[0])
                except OSError:
                    return ""

            if "vjoy" not in (val("DisplayName") + sub).lower():
                continue
            for name in ("DllX64Location", "DllX86Location"):
                d = val(name)
                if d:
                    found.append(d if d.lower().endswith(".dll") else os.path.join(d, "vJoyInterface.dll"))
            loc = val("InstallLocation")
            if loc:
                found += [os.path.join(loc, "x64", "vJoyInterface.dll"), os.path.join(loc, "vJoyInterface.dll")]
    return found


def _search_dlls():
    """Last resort: look through the usual program folders for vJoyInterface.dll."""
    found = []
    tops = {os.environ.get(v, "") for v in ("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")}
    for top in sorted(t for t in tops if t and os.path.isdir(t)):
        for name in os.listdir(top):
            if "joy" not in name.lower():
                continue
            for root, _dirs, files in os.walk(os.path.join(top, name)):
                if "vJoyInterface.dll" in files:
                    found.append(os.path.join(root, "vJoyInterface.dll"))
    # prefer the 64-bit DLL
    found.sort(key=lambda p: (("x64" not in p.lower() and "amd64" not in p.lower()), p))
    return found


def _try_enable(dll_dir, d):
    """Runs 'vJoyConfig enable on' as administrator (Windows asks for permission), then waits for the driver."""
    import time
    cfg = None
    for folder in (dll_dir, os.path.dirname(dll_dir), os.path.join(os.path.dirname(dll_dir), "x64")):
        p = os.path.join(folder, "vJoyConfig.exe")
        if os.path.isfile(p):
            cfg = p
            break
    if cfg is None:
        return False
    print("  vJoy is switched off - turning it on (click Yes if Windows asks for permission)...")
    try:
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", cfg, "enable on", os.path.dirname(cfg), 0)
    except Exception:
        return False
    if rc <= 32:  # user said No, or it couldn't start
        return False
    for _ in range(40):
        time.sleep(0.25)
        if d.vJoyEnabled():
            time.sleep(0.5)  # let the device come up fully
            print("  vJoy switched on.")
            return True
    return False


class VJoyError(Exception):
    pass


class VJoyDevice:
    def __init__(self, device_id=1):
        if sys.platform != "win32":
            raise VJoyError("vJoy only runs on Windows.")
        self.id = device_id
        self.dll = None
        tried = []
        load_errors = []
        for path in DLL_CANDIDATES + _registry_dlls() + _search_dlls():
            if not path or path in tried:
                continue
            tried.append(path)
            if not os.path.isfile(path):
                continue
            try:
                self.dll = ctypes.WinDLL(path)
                self.dll_path = path
                break
            except OSError as e:  # e.g. 32-bit DLL in a 64-bit program
                load_errors.append(f"{path}: {e}")
        if self.dll is None:
            if load_errors:
                raise VJoyError("found vJoyInterface.dll but Windows could not load it:\n    "
                                + "\n    ".join(load_errors)
                                + "\n  Reinstall vJoy 2.2.2.0 (64-bit).")
            raise VJoyError(
                "vJoy is not installed (vJoyInterface.dll not found).\n"
                "  Install vJoy 2.2.2.0 from github.com/BrunnerInnovation/vJoy/releases ,\n"
                "  restart the PC, then run PhoneWheel.exe again."
            )
        d = self.dll
        d.vJoyEnabled.restype = ctypes.c_bool
        d.GetVJDStatus.argtypes = [ctypes.c_uint]
        d.GetVJDStatus.restype = ctypes.c_int
        d.AcquireVJD.argtypes = [ctypes.c_uint]
        d.AcquireVJD.restype = ctypes.c_bool
        d.RelinquishVJD.argtypes = [ctypes.c_uint]
        d.ResetVJD.argtypes = [ctypes.c_uint]
        d.ResetVJD.restype = ctypes.c_bool
        d.SetAxis.argtypes = [ctypes.c_long, ctypes.c_uint, ctypes.c_uint]
        d.SetAxis.restype = ctypes.c_bool
        d.SetBtn.argtypes = [ctypes.c_bool, ctypes.c_uint, ctypes.c_ubyte]
        d.SetBtn.restype = ctypes.c_bool
        d.GetVJDButtonNumber.argtypes = [ctypes.c_uint]
        d.GetVJDButtonNumber.restype = ctypes.c_int
        d.GetVJDAxisExist.argtypes = [ctypes.c_uint, ctypes.c_uint]
        d.GetVJDAxisExist.restype = ctypes.c_bool

        if not d.vJoyEnabled():
            # vJoy sometimes switches itself off after a Windows restart: try to turn it back on
            if not _try_enable(os.path.dirname(self.dll_path), d):
                raise VJoyError("vJoy is installed but switched off, and it could not be switched on automatically.\n"
                                "  Open 'Configure vJoy', tick 'Enable vJoy' (bottom left), click Apply, then restart PhoneWheel.")
        status = d.GetVJDStatus(device_id)
        if status == VJD_STAT_MISS:
            raise VJoyError(f"vJoy device {device_id} does not exist. Create it in 'Configure vJoy'.")
        if status == VJD_STAT_BUSY:
            raise VJoyError(f"vJoy device {device_id} is used by another program (close SimHub/feeder apps using it).")
        if not d.AcquireVJD(device_id):
            raise VJoyError(f"Could not acquire vJoy device {device_id}.")
        d.ResetVJD(device_id)

        self.button_count = d.GetVJDButtonNumber(device_id)
        self.missing_axes = [name for name, a in (("X", AXIS_X), ("Y", AXIS_Y), ("Z", AXIS_Z))
                             if not d.GetVJDAxisExist(device_id, a)]
        self.missing_extra = [name for name, a in (("Rx", AXIS_RX), ("Ry", AXIS_RY))
                              if not d.GetVJDAxisExist(device_id, a)]
        self._axes = {}
        self._buttons = 0
        self.failed = False        # a write to vJoy failed: check ownership as soon as possible
        self.state = "ok"          # ok / lost / busy / off  (shown in the status line)
        self._said = None

    def set_axis(self, axis, norm):
        """norm: 0..1"""
        v = int(AXIS_MIN + max(0.0, min(1.0, norm)) * (AXIS_MAX - AXIS_MIN))
        if self._axes.get(axis) != v:
            if self.dll.SetAxis(v, self.id, axis):
                self._axes[axis] = v
            else:
                self.failed = True

    def set_buttons(self, mask):
        changed = mask ^ self._buttons
        if not changed:
            return
        n = min(32, self.button_count)
        ok = True
        for i in range(n):
            bit = 1 << i
            if changed & bit:
                ok = self.dll.SetBtn(bool(mask & bit), self.id, i + 1) and ok
        if ok:
            self._buttons = mask
        else:
            self.failed = True

    def _say(self, key, msg):
        if self._said != key:
            self._said = key
            print("\n" + msg)

    def check(self):
        """Make sure we still own the vJoy device; take it back if we lost it.
        Returns True when it was (re)acquired, so the caller can resend the current input.
        Ownership can be lost when another program grabs the device (Monect, PC Remote, vJoy Feeder,
        SimHub ...) or when Windows restarts the driver after sleep. The game then sees a frozen
        controller while PhoneWheel's window still shows your input."""
        self.failed = False
        d = self.dll
        try:
            if not d.vJoyEnabled():
                self.state = "off"
                self._say("off", "[!] vJoy was switched off (Windows sleep or another program). Turn it back on in "
                                 "'Configure vJoy' (tick Enable vJoy, Apply). Restart the game if it still doesn't react.")
                return False
            status = d.GetVJDStatus(self.id)
        except Exception:
            return False
        if status == VJD_STAT_OWN:
            if self.state != "ok":
                self.state = "ok"
                self._say("ok", "[+] vJoy device is ours again - the game gets your input")
            return False
        if status == VJD_STAT_BUSY:
            self.state = "busy"
            self._say("busy", f"[!] Another program has taken vJoy device {self.id}, so the game no longer gets "
                              "your input.\n    Close Monect / PC Remote / vJoy Feeder / SimHub (or anything else "
                              "using vJoy) - PhoneWheel takes it back automatically.")
            return False
        if status == VJD_STAT_FREE and d.AcquireVJD(self.id):
            self.state = "ok"
            self._said = "ok"
            print(f"\n[+] Lost the vJoy device (Windows let go of it) and took it back - input restored")
            self._axes = {}          # resend everything
            self._buttons = 0
            return True
        self.state = "lost"
        self._say("lost", f"[!] Lost vJoy device {self.id} (status {status}); retrying every second")
        return False

    def close(self):
        try:
            self.dll.ResetVJD(self.id)
            self.dll.RelinquishVJD(self.id)
        except Exception:
            pass


class DummyDevice:
    """Used when vJoy is unavailable, so the phone UI can still be tested."""
    button_count = 32
    missing_axes = []
    missing_extra = []
    dll_path = "(dry run - no vJoy)"

    failed = False
    state = "ok"

    def check(self):
        return False

    def set_axis(self, axis, norm):
        pass

    def set_buttons(self, mask):
        pass

    def close(self):
        pass
