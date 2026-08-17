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

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.DoomConfig;
import com.dbr.doom.engine.doom.DoomMain;
import com.dbr.doom.engine.doom.event_t;
import com.dbr.doom.engine.doom.evtype_t;
import com.dbr.doom.engine.g.Signals.ScanCode;
import com.dbr.doom.host.DoomHost;
import com.dbr.doom.network.DoomNetwork;
import com.dbr.doom.network.PacketReleaseArcade;
import com.dbr.doom.host.FrameBridge;

/**
 * Draws a running Doom session and feeds it Minecraft's input.
 *
 * Minecraft keeps ticking behind this screen, so it does not pause the game.
 */
public class GuiDoom extends GuiScreen {

    /**
     * Doom's 320x200 is displayed as 4:3, so its pixels are not square. Scaling
     * by the raw pixel count would render everything 20% too tall.
     */
    private static final double TARGET_ASPECT = 4.0D / 3.0D;

    protected final DoomHost host;

    private long lastEscapePress;
    protected long openedAt;

    private long framesDrawn;
    private boolean warnedNoFrames;

    /** Set by close(), so onGuiClosed() can tell a deliberate exit from any other. */
    private boolean closing;



    /** Coordinates of the cabinet this session belongs to, so it can be freed. */
    protected final int blockX;
    protected final int blockY;
    protected final int blockZ;

    public GuiDoom(DoomHost host, int blockX, int blockY, int blockZ) {
        this.host = host;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
    }

    @Override
    public void initGui() {
        super.initGui();

        /*
         * Only on the first call. Minecraft runs initGui() again on every window
         * resize, and resetting this would pop the quit hint back up each time.
         */
        if (openedAt == 0L) {
            openedAt = System.currentTimeMillis();
        }

        /*
         * Doom wants raw mouse deltas, not a cursor position, so the pointer is
         * captured the same way it is during normal gameplay.
         */
        Mouse.setGrabbed(true);

        /*
         * Without this, holding a movement key would deliver a stream of repeat
         * presses and Doom would never see a clean key-down/key-up pair.
         */
        Keyboard.enableRepeatEvents(false);

        duckMinecraftAudio();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();

        Mouse.setGrabbed(false);
        restoreMinecraftAudio();

        /*
         * Something other than close() took the screen away: the player died and
         * got GuiGameOver, the server opened a container, the connection
         * dropped. The session would otherwise carry on with no screen and no
         * way to reach it, still holding the cabinet and still making noise, and
         * only /DoomStop would get the player out.
         *
         * mc.currentScreen cannot be consulted here, since Minecraft calls this
         * before it swaps the field, which is what the flag is for.
         */
        if (!closing) {
            DbrDoomMod.logger().info("Doom screen closed from outside; ending the session");
            endSession();
        }

        /*
         * The texture is not deleted here. It belongs to DoomScreenTexture,
         * which is shared with every cabinet and frees it when the session
         * itself ends.
         */
    }

    private void duckMinecraftAudio() {
        MinecraftAudioDucker.duck();
    }

    private void restoreMinecraftAudio() {
        MinecraftAudioDucker.restore();
    }

    /** Minecraft carries on running behind Doom. */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        /*
         * If the Doom thread died, say so in chat and get out rather than
         * leaving the player staring at a frozen frame.
         */
        if (host.isFinished()) {
            final Throwable failure = host.getFailure();
            if (failure != null) {
                DbrDoomMod.logger().error("Doom session ended with an error", failure);
                // The log is not in front of the player; chat is.
                say(EnumChatFormatting.RED + "Doom stopped: " + failure);
                say(EnumChatFormatting.GRAY + "See the log for details.");
            }
            close();
        }
    }

    /** Puts a line in chat, if there is a player to receive it. */
    protected void say(String message) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF000000);

        final FrameBridge bridge = host.getFrameBridge();
        if (bridge == null) {
            /*
             * The engine boots on its own thread now, so for about a second
             * there is nothing to draw yet. Saying so beats a black screen that
             * looks like a crash.
             */
            drawCenteredString(fontRendererObj, "Loading Doom...",
                width / 2, height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        /*
         * The frame was already uploaded this tick by ClientTickHandler, which
         * feeds this screen and every cabinet in the world from one upload.
         */
        if (!DoomScreenTexture.isReady()) {
            drawCenteredString(fontRendererObj, "Loading Doom...",
                width / 2, height / 2 - 4, 0xFFFFFFFF);
            return;
        }

        if (framesDrawn++ == 0) {
            DbrDoomMod.logger().info("Doom GUI ready: {}x{} frame, {}x{} screen",
                new Object[] {
                    Integer.valueOf(bridge.getWidth()),
                    Integer.valueOf(bridge.getHeight()),
                    Integer.valueOf(width),
                    Integer.valueOf(height)
                });
        } else if (framesDrawn == 2 && !warnedNoFrames
                && host.getFramesPublished() == 0L) {
            warnedNoFrames = true;
            DbrDoomMod.logger().warn("Drawing but the engine has published no frames yet");
        }

        drawDoomFrame();

        if (DoomConfig.showHint()
                && System.currentTimeMillis() - openedAt < DoomConfig.hintDurationMs()) {
            final String hint = Keyboard.getKeyName(DoomConfig.exitKey())
                + " or double-tap ESC to return to Minecraft";
            drawCenteredString(fontRendererObj, hint, width / 2, height - 12, 0xFFFFAA00);
        }
    }

    /**
     * Blits the Doom texture centred and letterboxed, at the largest 4:3 size
     * that fits, with no filtering so the pixels stay crisp.
     */
    protected void drawDoomFrame() {
        int drawWidth = width;
        int drawHeight = (int) Math.round(width / TARGET_ASPECT);

        if (drawHeight > height) {
            drawHeight = height;
            drawWidth = (int) Math.round(height * TARGET_ASPECT);
        }

        final int x = (width - drawWidth) / 2;
        final int y = (height - drawHeight) / 2;

        mc.getTextureManager().bindTexture(DoomScreenTexture.location());

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_BLEND);

        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + drawHeight, 0.0D, 0.0D, 1.0D);
        t.addVertexWithUV(x + drawWidth, y + drawHeight, 0.0D, 1.0D, 1.0D);
        t.addVertexWithUV(x + drawWidth, y, 0.0D, 1.0D, 0.0D);
        t.addVertexWithUV(x, y, 0.0D, 0.0D, 0.0D);
        t.draw();

        GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * Taken over wholesale from GuiScreen, which only reports key presses.
     * Doom needs releases too, or the player would move forever after tapping W.
     */
    @Override
    public void handleKeyboardInput() {
        guard(new Runnable() {
            @Override
            public void run() {
                handleKeyboardInputImpl();
            }
        });
    }

    @Override
    public void handleMouseInput() {
        guard(new Runnable() {
            @Override
            public void run() {
                handleMouseInputImpl();
            }
        });
    }

    /**
     * Runs an input handler without letting it take Minecraft down.
     *
     * Minecraft calls these from runTick(), where anything thrown becomes a
     * full crash report and the game exits. A broken Doom session is not worth
     * that, so a failure here ends the session and hands the player back to
     * Minecraft with an explanation.
     */
    private void guard(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            DbrDoomMod.logger().error("Doom input handling failed; closing the session", t);
            say(EnumChatFormatting.RED + "Doom input error: " + t);
            say(EnumChatFormatting.GRAY + "Session closed. See the log for details.");
            close();
        }
    }

    private void handleKeyboardInputImpl() {
        final int key = Keyboard.getEventKey();
        final boolean down = Keyboard.getEventKeyState();

        if (down && key == DoomConfig.exitKey()) {
            close();
            return;
        }

        if (down && key == Keyboard.KEY_ESCAPE) {
            final long now = System.currentTimeMillis();
            if (now - lastEscapePress < DoomConfig.escapeDoubleTapMs()) {
                close();
                return;
            }
            lastEscapePress = now;
            // Falls through: a single ESC still opens Doom's own menu.
        }

        if (!engineReady()) {
            // Dropped on purpose. See engineReady().
            return;
        }

        final ScanCode sc = DoomKeyMap.toScanCode(key);
        if (sc != null) {
            post(new event_t.keyevent_t(down ? evtype_t.ev_keydown : evtype_t.ev_keyup, sc));
        }
    }

    /**
     * Whether it is safe to touch the engine's classes at all.
     *
     * This is not the same question as "is there a session", and the difference
     * froze the game. The engine boots on its own thread, and part of booting is
     * running the static initialisers of classes like event_t and
     * ScanCode. A class being initialised is locked by the thread doing
     * it, and any other thread that touches that class blocks until it finishes.
     *
     * Moving the mouse during that second was enough: the render thread reached
     * new event_t.mouseevent_t(...), waited on a lock the Doom thread
     * held, and the client stopped dead with no exception and no crash report.
     *
     * The guard has to come before the engine is mentioned. An earlier version
     * checked inside post(), which never helped: Java builds the argument first,
     * so the class was already initialising by the time the check ran. Anything
     * added here that names an engine type must sit below this call.
     */
    private boolean engineReady() {
        final DoomHost current = DoomHost.getActive();
        return current != null && !current.isFinished() && current.isReady();
    }

    /**
     * Also taken over from GuiScreen: Doom wants movement deltas and button
     * state, not the click coordinates GuiScreen would hand us.
     */
    private void handleMouseInputImpl() {
        /*
         * Before anything else: constructing the event below initialises engine
         * classes, and doing that while the Doom thread is still booting locks
         * the client up. See engineReady().
         */
        if (!engineReady()) {
            return;
        }

        final int dx = Mouse.getEventDX();
        // LWJGL measures Y upwards, the engine expects it downwards.
        final int dy = -Mouse.getEventDY();

        int buttons = 0;
        if (Mouse.isButtonDown(0)) {
            buttons |= event_t.MOUSE_LEFT;
        }
        if (Mouse.isButtonDown(1)) {
            buttons |= event_t.MOUSE_RIGHT;
        }
        if (Mouse.isButtonDown(2)) {
            buttons |= event_t.MOUSE_MID;
        }

        post(new event_t.mouseevent_t(evtype_t.ev_mouse, buttons, dx, dy));
    }

    private void post(event_t event) {
        final DoomHost current = DoomHost.getActive();
        if (current == null || current.isFinished()) {
            return;
        }

        /*
         * Null while the engine is still booting on its own thread. Keys pressed
         * during the "Loading Doom..." second land here, and there is nothing
         * yet to deliver them to, so they are dropped.
         */
        final DoomMain<?, ?> target = current.getDoom();
        if (target == null) {
            return;
        }

        target.PostEvent(event);
    }

    protected void close() {
        closing = true;
        endSession();
        mc.displayGuiScreen(null);
    }

    /**
     * Ends the engine session and frees the cabinet. Safe to call twice.
     *
     * stop() no longer waits for the Doom thread, so this returns at once and
     * the wait happens on a thread of its own; calling it from here used to
     * stall Minecraft for as long as the engine took to shut its audio down.
     */
    private void endSession() {
        host.stop();

        /*
         * Tell the server the cabinet is free. It also releases when the block
         * is broken and when the occupant is no longer around, since a client
         * that crashes never gets here.
         */
        try {
            DoomNetwork.toServer(new PacketReleaseArcade(blockX, blockY, blockZ));
        } catch (Throwable t) {
            // Usually a connection that has already gone, in which case the
            // server frees the cabinet on its own. Never worth an exception
            // from inside a screen teardown.
            DbrDoomMod.logger().warn("Could not tell the server the arcade is free", t);
        }
    }
}
