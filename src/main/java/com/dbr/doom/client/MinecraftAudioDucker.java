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

package com.dbr.doom.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.DoomConfig;

/**
 * Turns Minecraft's volume down while Doom plays, and back up afterwards.
 *
 * Doom goes through Java Sound and Minecraft through OpenAL, so neither knows
 * the other exists and both would play at full volume at once.
 *
 * The state is static and guarded by a shutdown hook on purpose. Minecraft
 * writes options.txt when it exits, so a player who quits the game with a Doom
 * session still open would otherwise find their master volume permanently stuck
 * at the ducked level, with nothing to explain why.
 */
public final class MinecraftAudioDucker {

    /** Volume before ducking. Negative means we are not currently ducking. */
    private static volatile float savedVolume = -1.0F;

    /** What we actually set, so a player who moves the slider is not overruled. */
    private static volatile float duckedTo = -1.0F;

    private static boolean hookInstalled;

    private MinecraftAudioDucker() {
    }

    public static synchronized void duck() {
        if (!DoomConfig.duckMinecraft() || savedVolume >= 0.0F) {
            return;
        }

        final Minecraft mc = Minecraft.getMinecraft();
        savedVolume = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        duckedTo = savedVolume * DoomConfig.duckLevel();
        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, duckedTo);

        installShutdownHook();
    }

    public static synchronized void restore() {
        if (savedVolume < 0.0F) {
            return;
        }

        final float volume = savedVolume;
        final float expected = duckedTo;
        savedVolume = -1.0F;
        duckedTo = -1.0F;

        final Minecraft mc = Minecraft.getMinecraft();

        /*
         * Only put back what we took. Doom's session can be long, and a player
         * who opened Minecraft's own options in the meantime and moved the
         * master slider means it: restoring the old value here would silently
         * undo their change, which reads as the setting refusing to stick.
         */
        if (Math.abs(mc.gameSettings.getSoundLevel(SoundCategory.MASTER) - expected) > 0.001F) {
            return;
        }

        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, volume);
    }

    /**
     * Last line of defence for a hard exit. Restores the setting in memory so
     * that whatever Minecraft writes on the way out is the player's own value.
     */
    private static void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    restore();
                } catch (Throwable t) {
                    // Shutting down anyway; nothing useful left to do.
                    DbrDoomMod.logger().warn("Could not restore Minecraft volume on exit", t);
                }
            }
        }, "DbrDoom-VolumeRestore"));
    }
}
