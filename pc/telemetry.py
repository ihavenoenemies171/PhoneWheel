"""
Game telemetry for PhoneWheel: reads live data from the sim's shared memory and turns it into
  * dashboard data  (gear, rpm, shift point, speed, TC/ABS/MAP/BB, fuel, delta)
  * vibration channels (ABS / lock-up, kerbs, wheelspin, road surface, impacts, gear shifts, rev limiter)

Supported:
  ACC       - native shared memory (Local\\acpmf_physics / _graphics / _static)
  RaceRoom  - native shared memory ($R3E), API v3
  LMU       - rF2 Shared Memory Map plugin ($rFactor2SMMP_Telemetry$ / _Scoring$ / _ForceFeedback$)

Byte offsets were computed from the official headers (ACC SharedFileOut.h, sector3studios r3e.h,
TheIronWolfModding rF2State.h) with a Windows x64 compiler.
"""
import ctypes
import math
import struct
import sys
import time

# ----------------------------------------------------------------------------- offsets
ACC_PHY = dict(packetId=0, gas=4, brake=8, fuel=12, gear=16, rpms=20, speedKmh=28, accG=44, wheelSlip=56,
               tc=204, pitLimiterOn=248, abs=252, finalFF=308, brakeBias=564, currentMaxRpm=588,
               tcinAction=672, absInAction=676, kerbVibration=784, slipVibrations=788, gVibrations=792,
               absVibrations=796, SIZE=800)
ACC_GFX = dict(packetId=0, status=4, TC=1268, TCCut=1272, EngineMap=1276, ABS=1280, fuelXLap=1284,
               iDeltaLapTime=1360, isDeltaPositive=1400, fuelEstimatedLaps=1412, SIZE=1416)
ACC_STA = dict(maxRpm=412, maxFuel=416, SIZE=420)

R3E = dict(version_major=0, version_minor=4, game_paused=20, game_in_menus=24, game_in_replay=28,
           car_speed=1392, engine_rps=1396, max_engine_rps=1400, upshift_rps=1404, gear=1408,
           local_acceleration=1440, fuel_left=1456, fuel_capacity=1460, fuel_per_lap=1464, throttle=1500,
           brake=1508, aid_abs=1536, aid_tc=1540, pit_limiter=1572, brake_bias=1596, abs_setting=1616,
           tire_speed=1648, traction_control_setting=1936, engine_map_setting=1940, tire_on_mtrl=1952,
           SIZE=44024)
R3E_RUMBLE, R3E_GRASS, R3E_DIRT, R3E_GRAVEL = 5, 2, 3, 4

RF2_TEL = dict(mNumVehicles=4, mVehicles=8, VEH_SIZE=1888, SIZE=241672)
RF2_VEH = dict(mID=0, mElapsedTime=12, mLocalVel=184, mLocalAccel=208, mGear=352, mEngineRPM=356,
               mUnfilteredBrake=396, mFuel=524, mEngineMaxRPM=532, mLastImpactET=552,
               mLastImpactMagnitude=560, mSpeedLimiter=604, mFuelCapacity=608, mRearBrakeBias=664,
               mWheels=848, WHEEL_SIZE=260)
RF2_WHEEL = dict(mBrakePressure=32, mRotation=40, mLongitudinalPatchVel=56, mLongitudinalGroundVel=72,
                 mSurfaceType=176)
RF2_SCO = dict(mScoringInfo=4, info_mNumVehicles=4 + 100, info_mGamePhase=4 + 104, mVehicles=544,
               VEH_SIZE=584, veh_mID=0, veh_mIsPlayer=196, veh_mControl=197, SIZE=75296)
RF2_SURF_RUMBLE, RF2_SURF_GRASS, RF2_SURF_DIRT, RF2_SURF_GRAVEL = 5, 2, 3, 4


# ----------------------------------------------------------------------------- shared memory access
class View:
    """Read-only window onto a named shared memory block (or a bytes buffer for testing)."""

    def __init__(self, data):
        self._data = data

    def raw(self, off, n):
        return self._data[off:off + n]

    def f32(self, off):
        return struct.unpack_from("<f", self.raw(off, 4))[0]

    def f64(self, off):
        return struct.unpack_from("<d", self.raw(off, 8))[0]

    def i32(self, off):
        return struct.unpack_from("<i", self.raw(off, 4))[0]

    def u8(self, off):
        return self.raw(off, 1)[0]

    def f32s(self, off, n):
        return struct.unpack_from(f"<{n}f", self.raw(off, 4 * n))

    def i32s(self, off, n):
        return struct.unpack_from(f"<{n}i", self.raw(off, 4 * n))

    def f64s(self, off, n):
        return struct.unpack_from(f"<{n}d", self.raw(off, 8 * n))

    def close(self):
        pass


class WinMap(View):
    """Opens an EXISTING named file mapping (never creates one, so a missing game reads as 'not running')."""
    FILE_MAP_READ = 0x0004

    def __init__(self, name, size):
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.OpenFileMappingW.restype = ctypes.c_void_p
        k32.OpenFileMappingW.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_wchar_p]
        k32.MapViewOfFile.restype = ctypes.c_void_p
        k32.MapViewOfFile.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_size_t]
        k32.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
        k32.CloseHandle.argtypes = [ctypes.c_void_p]
        self._k32 = k32
        self._h = k32.OpenFileMappingW(self.FILE_MAP_READ, 0, name)
        if not self._h:
            raise OSError(f"{name} not available")
        self._p = k32.MapViewOfFile(self._h, self.FILE_MAP_READ, 0, 0, size)
        if not self._p:
            k32.CloseHandle(self._h)
            raise OSError(f"{name} could not be mapped")
        self._size = size

    def raw(self, off, n):
        return ctypes.string_at(self._p + off, n)

    def close(self):
        try:
            self._k32.UnmapViewOfFile(self._p)
            self._k32.CloseHandle(self._h)
        except Exception:
            pass


def open_map(name, size):
    if sys.platform != "win32":
        raise OSError("shared memory telemetry needs Windows")
    return WinMap(name, size)


# ----------------------------------------------------------------------------- data model
class Frame:
    """One telemetry sample, game-independent."""
    __slots__ = ("game", "live", "gear", "rpm", "max_rpm", "shift_rpm", "speed", "tc", "tc2", "abs", "map",
                 "bb", "fuel", "fuel_laps", "pit_limiter", "abs_active", "tc_active", "delta_ms",
                 "v_abs", "v_kerb", "v_slip", "v_road", "impact", "ffb")

    def __init__(self, game):
        self.game = game
        self.live = False
        self.gear = 0            # -1 R, 0 N, 1.. forward
        self.rpm = 0.0
        self.max_rpm = 0.0
        self.shift_rpm = 0.0
        self.speed = 0.0         # km/h
        self.tc = self.tc2 = self.abs = self.map = -1   # -1 = n/a
        self.bb = -1.0           # % front
        self.fuel = -1.0         # litres
        self.fuel_laps = -1.0
        self.pit_limiter = False
        self.abs_active = False
        self.tc_active = False
        self.delta_ms = None
        # vibration channels 0..1
        self.v_abs = 0.0         # ABS working / wheel lock-up
        self.v_kerb = 0.0        # rumble strips
        self.v_slip = 0.0        # wheelspin / sliding
        self.v_road = 0.0        # off-track surfaces, road texture
        self.impact = 0.0        # >0 on the sample where an impact happened (strength 0..1)
        self.ffb = None          # raw steering force signal, if the game provides one


def clamp01(v):
    return 0.0 if v < 0 else 1.0 if v > 1 else v


# ----------------------------------------------------------------------------- ACC
class AccReader:
    name = "ACC"

    def __init__(self, opener=open_map):
        self.phy = opener("Local\\acpmf_physics", ACC_PHY["SIZE"])
        self.gfx = opener("Local\\acpmf_graphics", ACC_GFX["SIZE"])
        try:
            self.sta = opener("Local\\acpmf_static", ACC_STA["SIZE"])
        except OSError:
            self.sta = None
        self._last_g = None
        self._last_pkt = None
        self._same_pkt = 0

    def read(self):
        p, g = self.phy, self.gfx
        f = Frame(self.name)
        status = g.i32(ACC_GFX["status"])          # 0 off, 1 replay, 2 live, 3 pause
        pkt = p.i32(ACC_PHY["packetId"])
        self._same_pkt = self._same_pkt + 1 if pkt == self._last_pkt else 0
        self._last_pkt = pkt
        f.live = status == 2 and self._same_pkt < 50
        f.gear = p.i32(ACC_PHY["gear"]) - 1        # ACC: 0 = R, 1 = N, 2 = 1st
        f.rpm = float(p.i32(ACC_PHY["rpms"]))
        mx = p.i32(ACC_PHY["currentMaxRpm"])
        if mx <= 0 and self.sta is not None:
            mx = self.sta.i32(ACC_STA["maxRpm"])
        f.max_rpm = float(max(mx, 0))
        f.shift_rpm = f.max_rpm * 0.965
        f.speed = p.f32(ACC_PHY["speedKmh"])
        f.tc = g.i32(ACC_GFX["TC"])
        f.tc2 = g.i32(ACC_GFX["TCCut"])
        f.abs = g.i32(ACC_GFX["ABS"])
        f.map = g.i32(ACC_GFX["EngineMap"]) + 1
        bb = p.f32(ACC_PHY["brakeBias"])
        f.bb = bb * 100.0 if 0.0 < bb < 1.0 else -1.0
        f.fuel = p.f32(ACC_PHY["fuel"])
        f.fuel_laps = g.f32(ACC_GFX["fuelEstimatedLaps"])
        f.pit_limiter = p.i32(ACC_PHY["pitLimiterOn"]) != 0
        f.abs_active = p.i32(ACC_PHY["absInAction"]) != 0
        f.tc_active = p.i32(ACC_PHY["tcinAction"]) != 0
        d = g.i32(ACC_GFX["iDeltaLapTime"])
        if abs(d) < 600000:
            f.delta_ms = d if g.i32(ACC_GFX["isDeltaPositive"]) else -abs(d)

        # ACC already provides haptic signals made for this purpose
        av = p.f32(ACC_PHY["absVibrations"])
        f.v_abs = clamp01(max(av * 1.5, 0.55 if f.abs_active else 0.0))
        f.v_kerb = clamp01(p.f32(ACC_PHY["kerbVibration"]) * 1.5)
        f.v_slip = clamp01(p.f32(ACC_PHY["slipVibrations"]) * 1.5)
        f.v_road = clamp01(p.f32(ACC_PHY["gVibrations"]) * 1.5)
        f.ffb = p.f32(ACC_PHY["finalFF"])
        gx, gy, gz = p.f32s(ACC_PHY["accG"], 3)
        gmag = math.sqrt(gx * gx + gy * gy + gz * gz)
        if self._last_g is not None and f.live:
            jump = abs(gmag - self._last_g)
            if jump > 2.5:                          # g in one sample = a hit, not driving
                f.impact = clamp01(jump / 8.0)
        self._last_g = gmag
        return f

    def close(self):
        for m in (self.phy, self.gfx, self.sta):
            if m is not None:
                m.close()


# ----------------------------------------------------------------------------- RaceRoom
class R3eReader:
    name = "RaceRoom"

    def __init__(self, opener=open_map):
        self.m = opener("$R3E", R3E["SIZE"])
        self._last_acc = None
        if self.m.i32(R3E["version_major"]) not in (0, 3):
            self.close()
            raise OSError("unsupported RaceRoom shared memory version")

    def read(self):
        m = self.m
        f = Frame(self.name)
        if m.i32(R3E["version_major"]) != 3:
            return f
        f.live = (m.i32(R3E["game_paused"]) == 0 and m.i32(R3E["game_in_menus"]) == 0
                  and m.i32(R3E["game_in_replay"]) == 0)
        rps2rpm = 60.0 / (2 * math.pi)
        f.gear = m.i32(R3E["gear"])
        f.rpm = max(0.0, m.f32(R3E["engine_rps"]) * rps2rpm)
        f.max_rpm = max(0.0, m.f32(R3E["max_engine_rps"]) * rps2rpm)
        up = m.f32(R3E["upshift_rps"]) * rps2rpm
        f.shift_rpm = up if up > 0 else f.max_rpm * 0.965
        car_ms = m.f32(R3E["car_speed"])
        f.speed = max(0.0, car_ms * 3.6)
        aid_abs = m.i32(R3E["aid_abs"])
        aid_tc = m.i32(R3E["aid_tc"])
        f.abs_active = aid_abs == 5
        f.tc_active = aid_tc == 5
        f.abs = m.i32(R3E["abs_setting"])
        tcs = m.i32(R3E["traction_control_setting"])
        f.tc = tcs if 0 <= tcs < 100 else -1
        f.map = m.i32(R3E["engine_map_setting"])
        bb = m.f32(R3E["brake_bias"])
        f.bb = (1.0 - bb) * 100.0 if 0.0 < bb < 1.0 else -1.0     # r3e reports rearward bias
        f.fuel = m.f32(R3E["fuel_left"])
        per = m.f32(R3E["fuel_per_lap"])
        f.fuel_laps = f.fuel / per if per > 0 and f.fuel >= 0 else -1.0
        f.pit_limiter = m.i32(R3E["pit_limiter"]) == 1

        # derived vibration
        ts = m.f32s(R3E["tire_speed"], 4)          # FL FR RL RR, m/s
        mtrl = m.i32s(R3E["tire_on_mtrl"], 4)
        brake = m.f32(R3E["brake"])
        speed_k = clamp01(car_ms / 40.0)
        if car_ms > 3:
            rear = (ts[2] + ts[3]) / 2.0
            spin = (rear - car_ms) / max(car_ms, 5.0)
            f.v_slip = clamp01((spin - 0.06) * 5.0)
            front = (ts[0] + ts[1]) / 2.0
            lock = (car_ms - front) / max(car_ms, 5.0)
            if brake > 0.05:
                f.v_abs = clamp01((lock - 0.08) * 4.0)
        if f.abs_active:
            f.v_abs = max(f.v_abs, 0.6)
        rumble = sum(1 for x in mtrl if x == R3E_RUMBLE)
        if rumble:
            f.v_kerb = clamp01((0.45 + 0.15 * rumble) * (0.4 + 0.6 * speed_k))
        rough = sum(1 for x in mtrl if x in (R3E_GRASS, R3E_DIRT, R3E_GRAVEL))
        if rough:
            f.v_road = clamp01(0.25 * rough * (0.3 + 0.7 * speed_k))
        ax, ay, az = m.f32s(R3E["local_acceleration"], 3)
        amag = math.sqrt(ax * ax + ay * ay + az * az)
        if self._last_acc is not None and f.live:
            jump = abs(amag - self._last_acc)
            if jump > 25.0:                         # m/s^2 change in one sample
                f.impact = clamp01(jump / 80.0)
        self._last_acc = amag
        return f

    def close(self):
        self.m.close()


# ----------------------------------------------------------------------------- LMU / rFactor 2
class Rf2Reader:
    name = "LMU"

    def __init__(self, opener=open_map):
        self.tel = opener("$rFactor2SMMP_Telemetry$", RF2_TEL["SIZE"])
        self.sco = opener("$rFactor2SMMP_Scoring$", RF2_SCO["SIZE"])
        try:
            self.ffb = opener("$rFactor2SMMP_ForceFeedback$", 8)
        except OSError:
            self.ffb = None
        self._last_impact_et = None
        self._last_et = None
        self._same_et = 0

    def _player_id(self):
        s = self.sco
        n = s.i32(RF2_SCO["info_mNumVehicles"])
        for i in range(max(0, min(n, 128))):
            base = RF2_SCO["mVehicles"] + i * RF2_SCO["VEH_SIZE"]
            if s.u8(base + RF2_SCO["veh_mIsPlayer"]):
                return s.i32(base + RF2_SCO["veh_mID"])
        return None

    def read(self):
        f = Frame(self.name)
        pid = self._player_id()
        if pid is None:
            return f
        t = self.tel
        n = t.i32(RF2_TEL["mNumVehicles"])
        base = None
        for i in range(max(0, min(n, 128))):
            b = RF2_TEL["mVehicles"] + i * RF2_TEL["VEH_SIZE"]
            if t.i32(b + RF2_VEH["mID"]) == pid:
                base = b
                break
        if base is None:
            return f
        V = RF2_VEH
        et = t.f64(base + V["mElapsedTime"])
        self._same_et = self._same_et + 1 if et == self._last_et else 0
        self._last_et = et
        f.live = self._same_et < 50
        f.gear = t.i32(base + V["mGear"])
        f.rpm = max(0.0, t.f64(base + V["mEngineRPM"]))
        f.max_rpm = max(0.0, t.f64(base + V["mEngineMaxRPM"]))
        f.shift_rpm = f.max_rpm * 0.965
        vx, vy, vz = t.f64s(base + V["mLocalVel"], 3)
        speed_ms = math.sqrt(vx * vx + vy * vy + vz * vz)
        f.speed = speed_ms * 3.6
        f.fuel = t.f64(base + V["mFuel"])
        rb = t.f64(base + V["mRearBrakeBias"])
        f.bb = (1.0 - rb) * 100.0 if 0.0 < rb < 1.0 else -1.0
        f.pit_limiter = t.u8(base + V["mSpeedLimiter"]) != 0
        brake = t.f64(base + V["mUnfilteredBrake"])

        speed_k = clamp01(speed_ms / 40.0)
        lock = spin = 0.0
        rumble = rough = 0
        for w in range(4):
            wb = base + V["mWheels"] + w * V["WHEEL_SIZE"]
            patch = t.f64(wb + RF2_WHEEL["mLongitudinalPatchVel"])
            ground = t.f64(wb + RF2_WHEEL["mLongitudinalGroundVel"])
            ratio = abs(patch) / max(abs(ground), 3.0)
            if w < 2 and brake > 0.05:
                lock = max(lock, ratio)
            if w >= 2 and brake < 0.05:
                spin = max(spin, ratio)
            surf = t.u8(wb + RF2_WHEEL["mSurfaceType"])
            if surf == RF2_SURF_RUMBLE:
                rumble += 1
            elif surf in (RF2_SURF_GRASS, RF2_SURF_DIRT, RF2_SURF_GRAVEL):
                rough += 1
        if speed_ms > 3:
            f.v_abs = clamp01((lock - 0.08) * 4.0)
            f.v_slip = clamp01((spin - 0.08) * 4.0)
            f.abs_active = f.v_abs > 0.3
        if rumble:
            f.v_kerb = clamp01((0.45 + 0.15 * rumble) * (0.4 + 0.6 * speed_k))
        if rough:
            f.v_road = clamp01(0.25 * rough * (0.3 + 0.7 * speed_k))
        iet = t.f64(base + V["mLastImpactET"])
        if self._last_impact_et is not None and iet > self._last_impact_et and f.live:
            f.impact = clamp01(t.f64(base + V["mLastImpactMagnitude"]) / 3000.0 + 0.3)
        self._last_impact_et = iet
        if self.ffb is not None:
            f.ffb = self.ffb.f64(0)
        return f

    def close(self):
        for m in (self.tel, self.sco, self.ffb):
            if m is not None:
                m.close()


READERS = (AccReader, R3eReader, Rf2Reader)


# ----------------------------------------------------------------------------- source + effects
class Telemetry:
    """Finds whichever supported game is running and produces phone packets."""

    def __init__(self, opener=open_map, readers=READERS):
        self.opener = opener
        self.readers = readers
        self.reader = None
        self.next_probe = 0.0
        self.frame = None
        self.shift_seq = 0
        self.impact_seq = 0
        self.impact_mag = 0.0
        self._last_gear = None
        self._ffb_hist = []      # last 3 FFB samples
        self._ffb_peak = 0.0
        self.last_error = ""

    @property
    def game(self):
        return self.reader.name if self.reader else ""

    def poll(self):
        """Returns a Frame, or None when no game is running."""
        now = time.monotonic()
        if self.reader is None:
            if now < self.next_probe:
                return None
            self.next_probe = now + 2.0
            for R in self.readers:
                try:
                    self.reader = R(self.opener)
                    break
                except OSError:
                    continue
                except Exception as e:  # malformed data etc.
                    self.last_error = str(e)
            if self.reader is None:
                return None
        try:
            f = self.reader.read()
        except Exception as e:
            self.last_error = str(e)
            self.reader.close()
            self.reader = None
            self.frame = None
            return None
        if f.live and f.gear != self._last_gear and self._last_gear is not None and f.gear > 0 and self._last_gear > 0:
            self.shift_seq += 1
        self._last_gear = f.gear
        if f.impact > 0:
            self.impact_seq += 1
            self.impact_mag = f.impact
        # road texture from the game's own force-feedback signal.
        # Uses the 2nd difference (f[n] - 2 f[n-1] + f[n-2]): fast bumps/vibration show up,
        # slow changes from turning the wheel cancel out.
        if f.ffb is not None and math.isfinite(f.ffb):
            h = self._ffb_hist
            h.append(f.ffb)
            if len(h) > 3:
                h.pop(0)
            if len(h) == 3:
                texture = abs(h[2] - 2 * h[1] + h[0])
                # games use different FFB units: measure texture relative to the recent peak force
                self._ffb_peak = max(abs(f.ffb), self._ffb_peak * 0.9995, 1e-6)
                rel = texture / self._ffb_peak
                f.v_road = max(f.v_road, clamp01((rel - 0.04) / 0.25))

        # nothing to feel when the car is (nearly) standing still: fade effects in with speed
        move_k = clamp01((f.speed - 8.0) / 22.0)       # 0 below 8 km/h, full from 30 km/h
        f.v_slip *= move_k
        f.v_road *= move_k
        f.v_kerb *= clamp01((f.speed - 3.0) / 10.0)
        self.frame = f
        return f

    # ---- packets to the phone
    def dash_packet(self, f):
        def i(v):
            return str(int(v)) if v is not None and v >= 0 else "x"

        def fl(v, d=1):
            return f"{v:.{d}f}" if v is not None and v >= 0 else "x"
        delta = "x" if f.delta_ms is None else str(int(f.delta_ms))
        return ",".join([
            "t", f.game, "1" if f.live else "0", str(int(f.gear)), str(int(f.rpm)), str(int(f.shift_rpm)),
            str(int(f.max_rpm)), str(int(round(f.speed))), i(f.tc), i(f.tc2), i(f.abs), i(f.map), fl(f.bb),
            fl(f.fuel), fl(f.fuel_laps), "1" if f.pit_limiter else "0", "1" if f.abs_active else "0",
            "1" if f.tc_active else "0", delta,
        ])

    def vibe_packet(self, f):
        live = f.live
        limiter = live and f.max_rpm > 0 and f.rpm >= f.max_rpm * 0.985 and f.gear > 0
        z = 0.0 if not live else 1.0
        return (f"v,{f.v_abs * z:.2f},{f.v_kerb * z:.2f},{f.v_slip * z:.2f},{f.v_road * z:.2f},"
                f"{self.impact_seq},{self.impact_mag:.2f},{self.shift_seq},{1 if limiter else 0}")

    def close(self):
        if self.reader:
            self.reader.close()
            self.reader = None
