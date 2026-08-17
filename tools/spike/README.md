# Phase 0 spike: is demo verification viable?

Throwaway harness, kept because it is the evidence behind a design decision.
Not part of the mod build and not shipped.

The rewards system is server-authoritative by **demo verification**: the client
records a `.lmp`, the server replays it, and rewards are paid on what the replay
produces rather than on anything the client claims. That only works if two
things hold, and both are checked here.

## What it proves

**1. A replay reproduces the session exactly.**
20 replays of a 40 second session and 12 of a 200 second one came out identical
byte for byte, and matched the recording. The sessions include kills, random
damage, item pickups and the player dying, so the RNG and the monster AI are
both exercised. Comparison is on position, angle and health as well as the
counters: a desync moves the player long before it changes a kill count, so
checking only kills would call a broken replay identical.

**2. The engine loads with no Forge and no Minecraft.**
`Spike` runs each engine in a `URLClassLoader` whose **parent is null** — the
bootstrap loader and nothing else. This is how the Bukkit plugin would run the
verifier. It works because `com.dbr.doom.engine.**` references nothing outside
itself but `DoomExitException`. A fresh classloader also gets a fresh copy of
the engine's static `Engine.instance`, so the singleton stops being an obstacle
and becomes the isolation mechanism: N classloaders, N parallel verifiers.

Replaying 7000 tics takes about 300ms, roughly 660x real time. A ten minute map
verifies in under a second.

## What it also proves, later

**3. A segment that starts mid-playthrough replays exactly.**
`SegmentSpike` records a session that crosses a map boundary, cuts it the way
the mod now does, and replays the second segment on its own. Map two is entered
carrying map one's weapons, ammo, health and random index, while a demo header
says only "episode 1, map 2, skill 3" - so this is the whole question behind
recording per map instead of re-uploading a longer prefix every time.

Measured: 896 and 2096 tic segments, 2 to 6 replays each, agreeing with the
recording on **every aligned tic** - position, angle, health, ammo, kills - and
identical to each other. Both runs carried something real: 76 health and 24
rounds in one, a death at the exit in the other.

It earned its keep immediately. The first version restored the player's state
unconditionally, including a player who reached the exit dead - and the engine
reborns those on the next map. 2096 tics compared, 2096 different. That is a bug
that pays wrong amounts silently and no amount of reading the code had caught it.

```
javac -nowarn -cp ../../build/libs/dbrdoom-0.3.1.jar -d out SegmentSpike.java SpikeRunner.java
java -cp out SegmentSpike ../../build/libs/dbrdoom-0.3.1.jar out \
    ../../run/config/dbrdoom/wads/freedoom1.wad segwork [replays] [ticsPerMap]
```

The exit is reached by calling `ExitLevel()`, which is what the exit line calls:
scripting a bot to a real switch is not practical. The comparison is keyed on
`leveltime`, not `gametic` - the recording has already played a map, so its
gametic is a thousand further along and comparing on it would call every tic a
disagreement.

The recording's first few tics are missing from its own timeline, because a live
engine runs tics in batches inside one frame while a `-timedemo` replay runs
exactly one. That is not a hole: a desync does not heal, so a carried state that
was wrong on tic one disagrees on every tic after it too.

## Running it

```
javac -nowarn -cp ../../build/libs/dbrdoom-0.3.1.jar -d out SpikeRunner.java Spike.java
java -cp out Spike ../../build/libs/dbrdoom-0.3.1.jar out \
    ../../run/config/dbrdoom/wads/freedoom1.wad work [replays] [tics]
```

Recording runs at Doom's 35Hz, so `tics` is the wall clock cost: 7000 tics is
about three and a half minutes. Replays are the fast part.

`out/` and `work/` are scratch and gitignored.

## Two traps, both of which produced a wrong answer first

**`-timedemo`, not `-fastdemo`.** Only `-timedemo` sets `singletics`, which is
what unhooks the loop from the 35Hz clock. `-fastdemo`, despite the name,
replayed at real time: 41 seconds to check a 40 second demo, which no server can
pay per completed map.

**Forward is bound to `SC_W`, not the up arrow.** A script that posted `SC_UP`
moved nobody, so the recording was 1400 tics of standing still — which replays
identically for reasons that say nothing about determinism, and reported **GO**.
`SpikeRunner` now derives scancodes from `doom.key_up` / `key_fire` /
`key_right`, and `Spike` refuses to reach a verdict unless the recorded session
registered kills. A harness that can only pass is not a harness.
