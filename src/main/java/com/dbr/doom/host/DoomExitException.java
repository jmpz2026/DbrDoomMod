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

/**
 * Replaces the System.exit() calls of the vendored Doom engine.
 *
 * Standalone Mocha Doom quits by calling System.exit() in 18 places.
 * Inside Minecraft that would take the whole game down with it, so the vendoring
 * script rewrites every one of them into a throw of this class. The Doom thread
 * catches it, tears the session down and closes the GUI.
 *
 * The engine is full of broad catch (Exception e) blocks. A
 * RuntimeException would get swallowed by one of them and the engine would keep
 * running in a state it had already decided was unrecoverable. System.exit()
 * could not be swallowed, and neither should its replacement, so this extends
 * Error to preserve the original semantics.
 *
 * @see com.dbr.doom.host.DoomHost
 */
public class DoomExitException extends Error {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public DoomExitException(int exitCode) {
        super("Doom engine requested exit with code " + exitCode);
        this.exitCode = exitCode;
    }

    /** The code the engine originally passed to System.exit(). */
    public int getExitCode() {
        return exitCode;
    }

    /** A non-zero code means the engine hit an error rather than quitting cleanly. */
    public boolean isError() {
        return exitCode != 0;
    }
}
