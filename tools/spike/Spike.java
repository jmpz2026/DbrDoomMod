/*
 * DbrDoomMod - phase 0 spike. Not shipped, not part of the mod build.
 *
 * Answers two questions at once:
 *
 *   1. Can the Doom engine be loaded out of the mod jar by a classloader that
 *      has no Forge and no Minecraft on it? That is how the Bukkit plugin would
 *      run the verifier, and it only works because com.dbr.doom.engine.** plus
 *      DoomExitException reference nothing outside themselves.
 *
 *   2. Does a recorded demo replay to exactly the same state, every time?
 *      Everything server-authoritative rests on this. If replays disagree, or
 *      disagree with the recording, verification by demo is not possible.
 *
 * Each run gets a fresh URLClassLoader, which also gives it a fresh copy of the
 * engine's static Engine.instance. That isolation is the mechanism the plugin
 * would use to verify several runs at once, so testing it here is not incidental.
 *
 * Usage: java Spike <modJar> <spikeClassesDir> <iwad> <workDir> [replays]
 */

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public final class Spike {

    public static void main(String[] args) throws Exception {
        final File modJar = new File(args[0]);
        final File spikeClasses = new File(args[1]);
        final File iwad = new File(args[2]);
        final File workDir = new File(args[3]);
        final int replays = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        final String tics = args.length > 5 ? args[5] : "1400";

        workDir.mkdirs();
        final String demoBase = new File(workDir, "spike").getAbsolutePath();

        for (File stale : new File[] { new File(demoBase + ".lmp") }) {
            if (stale.exists() && !stale.delete()) {
                System.out.println("WARN could not delete " + stale);
            }
        }

        System.out.println("mod jar : " + modJar.getAbsolutePath());
        System.out.println("iwad    : " + iwad.getAbsolutePath());
        System.out.println("work    : " + workDir.getAbsolutePath());
        System.out.println();

        final URL[] classpath = {
            modJar.toURI().toURL(),
            spikeClasses.toURI().toURL()
        };

        /*
         * "record-ingame" is the path the mod uses: no -record on the command
         * line, the game started from inside a live session, the demo handed
         * back as bytes. "record" is the plain -record path, kept because it is
         * what proved the engine deterministic in the first place.
         */
        final String recordMode = System.getProperty("spike.record", "record-ingame");

        System.out.println("== recording (" + recordMode + ") ==");
        final long recordStart = System.currentTimeMillis();
        final String recorded = invoke(classpath, new String[] {
            recordMode, iwad.getAbsolutePath(), demoBase, workDir.getAbsolutePath(), tics
        });
        System.out.println("  " + recorded);
        System.out.println("  took " + (System.currentTimeMillis() - recordStart) + "ms");

        final File lmp = new File(demoBase + ".lmp");
        if (!lmp.isFile()) {
            System.out.println();
            System.out.println("FAIL no demo was written to " + lmp);
            System.exit(1);
        }
        System.out.println("  demo " + lmp.length() + " bytes");

        /*
         * A demo of a player standing still replays identically because nothing
         * happens in it, and that reads as a pass. The first run of this spike
         * did exactly that - forward was bound to SC_W, not the up arrow - and
         * reported GO on a session with no kills and no movement. Refuse to
         * draw a conclusion from a session that never did anything.
         */
        if (!exercised(recorded)) {
            System.out.println();
            System.out.println("FAIL the recorded session did nothing: no kills and no movement.");
            System.out.println("     A demo of standing still replays identically for reasons");
            System.out.println("     that say nothing about determinism. Fix the input script.");
            System.out.println("     recorded: " + recorded);
            System.exit(1);
        }
        System.out.println();

        System.out.println("== replaying " + replays + " times ==");
        final List<String> results = new ArrayList<String>();
        for (int i = 0; i < replays; i++) {
            final long started = System.currentTimeMillis();
            final String result = invoke(classpath, new String[] {
                "replay", iwad.getAbsolutePath(), demoBase, workDir.getAbsolutePath()
            });
            results.add(result);
            System.out.println("  " + (i + 1) + ": " + result
                + "   (" + (System.currentTimeMillis() - started) + "ms)");
        }

        report(recorded, results);
    }

    /**
     * Runs one engine in a classloader of its own.
     *
     * The null parent is the whole point: it means the bootstrap loader and
     * nothing else, so if the engine reached for a Forge or Minecraft class
     * this would fail with NoClassDefFoundError rather than quietly working
     * because the harness happened to have it on the classpath.
     */
    private static String invoke(URL[] classpath, String[] args) throws Exception {
        final URLClassLoader loader = new URLClassLoader(classpath, null);
        try {
            final Class<?> runner = loader.loadClass("SpikeRunner");
            final Method run = runner.getMethod("run", String[].class);
            return (String) run.invoke(null, (Object) args);
        } finally {
            loader.close();
        }
    }

    private static void report(String recorded, List<String> results) {
        System.out.println();
        System.out.println("== verdict ==");

        boolean bad = false;
        for (String r : results) {
            if (r.startsWith("ERROR") || r.isEmpty()) {
                bad = true;
            }
        }
        if (bad) {
            System.out.println("FAIL at least one replay did not produce a state");
            return;
        }

        final String first = results.get(0);
        int disagreeing = 0;
        for (String r : results) {
            if (!first.equals(r)) {
                disagreeing++;
            }
        }

        if (disagreeing > 0) {
            System.out.println("FAIL replays disagree with each other: "
                + disagreeing + " of " + results.size() + " differ from the first");
            System.out.println("  first: " + first);
            for (String r : results) {
                if (!first.equals(r)) {
                    System.out.println("  diff : " + r);
                    break;
                }
            }
            return;
        }

        System.out.println("OK   all " + results.size() + " replays agree with each other");

        /*
         * The stronger test. Replays agreeing only proves replay is stable;
         * production compares a replay against what the client actually played,
         * so the recording has to match too. Compared without the tic counter,
         * which legitimately differs: recording stops on a tic we choose, and
         * playback stops when the demo stream runs dry.
         */
        if (stripTic(recorded).equals(stripTic(first))) {
            System.out.println("OK   replay matches the recorded session");
            System.out.println();
            System.out.println("GO   demo verification is viable");
        } else {
            System.out.println("FAIL replay does not match the recorded session");
            System.out.println("  recorded: " + recorded);
            System.out.println("  replayed: " + first);
            System.out.println();
            System.out.println("NO-GO as it stands. Replay is stable but does not reproduce");
            System.out.println("      the session, so it cannot be used to verify one.");
        }
    }

    /**
     * Whether the recorded session actually simulated anything worth checking.
     *
     * Kills are the signal: they need the player to move, aim, fire, and the
     * monster AI and the RNG to all run. A session with kills in it exercises
     * every part of the simulation a desync could show up in.
     */
    private static boolean exercised(String state) {
        return field(state, "kills=") > 0;
    }

    private static long field(String state, String key) {
        for (String part : state.split(" ")) {
            if (part.startsWith(key)) {
                try {
                    return Long.parseLong(part.substring(key.length()));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String stripTic(String state) {
        final StringBuilder out = new StringBuilder();
        for (String part : state.split(" ")) {
            if (!part.startsWith("tic=") && !part.startsWith("leveltime=")) {
                out.append(part).append(' ');
            }
        }
        return out.toString().trim();
    }
}
