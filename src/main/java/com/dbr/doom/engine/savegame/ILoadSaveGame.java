package com.dbr.doom.engine.savegame;

import com.dbr.doom.engine.p.ThinkerList;

public interface ILoadSaveGame {
    void setThinkerList(ThinkerList li);
    void doSave(ThinkerList li);
    void doLoad(ThinkerList li);
}
