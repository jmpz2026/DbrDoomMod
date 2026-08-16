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
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.host.DoomHost;
import com.dbr.doom.host.FrameBridge;

/**
 * The one and only Doom screen texture on this client.
 *
 * Every cabinet in the world shows the same game, because the engine keeps a
 * static singleton and a client can only ever run one. That turns what could
 * have been an expensive feature into a cheap one: the frame is uploaded to the
 * GPU exactly once per rendered frame, from {@link #uploadFrame()}, and each
 * cabinet then costs nothing more than binding an existing texture and drawing
 * one quad. A hundred cabinets in view cost the same upload as one.
 *
 * The upload deliberately does not live in the renderer. Minecraft calls a tile
 * entity renderer once per block per frame, so doing it there would re-upload
 * 64000 pixels for every cabinet on screen.
 */
public final class DoomScreenTexture {

    private static DynamicTexture texture;
    private static ResourceLocation location;

    /** Set once a real frame has been uploaded, not merely allocated. */
    private static boolean hasContent;

    private DoomScreenTexture() {
    }

    /**
     * Pushes the latest Doom frame to the GPU. Call once per rendered frame,
     * before anything draws.
     */
    public static void uploadFrame() {
        final DoomHost host = DoomHost.getActive();
        if (host == null) {
            dispose();
            return;
        }

        final FrameBridge bridge = host.getFrameBridge();
        if (bridge == null) {
            // Engine still booting on its own thread.
            return;
        }

        if (texture == null) {
            allocate(bridge);
        }

        // drainInto reports false when the engine has not finished a new frame,
        // in which case the texture already on the GPU is still correct.
        if (bridge.drainInto(texture.getTextureData())) {
            texture.updateDynamicTexture();
            hasContent = true;
        }
    }

    /** Where to bind from, or null when there is nothing to show. */
    public static ResourceLocation location() {
        return location;
    }

    /**
     * True only once a real frame has landed on the GPU.
     *
     * The texture is allocated as soon as the engine's frame bridge exists,
     * which happens a moment before the engine finishes its first frame. Going
     * by allocation alone would briefly report a screen that is ready but still
     * blank, and callers would draw one black frame instead of saying "loading".
     */
    public static boolean isReady() {
        return location != null && hasContent;
    }

    /** Frees the texture when a session ends. */
    public static void dispose() {
        if (location == null) {
            return;
        }

        Minecraft.getMinecraft().getTextureManager().deleteTexture(location);
        location = null;
        texture = null;
        hasContent = false;
    }

    private static void allocate(FrameBridge bridge) {
        texture = new DynamicTexture(bridge.getWidth(), bridge.getHeight());
        location = Minecraft.getMinecraft().getTextureManager()
            .getDynamicTextureLocation(DbrDoomMod.MODID + "_screen", texture);

        DbrDoomMod.logger().info("Doom screen texture allocated: {}x{}",
            Integer.valueOf(bridge.getWidth()),
            Integer.valueOf(bridge.getHeight()));
    }
}
