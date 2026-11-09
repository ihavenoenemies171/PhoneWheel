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


class VJoyError(Exception):
    pass


class VJoyDevice:
    def __init__(self, device_id=1):
        if sys.platform != "win32":
            raise VJoyError("vJoy only runs on Windows.")
        self.id = device_id
        self.dll = None
        for path in DLL_CANDIDATES:
            if path and os.path.isfile(path):
                try:
                    self.dll = ctypes.WinDLL(path)
                    self.dll_path = path
                    break
                except OSError:
                    continue
        if self.dll is None:
            raise VJoyError(
                "vJoyInterface.dll not found. Install vJoy 2.2.2.0 "
                "(github.com/BrunnerInnovation/vJoy/releases) or copy "
                "vJoyInterface.dll next to PhoneWheel.exe / server.py."
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
            raise VJoyError("vJoy driver is installed but not enabled. Open 'Configure vJoy' and tick 'Enable vJoy'.")
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
        self._axes = {}
        self._buttons = 0

    def set_axis(self, axis, norm):
        """norm: 0..1"""
        v = int(AXIS_MIN + max(0.0, min(1.0, norm)) * (AXIS_MAX - AXIS_MIN))
        if self._axes.get(axis) != v:
            self._axes[axis] = v
            self.dll.SetAxis(v, self.id, axis)

    def set_buttons(self, mask):
        changed = mask ^ self._buttons
        if not changed:
            return
        n = min(32, self.button_count)
        for i in range(n):
            bit = 1 << i
            if changed & bit:
                self.dll.SetBtn(bool(mask & bit), self.id, i + 1)
        self._buttons = mask

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
    dll_path = "(dry run - no vJoy)"

    def set_axis(self, axis, norm):
        pass

    def set_buttons(self, mask):
        pass

    def close(self):
        pass
