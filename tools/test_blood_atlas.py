#!/usr/bin/env python3
"""Assert the blood atlas reads as organic blood, not a hard-edged glyph.

Stdlib only (reuses pixelstat.load_png). Run: python3 tools/test_blood_atlas.py

Each assertion targets one way a splat can look fake:

  cells_resolve        every art cell is actually drawn (not empty, not a slab)
  reserved_cells_empty cells 16-63 stay transparent (the 8x8 layout contract)
  edge_alpha_falloff   the outer 10% of the shape's depth is far more
                       transparent than the inner 30% core -- a hard-edged
                       primitive has no falloff and fails this
  no_straight_spine    no column of an impact cell carries a long flat opaque
                       plateau -- that plateau is what reads as "a line"
  tail_tapers          the tail is narrow well before the tip, not a bar
  legacy_meanings      cell 14 is still a full-height run, cell 15 one bead
  rim_darker_than_core the rim pools darker than the core (edge pooling)
  colour_spread        per-cell R varies (no flat fills)
  sheen_present        a wet specular highlight is baked in

Depth is measured inward from the shape boundary (the outermost shape
pixels are depth ~0), so "outer 10% ring" = depth <= 0.10*dmax and
"inner 30% core" = depth >= 0.70*dmax.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pixelstat import load_png  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ATLAS = os.path.join(
    REPO_ROOT, "src/main/resources/assets/gore/textures/decal/blood_atlas.png"
)

SIZE = 512
CELL = 64
GRID = 8
ART_CELLS = 17

OPAQUE = 128  # "has blood here"
FULLY_OPAQUE = 250  # "effectively flat/opaque" for the plateau check
MAX_PLATEAU = 8  # longest allowed run of FULLY_OPAQUE in one column
COLOUR_FLOOR = 32  # ignore the near-transparent fringe when averaging colour


def cell_pixels(w, channels, out, cell):
    x0 = (cell % GRID) * CELL
    y0 = (cell // GRID) * CELL
    rows = []
    for y in range(y0, y0 + CELL):
        row = []
        for x in range(x0, x0 + CELL):
            o = (y * w + x) * channels
            row.append((out[o], out[o + 1], out[o + 2], out[o + 3]))
        rows.append(row)
    return rows


def depth_map(rows):
    """Inward distance to the shape boundary, in pixels."""
    n = len(rows)
    mask = [[1 if rows[y][x][3] >= 1 else 0 for x in range(n)] for y in range(n)]
    # Distance to the nearest transparent pixel: transparent = 0, shape = INF.
    d = [[0 if not mask[y][x] else n * n for x in range(n)] for y in range(n)]
    for y in range(n):
        for x in range(n):
            if not mask[y][x]:
                continue
            best = d[y][x]
            if y > 0:
                best = min(best, d[y - 1][x] + 1)
            if x > 0:
                best = min(best, d[y][x - 1] + 1)
            if y > 0 and x > 0:
                best = min(best, d[y - 1][x - 1] + 1)
            if y > 0 and x < n - 1:
                best = min(best, d[y - 1][x + 1] + 1)
            d[y][x] = best
    for y in range(n - 1, -1, -1):
        for x in range(n - 1, -1, -1):
            if not mask[y][x]:
                continue
            best = d[y][x]
            if y < n - 1:
                best = min(best, d[y + 1][x] + 1)
            if x < n - 1:
                best = min(best, d[y][x + 1] + 1)
            if y < n - 1 and x < n - 1:
                best = min(best, d[y + 1][x + 1] + 1)
            if y < n - 1 and x > 0:
                best = min(best, d[y + 1][x - 1] + 1)
            d[y][x] = best
    return d, mask


def rings(rows):
    """(ring_pixels, core_pixels) as RGBA tuples, by depth from the boundary."""
    d, mask = depth_map(rows)
    live = [d[y][x] for y in range(CELL) for x in range(CELL) if mask[y][x]]
    if not live:
        return [], []
    dmax = max(live)
    if dmax < 3:
        return [], []
    ring = [
        rows[y][x]
        for y in range(CELL)
        for x in range(CELL)
        if mask[y][x] and d[y][x] <= max(1, int(0.10 * dmax))
    ]
    core = [
        rows[y][x]
        for y in range(CELL)
        for x in range(CELL)
        if mask[y][x] and d[y][x] >= int(0.70 * dmax)
    ]
    return ring, core


def run_width_at(rows, row, axis_x):
    """Length of the opaque run at `row` that touches/nearest `axis_x`."""
    n = len(rows)
    if not 0 <= row < n:
        return 0
    runs = []
    x = 0
    while x < n:
        if rows[row][x][3] >= OPAQUE:
            start = x
            while x < n and rows[row][x][3] >= OPAQUE:
                x += 1
            runs.append((start, x - 1))
        else:
            x += 1
    if not runs:
        return 0
    best = min(runs, key=lambda r: min(abs(r[0] - axis_x), abs(r[1] - axis_x)))
    return best[1] - best[0] + 1


def connected_components(rows, threshold=OPAQUE):
    n = len(rows)
    seen = [[False] * n for _ in range(n)]
    comps = 0
    for y in range(n):
        for x in range(n):
            if rows[y][x][3] < threshold or seen[y][x]:
                continue
            comps += 1
            stack = [(x, y)]
            seen[y][x] = True
            while stack:
                cx, cy = stack.pop()
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < n and 0 <= ny < n and not seen[ny][nx]:
                        if rows[ny][nx][3] >= threshold:
                            seen[ny][nx] = True
                            stack.append((nx, ny))
    return comps


# --- checks ---------------------------------------------------------------


def check_cells_resolve(cells):
    bad = []
    for cell in range(ART_CELLS):
        frac = sum(1 for r in cells[cell] for p in r if p[3] >= OPAQUE) / float(
            CELL * CELL
        )
        if not 0.05 <= frac <= 0.75:
            bad.append(f"cell {cell} opaque fraction {frac:.3f}")
    return bad


def check_reserved_cells_empty(w, channels, out):
    bad = []
    for cell in range(ART_CELLS, GRID * GRID):
        rows = cell_pixels(w, channels, out, cell)
        if any(p[3] >= 1 for r in rows for p in r):
            bad.append(f"cell {cell} is not empty")
    return bad


def check_edge_alpha_falloff(cells):
    bad = []
    for cell in list(range(14)) + [15]:
        ring, core = rings(cells[cell])
        if len(core) < 8 or len(ring) < 8:
            bad.append(f"cell {cell} core/ring too small to judge falloff")
            continue
        mean_ring = sum(p[3] for p in ring) / float(len(ring))
        mean_core = sum(p[3] for p in core) / float(len(core))
        if not mean_ring < 0.5 * mean_core:
            bad.append(
                f"cell {cell} edge alpha {mean_ring:.1f} not < 0.5*core {mean_core:.1f}"
            )
    return bad


def check_no_straight_spine(cells):
    bad = []
    for cell in range(12):
        rows = cells[cell]
        for x in range(CELL):
            run = 0
            for y in range(CELL):
                if rows[y][x][3] >= FULLY_OPAQUE:
                    run += 1
                    if run > MAX_PLATEAU:
                        bad.append(f"cell {cell} col {x} has a {run}px opaque plateau")
                        break
                else:
                    run = 0
            if bad and bad[-1].startswith(f"cell {cell} col {x}"):
                break
    return bad


def check_tail_tapers(cells):
    bad = []
    for cell in range(3, 12):  # elongation bands 1..3
        rows = cells[cell]
        axis = 32
        core = max(run_width_at(rows, y, axis) for y in range(30, 51))
        at_80 = run_width_at(rows, 11, axis)
        if core <= 0:
            bad.append(f"cell {cell} has no measurable core width")
            continue
        if not at_80 < 0.4 * core:
            bad.append(
                f"cell {cell} tail width {at_80} not < 0.4*core {core} ({0.4 * core:.1f})"
            )
    return bad


def check_legacy_meanings(cells):
    bad = []
    run = cells[14]
    fracs = []
    for x in range(28, 36):
        fracs.append(
            sum(1 for y in range(CELL) if run[y][x][3] >= OPAQUE) / float(CELL)
        )
    if not sum(fracs) / len(fracs) > 0.5:
        bad.append(
            f"cell 14 central-column coverage {sum(fracs) / len(fracs):.3f} not > 0.5"
        )

    bead = cells[15]
    comps = connected_components(bead)
    area = sum(1 for r in bead for p in r if p[3] >= OPAQUE)
    if comps != 1:
        bad.append(f"cell 15 has {comps} connected components, expected 1")
    if not area < 0.25 * CELL * CELL:
        bad.append(f"cell 15 area {area} not < {0.25 * CELL * CELL:.0f}")
    return bad


def check_rim_darker_than_core(cells):
    bad = []
    for cell in range(12):
        ring, core = rings(cells[cell])
        ring = [p for p in ring if p[3] >= COLOUR_FLOOR]
        core = [p for p in core if p[3] >= COLOUR_FLOOR]
        if len(core) < 8 or len(ring) < 8:
            bad.append(f"cell {cell} core/ring too small to judge colour")
            continue
        mean_ring = sum(p[0] for p in ring) / float(len(ring))
        mean_core = sum(p[0] for p in core) / float(len(core))
        if not mean_ring < 0.85 * mean_core:
            bad.append(
                f"cell {cell} rim R {mean_ring:.1f} not < 0.85*core R {mean_core:.1f}"
            )
    return bad


def check_colour_spread(cells):
    bad = []
    for cell in range(12):
        rs = [p[0] for r in cells[cell] for p in r if p[3] >= OPAQUE]
        if len(rs) < 16:
            bad.append(f"cell {cell} too few opaque pixels to judge spread")
            continue
        mean = sum(rs) / float(len(rs))
        var = sum((v - mean) ** 2 for v in rs) / float(len(rs))
        if not math.sqrt(var) > 4.0:
            bad.append(f"cell {cell} R std-dev {math.sqrt(var):.2f} not > 4.0")
    return bad


def check_sheen_present(cells):
    bad = []
    for cell in range(12):
        hit = any(p[0] > 200 and p[3] > 200 for r in cells[cell] for p in r)
        if not hit:
            bad.append(f"cell {cell} has no sheen pixel (R>200 and A>200)")
    return bad


def main():
    w, h, channels, out = load_png(ATLAS)
    if (w, h) != (SIZE, SIZE) or channels != 4:
        print(f"FAIL atlas is {w}x{h} ch={channels}, expected {SIZE}x{SIZE} RGBA")
        return 1

    cells = [cell_pixels(w, channels, out, i) for i in range(GRID * GRID)]

    checks = [
        ("cells_resolve", check_cells_resolve(cells)),
        ("reserved_cells_empty", check_reserved_cells_empty(w, channels, out)),
        ("edge_alpha_falloff", check_edge_alpha_falloff(cells)),
        ("no_straight_spine", check_no_straight_spine(cells)),
        ("tail_tapers", check_tail_tapers(cells)),
        ("legacy_meanings", check_legacy_meanings(cells)),
        ("rim_darker_than_core", check_rim_darker_than_core(cells)),
        ("colour_spread", check_colour_spread(cells)),
        ("sheen_present", check_sheen_present(cells)),
    ]

    failed = False
    for name, bad in checks:
        if bad:
            failed = True
            print(f"FAIL {name}:")
            for line in bad:
                print(f"  {line}")
        else:
            print(f"ok   {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
