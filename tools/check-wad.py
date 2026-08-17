#!/usr/bin/env python3
"""
Validates a pruned IWAD before it ships.

Pruning fails in two directions and only one of them is loud. A missing patch
kills the engine on the first wall that needs it, which is impossible to miss. A
misnumbered or half-kept one draws the wrong thing quietly, on some map nobody
tested, and the first report of it is a screenshot from a player.

So this checks the quiet ones:

  * every PNAMES entry resolves to a lump that is really there;
  * every texture's patch indices are inside PNAMES;
  * every column of every texture is covered by a patch, since a bare column is
    the classic stripe of garbage down a wall;
  * every texture and flat the surviving maps ask for still exists;
  * every animation range is *whole*. P_InitPicAnims does not read a list of
    frames: it takes the start and end names and animates everything between
    them in WAD order. Keeping only the two ends leaves a four frame waterfall
    running two, and anything unrelated that survives in between gets animated
    as a frame of slime.

Optionally compares against the WAD it was cut from, which is the strongest
check there is: everything kept has to be bit for bit what it was.

Usage:
    python tools/check-wad.py <pruned.wad> [original.wad] [engine-source-dir]
"""

import os
import re
import struct
import sys

MAP_SUBLUMPS = {
    "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES", "SEGS", "SSECTORS",
    "NODES", "SECTORS", "REJECT", "BLOCKMAP", "BEHAVIOR",
}

FLAT_START = ("F_START", "FF_START", "F1_START", "F2_START", "F3_START")
FLAT_END = ("F_END", "FF_END", "F1_END", "F2_END", "F3_END")

DEFAULT_ENGINE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "java", "com", "dbr", "doom", "engine")

problems = []


def fail(message):
    problems.append(message)
    print("  FAIL " + message)


def load(path):
    data = open(path, "rb").read()
    magic, count, table = struct.unpack_from("<4sii", data, 0)
    if magic not in (b"IWAD", b"PWAD"):
        raise SystemExit("not a WAD: %r" % magic)
    lumps = []
    for i in range(count):
        pos, size, raw = struct.unpack_from("<ii8s", data, table + i * 16)
        lumps.append((raw.rstrip(b"\0").decode("ascii", "replace").upper(), pos, size))
    # Last wins, which is how the engine resolves a name.
    index = {}
    for name, pos, size in lumps:
        index[name] = (pos, size)
    return data, lumps, index


def pnames_of(data, index):
    pos, _ = index["PNAMES"]
    count = struct.unpack_from("<i", data, pos)[0]
    return [data[pos + 4 + i * 8:pos + 12 + i * 8].rstrip(b"\0").decode("ascii", "replace").upper()
            for i in range(count)]


def textures_of(data, index):
    """Ordered [(name, masked, width, height, [(ox, oy, patch, step, cmap)])]."""
    names = pnames_of(data, index)
    out = []
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
            patch_count = struct.unpack_from("<h", data, base + 20)[0]
            patches = []
            for j in range(patch_count):
                ox, oy, pi, step, cmap = struct.unpack_from("<hhhhh", data, base + 22 + j * 10)
                patches.append((ox, oy, pi, step, cmap))
            out.append((name, masked, width, height, patches, names))
    return out


def flats_of(lumps):
    out, inside = [], False
    for name, pos, size in lumps:
        if name in FLAT_START:
            inside = True
            continue
        if name in FLAT_END:
            inside = False
            continue
        if inside and size > 0:
            out.append(name)
    return out


def map_assets(data, lumps):
    """(textures, flats) each map's sidedefs and sectors ask for, by map."""
    inside, current = False, None
    textures, flats = {}, {}
    for name, pos, size in lumps:
        if len(name) == 4 and name[0] == "E" and name[2] == "M":
            inside, current = True, name
            continue
        if not inside:
            continue
        if name == "SIDEDEFS":
            for o in range(pos, pos + size, 30):
                for field in (4, 12, 20):
                    t = data[o + field:o + field + 8].rstrip(b"\0").decode("ascii", "replace").upper()
                    if t and t != "-":
                        textures.setdefault(t, set()).add(current)
        elif name == "SECTORS":
            for o in range(pos, pos + size, 26):
                for field in (4, 12):
                    f = data[o + field:o + field + 8].rstrip(b"\0").decode("ascii", "replace").upper()
                    if f:
                        flats.setdefault(f, set()).add(current)
        elif name not in MAP_SUBLUMPS:
            inside = False
    return textures, flats


def animations(engine_dir):
    path = os.path.join(engine_dir, "p", "UnifiedGameMap.java")
    src = open(path, encoding="utf-8", newline="").read()
    found = re.findall(r'new\s+animdef_t\(\s*(true|false)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"', src)
    if not found:
        raise SystemExit("could not read the animation table from " + path)
    return [(t == "true", start.upper(), end.upper()) for t, end, start in found]


def same_family(frame, start, end):
    """Whether a name looks like a frame of the animation start..end.

    Not by stripping digits: the frames of an animation are not always numbered.
    FIREWALA, FIREWALB and FIREWALL are three frames of one, and FIRELAV3 runs
    to FIRELAVA. What they do share is everything but the last character, which
    is what the id naming convention actually guarantees.
    """
    stem = os.path.commonprefix([start, end])
    if not stem:
        stem = start[:-1]
    return frame.startswith(stem)


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)

    pruned_path = sys.argv[1]
    original_path = sys.argv[2] if len(sys.argv) > 2 else None
    engine_dir = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_ENGINE

    data, lumps, index = load(pruned_path)
    present = set(name for name, _, _ in lumps)
    pnames = pnames_of(data, index)
    textures = textures_of(data, index)
    texture_names = [t[0] for t in textures]
    flats = flats_of(lumps)

    print("%s: %d lumps, %d textures, %d patches, %d flats"
          % (os.path.basename(pruned_path), len(lumps), len(textures), len(pnames), len(flats)))

    print("\npatches")
    for name in pnames:
        if name not in present:
            fail("PNAMES lists %s, which is not in the WAD" % name)
    for name, masked, width, height, patches, names in textures:
        for ox, oy, pi, step, cmap in patches:
            if not 0 <= pi < len(names):
                fail("texture %s points at patch %d, outside PNAMES" % (name, pi))
    print("  ok   every patch reference resolves")

    print("\ntexture coverage")
    def patch_width(name):
        if name not in index:
            return None
        pos, size = index[name]
        return struct.unpack_from("<h", data, pos)[0] if size >= 8 else None

    for name, masked, width, height, patches, names in textures:
        covered = [False] * max(0, width)
        for ox, oy, pi, step, cmap in patches:
            if not 0 <= pi < len(names):
                continue
            w = patch_width(names[pi])
            if w is None:
                continue
            for x in range(max(0, ox), min(ox + w, width)):
                covered[x] = True
        if not all(covered):
            fail("texture %s has %d columns no patch covers"
                 % (name, sum(1 for c in covered if not c)))
    print("  ok   no texture has a bare column")

    print("\nwhat the maps ask for")
    wanted_tex, wanted_flat = map_assets(data, lumps)
    for t, maps in sorted(wanted_tex.items()):
        if t not in texture_names:
            fail("%s uses texture %s, which is gone" % (",".join(sorted(maps)), t))
    for f, maps in sorted(wanted_flat.items()):
        if f != "F_SKY1" and f not in flats:
            fail("%s uses flat %s, which is gone" % (",".join(sorted(maps)), f))
    print("  ok   every texture and flat the maps reference is present")

    print("\nanimations")
    for is_texture, start, end in animations(engine_dir):
        order = texture_names if is_texture else flats
        kind = "texture" if is_texture else "flat"
        if start not in order:
            continue                        # not in this WAD; the engine skips it too
        if end not in order:
            fail("%s animation %s..%s has lost its end frame, and the engine"
                 " takes that one unchecked" % (kind, start, end))
            continue
        a, b = order.index(start), order.index(end)
        if b < a:
            fail("%s animation %s..%s runs backwards in this WAD" % (kind, start, end))
            continue
        frames = order[a:b + 1]
        strangers = [f for f in frames if not same_family(f, start, end)]
        if strangers:
            fail("%s animation %s..%s would animate %s"
                 % (kind, start, end, ",".join(strangers)))
        elif len(frames) < 2:
            fail("%s animation %s..%s is down to one frame" % (kind, start, end))
        else:
            print("  ok   %-7s %-9s %d frames" % (kind, start, len(frames)))

    if original_path:
        print("\nagainst the original")
        odata, olumps, oindex = load(original_path)
        changed = 0
        for name, pos, size in lumps:
            if name in ("PNAMES", "TEXTURE1", "TEXTURE2") or size == 0:
                continue                     # rebuilt on purpose
            if name in MAP_SUBLUMPS:
                continue                     # every map has one; compared below
            if name not in oindex:
                continue                     # markers and the like
            opos, osize = oindex[name]
            if odata[opos:opos + osize] != data[pos:pos + size]:
                changed += 1
                if changed <= 5:
                    fail("%s is not bit for bit the original" % name)

        # Maps, by marker: their sublumps all share the same handful of names,
        # so comparing those by name compares the last map with every map.
        def maps_of(data, lumps):
            out, current = {}, None
            for name, pos, size in lumps:
                if len(name) == 4 and name[0] == "E" and name[2] == "M":
                    current = name
                    out[current] = {}
                    continue
                if current is None:
                    continue
                if name in MAP_SUBLUMPS:
                    out[current][name] = data[pos:pos + size]
                else:
                    current = None
            return out

        theirs, ours = maps_of(odata, olumps), maps_of(data, lumps)
        for mapname, sublumps in sorted(ours.items()):
            if mapname not in theirs:
                fail("%s is not in the original at all" % mapname)
            elif theirs[mapname] != sublumps:
                fail("%s is not bit for bit the original" % mapname)
        print("  ok   %d maps are bit for bit the original" % len(ours))
        if changed == 0:
            print("  ok   everything kept is bit for bit the original")
        else:
            fail("%d lumps differ from the original" % changed)

    print()
    if problems:
        print("NOT SHIPPABLE: %d problem(s)" % len(problems))
        sys.exit(1)
    print("OK: this WAD is safe to ship")


if __name__ == "__main__":
    main()
