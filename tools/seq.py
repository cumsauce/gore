#!/usr/bin/env python3
"""Temporal analysis of the game test's frame sequences.

    python3 tools/seq.py <screenshots dir> <scene> [out dir]

For the frames named ``*seq-<scene>-NNN.png`` it writes:

* ``<scene>.apng``   — an animated PNG of the sequence (plays in any browser),
* ``<scene>-sheet.png`` — a contact sheet of every frame, quarter size,
* and prints a per-frame flicker score.

Flicker is what z-fighting looks like over time: a pixel that jumps to a new
colour and straight back (A, B, A) between three consecutive frames. Motion
moves an edge one way and does not come back, so a slowly moving camera
scores near zero on clean geometry, while coplanar quads fighting for depth
score in the hundreds. Only pixels that are blood-coloured in one of the three
frames count, so terrain shimmer does not mask the result.

Standard library only (zlib + struct): no Pillow on the dev machine.
"""
import glob
import os
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, w = 8, [], 0
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and interlace == 0 and ctype in (2, 6), (depth, ctype)
            bpp = 3 if ctype == 2 else 4
        elif kind == b"IDAT":
            idat.append(body)
        pos += 12 + n
    raw = zlib.decompress(b"".join(idat))
    stride = w * bpp
    out = bytearray(w * h * 3)
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        prev = line
        if bpp == 3:
            out[y * w * 3:(y + 1) * w * 3] = line
        else:
            row = out[y * w * 3:(y + 1) * w * 3]
            row[0::3] = line[0::4]
            row[1::3] = line[1::4]
            row[2::3] = line[2::4]
            out[y * w * 3:(y + 1) * w * 3] = row
    return w, h, bytes(out)


def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def rows(w, h, rgb):
    return b"".join(b"\x00" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))


def write_png(path, w, h, rgb):
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(rows(w, h, rgb), 6)))
        f.write(chunk(b"IEND", b""))


def write_apng(path, w, h, frames, delay_ms):
    seq = 0
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"acTL", struct.pack(">II", len(frames), 0)))
        for n, rgb in enumerate(frames):
            f.write(chunk(b"fcTL", struct.pack(">IIIIIHHBB", seq, w, h, 0, 0, delay_ms, 1000, 0, 0)))
            seq += 1
            data = zlib.compress(rows(w, h, rgb), 6)
            if n == 0:
                f.write(chunk(b"IDAT", data))
            else:
                f.write(chunk(b"fdAT", struct.pack(">I", seq) + data))
                seq += 1
        f.write(chunk(b"IEND", b""))


def downscale(w, h, rgb, k):
    ow, oh = w // k, h // k
    out = bytearray(ow * oh * 3)
    for y in range(oh):
        src = (y * k) * w * 3
        for x in range(ow):
            s = src + x * k * 3
            out[(y * ow + x) * 3:(y * ow + x) * 3 + 3] = rgb[s:s + 3]
    return ow, oh, bytes(out)


def is_blood(rgb, i):
    r, g, b = rgb[i], rgb[i + 1], rgb[i + 2]
    return r > 40 and r > 2 * g and r > 2 * b


def flicker(a, b, c, w, h, threshold=40):
    count = 0
    for i in range(0, w * h * 3, 3):
        if not (is_blood(a, i) or is_blood(b, i) or is_blood(c, i)):
            continue
        ab = sum(abs(a[i + k] - b[i + k]) for k in range(3))
        bc = sum(abs(b[i + k] - c[i + k]) for k in range(3))
        ac = sum(abs(a[i + k] - c[i + k]) for k in range(3))
        if ab > threshold and bc > threshold and ac < threshold // 2:
            count += 1
    return count


def main():
    src, scene = sys.argv[1], sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else src
    paths = sorted(glob.glob(os.path.join(src, "*seq-%s-*.png" % scene)))
    if not paths:
        sys.exit("no frames for scene %r in %s" % (scene, src))
    frames = [read_png(p) for p in paths]
    w, h = frames[0][0], frames[0][1]
    rgbs = [f[2] for f in frames]

    small = [downscale(w, h, rgb, 2) for rgb in rgbs]
    sw, sh = small[0][0], small[0][1]
    write_apng(os.path.join(out, scene + ".apng"), sw, sh, [s[2] for s in small], 50)

    k = 4
    tw, th = w // k, h // k
    cols = 6
    rows_n = (len(rgbs) + cols - 1) // cols
    sheet = bytearray(tw * cols * th * rows_n * 3)
    for n, rgb in enumerate(rgbs):
        _, _, t = downscale(w, h, rgb, k)
        ox, oy = (n % cols) * tw, (n // cols) * th
        for y in range(th):
            d = ((oy + y) * tw * cols + ox) * 3
            sheet[d:d + tw * 3] = t[y * tw * 3:(y + 1) * tw * 3]
    write_png(os.path.join(out, scene + "-sheet.png"), tw * cols, th * rows_n, bytes(sheet))

    scores = [flicker(rgbs[i - 1], rgbs[i], rgbs[i + 1], w, h) for i in range(1, len(rgbs) - 1)]
    print("%s: %d frames, flicker per frame %s" % (scene, len(rgbs), scores))
    print("%s: flicker max %d mean %.1f" % (scene, max(scores or [0]), sum(scores) / max(1, len(scores))))


if __name__ == "__main__":
    main()
