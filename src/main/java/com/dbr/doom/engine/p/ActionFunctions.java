/*
 * Copyright (C) 1993-1996 by id Software, Inc.
 * Copyright (C) 2017 Good Sign
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
package com.dbr.doom.engine.p;

import com.dbr.doom.engine.automap.IAutoMap;
import com.dbr.doom.engine.data.sounds;
import com.dbr.doom.engine.defines.skill_t;
import com.dbr.doom.engine.doom.DoomMain;
import com.dbr.doom.engine.doom.player_t;
import com.dbr.doom.engine.hu.IHeadsUp;
import com.dbr.doom.engine.i.IDoomSystem;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.dbr.doom.engine.p.Actions.ActionsAttacks;
import com.dbr.doom.engine.p.Actions.ActionsEnemies;
import com.dbr.doom.engine.p.Actions.ActionsThinkers;
import com.dbr.doom.engine.p.Actions.ActiveStates.Ai;
import com.dbr.doom.engine.p.Actions.ActiveStates.Attacks;
import com.dbr.doom.engine.p.Actions.ActiveStates.Thinkers;
import com.dbr.doom.engine.p.Actions.ActiveStates.Weapons;
import com.dbr.doom.engine.rr.SceneRenderer;
import com.dbr.doom.engine.s.ISoundOrigin;
import com.dbr.doom.engine.st.IDoomStatusBar;
import com.dbr.doom.engine.utils.TraitFactory;
import com.dbr.doom.engine.utils.TraitFactory.SharedContext;

public class ActionFunctions extends UnifiedGameMap implements
    ActionsThinkers, ActionsEnemies, ActionsAttacks, Ai, Attacks, Thinkers, Weapons
{
    private final SharedContext traitsSharedContext;
    
    public ActionFunctions(final DoomMain<?, ?> DOOM) {
        super(DOOM);
        this.traitsSharedContext = buildContext();
    }
    
    private SharedContext buildContext() {
        try {
            return TraitFactory.build(this, ACTION_KEY_CHAIN);
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            Logger.getLogger(ActionFunctions.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
    }
    
    @Override
    public AbstractLevelLoader levelLoader() {
        return DOOM.levelLoader;
    }

    @Override
    public IHeadsUp headsUp() {
        return DOOM.headsUp;
    }

    @Override
    public IDoomSystem doomSystem() {
        return DOOM.doomSystem;
    }

    @Override
    public IDoomStatusBar statusBar() {
        return DOOM.statusBar;
    }

    @Override
    public IAutoMap<?, ?> autoMap() {
        return DOOM.autoMap;
    }

    @Override
    public SceneRenderer<?, ?> sceneRenderer() {
        return DOOM.sceneRenderer;
    }

    @Override
    public UnifiedGameMap.Specials getSpecials() {
        return SPECS;
    }

    @Override
    public UnifiedGameMap.Switches getSwitches() {
        return SW;
    }

    @Override
    public void StopSound(ISoundOrigin origin) {
        DOOM.doomSound.StopSound(origin);
    }

    @Override
    public void StartSound(ISoundOrigin origin, sounds.sfxenum_t s) {
        DOOM.doomSound.StartSound(origin, s);
    }

    @Override
    public void StartSound(ISoundOrigin origin, int s) {
        DOOM.doomSound.StartSound(origin, s);
    }

    @Override
    public player_t getPlayer(int number) {
        return DOOM.players[number];
    }

    @Override
    public skill_t getGameSkill() {
        return DOOM.gameskill;
    }

    @Override
    public mobj_t createMobj() {
        return mobj_t.createOn(DOOM);
    }

    @Override
    public int LevelTime() {
        return DOOM.leveltime;
    }

    @Override
    public int P_Random() {
        return DOOM.random.P_Random();
    }

    @Override
    public int ConsolePlayerNumber() {
        return DOOM.consoleplayer;
    }

    @Override
    public int MapNumber() {
        return DOOM.gamemap;
    }

    @Override
    public boolean PlayerInGame(int number) {
        return DOOM.playeringame[number];
    }

    @Override
    public boolean IsFastParm() {
        return DOOM.fastparm;
    }

    @Override
    public boolean IsPaused() {
        return DOOM.paused;
    }

    @Override
    public boolean IsNetGame() {
        return DOOM.netgame;
    }

    @Override
    public boolean IsDemoPlayback() {
        return DOOM.demoplayback;
    }

    @Override
    public boolean IsDeathMatch() {
        return DOOM.deathmatch;
    }

    @Override
    public boolean IsAutoMapActive() {
        return DOOM.automapactive;
    }

    @Override
    public boolean IsMenuActive() {
        return DOOM.menuactive;
    }

    /**
     * TODO: avoid, deprecate
     */
    @Override
    public DoomMain<?, ?> DOOM() {
        return DOOM;
    }

    @Override
    public SharedContext getContext() {
        return traitsSharedContext;
    }

    @Override
    public ActionsThinkers getThinkers() {
        return this;
    }

    @Override
    public ActionsEnemies getEnemies() {
        return this;
    }

    @Override
    public ActionsAttacks getAttacks() {
        return this;
    }
}
