/*
 * DbrDoomMod - play Doom inside Minecraft 1.7.10.
 * Copyright (C) 2026  DbrDoomMod contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dbr.doom.host;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

import org.tukaani.xz.XZInputStream;

import com.dbr.doom.DbrDoomMod;

/**
 * Puts the bundled Freedoom data on disk and keeps it that way.
 *
 * The mod plays one WAD: the Freedoom Episode 1 that ships in the jar. It is not
 * a default that a player may replace - it is the only thing the engine is ever
 * pointed at, so every client is playing the same game and a run recorded on one
 * means the same thing on another.
 *
 * That is why this checks rather than only installs. A WAD that has been swapped
 * for another is restored on the next launch, which keeps an edited copy - one
 * with weaker monsters, or ammo in convenient places - from quietly becoming
 * what a session is played on.
 *
 * The server has its own defence and does not rely on this one: it pays only for
 * runs whose WAD hash is in its allowlist, and it replays against its own copy.
 * This is what stops the client drifting in the first place.
 *
 * Freedoom is licensed under a three-clause BSD licence that permits
 * redistribution and modification in binary form as long as its copyright notice
 * and disclaimer travel with it. The licence, credits and a note saying which
 * maps were removed are unpacked alongside the WAD for exactly that reason, and
 * must not be dropped.
 *
 * @see <a href="https://freedoom.github.io/">freedoom.github.io</a>
 */
public final class FreedoomInstaller {

    private static final String RESOURCE_ROOT = "/assets/dbrdoom/wads/";

    /** The one WAD this mod plays. Named by the engine, so it cannot change. */
    public static final String IWAD_NAME = "freedoom1.wad";

    /**
     * What ships in the jar.
     *
     * The WAD is Episode 1 of Freedoom Phase 1, stored as .xz. Game data was
     * around ninety percent of the jar, so it is trimmed twice over: 27 of the
     * 36 maps are dropped, and LZMA2 replaces zip's deflate. A name ending in
     * .xz is decompressed and the suffix dropped; everything else is copied.
     *
     * The licence and credits are not optional extras: the BSD terms require
     * them to accompany the binary.
     */
    private static final String[] BUNDLED = {
        IWAD_NAME + ".xz",
        "COPYING-freedoom.txt",
        "CREDITS-freedoom.txt",
        "MODIFICATIONS-freedoom.txt",
    };

    private static final String XZ_SUFFIX = ".xz";

    /**
     * Records the size and hash of the WAD as it was written.
     *
     * Without it, telling "the file we unpacked" from "a file of the same name"
     * would mean decompressing the resource on every launch to compare against
     * it. With it the usual case is a stat and a hash of what is already there.
     */
    private static final String STAMP_NAME = ".freedoom-stamp";

    private FreedoomInstaller() {
    }

    /**
     * Unpacks whatever is missing, and restores the WAD if it is not the one
     * that was unpacked.
     *
     * @return the number of files written
     */
    public static int ensureInstalled(File wadDir) throws IOException {
        if (!wadDir.isDirectory() && !wadDir.mkdirs()) {
            throw new IOException("could not create " + wadDir.getAbsolutePath());
        }

        /*
         * Left by the version that only ever installed once. It meant "do not
         * unpack again", which is no longer a thing this can promise.
         */
        final File legacyMarker = new File(wadDir, ".freedoom-installed");
        if (legacyMarker.isFile()) {
            legacyMarker.delete();
        }

        int written = 0;
        for (String name : BUNDLED) {
            final boolean compressed = name.endsWith(XZ_SUFFIX);
            final String targetName = compressed
                ? name.substring(0, name.length() - XZ_SUFFIX.length())
                : name;

            final File target = new File(wadDir, targetName);
            final boolean isIwad = IWAD_NAME.equals(targetName);

            /*
             * The WAD is checked, the licence and credits only have to exist. A
             * player who edits CREDITS-freedoom.txt is not changing the game.
             */
            if (target.isFile() && !(isIwad && !matchesStamp(wadDir, target))) {
                continue;
            }

            if (isIwad && target.isFile()) {
                DbrDoomMod.logger().warn(
                    "{} is not the WAD that ships with the mod; restoring it", targetName);
            }

            if (extract(RESOURCE_ROOT + name, target, compressed)) {
                written++;
                if (isIwad) {
                    writeStamp(wadDir, target);
                }
            }
        }

        return written;
    }

    /** True if the file on disk is the one the stamp was written for. */
    private static boolean matchesStamp(File wadDir, File wad) {
        final File stamp = new File(wadDir, STAMP_NAME);
        if (!stamp.isFile()) {
            return false;
        }

        InputStream in = null;
        try {
            in = new FileInputStream(stamp);
            final byte[] raw = new byte[256];
            final int read = in.read(raw);
            if (read <= 0) {
                return false;
            }

            final String[] parts = new String(raw, 0, read, "US-ASCII").trim().split(" ");
            if (parts.length != 2) {
                return false;
            }

            // Size first: it settles the usual case without reading 12MB.
            if (Long.parseLong(parts[0]) != wad.length()) {
                return false;
            }
            return parts[1].equals(hash(wad));
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static void writeStamp(File wadDir, File wad) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(new File(wadDir, STAMP_NAME));
            out.write((wad.length() + " " + hash(wad)).getBytes("US-ASCII"));
        } catch (Exception e) {
            /*
             * Not fatal: without a stamp the next launch decides the WAD is not
             * ours and unpacks it again, which is wasteful but correct.
             */
            DbrDoomMod.logger().warn("Could not write {}: {}", STAMP_NAME, e.toString());
        } finally {
            closeQuietly(out);
        }
    }

    /** SHA-256 of a file, lower case hex. */
    private static String hash(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }

        final InputStream in = new FileInputStream(file);
        try {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            closeQuietly(in);
        }

        final StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) {
            final int v = b & 0xFF;
            if (v < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(v));
        }
        return out.toString();
    }

    /**
     * Copies one jar resource to disk, decompressing it on the way if needed.
     *
     * Writes to a temporary file first: an interrupted launch would otherwise
     * leave a half-written WAD, which the engine rejects deep in its loader with
     * an error that explains nothing.
     */
    private static boolean extract(String resource, File target, boolean compressed)
            throws IOException {

        InputStream in = FreedoomInstaller.class.getResourceAsStream(resource);
        if (in == null) {
            // Someone built a jar without the game data.
            return false;
        }

        if (compressed) {
            in = new XZInputStream(in);
        }

        final File temp = new File(target.getParentFile(), target.getName() + ".part");
        OutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.close();
            out = null;

            if (target.exists() && !target.delete()) {
                throw new IOException("could not replace " + target.getAbsolutePath());
            }
            if (!temp.renameTo(target)) {
                throw new IOException("could not move into place: " + target.getAbsolutePath());
            }
            return true;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (temp.exists()) {
                // Best effort: a leftover .part is harmless and never loaded.
                temp.delete();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do about a failed close.
        }
    }
}
