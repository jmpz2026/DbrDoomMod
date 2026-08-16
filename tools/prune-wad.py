#!/usr/bin/env python3
"""
Drops the assets a trimmed IWAD no longer needs.

Removing maps leaves most of their artwork behind: after keeping only Episode 1
of Freedoom, 620 of 1047 texture patches, 94 of 240 flats and 27 of 41 music
tracks belong to maps that are gone. That is around 8MB of the 19MB left.

Patches cannot simply be deleted. The engine builds every texture from the
TEXTURE1 and PNAMES tables and dies on a missing patch, so both tables are
rebuilt and the patch indices renumbered.

Some names are never safe to drop, whatever the maps say, because the engine
asks for them by name:

  * Switch textures. P_InitSwitchList calls TextureNumForName, which errors
    rather than returning -1. A missing switch is a dead game.
  * Animated texture and flat ranges. P_InitPicAnims does check first, but only
    on the start name; it then takes the end name unchecked. Keep both or drop
    both, so keep both.
  * The sky. Chosen by episode number, not by anything in the map.

Those lists are read out of the engine source rather than copied here, so they
cannot drift.

Usage:
    python tools/prune-wad.py <in.wad> <out.wad> [engine-source-dir]
"""

import os
import re
import struct
import sys

MAP_SUBLUMPS = {
    "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES", "SEGS", "SSECTORS",
    "NODES", "SECTORS", "REJECT", "BLOCKMAP", "BEHAVIOR",
}

# Music for episodes that are gone. Everything else, including the title,
# intermission and end-game tracks, is kept: those play outside any map.
DROPPABLE_MUSIC = re.compile(r"^D_E[2-9]M\d$")

DEFAULT_ENGINE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "java", "com", "dbr", "doom", "engine")


def is_map_marker(name):
    if len(name) == 4 and name[0] == "E" and name[2] == "M":
        return name[1].isdigit() and name[3].isdigit()
    return len(name) == 5 and name.startswith("MAP") and name[3:].isdigit()


def engine_protected_names(engine_dir):
    """Switch and animation names, read straight from the engine source."""
    path = os.path.join(engine_dir, "p", "UnifiedGameMap.java")
    src = open(path, encoding="utf-8", newline="").read()

    switches = re.findall(r'new\s+switchlist_t\("([^"]*)"\s*,\s*"([^"]*)"', src)
    anims = re.findall(r'new\s+animdef_t\(\s*(true|false)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"', src)
    if not switches or not anims:
        raise SystemExit("could not read the switch or animation tables from " + path)

    textures, flats = set(), set()
    for a, b in switches:
        textures.update(x.upper() for x in (a, b) if x)

    for is_texture, end, start in anims:
        target = textures if is_texture == "true" else flats
        target.update(x.upper() for x in (end, start) if x)

    # Sky is picked by episode number and never appears in a sidedef.
    textures.update({"SKY1", "SKY2", "SKY3", "SKY4"})
    flats.add("F_SKY1")

    print("protected: %d textures, %d flats (from the engine's own tables)"
          % (len(textures), len(flats)))
    return textures, flats


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)

    src_path, dst_path = sys.argv[1], sys.argv[2]
    engine_dir = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_ENGINE

    data = open(src_path, "rb").read()
    magic, count, table = struct.unpack_from("<4sii", data, 0)
    if magic != b"IWAD":
        raise SystemExit("not an IWAD: %r" % magic)

    lumps = []
    for i in range(count):
        pos, size, raw = struct.unpack_from("<ii8s", data, table + i * 16)
        lumps.append((raw.rstrip(b"\0").decode("ascii", "replace").upper(), pos, size))
    index = {}
    for name, pos, size in lumps:
        index.setdefault(name, (pos, size))

    keep_tex, keep_flat = engine_protected_names(engine_dir)

    # What the surviving maps actually reference.
    in_map = False
    for name, pos, size in lumps:
        if is_map_marker(name):
            in_map = True
            continue
        if not in_map:
            continue
        if name == "SIDEDEFS":
            for o in range(pos, pos + size, 30):
                for field in (4, 12, 20):
                    t = data[o + field:o + field + 8].rstrip(b"\0").decode("ascii", "replace").upper()
                    if t and t != "-":
                        keep_tex.add(t)
        elif name == "SECTORS":
            for o in range(pos, pos + size, 26):
                for field in (4, 12):
                    f = data[o + field:o + field + 8].rstrip(b"\0").decode("ascii", "replace").upper()
                    if f:
                        keep_flat.add(f)
        elif name not in MAP_SUBLUMPS:
            in_map = False

    # PNAMES and TEXTURE1 decide which patches survive.
    ppos, _ = index["PNAMES"]
    pcount = struct.unpack_from("<i", data, ppos)[0]
    pnames = [data[ppos + 4 + i * 8:ppos + 12 + i * 8].rstrip(b"\0").decode("ascii", "replace").upper()
              for i in range(pcount)]

    """
    Both texture tables have to be handled together. TEXTURE2 shares the one
    PNAMES with TEXTURE1, so rebuilding only the first and renumbering PNAMES
    underneath leaves the second pointing at the wrong names: not a crash, just
    the wrong artwork on the wrong walls.
    """
    texture_lumps = [t for t in ("TEXTURE1", "TEXTURE2") if t in index]

    parsed, used_patches, original_total = {}, set(), 0
    for lump in texture_lumps:
        tpos, _ = index[lump]
        tcount = struct.unpack_from("<i", data, tpos)[0]
        original_total += tcount
        offsets = [struct.unpack_from("<i", data, tpos + 4 + i * 4)[0] for i in range(tcount)]

        kept_here = []
        for off in offsets:
            base = tpos + off
            name = data[base:base + 8].rstrip(b"\0").decode("ascii", "replace").upper()
            patch_count = struct.unpack_from("<h", data, base + 20)[0]
            if name not in keep_tex:
                continue

            patches = []
            for j in range(patch_count):
                e = base + 22 + j * 10
                ox, oy, pi, step, cmap = struct.unpack_from("<hhhhh", data, e)
                if not 0 <= pi < len(pnames):
                    raise SystemExit("texture %s points at patch %d, outside PNAMES" % (name, pi))
                used_patches.add(pnames[pi])
                patches.append((ox, oy, pnames[pi], step, cmap))

            masked, width, height, _ = struct.unpack_from("<ihhi", data, base + 8)
            kept_here.append((name, masked, width, height, patches))

        parsed[lump] = kept_here

    # Renumber PNAMES against only the patches that are still referenced.
    new_pnames = sorted(used_patches)
    pindex = {n: i for i, n in enumerate(new_pnames)}

    replacements = {}
    kept_total = 0
    for lump, kept_here in parsed.items():
        kept_total += len(kept_here)
        out_lump = bytearray(struct.pack("<i", len(kept_here)))
        header = 4 + 4 * len(kept_here)
        body, offs = bytearray(), []
        for name, masked, width, height, patches in kept_here:
            offs.append(header + len(body))
            body += name.encode("ascii")[:8].ljust(8, b"\0")
            body += struct.pack("<ihhi", masked, width, height, 0)
            body += struct.pack("<h", len(patches))
            for ox, oy, pname, step, cmap in patches:
                body += struct.pack("<hhhhh", ox, oy, pindex[pname], step, cmap)
        for o in offs:
            out_lump += struct.pack("<i", o)
        out_lump += body
        replacements[lump] = bytes(out_lump)

    new_pnames_lump = bytearray(struct.pack("<i", len(new_pnames)))
    for n in new_pnames:
        new_pnames_lump += n.encode("ascii")[:8].ljust(8, b"\0")
    replacements["PNAMES"] = bytes(new_pnames_lump)

    tcount, kept_textures = original_total, [None] * kept_total
    section, kept, dropped = None, [], {"patch": 0, "flat": 0, "music": 0}

    for name, pos, size in lumps:
        if name in ("P_START", "PP_START", "P1_START", "P2_START", "P3_START"):
            section = "patch"
            kept.append((name, pos, size, None)); continue
        if name in ("P_END", "PP_END", "P1_END", "P2_END", "P3_END"):
            section = None
            kept.append((name, pos, size, None)); continue
        if name in ("F_START", "FF_START", "F1_START", "F2_START", "F3_START"):
            section = "flat"
            kept.append((name, pos, size, None)); continue
        if name in ("F_END", "FF_END", "F1_END", "F2_END", "F3_END"):
            section = None
            kept.append((name, pos, size, None)); continue

        if section == "patch" and size > 0 and name not in used_patches:
            dropped["patch"] += size; continue
        if section == "flat" and size > 0 and name not in keep_flat:
            dropped["flat"] += size; continue
        if DROPPABLE_MUSIC.match(name):
            dropped["music"] += size; continue

        kept.append((name, pos, size, replacements.get(name)))

    # Everything a kept texture needs must still be there.
    present = {n for n, _, _, _ in kept}
    missing = [p for p in new_pnames if p not in present]
    if missing:
        raise SystemExit("these patches would be missing: %s" % ", ".join(missing[:10]))

    out = bytearray(b"\0" * 12)
    directory = []
    for name, pos, size, replacement in kept:
        payload = replacement if replacement is not None else data[pos:pos + size]
        directory.append((name, len(out), len(payload)))
        out += payload

    table_at = len(out)
    for name, pos, size in directory:
        out += struct.pack("<ii8s", pos, size, name.encode("ascii")[:8].ljust(8, b"\0"))
    struct.pack_into("<4sii", out, 0, magic, len(directory), table_at)

    open(dst_path, "wb").write(out)

    print("textures  %d -> %d" % (tcount, len(kept_textures)))
    print("pnames    %d -> %d" % (len(pnames), len(new_pnames)))
    print("dropped   patches %.2f MB, flats %.2f MB, music %.2f MB"
          % (dropped["patch"] / 1048576.0, dropped["flat"] / 1048576.0,
             dropped["music"] / 1048576.0))
    print("size      %.2f MB -> %.2f MB" % (len(data) / 1048576.0, len(out) / 1048576.0))


if __name__ == "__main__":
    main()
