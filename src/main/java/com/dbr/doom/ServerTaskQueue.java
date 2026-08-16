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

package com.dbr.doom;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Runs work on the server thread on behalf of a netty thread.
 *
 * Packet handlers are called on the network thread, so anything that touches
 * the world - a tile entity, a block update - has to be handed over first.
 * 1.7.10 has no way to do that: the client has
 * {@code Minecraft.func_152344_a(Runnable)}, but MinecraftServer did not get a
 * task queue of its own until 1.8, so this is that queue.
 *
 * Tasks are drained at the end of a server tick, which is a point where the
 * world is not mid-update.
 *
 * <h3>Why there is a per-player cap</h3>
 *
 * Everything queued here arrives from a packet, and a client can send packets as
 * fast as it likes. With one shared limit, a player spamming the channel would
 * fill the queue and everyone else's work would be dropped along with theirs -
 * their cabinets would stay claimed. A player can therefore only ever hold a few
 * slots, and overflowing costs them nothing but their own tasks.
 */
public final class ServerTaskQueue {

    /** Plenty: real traffic is one task when a player leaves a cabinet. */
    private static final int MAX_PER_PLAYER = 8;

    /** Backstop for the queue as a whole, across every player. */
    private static final int MAX_PENDING = 512;

    /** How often a flood is allowed to say so in the log. */
    private static final long WARN_INTERVAL_MS = 10000L;

    private static final Queue<Entry> PENDING = new ConcurrentLinkedQueue<Entry>();
    private static final ConcurrentMap<UUID, AtomicInteger> HELD =
        new ConcurrentHashMap<UUID, AtomicInteger>();

    private static final AtomicInteger SIZE = new AtomicInteger();
    private static volatile long lastWarn;

    private ServerTaskQueue() {
    }

    /** Hooks the drain onto the server tick. Called once, from preInit. */
    public static void register() {
        FMLCommonHandler.instance().bus().register(new ServerTaskQueue());
    }

    private static final class Entry {

        private final UUID owner;
        private final Runnable task;

        Entry(UUID owner, Runnable task) {
            this.owner = owner;
            this.task = task;
        }
    }

    /**
     * Queues work for the next server tick. Callable from any thread.
     *
     * @param owner the player the work is on behalf of, for the per-player cap
     */
    public static void enqueue(UUID owner, Runnable task) {
        if (SIZE.get() >= MAX_PENDING) {
            warnThrottled("Server task queue is full; dropping a task");
            return;
        }

        final AtomicInteger held = counter(owner);
        if (held.get() >= MAX_PER_PLAYER) {
            warnThrottled("A player is flooding the server task queue; dropping their task");
            return;
        }

        held.incrementAndGet();
        SIZE.incrementAndGet();
        PENDING.add(new Entry(owner, task));
    }

    private static AtomicInteger counter(UUID owner) {
        AtomicInteger held = HELD.get(owner);
        if (held == null) {
            held = new AtomicInteger();
            final AtomicInteger raced = HELD.putIfAbsent(owner, held);
            if (raced != null) {
                held = raced;
            }
        }
        return held;
    }

    /**
     * One line per flood, not one per packet. Without this a client could fill
     * the disk simply by sending faster than the queue drains.
     */
    private static void warnThrottled(String message) {
        final long now = System.currentTimeMillis();
        if (now - lastWarn < WARN_INTERVAL_MS) {
            return;
        }
        lastWarn = now;
        DbrDoomMod.logger().warn(message);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Entry entry;
        while ((entry = PENDING.poll()) != null) {
            SIZE.decrementAndGet();

            final AtomicInteger held = HELD.get(entry.owner);
            if (held != null && held.decrementAndGet() <= 0) {
                // Nothing of theirs is left, so the counter goes too rather
                // than keeping an entry per player who ever sent a packet.
                HELD.remove(entry.owner, held);
            }

            try {
                entry.task.run();
            } catch (Throwable t) {
                // One bad task must not stop the rest, and must never take the
                // server's tick loop down with it.
                DbrDoomMod.logger().error("Queued server task failed", t);
            }
        }
    }
}
