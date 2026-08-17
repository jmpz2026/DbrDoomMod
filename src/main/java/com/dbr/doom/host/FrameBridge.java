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

package com.dbr.doom.host;

/**
 * Hands finished frames from the Doom thread to Minecraft's render thread.
 *
 * Doom runs at a fixed 35 tics per second while Minecraft renders at whatever
 * rate it can manage, so the two are decoupled: the Doom thread publishes each
 * finished frame here and the render thread drains the most recent one. Frames
 * produced while the render thread is busy are simply overwritten, which is the
 * correct behaviour - there is no point drawing a frame that is already stale.
 *
 * Frames are carried as 8-bit palette indices plus the palette itself rather
 * than as expanded pixels. That is how the engine's indexed renderer already
 * stores them, it keeps the handoff to a 64KB copy, and it means Doom's palette
 * effects (the red flash when hit, the radiation suit green, the item pickup
 * gold) come along for free, since those are nothing but a palette swap.
 */
public final class FrameBridge {

    /** Doom's palette is always 256 entries. */
    public static final int PALETTE_SIZE = 256;

    private final Object lock = new Object();

    private final int width;
    private final int height;
    private final int pixelCount;

    /** Latest published frame, guarded by {@link #lock}. */
    private final byte[] pending;
    private final int[] pendingPalette;
    private boolean hasNewFrame;

    /** Owned by the consumer thread; only ever touched after the lock is released. */
    private final byte[] snapshot;
    private final int[] snapshotPalette;

    public FrameBridge(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixelCount = width * height;

        this.pending = new byte[pixelCount];
        this.pendingPalette = new int[PALETTE_SIZE];
        this.snapshot = new byte[pixelCount];
        this.snapshotPalette = new int[PALETTE_SIZE];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getPixelCount() {
        return pixelCount;
    }

    /**
     * Called from the Doom thread once per frame. Overwrites any frame the
     * consumer has not picked up yet.
     *
     * @param indexed palette indices, at least {@link #getPixelCount()} long
     * @param palette ARGB entries, at least {@link #PALETTE_SIZE} long
     */
    public void publish(byte[] indexed, int[] palette) {
        synchronized (lock) {
            System.arraycopy(indexed, 0, pending, 0, pixelCount);
            System.arraycopy(palette, 0, pendingPalette, 0, PALETTE_SIZE);
            hasNewFrame = true;
        }
    }

    /**
     * Called from Minecraft's render thread. Expands the latest frame into
     * argbOut as ARGB, ready to hand to a texture upload.
     *
     * The copy happens under the lock but the expansion does not, so the Doom
     * thread is blocked only for a 64KB memcpy rather than for 64000 lookups.
     *
     * @param argbOut destination, at least {@link #getPixelCount()} long
     * @return false if no new frame arrived since the last call, leaving
     *         argbOut untouched so the caller can reuse its texture
     */
    public boolean drainInto(int[] argbOut) {
        synchronized (lock) {
            if (!hasNewFrame) {
                return false;
            }
            System.arraycopy(pending, 0, snapshot, 0, pixelCount);
            System.arraycopy(pendingPalette, 0, snapshotPalette, 0, PALETTE_SIZE);
            hasNewFrame = false;
        }

        for (int i = 0; i < pixelCount; i++) {
            argbOut[i] = snapshotPalette[snapshot[i] & 0xFF];
        }
        return true;
    }
}
