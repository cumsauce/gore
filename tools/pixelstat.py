#!/usr/bin/env python3
"""Count pixels near a target colour in a PNG (stdlib only, no PIL).

Usage: pixelstat.py <png> <r> <g> <b> <tol> [x0 y0 x1 y1]

Counts pixels whose |R-r|, |G-g| and |B-b| are all <= tol, inside the
optional region (inclusive on both ends) or the whole image. Prints exactly
one line:

    size=WxH count=N bbox=x0,y0,x1,y1 centroid=cx.x,cy.y

with bbox=none and centroid=none when nothing matched; the centroid is the
mean of the matching pixel coordinates, printed with one decimal.
"""

import struct
import sys
import zlib


def load_png(path):
    data = open(path, "rb").read()
    i = 8
    idat = b""
    w = h = bitdepth = colortype = 0
    while i < len(data):
        ln = struct.unpack(">I", data[i : i + 4])[0]
        typ = data[i + 4 : i + 8]
        chunk = data[i + 8 : i + 8 + ln]
        if typ == b"IHDR":
            w, h, bitdepth, colortype, comp, filt, interlace = struct.unpack(
                ">IIBBBBB", chunk
            )
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        i += 12 + ln
    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    bpp = channels * (bitdepth // 8)
    stride = w * bpp
    out = bytearray(h * stride)
    pos = 0
    prev = bytearray(stride)
    for y in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos : pos + stride])
        pos += stride
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
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out[y * stride : (y + 1) * stride] = line
        prev = line
    return w, h, channels, out


def main(argv):
    if len(argv) not in (6, 10):
        sys.exit("usage: pixelstat.py <png> <r> <g> <b> <tol> [x0 y0 x1 y1]")
    path = argv[1]
    r, g, b, tol = (int(v) for v in argv[2:6])
    w, h, ch, px = load_png(path)
    if len(argv) == 10:
        x0, y0, x1, y1 = (int(v) for v in argv[6:10])
        x0 = max(0, x0)
        y0 = max(0, y0)
        x1 = min(w - 1, x1)
        y1 = min(h - 1, y1)
    else:
        x0, y0, x1, y1 = 0, 0, w - 1, h - 1

    count = 0
    minx = miny = maxx = maxy = 0
    sumx = sumy = 0
    for y in range(y0, y1 + 1):
        row = y * w * ch
        for x in range(x0, x1 + 1):
            o = row + x * ch
            if (
                abs(px[o] - r) <= tol
                and abs(px[o + 1] - g) <= tol
                and abs(px[o + 2] - b) <= tol
            ):
                count += 1
                if count == 1:
                    minx = maxx = x
                    miny = maxy = y
                else:
                    if x < minx:
                        minx = x
                    if x > maxx:
                        maxx = x
                    if y < miny:
                        miny = y
                    if y > maxy:
                        maxy = y
                sumx += x
                sumy += y

    if count == 0:
        print("size=%dx%d count=0 bbox=none centroid=none" % (w, h))
    else:
        print(
            "size=%dx%d count=%d bbox=%d,%d,%d,%d centroid=%.1f,%.1f"
            % (w, h, count, minx, miny, maxx, maxy, sumx / count, sumy / count)
        )


if __name__ == "__main__":
    main(sys.argv)
