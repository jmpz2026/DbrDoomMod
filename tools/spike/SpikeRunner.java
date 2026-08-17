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
