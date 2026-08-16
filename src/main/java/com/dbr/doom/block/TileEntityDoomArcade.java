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

package com.dbr.doom.block;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * One arcade cabinet. Tracks who, if anyone, is currently playing on it.
 *
 * The occupant is decided by the server and pushed to nearby clients, so every
 * client can tell whether to draw a live screen or the idle one. The game state
 * itself is never here: each client runs its own engine, and only the player
 * holding the claim runs one at all.
 */
public class TileEntityDoomArcade extends TileEntity {

    private static final String NBT_OCCUPANT = "Occupant";
    private static final String NBT_OCCUPANT_ID = "OccupantId";

    /**
     * Who holds the cabinet, or null when it is free. Authoritative on the
     * server.
     *
     * The name is what gets shown to people; the identity is the UUID, so a
     * renamed account does not leave a claim nobody can match. The name is still
     * compared as a fallback: a client whose profile carries no UUID would
     * otherwise never recognise its own cabinet and would render an idle screen
     * while playing.
     */
    private UUID occupantId;
    private String occupantName;

    public boolean isOccupied() {
        return occupantName != null;
    }

    /** The occupant's name, for showing to a player. */
    public String getOccupant() {
        return occupantName;
    }

    public boolean isOccupiedBy(EntityPlayer player) {
        if (occupantName == null || player == null) {
            return false;
        }
        if (occupantId != null) {
            final UUID id = player.getUniqueID();
            if (id != null) {
                return occupantId.equals(id);
            }
        }
        return occupantName.equals(player.getCommandSenderName());
    }

    /**
     * Tries to give the cabinet to a player.
     *
     * @return false if somebody else is already on it
     */
    public boolean claim(EntityPlayer player) {
        if (occupantName != null && !isOccupiedBy(player)) {
            return false;
        }

        occupantId = player.getUniqueID();
        occupantName = player.getCommandSenderName();
        sync();
        return true;
    }

    /** Releases the cabinet, but only for the player actually holding it. */
    public void releaseIfHeldBy(EntityPlayer player) {
        if (isOccupiedBy(player)) {
            clearOccupant();
        }
    }

    /** Releases regardless of who holds it. For block removal and a vanished occupant. */
    public void forceRelease() {
        if (occupantName != null) {
            clearOccupant();
        }
    }

    private void clearOccupant() {
        occupantId = null;
        occupantName = null;
        sync();
    }

    /**
     * Pushes the new state to clients. Occupancy changes are rare, a few times a
     * session, so a full block update is cheaper than maintaining a watch list.
     */
    private void sync() {
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeOccupant(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        /*
         * Deliberately not restored from disk. A claim cannot outlive the
         * session that made it: a server that crashes mid-game would otherwise
         * reload with a cabinet nobody can ever use again.
         */
        occupantId = null;
        occupantName = null;
    }

    @Override
    public Packet getDescriptionPacket() {
        final NBTTagCompound tag = new NBTTagCompound();
        writeOccupant(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity packet) {
        final NBTTagCompound tag = packet.func_148857_g();

        occupantName = tag.hasKey(NBT_OCCUPANT) ? tag.getString(NBT_OCCUPANT) : null;
        occupantId = null;

        if (tag.hasKey(NBT_OCCUPANT_ID)) {
            try {
                occupantId = UUID.fromString(tag.getString(NBT_OCCUPANT_ID));
            } catch (IllegalArgumentException e) {
                // Nothing to do but fall back to matching on the name.
            }
        }
    }

    private void writeOccupant(NBTTagCompound tag) {
        if (occupantName == null) {
            return;
        }
        tag.setString(NBT_OCCUPANT, occupantName);
        if (occupantId != null) {
            tag.setString(NBT_OCCUPANT_ID, occupantId.toString());
        }
    }

    /** How often the server checks that the occupant is still around. */
    private static final int CLAIM_CHECK_INTERVAL = 40;

    private int claimCheckTimer;

    /**
     * Frees a cabinet whose occupant has vanished.
     *
     * A client that disconnects, crashes or changes dimension never sends the
     * release packet, and without this the cabinet would stay claimed until the
     * chunk unloaded. Checked twice a second, and only while occupied.
     */
    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote || occupantName == null) {
            return;
        }

        if (++claimCheckTimer < CLAIM_CHECK_INTERVAL) {
            return;
        }
        claimCheckTimer = 0;

        if (findOccupant() == null) {
            forceRelease();
        }
    }

    /**
     * The occupant, if they are still in this world.
     *
     * By UUID where there is one, since that survives a rename; func_152378_a is
     * getPlayerEntityByUUID, which has no MCP name in 1.7.10.
     */
    private EntityPlayer findOccupant() {
        if (occupantId != null) {
            final EntityPlayer byId = worldObj.func_152378_a(occupantId);
            if (byId != null) {
                return byId;
            }
        }
        return worldObj.getPlayerEntityByName(occupantName);
    }

    /** The renderer needs this before the block is anywhere near the player. */
    @Override
    public double getMaxRenderDistanceSquared() {
        // 32 blocks. Past that the screen is a couple of pixels and not worth drawing.
        return 1024.0D;
    }
}
