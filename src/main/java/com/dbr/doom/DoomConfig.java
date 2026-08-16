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

import net.minecraftforge.common.config.Configuration;

import org.lwjgl.input.Keyboard;

/**
 * Reads config/dbrdoom.cfg.
 *
 * Deliberately does not duplicate Doom's own settings. The engine already has
 * an options menu, and its config now persists to config/dbrdoom/default.cfg,
 * so screen size, mouse sensitivity and key bindings belong there. Two places
 * setting the same value would only fight each other. What lives here is the
 * things Doom cannot know about, like how loudly Minecraft should play while a
 * session is open.
 */
public final class DoomConfig {

    private static final String CATEGORY_CONTROLS = "controls";
    private static final String CATEGORY_VIDEO = "video";
    private static final String CATEGORY_AUDIO = "audio";

    /** LWJGL key code that leaves a session. */
    private static int exitKey = Keyboard.KEY_F10;

    private static int escapeDoubleTapMs = 400;
    private static boolean showHint = true;
    private static int hintDurationMs = 5000;

    private static int internalScale = 1;
    private static float arcadeViewDistance = 0.7F;
    private static boolean detachedCamera = true;

    private static boolean duckMinecraft = true;
    private static float duckLevel = 0.15F;

    /** Doom volumes, 0-15. -1 leaves whatever the engine's own menu has saved. */
    private static int sfxVolume = -1;
    private static int musicVolume = -1;

    private DoomConfig() {
    }

    public static void load(File file) {
        final Configuration config = new Configuration(file);
        config.load();

        final String exitKeyName = config.getString(
            "exitKey", CATEGORY_CONTROLS, "F10",
            "Key that returns to Minecraft. LWJGL name, for example F10, GRAVE or END.\n"
            + "A double tap of ESCAPE always works as well, so this is a convenience.");

        final int resolved = Keyboard.getKeyIndex(exitKeyName.toUpperCase());
        if (resolved == Keyboard.KEY_NONE) {
            DbrDoomMod.logger().warn(
                "Unknown exitKey '{}' in dbrdoom.cfg, falling back to F10", exitKeyName);
            exitKey = Keyboard.KEY_F10;
        } else {
            exitKey = resolved;
        }

        escapeDoubleTapMs = config.getInt(
            "escapeDoubleTapMs", CATEGORY_CONTROLS, 400, 100, 2000,
            "How close together two ESCAPE presses must be to leave Doom.\n"
            + "A single ESCAPE opens Doom's own menu, as it does in the real game.");

        showHint = config.getBoolean(
            "showHint", CATEGORY_CONTROLS, true,
            "Show the 'how to quit' reminder when a session starts.");

        hintDurationMs = config.getInt(
            "hintDurationMs", CATEGORY_CONTROLS, 5000, 0, 60000,
            "How long that reminder stays on screen.");

        internalScale = config.getInt(
            "internalScale", CATEGORY_VIDEO, 1, 1, 5,
            "Doom's internal render size, as a multiple of 320x200.\n"
            + "This is NOT an upscale: the software renderer draws every pixel, so 2\n"
            + "costs four times the CPU and 3 costs nine. The picture is stretched to\n"
            + "your window by the GPU either way, so 1 is almost always the right answer.\n"
            + "Raise it only if the pixels look too chunky and you have CPU to spare.");

        arcadeViewDistance = config.getFloat(
            "arcadeViewDistance", CATEGORY_VIDEO, 0.7F, 0.4F, 4.0F,
            "How far from the cabinet screen you stand when using it, in blocks.\n"
            + "Smaller fills more of your view. Note that your height is never\n"
            + "changed, so a cabinet sitting on the floor is always viewed from\n"
            + "above, at a steep angle. Mount it one block up, so its middle is\n"
            + "near eye level, for a head-on view.");

        detachedCamera = config.getBoolean(
            "detachedCamera", CATEGORY_VIDEO, true,
            "Detach the camera from your body while using a cabinet.\n"
            + "On: the view sits level with the screen whatever height the cabinet\n"
            + "is at, and your body stays standing where it was.\n"
            + "Off: your body is walked in front and its head aimed, so a cabinet\n"
            + "on the floor is seen from above.\n"
            + "TURN THIS OFF if the game freezes when you use a cabinet. A build\n"
            + "using this froze with no crash report and the cause is not yet known.");

        duckMinecraft = config.getBoolean(
            "duckMinecraft", CATEGORY_AUDIO, true,
            "Lower Minecraft's volume while a Doom session is open.\n"
            + "Doom uses Java Sound and Minecraft uses OpenAL, so without this both\n"
            + "play at full volume on top of each other.");

        duckLevel = config.getFloat(
            "duckLevel", CATEGORY_AUDIO, 0.15F, 0.0F, 1.0F,
            "Minecraft's master volume while Doom is open, as a fraction of normal.\n"
            + "0 mutes it completely. Your normal level is restored on exit.");

        sfxVolume = config.getInt(
            "sfxVolume", CATEGORY_AUDIO, -1, -1, 15,
            "Doom sound effect volume, 0-15. Leave at -1 to use whatever Doom's own\n"
            + "options menu has saved, which is usually what you want.");

        musicVolume = config.getInt(
            "musicVolume", CATEGORY_AUDIO, -1, -1, 15,
            "Doom music volume, 0-15. Leave at -1 to use Doom's own setting.");

        if (config.hasChanged()) {
            config.save();
        }
    }

    public static int exitKey() {
        return exitKey;
    }

    public static int escapeDoubleTapMs() {
        return escapeDoubleTapMs;
    }

    public static boolean showHint() {
        return showHint;
    }

    public static int hintDurationMs() {
        return hintDurationMs;
    }

    public static int internalScale() {
        return internalScale;
    }

    public static float arcadeViewDistance() {
        return arcadeViewDistance;
    }

    public static boolean detachedCamera() {
        return detachedCamera;
    }

    public static boolean duckMinecraft() {
        return duckMinecraft;
    }

    public static float duckLevel() {
        return duckLevel;
    }

    public static int sfxVolume() {
        return sfxVolume;
    }

    public static int musicVolume() {
        return musicVolume;
    }
}
