/*
 * DbrDoomMod - not shipped, not part of the mod build.
 *
 * Asks the engine what it would actually draw for a texture's columns, so that
 * "is that stripe of garbage on the wall the WAD or the engine" can be answered
 * with bytes instead of opinions.
 *
 * A texture whose single patch does not cover its full height has holes. On a
 * two-sided line those are see-through and intended; on a solid wall the
 * renderer takes the single-patch shortcut and reads the patch column raw, so
 * the post headers land on the wall as pixels - the classic tutti-frutti.
 *
 * Usage: java TexProbe <modJar> <spikeClassesDir> <iwad> <TEXTURE> [TEXTURE ...]
 */

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public final class TexProbe {

    public static void main(String[] args) throws Exception {
        final URL[] classpath = {
            new File(args[0]).toURI().toURL(),
            new File(args[1]).toURI().toURL()
        };
        // Absolute: the engine resolves -iwad against its own working directory.
        final String iwad = new File(args[2]).getAbsolutePath();

        for (int i = 3; i < args.length; i++) {
            /*
             * A classloader per texture. Engine.instance is static, so a second
             * boot inside one would get the engine that has already quit.
             */
            final URLClassLoader loader = new URLClassLoader(classpath, null);
            try {
                final Class<?> runner = loader.loadClass("SpikeRunner");
                final Method run = runner.getMethod("run", String[].class);
                final String[] a = {
                    "texcolumn", iwad, "x", System.getProperty("java.io.tmpdir"), args[i]
                };
                System.out.println("  " + run.invoke(null, (Object) a));
            } finally {
                loader.close();
            }
        }
    }
}
