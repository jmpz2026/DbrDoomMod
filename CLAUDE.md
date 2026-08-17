# DbrDoomMod

Doom running on the screen of a block, inside Minecraft 1.7.10. Forge, Java 8.

The Doom engine is [Mocha Doom](https://github.com/AXDOOMER/mochadoom), a pure
Java source port, vendored into this repo and driven frame by frame from a
background thread. Frames reach Minecraft as a texture.

## Build and run

```
./gradlew build       # jar into build/libs/
./gradlew runClient   # dev client, run dir is run/
```

`runClient` blocks the terminal for about two minutes before it backgrounds. If
you need to run anything else while the game is up, wait for it to background
**before** triggering whatever you are testing.

## The vendored engine: read this first

`src/main/java/com/dbr/doom/engine/` is 442 generated files. **Never edit
anything in there by hand.** It is produced by:

```
tools/vendor-mochadoom.sh [path-to-mochadoom-checkout]   # default ../DoomRefs/mochadoom
```

which wipes the directory and rebuilds it: copies the sources, rewrites the
package roots under `com.dbr.doom.engine`, applies a few mechanical
substitutions, and finally applies `tools/patches/*.patch`.

To change engine behaviour: edit the files, verify, then regenerate the patch by
diffing against a fresh run of the script with the patch step disabled. There is
no other way to make a change survive.

Upstream is GPLv3 and uses single-letter root packages (`m`, `p`, `s`, `v`, `w`,
`g`, `i`, `f`, `n`), which is why they are relocated: a `p.Something` on a modpack
classpath is asking for a collision.

## Architecture

Engine and rendering are **client only**. The block exists on both sides, so this
is a two-sided mod and the server needs it installed. Anything client-only lives
behind `CommonProxy` / `ClientProxy`.

One exception, and it is deliberate: `com.dbr.doom.verify` runs the engine
**server side**, headless, to replay a recorded run. See "Rewards" below.

```
BlockDoomArcade          right click -> server grants the claim
TileEntityDoomArcade     who is using it, server authoritative
  PacketOpenArcade       server -> the one client that won it
  PacketReleaseArcade    client -> server, session over
ServerTaskQueue          netty thread -> server thread, drained on tick end
ArcadeClaims             one cabinet per player, server side

DoomHost                 owns the engine and its thread; boots on that thread
  FrameBridge            handoff, 8-bit indices + palette, not pixels
DoomScreenTexture        the single texture; uploads once per rendered frame
TileEntityDoomArcadeRenderer   binds it and draws one quad per cabinet

GuiDoom                  full screen; input, audio ducking, teardown
GuiDoomImmersive         subclass that draws nothing so the world shows through
```

One engine per client, enforced by a static singleton inside the engine. Every
cabinet therefore shows the same game, which is why the texture upload is global
and a hundred cabinets cost what one does. **Never move that upload into the
renderer**, which Minecraft calls once per block per frame.

Each client simulates its own Doom, so only the player holding a cabinet sees a
picture on it. Sharing one would require simulating Doom on the server; the
engine has no network code at all.

A claim is granted by the server **before** the client is asked to play, so
every path in `ClientProxy.openArcade` that declines has to send
`PacketReleaseArcade` back. Forgetting one leaves a cabinet nobody can use until
the player logs out.

Nothing obliges a client to hand a claim back, which is why `ArcadeClaims`
grants a new one only by dropping the player's previous one: without it, a
hacked client could click every cabinet on a server and leave them all occupied.
For the same reason anything queued from a packet is charged to the sender, so a
client that floods the channel can only ever lose its own work - a single shared
limit meant one player's flood dropped everybody else's releases and left their
cabinets stuck.

A session ends through `GuiDoom.close()`, which sets a `closing` flag. Anything
else that takes the screen away - death, a container opened by the server, a
dropped connection - lands in `onGuiClosed()` without that flag and tears the
session down there. `mc.currentScreen` is useless for telling the two apart,
because Minecraft calls `onGuiClosed()` *before* it swaps the field.

`DoomHost.stop()` returns immediately and does the waiting on a thread of its
own; it is called from the client thread, where the engine's audio shutdown was
a visible freeze. That splits the two accessors, and mixing them up starts a
second engine on top of a dying one: **`getActive()` is null the moment a
session is asked to stop, while `start()` reads the raw field** and refuses
until the old thread is really gone.

## Things that cost real time to find

Minecraft 1.7.10, in rough order of how long they took:

- **Class initialisation deadlocks across threads.** This one froze the client
  hard, twice, with no exception and no crash report, and took several wrong
  guesses to pin down. The engine boots on its own thread and runs the static
  initialisers of `event_t`, `ScanCode` and friends. A class being initialised is
  locked by the thread doing it; any other thread that touches it blocks. Moving
  the mouse during that second was enough: the render thread hit
  `new event_t.mouseevent_t(...)` and stopped dead.
  `GuiDoom.engineReady()` guards it, and **the guard must come before the engine
  is named**. A null check inside `post()` did nothing, because Java evaluates the
  argument first, so the class was already initialising. Never mention an engine
  type in an input path without checking readiness above it.
  Diagnosed from a thread dump: `tools/dump-hang.sh` while the game is still
  frozen, before closing anything. It warns when it caught the wrong moment, which
  matters — a dump with no session running looks just as convincing and proves
  nothing. Reading one of those nearly produced a confident, wrong answer.
- **A TESR quad renders black** unless you force the lightmap. `GL_LIGHTING` is
  not what lights the world; a second texture unit is. See
  `TileEntityDoomArcadeRenderer`, and note `glPopAttrib` does not restore it.
- **`renderViewEntity` is drawn at `posY - (yOffset - 1.62)`**, a leftover of the
  old convention where `posY` was eye level. A custom camera entity needs
  `yOffset = 1.62F` or the view floats a block and a half too high.
  `getEyeHeight()` is not consulted.
- **Packet handlers run on a netty thread**, and 1.7.10 gives you nowhere to put
  the work. The client has `Minecraft.func_152344_a(Runnable)`; `MinecraftServer`
  has no equivalent, because its task queue only arrives in 1.8 with
  `IThreadListener`. Hence `ServerTaskQueue`, drained at the end of a server
  tick. Anything in a handler that touches the world has to go through it.
- **`@Mod` has no `clientSideOnly`** in 1.7.10.
- **Command lookup is case sensitive.** Register lowercase aliases or nobody can
  type your command.
- **A command cannot open a GUI.** `GuiChat` closes the screen right after
  dispatching, taking yours with it. Defer a tick; see `ClientTickHandler`.
- **`BlockContainer` does not override `getRenderType()`** — the block renders
  normally. Do not "fix" this.
- **`Minecraft.func_152344_a`** has no MCP name in 1.7.10 mappings.
- **Gradle 8 rejects the two-`from` `processResources` idiom** that older 1.7.10
  build scripts use. Use `filesMatching`.
- **`reobf` dies with a bare `IllegalArgumentException`** if a third-party
  library is inside the jar when it runs. SpecialSource cannot cope. Embedded
  libraries are therefore injected by the `embedLibraries` task, which runs
  *after* reobfuscation. This is also correct on its own terms: they contain no
  Minecraft references, so there is nothing to remap.
- **`compile.extendsFrom` no longer feeds the compile classpath** on Gradle 8.
  The dependency resolves and downloads, then the compile fails on a missing
  package. Extend `implementation`.

## Rewards: verifying what a player actually did

Rewards are paid on a **replay of the run**, never on anything the client says
about itself. The client records a demo, the server replays it and pays for what
comes out. `com.dbr.doom.verify.RunVerifier` is the replay half;
`tools/spike/README.md` is the evidence that this works at all.

- **Replay is deterministic, and that is measured, not assumed.** 32 replays
  across two session lengths came out identical byte for byte and matched the
  recording, with kills, random damage, item pickups and a death in them.
  Roughly **660x real time**: a ten minute map verifies in about a second.
- **The verifier ships in the mod jar, though the reward logic lives in a Bukkit
  plugin.** It has to run against exactly the engine that recorded the demo. A
  verifier built against a different Mocha Doom would desync silently and pay
  wrong amounts with nothing in any log. Travelling in the same jar makes drift
  impossible.
- **The plugin loads it through a `URLClassLoader` with a null parent.** That
  works because `com.dbr.doom.engine.**` references nothing outside itself but
  `DoomExitException`, and `com.dbr.doom.verify` adds only the JDK. Everything
  crossing the boundary is a String or a `byte[]`, which are bootstrap types.
  A fresh classloader also means a fresh `Engine.instance`, so the engine's
  singleton stops being an obstacle and becomes the isolation mechanism:
  **verified working with four concurrent verifications**.
- **Never silence the engine with `System.setOut`.** Those fields are global to
  the JVM, not per classloader. Two verifications at once race to restore them
  and the loser leaves the server's stdout pointing at a dead buffer,
  permanently. Filter by thread name instead, the way `EngineOutputRouter`
  already does; `RunVerifier` renames its thread to `DbrDoom-Verify` for exactly
  this.
- **Recording hooks `DoNewGame`, not `InitNew`.** `InitNew` has two other
  callers and neither may be recorded. `DoPlayDemo` uses it for the attract-mode
  demos on the title screen, which produced a demo of the engine playing itself
  on whatever map the attract demo used. `DoLoadGame` uses it for a blank level
  it then overwrites from the savegame, and a demo recorded from there **cannot
  reproduce** - a replay starts the level fresh and applies the tics to a world
  that never had the savegame in it. It is also the obvious exploit: save in
  front of a boss, load, kill it, repeat.
- **A run started by `dbrStartRun()` has a null `demoname`, and
  `CheckDemoStatus` has to check for it.** Without that the engine writes to a
  null path, fails, and reports the failure through `I_Error` - which is a
  `DoomExitException`, so anything reaching `CheckDemoStatus` mid-session kills
  it. The attract-mode demo loop does exactly that.
- **`-timedemo`, not `-fastdemo`.** Only `-timedemo` sets `singletics`, which
  unhooks the loop from the 35Hz clock. Despite the name, `-fastdemo` replays at
  real time: 41 seconds to check a 40 second demo.
- **A demo that never reaches a level is a failure, not a run worth nothing.**
  Corrupt uploads, demos from another engine version and demos for maps this WAD
  no longer has all look identical otherwise, and all of them are worth knowing
  about.
- **A finished run outlives the session that made it, so its queue is static.**
  The most valuable run of all - everything the player did before leaving - is
  only collected as the Doom thread winds down, which is *after* `stop()`. By
  then `getActive()` returns null by design, so anything that drains through it
  drops exactly the run that mattered. The first live test recorded 5714 bytes,
  uploaded nothing and paid nothing, for precisely this reason. `getActive()` is
  the wrong question for anything that happens on the way out.
- **`MAPEND` works, confirmed on a live server** on 16 August 2026. It was the
  last event never exercised - scripting a bot to an exit is not practical, so
  it took a real player. A run finishing E1M1 verified in 339ms and paid
  `mapComplete` and `firstClear`, with the amounts multiplied by the skill it
  was played on. The prefix rule held in the same test: the second upload
  reported the same 14 kills as the first and paid nothing for them again.

## Engine quirks worth knowing

- **`System.exit()` in 18 places.** Rewritten to `DoomExitException`, which
  extends `Error` on purpose: the engine is full of `catch (Exception)` that
  would swallow a RuntimeException and carry on in a state it had already
  declared unrecoverable.
- **Audio shutdown is commented out upstream** in `DoomSystem.Quit()`. Nothing
  stops the MIDI sequencer, so music outlives the session and every restart
  layers another copy on top. `DoomHost.shutdownAudio()` does it explicitly.
- **`ClipSFXModule.ShutdownSound()` busy-waits with no timeout.** Patched, and
  called on the Doom thread — never the client thread, or Minecraft freezes.
- **`TryRunTics()` spins without sleeping**, which pegs a core. Patched.
- The engine boots on its own thread, so `DoomHost.getDoom()` and
  `getFrameBridge()` are **null for about a second**. Anything that assumes "a
  session exists, therefore an engine exists" is a latent NPE.
- Input handlers are wrapped in `guard()`. Minecraft calls them from `runTick()`,
  where an exception is a crash report and an exit; a broken Doom session must
  never take the game down.

## Legal

GPLv3, because Mocha Doom is. Headers on every file, `LICENSE` shipped inside the
jar.

Bundled game data is **Freedoom** (three-clause BSD). Its `COPYING` and `CREDITS`
are packed next to the WADs deliberately: the licence requires them to travel
with the binary, not merely sit in the repo.

**`DOOM.WAD` and `DOOM2.WAD` are proprietary and are never bundled.** Players
drop their own into `config/dbrdoom/wads/`.

## Open problems

- **Stray `3` and `4` on stderr** from the engine, a few seconds into a session.
  Not string literals anywhere in the source. Harmless, unexplained.
- **Two players: partly exercised.** On 16 August 2026 two dev clients ran
  against one LAN world (`run/` and `run2/`, different `--username`, launched as
  plain `java -cp ... GradleStart` rather than through Gradle, which locks the
  project while `runClient` is up). Confirmed: mutual exclusion in both
  directions, a cabinet freed by F10 claimable by the other player a second
  later, and two simultaneous sessions on separate cabinets. Still unexercised:
  a player who crashes or logs out mid-session, which relies on the
  `updateEntity` poll rather than on any packet, and the case where the screen is
  taken away by something other than the player - the `onGuiClosed` path has
  never actually run.

## Working notes

- Version lives in **two** places, `gradle.properties` and `DbrDoomMod.VERSION`.
  They have already drifted once and shipped a jar two versions behind in its
  filename. Change both.
- Textures are generated: `tools/GenerateTextures.java`. Regenerate rather than
  hand-edit, or the next run overwrites you.
- **Game data is the only size lever that matters.** The mod itself is 1.3MB;
  everything else is the WAD. Two cuts are already applied:
  `tools/strip-wad.py` keeps only Episode 1 (27 of 36 maps dropped),
  `tools/prune-wad.py` then removes the artwork and music those maps took with
  them, and the result is stored as `.xz`, since LZMA2 beats zip's deflate by
  about 30% on WAD data. Pruning rebuilds TEXTURE1, **TEXTURE2** and PNAMES:
  both tables share one PNAMES, so renumbering for one and not the other
  silently puts the wrong artwork on the wrong walls. It reads the switch and
  animation tables out of the engine source, because `P_InitSwitchList` resolves
  those by name with `TextureNumForName`, which errors rather than returning -1.
  Always validate a pruned WAD before shipping: a missing patch kills the engine,
  a misnumbered one corrupts silently. Anything ending in `.xz` under
  `src/main/resources/assets/dbrdoom/wads/` is decompressed on unpack and the
  suffix stripped. `xz -9e` measured slightly *worse* than `-9` here.
  Cutting below a full episode is not worth it: three maps rather than nine saved
  only 0.4MB, because REJECT and BLOCKMAP are mostly zeroes and compress away.
  Only maps can be dropped safely. The engine wants a complete asset set and dies
  looking for whatever is missing.
- The bundled WAD is a **modified** Freedoom. `MODIFICATIONS-freedoom.txt` ships
  next to it saying so; the BSD licence allows the change but the file must not
  pretend to be the original release.
- Diagnostic logging in this project is deliberately one-shot or throttled. At
  35 frames a second, per cabinet, anything else buries the log. Throttling has
  to be **per cabinet**, not per renderer: one renderer instance serves every
  block, and a single timestamp let the nearest cabinet starve the others, so a
  two-cabinet test logged only the idle one.
- A cabinet's occupant is identified by **UUID, with the name as a fallback**.
  The name is still stored, because it is what a player is shown, and still
  compared, because a client whose profile carries no UUID would otherwise never
  match its own cabinet and would draw an idle screen while playing.
- **Python's text mode rewrites LF as CRLF on Windows.** Editing a vendored
  engine file that way turns every line into a change, and the patch went from
  263 lines to 3986 for a fifteen line edit. Always `open(..., newline='')`.
  Check the byte offset with `cmp` rather than trusting a `grep` for ``; that
  measurement lied and cost a detour.
- **The engine identifies an IWAD by its file name**, through a hardcoded enum in
  `defines/DoomVersion`. `freedoom1.wad` is recognised, `freedoom1-ep1.wad` is
  not, and the game mode silently ends up null. Do not rename the bundled WAD.
  Game mode then decides the episode count, and no mode maps to a single
  episode, so `m/Menu` is patched to trim the list to episodes whose first map
  actually exists.
- When piping a heredoc into `python` from bash, `\n` inside the heredoc is eaten
  by the shell before Python sees it, and Java string literals end up split across
  real newlines. Quote the delimiter (`<<'EOF'`) and escape as `\\n`, or use the
  editor instead. This has broken the build twice.
