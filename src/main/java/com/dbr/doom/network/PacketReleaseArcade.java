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

package com.dbr.doom.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import com.dbr.doom.ServerTaskQueue;
import com.dbr.doom.block.ArcadeClaims;
import com.dbr.doom.block.TileEntityDoomArcade;

/**
 * Client to server: I have stopped playing, free the block.
 *
 * The server also releases a block when it is broken, and whenever it notices
 * the occupant is no longer in the world (see TileEntityDoomArcade.updateEntity),
 * because a client that crashes or is killed never gets to send this.
 */
public class PacketReleaseArcade implements IMessage {

    private int x;
    private int y;
    private int z;

    /** Required by the network layer. */
    public PacketReleaseArcade() {
    }

    public PacketReleaseArcade(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PacketReleaseArcade, IMessage> {

        /*
         * Handlers run on a netty thread, so nothing here may touch the world
         * directly. The work is queued onto the server thread instead; a block
         * update raced against the tick loop is the kind of corruption that
         * never reproduces.
         */
        @Override
        public IMessage onMessage(final PacketReleaseArcade message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;

            ServerTaskQueue.enqueue(player.getUniqueID(), new Runnable() {
                @Override
                public void run() {
                    release(message, player);
                }
            });
            return null;
        }

        private static void release(PacketReleaseArcade message, EntityPlayerMP player) {
            final World world = player.worldObj;
            if (world == null) {
                // Logged out between the packet arriving and the tick handling it.
                return;
            }

            /*
             * Do not load a chunk just to release a block. If the area is not
             * loaded there is nothing keeping the claim alive that matters, and
             * the block will be released when it next loads anyway.
             */
            if (!world.blockExists(message.x, message.y, message.z)) {
                return;
            }

            final TileEntity te = world.getTileEntity(message.x, message.y, message.z);
            if (te instanceof TileEntityDoomArcade) {
                // Only the occupant may release it, or anyone could free anyone.
                ((TileEntityDoomArcade) te).releaseIfHeldBy(player);
            }

            /*
             * Drop the registration too, but only if this really was the
             * cabinet they held. A release aimed at some other block must not
             * make the server forget the one they are actually on.
             */
            if (ArcadeClaims.holds(player, world, message.x, message.y, message.z)) {
                ArcadeClaims.forget(player);
            }
        }
    }
}
