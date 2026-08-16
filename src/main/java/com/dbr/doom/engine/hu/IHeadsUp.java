package com.dbr.doom.engine.hu;

import com.dbr.doom.engine.doom.SourceCode.HU_Stuff;
import static com.dbr.doom.engine.doom.SourceCode.HU_Stuff.HU_Responder;
import com.dbr.doom.engine.doom.event_t;
import com.dbr.doom.engine.rr.patch_t;

public interface IHeadsUp {

	void Ticker();

	void Erase();

	void Drawer();

    @HU_Stuff.C(HU_Responder)
	boolean Responder(event_t ev);

	patch_t[] getHUFonts();

	char dequeueChatChar();

	void Init();

	void setChatMacro(int i, String s);

	void Start();

	void Stop();

}
