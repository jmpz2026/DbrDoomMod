package com.dbr.doom.engine.timing;

import com.dbr.doom.engine.doom.CVarManager;
import com.dbr.doom.engine.doom.CommandVariable;
import com.dbr.doom.engine.doom.SourceCode.I_IBM;
import static com.dbr.doom.engine.doom.SourceCode.I_IBM.*;

public interface ITicker {

    static ITicker createTicker(CVarManager CVM) {
        if (CVM.bool(CommandVariable.MILLIS)) {
            return new MilliTicker();
        } else if (CVM.bool(CommandVariable.FASTTIC) || CVM.bool(CommandVariable.FASTDEMO)) {
            return new DelegateTicker();
        } else {
            return new NanoTicker();
        }
    }
    
    @I_IBM.C(I_GetTime)
    public int GetTime();
}