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

import org.lwjgl.input.Keyboard;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.DoomConfig;
import com.dbr.doom.host.DoomHost;

/**
 * Playing at the cabinet itself, with the world still visible around it.
 *
 * This screen draws almost nothing. Minecraft renders the world before any GUI,
 * so leaving the canvas alone means the world shows through, and the picture
 * comes from the cabinet's own renderer. What this class adds on top of its
 * parent is holding the player in front of the machine and deciding when to let
 * them go.
 *
 * Input, audio ducking and session teardown are inherited unchanged: a GuiScreen
 * captures the keyboard whether or not it paints anything, which is what makes
 * the whole approach cheap.
 */
public class GuiDoomImmersive extends GuiDoom {

    /** Vertical centre of the screen quad, matching the renderer. */
    private static final double SCREEN_CENTRE_Y = 0.53125D;

    private double anchorX;
    private double anchorY;
    private double anchorZ;

    /** Non-null only when the detached camera is in use. */
    private EntityDoomCamera camera;
    private float anchorYaw;
    private float anchorPitch;
    private boolean anchored;

    /** Health when the session started, so any loss can end it. */
    private float healthOnOpen = -1.0F;

    public GuiDoomImmersive(DoomHost host, int blockX, int blockY, int blockZ) {
        super(host, blockX, blockY, blockZ);
    }

    @Override
    public void initGui() {
        super.initGui();

        // initGui runs again on every window resize; the anchor is set once.
        if (!anchored) {
            anchored = anchor();
        }
    }

    /**
     * Walks the player in front of the screen and aims their head at it.
     *
     * Their height is deliberately not touched. Lining a standing player's eyes
     * up with a cabinet that sits on the floor would mean sinking them a metre
     * into the ground, so instead they stand where they can and look down at it,
     * the way you would at a real cabinet. Mounting the cabinet a block up puts
     * its middle near eye level and gives a head-on view.
     *
     * A detached camera entity would remove that limitation, and was tried, but
     * it froze the client hard enough to need killing, so it is not worth
     * shipping until that is understood.
     *
     * @return false if the world is not in a state to anchor against
     */
    private boolean anchor() {
        final EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return false;
        }

        /*
         * The cabinet has to still be there. Reading metadata off whatever
         * happens to occupy those coordinates gives a facing that means nothing,
         * and the player is then anchored looking at a wall.
         */
        if (mc.theWorld.getBlock(blockX, blockY, blockZ) != DbrDoomMod.arcadeBlock) {
            return false;
        }

        healthOnOpen = player.getHealth();

        final int facing = mc.theWorld.getBlockMetadata(blockX, blockY, blockZ);

        // Outward normal of the screen face, matching the renderer's facings.
        double nx = 0.0D;
        double nz = 0.0D;
        switch (facing) {
            case 2:
                nz = -1.0D;
                break;
            case 3:
                nz = 1.0D;
                break;
            case 4:
                nx = -1.0D;
                break;
            default:
                nx = 1.0D;
                break;
        }

        final double screenX = blockX + 0.5D + nx * 0.5D;
        final double screenY = blockY + SCREEN_CENTRE_Y;
        final double screenZ = blockZ + 0.5D + nz * 0.5D;

        final double distance = DoomConfig.arcadeViewDistance();
        anchorX = screenX + nx * distance;
        anchorZ = screenZ + nz * distance;

        if (DoomConfig.detachedCamera()) {
            anchorY = screenY;
            // Looking back along the face normal, so pitch is exactly level.
            anchorYaw = (float) (Math.atan2(-nz, -nx) * 180.0D / Math.PI) - 90.0F;
            anchorPitch = 0.0F;

            /*
             * Logged step by step because a previous build froze somewhere in
             * here with no exception and no crash report. Whichever line is the
             * last one in the log is the one that did not return.
             */
            DbrDoomMod.logger().info("Camera: constructing entity");
            camera = new EntityDoomCamera(mc.theWorld);

            DbrDoomMod.logger().info("Camera: placing at {} {} {} yaw={}",
                new Object[] {
                    Double.valueOf(anchorX), Double.valueOf(anchorY),
                    Double.valueOf(anchorZ), Float.valueOf(anchorYaw)
                });
            camera.place(anchorX, anchorY, anchorZ, anchorYaw, anchorPitch);

            DbrDoomMod.logger().info("Camera: taking over renderViewEntity");
            mc.renderViewEntity = camera;

            DbrDoomMod.logger().info("Camera: attached");
            return true;
        }

        player.setPositionAndUpdate(anchorX, player.posY, anchorZ);

        // Aim from the eyes, not the feet, or the screen sits too high in view.
        final double eyeY = player.posY + player.getEyeHeight();
        final double dx = screenX - anchorX;
        final double dy = screenY - eyeY;
        final double dz = screenZ - anchorZ;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);

        anchorYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        anchorPitch = (float) (-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));

        applyRotation(player);
        return true;
    }

    /** Gives the view back to the player, whatever happened. */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();

        if (camera != null) {
            mc.renderViewEntity = mc.thePlayer;
            camera = null;
            DbrDoomMod.logger().info("Camera: released back to the player");
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        final EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || !anchored) {
            return;
        }

        if (endedByWorld(player)) {
            return;
        }

        /*
         * The camera holds still by itself, but the player can still be shoved
         * around by knockback or pistons. Their horizontal motion is cancelled
         * so they stay at the machine; gravity is left alone.
         */
        player.motionX = 0.0D;
        player.motionZ = 0.0D;

        if (camera != null) {
            // Nothing ticks this entity, so its position is refreshed by hand.
            camera.place(anchorX, anchorY, anchorZ, anchorYaw, anchorPitch);
            mc.renderViewEntity = camera;
        } else {
            applyRotation(player);
        }
    }

    /**
     * Ends the session when the world says it should.
     *
     * @return true if the session was closed
     */
    private boolean endedByWorld(EntityClientPlayerMP player) {
        if (healthOnOpen >= 0.0F && player.getHealth() < healthOnOpen) {
            say(EnumChatFormatting.RED + "Something hit you. Doom paused.");
            close();
            return true;
        }

        // Somebody mined the cabinet out from under the session.
        if (mc.theWorld != null
                && mc.theWorld.getBlock(blockX, blockY, blockZ) != DbrDoomMod.arcadeBlock) {
            close();
            return true;
        }

        return false;
    }

    private void applyRotation(EntityClientPlayerMP player) {
        player.rotationYaw = anchorYaw;
        player.rotationPitch = anchorPitch;
        // Without the previous values the renderer interpolates from the old
        // angles and the first frames swing wildly.
        player.prevRotationYaw = anchorYaw;
        player.prevRotationPitch = anchorPitch;
        player.setRotationYawHead(anchorYaw);
    }

    /**
     * Draws nothing but the hint, so the world and the cabinet show through.
     *
     * The game itself is drawn by the cabinet's renderer, from the same texture
     * this class never touches.
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!DoomScreenTexture.isReady()) {
            drawCenteredString(fontRendererObj, "Loading Doom...",
                width / 2, height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        if (DoomConfig.showHint()
                && System.currentTimeMillis() - openedAt < DoomConfig.hintDurationMs()) {
            final String hint = Keyboard.getKeyName(DoomConfig.exitKey())
                + " or double-tap ESC to step away";
            drawCenteredString(fontRendererObj, hint, width / 2, height - 12, 0xFFFFAA00);
        }
    }
}
