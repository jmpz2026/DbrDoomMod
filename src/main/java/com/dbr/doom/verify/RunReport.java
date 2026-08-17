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

/**
 * What a verified run contained, as lines of text.
 *
 * The verifier runs in a classloader of its own so that each verification gets
 * its own copy of the engine's statics. A class loaded there is a different
 * class from the same name loaded by the plugin, so an object of it cannot be
 * cast on the far side; only the bootstrap types are genuinely shared. That
 * leaves a String, and a format simple enough to parse without a library.
 *
 * One event per line, a keyword and then key=value pairs:
 *
 * MAPSTART tic=61 ep=1 map=1 skill=2
 * KILL tic=340 n=1
 * ITEM tic=502 n=1
 * SECRET tic=1180 n=1
 * DEATH tic=6800 ep=1 map=1
 * MAPEND tic=8134 ep=1 map=1 kills=27 items=14 secrets=2 maxkills=31 maxitems=18 maxsecrets=3 time=8073
 * END tics=8134 truncated=false
 *
 * A failed verification is a single line, and pays nothing:
 *
 * ERROR reason=the demo is from a different game version
 *
 * Tics are the engine's own clock at 35 a second, counted from the start of
 * the replay. The reward side needs them for two things: placing events in
 * order, and checking the run against how long the server saw the cabinet
 * occupied - which is what stops a demo recorded offline from being uploaded as
 * if it had just been played.
 */
public final class RunReport {

    private final StringBuilder lines = new StringBuilder();
    private boolean failed;

    /** A new map began. Counters reset here. */
    public void mapStart(int tic, int episode, int map, int skill) {
        line("MAPSTART tic=" + tic + " ep=" + episode + " map=" + map + " skill=" + skill);
    }

    /** Monsters killed since the last report, not the running total. */
    public void kills(int tic, int n) {
        line("KILL tic=" + tic + " n=" + n);
    }

    public void items(int tic, int n) {
        line("ITEM tic=" + tic + " n=" + n);
    }

    public void secrets(int tic, int n) {
        line("SECRET tic=" + tic + " n=" + n);
    }

    /** The player died. Reported once per death, not once per tic spent dead. */
    public void death(int tic, int episode, int map) {
        line("DEATH tic=" + tic + " ep=" + episode + " map=" + map);
    }

    /**
     * A map was finished.
     *
     * The maxima come with it because a reward table pays for clearing a map
     * completely, and "all of them" is not something the reward side can work
     * out on its own.
     */
    public void mapComplete(int tic, int episode, int map,
            int kills, int items, int secrets,
            int maxKills, int maxItems, int maxSecrets, int levelTime) {
        line("MAPEND tic=" + tic + " ep=" + episode + " map=" + map
            + " kills=" + kills + " items=" + items + " secrets=" + secrets
            + " maxkills=" + maxKills + " maxitems=" + maxItems
            + " maxsecrets=" + maxSecrets + " time=" + levelTime);
    }

    /**
     * The replay ended.
     *
     * @param truncated true if it hit the verifier's ceiling rather than the
     *                  end of the demo. Such a run is short of whatever came
     *                  after, so the reward side should treat it as suspect
     */
    public void finish(int tics, boolean truncated) {
        line("END tics=" + tics + " truncated=" + truncated);
    }

    /**
     * The run could not be verified, so it pays nothing.
     *
     * Not the same as a run worth nothing: this means the server could not
     * establish what happened, which is the answer for a corrupt demo, a demo
     * from another engine version, or one recorded against a different WAD.
     */
    public void fail(String reason) {
        if (failed) {
            // Keep the first reason; anything after it is a consequence.
            return;
        }
        // Whatever was collected so far goes, so no caller can pay on half a run.
        lines.setLength(0);
        lines.append("ERROR reason=").append(reason).append('\n');
        failed = true;
    }

    public boolean isFailed() {
        return failed;
    }

    public String render() {
        return lines.toString();
    }

    private void line(String text) {
        if (failed) {
            // Nothing follows a failure. A partial timeline must not be paid on.
            return;
        }
        lines.append(text).append('\n');
    }
}
