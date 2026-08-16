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

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * An invisible eye that Minecraft can render the world from.
 *
 * Minecraft draws the world from {@code Minecraft.renderViewEntity}, which does
 * not have to be the player. Pointing it at one of these puts the camera exactly
 * in front of an arcade screen without touching the player's body, which is the
 * only way to get a level view of a cabinet standing on the floor: its screen is
 * at knee height, so lining a standing player's eyes up with it would mean
 * burying them a metre in the ground.
 *
 * <h3>Known problem</h3>
 *
 * An earlier build using this froze the client hard enough to need killing, with
 * no crash report and no Java exception. The cause is not yet understood, which
 * is why {@link com.dbr.doom.DoomConfig#detachedCamera()} exists: it lets the
 * whole approach be switched off without a rebuild.
 *
 * This is never added to the world. It only needs to exist and hold a position;
 * spawning it would sync a pointless entity to the server and draw a body.
 */
public class EntityDoomCamera extends EntityLivingBase {

    public EntityDoomCamera(World world) {
        super(world);
        setSize(0.1F, 0.1F);
        noClip = true;

        /*
         * Not cosmetic, and not optional. Minecraft places the camera at
         * posY - (yOffset - 1.62), a leftover of the old convention where an
         * entity's posY was its eye level rather than its feet. Left at the
         * default of zero, that works out to posY + 1.62 and the view floats a
         * block and a half above wherever the camera was put, which for a
         * cabinet means looking down from above the machine. Setting it to 1.62
         * cancels the term so the camera sits exactly where it is placed.
         */
        yOffset = 1.62F;
    }

    /**
     * Puts the camera somewhere and points it.
     *
     * The previous and last-tick positions are set to the same values because
     * nothing ticks this entity, and the renderer interpolates between them. Left
     * at zero, the view would be dragged from the world origin every frame.
     */
    public void place(double x, double y, double z, float yaw, float pitch) {
        setPosition(x, y, z);

        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        lastTickPosX = posX;
        lastTickPosY = posY;
        lastTickPosZ = posZ;

        rotationYaw = yaw;
        prevRotationYaw = yaw;
        rotationYawHead = yaw;
        prevRotationYawHead = yaw;

        rotationPitch = pitch;
        prevRotationPitch = pitch;
    }

    /** Zero, so the camera sits exactly at the position it was given. */
    @Override
    public float getEyeHeight() {
        return 0.0F;
    }

    @Override
    public ItemStack getHeldItem() {
        return null;
    }

    @Override
    public ItemStack getEquipmentInSlot(int slot) {
        return null;
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {
        // Nothing to equip: this is an eye, not a body.
    }

    @Override
    public ItemStack[] getLastActiveItems() {
        return new ItemStack[0];
    }
}
