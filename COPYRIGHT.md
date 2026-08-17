# Copyright

Copyright (c) 2026 Machitos (Dbr) and DbrDoomMod contributors.

This project is licensed under the **GNU General Public License v3.0 or later**.
The full text is in `LICENSE`.

It cannot be licensed any other way: the Doom engine vendored under
`src/main/java/com/dbr/doom/engine/` is [Mocha Doom][mocha], a GPLv3 Java source
port, and a work built on it is covered by the same licence.

You may use, study, share and modify this work under the terms of the GPLv3. If
you distribute it, or anything derived from it, you must do so under the GPLv3
and make the corresponding source available.

## Bundled third-party work

**Mocha Doom** — GPLv3. Vendored and relocated under `com.dbr.doom.engine`.
See `third-party/` and `tools/vendor-mochadoom.sh`.

**Freedoom** — three-clause BSD. The game data in
`src/main/resources/assets/dbrdoom/wads/` is a *modified* Freedoom: maps outside
Episode 1 are stripped and unused art is pruned. `COPYING-freedoom.txt`,
`CREDITS-freedoom.txt` and `MODIFICATIONS-freedoom.txt` ship next to it, as the
licence requires.

`DOOM.WAD` and `DOOM2.WAD` are proprietary and are **never** bundled. Players
supply their own.

Contact: elcorremachitos.2015@gmail.com

[mocha]: https://github.com/AXDOOMER/mochadoom
