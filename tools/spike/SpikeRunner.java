/*
 * DbrDoomMod - phase 0 spike. Not shipped, not part of the mod build.
 *
 * Runs INSIDE an isolated URLClassLoader over the mod jar, so it may reference
 * engine types directly. Everything it hands back crosses the classloader
 * boundary as a String, which is a bootstrap type and therefore shared.
 *
 * Two modes:
 *   record <iwad> <demoBase> <workDir> <tics>   plays scripted input, writes a .lmp
 *   replay <iwad> <demoBase> <workDir>          replays it, samples every tic
 *
 * The point is to find out whether a demo recorded by a client replays to
 * exactly the same state on a server. If it does not, server-authoritative
 * rewards by demo verification are impossible and the whole design changes.
 */

import com.dbr.doom.engine.defines.gamestate_t;
import com.dbr.doom.engine.defines.skill_t;
import com.dbr.doom.engine.doom.ConfigBase;
import com.dbr.doom.engine.doom.DoomMain;
import com.dbr.doom.engine.doom.event_t;
import com.dbr.doom.engine.doom.evtype_t;
import com.dbr.doom.engine.doom.player_t;
import com.dbr.doom.engine.g.Signals.ScanCode;
import com.dbr.doom.engine.mochadoom.Engine;
import com.dbr.doom.engine.p.mobj_t;

import java.io.File;
import java.io.FileOutputStream;

public final class SpikeRunner {

    private SpikeRunner() {
    }

    /**
     * Entry point reached by reflection from the outer harness.
     *
     * @return one line of {@code key=value} pairs describing the final state,
     *         or a line starting with {@code ERROR} if the run fell over
     */
    public static String run(String[] args) {
        try {
            return dispatch(args);
        } catch (Throwable t) {
            // Includes DoomExitException, which extends Error: the engine's
            // System.exit() calls are rewritten to it, and both a finished demo
            // and a finished recording go out that way.
            return "ERROR " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static String dispatch(String[] args) throws Exception {
        final String mode = args[0];
        final String iwad = args[1];
        final String demoBase = args[2];
        final String workDir = args[3];

        /*
         * The engine reads and writes default.cfg here. Every run has to see
         * the same one, or a setting that changes behaviour would show up as a
         * desync that is really just configuration drift.
         */
        ConfigBase.Files.setFolder(workDir + File.separator);

        if ("record".equals(mode)) {
            return record(iwad, demoBase, Integer.parseInt(args[4]));
        }
        if ("record-ingame".equals(mode)) {
            return recordInGame(iwad, demoBase, Integer.parseInt(args[4]));
        }
        if ("record-segments".equals(mode)) {
            return recordSegments(iwad, demoBase, Integer.parseInt(args[4]));
        }
        if ("replay-segment".equals(mode)) {
            return replaySegment(iwad, demoBase);
        }
        if ("texcolumn".equals(mode)) {
            return texColumn(iwad, args[4]);
        }
        if ("idle".equals(mode)) {
            return idle(iwad, Integer.parseInt(args[4]));
        }
        return replay(iwad, demoBase);
    }

    // ------------------------------------------------------------------ idle

    /**
     * Boots and sits on the title screen, touching nothing.
     *
     * This is a player who opens a cabinet and reads the menu for a minute. The
     * engine fills that time with attract-mode demos, and those demos are on
     * maps our WAD no longer has.
     */
    private static String idle(String iwad, int tics) throws Exception {
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        while (doom.gametic < tics) {
            doom.runOneFrame();
        }
        return "survived " + doom.gametic + " tics on the title screen";
    }

    // -------------------------------------------------------- record in game

    /**
     * Records the way the mod will: no {@code -record} on the command line, the
     * game started from inside a running session, the demo handed back as bytes.
     *
     * This is the path that matters. The plain {@code record} mode above proves
     * the engine is deterministic; this proves the mechanism the mod will
     * actually use produces a demo that reproduces the same session.
     */
    private static String recordInGame(String iwad, String demoBase, int tics)
            throws Exception {
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        // Every game the player starts from here on is recorded.
        doom.dbrAutoRecord = true;

        /*
         * Let the engine settle on the title screen first, the way a player
         * does. Asking for a game before the first frame loses it: the attract
         * demo the engine already had queued runs instead.
         */
        for (int i = 0; i < 60; i++) {
            doom.runOneFrame();
        }

        /*
         * Start a game the way the menu does, rather than with -warp. This is
         * the case upstream cannot record at all: it begins recording once at
         * launch, with whatever map was current then.
         */
        doom.DeferedInitNew(skill_t.sk_medium, 1, 1);

        String state = "";
        final int base = doom.gametic;
        final int until = base + tics;
        while (doom.gametic < until) {
            doom.runOneFrame();
            script(doom, doom.gametic - base);
            if (doom.gamestate == gamestate_t.GS_LEVEL) {
                state = sample(doom);
            }
        }

        final byte[] demo = doom.dbrFinishRun();
        if (demo == null) {
            return "ERROR dbrFinishRun returned null: nothing was recording";
        }

        /*
         * Written here only so the replay half can load it with -timedemo. In
         * production these bytes go up the wire and never touch the client's
         * disk at all.
         */
        final FileOutputStream out = new FileOutputStream(demoBase + ".lmp");
        try {
            out.write(demo);
        } finally {
            out.close();
        }

        return state;
    }

    // -------------------------------------------------------------- textures

    /**
     * Asks the engine for the columns of one texture, as the wall renderer gets
     * them.
     *
     * A texture whose single patch does not cover its full height has holes, and
     * a hole on a solid wall is the classic tutti-frutti: the renderer reads the
     * patch column raw, so the post headers land on the wall as pixels. This
     * prints what the engine would actually draw, so "is that stripe the WAD or
     * the engine" stops being a matter of opinion.
     *
     * Read GetCachedColumn and nothing else. That one returns a flat column of
     * height pixels, so a zero really is a hole drawn black and anything else in
     * the empty part of a texture really is garbage. GetColumn returns the raw
     * post data - headers included - for the masked renderer to walk itself, and
     * reading that as pixels makes every texture look corrupt. Measuring it by
     * mistake made a fix look necessary, and then made it look effective.
     */
    private static String texColumn(String iwad, String texture) throws Exception {
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        final int num = doom.textureManager.CheckTextureNumForName(texture);
        if (num < 0) {
            return "ERROR " + texture + " is not in this WAD";
        }

        final StringBuilder out = new StringBuilder(texture + " tex=" + num);

        int columnsWithJunk = 0;
        int sampled = 0;
        String sample = "";

        for (int col = 0; col < 64; col += 8) {
            /*
             * GetCachedColumn, not GetColumn: walls take this path, and the
             * masked one has its own composite. Measuring the wrong one made a
             * fix look like it had done nothing.
             */
            final Object column = doom.textureManager.GetCachedColumn(num, col);
            if (!(column instanceof byte[])) {
                return "ERROR the engine returned " + (column == null ? "null"
                    : column.getClass().getName());
            }
            final byte[] pixels = (byte[]) column;
            sampled++;

            /*
             * The top of these textures is the empty part - the artwork sits at
             * the bottom - so that is where a hole shows up.
             */
            int nonZero = 0;
            for (int y = 0; y < Math.min(64, pixels.length); y++) {
                if (pixels[y] != 0) {
                    nonZero++;
                }
            }
            if (nonZero > 0) {
                columnsWithJunk++;
                if (sample.isEmpty()) {
                    final StringBuilder first = new StringBuilder();
                    for (int y = 0; y < Math.min(12, pixels.length); y++) {
                        first.append(pixels[y] & 0xFF).append(' ');
                    }
                    sample = first.toString().trim();
                }
            }
        }

        out.append(" columns=").append(sampled)
           .append(" withJunkOnTop=").append(columnsWithJunk)
           .append(" firstPixels=[").append(sample).append(']');
        return out.toString();
    }

    // -------------------------------------------------------------- segments

    /**
     * Records a playthrough that crosses a map boundary, and reports the second
     * map tic by tic.
     *
     * This is the case a demo cannot describe on its own. Map two is entered
     * carrying map one's weapons, ammo, health and random index, while a demo
     * header says only "episode 1, map 2, skill 3" - so a segment starting there
     * would replay from a pistol, a full clip and a cleared random index, and
     * diverge on the first monster to act.
     *
     * What the engine now carries beside the demo is meant to close exactly that
     * gap. Whether it does is not arguable from the code; it is this.
     */
    private static String recordSegments(String iwad, String demoBase, int tics)
            throws Exception {
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        doom.dbrAutoRecord = true;

        for (int i = 0; i < 60; i++) {
            doom.runOneFrame();
        }

        doom.DeferedInitNew(skill_t.sk_medium, 1, 1);

        /*
         * Map one, played for real: the state carried into map two has to be
         * worth carrying, or this proves nothing.
         */
        final int base = doom.gametic;
        final int until = base + tics;
        while (doom.gametic < until) {
            doom.runOneFrame();
            script(doom, doom.gametic - base);
        }

        final String carried = carried(doom);

        /*
         * What reaching an exit does. Scripting a bot to a real exit switch is
         * not practical, and the engine cannot tell the difference: ExitLevel is
         * what the exit line calls.
         */
        doom.ExitLevel();
        while (doom.gamestate == gamestate_t.GS_LEVEL) {
            doom.runOneFrame();
        }

        // The segment covering map one is closed by now.
        final byte[] first = doom.dbrTakePendingRun();
        if (first == null) {
            return "ERROR no segment was closed when the map ended";
        }
        write(demoBase + ".seg0", first);

        // Leaving the intermission, which is what loads map two.
        doom.WorldDone();
        while (doom.gamestate != gamestate_t.GS_LEVEL) {
            doom.runOneFrame();
        }

        final StringBuilder timeline = new StringBuilder();
        int sampled = 0;
        String last = "";
        final int mapTwoUntil = doom.gametic + tics;
        while (doom.gametic < mapTwoUntil) {
            doom.runOneFrame();
            script(doom, doom.gametic - base);
            if (doom.gamestate == gamestate_t.GS_LEVEL) {
                last = sample(doom);
                timeline.append(levelSample(doom)).append("\n");
                sampled++;
            }
        }

        write(demoBase + ".rec.csv", timeline.toString().getBytes("UTF-8"));

        final byte[] second = doom.dbrFinishRun();
        if (second == null) {
            return "ERROR dbrFinishRun returned null: nothing was recording";
        }
        write(demoBase + ".seg1", second);

        return "map=" + doom.gamemap + " seg0=" + first.length + " seg1=" + second.length
            + " carried[" + carried + "]"
            + " tics=" + sampled + " digest=" + digest(timeline.toString())
            + " " + last;
    }

    /**
     * Replays the second segment on its own and reports it the same way.
     *
     * The demo inside is handed to the engine exactly as the verifier does it,
     * and the carried state goes in through the same field, so a disagreement
     * here is a disagreement in production.
     */
    private static String replaySegment(String iwad, String demoBase) throws Exception {
        final byte[] wrapped = read(demoBase + ".seg1");
        final com.dbr.doom.host.RunFormat.Run run =
            com.dbr.doom.host.RunFormat.decode(wrapped);
        if (run == null) {
            return "ERROR the segment could not be decoded";
        }

        write(demoBase + ".lmp", run.demo());

        final Engine engine = Engine.createHeadless(
            "-iwad", iwad,
            "-timedemo", demoBase,
            "-nosound", "-nomusic", "-nodraw", "-noblit");
        final DoomMain<?, ?> doom = engine.getDOOM();
        doom.dbrPendingStartState = run.state();
        doom.setupSession();
        doom.initLoop();

        final StringBuilder timeline = new StringBuilder();
        int sampled = 0;
        String last = "";
        try {
            while (true) {
                doom.runOneFrame();
                if (doom.gamestate == gamestate_t.GS_LEVEL) {
                    last = sample(doom);
                    timeline.append(levelSample(doom)).append("\n");
                    sampled++;
                }
                if (!doom.demoplayback && doom.gametic > 0) {
                    break;
                }
            }
        } catch (Throwable end) {
            // Demo finished: I_Error or Quit, both DoomExitException.
        }

        write(demoBase + ".rep.csv", timeline.toString().getBytes("UTF-8"));

        return "map=" + doom.gamemap + " state=" + (run.state() == null ? "none" : "carried")
            + " tics=" + sampled + " digest=" + digest(timeline.toString())
            + " " + last;
    }

    /** What the player walks out of a map with. For the log, not for the check. */
    private static String carried(DoomMain<?, ?> doom) {
        final player_t p = doom.players[doom.consoleplayer];
        int weapons = 0;
        for (boolean owned : p.weaponowned) {
            if (owned) {
                weapons++;
            }
        }
        return "health=" + p.health[0] + " armor=" + p.armorpoints[0]
            + " weapons=" + weapons + " clip=" + p.ammo[0]
            + " weapon=" + p.readyweapon;
    }

    /**
     * One tic of a level, with gametic left out of it.
     *
     * gametic counts from the start of the process, so the recording is a
     * thousand tics further along than a replay of one segment - and that is not
     * a disagreement about anything. leveltime is the clock they share.
     */
    private static String levelSample(DoomMain<?, ?> doom) {
        final player_t p = doom.players[doom.consoleplayer];
        final mobj_t mo = p.mo;
        return doom.leveltime + "," + p.killcount + "," + p.itemcount + ","
            + p.secretcount + "," + p.health[0] + "," + p.armorpoints[0] + ","
            + p.ammo[0] + "," + p.readyweapon + ","
            + (mo == null ? 0 : mo.x) + "," + (mo == null ? 0 : mo.y) + ","
            + (mo == null ? 0 : mo.z) + "," + (mo == null ? 0L : mo.angle);
    }

    private static String digest(String text) throws Exception {
        final byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes("UTF-8"));
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            out.append(String.format("%02x", Byte.valueOf(hash[i])));
        }
        return out.toString();
    }

    private static void write(String path, byte[] data) throws Exception {
        final FileOutputStream out = new FileOutputStream(path);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static byte[] read(String path) throws Exception {
        final File file = new File(path);
        final byte[] data = new byte[(int) file.length()];
        final java.io.DataInputStream in =
            new java.io.DataInputStream(new java.io.FileInputStream(file));
        try {
            in.readFully(data);
        } finally {
            in.close();
        }
        return data;
    }

    // ---------------------------------------------------------------- record

    private static String record(String iwad, String demoBase, int tics) throws Exception {
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-record", demoBase,
            "-warp", "1", "1",
            "-skill", "3",
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        String state = "";

        /*
         * Scripted input. Posted from this thread, between frames, which is
         * where GuiDoom posts from too. What the sequence actually does barely
         * matters; it only has to produce a demo with kills in it.
         */
        while (doom.gametic < tics) {
            doom.runOneFrame();
            script(doom, doom.gametic);
            if (doom.gamestate == gamestate_t.GS_LEVEL) {
                state = sample(doom);
            }
        }

        /*
         * Writes the .lmp and then throws, because I_Error("Demo recorded") is
         * how vanilla ends a recording and every System.exit() in the engine is
         * a DoomExitException here. The file is on disk by then.
         */
        try {
            doom.CheckDemoStatus();
        } catch (Throwable expected) {
            // The demo is written. Nothing to do.
        }

        return state;
    }

    /** Hold forward, pulse the trigger, sweep right now and then. */
    private static void script(DoomMain<?, ?> doom, int t) {

        if (t == 35) {
            post(doom, evtype_t.ev_keydown, doom.key_up);
        }

        if (t > 70) {
            final int phase = t % 20;
            if (phase == 0) {
                post(doom, evtype_t.ev_keydown, doom.key_fire);
            } else if (phase == 10) {
                post(doom, evtype_t.ev_keyup, doom.key_fire);
            }

            final int sweep = t % 210;
            if (sweep == 0) {
                post(doom, evtype_t.ev_keydown, doom.key_right);
            } else if (sweep == 25) {
                post(doom, evtype_t.ev_keyup, doom.key_right);
            }
        }
    }

    /**
     * Posts the key the engine itself has bound, rather than the one we assume.
     *
     * Guessing cost a run: forward is bound to SC_W by default, not the up
     * arrow, so a scripted "hold up" moved nobody and the demo recorded 1400
     * tics of standing still - which replays identically for the wrong reason
     * and reads as a pass.
     */
    private static void post(DoomMain<?, ?> doom, evtype_t type, int boundScanCode) {
        final ScanCode sc = ScanCode.values()[boundScanCode];
        doom.PostEvent(new event_t.keyevent_t(type, sc));
    }

    // ---------------------------------------------------------------- replay

    private static String replay(String iwad, String demoBase) throws Exception {
        /*
         * -timedemo, not -fastdemo. Only -timedemo sets singletics, which is
         * what unhooks the loop from the 35Hz clock; -fastdemo replayed at
         * real time, 41 seconds for a 40 second demo, which no server can pay
         * per completed map.
         */
        final DoomMain<?, ?> doom = boot(
            "-iwad", iwad,
            "-timedemo", demoBase,
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        String state = "";

        /*
         * Sampled every tic rather than read once at the end. When the demo
         * runs out the engine quits through I_Error, and singledemo means it
         * does so immediately, so there is no "after" to read. This is also
         * exactly the mechanism the real verifier needs: a timeline of events,
         * not a final total.
         */
        try {
            while (true) {
                doom.runOneFrame();
                if (doom.gamestate == gamestate_t.GS_LEVEL) {
                    state = sample(doom);
                }
                if (!doom.demoplayback && doom.gametic > 0) {
                    break;
                }
            }
        } catch (Throwable end) {
            // Demo finished: I_Error or Quit, both DoomExitException.
        }

        return state;
    }

    // ----------------------------------------------------------------- boot

    private static DoomMain<?, ?> boot(String... argv) throws Exception {
        final Engine engine = Engine.createHeadless(argv);
        final DoomMain<?, ?> doom = engine.getDOOM();
        doom.setupSession();
        doom.initLoop();
        return doom;
    }

    /**
     * The state a reward would be paid on, plus the canaries.
     *
     * Position and angle matter more than the counters here: a desync moves the
     * player long before it changes a kill count, so comparing only kills would
     * call a broken replay identical.
     */
    private static String sample(DoomMain<?, ?> doom) {
        final player_t p = doom.players[doom.consoleplayer];
        final mobj_t mo = p.mo;

        return "tic=" + doom.gametic
            + " leveltime=" + doom.leveltime
            + " kills=" + p.killcount
            + " items=" + p.itemcount
            + " secrets=" + p.secretcount
            + " health=" + p.health[0]
            + " x=" + (mo == null ? 0 : mo.x)
            + " y=" + (mo == null ? 0 : mo.y)
            + " z=" + (mo == null ? 0 : mo.z)
            + " angle=" + (mo == null ? 0L : mo.angle);
    }
}
