#!/bin/bash
# One Minecraft window at a time on the test Mac. Wrap every game test, runClient and benchmark in this script:
#   tools/gpu-lock.sh ./gradlew runClientGameTest ...                 (normal game test, waits for a free slot)
#   tools/gpu-lock.sh --exclusive ./gradlew runClientGameTest -Pbench ...  (benchmarks / timing measurements)
# Lock directory: $NATIVIUM_GPU_LOCK_DIR, default /private/tmp/claude-<uid>/nativium-gpu-slots (writable from the agent sandbox).
# Flag files in that directory (written by the coordinator or the owner, never by a test):
#   bench-allowed       fullscreen / --exclusive benchmarks are allowed (owner away); without it they are refused (exit 3)
#   owner-playing       contains a pid; while it lives, every test waits (the owner is playing)
#   tests-while-playing normal (windowed) tests may run while owner-playing is set
# Benchmarks also wait while the owner's own Minecraft (a non-dev client, e.g. Prism) runs.
D=${NATIVIUM_GPU_LOCK_DIR:-/private/tmp/claude-$(id -u)/nativium-gpu-slots}; SLOTS=${NATIVIUM_GPU_SLOTS:-1}; mkdir -p "$D"
case " $* " in *benchFullscreen*|*benchDisplay*|*" --exclusive "*)
  if [ ! -e "$D/bench-allowed" ]; then echo "gpu-lock: REFUSED - fullscreen/exclusive benchmarks are not allowed right now (no $D/bench-allowed)" >&2; exit 3; fi;;
esac
free_stale() { for s in "$D"/slot*; do [ -d "$s" ] || continue; p=$(cat "$s/pid" 2>/dev/null); [ -n "$p" ] && ! kill -0 "$p" 2>/dev/null && rm -rf "$s"; done; }
taken=()
owner_playing() { [ -e "$D/owner-playing" ] || return 1; p=$(cat "$D/owner-playing" 2>/dev/null); [ -n "$p" ] && kill -0 "$p" 2>/dev/null && return 0; rm -f "$D/owner-playing"; return 1; }
cleanup() { for s in "${taken[@]}"; do rm -rf "$s"; done; }
trap cleanup EXIT INT TERM
owner_game_running() { pgrep -f "KnotClient|net.minecraft.client.main.Main" | while read p; do ps -o command= -p "$p" | grep -q devlaunchinjector || { echo yes; break; }; done | grep -q yes; }
if [ "$1" = "--exclusive" ]; then
  shift
  while owner_game_running || owner_playing; do sleep 10; done
  for i in $(seq 0 $((SLOTS-1))); do
    until mkdir "$D/slot$i" 2>/dev/null; do free_stale; sleep 3; done
    echo $$ > "$D/slot$i/pid"; taken+=("$D/slot$i")
  done
else
  got=""
  while [ -z "$got" ]; do
    while owner_playing && [ ! -e "$D/tests-while-playing" ]; do sleep 10; done
    for i in $(seq 0 $((SLOTS-1))); do
      if mkdir "$D/slot$i" 2>/dev/null; then echo $$ > "$D/slot$i/pid"; taken+=("$D/slot$i"); got=1; break; fi
    done
    [ -z "$got" ] && { free_stale; sleep 3; }
  done
fi
"$@"
