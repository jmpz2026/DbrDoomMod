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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import com.dbr.doom.client.reward.RunUploader;

/**
 * Opens screens one tick after they are asked for.
 *
 * A command cannot open a GUI itself. Commands are dispatched from GuiChat,
 * which closes itself with displayGuiScreen(null) right afterwards, so anything
 * the command opened would be torn down immediately. Deferring by a tick puts
 * the call safely after chat is gone.
 */
public class ClientTickHandler {

    private static volatile GuiScreen pendingScreen;

    /** Asks for gui to be shown on the next client tick. */
    public static void requestOpen(GuiScreen gui) {
        pendingScreen = gui;
    }

    /**
     * Pushes the current Doom frame to the GPU, once per rendered frame.
     *
     * This is the reason many cabinets cost no more than one: the upload
     * happens here, not in the renderer, which Minecraft calls once per block.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            DoomScreenTexture.uploadFrame();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        /*
         * Sends whatever the engine has finished recording, one chunk at a
         * time. Unconditional: a run is most often produced by a session that
         * has just ended, so waiting for one to exist would drop the last one.
         */
        RunUploader.tick();

        final GuiScreen gui = pendingScreen;
        if (gui == null) {
            return;
        }
        pendingScreen = null;

        Minecraft.getMinecraft().displayGuiScreen(gui);
    }
}
