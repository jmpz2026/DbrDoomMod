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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds playable WADs in config/dbrdoom/wads.
 *
 * No WAD ships with this mod. DOOM.WAD and DOOM2.WAD are proprietary, so the
 * player supplies their own copy or uses Freedoom, which is freely licensed and
 * runs on the same engine.
 */
public final class WadLocator {

    /** A WAD's first four bytes say which kind it is. */
    private static final String MAGIC_IWAD = "IWAD";
    private static final String MAGIC_PWAD = "PWAD";

    private WadLocator() {
    }

    /** One WAD found on disk. */
    public static final class Wad {

        private final File file;
        private final boolean iwad;

        Wad(File file, boolean iwad) {
            this.file = file;
            this.iwad = iwad;
        }

        public File getFile() {
            return file;
        }

        /** The name without its extension, which is what /DoomPlay matches on. */
        public String getName() {
            final String n = file.getName();
            final int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }

        /**
         * True for a full game (an IWAD), false for an add-on (a PWAD).
         * Only an IWAD can be launched on its own.
         */
        public boolean isIwad() {
            return iwad;
        }
    }

    /**
     * Lists every readable WAD in the directory, IWADs first and alphabetical
     * within each group, so the default pick is stable between launches.
     *
     * Files that are unreadable or are not WADs at all are skipped silently;
     * players drop all sorts of things in these folders.
     */
    public static List<Wad> findAll(File wadDir) {
        final List<Wad> found = new ArrayList<Wad>();

        if (wadDir == null || !wadDir.isDirectory()) {
            return found;
        }

        final File[] files = wadDir.listFiles();
        if (files == null) {
            return found;
        }

        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".wad")) {
                continue;
            }

            final String magic = readMagic(f);
            if (MAGIC_IWAD.equals(magic)) {
                found.add(new Wad(f, true));
            } else if (MAGIC_PWAD.equals(magic)) {
                found.add(new Wad(f, false));
            }
            // Anything else just is not a WAD, whatever its extension claims.
        }

        Collections.sort(found, new Comparator<Wad>() {
            @Override
            public int compare(Wad a, Wad b) {
                if (a.isIwad() != b.isIwad()) {
                    return a.isIwad() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        return found;
    }

    /** The WAD /DoomPlay uses when given no argument, or null if there is none. */
    public static Wad findDefaultIwad(File wadDir) {
        for (Wad w : findAll(wadDir)) {
            if (w.isIwad()) {
                return w;
            }
        }
        return null;
    }

    /**
     * Reads the four-byte header. Trusting the extension is not enough: players
     * rename things, and handing a non-WAD to the engine crashes it deep inside
     * the loader with an error that explains nothing.
     */
    private static String readMagic(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            final DataInputStream data = new DataInputStream(in);
            final byte[] magic = new byte[4];
            data.readFully(magic);
            return new String(magic, "US-ASCII");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing useful to do about a failed close on a read.
                }
            }
        }
    }
}
