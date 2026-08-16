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

package com.dbr.doom;

import java.io.File;

/**
 * Everything that exists on both sides.
 *
 * The Doom engine, the GUI and the renderer are client-only. A dedicated server
 * would crash simply by resolving classes that mention them, so those live in
 * ClientProxy and the server gets the empty versions here.
 */
public class CommonProxy {

    /** Server side: no config to read, since nothing here plays Doom. */
    public void preInit(File suggestedConfigFile, File baseDir) {
    }

    public void init() {
    }

    /** Server side: never called, the packet only ever travels to a client. */
    public void openArcade(int x, int y, int z) {
    }
}
