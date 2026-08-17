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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A recorded run, as it travels: a vanilla Doom demo with what the player was
 * carrying when it started.
 *
 * <h2>Why this exists</h2>
 *
 * A demo replays from a level start with a pistol, because that is all a demo
 * header describes. The second map of a playthrough is entered carrying the
 * weapons, ammo and health of the first, so a demo that began there would
 * replay into a different game - which is why a run used to be recorded once,
 * from the start of the playthrough, and uploaded again as a longer prefix
 * every time a map was finished.
 *
 * That is correct and it costs a squared amount of work: nine maps meant
 * replaying map one nine times, map two eight times, and about 69 seconds of
 * server CPU for one sitting. The cost also grows <em>during</em> a session,
 * which is the worst shape it could have.
 *
 * So a run is cut at every map, and what the demo cannot say is written down
 * beside it: the carried state, and the engine's random index. Replaying then
 * means installing that state before the level is built - exactly what the real
 * transition did - and a nine map sitting costs nine map-sized replays.
 *
 * <h2>What is in a state, and what is deliberately not</h2>
 *
 * The state is produced and read by the engine alone, as an int array whose
 * meaning is {@code DoomMain.dbrCaptureCarriedState}'s business. Nothing here
 * interprets it: this class is the envelope, so that adding a field to the
 * state is one edit in the engine and none here.
 *
 * It holds what {@code G_PlayerReborn} would otherwise wipe and the random
 * indices, because those are the only two differences between "the engine
 * loaded the next map" and "the engine started this map fresh for a replay".
 * Powers, keys and the damage palette are cleared by {@code G_PlayerFinishLevel}
 * on one side and start clear on the other; the kill, item and secret counts
 * are reset by {@code P_SetupLevel} on both.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   4  magic "DBRD"
 *   1  format version
 *   4  serial   - which playthrough
 *   4  segment  - which map of it, from 0
 *   4  state length in ints, 0 when the segment starts a playthrough
 *   4* the state
 *   4  demo length in bytes
 *   n  the vanilla demo, byte for byte as the engine wrote it
 * </pre>
 *
 * Big endian, {@link DataOutputStream} order.
 *
 * <h2>Where this class may live</h2>
 *
 * Nothing but the JDK, on purpose. It is referenced from the engine, from the
 * client and from the verifier, and the verifier runs in a classloader with a
 * null parent - so anything reachable from here would have to be loadable
 * there. That is the same rule {@link DoomExitException} already lives under,
 * and the reason both are in this package rather than anywhere more convenient.
 */
public final class RunFormat {

    /** "DBRD". A demo starts with a version byte, so these cannot be confused. */
    private static final int MAGIC = 0x44425244;

    /** Bumped when the layout changes. A reader refuses anything else. */
    public static final int VERSION = 1;

    /**
     * The most ints a state may have.
     *
     * The engine decides what goes in a state and this only has to be larger
     * than that. It exists because the reader is fed by a network, and a length
     * read off the wire is an allocation somebody else chose.
     */
    private static final int MAX_STATE = 256;

    /** One run: a demo, and the state the engine needs to replay it. */
    public static final class Run {

        private final int serial;
        private final int segment;
        private final int[] state;
        private final byte[] demo;

        public Run(int serial, int segment, int[] state, byte[] demo) {
            this.serial = serial;
            this.segment = segment;
            this.state = state;
            this.demo = demo;
        }

        /** Which playthrough. Restarting bumps it and the segments start over. */
        public int serial() {
            return serial;
        }

        /**
         * Which map of that playthrough, counting from zero.
         *
         * Segments of one playthrough are disjoint and consecutive, which is
         * what the server adds up. They are not prefixes of each other - that
         * was the old shape, and the reason this format exists.
         */
        public int segment() {
            return segment;
        }

        /** What the player was carrying, or null when the segment starts fresh. */
        public int[] state() {
            return state;
        }

        /** The vanilla demo. What the engine replays. */
        public byte[] demo() {
            return demo;
        }
    }

    private RunFormat() {
    }

    /** Wraps a demo for upload. Never returns null. */
    public static byte[] encode(int serial, int segment, int[] state, byte[] demo) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(demo.length + 64);
        final DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(serial);
            out.writeInt(segment);

            out.writeInt(state == null ? 0 : state.length);
            if (state != null) {
                for (int value : state) {
                    out.writeInt(value);
                }
            }

            out.writeInt(demo.length);
            out.write(demo);
            out.flush();
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not fail, and there is nothing to
            // hand this to: the caller is the engine, mid-tic.
            throw new IllegalStateException("Could not encode a run", impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads a run back.
     *
     * Everything read here arrived from a client, so every length is checked
     * against what is really there before it is believed.
     *
     * @return the run, or null if these bytes are not one
     */
    public static Run decode(byte[] raw) {
        if (raw == null || raw.length < 21) {
            return null;
        }

        final DataInputStream in = new DataInputStream(
            new java.io.ByteArrayInputStream(raw));
        try {
            if (in.readInt() != MAGIC) {
                return null;
            }
            if ((in.readByte() & 0xFF) != VERSION) {
                return null;
            }

            final int serial = in.readInt();
            final int segment = in.readInt();

            final int stateLength = in.readInt();
            if (stateLength < 0 || stateLength > MAX_STATE) {
                return null;
            }
            int[] state = null;
            if (stateLength > 0) {
                state = new int[stateLength];
                for (int i = 0; i < stateLength; i++) {
                    state[i] = in.readInt();
                }
            }

            final int demoLength = in.readInt();
            /*
             * Against what is actually here, not against a limit: the whole
             * array is already in memory, so a length longer than the rest of it
             * is a corrupt upload rather than an allocation to refuse.
             */
            if (demoLength < 0 || demoLength > in.available()) {
                return null;
            }

            final byte[] demo = new byte[demoLength];
            in.readFully(demo);

            return new Run(serial, segment, state, demo);
        } catch (IOException truncated) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // A ByteArrayInputStream has nothing to close.
            }
        }
    }

    /** True if these bytes carry the envelope rather than a bare demo. */
    public static boolean isWrapped(byte[] raw) {
        return raw != null && raw.length >= 5
            && (raw[0] & 0xFF) == 0x44 && (raw[1] & 0xFF) == 0x42
            && (raw[2] & 0xFF) == 0x52 && (raw[3] & 0xFF) == 0x44;
    }
}
