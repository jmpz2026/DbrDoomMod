# The `DBRDOOM` channel

What the mod's client sends and expects, so the Bukkit plugin can implement the
other half. Written down rather than shared as a jar on purpose: mod and plugin
have no classes in common, only these constants, and they are duplicated by hand
on each side. **Keep them in step.**

Mod side: `com.dbr.doom.client.reward.RewardChannel`.

## Why a second channel

`com.dbr.doom.network.DoomNetwork` is mod to mod, over Forge's
`SimpleNetworkWrapper`, and settles who is allowed to use which cabinet. Rewards
are decided by a Bukkit plugin, which cannot see a Forge message at all. Plugin
messaging is the one thing both sides speak.

The two channels are separate and stay separate. Nothing about rewards is on the
path that lets a player play, and the mod works normally with no plugin
listening.

## Registration

Channel name `DBRDOOM`, protocol version `3`.

The plugin learns a client has the mod from `PlayerRegisterChannelEvent`, the
same way `DbrPetsPlugin` does — the mod registers the channel at startup whether
or not anything answers.

## Client to plugin

Every message starts with one byte of type. Fields are Java `DataOutputStream`
order: big endian, `writeUTF` for strings.

| Type | Name | Payload |
|------|------|---------|
| `0x20` | `HELLO` | `int` protocol, `UTF` mod version, `UTF` wad sha-256 |
| `0x21` | `RUN_BEGIN` | `long` runId, `int` serial, `int` segment, `int` compressedLength, `int` chunks, `int` rawLength |
| `0x22` | `RUN_CHUNK` | `long` runId, `int` index, `int` length, `length` bytes |
| `0x23` | `RUN_END` | `long` runId |

`HELLO` is sent when a session starts, not on join, because that is when the WAD
is known. The hash is what the plugin checks a run against: **only runs on a WAD
the server also has can be paid**, since paying means replaying.

`runId` is per client and increments from 1 per Minecraft launch. It is not
unique across players — key by `(uuid, runId)`.

`serial` is the playthrough and `segment` is which map of it, from 0. **Segments
are disjoint and consecutive**, so a server adds them up. They used to be
prefixes — every upload the whole recording so far — and the accounting that
went with that was high-water marks per playthrough. Both numbers are also
inside the payload; they are on the wire because the clock has to be checked
before anything is decompressed.

## Plugin to client

| Type | Name | Payload |
|------|------|---------|
| `0x01` | `CAPS` | rewards are enabled; the client notes it and says so in the log |
| `0x02` | `MESSAGE` | `UTF` text, shown in chat verbatim |

An unknown type is ignored rather than treated as an error, so a newer plugin
can talk to an older mod.

## The payload

A run is a vanilla Doom demo — a 13 byte header, then four bytes per tic, then
an end marker — inside an envelope that carries what the demo cannot say.

A demo replays from a level start with a pistol, and the second map of a
playthrough is entered carrying the first one's weapons, ammo, health and random
index. So the envelope holds that state, the verifier installs it before the
level is built, and a run covers one map instead of everything so far. See
`com.dbr.doom.host.RunFormat` for the layout and the reasoning; nothing between
here and the verifier interprets the state, so adding a field to it changes
nothing on the wire.

Doom runs at 35 tics a second, so a ten minute map is about 84KB.

Before sending, the client:

1. **Deflates** it — `java.util.zip.Deflater`, `BEST_COMPRESSION`, zlib wrapper
   (the default; not `nowrap`). Measured at **82% smaller** on a real demo:
   28482 bytes down to 5170. The same command repeats for as long as a key is
   held, so demos compress extremely well.
2. **Splits** it into chunks of at most **16384** bytes.
3. Sends `RUN_BEGIN`, then **one chunk per client tick**, then `RUN_END`.

One chunk per tick is deliberate. The whole run could go in a single tick and
should not: it would stall everything else the player is doing to deliver
something nobody is waiting for. At 20 ticks a second this is still finished in
well under a second for a typical run, and most runs are a single chunk anyway.

### Reassembling

Place each chunk at `index * 16384`, **by its index and not by arrival order**.
They cannot overtake each other on one connection today, but a reassembler that
relies on that is a bug waiting for a laggy client, and `tools/spike/RoundTrip`
shuffles them specifically to catch one.

Then inflate, and the result is the demo to hand to the verifier.

### What the plugin must bound

Everything here arrives from a client, which may send whatever it likes:

- `compressedLength` and `chunks` must agree with each other and with 16384.
- Refuse a `RUN_BEGIN` whose `rawLength` is larger than a run could plausibly be.
- Cap concurrent and total in-flight uploads **per player**, and charge dropped
  work to the sender. `ServerTaskQueue` in the mod has the same rule and the
  reason written down: one shared limit means one player's flood costs everybody
  else theirs.
- Inflating is where a zip bomb would land. Cap the output, do not trust
  `rawLength`.
- **Nothing whose size the client chose may run on the server thread.**
  Inflating and hashing are both sized by the upload, and a client may ask for
  them once a second. They belong on the same pool the replay does; only
  reassembly stays in the tick, and that is a fixed-size copy.

## Verifying

Load the verifier out of the mod jar, in a classloader of its own:

```java
URLClassLoader loader = new URLClassLoader(new URL[] { modJar.toURI().toURL() }, null);
Class<?> c = loader.loadClass("com.dbr.doom.verify.RunVerifier");
String report = (String) c.getMethod("verify", byte[].class, String.class, String.class)
    .invoke(null, demoBytes, iwadPath, workDirPath);
loader.close();
```

**Parent `null` is not a typo.** It means the bootstrap loader and nothing else,
which is what keeps Forge and Bukkit out of the engine's way. It works because
`com.dbr.doom.engine.**` references nothing outside itself but
`DoomExitException`, and `com.dbr.doom.verify` adds only the JDK.

A fresh classloader also means a fresh `Engine.instance`, the engine's static
singleton — which is why several verifications can run at once. Four concurrent
ones were measured producing identical reports.

Each concurrent verification needs a **work directory of its own**.

Roughly 660x real time: a ten minute map verifies in about a second.

### Do not silence it with `System.setOut`

The engine narrates its startup, a few hundred lines every time, and there is no
way to ask it not to. `System.out` and `System.err` are global to the JVM and
**not** per classloader: two verifications at once race to restore them, and the
loser leaves the server's stdout pointing at a dead buffer, permanently. That is
not hypothetical, it is what four concurrent verifications did on the first
attempt.

`RunVerifier` renames its thread to `DbrDoom-Verify` for the duration. Install
one filter for the whole process that drops output from that thread, the way the
mod's own `EngineOutputRouter` does for the client.

### The report

One event per line, a keyword then `key=value` pairs. Tics are the engine's own
clock at 35 a second, from the start of the replay.

```
MAPSTART tic=1 ep=1 map=1 skill=2
KILL tic=340 n=1
ITEM tic=502 n=1
SECRET tic=1180 n=1
DEATH tic=6800 ep=1 map=6
MAPEND tic=8134 ep=1 map=1 kills=27 items=14 secrets=2 maxkills=31 maxitems=18 maxsecrets=3 time=8073
END tics=8134 truncated=false
```

`KILL`, `ITEM` and `SECRET` carry `n`, the number **since the last event of that
kind**, not a running total. Counters reset at `MAPSTART`.

`DEATH` is edge triggered — once per death, not once per tic spent dead.

`MAPEND` carries the maxima because "cleared it completely" is not something the
reward side can work out on its own.

A failure is a single line and pays nothing:

```
ERROR reason=the demo never started a level
```

That is different from a run worth nothing. It means the server could not
establish what happened, which is what a corrupt demo, a demo from another engine
version, and a demo for a map this WAD does not have all produce. Worth logging;
a client producing them is either broken or trying something.

## What stops a fabricated run

The replay settles what happened, but not *when*, so the plugin still has to:

- **Refuse a segment that has already been paid for**, by `(serial, segment)`,
  before the ledger is even asked. The hash catches a resend for good; this
  catches it for free, and without it two copies arriving together are two
  payments.
- **Keep a ledger of demo hashes.** SHA-256 the demo, persist it, never pay
  twice for the same one. The ledger is written after the replay and read before
  it, so also hold the hash for the duration, or two copies of one demo arriving
  together both find the ledger empty.
- **Compare tics against the wall clock.** A run of `N` tics needs at least
  `N/35` seconds of real time to have been played. A demo of three minutes handed
  over twenty seconds after the player sat down was not played on this server.
  This is the wall that makes the rest hold: a tool-assisted run only pays if the
  player also spent the real time, in which case it has gained them nothing over
  playing.
- **Anchor that clock to something the client did not choose.** `HELLO` is a
  packet, so measuring from it means a client that says hello at login and goes
  away accrues an hour of budget it never spent at a machine. The server sees the
  right click on the cabinet; require `HELLO` to follow one. Without this the
  wall reduces to "was connected", which is not a wall.
- **Bound the replay before running it, not after.** A demo is four bytes a tic,
  so its length is a ceiling on how long it claims to be, for the price of a
  subtraction. Checking the clock only after verifying means a run that was
  always going to be refused still costs a full replay, and the verifier will
  replay up to twelve hours of game time - a minute of CPU per upload, once a
  second, for a client sending rubbish.
- **Cap in-flight verifications per player, not just in total.** A shared queue
  with no per-player limit means one client can fill it and everybody else's runs
  are refused. Same rule as `ServerTaskQueue`, one layer up.
- **Refuse runs on a WAD hash it does not have, and refuse by default.** Without
  the same data the replay cannot reproduce the session. "No allowlist configured"
  must mean nothing is paid: a desynchronised replay does not reliably stop, it
  reports what the wrong monsters in the wrong places did.

  The allowlist need not be configured at all, though. The server holds the WAD
  it replays against, so it can hash that and accept it — which is by
  construction the only data a run can be checked with. `DbrDoomPlugin` does
  this at startup and keeps the configured list as an override; asking an
  administrator to paste sixty-four characters was its most expensive setup
  step and its quietest failure.

## Exercised on a live server

Every event including `MAPEND`, on 16 August 2026. It was the last one never
tested — scripting a bot to an exit is not practical, so it took a real player.

A session on Crucible produced two uploads of one playthrough:

```
3676 tics, 14 kills, 0 maps  -> 358ms  -> everyN milestone
4098 tics, 14 kills, 1 map   -> 339ms  -> mapComplete + firstClear
```

Three things that had only ever been argued for on paper are visible in that:
the second upload reports the same 14 kills and pays nothing more for them, the
amounts come out multiplied by the skill the playthrough was started on, and a
map completion pays `mapComplete` and `firstClear` and nothing else.

`PlayerInteractEvent` does fire for a modded block on Crucible, so the cabinet
click is a usable anchor for the clock and the plugin does not fall back to
believing `HELLO` on its own.
