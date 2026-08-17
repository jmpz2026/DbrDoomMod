package com.dbr.doom.engine.m;

import com.dbr.doom.engine.data.mobjtype_t;
import com.dbr.doom.engine.p.ActiveStates;

public interface IRandom {
	public int P_Random ();
	public int M_Random ();
	public void ClearRandom ();
	public int getIndex();
	/*
	 * DbrDoomMod: the whole random state, and a way to put it back.
	 *
	 * A run is now recorded one map at a time, and the random index carries
	 * across a level in real play while a replay of a demo starts from a
	 * cleared one. Restoring it is what keeps the two identical; without it
	 * the first monster to act in a continued map rolls a different number.
	 *
	 * Only DoomRandom can honour this, which is enough: recorded runs always
	 * use it, because InitNew switches to it whenever -javarandom is absent.
	 */
	public int[] dbrIndices();
	public void dbrSetIndices(int prndindex, int rndindex);
	public int P_Random(int caller);
	public int P_Random(String message);
	public int P_Random(ActiveStates caller, int sequence);
	public int P_Random(ActiveStates caller, mobjtype_t type,int sequence);
}
