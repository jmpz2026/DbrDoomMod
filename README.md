# DbrDoomMod

Play Doom inside Minecraft 1.7.10. Right click an arcade cabinet and Doom runs
on its screen while the world carries on around you.

The mod must be installed on **both the client and the server**. The block has to
exist on both sides; the Doom engine itself only ever runs on a client.

## The arcade cabinet

A solid block with a screen on the face you were looking at when you placed it.

**There is no recipe.** A cabinet is placed by staff, not crafted: it is a
fixture of an arcade somebody built, and a craftable one ends up in every base.
It sits in the Redstone creative tab, so an admin gets one from there or with:

```
/give <player> dbrdoom:doom_arcade
```

Once placed it behaves like any other block, so if players should not be able to
mine it and take it away, protect the area the way you would protect any build.

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
are **never** bundled here.

**This is the only WAD the mod plays.** Your own WADs are not loaded, and a
`freedoom1.wad` that has been swapped for something else is restored on the next
launch. That is not tidiness: rewards are paid by replaying your run on the
server against the server's copy of the data, so a client playing something else
records a run that cannot reproduce — and one playing an edited copy, with
weaker monsters or ammo in convenient places, records a run that reproduces into
a game it did not play. Anything else you leave in the folder is ignored, not
deleted.

Game data was around ninety percent of the download, so it is cut three ways: 27
of Freedoom's 36 maps are dropped, then the textures, patches, flats and music
those maps took with them, and what remains is stored as LZMA2 rather than a
plain zip entry. Together that takes the jar from 23MB to about 5MB.

The whole episode is kept rather than a few maps: map data compresses so well
that cutting to three would have saved another 0.4MB and lost the ending.

`MODIFICATIONS-freedoom.txt`, unpacked next to the WAD, records exactly what was
removed, as Freedoom's licence requires.

## Layout

```
config/dbrdoom.cfg   <- this mod's settings
config/dbrdoom/
  wads/              <- the bundled Freedoom, checked on every launch
  saves/             <- Doom savegames (see below)
  default.cfg        <- Doom's own settings, written by its options menu
  mochadoom.cfg      <- engine settings
```

### Saving and loading

Doom's own save menu works, six slots, in `config/dbrdoom/saves/`. They are your
files and never leave your machine.

One thing worth knowing if the server pays for playing: **loading a save stops
the recording**. What you played up to that point still counts and is handed in
as normal, but nothing after the load is recorded, because a run that continues
past a load cannot be replayed — the server would be re-running your keypresses
against a game your savegame never touched. Start a new game to record again.

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
