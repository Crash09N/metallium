#!/bin/bash
# Builds a reproducible test copy of a real Minecraft instance for `./gradlew :runClientGameTest -Pinstance`.
#
#   tools/make-run-instance.sh [SOURCE_INSTANCE] [DEST]
#
# SOURCE_INSTANCE defaults to $NATIVIUM_INSTANCE (or the old $METALLIUM_INSTANCE) or /Users/noah/Desktop/Nativium/nativium-research/minecraft-instance,
# DEST to run-instance/ in the repository (git-ignored). Copies the enabled mods (*.jar; *.disabled stay out),
# shaderpacks/, resourcepacks/, config/ and options.txt. Never copies saves, server lists, account data (essential/),
# logs, crash reports or screenshots. Fabric API is left out because the dev game-test classpath already provides it
# (two copies of the same mod id would stop Fabric Loader). Nativium itself comes from the dev build, never from mods/.
# Essential is skipped (KEEP_ESSENTIAL=1 keeps it), see below. SKIP_MODS="glob1 glob2" leaves out more jars. Nothing from DEST may be committed.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
src="${1:-${NATIVIUM_INSTANCE:-${METALLIUM_INSTANCE:-/Users/noah/Desktop/Nativium/nativium-research/minecraft-instance}}}"
dest="${2:-$repo/run-instance}"
[ -f "$src/options.txt" ] || { echo "not a Minecraft instance: $src" >&2; exit 1; }

mkdir -p "$dest/mods"
# Fresh mods/ every time, so removed or disabled mods do not linger.
find "$dest/mods" -maxdepth 1 -name '*.jar' -delete
for jar in "$src"/mods/*.jar; do
	name="$(basename "$jar")"
	case "$name" in
		fabric-api-*|nativium*|metallium*) continue ;;
		# Essential's loader remaps Essential to "dev mappings" whenever fabric.development=true and fails without a
		# mappings.tiny on the classpath (26.x has none), so it cannot start in a Loom dev run. Production only.
		Essential_*|essential-*) [ -n "${KEEP_ESSENTIAL:-}" ] || { echo "skipping $name (cannot run in a dev launch)"; continue; } ;;
	esac
	skip=""
	for pattern in ${SKIP_MODS:-}; do
		# shellcheck disable=SC2254
		case "$name" in $pattern) skip=1 ;; esac
	done
	[ -n "$skip" ] && { echo "skipping $name (SKIP_MODS)"; continue; }
	cp -p "$jar" "$dest/mods/"
done
for dir in shaderpacks resourcepacks config; do
	[ -d "$src/$dir" ] && rsync -a --delete --exclude '.DS_Store' "$src/$dir/" "$dest/$dir/"
done

# options.txt: windowed, silent, no pause when the window loses focus (automated runs).
sed -E \
	-e 's/^fullscreen:.*/fullscreen:false/' \
	-e 's/^(soundCategory_[a-z_]+):.*/\1:0.0/' \
	-e 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' \
	"$src/options.txt" > "$dest/options.txt"

echo "run instance: $dest ($(ls "$dest/mods" | wc -l | tr -d ' ') mods, $(ls "$dest/shaderpacks" 2>/dev/null | grep -c '\.zip$') shader packs)"

# Background FPS limiters: the test window usually has no focus (Dynamic FPS then renders 1 fps, FPS Reducer reduces
# in the background), which makes every FPS number meaningless. Off in the copy unless KEEP_FPS_LIMITERS=1.
if [ -z "${KEEP_FPS_LIMITERS:-}" ]; then
	echo '{"enabled": false}' > "$dest/config/dynamic_fps.json"
	[ -f "$dest/config/fpsreducer.json" ] && sed -i '' -E 's/"enableFpsReducer": *true/"enableFpsReducer": false/' "$dest/config/fpsreducer.json"
	echo "FPS limiters (Dynamic FPS, FPS Reducer) disabled in the copy"
fi
