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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.host.DoomHost;

/**
 * Sends finished runs to the server, a chunk at a time.
 *
 * A run is four bytes a tic, so half an hour of play is a quarter of a
 * megabyte. Deflate takes most of that off, but what is left still has to be
 * split: a 1.7.10 custom payload will not carry it in one go.
 *
 * A chunk per tick rather than all at once, because dumping it into the
 * connection stalls everything else the player is doing to deliver something
 * nobody is waiting for.
 *
 * Client side only, driven from the client tick.
 */
public final class RunUploader {

    /** One chunk a tick: 16KB at 20 ticks a second, so 320KB/s at worst. */
    private static final int CHUNKS_PER_TICK = 1;

    /**
     * How many runs may be waiting before the oldest is dropped.
     *
     * Reaching this means the connection is not draining them, in which case
     * queueing more only delays the ones already there.
     */
    private static final int MAX_QUEUED = 8;

    /**
     * Runs ready to go out, filled by the compressor and drained by the tick.
     *
     * Concurrent because those are two different threads: see {@link #queue}.
     */
    private static final Queue<Upload> PENDING = new ConcurrentLinkedQueue<Upload>();

    /**
     * Compresses runs, one at a time, off the client thread.
     *
     * Deflate at BEST_COMPRESSION over a quarter of a megabyte is tens of
     * milliseconds, and a session ends by handing the player back to the world -
     * the worst moment to stall a tick.
     *
     * One thread, so runs stay in the order they were played. Daemon: a client
     * that is quitting has nothing to wait for.
     */
    private static ExecutorService compressor;

    /**
     * Runs handed to the compressor and not yet queued.
     *
     * Counted separately so that MAX_QUEUED still means something: the queue is
     * checked before the work starts, and without this a burst would all see an
     * empty queue and all land in it.
     */
    private static final AtomicInteger COMPRESSING = new AtomicInteger();

    /**
     * Which server this is. Bumped by {@link #reset()}.
     *
     * A run being compressed as the player disconnects would otherwise arrive
     * afterwards and sit in the queue, to be uploaded to whatever server they
     * joined next - which cannot verify it and should never have been offered
     * it.
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static Upload current;
    private static long nextRunId = 1;

    private RunUploader() {
    }

    /** One run, compressed and ready to go out in pieces. */
    private static final class Upload {

        private final long id;
        /** Which playthrough this is a prefix of. See DoomHost.Run. */
        private final int serial;
        private final byte[] data;
        private final int rawLength;
        private final int chunks;
        private int sent;
        private boolean announced;

        Upload(long id, int serial, byte[] data, int rawLength) {
            this.id = id;
            this.serial = serial;
            this.data = data;
            this.rawLength = rawLength;
            this.chunks = (data.length + RewardChannel.MAX_CHUNK - 1) / RewardChannel.MAX_CHUNK;
        }
    }

    /**
     * Takes whatever the engine has finished and starts sending it.
     *
     * Called once per client tick. Draining the host here rather than from the
     * Doom thread keeps the engine to one thread: see
     * {@link DoomHost#pollCompletedRun()}.
     */
    public static void tick() {
        collect();
        pump();
    }

    /**
     * Takes whatever the engine has finished with.
     *
     * Deliberately does not ask whether a session is running: the most valuable
     * run of all, everything the player did before leaving, is only produced as
     * the Doom thread winds down, and {@link DoomHost#getActive()} returns null
     * from that moment by design. Guarding on it drops exactly that run.
     */
    private static void collect() {
        DoomHost.Run run;
        while ((run = DoomHost.pollCompletedRun()) != null) {
            queue(run.getSerial(), run.getData());
        }
    }

    /**
     * Queues a run. Public because the session teardown path has to hand over
     * the last one after the host is already gone.
     */
    public static void queue(final int serial, final byte[] run) {
        if (run == null || run.length == 0) {
            return;
        }

        while (PENDING.size() + COMPRESSING.get() >= MAX_QUEUED && !PENDING.isEmpty()) {
            PENDING.poll();
            DbrDoomMod.logger().warn("Dropping an unsent Doom run: the upload queue is full");
        }

        /*
         * The run id is taken here rather than on the compressor thread, so that
         * ids follow the order the runs were played in even though the work of
         * compressing them happens elsewhere.
         */
        final long id = nextRunId++;
        final int generation = GENERATION.get();

        COMPRESSING.incrementAndGet();
        compressor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] compressed = deflate(run);
                    if (compressed == null) {
                        return;
                    }

                    /*
                     * Dropped if the player left the server while this was being
                     * compressed. It belongs to a server that is no longer
                     * listening, and the next one cannot verify it.
                     */
                    if (generation != GENERATION.get()) {
                        return;
                    }

                    PENDING.add(new Upload(id, serial, compressed, run.length));
                    DbrDoomMod.logger().info(
                        "Queued a Doom run: {} bytes, {} compressed (playthrough {})",
                        new Object[] {
                            Integer.valueOf(run.length),
                            Integer.valueOf(compressed.length),
                            Integer.valueOf(serial)
                        });
                } finally {
                    COMPRESSING.decrementAndGet();
                }
            }
        });
    }

    private static synchronized ExecutorService compressor() {
        if (compressor == null) {
            compressor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable job) {
                    final Thread thread = new Thread(job, "DbrDoom-Compress");
                    thread.setDaemon(true);
                    // The game matters more than getting a run out promptly.
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });
        }
        return compressor;
    }

    private static void pump() {
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            if (current == null) {
                current = PENDING.poll();
                if (current == null) {
                    return;
                }
            }

            if (!current.announced) {
                RewardChannel.sendRunBegin(current.id, current.serial,
                    current.data.length, current.chunks, current.rawLength);
                current.announced = true;
                // The announcement is this tick's send; the first chunk is next.
                return;
            }

            final int offset = current.sent * RewardChannel.MAX_CHUNK;
            final int length = Math.min(RewardChannel.MAX_CHUNK, current.data.length - offset);

            RewardChannel.sendRunChunk(current.id, current.sent, current.data, offset, length);
            current.sent++;

            if (current.sent >= current.chunks) {
                RewardChannel.sendRunEnd(current.id);
                current = null;
            }
        }
    }

    /**
     * Compresses a run.
     *
     * A demo is four bytes per tic and the same command repeats while a key is
     * held, so this is very compressible: a forty second recording goes from
     * 5.6KB to well under half that.
     */
    private static byte[] deflate(byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();

            final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer);
                if (written <= 0) {
                    break;
                }
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            DbrDoomMod.logger().warn("Could not compress a Doom run", t);
            return null;
        } finally {
            // Deflater holds memory outside the heap until this is called.
            deflater.end();
        }
    }

    /**
     * Forgets everything queued. For leaving a server.
     *
     * Anything still being compressed is disowned rather than waited for: see
     * {@link #GENERATION}.
     */
    public static void reset() {
        GENERATION.incrementAndGet();
        PENDING.clear();
        current = null;
    }
}
