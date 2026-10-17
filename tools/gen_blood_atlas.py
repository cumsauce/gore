#!/usr/bin/env python3
"""Generate the Gore blood-splat decal atlas.

Produces a 256x256 RGBA PNG (4x4 grid of 64px cells) at
src/main/resources/assets/gore/textures/decal/blood_atlas.png

Layout (cell i at x=(i%4)*64, y=(i//4)*64), splat tail points UP (-y):

  cells 0-11  impact splats: cell = elongationBand * 3 + densityBand
              elongation bands 0-3 -> b/a = [1.0, 0.7, 0.45, 0.25]
              density bands 0-2    -> satellite counts [0, 4, 9]
  cells 12-13 pool blobs (two sizes, no tail)
  cell 14        drip run strip (full cell height, 6px wide, dark edges)
  cell 15        drip leading bead (single ellipse)

Colours: base (120,14,14,255); spine/satellites (90,8,8,255);
1px lighter leading edge (150,24,20,255).

Stdlib only: zlib + struct for PNG encoding, no premultiplied alpha.
Run: python3 tools/gen_blood_atlas.py
"""

import math
import os
import random
import struct
import zlib

SIZE = 256  # atlas width/height
CELL = 64  # cell edge length
GRID = 4  # cells per row/column

BASE = (120, 14, 14, 255)
DARK = (90, 8, 8, 255)
EDGE = (150, 24, 20, 255)

# elongation band -> b/a (semi-minor across tail / semi-major along tail)
ELONGATION = (1.0, 0.7, 0.45, 0.25)
# density band -> satellite count (radius shrinks as count grows)
SAT_COUNTS = (0, 4, 9)
SAT_RADII = {1: (2, 3), 2: (1, 2)}

TAIL_AXIS = 32  # tail axis x, in cell-local pixels
ELLIPSE_CY = 37  # ellipse centre y (cell centre 32, "slightly below")
ELLIPSE_A = 24.0  # semi-major along the tail axis
TAIL_TIP = 6  # tail tip y, cell-local
SPINE_W = 1  # spine half-width (3px line)
SAT_SPREAD = 0.7  # satellite sigma as fraction of semi-minor b
SAT_EXTENT = 1.25  # outermost satellite offset as fraction of b
SAT_CLAMP = 28.0  # max lateral offset from the tail axis, px

SEED = 20261016

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PATH = os.path.join(
    REPO_ROOT, "src/main/resources/assets/gore/textures/decal/blood_atlas.png"
)


def put(buf, x, y, rgba, clip):
    """Write one pixel if inside the clip rect (inclusive)."""
    x0, y0, x1, y1 = clip
    if x0 <= x <= x1 and y0 <= y <= y1:
        o = (y * SIZE + x) * 4
        buf[o] = rgba[0]
        buf[o + 1] = rgba[1]
        buf[o + 2] = rgba[2]
        buf[o + 3] = rgba[3]


def fill_ellipse(buf, cx, cy, a, b, clip, color=BASE, leading_edge=None):
    """Filled axis-aligned ellipse (a vertical, b horizontal).

    If leading_edge is set, pixels within ~1px of the boundary on the
    leading side (y < cy, i.e. the side facing the tail direction) are
    painted with that colour instead of `color`.
    """
    for y in range(int(cy - a) - 1, int(cy + a) + 2):
        for x in range(int(cx - b) - 1, int(cx + b) + 2):
            dx = x - cx
            dy = y - cy
            if (dx / b) ** 2 + (dy / a) ** 2 > 1.0:
                continue
            c = color
            if leading_edge is not None and y < cy:
                inner_b = max(b - 1.0, 1.0)
                inner_a = max(a - 1.0, 1.0)
                if (dx / inner_b) ** 2 + (dy / inner_a) ** 2 > 1.0:
                    c = leading_edge
            put(buf, x, y, c, clip)


def fill_rect(buf, x0, x1, y0, y1, color, clip):
    """Filled rectangle, bounds inclusive."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(buf, x, y, color, clip)


def fill_circle(buf, cx, cy, r, color, clip):
    for y in range(int(cy - r) - 1, int(cy + r) + 2):
        for x in range(int(cx - r) - 1, int(cx + r) + 2):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                put(buf, x, y, color, clip)


def fill_blob(buf, cx, cy, radius, color, clip):
    """Circle with a small deterministic radius wobble for an organic edge."""
    for y in range(int(cy - radius) - 2, int(cy + radius) + 3):
        for x in range(int(cx - radius) - 2, int(cx + radius) + 3):
            dx = x - cx
            dy = y - cy
            ang = math.atan2(dy, dx)
            rr = radius * (
                1.0 + 0.05 * math.sin(3 * ang + 0.7) + 0.035 * math.sin(5 * ang + 2.1)
            )
            if dx * dx + dy * dy <= rr * rr:
                put(buf, x, y, color, clip)


def draw_impact(buf, cell_x, cell_y, elong, dens, rng):
    """One impact splat: ellipse, spine to the tail tip, satellites past it."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    cx = cell_x + TAIL_AXIS
    cy = cell_y + ELLIPSE_CY
    a = ELLIPSE_A
    b = a * ELONGATION[elong]
    tip = cell_y + TAIL_TIP

    # (1) body, centred slightly below cell centre, leading edge lit
    fill_ellipse(buf, cx, cy, a, b, clip, BASE, EDGE)

    # (2) darker spine from the ellipse centre up to the tail tip
    fill_rect(buf, cx - SPINE_W, cx + SPINE_W, tip, cy, DARK, clip)

    # (3) satellites past the tip, biased along the tail axis,
    #     none behind the ellipse (all at or above the tip)
    n = SAT_COUNTS[dens]
    if n == 0:
        return
    zs = [rng.gauss(0.0, SAT_SPREAD) for _ in range(n)]
    peak = max(abs(z) for z in zs) or 1.0
    extent = min(b * SAT_EXTENT, SAT_CLAMP)
    for z in zs:
        r = rng.choice(SAT_RADII[dens])
        x = int(round(cx + z / peak * extent))
        y = rng.randint(cell_y + r, tip)
        x = max(cell_x + r, min(cell_x + CELL - 1 - r, x))
        fill_circle(buf, x, y, r, DARK, clip)


def draw_pool(buf, cell_x, cell_y, radius):
    """Floor pool: a round blob with no tail (band-0 ellipse, two sizes)."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    fill_blob(buf, cell_x + 31.5, cell_y + 31.5, radius, BASE, clip)


def draw_run(buf, cell_x, cell_y):
    """Drip run strip: full cell height, 6px wide, BASE with 1px DARK edges."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    fill_rect(buf, cell_x + 29, cell_x + 34, cell_y, cell_y + CELL - 1, BASE, clip)
    fill_rect(buf, cell_x + 29, cell_x + 29, cell_y, cell_y + CELL - 1, DARK, clip)
    fill_rect(buf, cell_x + 34, cell_x + 34, cell_y, cell_y + CELL - 1, DARK, clip)


def draw_bead(buf, cell_x, cell_y):
    """Drip leading bead: a 9x8 ellipse centred in the cell, lit lower edge."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    fill_ellipse(buf, cell_x + 31.5, cell_y + 31.5, 9.0, 8.0, clip, BASE, EDGE)


def write_png(path, width, height, buf):
    """Encode an RGBA byte buffer as a PNG (colour type 6, straight alpha)."""

    def chunk(typ, data):
        body = typ + data
        return (
            struct.pack(">I", len(data))
            + body
            + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        )

    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None)
        raw += buf[y * stride : (y + 1) * stride]

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    buf = bytearray(SIZE * SIZE * 4)  # fully transparent background

    # impact cells: 4 elongation rows x 3 density columns
    for i in range(12):
        cell_x = (i % GRID) * CELL
        cell_y = (i // GRID) * CELL
        draw_impact(
            buf, cell_x, cell_y, elong=i // 3, dens=i % 3, rng=random.Random(SEED + i)
        )

    # pools: two size variants, no tail
    draw_pool(buf, (12 % GRID) * CELL, (12 // GRID) * CELL, 26.0)
    draw_pool(buf, (13 % GRID) * CELL, (13 // GRID) * CELL, 17.0)

    # drip: full-height run strip (14) and its leading bead (15)
    draw_run(buf, (14 % GRID) * CELL, (14 // GRID) * CELL)
    draw_bead(buf, (15 % GRID) * CELL, (15 // GRID) * CELL)

    write_png(OUT_PATH, SIZE, SIZE, buf)
    opaque = sum(1 for i in range(3, len(buf), 4) if buf[i])
    print(f"wrote {OUT_PATH} ({SIZE}x{SIZE} RGBA, {opaque} opaque px)")


if __name__ == "__main__":
    main()
