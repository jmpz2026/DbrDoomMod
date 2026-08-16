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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Keeps a player to one cabinet at a time.
 *
 * A claim is granted on right click and handed back by the client when it is
 * done. Nothing stopped a client from simply never handing it back, so anyone
 * could walk a server clicking every cabinet in it and leave them all occupied
 * until they logged out. Granting a new claim now drops the previous one, which
 * makes that impossible to do with more than one machine.
 *
 * Server side only. Entries that go stale - the cabinet was broken, its chunk
 * unloaded, someone else ended up with it - are harmless: releasing goes through
 * releaseIfHeldBy, which does nothing unless the claim is really theirs.
 */
public final class ArcadeClaims {

    /** Where each player's current cabinet is. Keyed by UUID, not by name. */
    private static final Map<UUID, Location> HELD = new HashMap<UUID, Location>();

    private ArcadeClaims() {
    }

    /** A cabinet, in a particular world. */
    private static final class Location {

        private final int dimension;
        private final int x;
        private final int y;
        private final int z;

        Location(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean isAt(int otherDimension, int ox, int oy, int oz) {
            return dimension == otherDimension && x == ox && y == oy && z == oz;
        }
    }

    /**
     * Gives a player a cabinet, dropping whatever they held before.
     *
     * @return false if somebody else is on this one
     */
    public static synchronized boolean grant(EntityPlayer player, TileEntityDoomArcade arcade) {
        final int dimension = arcade.getWorldObj().provider.dimensionId;

        if (!arcade.claim(player)) {
            return false;
        }

        final UUID id = player.getUniqueID();
        final Location previous = HELD.get(id);

        if (previous != null
                && !previous.isAt(dimension, arcade.xCoord, arcade.yCoord, arcade.zCoord)) {
            releaseAt(previous, player);
        }

        HELD.put(id, new Location(dimension, arcade.xCoord, arcade.yCoord, arcade.zCoord));
        return true;
    }

    /** Forgets a player's claim, once it has been released for real. */
    public static synchronized void forget(EntityPlayer player) {
        HELD.remove(player.getUniqueID());
    }

    /**
     * Frees a cabinet the player used to hold.
     *
     * A cabinet whose chunk is no longer loaded needs nothing done to it: the
     * occupant is deliberately not saved to NBT, so it comes back free.
     */
    private static void releaseAt(Location location, EntityPlayer player) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        final WorldServer world = server.worldServerForDimension(location.dimension);
        if (world == null || !world.blockExists(location.x, location.y, location.z)) {
            return;
        }

        final TileEntity te = world.getTileEntity(location.x, location.y, location.z);
        if (te instanceof TileEntityDoomArcade) {
            ((TileEntityDoomArcade) te).releaseIfHeldBy(player);
        }
    }

    /** True if this cabinet is the one the player is registered as holding. */
    public static synchronized boolean holds(EntityPlayer player, World world, int x, int y, int z) {
        final Location held = HELD.get(player.getUniqueID());
        return held != null && held.isAt(world.provider.dimensionId, x, y, z);
    }
}
