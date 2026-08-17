#!/usr/bin/env python3
"""
Draws a WAD's wall textures to PNG, the way the engine composes them.

An independent renderer on purpose. When a wall looks wrong in the game there
are only three suspects - the data, the engine, or the artwork being like that
to begin with - and composing the texture here separates them: if this draws the
same mess, the mess is in the WAD; if this draws something sensible, the engine
is not drawing it that way.

Usage:
    python tools/render-texture.py <wad> <outdir> [name ...]
    python tools/render-texture.py <wad> <outdir> --map E1M2      # all it uses
    python tools/render-texture.py <wad> <outdir> --keydoors E1M2 # keyed doors
    python tools/render-texture.py <wad> <outdir> --bluest E1M2   # most blue
"""

import os
import struct
import sys

from PIL import Image

# Doom's keyed door specials: red, blue, yellow, both repeatable and once.
KEY_DOOR_SPECIALS = {26, 27, 28, 32, 33, 34, 99, 133, 134, 135, 136, 137}
KEY_NAMES = {26: "blue", 27: "yellow", 28: "red", 32: "blue", 33: "red",
             34: "yellow", 99: "blue", 133: "blue", 134: "red", 135: "red",
             136: "yellow", 137: "yellow"}


def load(path):
    data = open(path, "rb").read()
    magic, count, table = struct.unpack_from("<4sii", data, 0)
    lumps = []
    for i in range(count):
        pos, size, raw = struct.unpack_from("<ii8s", data, table + i * 16)
        lumps.append((raw.rstrip(b"\0").decode("ascii", "replace").upper(), pos, size))
    index = {}
    for name, pos, size in lumps:
        index[name] = (pos, size)      # last wins, as the engine resolves it
    return data, lumps, index


def palette(data, index):
    pos, _ = index["PLAYPAL"]
    return [tuple(data[pos + i * 3:pos + i * 3 + 3]) for i in range(256)]


def texture_table(data, index):
    ppos, _ = index["PNAMES"]
    pcount = struct.unpack_from("<i", data, ppos)[0]
    pnames = [data[ppos + 4 + i * 8:ppos + 12 + i * 8].rstrip(b"\0").decode("ascii", "replace").upper()
              for i in range(pcount)]

    out = {}
    for lump in ("TEXTURE1", "TEXTURE2"):
        if lump not in index:
            continue
        tpos, _ = index[lump]
        count = struct.unpack_from("<i", data, tpos)[0]
        for i in range(count):
            off = struct.unpack_from("<i", data, tpos + 4 + i * 4)[0]
            base = tpos + off
            name = data[base:base + 8].rstrip(b"\0").decode("ascii", "replace").upper()
            masked, width, height, _ = struct.unpack_from("<ihhi", data, base + 8)
            pc = struct.unpack_from("<h", data, base + 20)[0]
            patches = []
            for j in range(pc):
                ox, oy, pi, step, cmap = struct.unpack_from("<hhhhh", data, base + 22 + j * 10)
                patches.append((ox, oy, pnames[pi] if 0 <= pi < len(pnames) else None))
            out[name] = (width, height, patches)
    return out


def draw_patch(image, data, index, name, ox, oy, pal):
    """Doom's picture format: a column table of posts, transparent gaps."""
    if name not in index:
        return False
    pos, size = index[name]
    width, height, left, top = struct.unpack_from("<hhhh", data, pos)
    offsets = struct.unpack_from("<%di" % width, data, pos + 8)

    px = image.load()
    for x in range(width):
        column = pos + offsets[x]
        while True:
            row = data[column]
            if row == 0xFF:
                break
            count = data[column + 1]
            column += 3                     # skip the leading dummy byte
            for y in range(count):
                tx, ty = ox + x, oy + row + y
                if 0 <= tx < image.width and 0 <= ty < image.height:
                    px[tx, ty] = pal[data[column + y]]
            column += count + 1             # and the trailing one
    return True


def render(data, index, textures, pal, name):
    if name not in textures:
        return None
    width, height, patches = textures[name]
    image = Image.new("RGB", (max(1, width), max(1, height)), (255, 0, 255))
    for ox, oy, patch in patches:
        if patch:
            draw_patch(image, data, index, patch, ox, oy, pal)
    return image


def map_data(data, lumps, wanted):
    """(sidedefs bytes, linedefs bytes) of one map."""
    inside, current, out = False, None, {}
    for name, pos, size in lumps:
        if len(name) == 4 and name[0] == "E" and name[2] == "M":
            inside, current = True, name
            continue
        if inside and current == wanted and name in ("SIDEDEFS", "LINEDEFS"):
            out[name] = data[pos:pos + size]
        elif inside and name not in ("THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES",
                                     "SEGS", "SSECTORS", "NODES", "SECTORS",
                                     "REJECT", "BLOCKMAP"):
            inside = False
    return out.get("SIDEDEFS", b""), out.get("LINEDEFS", b"")


def sidedef_textures(sidedefs, which):
    out = []
    for o in range(0, len(sidedefs), 30):
        names = []
        for field in (4, 12, 20):
            t = sidedefs[o + field:o + field + 8].rstrip(b"\0").decode("ascii", "replace").upper()
            names.append(t if t and t != "-" else None)
        out.append(names)
    return out


def main():
    wad, outdir = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    os.makedirs(outdir, exist_ok=True)

    data, lumps, index = load(wad)
    pal = palette(data, index)
    textures = texture_table(data, index)

    names = []
    if rest and rest[0] in ("--map", "--keydoors", "--bluest"):
        mapname = rest[1]
        sidedefs, linedefs = map_data(data, lumps, mapname)
        sides = sidedef_textures(sidedefs, mapname)

        if rest[0] == "--keydoors":
            for o in range(0, len(linedefs), 14):
                special = struct.unpack_from("<h", linedefs, o + 6)[0]
                if special not in KEY_DOOR_SPECIALS:
                    continue
                right = struct.unpack_from("<h", linedefs, o + 10)[0]
                if 0 <= right < len(sides):
                    for t in sides[right]:
                        if t:
                            print("%s door: %s" % (KEY_NAMES.get(special, "?"), t))
                            names.append(t)
        else:
            used = set()
            for s in sides:
                for t in s:
                    if t:
                        used.add(t)
            names = sorted(used)
    else:
        names = [n.upper() for n in rest]

    seen = set()
    blueness = []
    for name in names:
        if name in seen:
            continue
        seen.add(name)
        image = render(data, index, textures, pal, name)
        if image is None:
            print("  %s: not in this WAD" % name)
            continue
        image.save(os.path.join(outdir, name + ".png"))

        pixels = list(image.getdata())
        blue = sum(1 for r, g, b in pixels if b > 60 and b > r + 30 and b > g + 30)
        blueness.append((blue / float(len(pixels)), name, image.size))

    if rest and rest[0] == "--bluest":
        print("\nmost blue textures on the map:")
        for share, name, size in sorted(blueness, reverse=True)[:12]:
            print("   %-9s %3dx%-3d  %5.1f%% blue" % (name, size[0], size[1], share * 100))
    else:
        print("\nwrote %d textures to %s" % (len(seen), outdir))


if __name__ == "__main__":
    main()
