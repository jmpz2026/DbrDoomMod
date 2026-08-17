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

import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.block.TileEntityDoomArcade;
import com.dbr.doom.host.DoomHost;

/**
 * Draws the live game on the screen face of a cabinet.
 *
 * Only the player currently holding the cabinet sees a picture. Everyone else
 * sees the block's own front texture, the dark screen with the amber bars,
 * because their client is not running an engine and has no frame to show. That
 * is honest: each client simulates its own Doom, so a shared picture would be a
 * lie about a game state they do not have.
 *
 * The texture upload happens once per frame in {@link DoomScreenTexture}, not
 * here. This method runs once per cabinet per frame, so all it does is bind and
 * draw.
 */
public class TileEntityDoomArcadeRenderer extends TileEntitySpecialRenderer {

    /** Lightmap coordinate for "as bright as the game can draw". */
    private static final float MAX_LIGHT = 240.0F;

    /** Same idea for the tessellator's own per-vertex brightness. */
    private static final int FULLBRIGHT = 15728880;

    /** Lifts the screen off the block face so the two do not z-fight. */
    private static final double EPSILON = 0.002D;

    /*
     * The screen sits inside the bezel drawn in the block texture, and is a
     * true 4:3 rectangle: 0.75 wide by 0.5625 tall. Doom's pixels are not
     * square, so matching 320x200 here would stretch everything vertically.
     */
    private static final double LEFT = 0.125D;
    private static final double RIGHT = 0.875D;
    private static final double BOTTOM = 0.25D;
    private static final double TOP = 0.8125D;

    /** How many cabinets are remembered for throttling purposes. */
    private static final int DIAGNOSTIC_MEMORY = 32;

    /**
     * When each cabinet last explained itself, keyed by position.
     *
     * One renderer instance serves every cabinet, so a single timestamp let the
     * nearest one starve the rest: with two in view, the one being played
     * reported nothing and the log only ever showed the idle one next to it.
     *
     * Bounded, oldest first: otherwise it keeps an entry per cabinet ever seen,
     * a slow leak in a map that exists only to rate limit a log line.
     */
    private final Map<Long, Long> lastDiagnostic =
        new LinkedHashMap<Long, Long>(DIAGNOSTIC_MEMORY, 0.75F, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > DIAGNOSTIC_MEMORY;
            }
        };

    /** Proves the quad is actually drawn, and on which face. */
    private boolean loggedFirstDraw;

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof TileEntityDoomArcade)) {
            return;
        }

        final TileEntityDoomArcade arcade = (TileEntityDoomArcade) tile;
        if (!shouldRenderLiveScreen(arcade)) {
            return;
        }

        final int facing = tile.getWorldObj().getBlockMetadata(
            tile.xCoord, tile.yCoord, tile.zCoord);

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            /*
             * The facing matters more than anything else here. Only 2 to 5 are
             * real orientations; anything else falls through the switch to east
             * and would paint the screen on a different face than the bezel in
             * the block texture, which looks exactly like nothing rendering.
             */
            DbrDoomMod.logger().info(
                "Arcade drawing screen at {} {} {}, facing={} ({})",
                new Object[] {
                    Integer.valueOf(tile.xCoord),
                    Integer.valueOf(tile.yCoord),
                    Integer.valueOf(tile.zCoord),
                    Integer.valueOf(facing),
                    describeFacing(facing)
                });
        }

        /*
         * Minecraft lights the world with a second texture unit, not with
         * GL_LIGHTING, so turning lighting off is not enough to make a screen
         * glow: the quad inherits whatever lightmap coordinate was last set and
         * gets multiplied down to black. Forcing the lightmap to full brightness
         * is what actually makes it emit light.
         */
        final float lastBrightnessX = OpenGlHelper.lastBrightnessX;
        final float lastBrightnessY = OpenGlHelper.lastBrightnessY;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);

        try {
            GL11.glTranslated(x, y, z);

            bindTexture(DoomScreenTexture.location());

            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit, MAX_LIGHT, MAX_LIGHT);

            /*
             * The screen is opaque, so blending only costs fill rate. Culling is
             * off because the quad is one sided and this saves having to get the
             * winding right for all four facings.
             */
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            // Keep Doom's pixels sharp instead of smearing them.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            drawScreen(facing);
        } finally {
            /*
             * Restoring state is not optional. A tile entity renderer that
             * leaves lighting or culling off is the classic cause of "some other
             * mod started rendering wrong". glPopAttrib does not cover the
             * lightmap, so that is put back by hand.
             */
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

    private static String describeFacing(int facing) {
        switch (facing) {
            case 2:
                return "north";
            case 3:
                return "south";
            case 4:
                return "west";
            case 5:
                return "east";
            default:
                return "INVALID, falling through to east";
        }
    }

    /**
     * True only when this client is the one playing on this cabinet and it has
     * a frame ready.
     */
    private boolean shouldRenderLiveScreen(TileEntityDoomArcade arcade) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String me = mc.thePlayer == null ? null : mc.thePlayer.getCommandSenderName();

        final boolean occupied = arcade.isOccupied();
        final boolean textureReady = DoomScreenTexture.isReady();
        final boolean mine = arcade.isOccupiedBy(mc.thePlayer);

        if (occupied && textureReady && mine) {
            return true;
        }

        explainIdleScreen(arcade, occupied, textureReady, mine, arcade.getOccupant(), me);
        return false;
    }

    /**
     * Reports why a cabinet is showing its idle face while a session is running.
     *
     * Three separate things have to line up before a picture appears, and from
     * the outside all three failures look identical. Throttled to once a second
     * per cabinet because this runs per cabinet per frame, and silent unless a
     * session is actually running, so a world full of unused cabinets stays
     * quiet. The coordinates are logged because with more than one cabinet in
     * view the line is meaningless without them.
     */
    private void explainIdleScreen(TileEntityDoomArcade arcade, boolean occupied,
            boolean textureReady, boolean mine, String occupant, String me) {

        if (!DoomHost.isRunning()) {
            return;
        }

        final Long key = Long.valueOf(positionKey(arcade));
        final long now = System.currentTimeMillis();
        final Long last = lastDiagnostic.get(key);
        if (last != null && now - last.longValue() < 1000L) {
            return;
        }
        lastDiagnostic.put(key, Long.valueOf(now));

        DbrDoomMod.logger().info(
            "Arcade idle at {} {} {} while a session runs: occupied={} textureReady={} mine={} occupant={} me={}",
            new Object[] {
                Integer.valueOf(arcade.xCoord),
                Integer.valueOf(arcade.yCoord),
                Integer.valueOf(arcade.zCoord),
                Boolean.valueOf(occupied),
                Boolean.valueOf(textureReady),
                Boolean.valueOf(mine),
                String.valueOf(occupant),
                String.valueOf(me)
            });
    }

    /** Packs a cabinet's position into one key. Y is only ever 0-255. */
    private static long positionKey(TileEntityDoomArcade arcade) {
        return ((long) arcade.xCoord & 0x3FFFFFFL) << 38
            | ((long) arcade.zCoord & 0x3FFFFFFL) << 12
            | ((long) arcade.yCoord & 0xFFFL);
    }

    /**
     * Emits the screen quad for one of the four horizontal facings.
     *
     * The corners are ordered so that the texture's top left lands at the top
     * left as seen by somebody standing in front of that face. Getting this
     * wrong mirrors the picture rather than hiding it.
     */
    private void drawScreen(int facing) {
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();

        // Belt and braces with the lightmap above: the tessellator writes its
        // own brightness per vertex, and a stale dark value would win.
        t.setBrightness(FULLBRIGHT);
        t.setColorOpaque_F(1.0F, 1.0F, 1.0F);

        switch (facing) {
            case 2: // north, screen faces -Z; viewer there sees +X on their left
                t.addVertexWithUV(RIGHT, TOP, -EPSILON, 0.0D, 0.0D);
                t.addVertexWithUV(LEFT, TOP, -EPSILON, 1.0D, 0.0D);
                t.addVertexWithUV(LEFT, BOTTOM, -EPSILON, 1.0D, 1.0D);
                t.addVertexWithUV(RIGHT, BOTTOM, -EPSILON, 0.0D, 1.0D);
                break;

            case 3: // south, screen faces +Z
                t.addVertexWithUV(LEFT, TOP, 1.0D + EPSILON, 0.0D, 0.0D);
                t.addVertexWithUV(RIGHT, TOP, 1.0D + EPSILON, 1.0D, 0.0D);
                t.addVertexWithUV(RIGHT, BOTTOM, 1.0D + EPSILON, 1.0D, 1.0D);
                t.addVertexWithUV(LEFT, BOTTOM, 1.0D + EPSILON, 0.0D, 1.0D);
                break;

            case 4: // west, screen faces -X
                t.addVertexWithUV(-EPSILON, TOP, LEFT, 0.0D, 0.0D);
                t.addVertexWithUV(-EPSILON, TOP, RIGHT, 1.0D, 0.0D);
                t.addVertexWithUV(-EPSILON, BOTTOM, RIGHT, 1.0D, 1.0D);
                t.addVertexWithUV(-EPSILON, BOTTOM, LEFT, 0.0D, 1.0D);
                break;

            default: // east, screen faces +X
                t.addVertexWithUV(1.0D + EPSILON, TOP, RIGHT, 0.0D, 0.0D);
                t.addVertexWithUV(1.0D + EPSILON, TOP, LEFT, 1.0D, 0.0D);
                t.addVertexWithUV(1.0D + EPSILON, BOTTOM, LEFT, 1.0D, 1.0D);
                t.addVertexWithUV(1.0D + EPSILON, BOTTOM, RIGHT, 0.0D, 1.0D);
                break;
        }

        t.draw();
    }
}
