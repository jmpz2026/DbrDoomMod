import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/** Boots the engine and idles on the title screen. See Spike. */
public final class Idle {
    public static void main(String[] args) throws Exception {
        URL[] cp = { new File(args[0]).toURI().toURL(), new File(args[1]).toURI().toURL() };
        URLClassLoader loader = new URLClassLoader(cp, null);
        try {
            Method run = loader.loadClass("SpikeRunner").getMethod("run", String[].class);
            System.out.println("RESULT " + run.invoke(null, (Object) new String[] {
                "idle", new File(args[2]).getAbsolutePath(), "unused", new File(args[3]).getAbsolutePath(), args[4] }));
        } finally {
            loader.close();
        }
    }
}
