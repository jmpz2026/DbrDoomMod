/*
 * DbrDoomMod - phase 1 check. Not shipped, not part of the mod build.
 *
 * Calls RunVerifier exactly the way the Bukkit plugin will: reflectively,
 * through a classloader over the mod jar whose parent is null. Nothing here
 * imports an engine type, and this file is compiled against the JDK alone -
 * which is the point. If it can drive a verification, so can a plugin that has
 * only Bukkit on its own classpath.
 *
 * Usage: java VerifyCheck <modJar> <demo.lmp> <iwad> <workDir> [parallel]
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public final class VerifyCheck {

    public static void main(String[] args) throws Exception {
        final File modJar = new File(args[0]);
        final byte[] demo = readAll(new File(args[1]));
        final String iwad = new File(args[2]).getAbsolutePath();
        final File workRoot = new File(args[3]);
        final int parallel = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        System.out.println("demo: " + demo.length + " bytes");
        System.out.println();

        if (parallel <= 1) {
            final long started = System.currentTimeMillis();
            final String report = verify(modJar, demo, iwad, new File(workRoot, "v0"));
            System.out.println(report);
            System.out.println("-- verified in " + (System.currentTimeMillis() - started) + "ms");
            return;
        }

        /*
         * Several at once, each in its own classloader. This is the claim that
         * the engine's static Engine.instance stops being an obstacle once every
         * verification has its own copy of it - which is what lets a busy server
         * check more than one run at a time.
         */
        System.out.println("== " + parallel + " concurrent verifications ==");
        final List<Thread> threads = new ArrayList<Thread>();
        final String[] reports = new String[parallel];
        final long started = System.currentTimeMillis();

        for (int i = 0; i < parallel; i++) {
            final int index = i;
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        reports[index] = verify(modJar, demo, iwad,
                            new File(workRoot, "v" + index));
                    } catch (Exception e) {
                        reports[index] = "ERROR harness: " + e;
                    }
                }
            }, "verify-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        System.out.println("all finished in " + (System.currentTimeMillis() - started) + "ms");
        System.out.println();

        boolean allAgree = true;
        for (String r : reports) {
            if (r == null || !r.equals(reports[0])) {
                allAgree = false;
            }
        }
        System.out.println(reports[0]);
        System.out.println(allAgree
            ? "OK   all " + parallel + " concurrent verifications produced the same report"
            : "FAIL concurrent verifications disagreed");
    }

    /** Exactly what the plugin does: no compile-time dependency on the mod. */
    private static String verify(File modJar, byte[] demo, String iwad, File workDir)
            throws Exception {
        final URLClassLoader loader =
            new URLClassLoader(new URL[] { modJar.toURI().toURL() }, null);
        try {
            final Class<?> verifier = loader.loadClass("com.dbr.doom.verify.RunVerifier");
            final Method verify = verifier.getMethod(
                "verify", byte[].class, String.class, String.class);
            return (String) verify.invoke(null, demo, iwad, workDir.getAbsolutePath());
        } finally {
            loader.close();
        }
    }

    private static byte[] readAll(File file) throws Exception {
        final FileInputStream in = new FileInputStream(file);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
