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

package com.dbr.doom.client.reward;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientCustomPacketEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;

import com.dbr.doom.DbrDoomMod;

/**
 * Talks to the rewards plugin.
 *
 * {@link com.dbr.doom.network.DoomNetwork} is mod to mod: it settles who is
 * allowed to use which cabinet, and both ends are Forge. Rewards are decided by
 * a Bukkit plugin instead, which cannot see a Forge SimpleNetworkWrapper
 * message at all. A plugin messaging channel is the one thing both sides can
 * speak, so this is a second, separate channel carrying bytes.
 *
 * Nothing is shared between mod and plugin but the constants below, which are
 * therefore duplicated by hand on the far side. Keep them in step.
 *
 * The mod works perfectly well with no plugin listening. Every send is
 * best-effort and nothing here is on the path that lets a player play.
 */
public final class RewardChannel {

    /**
     * The plugin messaging channel name.
     *
     * Upper case and distinct from the mod id, which names the Forge channel.
     * Two channels with one name would be a very confusing afternoon.
     */
    public static final String CHANNEL = "DBRDOOM";

    /** Bumped when the wire format changes, so a stale plugin can say so. */
    public static final int PROTOCOL_VERSION = 3;

    // Client -> plugin.
    public static final byte C2S_HELLO = 0x20;
    public static final byte C2S_RUN_BEGIN = 0x21;
    public static final byte C2S_RUN_CHUNK = 0x22;
    public static final byte C2S_RUN_END = 0x23;

    // Plugin -> client.
    public static final byte S2C_CAPS = 0x01;
    public static final byte S2C_MESSAGE = 0x02;

    /**
     * The largest payload put on the wire at once.
     *
     * A run is four bytes a tic, so ten minutes of play is about 84KB, well past
     * what a single 1.7.10 custom payload will carry. Runs are deflated and then
     * split into chunks of this size.
     */
    public static final int MAX_CHUNK = 16 * 1024;

    private static FMLEventChannel channel;

    /** Set by the plugin's greeting; false until something answers. */
    private static volatile boolean pluginPresent;

    private RewardChannel() {
    }

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        channel.register(new RewardChannel());
    }

    /** True once the plugin has said hello. Purely informational. */
    public static boolean isPluginPresent() {
        return pluginPresent;
    }

    /**
     * Announces the mod, so the plugin knows this client can send runs.
     *
     * Carries the hash of the WAD being played. The plugin has to replay a run
     * against the same data it was recorded on, and a different WAD would
     * produce a verification that disagrees with the session for reasons that
     * have nothing to do with cheating.
     */
    public static void sendHello(String wadHash) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(C2S_HELLO);
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(DbrDoomMod.VERSION);
            out.writeUTF(wadHash == null ? "" : wadHash);
        } catch (IOException e) {
            // A ByteArrayOutputStream does not fail; this satisfies the compiler.
            return;
        }
        send(bytes.toByteArray());
    }

    /**
     * Announces a run that is about to be uploaded, and how big it is.
     *
     * @param serial  which playthrough this belongs to. Starting a new game
     *                bumps it, and the segments start over from zero
     * @param segment which map of that playthrough this is, counting from zero.
     *                Segments are disjoint and consecutive: the server adds them
     *                up rather than working out what grew, which is what runs
     *                used to require when every upload was a longer prefix of
     *                the same recording
     */
    public static void sendRunBegin(long runId, int serial, int segment,
            int compressedLength, int chunks, int rawLength) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(C2S_RUN_BEGIN);
            out.writeLong(runId);
            out.writeInt(serial);
            out.writeInt(segment);
            out.writeInt(compressedLength);
            out.writeInt(chunks);
            out.writeInt(rawLength);
        } catch (IOException e) {
            return;
        }
        send(bytes.toByteArray());
    }

    public static void sendRunChunk(long runId, int index, byte[] chunk, int offset, int length) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(C2S_RUN_CHUNK);
            out.writeLong(runId);
            out.writeInt(index);
            out.writeInt(length);
            out.write(chunk, offset, length);
        } catch (IOException e) {
            return;
        }
        send(bytes.toByteArray());
    }

    public static void sendRunEnd(long runId) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(C2S_RUN_END);
            out.writeLong(runId);
        } catch (IOException e) {
            return;
        }
        send(bytes.toByteArray());
    }

    private static void send(byte[] data) {
        if (channel == null) {
            return;
        }
        try {
            channel.sendToServer(new cpw.mods.fml.common.network.internal.FMLProxyPacket(
                new PacketBuffer(Unpooled.wrappedBuffer(data)), CHANNEL));
        } catch (Throwable t) {
            /*
             * Nothing about rewards may take the game down. A player with no
             * plugin, a dropped connection or a full send queue simply earns
             * nothing for that run.
             */
            DbrDoomMod.logger().warn("Could not send a Doom run to the server", t);
        }
    }

    @SubscribeEvent
    public void onClientPacket(ClientCustomPacketEvent event) {
        if (FMLCommonHandler.instance().getEffectiveSide() != Side.CLIENT) {
            return;
        }
        if (event.packet == null || !CHANNEL.equals(event.packet.channel())) {
            return;
        }

        final byte[] payload;
        try {
            final io.netty.buffer.ByteBuf buffer = event.packet.payload();
            payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
        } catch (Exception e) {
            DbrDoomMod.logger().warn("Malformed reward packet: {}", e.toString());
            return;
        }

        /*
         * Handlers run on a netty thread. Anything that touches the player or
         * the screen has to be handed to the client thread first; this is
         * addScheduledTask, which has no MCP name in 1.7.10.
         */
        Minecraft.getMinecraft().func_152344_a(new Runnable() {
            @Override
            public void run() {
                handle(payload);
            }
        });
    }

    private static void handle(byte[] payload) {
        if (payload.length < 1) {
            return;
        }

        try {
            final java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(payload));

            switch (in.readByte()) {
                case S2C_CAPS:
                    pluginPresent = true;
                    DbrDoomMod.logger().info("Doom rewards are enabled on this server");
                    break;

                case S2C_MESSAGE:
                    say(in.readUTF());
                    break;

                default:
                    // A newer plugin talking about something we do not know yet.
                    break;
            }
        } catch (Exception e) {
            DbrDoomMod.logger().warn("Could not read a reward packet: {}", e.toString());
        }
    }

    private static void say(String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && message != null && !message.isEmpty()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
