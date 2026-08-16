#!/usr/bin/env python3
"""
Builds a smaller IWAD by keeping only some of its maps.

Freedoom is BSD licensed, so cutting it down and shipping the result is allowed
as long as the copyright notice and disclaimer come along, which they do: see
third-party/freedoom and the files unpacked next to the WAD.

Maps are about a third of the file. Everything else - sprites, textures, sounds,
music, the status bar, the menu - has to stay, because the engine expects a
complete asset set and will die looking for whatever is missing. So this trims
what is safe to trim and nothing else.

Usage:
    python tools/strip-wad.py <in.wad> <out.wad> E1M1 E1M2 E1M3
"""

import struct
import sys

# The lumps that belong to the map marker before them.
MAP_SUBLUMPS = {
    "THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES", "SEGS", "SSECTORS",
    "NODES", "SECTORS", "REJECT", "BLOCKMAP", "BEHAVIOR",
}


def is_map_marker(name):
    """ExMy for Doom 1, MAPxx for Doom 2."""
    if len(name) == 4 and name[0] == "E" and name[2] == "M":
        return name[1].isdigit() and name[3].isdigit()
    return len(name) == 5 and name.startswith("MAP") and name[3:].isdigit()


def read_directory(data):
    magic, count, table = struct.unpack_from("<4sii", data, 0)
    if magic not in (b"IWAD", b"PWAD"):
        raise SystemExit("not a WAD: %r" % magic)

    entries = []
    for i in range(count):
        pos, size, raw = struct.unpack_from("<ii8s", data, table + i * 16)
        entries.append((raw.rstrip(b"\0").decode("ascii", "replace"), pos, size))
    return magic, entries


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)

    src, dst, keep = sys.argv[1], sys.argv[2], [m.upper() for m in sys.argv[3:]]
    data = open(src, "rb").read()
    magic, entries = read_directory(data)

    kept, dropped_maps, current = [], [], None
    for name, pos, size in entries:
        if is_map_marker(name):
            current = name
            if name in keep:
                kept.append((name, pos, size))
            else:
                dropped_maps.append(name)
            continue

        if current is not None and name in MAP_SUBLUMPS:
            # Belongs to whichever map marker we last saw.
            if current in keep:
                kept.append((name, pos, size))
            continue

        # Anything else ends the map and is kept: shared assets, markers, etc.
        current = None
        kept.append((name, pos, size))

    missing = [m for m in keep if m not in [n for n, _, _ in kept]]
    if missing:
        raise SystemExit("these maps are not in the WAD: %s" % ", ".join(missing))

    # Lump data first, then the directory pointing at it.
    out = bytearray(b"\0" * 12)
    directory = []
    for name, pos, size in kept:
        directory.append((name, len(out), size))
        out += data[pos:pos + size]

    table = len(out)
    for name, pos, size in directory:
        out += struct.pack("<ii8s", pos, size, name.encode("ascii")[:8].ljust(8, b"\0"))

    struct.pack_into("<4sii", out, 0, magic, len(directory), table)
    open(dst, "wb").write(out)

    print("kept %d maps, dropped %d" % (len(keep), len(dropped_maps)))
    print("lumps  %d -> %d" % (len(entries), len(directory)))
    print("size   %.2f MB -> %.2f MB" % (len(data) / 1048576.0, len(out) / 1048576.0))


if __name__ == "__main__":
    main()
