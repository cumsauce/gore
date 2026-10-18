#!/usr/bin/env python3
"""Generate the blood particle sprites (drops and mist).

The atlas generator (gen_blood_atlas.py) draws the lasting stain; this draws the
*fleeting* marks that fly through the air. Both share the same stdlib PNG writer
and noise primitives, and both bake their shape into alpha so nothing depends on a
shader at runtime.

Sprites are 16x16 RGBA. They are deliberately near-neutral in luminance with a soft
alpha falloff and only a whisper of hue jitter: the particle class multiplies them
by its own tint (ParticleLook.look), so a strongly coloured sprite would double-tint
and read muddy. What is baked here is *depth* — bright core, darker rim — so that
once tinted the droplet has a highlight and an edge instead of being a flat disc.

Drops   : 0 round drop, 1 elongated streak, 2 irregular fleck.
Mist    : 0/1/2 soft noise-textured puffs of increasing radius.

Run:  python3 tools/gen_particle_sprites.py
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gen_blood_atlas import fbm, value_noise, write_png  # noqa: E402

SIZE = 16
OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src",
    "main",
    "resources",
    "assets",
    "gore",
    "textures",
    "particle",
)

SEED = 20261016

# Coverage at which a pixel becomes fully opaque. Small on purpose: see _paint.
ALPHA_RAMP = 0.42

# Baked luminance profile: the core of a droplet catches light, the rim sits in shadow.
CORE_LUM = 255
RIM_LUM = 196
# A whisper of warmth in the core and coolness in the rim. Kept small on purpose:
# the particle tint is what decides this reads as blood.
CORE_WARMTH = (1.00, 0.97, 0.95)
RIM_WARMTH = (0.93, 0.90, 0.92)


def _smoothstep(e0, e1, x):
    if e0 == e1:
        return 0.0 if x < e0 else 1.0
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3.0 - 2.0 * t)


def _shade(d, noise):
    """Luminance + hue for a pixel whose normalised boundary distance is d in [0,1]."""
    u = _smoothstep(0.0, 1.0, d)  # 0 at the rim, 1 at the core
    lum = RIM_LUM + (CORE_LUM - RIM_LUM) * u
    # Thin regions scatter more and read cooler; the core is the densest and warmest.
    lum *= 0.94 + 0.12 * noise
    r = lum * (RIM_WARMTH[0] + (CORE_WARMTH[0] - RIM_WARMTH[0]) * u)
    g = lum * (RIM_WARMTH[1] + (CORE_WARMTH[1] - RIM_WARMTH[1]) * u)
    b = lum * (RIM_WARMTH[2] + (CORE_WARMTH[2] - RIM_WARMTH[2]) * u)
    return min(255, int(round(r))), min(255, int(round(g))), min(255, int(round(b)))


def _paint(buf, field, seed):
    """field(x, y) -> signed coverage in roughly [-1, 1]; >0 is inside the shape.

    The alpha ramp is deliberately short: a particle sprite needs a solid core with a
    soft rim, not a gaussian ghost. Coverage >= ALPHA_RAMP is fully opaque.
    """
    for y in range(SIZE):
        for x in range(SIZE):
            cov = field(x + 0.5, y + 0.5)
            if cov <= 0.0:
                continue
            alpha = _smoothstep(0.0, ALPHA_RAMP, min(1.0, cov))
            n = value_noise(x / 3.7, y / 3.7, seed)
            r, g, b = _shade(min(1.0, cov / ALPHA_RAMP), n)
            o = (y * SIZE + x) * 4
            buf[o] = r
            buf[o + 1] = g
            buf[o + 2] = b
            buf[o + 3] = int(round(255.0 * alpha))


def _radial(rx, ry, cx, cy, wobble_seed, wobble):
    def field(px, py):
        dx = (px - cx) / rx
        dy = (py - cy) / ry
        d = math.hypot(dx, dy)
        if wobble > 0.0:
            ang = math.atan2(dy, dx)
            d /= 1.0 + wobble * math.sin(ang * 3.0 + wobble_seed) * 0.5
        # 1.0 at the centre, 0 at the boundary, and a soft ramp that lives outside too.
        return 1.0 - d

    return field


def draw_drop_round(buf):
    c = SIZE / 2.0
    _paint(buf, _radial(SIZE * 0.40, SIZE * 0.40, c, c, 0.0, 0.0), SEED + 1)


def draw_drop_streak(buf):
    c = SIZE / 2.0
    _paint(buf, _radial(SIZE * 0.19, SIZE * 0.48, c, c, 0.0, 0.0), SEED + 2)


def draw_drop_fleck(buf):
    c = SIZE / 2.0
    _paint(buf, _radial(SIZE * 0.34, SIZE * 0.30, c, c, 1.7, 0.30), SEED + 3)


def draw_mist(buf, index):
    """Three puffs of increasing radius and increasing noise, so a SpriteSet's
    setSpriteFromAge walks from a tight burst to a wide cloud."""
    c = SIZE / 2.0
    radius = SIZE * (0.30 + 0.10 * index)
    seed = SEED + 10 + index

    def field(px, py):
        d = math.hypot(px - c, py - c) / radius
        # Value noise breaks the perfect circle; the puff keeps a soft rim either way.
        n = fbm(px / 4.5, py / 4.5, seed, octaves=3)
        d *= 1.0 + (n - 0.5) * (0.45 + 0.20 * index)
        # Scaled up so the core saturates: a puff needs a solid body of pigment, not a
        # gaussian ghost, or it disappears the moment it is composited over stone.
        return (1.0 - d) * 1.55 - 0.12 * (1.0 - n)

    _paint(buf, field, seed)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    drops = [
        ("blood_drop_0", draw_drop_round),
        ("blood_drop_1", draw_drop_streak),
        ("blood_drop_2", draw_drop_fleck),
    ]
    for name, fn in drops:
        buf = bytearray(SIZE * SIZE * 4)
        fn(buf)
        path = os.path.join(OUT_DIR, name + ".png")
        write_png(path, SIZE, SIZE, buf)
        print(
            "wrote %s (%dx%d RGBA, %d opaque px)"
            % (path, SIZE, SIZE, sum(1 for i in range(3, len(buf), 4) if buf[i] >= 128))
        )

    for index in range(3):
        buf = bytearray(SIZE * SIZE * 4)
        draw_mist(buf, index)
        path = os.path.join(OUT_DIR, "blood_mist_%d.png" % index)
        write_png(path, SIZE, SIZE, buf)
        print(
            "wrote %s (%dx%d RGBA, %d opaque px)"
            % (path, SIZE, SIZE, sum(1 for i in range(3, len(buf), 4) if buf[i] >= 128))
        )


if __name__ == "__main__":
    main()
