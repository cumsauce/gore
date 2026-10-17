#!/usr/bin/env python3
"""Assert the blood atlas reads as organic blood, not a hard-edged glyph.

Stdlib only (reuses pixelstat.load_png). Run: python3 tools/test_blood_atlas.py

Five assertions, each targeting one way a splat can look fake:

  cells_resolve      every art cell is actually drawn (not empty, not a slab)
  reserved_cells_empty  cells 16-63 stay transparent (the 8x8 layout contract)
  edge_alpha_falloff the outer 10% of the shape's depth is far more
                     transparent than the inner 30% core -- a hard-edged
                     primitive has no falloff and fails this
  no_straight_spine  no column of an impact cell carries a long flat opaque
                     plateau -- that plateau is what reads as "a line"
  tail_tapers        the tail is narrow well before the tip, not a bar
  legacy_meanings    cell 14 is still a full-height run, cell 15 one bead

Depth is measured inward from the shape boundary (the outermost shape
pixels are depth ~0), so "outer 10% ring" = depth <= 0.10*dmax and
"inner 30% core" = depth >= 0.70*dmax.
"""

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
ART_CELLS = 16

OPAQUE = 128  # "has blood here"
FULLY_OPAQUE = 250  # "effectively flat/opaque" for the plateau check
MAX_PLATEAU = 8  # longest allowed run of FULLY_OPAQUE in one column


def cell_pixels(w, channels, out, cell):
    x0 = (cell % GRID) * CELL
    y0 = (cell // GRID) * CELL
    rows = []
    for y in range(y0, y0 + CELL):
        row = []
        for x in range(x0, x0 + CELL):
            o = (y * w + x) * channels
            row.append(out[o + 3])
        rows.append(row)
    return rows


def depth_map(rows):
    """Inward distance to the shape boundary, in pixels."""
    n = len(rows)
    mask = [[1 if rows[y][x] >= 1 else 0 for x in range(n)] for y in range(n)]
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


def run_width_at(rows, row, axis_x):
    """Length of the opaque run at `row` that touches/nearest `axis_x`."""
    n = len(rows)
    if not 0 <= row < n:
        return 0
    runs = []
    x = 0
    while x < n:
        if rows[row][x] >= OPAQUE:
            start = x
            while x < n and rows[row][x] >= OPAQUE:
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
            if rows[y][x] < threshold or seen[y][x]:
                continue
            comps += 1
            stack = [(x, y)]
            seen[y][x] = True
            while stack:
                cx, cy = stack.pop()
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < n and 0 <= ny < n and not seen[ny][nx]:
                        if rows[ny][nx] >= threshold:
                            seen[ny][nx] = True
                            stack.append((nx, ny))
    return comps


def check_cells_resolve(cells):
    bad = []
    for cell in range(ART_CELLS):
        rows = cells[cell]
        frac = sum(1 for r in rows for a in r if a >= OPAQUE) / float(CELL * CELL)
        if not 0.05 <= frac <= 0.75:
            bad.append(f"cell {cell} opaque fraction {frac:.3f}")
    return bad


def check_reserved_cells_empty(w, channels, out):
    bad = []
    for cell in range(ART_CELLS, GRID * GRID):
        rows = cell_pixels(w, channels, out, cell)
        if any(a >= 1 for r in rows for a in r):
            bad.append(f"cell {cell} is not empty")
    return bad


def check_edge_alpha_falloff(cells):
    bad = []
    for cell in list(range(14)) + [15]:
        rows = cells[cell]
        d, mask = depth_map(rows)
        dmax = max(d[y][x] for y in range(CELL) for x in range(CELL) if mask[y][x])
        if dmax < 3:
            bad.append(f"cell {cell} too shallow (dmax={dmax}) to judge falloff")
            continue
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
        if len(core) < 8 or len(ring) < 8:
            bad.append(f"cell {cell} core/ring too small to judge falloff")
            continue
        mean_ring = sum(ring) / float(len(ring))
        mean_core = sum(core) / float(len(core))
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
                if rows[y][x] >= FULLY_OPAQUE:
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
        fracs.append(sum(1 for y in range(CELL) if run[y][x] >= OPAQUE) / float(CELL))
    if not sum(fracs) / len(fracs) > 0.5:
        bad.append(
            f"cell 14 central-column coverage {sum(fracs) / len(fracs):.3f} not > 0.5"
        )

    bead = cells[15]
    comps = connected_components(bead)
    area = sum(1 for r in bead for a in r if a >= OPAQUE)
    if comps != 1:
        bad.append(f"cell 15 has {comps} connected components, expected 1")
    if not area < 0.25 * CELL * CELL:
        bad.append(f"cell 15 area {area} not < {0.25 * CELL * CELL:.0f}")
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
