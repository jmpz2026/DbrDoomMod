package com.dbr.doom.engine.p;

import com.dbr.doom.engine.doom.SourceCode.P_Tick;
import static com.dbr.doom.engine.doom.SourceCode.P_Tick.*;
import com.dbr.doom.engine.doom.thinker_t;

public interface ThinkerList {

    @P_Tick.C(P_AddThinker)
    void AddThinker(thinker_t thinker);
    @P_Tick.C(P_RemoveThinker)
    void RemoveThinker(thinker_t thinker);
    @P_Tick.C(P_InitThinkers)
    void InitThinkers();
    
    thinker_t getRandomThinker();
    thinker_t getThinkerCap();
}
