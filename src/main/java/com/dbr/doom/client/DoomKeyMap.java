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

package com.dbr.doom.client;

import org.lwjgl.input.Keyboard;

import com.dbr.doom.engine.g.Signals.ScanCode;

/**
 * Translates LWJGL key codes into the engine's ScanCode values.
 *
 * Both sides are ultimately PC scancodes, but they are not the same numbering,
 * so the mapping is spelled out rather than assumed. Anything not listed here
 * is simply not forwarded to Doom.
 */
public final class DoomKeyMap {

    /** LWJGL key codes stay below this, so a flat array beats a HashMap. */
    private static final int TABLE_SIZE = 256;

    private static final ScanCode[] TABLE = new ScanCode[TABLE_SIZE];

    static {
        // Letters
        put(Keyboard.KEY_A, ScanCode.SC_A);
        put(Keyboard.KEY_B, ScanCode.SC_B);
        put(Keyboard.KEY_C, ScanCode.SC_C);
        put(Keyboard.KEY_D, ScanCode.SC_D);
        put(Keyboard.KEY_E, ScanCode.SC_E);
        put(Keyboard.KEY_F, ScanCode.SC_F);
        put(Keyboard.KEY_G, ScanCode.SC_G);
        put(Keyboard.KEY_H, ScanCode.SC_H);
        put(Keyboard.KEY_I, ScanCode.SC_I);
        put(Keyboard.KEY_J, ScanCode.SC_J);
        put(Keyboard.KEY_K, ScanCode.SC_K);
        put(Keyboard.KEY_L, ScanCode.SC_L);
        put(Keyboard.KEY_M, ScanCode.SC_M);
        put(Keyboard.KEY_N, ScanCode.SC_N);
        put(Keyboard.KEY_O, ScanCode.SC_O);
        put(Keyboard.KEY_P, ScanCode.SC_P);
        put(Keyboard.KEY_Q, ScanCode.SC_Q);
        put(Keyboard.KEY_R, ScanCode.SC_R);
        put(Keyboard.KEY_S, ScanCode.SC_S);
        put(Keyboard.KEY_T, ScanCode.SC_T);
        put(Keyboard.KEY_U, ScanCode.SC_U);
        put(Keyboard.KEY_V, ScanCode.SC_V);
        put(Keyboard.KEY_W, ScanCode.SC_W);
        put(Keyboard.KEY_X, ScanCode.SC_X);
        put(Keyboard.KEY_Y, ScanCode.SC_Y);
        put(Keyboard.KEY_Z, ScanCode.SC_Z);

        // Number row
        put(Keyboard.KEY_0, ScanCode.SC_0);
        put(Keyboard.KEY_1, ScanCode.SC_1);
        put(Keyboard.KEY_2, ScanCode.SC_2);
        put(Keyboard.KEY_3, ScanCode.SC_3);
        put(Keyboard.KEY_4, ScanCode.SC_4);
        put(Keyboard.KEY_5, ScanCode.SC_5);
        put(Keyboard.KEY_6, ScanCode.SC_6);
        put(Keyboard.KEY_7, ScanCode.SC_7);
        put(Keyboard.KEY_8, ScanCode.SC_8);
        put(Keyboard.KEY_9, ScanCode.SC_9);

        // Movement and editing
        put(Keyboard.KEY_UP, ScanCode.SC_UP);
        put(Keyboard.KEY_DOWN, ScanCode.SC_DOWN);
        put(Keyboard.KEY_LEFT, ScanCode.SC_LEFT);
        put(Keyboard.KEY_RIGHT, ScanCode.SC_RIGHT);
        put(Keyboard.KEY_HOME, ScanCode.SC_HOME);
        put(Keyboard.KEY_END, ScanCode.SC_END);
        put(Keyboard.KEY_PRIOR, ScanCode.SC_PGUP);
        put(Keyboard.KEY_NEXT, ScanCode.SC_PGDOWN);
        put(Keyboard.KEY_INSERT, ScanCode.SC_INSERT);
        put(Keyboard.KEY_DELETE, ScanCode.SC_DELETE);

        // Modifiers and whitespace
        put(Keyboard.KEY_LSHIFT, ScanCode.SC_LSHIFT);
        put(Keyboard.KEY_RSHIFT, ScanCode.SC_RSHIFT);
        put(Keyboard.KEY_LCONTROL, ScanCode.SC_LCTRL);
        put(Keyboard.KEY_RCONTROL, ScanCode.SC_RCTRL);
        put(Keyboard.KEY_LMENU, ScanCode.SC_LALT);
        put(Keyboard.KEY_RMENU, ScanCode.SC_RALT);
        put(Keyboard.KEY_SPACE, ScanCode.SC_SPACE);
        put(Keyboard.KEY_RETURN, ScanCode.SC_ENTER);
        put(Keyboard.KEY_TAB, ScanCode.SC_TAB);
        put(Keyboard.KEY_BACK, ScanCode.SC_BACKSPACE);
        put(Keyboard.KEY_ESCAPE, ScanCode.SC_ESCAPE);
        put(Keyboard.KEY_PAUSE, ScanCode.SC_PAUSE);
        put(Keyboard.KEY_CAPITAL, ScanCode.SC_CAPSLK);
        put(Keyboard.KEY_NUMLOCK, ScanCode.SC_NUMLK);
        put(Keyboard.KEY_SCROLL, ScanCode.SC_SCROLLLK);

        // Punctuation
        put(Keyboard.KEY_MINUS, ScanCode.SC_MINUS);
        put(Keyboard.KEY_EQUALS, ScanCode.SC_EQUALS);
        put(Keyboard.KEY_LBRACKET, ScanCode.SC_LBRACE);
        put(Keyboard.KEY_RBRACKET, ScanCode.SC_RBRACE);
        put(Keyboard.KEY_SEMICOLON, ScanCode.SC_SEMICOLON);
        put(Keyboard.KEY_APOSTROPHE, ScanCode.SC_QUOTE);
        put(Keyboard.KEY_GRAVE, ScanCode.SC_TILDE);
        put(Keyboard.KEY_BACKSLASH, ScanCode.SC_BACKSLASH);
        put(Keyboard.KEY_COMMA, ScanCode.SC_COMMA);
        put(Keyboard.KEY_PERIOD, ScanCode.SC_PERIOD);
        put(Keyboard.KEY_SLASH, ScanCode.SC_SLASH);

        // Function row. F10 is deliberately absent: it is the escape hatch back
        // to Minecraft and must never reach the game. See GuiDoom.
        put(Keyboard.KEY_F1, ScanCode.SC_F1);
        put(Keyboard.KEY_F2, ScanCode.SC_F2);
        put(Keyboard.KEY_F3, ScanCode.SC_F3);
        put(Keyboard.KEY_F4, ScanCode.SC_F4);
        put(Keyboard.KEY_F5, ScanCode.SC_F5);
        put(Keyboard.KEY_F6, ScanCode.SC_F6);
        put(Keyboard.KEY_F7, ScanCode.SC_F7);
        put(Keyboard.KEY_F8, ScanCode.SC_F8);
        put(Keyboard.KEY_F9, ScanCode.SC_F9);
        put(Keyboard.KEY_F11, ScanCode.SC_F11);
        put(Keyboard.KEY_F12, ScanCode.SC_F12);

        // Numpad
        put(Keyboard.KEY_NUMPAD0, ScanCode.SC_NUMKEY0);
        put(Keyboard.KEY_NUMPAD1, ScanCode.SC_NUMKEY1);
        put(Keyboard.KEY_NUMPAD2, ScanCode.SC_NUMKEY2);
        put(Keyboard.KEY_NUMPAD3, ScanCode.SC_NUMKEY3);
        put(Keyboard.KEY_NUMPAD4, ScanCode.SC_NUMKEY4);
        put(Keyboard.KEY_NUMPAD5, ScanCode.SC_NUMKEY5);
        put(Keyboard.KEY_NUMPAD6, ScanCode.SC_NUMKEY6);
        put(Keyboard.KEY_NUMPAD7, ScanCode.SC_NUMKEY7);
        put(Keyboard.KEY_NUMPAD8, ScanCode.SC_NUMKEY8);
        put(Keyboard.KEY_NUMPAD9, ScanCode.SC_NUMKEY9);
        put(Keyboard.KEY_NUMPADENTER, ScanCode.SC_NPENTER);
        put(Keyboard.KEY_ADD, ScanCode.SC_NPPLUS);
        put(Keyboard.KEY_SUBTRACT, ScanCode.SC_NPMINUS);
        put(Keyboard.KEY_MULTIPLY, ScanCode.SC_NPMULTIPLY);
        put(Keyboard.KEY_DIVIDE, ScanCode.SC_NPSLASH);
        put(Keyboard.KEY_DECIMAL, ScanCode.SC_NPDOT);
    }

    private DoomKeyMap() {
    }

    private static void put(int lwjglKey, ScanCode sc) {
        if (lwjglKey > 0 && lwjglKey < TABLE_SIZE) {
            TABLE[lwjglKey] = sc;
        }
    }

    /**
     * @param lwjglKey a code from {@link Keyboard}
     * @return the matching ScanCode, or null if Doom has no use for this key
     */
    public static ScanCode toScanCode(int lwjglKey) {
        if (lwjglKey < 0 || lwjglKey >= TABLE_SIZE) {
            return null;
        }
        return TABLE[lwjglKey];
    }
}
