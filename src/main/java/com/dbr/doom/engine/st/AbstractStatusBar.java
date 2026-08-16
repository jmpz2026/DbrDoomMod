package com.dbr.doom.engine.st;

import com.dbr.doom.engine.doom.DoomMain;

public abstract class AbstractStatusBar implements IDoomStatusBar {
    protected final DoomMain<?, ?> DOOM;

    public AbstractStatusBar(DoomMain<?, ?> DOOM) {
        this.DOOM = DOOM;
    }
}
