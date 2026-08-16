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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import com.dbr.doom.DbrDoomMod;

/**
 * Sends the engine's console output to Minecraft's log.
 *
 * Doom is 1990s C at heart and narrates its startup on stdout: "W_Init: Init
 * WADfiles.", "R_Init: Init DOOM refresh daemon" and so on. Left alone those
 * lines appear raw, with no timestamp and no source, mixed into everything else.
 * Worse, a few stray debug prints survive in the engine that produce bare
 * numbers with no context at all, which is impossible to trace back.
 *
 * Replacing System.out in a JVM shared with every other mod would be rude, so
 * this filters by thread: only output from the Doom thread is captured, and
 * everything else is passed straight through to the original stream untouched.
 */
public final class EngineOutputRouter {

    /** Must match the thread name used in {@link DoomHost}. */
    static final String ENGINE_THREAD_NAME = "DbrDoom-Engine";

    private static boolean installed;

    private EngineOutputRouter() {
    }

    /**
     * Installs the filter. Safe to call more than once, and deliberately never
     * uninstalled: swapping the streams back while another thread is mid-write
     * would be a race for no benefit.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        System.setOut(wrap(System.out, false));
        System.setErr(wrap(System.err, true));
    }

    private static PrintStream wrap(PrintStream original, boolean error) {
        try {
            return new PrintStream(new RoutingStream(original, error), true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM, so this cannot happen.
            return original;
        }
    }

    /**
     * Buffers bytes until a line is complete, then decides where it goes.
     * Line-buffered because the engine prints partial lines and a per-byte
     * decision would shred them.
     */
    private static final class RoutingStream extends OutputStream {

        private final PrintStream original;
        private final boolean error;
        private final StringBuilder line = new StringBuilder(128);

        RoutingStream(PrintStream original, boolean error) {
            this.original = original;
            this.error = error;
        }

        @Override
        public void write(int b) throws IOException {
            if (!isEngineThread()) {
                original.write(b);
                return;
            }

            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.append((char) (b & 0xFF));
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (!isEngineThread()) {
                original.write(buffer, offset, length);
                return;
            }

            for (int i = 0; i < length; i++) {
                write(buffer[offset + i]);
            }
        }

        @Override
        public void flush() throws IOException {
            original.flush();
        }

        private void flushLine() {
            final String text = line.toString().trim();
            line.setLength(0);

            if (text.isEmpty()) {
                return;
            }

            if (error) {
                DbrDoomMod.logger().warn("[doom] {}", text);
            } else {
                DbrDoomMod.logger().info("[doom] {}", text);
            }
        }

        private static boolean isEngineThread() {
            return ENGINE_THREAD_NAME.equals(Thread.currentThread().getName());
        }
    }
}
