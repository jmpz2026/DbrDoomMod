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

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Draws the arcade block's placeholder textures.
 *
 * Generated rather than hand-drawn so they can be regenerated and tweaked
 * without a paint program, and so the palette lives in one place. These are
 * placeholders: replace the PNGs and nothing in the code needs to change.
 *
 * Run: javac -d out tools/GenerateTextures.java && java -cp out GenerateTextures <outputDir>
 */
public final class GenerateTextures {

    private static final int SIZE = 16;

    // Dark casing, in the spirit of a beige-and-grey 90s tower gone matte black.
    private static final int CASE_DARK = 0xFF1A1A1F;
    private static final int CASE_MID = 0xFF2A2A32;
    private static final int CASE_LIGHT = 0xFF3A3A45;
    private static final int CASE_EDGE = 0xFF12121A;

    // Amber phosphor, the colour of a terminal that has been on too long.
    private static final int SCREEN_BG = 0xFF0A0A08;
    private static final int SCREEN_DIM = 0xFF3A2A08;
    private static final int AMBER = 0xFFFFB020;
    private static final int AMBER_DARK = 0xFF8A5C10;

    public static void main(String[] args) throws IOException {
        final File dir = new File(args.length > 0 ? args[0] : ".");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }

        write(dir, "doom_arcade_side.png", side());
        write(dir, "doom_arcade_top.png", top());
        write(dir, "doom_arcade_bottom.png", bottom());
        write(dir, "doom_arcade_front.png", front());

        System.out.println("wrote 4 textures to " + dir.getAbsolutePath());
    }

    /** Casing with cooling vents. */
    private static BufferedImage side() {
        final BufferedImage img = base(CASE_MID);
        for (int y = 3; y <= 12; y += 3) {
            for (int x = 3; x <= 12; x++) {
                img.setRGB(x, y, CASE_DARK);
                img.setRGB(x, y + 1, CASE_LIGHT);
            }
        }
        border(img, CASE_EDGE);
        return img;
    }

    /** Plain casing with a subtle highlight so the top does not look flat. */
    private static BufferedImage top() {
        final BufferedImage img = base(CASE_MID);
        for (int x = 2; x < SIZE - 2; x++) {
            img.setRGB(x, 2, CASE_LIGHT);
        }
        border(img, CASE_EDGE);
        return img;
    }

    private static BufferedImage bottom() {
        final BufferedImage img = base(CASE_DARK);
        border(img, CASE_EDGE);
        return img;
    }

    /**
     * Bezel around a dark screen showing two short amber bars.
     *
     * At 16x16 there is no room for readable words, so the bars stand in for
     * the "insert coin" line. The renderer draws the live game over this same
     * area, so this is what the block looks like when nobody is playing.
     */
    private static BufferedImage front() {
        final BufferedImage img = base(CASE_MID);

        // Screen recess
        for (int y = 2; y < 13; y++) {
            for (int x = 2; x < 14; x++) {
                img.setRGB(x, y, SCREEN_BG);
            }
        }

        // Faint scanlines, so the dark area reads as glass and not a hole
        for (int y = 3; y < 12; y += 2) {
            for (int x = 3; x < 13; x++) {
                img.setRGB(x, y, SCREEN_DIM);
            }
        }

        // Two amber bars suggesting a line of text
        for (int x = 4; x <= 11; x++) {
            img.setRGB(x, 6, AMBER);
        }
        for (int x = 5; x <= 10; x++) {
            img.setRGB(x, 8, AMBER_DARK);
        }

        // Bezel highlight along the top of the recess
        for (int x = 2; x < 14; x++) {
            img.setRGB(x, 1, CASE_LIGHT);
        }

        // Power light
        img.setRGB(14, 13, AMBER);

        border(img, CASE_EDGE);
        return img;
    }

    private static BufferedImage base(int fill) {
        final BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                img.setRGB(x, y, fill);
            }
        }
        return img;
    }

    private static void border(BufferedImage img, int colour) {
        for (int i = 0; i < SIZE; i++) {
            img.setRGB(i, 0, colour);
            img.setRGB(i, SIZE - 1, colour);
            img.setRGB(0, i, colour);
            img.setRGB(SIZE - 1, i, colour);
        }
    }

    private static void write(File dir, String name, BufferedImage img) throws IOException {
        ImageIO.write(img, "PNG", new File(dir, name));
    }

    private GenerateTextures() {
    }
}
