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

import java.io.File;
import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;

import com.dbr.doom.CommonProxy;
import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.DoomConfig;
import com.dbr.doom.client.reward.RewardChannel;
import com.dbr.doom.client.reward.WadFingerprint;
import com.dbr.doom.block.TileEntityDoomArcade;
import com.dbr.doom.host.DoomHost;
import com.dbr.doom.host.EngineOutputRouter;
import com.dbr.doom.host.FreedoomInstaller;
import com.dbr.doom.host.WadLocator;
import com.dbr.doom.network.DoomNetwork;
import com.dbr.doom.network.PacketReleaseArcade;

/**
 * The client half: the Doom engine, its config and everything that draws.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(File suggestedConfigFile, File baseDir) {
        DoomConfig.load(suggestedConfigFile);

        // Give the engine's console chatter a timestamp and a source.
        EngineOutputRouter.install();

        try {
            final int written = FreedoomInstaller.installIfMissing(DbrDoomMod.wadDir());
            if (written > 0) {
                DbrDoomMod.logger().info("Unpacked {} Freedoom files into {}",
                    Integer.valueOf(written), DbrDoomMod.wadDir().getAbsolutePath());
            }
        } catch (IOException e) {
            // Not fatal: a player can still supply their own WAD.
            DbrDoomMod.logger().error("Could not unpack the bundled Freedoom data", e);
        }
    }

    @Override
    public void init() {
        // Kept as a way out of a wedged session without restarting the game.
        ClientCommandHandler.instance.registerCommand(new CommandDoomStop());

        /*
         * Handles both opening a GUI a tick after a packet asks for it, and the
         * once-per-frame texture upload that feeds every cabinet at once.
         */
        FMLCommonHandler.instance().bus().register(new ClientTickHandler());

        ClientRegistry.bindTileEntitySpecialRenderer(
            TileEntityDoomArcade.class, new TileEntityDoomArcadeRenderer());

        /*
         * A second, separate channel, spoken to the rewards plugin rather than
         * to the mod on the server. Registering it is also how the plugin
         * discovers this client has the mod, so it happens whether or not a
         * plugin is listening.
         */
        RewardChannel.init();
    }

    /**
     * The server granted this player the cabinet, so start a session.
     *
     * Only reached on the client that made the claim: the server sends this to
     * one player, never broadcast.
     *
     * Called on a netty thread, so the work is handed to the client thread
     * first. Everything below it touches Minecraft: reading thePlayer, opening
     * WADs off the disk and starting the engine.
     */
    @Override
    public void openArcade(final int x, final int y, final int z) {
        // No MCP name in 1.7.10: this is addScheduledTask.
        Minecraft.getMinecraft().func_152344_a(new Runnable() {
            @Override
            public void run() {
                openArcadeOnClientThread(x, y, z);
            }
        });
    }

    private void openArcadeOnClientThread(int x, int y, int z) {
        if (!looksLikeOurCabinet(x, y, z)) {
            /*
             * Nothing here is a security boundary - a server can always ask for
             * whatever it likes - but a session takes over the screen and the
             * keyboard, so it is worth refusing to open one for a cabinet that
             * is not there or is nowhere near the player. No release is sent:
             * whatever granted this claim is not something to answer.
             */
            DbrDoomMod.logger().warn(
                "Ignoring an arcade session for {} {} {}: no cabinet of ours within reach",
                new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) });
            return;
        }

        if (DoomHost.isRunning()) {
            /*
             * Already playing on another cabinet. The server granted this claim
             * before asking, so it has to be handed straight back or the cabinet
             * stays occupied by a session that never opened.
             */
            say(EnumChatFormatting.RED + "You are already playing on another machine.");
            releaseClaim(x, y, z);
            return;
        }

        final WadLocator.Wad wad = WadLocator.findDefaultIwad(DbrDoomMod.wadDir());
        if (wad == null) {
            say(EnumChatFormatting.RED + "No IWAD found in " + DbrDoomMod.wadDir().getAbsolutePath());
            releaseClaim(x, y, z);
            return;
        }

        /*
         * Tell the plugin which WAD this run will be played on. A run can only
         * be verified against the data it was played on.
         *
         * On its own thread: fingerprinting is SHA-256 over twelve megabytes, a
         * visible stall on the client thread. Nothing waits for it, and the
         * answer is cached, so it costs this once per launch.
         */
        announceWad(wad.getFile());

        try {
            final DoomHost host = DoomHost.start(wad.getFile(), DbrDoomMod.baseDir());
            /*
             * The immersive screen paints nothing itself: the world stays
             * visible and the picture comes from the cabinet's renderer.
             */
            ClientTickHandler.requestOpen(new GuiDoomImmersive(host, x, y, z));
        } catch (Exception e) {
            DbrDoomMod.logger().error("Could not start Doom", e);
            say(EnumChatFormatting.RED + "Could not start Doom: " + e);
            releaseClaim(x, y, z);
        }
    }

    /**
     * Fingerprints the WAD and tells the plugin about it, off the client thread.
     *
     * Nothing here can fail in a way that stops a player playing: a server with
     * no plugin ignores it, and a hash that cannot be computed only means this
     * run will not be paid for.
     */
    private static void announceWad(final File wad) {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RewardChannel.sendHello(WadFingerprint.of(wad));
                } catch (Throwable t) {
                    DbrDoomMod.logger().warn("Could not announce the WAD to the server", t);
                }
            }
        }, "DbrDoom-Fingerprint");

        // Never hold the game open on account of a reward announcement.
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whether there really is a cabinet there, close enough to be using.
     *
     * The block has to have arrived on this client already, so the check is
     * generous: sixteen blocks, well past the reach the server enforces, since
     * a claim granted at the edge of a chunk boundary should not be lost to a
     * rounding argument.
     */
    private static boolean looksLikeOurCabinet(int x, int y, int z) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }

        if (mc.theWorld.getBlock(x, y, z) != DbrDoomMod.arcadeBlock) {
            return false;
        }

        return mc.thePlayer.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) <= 256.0D;
    }

    /**
     * Hands a granted claim back when no session was opened against it.
     *
     * The claim is made on the server before this client is asked to play, so
     * every path that declines has to say so. Without it the cabinet reads as
     * occupied until the player logs out.
     */
    private static void releaseClaim(int x, int y, int z) {
        DoomNetwork.toServer(new PacketReleaseArcade(x, y, z));
    }

    private static void say(String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
