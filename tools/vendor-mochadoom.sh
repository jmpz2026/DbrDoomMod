#!/usr/bin/env bash
#
# Vendors the Mocha Doom engine into this mod, relocating its root packages
# under com.dbr.doom.engine so they cannot collide with other mods on the
# classpath (Mocha Doom uses single-letter root packages like m, p, s, v, w).
#
# Idempotent: wipes and regenerates the target directory. Re-run it to sync
# with a newer upstream checkout.
#
# Usage: tools/vendor-mochadoom.sh [path-to-mochadoom-checkout]
#
# Mocha Doom is GPLv3. See LICENSE and README.md for attribution.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

UPSTREAM="${1:-$PROJECT_DIR/../DoomRefs/mochadoom}"
SRC="$UPSTREAM/src"
DST="$PROJECT_DIR/src/main/java/com/dbr/doom/engine"

NEW_PREFIX="com.dbr.doom.engine"

# Root packages of Mocha Doom, taken from the directory names under src/.
ROOTS="automap|awt|boom|data|defines|demo|doom|f|g|hu|i|m|mochadoom|n|p|pooling|rr|s|savegame|st|timing|utils|v|w"

if [ ! -d "$SRC" ]; then
    echo "error: no Mocha Doom source at $SRC" >&2
    echo "clone https://github.com/AXDOOMER/mochadoom and pass its path" >&2
    exit 1
fi

echo "upstream : $SRC"
echo "target   : $DST"

# Clear the contents rather than the directory itself: on Windows an IDE or a
# Gradle daemon may hold a handle on the directory, which makes removing it
# fail with "Device or resource busy".
mkdir -p "$DST"
find "$DST" -mindepth 1 -delete

# Copy only .java files. The .c/.h files in the tree are original id Software
# sources kept for reference, and Manifest.txt/README.md belong to the upstream
# build, not to ours.
count=0
while IFS= read -r file; do
    rel="${file#$SRC/}"
    mkdir -p "$DST/$(dirname "$rel")"
    cp "$file" "$DST/$rel"
    count=$((count + 1))
done < <(find "$SRC" -type f -name '*.java')

echo "copied   : $count .java files"

# Rewrite package and import statements. Static imports are handled first;
# "static" is not a root package name so the orders cannot interfere, but
# keeping them separate makes the intent explicit.
#
# Only the roots listed above are rewritten, so java.*, javax.* and any other
# JDK import is left untouched.
#
# The leading [[:space:]]* matters: v/renderers/BufferedRenderer.java indents
# its package declaration by one space.
find "$DST" -type f -name '*.java' -print0 | xargs -0 sed -i -E \
    -e "s/^([[:space:]]*package[[:space:]]+)($ROOTS)([.;])/\1$NEW_PREFIX.\2\3/" \
    -e "s/^([[:space:]]*import[[:space:]]+static[[:space:]]+)($ROOTS)\./\1$NEW_PREFIX.\2./" \
    -e "s/^([[:space:]]*import[[:space:]]+)($ROOTS)\./\1$NEW_PREFIX.\2./"

echo "rewrote  : package and import statements -> $NEW_PREFIX.*"

# A handful of places reference engine classes fully-qualified inline, where no
# import statement exists to rewrite. These are listed one by one on purpose:
# a blind rewrite of, say, "data\." would corrupt BoomLevelLoader, which has a
# local variable named "data" (see "data[i].x" around line 340).
#
# The (^|[^A-Za-z0-9_.]) guard makes sure we only match a real package
# qualifier and never the tail of a longer expression.
INLINE_REFS=(
    'hu.HU.'
    'automap.Map'
    'w.CacheableDoomObject'
    'm.fixed_t.'
    'data.Defines.'
    's.ISoundDriver.'
    'rr.RendererState.'
)

for ref in "${INLINE_REFS[@]}"; do
    escaped="${ref//./\\.}"
    find "$DST" -type f -name '*.java' -print0 | xargs -0 sed -i -E \
        -e "s/(^|[^A-Za-z0-9_.])$escaped/\1$NEW_PREFIX.$ref/g"
done

echo "rewrote  : ${#INLINE_REFS[@]} inline fully-qualified references"

# Standalone Doom quits by calling System.exit(). Inside Minecraft that kills
# the whole game, so every call becomes a throw the host can catch. The name is
# fully qualified on purpose: no import has to be inserted anywhere.
find "$DST" -type f -name '*.java' -print0 | xargs -0 sed -i -E \
    -e 's/System\.exit\(([-0-9]+)\);/throw new com.dbr.doom.host.DoomExitException(\1);/g'

exits=$(grep -rl 'DoomExitException' "$DST" --include='*.java' | wc -l)
echo "rewrote  : System.exit() -> DoomExitException in $exits files"

# Structural edits that cannot be expressed as a substitution: the headless
# Engine constructor, splitting DoomLoop()'s infinite loop into runOneFrame(),
# the config and savegame path hooks, and two "return null;" statements that
# become unreachable once System.exit() is a throw.
PATCH_DIR="$SCRIPT_DIR/patches"
if [ -d "$PATCH_DIR" ]; then
    for p in "$PATCH_DIR"/*.patch; do
        [ -e "$p" ] || continue
        echo "applying : $(basename "$p")"
        patch -p1 -d "$DST" --no-backup-if-mismatch < "$p"
    done
fi

echo
echo "done. now run: ./gradlew build"
echo
echo "If upstream moved and a patch no longer applies, fix the engine sources by"
echo "hand and regenerate tools/patches/ by diffing against a run of this script"
echo "with the patch step disabled."
