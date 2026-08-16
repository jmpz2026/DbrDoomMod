# DbrDoomMod

Play Doom inside Minecraft 1.7.10. Craft an arcade cabinet, right click it, and
Doom runs on its screen while the world carries on around you.

The mod must be installed on **both the client and the server**. The block has to
exist on both sides; the Doom engine itself only ever runs on a client.

## The arcade cabinet

A solid block with a screen on the face you were looking at when you placed it.

```
I G I      I = Iron ingot     G = Glass
R E R      R = Redstone       E = Eye of Ender
I R I
```

The eye of ender keeps it out of reach early on, so servers do not fill up with
cabinets. It is in the Redstone creative tab.

Right click to play. The server decides who gets a machine, so two players
clicking at once cannot both end up on it; the second is told who has it.

**Each client runs its own engine.** Two players at the same cabinet would be
playing separate games, so only the person holding it sees a picture. Everybody
else sees the idle screen. Synchronising the game itself would mean simulating
Doom on the server, which the engine has no support for.

While you are playing, your body stays where it is and stays vulnerable. Taking
damage ends the session, as does breaking the cabinet.

## Game data

Works out of the box. **Episode 1 of [Freedoom](https://freedoom.github.io/)
Phase 1** ships with the mod and is unpacked into `config/dbrdoom/wads/` on first
launch, so a cabinet has something to run straight away. That takes a second or
two, once.

Nine maps, E1M1 to E1M9, ending with the episode's own boss. Enough to feel like
something you found rather than a second game bolted on.

Freedoom is a free, openly licensed replacement for Doom's game data. It is not
the original id Software artwork: `DOOM.WAD` and `DOOM2.WAD` are proprietary and
are **never** bundled here. If you own a copy, drop it into
`config/dbrdoom/wads/` and it will be found.

Game data was around ninety percent of the download, so it is cut three ways: 27
of Freedoom's 36 maps are dropped, then the textures, patches, flats and music
those maps took with them, and what remains is stored as LZMA2 rather than a
plain zip entry. Together that takes the jar from 23MB to about 5MB.

The whole episode is kept rather than a few maps: map data compresses so well
that cutting to three would have saved another 0.4MB and lost the ending.

Want the rest? Download Freedoom from its own site and drop the WAD in the same
folder; the mod finds whatever is there. `MODIFICATIONS-freedoom.txt`, unpacked
next to the WAD, records exactly what was removed.

Deleting a bundled WAD is fine. A marker file records that the unpack already
happened, so nothing is restored behind your back on the next launch.

## Layout

```
config/dbrdoom.cfg   <- this mod's settings
config/dbrdoom/
  wads/              <- Freedoom lands here; add your own .wad files too
  saves/             <- Doom savegames
  default.cfg        <- Doom's own settings, written by its options menu
  mochadoom.cfg      <- engine settings
```

## Configuration

`config/dbrdoom.cfg` covers the things Doom cannot know about:

| Setting | Default | Notes |
|---|---|---|
| `exitKey` | `F10` | LWJGL key name. Double-tap ESC always works too |
| `escapeDoubleTapMs` | `400` | A single ESC opens Doom's own menu, as in the real game |
| `internalScale` | `1` | Multiple of 320x200. **Not** an upscale, see below |
| `detachedCamera` | `true` | See the warning below |
| `arcadeViewDistance` | `0.7` | How far from the screen you sit, in blocks |
| `duckMinecraft` | `true` | Turn Minecraft down while Doom plays |
| `duckLevel` | `0.15` | Minecraft's volume while Doom is open |
| `sfxVolume` / `musicVolume` | `-1` | `-1` means "use Doom's own setting" |

### detachedCamera, and a known bug

With `detachedCamera=true` the view is moved to sit level with the screen, dead
on, whatever height the cabinet is at. Your body is not moved.

**If the game freezes when you use a cabinet, set this to `false`.** Two early
builds froze hard enough to need killing the process, with no crash report and no
Java exception. It has not reproduced since, and the cause is not understood, so
the switch exists to get out of it without a rebuild.

With it off, your body is walked in front of the cabinet and its head aimed at
the screen. That works, but a cabinet standing on the floor has its screen at
knee height and is therefore seen from above. Mount it a block up, so its middle
is near eye level, for a head-on view.

Screen size, mouse sensitivity and key bindings are **not** here on purpose.
Doom already has an options menu for those and it now saves to
`config/dbrdoom/default.cfg`. Two places setting one value would only fight.

`internalScale` deserves a warning: the engine renders every pixel in software,
so `2` costs four times the CPU and `3` costs nine. The picture is stretched to
your window by the GPU either way, so `1` is almost always right.

## Controls

Everything goes to Doom, including ESC, which opens Doom's own menu. To step away
press `F10`, or tap ESC twice quickly.

`/DoomStop` is the only command. It exists to free a session that has wedged,
without restarting the game; playing is done at the cabinet.

## Build

Requires JDK 8.

```
./gradlew build
```

The jar lands in `build/libs/`.

## License

GPLv3. See `LICENSE`.

This mod embeds the [Mocha Doom](https://github.com/AXDOOMER/mochadoom) engine,
a pure Java Doom source port:

- Copyright (C) 1993-1996 id Software, Inc.
- Copyright (C) 2010-2013 Victor Epitropou (Velktron)
- Copyright (C) 2016-2017 Alexandre-Xavier Labonté-Lamoureux
- Copyright (C) 2017 Good Sign

Because Mocha Doom is GPLv3, DbrDoomMod as a whole is GPLv3. Anyone who receives
the jar is entitled to the corresponding source.

### Freedoom

The bundled game data is [Freedoom](https://freedoom.github.io/) Phase 1
(0.13.0), copyright © 2001-2024 Contributors to the Freedoom project,
distributed under a three-clause BSD licence. Its full licence text and
contributor credits are in `third-party/freedoom/`, and are also unpacked next to
the WAD so they travel with the binary as that licence requires.

Freedoom is an independent project. It is not affiliated with, nor endorsed by,
this mod or id Software. Thanks to everyone who has worked on it: without freely
licensed game data, a mod like this would only run for people who already own
a copy of Doom.
