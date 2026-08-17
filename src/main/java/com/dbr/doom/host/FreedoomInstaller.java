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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.io.OutputStream;

import org.tukaani.xz.XZInputStream;

import com.dbr.doom.DbrDoomMod;

/**
 * Unpacks the bundled Freedoom game data on first run, so /DoomPlay works
 * without the player having to find a WAD anywhere.
 *
 * Freedoom is a free replacement for Doom's game data, licensed under a
 * three-clause BSD licence that permits redistribution, and modification, in
 * binary form as long as its copyright notice and disclaimer travel with it.
 * The licence, credits and a note saying which maps were removed are unpacked
 * alongside the WAD for exactly that reason, and must not be dropped.
 *
 * The real Doom data is a different matter: DOOM.WAD and DOOM2.WAD are
 * proprietary and are never bundled. A player who owns them can drop them into
 * the same folder and the mod will find them.
 *
 * @see <a href="https://freedoom.github.io/">freedoom.github.io</a>
 */
public final class FreedoomInstaller {

    private static final String RESOURCE_ROOT = "/assets/dbrdoom/wads/";

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
        "freedoom1.wad.xz",
        "COPYING-freedoom.txt",
        "CREDITS-freedoom.txt",
        "MODIFICATIONS-freedoom.txt",
    };

    private static final String XZ_SUFFIX = ".xz";

    private FreedoomInstaller() {
    }

    /**
     * Writes any bundled file that is not already in wadDir.
     *
     * Existing files are left alone, so a player who deletes the WAD to save
     * space does not get it back on every launch, and anyone who drops in their
     * own DOOM.WAD is never overwritten.
     *
     * @return the number of files actually written
     */
    public static int installIfMissing(File wadDir) throws IOException {
        if (!wadDir.isDirectory() && !wadDir.mkdirs()) {
            throw new IOException("could not create " + wadDir.getAbsolutePath());
        }

        // A marker file records that we have unpacked once already. Without it,
        // deleting every WAD on purpose would silently restore them next launch.
        final File marker = new File(wadDir, ".freedoom-installed");
        if (marker.isFile()) {
            return 0;
        }

        int written = 0;
        for (String name : BUNDLED) {
            final boolean compressed = name.endsWith(XZ_SUFFIX);
            final String targetName = compressed
                ? name.substring(0, name.length() - XZ_SUFFIX.length())
                : name;

            final File target = new File(wadDir, targetName);
            if (target.isFile()) {
                continue;
            }
            if (extract(RESOURCE_ROOT + name, target, compressed)) {
                written++;
            }
        }

        if (!marker.createNewFile()) {
            /*
             * Not fatal, and not worth failing a successful unpack over: the
             * worst case is that the check runs again next launch and finds
             * every file already in place. Throwing here reported a 28MB WAD
             * that had just been written correctly as a failed install.
             */
            DbrDoomMod.logger().warn("Could not create marker {}; the unpack check will run again next launch",
                marker.getAbsolutePath());
        }

        return written;
    }

    /**
     * Copies one jar resource to disk, decompressing it on the way if needed.
     *
     * Writes to a temporary file first: an interrupted launch would otherwise
     * leave a half-written 28MB WAD, which the engine rejects deep in its loader
     * with an error that explains nothing.
     */
    private static boolean extract(String resource, File target, boolean compressed)
            throws IOException {

        InputStream in = FreedoomInstaller.class.getResourceAsStream(resource);
        if (in == null) {
            // Someone built a jar without the game data. Not fatal: the player
            // can still supply their own WAD.
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
                // Best effort: a leftover .part is harmless, WadLocator ignores it.
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
