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

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import com.dbr.doom.DbrDoomMod;

/**
 * Server to client: your claim on this arcade was granted, start playing.
 *
 * The server decides who gets a block, so the client never opens a session on
 * its own. Without that, two players clicking at the same moment would both
 * believe they had it.
 */
public class PacketOpenArcade implements IMessage {

    private int x;
    private int y;
    private int z;

    /** Required by the network layer. */
    public PacketOpenArcade() {
    }

    public PacketOpenArcade(int x, int y, int z) {
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

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public static class Handler implements IMessageHandler<PacketOpenArcade, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenArcade message, MessageContext ctx) {
            /*
             * Handled through the proxy because this class is loaded on the
             * server too, and anything that touches Minecraft's client classes
             * would crash a dedicated server just by being resolved here.
             */
            DbrDoomMod.proxy().openArcade(message.getX(), message.getY(), message.getZ());
            return null;
        }
    }
}
