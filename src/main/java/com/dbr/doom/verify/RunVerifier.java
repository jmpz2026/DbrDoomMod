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

package com.dbr.doom.verify;

import java.io.File;
import java.io.FileOutputStream;

import com.dbr.doom.engine.defines.gamestate_t;
import com.dbr.doom.engine.doom.ConfigBase;
import com.dbr.doom.engine.doom.DoomMain;
import com.dbr.doom.engine.doom.player_t;
import com.dbr.doom.engine.doom.playerstate_t;
import com.dbr.doom.engine.mochadoom.Engine;

/**
 * Replays a recorded run and reports what actually happened in it.
 *
 * This is the whole basis of server-authoritative rewards. The client records a
 * demo of what it played and sends it up; the server replays it here and pays
 * for what comes out. Nothing the client claims about its own run is ever used
 * to pay, because nothing stops a client from claiming anything.
 *
 * The reward logic lives in a Bukkit plugin, and this does not. It stays here
 * because it has to run against exactly the engine that recorded the
 * demo: a verifier built against a different build of Mocha Doom would desync
 * silently and pay the wrong amounts, with nothing in any log to say so. Since
 * this travels in the same jar as the engine, the two cannot drift apart.
 *
 * Through a {@link ClassLoader} of its own, over the mod jar, with a null
 * parent. That is possible because com.dbr.doom.engine.** references
 * nothing outside itself but DoomExitException, and this class adds
 * nothing but the JDK - no Forge, no Minecraft, no Bukkit. Every argument and
 * the result are Strings and byte arrays, which are bootstrap types and so are
 * shared across the boundary rather than duplicated.
 *
 * A fresh classloader also gets a fresh copy of the engine's static
 * Engine.instance, which is what makes several verifications at once
 * possible at all.
 *
 * Reflective entry point, so the plugin needs no compile-time dependency:
 *
 * Class&lt;?&gt; c = loader.loadClass("com.dbr.doom.verify.RunVerifier");
 * String report = (String) c.getMethod("verify", byte[].class, String.class, String.class)
 *     .invoke(null, demoBytes, iwadPath, workDirPath);
 *
 * @see RunReport for the format of what comes back
 */
public final class RunVerifier {

    /**
     * A run longer than this is refused rather than replayed.
     *
     * Twelve hours of game time. Replaying is fast - roughly 660x real time -
     * but "fast" is not "free", and the size of the work has to be bounded by
     * something other than what a client chose to upload.
     */
    private static final int MAX_TICS = 35 * 60 * 60 * 12;

    /**
     * The lump the engine will look the demo up under.
     *
     * The engine loads a demo by adding the file as a WAD and then caching the
     * lump named after it, so this doubles as the file name. Short, because a
     * WAD lump name is eight characters.
     */
    private static final String DEMO_NAME = "DBRRUN";

    /**
     * What the calling thread is renamed to while a verification runs.
     *
     * The engine narrates its startup on stdout and there is no way to ask it
     * not to. Swapping System.out here would be a bug: those streams are global
     * to the JVM, not per classloader, so two verifications race to restore them
     * and the loser leaves stdout pointing at a dead buffer, permanently.
     *
     * So this touches nothing global and only marks the thread. The caller
     * installs one filter for the whole process, the way the mod's own
     * EngineOutputRouter does for the client.
     */
    public static final String THREAD_NAME = "DbrDoom-Verify";

    private RunVerifier() {
    }

    /**
     * Replays a demo and reports every rewardable event in it.
     *
     * @param demo    the recorded run, as produced by DoomMain.dbrFinishRun()
     * @param iwad    absolute path of the WAD to replay against. Must be the
     *                same one the run was recorded on, which the caller checks
     *                by hash before getting here
     * @param workDir a directory this call may use for itself. Must not be
     *                shared with another verification running at the same time
     * @return a {@link RunReport} rendered as text, one event per line, or a
     *         single ERROR line. Never null and never throws
     */
    public static String verify(byte[] demo, String iwad, String workDir) {
        final RunReport report = new RunReport();

        /*
         * Names the thread for the duration so the caller can recognise the
         * engine's chatter and route it somewhere quiet. See THREAD_NAME.
         */
        final Thread self = Thread.currentThread();
        final String callerName = self.getName();

        try {
            self.setName(THREAD_NAME);
            replay(demo, iwad, workDir, report);
        } catch (Throwable t) {
            /*
             * Throwable, not Exception: the engine's System.exit() calls are
             * rewritten to DoomExitException, which extends Error. A failed
             * verification must never be able to reach the server's thread.
             */
            report.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            self.setName(callerName);
        }

        return report.render();
    }

    private static void replay(byte[] demo, String iwad, String workDir, RunReport report)
            throws Exception {

        final File work = new File(workDir);
        if (!work.isDirectory() && !work.mkdirs()) {
            report.fail("could not create the work directory " + workDir);
            return;
        }

        /*
         * The engine loads a demo by adding it to the WAD list, so it has to be
         * a file. Written here rather than kept in memory for that reason alone;
         * it is deleted on the way out.
         */
        final File lmp = new File(work, DEMO_NAME + ".lmp");
        final FileOutputStream demoOut = new FileOutputStream(lmp);
        try {
            demoOut.write(demo);
        } finally {
            demoOut.close();
        }

        try {
            final DoomMain<?, ?> doom = boot(iwad, work, lmp);
            sample(doom, report);
        } finally {
            if (lmp.isFile() && !lmp.delete()) {
                lmp.deleteOnExit();
            }
        }
    }

    private static DoomMain<?, ?> boot(String iwad, File work, File lmp) throws Exception {
        /*
         * Points the engine's config files at the work directory. Left alone it
         * would read and write the ones the client plays with, and a setting
         * that changes behaviour would turn into a verification that disagrees
         * with the session for reasons that have nothing to do with cheating.
         */
        ConfigBase.Files.setFolder(work.getAbsolutePath() + File.separator);

        final String base = lmp.getAbsolutePath()
            .substring(0, lmp.getAbsolutePath().length() - ".lmp".length());

        final Engine engine = Engine.createHeadless(
            "-iwad", iwad,
            /*
             * -timedemo, not -fastdemo. Only -timedemo sets singletics, which
             * is what unhooks the loop from Doom's 35Hz clock. Despite the
             * name, -fastdemo replays at real time: a ten minute map would take
             * ten minutes to check.
             */
            "-timedemo", base,
            // Nothing is drawn, heard or mixed. None of it changes the outcome.
            "-nosound", "-nomusic", "-nodraw", "-noblit");

        final DoomMain<?, ?> doom = engine.getDOOM();
        doom.setupSession();
        doom.initLoop();
        return doom;
    }

    /**
     * Drives the replay, reading the state between frames.
     *
     * Sampling every tic rather than reading totals at the end is what turns a
     * demo into a timeline. The counters alone cannot tell a player who cleared
     * a map from one who died on it twice, and cannot place an event in time at
     * all, so rewards for streaks, deaths and per-map completion would have
     * nothing to work from.
     */
    private static void sample(DoomMain<?, ?> doom, RunReport report) {
        int kills = 0;
        int items = 0;
        int secrets = 0;
        int currentMap = -1;
        boolean wasDead = false;
        boolean counted = false;

        while (doom.gametic < MAX_TICS) {
            try {
                doom.runOneFrame();
            } catch (Throwable end) {
                /*
                 * The demo ran out. The engine ends a timed demo through
                 * I_Error, which is a DoomExitException here, so this is the
                 * normal way a verification finishes rather than a failure.
                 */
                break;
            }

            if (!doom.demoplayback && doom.gametic > 0) {
                // Playback stopped on its own.
                break;
            }

            if (doom.gamestate != gamestate_t.GS_LEVEL) {
                /*
                 * The intermission screen. Reported once, on the way in, using
                 * the totals from the level just left: this is a map the player
                 * finished, which is what most of a reward table keys on.
                 */
                if (counted && currentMap > 0) {
                    report.mapComplete(doom.gametic, doom.gameepisode, currentMap,
                        kills, items, secrets,
                        doom.totalkills, doom.totalitems, doom.totalsecret,
                        doom.leveltime);
                    counted = false;
                }
                continue;
            }

            final player_t p = doom.players[doom.consoleplayer];

            if (doom.gamemap != currentMap) {
                currentMap = doom.gamemap;
                counted = true;
                kills = items = secrets = 0;
                wasDead = false;
                report.mapStart(doom.gametic, doom.gameepisode, currentMap,
                    doom.gameskill == null ? -1 : doom.gameskill.ordinal());
            }

            /*
             * Reported as deltas rather than as totals. A reward table pays per
             * kill and per milestone, so it needs to know when each one landed,
             * and the counters reset between maps.
             */
            if (p.killcount > kills) {
                report.kills(doom.gametic, p.killcount - kills);
                kills = p.killcount;
            }
            if (p.itemcount > items) {
                report.items(doom.gametic, p.itemcount - items);
                items = p.itemcount;
            }
            if (p.secretcount > secrets) {
                report.secrets(doom.gametic, p.secretcount - secrets);
                secrets = p.secretcount;
            }

            /*
             * Edge-triggered. playerstate stays PST_DEAD for every tic between
             * dying and respawning, which at 35 a second would otherwise be a
             * few hundred deaths for one.
             */
            // player_t.playerstate is an int, while playerstate_t is an enum.
        final boolean dead = p.playerstate == playerstate_t.PST_DEAD.ordinal();
            if (dead && !wasDead) {
                report.death(doom.gametic, doom.gameepisode, currentMap);
            }
            wasDead = dead;
        }

        if (currentMap < 0) {
            /*
             * The replay never reached a level, so there is nothing to pay for
             * and no reason a genuine run would look like this. Reported as a
             * failure rather than as a run worth nothing, so the caller can tell
             * "played badly" from "this upload is not a run at all" - which is
             * what a corrupt demo, a demo from another engine version and a demo
             * for a map this WAD does not have all produce.
             */
            report.fail("the demo never started a level");
            return;
        }

        report.finish(doom.gametic, doom.gametic >= MAX_TICS);
    }
}
