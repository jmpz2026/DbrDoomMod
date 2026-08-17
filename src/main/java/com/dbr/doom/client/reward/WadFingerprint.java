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

package com.dbr.doom.client.reward;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import com.dbr.doom.DbrDoomMod;

/**
 * Identifies the WAD a run was played on.
 *
 * The server replays a run to find out what happened in it, and a replay only
 * reproduces a session when it runs against the same game data. A different WAD
 * puts different monsters in different places, so the replay would disagree with
 * the session for reasons that have nothing to do with cheating - and would
 * disagree in whichever direction the WAD happened to make easier.
 *
 * So the client says which WAD it played, the server compares that against
 * the ones it is willing to pay for, and refuses the rest. Only the WAD shipped
 * in the jar is rewarded; a player is still free to drop their own DOOM2.WAD in
 * and play it, they simply earn nothing for it, because the server has no copy
 * to check the run against.
 *
 * This is not a security measure on its own - a client can claim any hash it
 * likes - it is what makes an honest client's run verifiable. A lie here only
 * moves the failure to the replay, which will not reproduce and so pays nothing.
 */
public final class WadFingerprint {

    private static File cachedFile;
    private static long cachedLength;
    private static long cachedModified;
    private static String cachedHash;

    private WadFingerprint() {
    }

    /**
     * SHA-256 of a WAD, as lower case hex, or an empty string if it could not
     * be read.
     *
     * Cached against the file's size and timestamp, because hashing twelve
     * megabytes takes long enough to notice and a session may start often.
     */
    public static synchronized String of(File wad) {
        if (wad == null || !wad.isFile()) {
            return "";
        }

        if (wad.equals(cachedFile)
                && wad.length() == cachedLength
                && wad.lastModified() == cachedModified) {
            return cachedHash;
        }

        final String hash = compute(wad);

        cachedFile = wad;
        cachedLength = wad.length();
        cachedModified = wad.lastModified();
        cachedHash = hash;

        return hash;
    }

    private static String compute(File wad) {
        InputStream in = null;
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(wad);

            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }

            return hex(digest.digest());
        } catch (Exception e) {
            DbrDoomMod.logger().warn("Could not fingerprint {}: {}",
                new Object[] { wad.getName(), e.toString() });
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Nothing useful to do about a failed close on a read.
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            final int value = b & 0xFF;
            if (value < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(value));
        }
        return out.toString();
    }
}
