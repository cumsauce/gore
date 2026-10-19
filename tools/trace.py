#!/usr/bin/env python3
"""Per-tick blood particle trace analysis.

    python3 tools/trace.py <trace.jsonl> [out dir] [--max-speed 1.5] [--type flight]

Reads the JSON lines a client writes with ``-Dgore.trace=<file>`` (the client
game test writes ``~/gore-run/clientGameTest/gore-trace.jsonl``). Each line is
one particle at the start of one tick, so ``xo`` must equal the previous
line's ``x`` for the same id. It prints a per-type summary and flags:

* ``xo``      interpolation origin not following x (the frame is drawn between
              the wrong two points, so the particle smears or flickers),
* ``speed``   position step or velocity above ``--max-speed`` blocks/tick,
* ``age``     ticks skipped or repeated,
* ``value``   NaN/inf, alpha outside 0..1, size <= 0.

and writes ``<out>/trace-side.png`` (x across, y up) and ``trace-top.png``
(x across, z down) with every path, coloured by type and flagged points in
yellow. Standard library only; PNG writing comes from tools/seq.py.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seq import write_png  # noqa: E402

COLOURS = {
    "flight": (230, 40, 40),
    "drop": (240, 120, 60),
    "micro": (200, 120, 200),
    "mist": (120, 160, 240),
    "jet": (80, 220, 120),
}
FLAG = (255, 230, 0)


def load(path, only):
    tracks = {}
    for n, line in enumerate(open(path), 1):
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except ValueError:
            print("line %d: not JSON (truncated write?)" % n)
            continue
        if only and rec["type"] not in only:
            continue
        tracks.setdefault(rec["id"], []).append(rec)
    return tracks


def check(track, max_speed, eps=1e-4):
    flags = []
    for i, r in enumerate(track):
        vals = [r[k] for k in ("x", "y", "z", "xo", "yo", "zo", "vx", "vy", "vz", "alpha", "size")]
        if any(math.isnan(v) or math.isinf(v) for v in vals):
            flags.append((i, "value", "NaN/inf"))
            continue
        if not 0.0 <= r["alpha"] <= 1.0 or r["size"] <= 0.0:
            flags.append((i, "value", "alpha %.3f size %.4f" % (r["alpha"], r["size"])))
        v = math.sqrt(r["vx"] ** 2 + r["vy"] ** 2 + r["vz"] ** 2)
        if v > max_speed:
            flags.append((i, "speed", "velocity %.3f b/t" % v))
        if i == 0:
            continue
        p = track[i - 1]
        if r["age"] != p["age"] + 1:
            flags.append((i, "age", "age %d after %d" % (r["age"], p["age"])))
        off = max(abs(r["xo"] - p["x"]), abs(r["yo"] - p["y"]), abs(r["zo"] - p["z"]))
        if off > eps:
            flags.append((i, "xo", "xo/yo/zo %.4f from previous x/y/z" % off))
        step = math.dist((r["x"], r["y"], r["z"]), (p["x"], p["y"], p["z"]))
        if step > max_speed:
            flags.append((i, "speed", "moved %.3f in one tick" % step))
    return flags


def plot(tracks, flagged, out, axes, name, size=900, pad=20):
    a, b, flip = axes
    pts = [(r[a], r[b]) for t in tracks.values() for r in t]
    if not pts:
        return
    lo_a, hi_a = min(p[0] for p in pts), max(p[0] for p in pts)
    lo_b, hi_b = min(p[1] for p in pts), max(p[1] for p in pts)
    span = max(hi_a - lo_a, hi_b - lo_b, 1e-3)
    k = (size - 2 * pad) / span
    w = int((hi_a - lo_a) * k) + 2 * pad
    h = int((hi_b - lo_b) * k) + 2 * pad
    img = bytearray(b"\x1c" * (w * h * 3))

    def to_px(r):
        x = pad + (r[a] - lo_a) * k
        y = pad + ((hi_b - r[b]) if flip else (r[b] - lo_b)) * k
        return x, y

    def put(x, y, c, rad=0):
        for dy in range(-rad, rad + 1):
            for dx in range(-rad, rad + 1):
                xi, yi = int(x) + dx, int(y) + dy
                if 0 <= xi < w and 0 <= yi < h:
                    img[(yi * w + xi) * 3:(yi * w + xi) * 3 + 3] = bytes(c)

    # Block grid, one line per whole block.
    for g in range(math.floor(lo_a), math.ceil(hi_a) + 1):
        x = pad + (g - lo_a) * k
        for y in range(h):
            put(x, y, (50, 50, 50))
    for g in range(math.floor(lo_b), math.ceil(hi_b) + 1):
        y = pad + ((hi_b - g) if flip else (g - lo_b)) * k
        for x in range(w):
            put(x, y, (50, 50, 50))

    for tid, track in tracks.items():
        c = COLOURS.get(track[0]["type"], (200, 200, 200))
        for i in range(1, len(track)):
            x0, y0 = to_px(track[i - 1])
            x1, y1 = to_px(track[i])
            n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
            for s in range(n + 1):
                put(x0 + (x1 - x0) * s / n, y0 + (y1 - y0) * s / n, c)
        for i, r in enumerate(track):
            put(*to_px(r), FLAG if (tid, i) in flagged else c, 2 if (tid, i) in flagged else 0)
    write_png(os.path.join(out, name), w, h, bytes(img))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("trace")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--max-speed", type=float, default=1.5, help="blocks/tick (default 1.5)")
    ap.add_argument("--type", action="append", help="only these particle types (repeatable)")
    ap.add_argument("--show", type=int, default=20, help="flags to print (default 20)")
    args = ap.parse_args()
    out = args.out or os.path.dirname(os.path.abspath(args.trace))
    os.makedirs(out, exist_ok=True)

    tracks = load(args.trace, args.type)
    if not tracks:
        sys.exit("no particles in %s" % args.trace)

    by_type = {}
    flagged = set()
    all_flags = []
    for tid, track in tracks.items():
        track.sort(key=lambda r: (r["t"], r["age"]))
        s = by_type.setdefault(track[0]["type"], {"n": 0, "ticks": 0, "life": [], "vmax": 0.0, "flags": {}})
        s["n"] += 1
        s["ticks"] += len(track)
        s["life"].append(len(track))
        s["vmax"] = max([s["vmax"]] + [math.sqrt(r["vx"] ** 2 + r["vy"] ** 2 + r["vz"] ** 2) for r in track])
        for i, kind, msg in check(track, args.max_speed):
            flagged.add((tid, i))
            s["flags"][kind] = s["flags"].get(kind, 0) + 1
            all_flags.append((track[i]["t"], tid, track[i]["type"], track[i]["age"], kind, msg))

    print("%-7s %6s %7s %9s %8s  %s" % ("type", "count", "ticks", "life avg", "v max", "flags"))
    for kind, s in sorted(by_type.items()):
        flags = ", ".join("%s %d" % f for f in sorted(s["flags"].items())) or "-"
        print("%-7s %6d %7d %9.1f %8.3f  %s" % (kind, s["n"], s["ticks"], sum(s["life"]) / len(s["life"]), s["vmax"], flags))

    for t, tid, kind, age, what, msg in sorted(all_flags)[:args.show]:
        print("  t=%d id=%d %s age %d: %s: %s" % (t, tid, kind, age, what, msg))
    if len(all_flags) > args.show:
        print("  ... %d more" % (len(all_flags) - args.show))

    plot(tracks, flagged, out, ("x", "y", True), "trace-side.png")
    plot(tracks, flagged, out, ("x", "z", False), "trace-top.png")
    print("%d particles, %d flags -> %s/trace-{side,top}.png" % (len(tracks), len(all_flags), out))
    sys.exit(1 if all_flags else 0)


if __name__ == "__main__":
    main()
