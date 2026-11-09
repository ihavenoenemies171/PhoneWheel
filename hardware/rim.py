"""
PhoneWheel GT3-style rim - parametric CAD (CadQuery).

    pip install cadquery
    python rim.py                 # writes STL (for printing) and STEP (for SolidWorks/Fusion) into ./out

Change the PHONE_* values to your phone (measure it WITH its case, or without and use a thin case),
then re-run. Every part fits a 220 x 220 mm print bed. Units: millimetres.
"""
import os
import cadquery as cq

# ----------------------------------------------------------------------------- your phone
PHONE_L = 165.0      # long side (landscape width)
PHONE_W = 77.0       # short side (landscape height)
PHONE_T = 9.5        # thickness incl. camera bump margin / thin case
CLEAR = 0.8          # clearance around the phone

# ----------------------------------------------------------------------------- rim
WALL = 3.0           # cradle side walls
BASE = 3.0           # cradle back plate
LIP = 2.5            # how far the top/bottom lips reach over the screen edge (stay inside the bezel)
LIP_T = 1.8          # lip thickness
USB_W, USB_H = 16.0, 8.0   # cable slot in the right end wall (USB tethering while driving)
FLANGE = 21.0        # mounting flange reaching out from each end of the cradle (keeps the cradle under 220 mm)
FLANGE_T = 6.0
BOLT = 4.4           # M4 clearance holes
NUT = 7.2            # M4 nut across flats (+clearance), captured in the grip
GRIP_W = 34.0        # grip thickness (x)
GRIP_EXTRA = 55.0    # how much the grips extend beyond the cradle height (y), split top/bottom
GRIP_D = 30.0        # grip depth (z)
GRIP_R = 9.0         # rounding

# derived
IN_L = PHONE_L + 2 * CLEAR
IN_W = PHONE_W + 2 * CLEAR
OUT_L = IN_L + 2 * WALL
OUT_W = IN_W + 2 * WALL
H = BASE + PHONE_T + CLEAR + LIP_T
HOLES = [(FLANGE * 0.45, OUT_W * 0.28), (FLANGE * 0.45, -OUT_W * 0.28)]   # relative to flange root


def cradle():
    body = cq.Workplane("XY").box(OUT_L, OUT_W, H, centered=(True, True, False))
    # phone pocket
    body = body.cut(cq.Workplane("XY").workplane(offset=BASE).box(IN_L, IN_W, H, centered=(True, True, False)))
    # lips on the long (top/bottom) edges: open the top layer narrower than the pocket
    body = body.union(
        cq.Workplane("XY").workplane(offset=H - LIP_T).box(OUT_L, OUT_W, LIP_T, centered=(True, True, False))
        .cut(cq.Workplane("XY").workplane(offset=H - LIP_T).box(IN_L + 2 * WALL, IN_W - 2 * LIP, LIP_T, centered=(True, True, False)))
    )
    # left end open so the phone slides in (held by the gate part)
    body = body.cut(cq.Workplane("XY").workplane(offset=BASE).center(-OUT_L / 2 + WALL / 2, 0)
                    .box(WALL + 0.2, IN_W, H, centered=(True, True, False)))
    # USB cable slot in the right end wall
    body = body.cut(cq.Workplane("XY").workplane(offset=BASE + (PHONE_T - USB_H) / 2).center(OUT_L / 2 - WALL / 2, 0)
                    .box(WALL + 1, USB_W, USB_H, centered=(True, True, False)))
    # big back window: cameras, cooling, less plastic
    body = body.cut(cq.Workplane("XY").box(IN_L - 36, IN_W - 26, BASE * 3, centered=(True, True, True)).edges("|Z").fillet(6))
    # mounting flanges for the grips (flush with the back)
    for side in (-1, 1):
        fl = (cq.Workplane("XY").center(side * (OUT_L / 2 + FLANGE / 2 - 0.01), 0)
              .box(FLANGE, OUT_W, FLANGE_T, centered=(True, True, False)))
        for (hx, hy) in HOLES:
            fl = fl.cut(cq.Workplane("XY").center(side * (OUT_L / 2 + hx), hy).circle(BOLT / 2).extrude(FLANGE_T))
        body = body.union(fl)
    # gate screw holes (2x M3, self-tapping) in the left flange
    for y in GATE_Y:
        body = body.cut(cq.Workplane("XY").center(-OUT_L / 2 - 6, y).circle(1.4).extrude(FLANGE_T))
    return body


GATE_Y = (IN_W / 2 - 6, -IN_W / 2 + 6)


def gate():
    """L-shaped gate that closes the open (left) end after the phone slid in: a wall that drops into the
    missing end wall, and a foot that screws onto the flange with 2x M3. Shown in cradle coordinates."""
    wall = (cq.Workplane("XY").workplane(offset=BASE).center(-OUT_L / 2 + WALL / 2, 0)
            .box(WALL - 0.3, IN_W - 0.4, PHONE_T + CLEAR, centered=(True, True, False)))
    foot = (cq.Workplane("XY").workplane(offset=FLANGE_T).center(-OUT_L / 2 - 6, 0)
            .box(12, OUT_W, 3, centered=(True, True, False)))
    bridge = (cq.Workplane("XY").workplane(offset=FLANGE_T).center(-OUT_L / 2 + (WALL - 0.3) / 2, 0)
              .box(WALL - 0.3 + 0.02, IN_W - 0.4, 3, centered=(True, True, False)))
    g = wall.union(foot.translate((-0.01, 0, 0))).union(bridge)
    for y in GATE_Y:
        g = g.cut(cq.Workplane("XY").center(-OUT_L / 2 - 6, y).circle(1.7).extrude(FLANGE_T + 4))
    return g


def grip(side=1):
    """Right grip (side=1) or left grip (side=-1). Bolts to the cradle flange with 2x M4 + captured nuts."""
    length = OUT_W + GRIP_EXTRA
    g = (cq.Workplane("XY").box(GRIP_W, length, GRIP_D, centered=(True, True, False))
         .edges().fillet(GRIP_R))
    # mounting pad that sits under the flange
    pad = cq.Workplane("XY").center(-side * (GRIP_W / 2 + FLANGE / 2 - 4), 0).box(FLANGE + 8, OUT_W, FLANGE_T, centered=(True, True, False))
    g = g.union(pad.edges("|Z").fillet(3))
    # bolt holes + nut traps (flange root is at x = -side*(GRIP_W/2 + FLANGE - 4) relative to grip centre)
    root = -side * (GRIP_W / 2 + FLANGE - 4)
    for (hx, hy) in HOLES:
        x = root + side * hx
        g = g.cut(cq.Workplane("XY").center(x, hy).circle(BOLT / 2).extrude(GRIP_D))
        g = g.cut(cq.Workplane("XY").center(x, hy).polygon(6, NUT / 0.866).extrude(3.5))
    # finger grooves on the back of the grip
    for k in (-1, 0, 1):
        g = g.cut(cq.Workplane("YZ").center(k * 24, -2).circle(7).extrude(GRIP_W, both=True))
    return g


def paddle_lever():
    """EXPERIMENTAL screen tapper. Pivots on an M3 screw in the bracket; pull the back tab with a finger and
    the front tip comes down onto the paddle area of the screen. The tip needs a capacitive rubber stylus
    tip (6 mm) wired to the finger tab with copper tape, or print it in conductive filament."""
    arm_len = 30.0     # from pivot to the tip (over the screen)
    tab_len = 45.0     # from pivot to the finger tab (behind the rim)
    t = 6.0
    lev = cq.Workplane("XZ").polyline([(-tab_len, 0), (arm_len, 0), (arm_len, -14), (arm_len - 8, -14),
                                       (arm_len - 8, -4), (-tab_len + 12, -4), (-tab_len + 12, 10), (-tab_len, 10)]).close().extrude(t)
    lev = lev.cut(cq.Workplane("XZ").center(0, -2).circle(1.7).extrude(t))              # pivot (M3)
    lev = lev.cut(cq.Workplane("XY").workplane(offset=-14).center(arm_len - 4, -t / 2).circle(3.1).extrude(8))  # tip socket
    lev = lev.union(cq.Workplane("XZ").center(-tab_len + 4, 14).circle(2.5).extrude(t))   # rubber band hook
    return lev


def paddle_bracket():
    """Pivot bracket that glues/screws to the top edge of the cradle above the paddle zone."""
    b = cq.Workplane("XY").box(20, 10, 4, centered=(True, True, False))
    for x in (-7, 7):
        b = b.union(cq.Workplane("XY").center(x, 0).box(3, 10, 16, centered=(True, True, False)))
    b = b.cut(cq.Workplane("YZ").center(0, 13).circle(1.7).extrude(20, both=True))
    b = b.union(cq.Workplane("XY").center(0, -9).box(6, 8, 4, centered=(True, True, False)))   # band hook anchor
    return b


PARTS = {
    "cradle": cradle,
    "gate": gate,
    "grip_right": lambda: grip(1),
    "grip_left": lambda: grip(-1),
    "paddle_lever_x2": paddle_lever,
    "paddle_bracket_x2": paddle_bracket,
}

def assembly():
    """All parts in place (for previews / checking fit)."""
    a = cradle()
    for side in (1, -1):
        gx = side * (OUT_L / 2 + GRIP_W / 2 + FLANGE - 4)
        a = a.union(grip(side).translate((gx, 0, -FLANGE_T)))
    a = a.union(gate())
    return a


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
    os.makedirs(out, exist_ok=True)
    for name, make in PARTS.items():
        shape = make()
        bb = shape.val().BoundingBox()
        cq.exporters.export(shape, os.path.join(out, f"{name}.stl"), tolerance=0.05, angularTolerance=0.1)
        cq.exporters.export(shape, os.path.join(out, f"{name}.step"))
        print(f"{name:20s} {bb.xlen:6.1f} x {bb.ylen:6.1f} x {bb.zlen:5.1f} mm")
    asm = assembly()
    cq.exporters.export(asm, os.path.join(out, "assembly_preview.step"))
    for view, d in (("front", (0, 0, 1)), ("iso", (-1, -1.4, 1.6))):
        cq.exporters.export(asm, os.path.join(out, f"preview_{view}.svg"),
                            opt={"width": 900, "height": 420, "projectionDir": d, "showHidden": False,
                                 "strokeWidth": 0.6, "strokeColor": (40, 40, 40)})
