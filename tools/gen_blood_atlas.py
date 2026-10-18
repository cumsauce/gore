#!/usr/bin/env python3
"""Generate the Gore blood-splat decal atlas.

Produces a 512x512 RGBA PNG (8x8 grid of 64px cells) at
src/main/resources/assets/gore/textures/decal/blood_atlas.png

Layout (cell i at x=(i%8)*64, y=(i//8)*64), splat tail points UP (-y):

  cells 0-11  impact splats: cell = elongationBand * 3 + densityBand
              elongation bands 0-3 -> b/a = [1.0, 0.7, 0.45, 0.25]
              density bands 0-2    -> satellite counts [0, 4, 9]
  cells 12-13 pool blobs (two sizes, no tail)
  cell 14        drip run strip (full cell height, ~6px wide, dark edges)
  cell 15        drip leading bead (single blob)
  cells 16-63 reserved, left fully transparent for later variants

Every shape is a signed-distance field, not a filled primitive. Each
pixel's alpha is a smoothstep ramp across the field boundary multiplied
by a thickness-noise term, and its colour ramps EDGE (rim) -> BASE ->
DARK (thick interior). That yields soft, irregular edges and internal
shading rather than a hard-edged "circle with a line" sticker.

Stdlib only: zlib + struct for PNG encoding, no premultiplied alpha.
Run: python3 tools/gen_blood_atlas.py
"""

import math
import os
import random
import struct
import zlib

SIZE = 512  # atlas width/height
CELL = 64  # cell edge length
GRID = 8  # cells per row/column
ART_CELLS = 16  # cells 0-15 carry art; 16-63 stay empty

BASE = (120, 14, 14, 255)
DARK = (90, 8, 8, 255)
EDGE = (150, 24, 20, 255)
# Wound colours: a dark laceration with raised (slightly lighter) edges.
WOUND_DARK = (25, 2, 2, 255)
WOUND_RAISED = (100, 12, 10, 255)
WOUND_EDGE = (135, 18, 14, 255)

# elongation band -> b/a (semi-minor across tail / semi-major along tail)
ELONGATION = (1.0, 0.7, 0.45, 0.25)
# density band -> satellite count (radius shrinks as count grows)
SAT_COUNTS = (0, 4, 9)
SAT_RADII = {1: (2, 3), 2: (1, 2)}

TAIL_AXIS = 32  # tail axis x, in cell-local pixels
ELLIPSE_CY = 40  # body centre y (cell centre 32, pushed down to free tail room)
ELLIPSE_A = 20.0  # body semi-major along the tail axis
TAIL_TIP = 4  # tail tip y, cell-local
SPINE_W = 1  # tail half-width unit: the tail starts at SPINE_W*3 px
SAT_SPREAD = 0.7  # satellite sigma as fraction of semi-minor b
SAT_EXTENT = 1.25  # outermost satellite offset as fraction of b
SAT_CLAMP = 28.0  # max lateral offset from the tail axis, px

# --- SDF rendering controls -------------------------------------------------
EDGE_SOFT = 1.6  # px of alpha ramp across the shape boundary
THICK_BASE = 0.80  # interior alpha floor
THICK_AMP = 0.19  # interior alpha modulation (keeps alpha>=250 rare)
THICK_SCALE = 4.0  # thickness-noise wavelength, px
BODY_WOBBLE = 0.17  # blob radius wobble amplitude
POOL_WOBBLE = 0.12
SAT_WOBBLE = 0.22
TAIL_SAMPLES = 28  # metaball discs along the tail bezier
RUN_HALF_W = 3.0  # drip run strip nominal half-width, px
RUN_WOBBLE = 2.2  # run side wobble amplitude, px

# --- material --------------------------------------------------------------
# The palette stays recognisable: RIM is DARK (edge pooling), CORE is BASE
# lifted toward EDGE (wet film), SHEEN is EDGE brightened and desaturated.
RIM = DARK
CORE = (
    int(round(BASE[0] + (EDGE[0] - BASE[0]) * 0.35)),
    int(round(BASE[1] + (EDGE[1] - BASE[1]) * 0.35)),
    int(round(BASE[2] + (EDGE[2] - BASE[2]) * 0.35)),
)
SHEEN = (
    min(255, int(round(EDGE[0] * 1.66))),
    min(255, int(round(EDGE[1] * 5.4))),
    min(255, int(round(EDGE[2] * 6.0))),
)
RIM_WIDTH = 7.0  # px of rim->core colour ramp
SPEC_SCALE = 0.40  # specular radius as a fraction of the core radius
SPEC_OFFSET = 0.34  # specular offset as a fraction of the core radius
SPEC_FALLOFF = 2.2  # gaussian tightness of the specular blob
SPEC_MIN_R = 2.5  # floor so the narrowest band still gets a highlight

SEED = 20261016

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PATH = os.path.join(
    REPO_ROOT, "src/main/resources/assets/gore/textures/decal/blood_atlas.png"
)


# --- deterministic value noise --------------------------------------------


def _hash2(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFFFFFF) / 4294967295.0


def value_noise(x, y, seed):
    """Bilinear value noise in [0,1] from a integer lattice hash."""
    ix = math.floor(x)
    iy = math.floor(y)
    fx = x - ix
    fy = y - iy
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)
    a = _hash2(ix, iy, seed)
    b = _hash2(ix + 1, iy, seed)
    c = _hash2(ix, iy + 1, seed)
    d = _hash2(ix + 1, iy + 1, seed)
    return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy


def fbm(x, y, seed, octaves=3):
    total = 0.0
    amp = 0.5
    freq = 1.0
    norm = 0.0
    for o in range(octaves):
        total += amp * value_noise(x * freq, y * freq, seed + o * 7919)
        norm += amp
        amp *= 0.5
        freq *= 2.0
    return total / norm


def _angular_wobble(ang, seed, octaves=3):
    n = 0.0
    a = 1.0
    f = 3.0
    norm = 0.0
    for o in range(octaves):
        n += a * math.sin(f * ang + seed * 0.71 + o * 2.39)
        norm += a
        a *= 0.55
        f *= 2.0
    return n / norm


def _smoothstep(e0, e1, x):
    if e0 == e1:
        return 0.0 if x < e0 else 1.0
    t = (x - e0) / (e1 - e0)
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    return t * t * (3.0 - 2.0 * t)


# --- signed-distance primitives -------------------------------------------


def sdf_blob(px, py, cx, cy, rx, ry, seed, wobble):
    """SDF of an ellipse with noise-modulated radius; negative inside, px."""
    if rx <= 0.0 or ry <= 0.0:
        return 1e9
    dx = (px - cx) / rx
    dy = (py - cy) / ry
    r = math.hypot(dx, dy)
    if r < 1e-9:
        return -min(rx, ry)
    ang = math.atan2(dy, dx)
    rr = 1.0 + wobble * _angular_wobble(ang, seed)
    return (r - rr) * min(rx, ry)


def sdf_chain(px, py, samples):
    """Union of discs (metaball chain): min over samples of (dist - radius)."""
    best = 1e9
    for (qx, qy), w in samples:
        d = math.hypot(px - qx, py - qy) - w
        if d < best:
            best = d
    return best


def sdf_at(px, py, prims):
    best = 1e9
    for p in prims:
        d = (
            sdf_blob(px, py, p[1], p[2], p[3], p[4], p[5], p[6])
            if p[0] == 0
            else sdf_chain(px, py, p[1])
        )
        if d < best:
            best = d
    return best


def _bezier(p0, p1, p2, p3, t):
    mt = 1.0 - t
    a = mt * mt * mt
    b = 3.0 * mt * mt * t
    c = 3.0 * mt * t * t
    d = t * t * t
    return (
        a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0],
        a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1],
    )


def tapered_tail(seed, p0, p1, p2, p3, start_w):
    """Metaball chain along a cubic bezier, width shrinking to ~0 with noise."""
    samples = []
    for i in range(TAIL_SAMPLES):
        t = i / (TAIL_SAMPLES - 1.0)
        x, y = _bezier(p0, p1, p2, p3, t)
        # Taper hard: a linear fade leaves the tail as wide as the narrowest
        # body, which reads as a bar. (1-t)^1.5 keeps the tip hairline.
        w = (
            start_w
            * ((1.0 - t) ** 1.5)
            * (0.72 + 0.55 * value_noise(t * 6.0, 0.5, seed))
        )
        samples.append(((x, y), max(w, 0.28)))
    return samples


# --- rasteriser -----------------------------------------------------------


def put(buf, x, y, rgba, clip):
    """Write one pixel if inside the clip rect (inclusive)."""
    x0, y0, x1, y1 = clip
    if x0 <= x <= x1 and y0 <= y <= y1:
        o = (y * SIZE + x) * 4
        buf[o] = rgba[0]
        buf[o + 1] = rgba[1]
        buf[o + 2] = rgba[2]
        buf[o + 3] = rgba[3]


def spec_at(px, py, specs):
    """Wet specular: gaussian blob per highlight, 0 where there is none."""
    best = 0.0
    for sx, sy, sr, strength in specs:
        if sr <= 0.0:
            continue
        d = math.hypot(px - sx, py - sy) / sr
        if d >= 2.5:
            continue
        v = math.exp(-d * d * SPEC_FALLOFF) * strength
        if v > best:
            best = v
    return best


def _material(t, thick, spec):
    """Rim pooling -> wet core -> specular highlight.

    t is depth into the shape (0 at the rim, 1 well inside); thick is the
    thickness-noise term; spec is the wet-highlight strength 0..1.
    """
    u = _smoothstep(0.0, 0.85, t)
    r = RIM[0] + (CORE[0] - RIM[0]) * u
    g = RIM[1] + (CORE[1] - RIM[1]) * u
    b = RIM[2] + (CORE[2] - RIM[2]) * u

    # Thicker blood still darkens a little, but the core stays readable.
    dark = (thick - THICK_BASE) / THICK_AMP
    dark = 0.0 if dark < 0.0 else (1.0 if dark > 1.0 else dark)
    k = 0.18 * dark * (1.0 - 0.5 * u)
    r += (DARK[0] - r) * k
    g += (DARK[1] - g) * k
    b += (DARK[2] - b) * k

    if spec > 0.0:
        r += (SHEEN[0] - r) * spec
        g += (SHEEN[1] - g) * spec
        b += (SHEEN[2] - b) * spec

    return (int(round(r)), int(round(g)), int(round(b)))


def render_shape(buf, clip, prims, seed, specs=()):
    """Rasterise an SDF union with a soft alpha ramp and a material pass."""
    x0, y0, x1, y1 = clip
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d = sdf_at(x + 0.5, y + 0.5, prims)
            if d > EDGE_SOFT:
                continue
            cov = _smoothstep(EDGE_SOFT, -EDGE_SOFT, d)
            if cov <= 0.004:
                continue
            thick = THICK_BASE + THICK_AMP * fbm(x / THICK_SCALE, y / THICK_SCALE, seed)
            alpha = cov * thick
            if alpha <= 0.004:
                continue
            t = -d / RIM_WIDTH
            t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
            r, g, b = _material(t, thick, spec_at(x + 0.5, y + 0.5, specs))
            a = int(round(255.0 * min(1.0, alpha)))
            put(buf, x, y, (r, g, b, a), clip)


# --- cell art -------------------------------------------------------------


def draw_impact(buf, cell_x, cell_y, elong, dens, rng):
    """One impact splat: noise-modulated body, tapered tail, satellite blobs."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    cx = cell_x + TAIL_AXIS
    cy = cell_y + ELLIPSE_CY
    a = ELLIPSE_A
    b = a * ELONGATION[elong]
    seed = (cell_x * 131 + cell_y * 17 + elong * 97 + dens * 13) & 0xFFFF

    prims = [(0, cx, cy, b, a, seed, BODY_WOBBLE)]
    # Wet highlight, off-centre toward the light (upper left of the body).
    specs = [
        (
            cx - SPEC_OFFSET * b,
            cy - SPEC_OFFSET * a * 0.55,
            max(SPEC_MIN_R, SPEC_SCALE * b),
            1.0,
        )
    ]

    tip_y = cell_y + TAIL_TIP
    p0 = (cx + rng.uniform(-1.0, 1.0), cy - a * 0.15)
    p1 = (cx + rng.uniform(-2.5, 2.5), cy - a * 0.85)
    p2 = (cx + rng.uniform(-3.0, 3.0), tip_y + a * 0.35)
    p3 = (cx + rng.uniform(-2.0, 2.0), tip_y)
    prims.append((1, tapered_tail(seed, p0, p1, p2, p3, SPINE_W * 3.0)))

    n = SAT_COUNTS[dens]
    if n:
        zs = [rng.gauss(0.0, SAT_SPREAD) for _ in range(n)]
        peak = max(abs(z) for z in zs) or 1.0
        extent = min(b * SAT_EXTENT, SAT_CLAMP)
        for i, z in enumerate(zs):
            r = float(rng.choice(SAT_RADII[dens]))
            sx = cx + z / peak * extent
            sy = rng.uniform(cell_y + r, tip_y + 2.0)
            sx = max(cell_x + r, min(cell_x + CELL - 1 - r, sx))
            prims.append((0, sx, sy, r * 1.25, r * 1.05, seed + i * 37, SAT_WOBBLE))
            micro = [
                ((sx + rng.uniform(-0.6, 0.6), sy - k * 1.6), 0.9 - 0.25 * k)
                for k in (1, 2, 3)
            ]
            prims.append((1, micro))

    render_shape(buf, clip, prims, seed, specs)


def draw_pool(buf, cell_x, cell_y, radius, seed):
    """Floor pool: a noise-modulated blob with no tail."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    prims = [
        (0, cell_x + 31.5, cell_y + 31.5, radius, radius * 0.92, seed, POOL_WOBBLE)
    ]
    specs = [
        (
            cell_x + 31.5 - SPEC_OFFSET * radius,
            cell_y + 31.5 - SPEC_OFFSET * radius * 0.92,
            max(SPEC_MIN_R, SPEC_SCALE * radius),
            1.0,
        )
    ]
    render_shape(buf, clip, prims, seed, specs)


def draw_run(buf, cell_x, cell_y, seed):
    """Drip run strip: full cell height, wobbly soft-edged sides, dark rim."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    cx = cell_x + 31.5
    prims = []
    samples = []
    for y in range(cell_y - 1, cell_y + CELL + 1):
        # A film of blood: thickness varies along the run and the two sides
        # wobble independently, so it is never a constant-width bar.
        half = RUN_HALF_W + 1.1 * value_noise(y / 11.0, 0.5, seed + 11)
        wob = RUN_WOBBLE * (value_noise(y / 7.0, 0.5, seed) - 0.5)
        samples.append(((cx + wob, y + 0.5), half))
    prims.append((1, samples))
    render_shape(buf, clip, prims, seed)


def draw_bead(buf, cell_x, cell_y, seed):
    """Drip leading bead: a noise-modulated blob centred in the cell."""
    clip = (cell_x, cell_y, cell_x + CELL - 1, cell_y + CELL - 1)
    prims = [(0, cell_x + 31.5, cell_y + 31.5, 9.0, 8.0, seed, BODY_WOBBLE)]
    specs = [
        (
            cell_x + 31.5 - SPEC_OFFSET * 8.0,
            cell_y + 31.5 - SPEC_OFFSET * 4.4,
            max(SPEC_MIN_R, SPEC_SCALE * 8.0),
            1.0,
        )
    ]
    render_shape(buf, clip, prims, seed, specs)


def draw_wound(buf, cell_x, cell_y, seed):
    """Wound laceration: a jagged dark cut with raised (lighter) edges.

    Not a splat — a gash. Dark in the centre (the cut itself), raised torn
    skin on either side, with a little blood pooling at the ends. Variant 16+.
    """
    clip = (cell_x, cell_y, cell_x + CELL, cell_y + CELL)
    # Jagged cut running roughly horizontally, with vertical jitter.
    cut_samples = []
    edge_top = []
    edge_bot = []
    for i in range(10):
        t = i / 9.0
        x = cell_x + 6 + t * 52
        y = cell_y + 32 + (value_noise(t * 4.0, 0.3, seed) - 0.5) * 8
        w = 2.5 + 1.0 * (1.0 - abs(2.0 * t - 1.0))
        cut_samples.append(((x, y), w))
        edge_top.append(((x, y - 5.0), 1.2))
        edge_bot.append(((x, y + 5.0), 1.2))
    prims = [(1, cut_samples), (1, edge_top), (1, edge_bot)]

    cx0, cy0, cw, ch = clip
    for yy in range(cy0, cy0 + ch):
        for xx in range(cx0, cx0 + cw):
            d = sdf_at(xx + 0.5, yy + 0.5, prims)
            if d > EDGE_SOFT:
                continue
            cov = _smoothstep(EDGE_SOFT, -EDGE_SOFT, d)
            thick = THICK_BASE + THICK_AMP * fbm(
                xx / THICK_SCALE, yy / THICK_SCALE, seed
            )
            alpha = cov * thick
            # t=1 at centre (darkest), t=0 at edge (raised).
            t = min(1.0, max(0.0, -d / RIM_WIDTH))
            u = _smoothstep(0.0, 0.6, t)
            r = WOUND_EDGE[0] + (WOUND_DARK[0] - WOUND_EDGE[0]) * u
            g = WOUND_EDGE[1] + (WOUND_DARK[1] - WOUND_EDGE[1]) * u
            b = WOUND_EDGE[2] + (WOUND_DARK[2] - WOUND_EDGE[2]) * u
            k = 0.30 * (1.0 - u)
            r += (WOUND_RAISED[0] - r) * k
            g += (WOUND_RAISED[1] - g) * k
            b += (WOUND_RAISED[2] - b) * k
            rgb = (int(round(r)), int(round(g)), int(round(b)))
            a = round(255 * min(1.0, alpha))
            put(buf, xx, yy, (rgb[0], rgb[1], rgb[2], a), (cx0, cy0, cw, ch))


# --- PNG output -----------------------------------------------------------


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
    draw_pool(buf, (12 % GRID) * CELL, (12 // GRID) * CELL, 26.0, SEED + 200)
    draw_pool(buf, (13 % GRID) * CELL, (13 // GRID) * CELL, 17.0, SEED + 201)

    # drip: full-height run strip (14) and its leading bead (15)
    draw_run(buf, (14 % GRID) * CELL, (14 // GRID) * CELL, SEED + 300)
    draw_bead(buf, (15 % GRID) * CELL, (15 // GRID) * CELL, SEED + 301)

    # wound: a dark laceration with raised edges (cell 16)
    draw_wound(buf, (16 % GRID) * CELL, (16 // GRID) * CELL, SEED + 400)

    write_png(OUT_PATH, SIZE, SIZE, buf)
    opaque = sum(1 for i in range(3, len(buf), 4) if buf[i])
    print(f"wrote {OUT_PATH} ({SIZE}x{SIZE} RGBA, {opaque} opaque px)")


if __name__ == "__main__":
    main()
