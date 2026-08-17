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

/**
 * Finds the one WAD this mod plays.
 *
 * It used to list whatever was in config/dbrdoom/wads and take the first IWAD,
 * so a player could drop in their own DOOM.WAD or an add-on. The mod now plays
 * the Freedoom that ships in the jar and nothing else, and
 * {@link FreedoomInstaller} puts it back if it is replaced.
 *
 * The reason is the rewards. A run is checked by replaying it on the server
 * against the server's own copy of the data, so a client on different data
 * records a run that cannot reproduce - and one on an edited copy, with weaker
 * monsters or convenient ammo, records a run that reproduces into something it
 * did not play. Anything else left in the folder is ignored, not deleted.
 */
public final class WadLocator {

    /** A WAD's first four bytes say which kind it is. */
    private static final String MAGIC_IWAD = "IWAD";

    private WadLocator() {
    }

    /**
     * The bundled IWAD, or null if it is missing or is not a WAD at all.
     *
     * The header is checked rather than the extension: handing a non-WAD to the
     * engine crashes it deep inside the loader with an error that explains
     * nothing.
     */
    public static File bundledIwad(File wadDir) {
        if (wadDir == null || !wadDir.isDirectory()) {
            return null;
        }

        final File wad = new File(wadDir, FreedoomInstaller.IWAD_NAME);
        if (!wad.isFile() || !MAGIC_IWAD.equals(readMagic(wad))) {
            return null;
        }
        return wad;
    }

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
