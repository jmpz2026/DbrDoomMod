/*
 * DbrDoomMod - not shipped, not part of the mod build.
 *
 * Answers the one question the per-map recording rests on: does a segment that
 * begins in the middle of a playthrough replay to the same game?
 *
 * A run used to be one recording from the start of the playthrough, re-uploaded
 * as a longer prefix every time a map was finished, because a demo header says
 * only which map and which skill - and map two is entered carrying map one's
 * weapons, ammo, health and random index. Replaying a demo that began there
 * would start from a pistol and a cleared random index.
 *
 * The engine now writes that carried state down beside the demo and installs it
 * before the level is built. If that is complete, a replay of segment two is
 * identical to the second map of the recording, tic by tic. If it is missing so
 * much as the random index, the two diverge on the first monster to act - which
 * is why the comparison here is a digest of every tic and not a final total.
 *
 * Usage: java SegmentSpike <modJar> <spikeClassesDir> <iwad> <workDir> [replays] [tics]
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public final class SegmentSpike {

    public static void main(String[] args) throws Exception {
        final File modJar = new File(args[0]);
        final File spikeClasses = new File(args[1]);
        final File iwad = new File(args[2]);
        final File workDir = new File(args[3]);
        final int replays = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        final String tics = args.length > 5 ? args[5] : "1400";

        workDir.mkdirs();
        final String demoBase = new File(workDir, "segment").getAbsolutePath();

        final URL[] classpath = {
            modJar.toURI().toURL(),
            spikeClasses.toURI().toURL()
        };

        System.out.println("mod jar : " + modJar.getAbsolutePath());
        System.out.println("iwad    : " + iwad.getAbsolutePath());
        System.out.println();

        System.out.println("== recording across a map boundary ==");
        final long started = System.currentTimeMillis();
        final String recorded = invoke(classpath, new String[] {
            "record-segments", iwad.getAbsolutePath(), demoBase,
            workDir.getAbsolutePath(), tics
        });
        System.out.println("  " + recorded);
        System.out.println("  took " + (System.currentTimeMillis() - started) + "ms");
        System.out.println();

        if (recorded.startsWith("ERROR")) {
            System.out.println("FAIL the recording did not produce two segments");
            System.exit(1);
        }

        /*
         * A segment that carries nothing proves nothing: it would be an ordinary
         * demo replaying the ordinary way, which was never in doubt. The whole
         * question is whether a carried state is enough.
         */
        if (!recorded.contains("carried[") || recorded.contains("weapons=2 clip=50")) {
            System.out.println("WARN the player walked into map two with the starting kit,");
            System.out.println("     so this run does not exercise much of the carried state.");
        }

        System.out.println("== replaying segment two " + replays + " times ==");
        final List<String> results = new ArrayList<String>();
        for (int i = 0; i < replays; i++) {
            final long at = System.currentTimeMillis();
            final String result = invoke(classpath, new String[] {
                "replay-segment", iwad.getAbsolutePath(), demoBase,
                workDir.getAbsolutePath()
            });
            results.add(result);
            System.out.println("  " + (i + 1) + ": " + result
                + "   (" + (System.currentTimeMillis() - at) + "ms)");

            compare(new File(demoBase + ".rec.csv"), new File(demoBase + ".rep.csv"));
        }

        report(recorded, results);
    }

    /**
     * Runs one engine in a classloader of its own.
     *
     * The null parent is the point: the bootstrap loader and nothing else, which
     * is how the plugin runs the verifier. A fresh loader also means a fresh
     * Engine.instance, so each replay starts from nothing.
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

    /** How many tics of the two timelines lined up, and how many disagreed. */
    private static int overlap;
    private static int diverged;
    private static String firstDivergence = "";

    /**
     * Compares the recorded map against its replay, tic by tic, keyed on
     * leveltime.
     *
     * Not on gametic: that counts from the start of the process, so a recording
     * that has already played a map is a thousand tics further along than a
     * replay of one segment, and comparing on it would call every tic a
     * disagreement.
     *
     * The recording's first few tics are missing from its own timeline, because
     * a live engine runs tics in batches inside one frame while a -timedemo
     * replay runs exactly one - so the sampling grain differs at the seam. That
     * is not a hole in the evidence: a desync does not heal. A carried state
     * that was wrong on tic one moves the player, and every tic after it
     * disagrees too.
     */
    private static void compare(File recorded, File replayed) throws Exception {
        final Map<String, String> a = read(recorded);
        final Map<String, String> b = read(replayed);

        overlap = 0;
        diverged = 0;
        firstDivergence = "";

        for (Map.Entry<String, String> entry : a.entrySet()) {
            final String mine = b.get(entry.getKey());
            if (mine == null) {
                continue;
            }
            overlap++;
            if (!mine.equals(entry.getValue())) {
                diverged++;
                if (firstDivergence.isEmpty()) {
                    firstDivergence = "leveltime=" + entry.getKey()
                        + "\n       recorded " + entry.getValue()
                        + "\n       replayed " + mine;
                }
            }
        }

        System.out.println("     " + overlap + " tics compared, "
            + diverged + " disagreed");
    }

    private static Map<String, String> read(File file) throws Exception {
        final Map<String, String> lines = new LinkedHashMap<String, String>();
        final BufferedReader in = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                lines.put(line.substring(0, line.indexOf(',')), line);
            }
        } finally {
            in.close();
        }
        return lines;
    }

    private static void report(String recorded, List<String> results) {
        final String want = field(recorded, "digest=");

        /*
         * The replays have to agree with each other as well as with the
         * recording: a verifier that gives two answers for one demo is no more
         * usable than one that gives the wrong answer.
         */
        int identical = 0;
        for (String result : results) {
            if (field(result, "digest=").equals(field(results.get(0), "digest="))) {
                identical++;
            }
        }

        System.out.println();
        System.out.println("recorded map two : tics=" + field(recorded, "tics=")
            + " digest=" + want);
        System.out.println("replays          : " + results.size()
            + ", " + identical + " identical to each other");
        System.out.println("aligned tics     : " + overlap
            + ", disagreeing: " + diverged);
        System.out.println();

        if (overlap < 100) {
            System.out.println("FAIL only " + overlap + " tics lined up. The recording and the");
            System.out.println("     replay are not describing the same map.");
            System.exit(1);
        }

        if (diverged == 0 && identical == results.size()) {
            System.out.println("GO   a segment starting mid-playthrough replays exactly,");
            System.out.println("     over " + overlap + " tics of the map it continues into.");
            return;
        }

        System.out.println("NO GO a continued segment does not reproduce its map.");
        System.out.println("     First disagreement: " + firstDivergence);
        System.out.println("     Something the engine carries between maps is missing from");
        System.out.println("     dbrCaptureCarriedState. The random index and the reborn");
        System.out.println("     wipe are the two known ones; look for a third.");
        System.exit(1);
    }

    private static String field(String line, String key) {
        final int at = line.indexOf(key);
        if (at < 0) {
            return "";
        }
        final int from = at + key.length();
        final int to = line.indexOf(' ', from);
        return to < 0 ? line.substring(from) : line.substring(from, to);
    }
}
