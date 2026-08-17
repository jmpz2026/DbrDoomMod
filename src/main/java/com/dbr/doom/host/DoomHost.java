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

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.DoomConfig;
import com.dbr.doom.engine.data.dstrings;
import com.dbr.doom.engine.doom.ConfigBase;
import com.dbr.doom.engine.doom.DoomMain;
import com.dbr.doom.engine.mochadoom.Engine;
import com.dbr.doom.engine.v.DoomGraphicSystem;
import com.dbr.doom.engine.v.renderers.DoomScreen;

/**
 * Owns one Doom session: the engine, the thread that drives it, and the bridge
 * that carries frames out to Minecraft.
 *
 * Only one session can exist at a time, because the engine keeps a static
 * singleton internally.
 */
public final class DoomHost {

    /** Doom's fixed tic rate. Not configurable: the game logic assumes it. */
    public static final int TICRATE = 35;

    /**
     * How many finished runs may wait to be sent before the oldest is dropped.
     *
     * A run is produced only when the player starts a new game or ends the
     * session, so more than a couple waiting means nothing is draining them.
     */
    private static final int MAX_PENDING_RUNS = 8;

    private static DoomHost active;

    private final File iwad;
    private final File baseDir;

    /*
     * Built on the Doom thread, not in the constructor. Booting the engine takes
     * about a second: it parses the WAD, builds every texture and sprite and
     * sets up sound. Doing that on Minecraft's client thread froze the game each
     * time /DoomPlay ran. Volatile because the GUI reads them from another thread
     * while this one is still filling them in.
     */
    private volatile DoomMain<?, ?> doom;
    private volatile DoomGraphicSystem<?, ?> graphics;
    private volatile FrameBridge frameBridge;

    private final Thread thread;
    private volatile boolean running = true;

    /** Set if the Doom thread died. Read by the GUI so it can report and close. */
    private volatile Throwable failure;
    private volatile boolean finished;

    /** Reused between frames so the render loop allocates nothing. */
    private final int[] paletteScratch = new int[FrameBridge.PALETTE_SIZE];

    private volatile long framesPublished;
    private boolean publishProblemReported;

    /** stop() is reachable from the GUI, from /DoomStop and from the engine quitting. */
    private volatile boolean stopped;

    /**
     * Runs the player has finished, waiting to be sent to the server.
     *
     * Filled by the Doom thread and drained by the client thread, because the
     * engine may only be touched from the thread that drives it: reading a demo
     * out while the engine is still appending tics to it would produce a demo
     * that replays into something else, which is exactly the failure this whole
     * mechanism exists to make impossible.
     *
     * A run outlives the session that produced it: the last one is collected as
     * the Doom thread winds down, after {@link #stop()}, when
     * {@link #getActive()} already returns null by design. A queue hanging off
     * the instance would be filled and never looked at again.
     *
     * One engine exists at a time, enforced by the engine's own singleton, so
     * there is no ambiguity about whose runs these are.
     */
    private static final Queue<Run> COMPLETED_RUNS = new ConcurrentLinkedQueue<Run>();

    /**
     * One upload: the demo, and which playthrough it belongs to.
     *
     * A run is uploaded more than once - once per map finished, so the player is
     * paid without leaving the cabinet - and each upload is a longer prefix of
     * the same recording. The serial is what lets the server tell those apart
     * from a fresh playthrough, whose tics start over from zero.
     */
    public static final class Run {

        private final int serial;
        private final byte[] data;

        Run(int serial, byte[] data) {
            this.serial = serial;
            this.data = data;
        }

        public int getSerial() {
            return serial;
        }

        public byte[] getData() {
            return data;
        }
    }

    private DoomHost(File iwad, File baseDir) {
        this.iwad = iwad;
        this.baseDir = baseDir;

        this.thread = new Thread(this::run, "DbrDoom-Engine");
        // Never block Minecraft from exiting on account of a Doom session.
        this.thread.setDaemon(true);
        // Minecraft's render thread matters more than Doom's timing.
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /**
     * Starts a session and returns immediately, before the engine has booted.
     * Callers should watch {@link #isReady()} and show something in the meantime.
     *
     * @param iwad    the WAD to play
     * @param baseDir config/dbrdoom, holding config files and savegames
     */
    public static synchronized DoomHost start(File iwad, File baseDir) {
        /*
         * The raw field, not getActive(): a host that has been asked to stop
         * still owns the engine singleton until its thread is gone, and starting
         * a second one on top of it is exactly what must not happen.
         */
        if (active != null) {
            throw new IllegalStateException(active.stopped
                ? "the previous Doom session is still shutting down"
                : "a Doom session is already running");
        }

        final DoomHost host = new DoomHost(iwad, baseDir);
        active = host;
        host.thread.start();
        return host;
    }

    /** Builds the engine. Runs on the Doom thread; takes roughly a second. */
    private Engine bootEngine() throws IOException {
        /*
         * Both of these have to happen before the Engine is constructed: it
         * reads the config files in its constructor, and SAVEGAMENAME is baked
         * into save paths the moment the game builds one.
         */
        ConfigBase.Files.setFolder(baseDir.getAbsolutePath() + File.separator);
        dstrings.SAVEGAMENAME = new File(baseDir, "saves" + File.separator + "doomsav").getAbsolutePath();

        final List<String> argv = new ArrayList<String>();
        argv.add("-iwad");
        argv.add(iwad.getAbsolutePath());
        /*
         * Indexed mode keeps the software renderer on 8-bit paths, which is both
         * the cheapest option and the one that lets us hand palette indices
         * straight to the FrameBridge.
         */
        argv.add("-indexed");
        /*
         * The engine's "scaling" is not an upscale: it renders every pixel in
         * software, so 2 costs four times the CPU and 3 costs nine. The GPU
         * stretches the result to the window for free, so 1 is the default.
         */
        argv.add("-multiply");
        argv.add(Integer.toString(DoomConfig.internalScale()));

        return Engine.createHeadless(argv.toArray(new String[argv.size()]));
    }

    /** True once the engine has booted and frames can start arriving. */
    public boolean isReady() {
        return frameBridge != null;
    }

    /**
     * The session anything should be talking to, or null.
     *
     * A host that has been asked to stop is already gone as far as callers are
     * concerned, even though its thread may still be winding down for another
     * second or two. Returning it would mean drawing frames from a dead session
     * and feeding it input.
     */
    public static synchronized DoomHost getActive() {
        return active == null || active.stopped ? null : active;
    }

    public static synchronized boolean isRunning() {
        return getActive() != null;
    }

    /**
     * Tears the session down and releases the engine singleton so a later
     * session can start a fresh one. Safe to call more than once, and from any
     * thread.
     *
     * Returns as soon as the Doom thread has been asked to stop. The waiting is
     * done on a thread of its own: the engine can spend a second or more in
     * ShutdownSound(), and this is called from Minecraft's client thread, where
     * that wait is a visible freeze.
     */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;

        running = false;
        thread.interrupt();

        /*
         * The engine singleton is only released once the thread is really gone,
         * so a session started in the meantime cannot find a half-dead engine.
         * start() looks at the raw field rather than getActive() for the same
         * reason: this host still owns the engine until the join returns.
         */
        final Thread reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                awaitThreadAndRelease();
            }
        }, "DbrDoom-Shutdown");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** Waits for the Doom thread to die, then hands the engine back. */
    private void awaitThreadAndRelease() {
        try {
            /*
             * Long enough for the engine to leave its tic wait and for
             * shutdownAudio() to run on the way out, including the one second
             * ShutdownSound() may spend waiting on channels.
             */
            thread.join(3000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        if (thread.isAlive()) {
            /*
             * Worth shouting about: a surviving thread means a second engine and
             * its audio would be layered on top by the next session.
             */
            DbrDoomMod.logger().error(
                "Doom thread did not stop within 3s. Audio may still be playing; "
                + "restart Minecraft before starting another session.");
        }

        Engine.resetInstance();

        synchronized (DoomHost.class) {
            if (active == this) {
                active = null;
            }
        }
    }

    public FrameBridge getFrameBridge() {
        return frameBridge;
    }

    public DoomMain<?, ?> getDoom() {
        return doom;
    }

    /** Non-null once the Doom thread has died from something unexpected. */
    public Throwable getFailure() {
        return failure;
    }

    /** True once the Doom thread has stopped, whether cleanly or not. */
    public boolean isFinished() {
        return finished;
    }

    private void run() {
        try {
            final Engine engine = bootEngine();

            doom = engine.getDOOM();
            graphics = doom.graphicSystem;
            applyVolumeOverrides();

            /*
             * Published last. The GUI treats a non-null bridge as "the engine is
             * up", so nothing may see it until everything above is in place.
             */
            frameBridge = new FrameBridge(
                graphics.getScreenWidth(),
                graphics.getScreenHeight()
            );

            doom.setupSession();
            doom.initLoop();

            /*
             * Record every game the player starts, so the server can verify what
             * they did rather than take their word for it. Costs four bytes a
             * tic and touches nothing else.
             */
            doom.dbrAutoRecord = true;

            while (running && !Thread.currentThread().isInterrupted()) {
                doom.runOneFrame();
                publishFrame();
                collectFinishedRun();
            }
        } catch (DoomExitException exit) {
            // The engine asked to quit. Only a non-zero code is worth reporting.
            if (exit.isError()) {
                failure = exit;
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            /*
             * The run in progress when the session ended. Taken here, on the
             * Doom thread, for the same reason the audio is: this is the last
             * moment the engine belongs to us. A player who quits mid-map has
             * still earned whatever they did up to that point.
             */
            takeFinalRun();

            /*
             * Audio has to be torn down here, on the Doom thread, and not in
             * stop(). ShutdownSound() waits on playing channels, so calling it
             * from Minecraft's client thread would stall the whole game.
             */
            shutdownAudio();
            finished = true;
        }
    }

    /**
     * Collects a run the player ended by starting another one.
     *
     * Polled between frames rather than pushed by the engine, so the engine
     * needs to know nothing about where its demos end up.
     */
    private void collectFinishedRun() {
        final DoomMain<?, ?> local = doom;
        if (local == null) {
            return;
        }

        final byte[] run = local.dbrTakePendingRun();
        if (run != null) {
            offerRun(local.dbrRunSerial(), run);
        }
    }

    /** Closes the recording that was still going when the session ended. */
    private void takeFinalRun() {
        final DoomMain<?, ?> local = doom;
        if (local == null) {
            // The engine never finished booting, so nothing was recorded.
            return;
        }

        try {
            // Anything the engine had already set aside, then the live one.
            collectFinishedRun();

            final byte[] run = local.dbrFinishRun();
            if (run != null) {
                offerRun(local.dbrRunSerial(), run);
            }
        } catch (Throwable t) {
            // A run that cannot be collected is a lost reward, never a crash.
            DbrDoomMod.logger().warn("Could not collect the final Doom run", t);
        }
    }

    private static void offerRun(int serial, byte[] run) {
        /*
         * A player who never stops playing would otherwise grow this without
         * limit. The client thread drains it every tick, so reaching this means
         * something is already wrong and the oldest run is the least valuable.
         */
        while (COMPLETED_RUNS.size() >= MAX_PENDING_RUNS) {
            COMPLETED_RUNS.poll();
            DbrDoomMod.logger().warn(
                "Dropping an unsent Doom run: {} are already waiting",
                Integer.valueOf(MAX_PENDING_RUNS));
        }

        COMPLETED_RUNS.add(new Run(serial, run));
        DbrDoomMod.logger().info("Recorded a Doom run of {} bytes (playthrough {})",
            new Object[] { Integer.valueOf(run.length), Integer.valueOf(serial) });
    }

    /**
     * Takes the next finished run, or null. Called from the client thread.
     *
     * Static, and callable with no session running at all: the last run of a
     * session only appears once that session is already gone.
     *
     * @see #COMPLETED_RUNS
     */
    public static Run pollCompletedRun() {
        return COMPLETED_RUNS.poll();
    }

    /**
     * Stops the music and the sound effects.
     *
     * Upstream never does this: the two calls that would are commented out in
     * DoomSystem.Quit(). Standalone it does not matter, the process is about to
     * die. Embedded it matters a lot, because the MIDI sequencer and the sound
     * clips run on their own threads and happily outlive the engine. Skipping
     * this is what leaves music playing after the player has quit, with a fresh
     * copy layered on top every time they start again.
     */
    private void shutdownAudio() {
        final DoomMain<?, ?> local = doom;
        if (local == null) {
            // The engine never finished booting, so there is nothing playing.
            return;
        }

        try {
            local.music.ShutdownMusic();
        } catch (Throwable t) {
            DbrDoomMod.logger().warn("Could not shut down Doom music", t);
        }

        try {
            local.soundDriver.ShutdownSound();
        } catch (Throwable t) {
            DbrDoomMod.logger().warn("Could not shut down Doom sound", t);
        }
    }

    /**
     * Applies the volume overrides from dbrdoom.cfg, if any were set.
     *
     * Both default to -1, meaning "leave it alone", because Doom's own options
     * menu already sets these and now persists them to config/dbrdoom. The
     * override exists for people who want Doom quieter than its menu allows
     * relative to Minecraft, without touching their saved Doom settings.
     */
    private void applyVolumeOverrides() {
        final int sfx = DoomConfig.sfxVolume();
        final int music = DoomConfig.musicVolume();

        if (sfx >= 0) {
            try {
                doom.doomSound.SetSfxVolume(sfx);
            } catch (Throwable t) {
                DbrDoomMod.logger().warn("Could not set Doom sfx volume", t);
            }
        }

        if (music >= 0) {
            try {
                doom.doomSound.SetMusicVolume(music);
            } catch (Throwable t) {
                DbrDoomMod.logger().warn("Could not set Doom music volume", t);
            }
        }
    }

    /**
     * Copies the finished screen out of the engine and into the bridge.
     *
     * The palette is read back from the image the graphics system exposes: in
     * indexed mode that image is tied to the same raster the engine draws into,
     * and its colour model is swapped whenever the palette or gamma changes, so
     * it always describes the frame we are publishing.
     */
    private void publishFrame() {
        final Object screen = graphics.getScreen(DoomScreen.FG);
        if (!(screen instanceof byte[])) {
            // Only indexed mode is supported, and that is what we ask for.
            reportOnce("screen buffer is " + describe(screen) + ", expected byte[]");
            return;
        }

        final java.awt.Image image = graphics.getScreenImage();
        if (!(image instanceof BufferedImage)) {
            reportOnce("screen image is " + describe(image) + ", expected BufferedImage");
            return;
        }
        final java.awt.image.ColorModel cm = ((BufferedImage) image).getColorModel();
        if (!(cm instanceof IndexColorModel)) {
            reportOnce("colour model is " + describe(cm) + ", expected IndexColorModel");
            return;
        }

        final IndexColorModel icm = (IndexColorModel) cm;
        if (icm.getMapSize() < FrameBridge.PALETTE_SIZE) {
            reportOnce("palette has only " + icm.getMapSize() + " entries");
            return;
        }
        icm.getRGBs(paletteScratch);

        frameBridge.publish((byte[]) screen, paletteScratch);

        if (framesPublished++ == 0) {
            DbrDoomMod.logger().info(
                "First Doom frame published: {}x{}, {} palette entries, buffer {} bytes",
                new Object[] {
                    Integer.valueOf(frameBridge.getWidth()),
                    Integer.valueOf(frameBridge.getHeight()),
                    Integer.valueOf(icm.getMapSize()),
                    Integer.valueOf(((byte[]) screen).length)
                });
        }
    }

    /** How many frames have reached the bridge. Read by the GUI for diagnostics. */
    public long getFramesPublished() {
        return framesPublished;
    }

    /**
     * Logs a rendering problem the first time only. These conditions repeat 35
     * times a second, so logging each one would bury everything else.
     */
    private void reportOnce(String problem) {
        if (publishProblemReported) {
            return;
        }
        publishProblemReported = true;
        DbrDoomMod.logger().error("Cannot publish Doom frames: {}", problem);
    }

    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
