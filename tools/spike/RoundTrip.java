/*
 * DbrDoomMod - phase 2 check. Not shipped, not part of the mod build.
 *
 * Puts a run through the wire format and out the other side: deflate, split
 * into 16KB chunks, reassemble, inflate, verify. The plugin has to implement
 * the far half of this from a written description, so the point is to pin the
 * format down against the mod's actual sender before anything is written twice.
 *
 * Passing means the bytes survive, and that the reassembled run verifies to the
 * same report as the original - which is the only property that matters, since
 * a run that arrives subtly wrong pays subtly wrong amounts.
 *
 * Usage: java RoundTrip <modJar> <demo.lmp> <iwad> <workDir>
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class RoundTrip {

    /**
     * Must match RewardChannel.MAX_CHUNK.
     *
     * Overridable so the split and reassemble paths can be exercised at all:
     * a real run compresses to about 5KB, so at the production size almost
     * everything is a single chunk and the interesting code never runs.
     */
    private static final int MAX_CHUNK =
        Integer.getInteger("roundtrip.chunk", 16 * 1024).intValue();

    public static void main(String[] args) throws Exception {
        final File modJar = new File(args[0]);
        final byte[] original = readAll(new File(args[1]));
        final String iwad = new File(args[2]).getAbsolutePath();
        final File work = new File(args[3]);

        System.out.println("original   : " + original.length + " bytes");

        final byte[] compressed = deflate(original);
        System.out.println("compressed : " + compressed.length + " bytes ("
            + (100 - compressed.length * 100 / original.length) + "% smaller)");

        final List<byte[]> chunks = split(compressed);
        System.out.println("chunks     : " + chunks.size() + " of up to " + MAX_CHUNK);

        /*
         * Chunks are sent one per tick over a single connection, so they cannot
         * overtake one another - but the plugin should not depend on that, and
         * a reassembler that quietly relies on order is a bug waiting for a
         * laggy client. Shuffled here so the test would catch one.
         */
        final List<Indexed> scrambled = new ArrayList<Indexed>();
        for (int i = 0; i < chunks.size(); i++) {
            scrambled.add(new Indexed(i, chunks.get(i)));
        }
        Collections.shuffle(scrambled);

        final byte[] reassembled = inflate(reassemble(scrambled, compressed.length));

        System.out.println();
        if (!java.util.Arrays.equals(original, reassembled)) {
            System.out.println("FAIL the run did not survive the round trip");
            System.exit(1);
        }
        System.out.println("OK   bytes are identical after deflate, split, shuffle, reassemble");

        final String before = verify(modJar, original, iwad, new File(work, "before"));
        final String after = verify(modJar, reassembled, iwad, new File(work, "after"));

        if (!before.equals(after)) {
            System.out.println("FAIL the reassembled run verifies differently");
            System.out.println("  before: " + before);
            System.out.println("  after : " + after);
            System.exit(1);
        }
        System.out.println("OK   both verify to the same report");
        System.out.println();
        System.out.print(after);
    }

    private static final class Indexed {
        final int index;
        final byte[] data;

        Indexed(int index, byte[] data) {
            this.index = index;
            this.data = data;
        }
    }

    private static List<byte[]> split(byte[] data) {
        final List<byte[]> chunks = new ArrayList<byte[]>();
        for (int offset = 0; offset < data.length; offset += MAX_CHUNK) {
            final int length = Math.min(MAX_CHUNK, data.length - offset);
            final byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            chunks.add(chunk);
        }
        return chunks;
    }

    /** What the plugin does: place each chunk by its index, not by arrival. */
    private static byte[] reassemble(List<Indexed> chunks, int totalLength) {
        final byte[] out = new byte[totalLength];
        for (Indexed chunk : chunks) {
            System.arraycopy(chunk.data, 0, out, chunk.index * MAX_CHUNK, chunk.data.length);
        }
        return out;
    }

    /** Must match RunUploader.deflate. */
    private static byte[] deflate(byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer);
                if (written <= 0) {
                    break;
                }
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed) throws Exception {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 3);
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int written = inflater.inflate(buffer);
                if (written <= 0) {
                    break;
                }
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

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
