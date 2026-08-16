#!/usr/bin/env bash
#
# Captures a thread dump from a frozen Minecraft dev client.
#
# Run this WHILE the client is still hung. The dump says what every thread is
# blocked on, and whether two of them are waiting on each other, which is the
# one thing a log cannot show.
#
# Usage: tools/dump-hang.sh [output-file]

set -uo pipefail

OUT="${1:-C:/temp/dbrdoom-hang.txt}"

echo "Looking for the Minecraft client..."

# The dev client runs as GradleStart. Skip the Gradle daemon and jps itself,
# or we would dump the build tool instead of the game.
PID="$(jps -l 2>/dev/null \
    | grep -viE 'jps|GradleDaemon|GradleWrapperMain' \
    | grep -iE 'GradleStart|net\.minecraft' \
    | head -1 \
    | awk '{print $1}')"

if [ -z "$PID" ]; then
    echo "No client found. Either it already closed, or it never started."
    echo "Everything currently running:"
    jps -l
    exit 1
fi

echo "Client PID: $PID"
mkdir -p "$(dirname "$OUT")"

# A live dump is far more readable. -F forces one out of a process that is too
# wedged to answer, at the cost of detail, so it is only the fallback.
if jstack -l "$PID" > "$OUT" 2>/dev/null && [ -s "$OUT" ]; then
    echo "Thread dump written to $OUT"
elif jstack -F "$PID" > "$OUT" 2>&1 && [ -s "$OUT" ]; then
    echo "Thread dump written to $OUT (forced)"
else
    echo "jstack could not read the process."
    exit 1
fi

echo
echo "--- deadlocks ---"
grep -iE "Found .* deadlock|Found one Java-level deadlock" "$OUT" || echo "none reported"

echo
echo "--- our threads ---"
grep -nE '^"(Client thread|Server thread|DbrDoom-Engine)"' "$OUT" || true

echo
if grep -q '"DbrDoom-Engine"' "$OUT"; then
    echo "A Doom session was running: this dump is of the right moment."
    echo "Send $OUT."
else
    # Without the engine thread there was no session, so whatever this caught is
    # a healthy client. Worth saying loudly: a dump from the wrong moment looks
    # exactly like a useful one, and the client thread sits in nSwapBuffers
    # waiting for vsync even when nothing is wrong.
    echo "WARNING: no Doom session was running when this was taken."
    echo "The client was not hung on a cabinet, so this dump proves nothing."
    echo "Take it while the game is actually frozen, with a session open."
fi
