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

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

import com.dbr.doom.DbrDoomMod;

/**
 * The mod's network channel.
 *
 * Doom itself is never sent over the wire: every client runs its own engine and
 * the server only arbitrates who is allowed to use which arcade block. That
 * keeps the traffic to two tiny messages, rather than the megabytes per second
 * that streaming frames would cost.
 */
public final class DoomNetwork {

    private static SimpleNetworkWrapper channel;

    private DoomNetwork() {
    }

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(DbrDoomMod.MODID);

        // Server grants a claim and tells that one client to start playing.
        channel.registerMessage(
            PacketOpenArcade.Handler.class, PacketOpenArcade.class, 0, Side.CLIENT);

        // Client reports that it is done, so the block frees up for others.
        channel.registerMessage(
            PacketReleaseArcade.Handler.class, PacketReleaseArcade.class, 1, Side.SERVER);
    }

    public static void toClient(IMessage message, EntityPlayerMP player) {
        channel.sendTo(message, player);
    }

    public static void toServer(IMessage message) {
        channel.sendToServer(message);
    }
}
