#!/usr/bin/env python3
"""Generate gore's blood textures: particle sprites and the decal atlas.

Pure stdlib (no PIL). Deterministic - seeded RNG, same output every run.
Run from anywhere:  python3 tools/gen_textures.py

Outputs:
  src/main/resources/assets/gore/textures/particle/blood_mist_*.png
  src/main/resources/assets/gore/textures/particle/blood_drop_*.png
  src/main/resources/assets/gore/textures/decal/blood_atlas.png   (4x4 cells of 32px)
  src/main/resources/assets/gore/icon.png
"""

import math
import os
import random
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "gore", "textures")

# Blood palette (dark to bright).
DEEP = (56, 8, 10)
BASE = (104, 16, 16)
BRIGHT = (150, 30, 26)
PALE = (176, 54, 44)


def clampf(v, a, b):
    return a if v < a else (b if v > b else v)


def write_png(path, w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)  # filter: none
        raw += rgba[y * stride : (y + 1) * stride]

    def chunk(t, d):
        return (
            struct.pack(">I", len(d))
            + t
            + d
            + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", os.path.relpath(path, ROOT))


class Canvas:
    """Supersampled RGBA canvas; coordinates are in output pixels."""

    def __init__(self, w, h, ss=4):
        self.w, self.h, self.ss = w, h, ss
        self.pw = w * ss
        self.ph = h * ss
        self.px = [[0.0, 0.0, 0.0, 0.0] for _ in range(self.pw * self.ph)]

    def blend(self, x, y, color, alpha):
        if x < 0 or y < 0 or x >= self.pw or y >= self.ph:
            return
        p = self.px[y * self.pw + x]
        sa = clampf(alpha, 0.0, 1.0)
        da = p[3]
        na = sa + da * (1.0 - sa)
        if na <= 0.0:
            return
        for i in range(3):
            sc = color[i] / 255.0
            p[i] = (sc * sa + p[i] * da * (1.0 - sa)) / na
        p[3] = na

    def ellipse(self, cx, cy, rx, ry, angle_deg, color, alpha=1.0):
        ss = self.ss
        cx, cy, rx, ry = cx * ss, cy * ss, rx * ss, ry * ss
        rad = math.radians(angle_deg)
        ca, sa = math.cos(rad), math.sin(rad)
        x0 = max(0, int(cx - rx - 2))
        x1 = min(self.pw - 1, int(cx + rx + 2))
        y0 = max(0, int(cy - ry - 2))
        y1 = min(self.ph - 1, int(cy + ry + 2))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                dx = x + 0.5 - cx
                dy = y + 0.5 - cy
                lx = dx * ca + dy * sa
                ly = -dx * sa + dy * ca
                if (lx / rx) ** 2 + (ly / ry) ** 2 <= 1.0:
                    self.blend(x, y, color, alpha)

    def export(self):
        w, h, ss = self.w, self.h, self.ss
        out = bytearray()
        for y in range(h):
            for x in range(w):
                ar = ag = ab = aa = 0.0
                for yy in range(ss):
                    row = ((y * ss + yy) * self.pw) + x * ss
                    for xx in range(ss):
                        p = self.px[row + xx]
                        a = p[3]
                        ar += p[0] * a
                        ag += p[1] * a
                        ab += p[2] * a
                        aa += a
                n = ss * ss
                if aa > 1e-6:
                    out += bytes(
                        (
                            int(255 * clampf(ar / aa, 0, 1)),
                            int(255 * clampf(ag / aa, 0, 1)),
                            int(255 * clampf(ab / aa, 0, 1)),
                            int(255 * clampf(aa / n, 0, 1)),
                        )
                    )
                else:
                    out += b"\x00\x00\x00\x00"
        return out


def blob(cv, rng, x, y, size, e, angle, colors, alpha=(0.85, 1.0)):
    """One rotated ellipse: `size` = short radius, `e` = elongation along angle."""
    cv.ellipse(x, y, size * e, size, angle, rng.choice(colors), rng.uniform(*alpha))


def splat(
    cv,
    rng,
    x,
    y,
    size,
    e=1.0,
    angle=0.0,
    colors=(BASE, DEEP),
    core=4,
    satellites=5,
    rays=0,
    sat_dir=None,
    spread=0.9,
    reach=13.0,
):
    # Core: a few jittered overlapping blobs.
    for _ in range(core):
        blob(
            cv,
            rng,
            x + rng.uniform(-0.25, 0.25) * size,
            y + rng.uniform(-0.25, 0.25) * size,
            size * rng.uniform(0.5, 0.9),
            e,
            angle,
            colors,
        )
    # Satellites: droplets thrown clear of the core.
    for _ in range(satellites):
        if sat_dir is None:
            a = rng.uniform(0, 2 * math.pi)
        else:
            a = sat_dir + rng.uniform(-spread, spread)
        d = min(size * rng.uniform(e + 0.35, e * 2.2 + 0.3), reach)
        r = size * rng.uniform(0.06, 0.22)
        cv.ellipse(
            x + math.cos(a) * d,
            y + math.sin(a) * d,
            r * rng.uniform(1.0, 1.6),
            r,
            angle + rng.uniform(-20, 20),
            rng.choice(colors),
            rng.uniform(0.6, 0.95),
        )
    # Rays: fine streaks spraying away from the core.
    for _ in range(rays):
        a = angle + (
            rng.uniform(-0.35, 0.35)
            if sat_dir is None
            else sat_dir - angle + rng.uniform(-0.25, 0.25)
        )
        length = min(size * rng.uniform(1.4, 2.6) * e, reach)
        steps = 9
        for i in range(1, steps + 1):
            t = i / steps
            rr = size * 0.3 * (1 - t) * rng.uniform(0.7, 1.1)
            if rr < 0.05:
                continue
            cv.ellipse(
                x + math.cos(a) * length * t,
                y + math.sin(a) * length * t,
                rr,
                rr * 0.8,
                a,
                rng.choice(colors),
                0.55 + 0.4 * (1 - t),
            )


def drip(cv, rng, x, y, size, length, colors=(BASE, DEEP, BRIGHT)):
    cv.ellipse(x, y, size, size * 0.85, 0, rng.choice(colors), 1.0)
    steps = max(8, int(length * 3))
    for i in range(1, steps + 1):
        t = i / steps
        rr = size * (1.0 - 0.75 * t) * rng.uniform(0.8, 1.05)
        cv.ellipse(
            x + rng.uniform(-0.25, 0.25),
            y + length * t,
            rr * 0.85,
            rr * 1.15,
            0,
            rng.choice(colors),
            0.85,
        )


def spatter(cv, rng, x, y, count, r_min, r_max, reach, colors=(BASE, DEEP)):
    for _ in range(count):
        a = rng.uniform(0, 2 * math.pi)
        d = reach * (rng.random() ** 0.55)
        cv.ellipse(
            x + math.cos(a) * d,
            y + math.sin(a) * d,
            rng.uniform(r_min, r_max),
            rng.uniform(r_min, r_max),
            rng.uniform(0, 180),
            rng.choice(colors),
            rng.uniform(0.7, 1.0),
        )


def mist_cluster(cv, rng, cx, cy, n, radius, colors=(BRIGHT, BASE, PALE), scale=1.0):
    for _ in range(n):
        a = rng.uniform(0, 2 * math.pi)
        d = radius * (rng.random() ** 0.6)
        r = rng.uniform(0.35, 1.35) * (1.0 - 0.4 * d / radius) * scale
        cv.ellipse(
            cx + math.cos(a) * d,
            cy + math.sin(a) * d,
            r,
            r * 0.9,
            rng.uniform(0, 180),
            rng.choice(colors),
            rng.uniform(0.45, 0.9),
        )


def gen_particles():
    for i in range(3):
        rng = random.Random(1000 + i)
        cv = Canvas(16, 16)
        mist_cluster(cv, rng, 8, 8, 46, 6.2)
        write_png(
            os.path.join(TEX, "particle", f"blood_mist_{i}.png"), 16, 16, cv.export()
        )

    rng = random.Random(2000)
    cv = Canvas(8, 8)
    cv.ellipse(4, 5.1, 1.9, 1.9, 0, BASE, 1.0)
    cv.ellipse(4, 4.9, 1.2, 1.3, 0, BRIGHT, 0.9)
    for i in range(3):
        t = i / 3
        cv.ellipse(4, 3.9 - t * 1.2, 1.35 - 0.45 * t, 1.0 - 0.3 * t, 0, BASE, 0.95)
    write_png(os.path.join(TEX, "particle", "blood_drop_0.png"), 8, 8, cv.export())

    rng = random.Random(2001)
    cv = Canvas(8, 8)
    cv.ellipse(4, 4.6, 1.7, 2.0, 0, BASE, 1.0)
    cv.ellipse(4, 4.2, 1.0, 1.2, 0, DEEP, 0.9)
    for i in range(4):
        t = i / 4
        cv.ellipse(4, 3.4 - t * 1.6, 1.2 - 0.5 * t, 0.9 - 0.35 * t, 0, BASE, 0.95)
    write_png(os.path.join(TEX, "particle", "blood_drop_1.png"), 8, 8, cv.export())


def gen_atlas():
    cell = 32
    cv = Canvas(cell * 4, cell * 4)
    rng = random.Random(3000)

    def at(ix, iy):
        return ix * cell + cell / 2.0, iy * cell + cell / 2.0

    x, y = at(0, 0)  # big impact
    splat(
        cv,
        rng,
        x,
        y,
        6.2,
        e=1.0,
        angle=rng.uniform(0, 360),
        satellites=7,
        rays=4,
        core=5,
    )
    x, y = at(1, 0)  # medium impact
    splat(cv, rng, x, y, 4.6, e=1.0, angle=rng.uniform(0, 360), satellites=6, rays=2)
    x, y = at(2, 0)  # grazing streak
    splat(cv, rng, x, y, 3.4, e=2.6, angle=-25, satellites=5, rays=3, core=3)
    x, y = at(3, 0)  # pooled blob
    for _ in range(4):
        blob(
            cv,
            rng,
            x + rng.uniform(-3, 3),
            y + rng.uniform(-3, 3),
            rng.uniform(4.5, 7.5),
            1.3,
            rng.uniform(0, 180),
            (BASE, DEEP),
        )
    splat(cv, rng, x, y, 3.0, satellites=4, rays=0, core=1)

    x, y = at(0, 1)  # small impact
    splat(cv, rng, x, y, 3.4, angle=rng.uniform(0, 360), satellites=5, rays=1, core=3)
    x, y = at(1, 1)  # fine spatter
    spatter(cv, rng, x, y, 16, 0.35, 1.3, 12.0)
    x, y = at(2, 1)  # long streak
    splat(cv, rng, x, y, 3.0, e=3.2, angle=15, satellites=6, rays=4, core=3, spread=0.5)
    x, y = at(3, 1)  # small pool
    for _ in range(3):
        blob(
            cv,
            rng,
            x + rng.uniform(-2, 2),
            y + rng.uniform(-2, 2),
            rng.uniform(3.5, 6.0),
            1.25,
            rng.uniform(0, 180),
            (BASE, DEEP, BRIGHT),
        )

    x, y = at(0, 2)  # single drip (hangs down in texture space)
    drip(cv, rng, x, y - 9, 2.6, 17)
    x, y = at(1, 2)  # double drip with base
    drip(cv, rng, x - 3.5, y - 8, 2.0, 14)
    drip(cv, rng, x + 3.5, y - 10, 2.2, 18)
    cv.ellipse(x, y + 8.5, 3.4, 2.2, 0, BASE, 0.95)
    x, y = at(2, 2)  # dense spatter
    spatter(cv, rng, x, y, 26, 0.3, 0.9, 10.0)
    x, y = at(3, 2)  # coarse spatter
    spatter(cv, rng, x, y, 9, 0.8, 2.0, 13.0)

    x, y = at(0, 3)  # directional impact (spray toward -x)
    splat(
        cv,
        rng,
        x,
        y,
        4.4,
        angle=180,
        satellites=7,
        rays=2,
        core=4,
        sat_dir=math.pi,
        spread=0.7,
    )
    x, y = at(1, 3)  # directional impact opposite
    splat(
        cv,
        rng,
        x,
        y,
        4.4,
        angle=0,
        satellites=7,
        rays=2,
        core=4,
        sat_dir=0.0,
        spread=0.7,
    )
    x, y = at(2, 3)  # tiny pool
    blob(cv, rng, x, y, 3.2, 1.3, rng.uniform(0, 180), (BASE, DEEP))
    blob(cv, rng, x + 1.5, y + 1.2, 2.2, 1.5, rng.uniform(0, 180), (BASE,))
    x, y = at(3, 3)  # fine spray streak
    splat(
        cv,
        rng,
        x,
        y,
        2.6,
        e=3.8,
        angle=rng.uniform(0, 360),
        satellites=8,
        rays=5,
        core=2,
        spread=0.4,
    )

    write_png(os.path.join(TEX, "decal", "blood_atlas.png"), cv.w, cv.h, cv.export())


def gen_icon():
    rng = random.Random(4000)
    cv = Canvas(64, 64)
    cv.ellipse(32, 32, 30, 30, 0, (26, 10, 10), 1.0)
    splat(
        cv,
        rng,
        32,
        34,
        12.5,
        angle=rng.uniform(0, 360),
        satellites=8,
        rays=5,
        core=6,
        colors=(BRIGHT, BASE, PALE),
        reach=26,
    )
    write_png(
        os.path.join(ROOT, "src", "main", "resources", "assets", "gore", "icon.png"),
        64,
        64,
        cv.export(),
    )


if __name__ == "__main__":
    gen_particles()
    gen_atlas()
    gen_icon()
    print("done")
