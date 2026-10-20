#!/usr/bin/env bash
# Shader pack sweep: runs the Iris client game test once per local pack zip on Metal (optionally OpenGL as reference)
# and writes a compatibility table to docs/compat/shaderpacks-<date>.md. See docs/compat/README.md.
# Packs are local only (never copy them into the repo). Screenshots/logs go to RESULTS_DIR, not the repo.
#
# Environment overrides:
#   PACKS="a.zip|Solas Shader V3.8.zip"   subset: '|'-separated file names or globs (Solas*, *Sildur*), so names with
#                         spaces work; without '|' a single exact name is tried first, then space-separated names/globs.
#   PACKS_FILE=list.txt   one name per line (alternative to PACKS)
#   PACK_DIR=/Users/noah/Desktop/Metallium/metallium-research/shaderpacks
#   RESULTS_DIR=/Users/noah/Desktop/Metallium/metallium-research/sweep-results/<date>
#   REFERENCE=1           also run each pack with -Pengine=OPENGL (reference screenshot)
#   GPU_LOCK=/path/gpu-lock.sh   wraps each gradle run (not --exclusive: compatibility runs may share the GPU)
#   OUT=docs/compat/shaderpacks-<date>.md     SMOKE=1 marks the output as a smoke run (do not commit)
set -u
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Users/noah/Library/Java/JavaVirtualMachines/temurin-25.0.4.1/Contents/Home}"
PACK_DIR="${PACK_DIR:-/Users/noah/Desktop/Metallium/metallium-research/shaderpacks}"
DATE="$(date +%Y-%m-%d)"
RESULTS_DIR="${RESULTS_DIR:-/Users/noah/Desktop/Metallium/metallium-research/sweep-results/$DATE}"
OUT="${OUT:-docs/compat/shaderpacks-$DATE.md}"
REFERENCE="${REFERENCE:-0}"
SMOKE="${SMOKE:-0}"
RUNDIR="build/run/clientGameTest"
REPORT="$RUNDIR/metallium-gametest-report.txt"
LOG="$RUNDIR/logs/latest.log"
mkdir -p "$RESULTS_DIR" "$(dirname "$OUT")"

LIST=()
if [ -n "${PACKS_FILE:-}" ]; then
  while IFS= read -r l; do [ -n "$l" ] && LIST+=("$l"); done < "$PACKS_FILE"
elif [ -n "${PACKS:-}" ]; then
  # Each entry is an exact file name or a glob inside PACK_DIR (file names may contain spaces and apostrophes).
  add_pack() {
    local entry="$1" f found=""
    if [ -f "$PACK_DIR/$entry" ]; then LIST+=("$entry"); return; fi
    for f in "$PACK_DIR"/$entry; do [ -f "$f" ] && { LIST+=("$(basename "$f")"); found=1; }; done
    [ -n "$found" ] || LIST+=("$entry") # reported as "pack file not found" below
  }
  if [[ "$PACKS" == *"|"* ]]; then
    IFS='|' read -r -a ENTRIES <<< "$PACKS"
  elif [ -f "$PACK_DIR/$PACKS" ]; then
    ENTRIES=("$PACKS")
  else
    read -r -a ENTRIES <<< "$PACKS"
  fi
  for p in "${ENTRIES[@]}"; do [ -n "$p" ] && add_pack "$p"; done
else
  for z in "$PACK_DIR"/*.zip; do LIST+=("$(basename "$z")"); done
fi

ROWS="$(mktemp)"; trap 'rm -f "$ROWS"' EXIT

manifest() { # manifest <file> <column: 4 = version, 7 = licence>
  awk -F'|' -v f="$1" -v c="$2" '{n=$5; gsub(/^ +| +$/,"",n); if (n==f) {v=$c; gsub(/^ +| +$/,"",v); print v}}' "$PACK_DIR/MANIFEST.md" | head -1
}

run_one() { # run_one <zip> <engine> <tag>: runs the game test, copies report/log/screenshot into RESULTS_DIR
  local zip="$1" engine="$2" tag="$3"
  [ -n "${GPU_LOCK:-}" ] || until ! pgrep -f "[d]evlaunchinjector.Main" >/dev/null; do sleep 5; done
  rm -rf "$REPORT" "$RUNDIR/screenshots" "$LOG"
  ${GPU_LOCK:+$GPU_LOCK} ./gradlew --no-daemon :runClientGameTest -PwithSodium -PwithIris "-Pengine=$engine" "-Pshaderpack=$PACK_DIR/$zip" \
    > "$RESULTS_DIR/$tag-gradle.log" 2>&1
  STATUS_RUN=$?
  [ -f "$REPORT" ] && cp "$REPORT" "$RESULTS_DIR/$tag-report.txt"
  [ -f "$LOG" ] && cp "$LOG" "$RESULTS_DIR/$tag-latest.log"
  # screenshots are numbered by the game test (0011_metallium-iris-external-pack-natural.png): match by suffix
  local f
  for f in "$RUNDIR"/screenshots/*_metallium-iris-external-pack-natural.png; do [ -f "$f" ] && cp "$f" "$RESULTS_DIR/$tag-natural.png"; done
  for f in "$RUNDIR"/screenshots/*_metallium-iris-external-pack.png; do [ -f "$f" ] && cp "$f" "$RESULTS_DIR/$tag-flat.png"; done
}

for zip in "${LIST[@]}"; do
  if [ ! -f "$PACK_DIR/$zip" ]; then
    echo "| $zip | - | - | fails | - | pack file not found |" >> "$ROWS"; continue
  fi
  tag="$(printf '%s' "${zip%.zip}" | tr -c 'A-Za-z0-9._\n-' '_')"
  echo "=== $zip on METAL ($(date +%H:%M:%S)) ==="
  run_one "$zip" METAL "$tag-metal"
  mrep="$RESULTS_DIR/$tag-metal-report.txt"; mlog="$RESULTS_DIR/$tag-metal-latest.log"
  version="$(manifest "$zip" 4)"; licence="$(manifest "$zip" 7)"
  version="${version:-?}"; licence="${licence:-?}"

  crash="no"; loaded="no"; pipeline="-"; cov="-"; missing=""; ntrans=0; nfail=0; nnotrun=""; testres="-"; info=""; frame="-"
  if [ ! -f "$mrep" ]; then
    crash="yes (no report, gradle exit $STATUS_RUN)"
  else
    pipeline="$(sed -n 's/^external pack (natural world): pipeline=//p' "$mrep" | head -1)"
    [ -z "$pipeline" ] && pipeline="$(sed -n 's/^external pack [^:]*: pipeline=//p' "$mrep" | head -1)"
    [ -n "$pipeline" ] && [ "$pipeline" != "null" ] && loaded="yes"
    cov="$(sed -n 's/^pack: terrain coverage=//p' "$mrep" | head -1)"; cov="${cov:--}"
    testres="$(grep '^result:' "$mrep" | tail -1 | cut -c1-120)"
    [ -z "$testres" ] && crash="yes (report incomplete, gradle exit $STATUS_RUN)"
  fi
  if [ -f "$mlog" ]; then
    grep -q 'Crash report saved\|Exception in thread "main"' "$mlog" && crash="yes (see $mlog)"
    ntrans="$(grep -c 'gbuffers program .* replaces' "$mlog")"
    nfail="$(grep -c 'could not be translated for Metal' "$mlog")"
    # DH programs (dh_terrain, dh_water, ...) only run with Distant Horizons installed, which the sweep does not load:
    # informational, not a missing feature. Other programs that are not executed still count.
    nnotrun="$(sed -n 's/.*programs not executed: //p' "$mlog" | head -1 | tr ',' '\n' | sed 's/^ *//' | grep -v '^dh_' | paste -sd, - | sed 's/,/, /g')"
    grep -q 'DH programs are not executed' "$mlog" && info="DH programs skipped (DH not installed)"
    # one entry per distinct reason with a count; "failed to translate (<program>: <reason>)" collapses to the reason
    missing="$(sed -n 's/.*Unsupported shader pack feature: //p' "$mlog" | grep -v 'DH programs are not executed' \
      | sed -e 's/^[A-Za-z0-9_]*: failed to translate ([^:]*: \(.*\)); vanilla shader used.*/translation failed: \1/' \
            -e 's/^\([A-Za-z0-9_]*\): \(mipmapped.*\)/\2 [\1]/' \
      | sort | uniq -c | sort -rn \
      | awk '{n=$1; $1=""; sub(/^ /,""); printf "%s%s%s", (NR>1?"; ":""), (n>1?n "x ":""), substr($0,1,140)}')"
  fi
  shot="$RESULTS_DIR/$tag-metal-natural.png"; [ -f "$shot" ] || shot="(none)"

  # Luminance/variance of the world area (tools/FrameStats.java): terrain coverage alone reads 1.00 for a black frame.
  verdict=""
  if [ -f "$shot" ]; then
    frame="$("$JAVA_HOME/bin/java" tools/FrameStats.java "$shot" 2>/dev/null | sed 's/^.* mean=/mean=/')"
    verdict="$(printf '%s' "$frame" | sed -n 's/.*verdict=//p')"
  fi
  status="works"
  if [ "$loaded" = no ] || [[ "$crash" == yes* ]]; then status="fails"
  elif [ "$verdict" = black ] || [ "$verdict" = uniform ]; then status="broken"; missing="${missing:+$missing; }$verdict frame"
  elif [ -n "$missing" ] || [ "$nfail" -gt 0 ] || [ -n "$nnotrun" ]; then status="partial"; fi
  [ "$verdict" = dark ] && missing="${missing:+$missing; }mostly dark frame (check screenshot)"
  if [ "$cov" != "-" ] && awk -v c="$cov" 'BEGIN{exit !(c<0.30)}'; then
    [ "$status" = works ] && status="partial"; missing="${missing:+$missing; }low terrain coverage"
  fi

  refshot=""; refnote=""
  if [ "$REFERENCE" = 1 ]; then
    echo "=== $zip on OPENGL ($(date +%H:%M:%S)) ==="
    run_one "$zip" OPENGL "$tag-opengl"
    [ -f "$RESULTS_DIR/$tag-opengl-natural.png" ] && refshot="; OpenGL reference: $RESULTS_DIR/$tag-opengl-natural.png"
    # macOS OpenGL is 4.1: packs that need GL 4.2+ (compute shaders, #version 430, glClearTexImage, ...) fail to load
    # in Iris on OpenGL, which then renders unshaded vanilla. Such a "reference" shows nothing about the pack.
    grep_rep="$RESULTS_DIR/$tag-opengl-report.txt"; grep_log="$RESULTS_DIR/$tag-opengl-latest.log"
    if [ -f "$grep_rep" ] && grep -q '^external pack (natural world): pipeline=.*VanillaRenderingPipeline' "$grep_rep"; then
      why=""
      [ -f "$grep_log" ] && why="$(grep -A3 -m1 'Failed to create shader rendering pipeline\|Failed to load the shaderpack' "$grep_log" \
        | grep -v 'Failed to\|(Iris) *$' | head -1 | sed -e 's/^[[:space:]]*//' -e 's/^[a-zA-Z.]*Exception: //' | cut -c1-120)"
      refnote="; OpenGL reference INVALID (Iris fell back to vanilla on GL 4.1${why:+: $why})"
      refshot=""
    fi
  fi
  notes="pipeline ${pipeline##*.}; crash: $crash; test $testres; coverage $cov; frame $frame${info:+; info: $info}$refnote; $ntrans gbuffers programs translated, $nfail failed translation${nnotrun:+; not executed: $nnotrun}; screenshot: $shot$refshot; log: $mlog"
  echo "| $zip | $version | $licence | $status | ${missing:--} | $notes |" >> "$ROWS"
done

{
  if [ "$SMOKE" = 1 ]; then
    echo "# Shader pack compatibility $DATE (SMOKE RUN - NOT A SWEEP)"; echo
    echo "> Smoke run to validate tools/pack-sweep.sh on a subset of packs. Do not commit."
  else
    echo "# Shader pack compatibility $DATE"
  fi
  echo
  echo "- Commit: $(git rev-parse --short HEAD)$(git diff --quiet || echo ' (dirty)'); machine: $(sysctl -n machdep.cpu.brand_string), macOS $(sw_vers -productVersion)"
  echo "- Results (screenshots, logs): $RESULTS_DIR"
  echo
  echo "## Summary"; echo
  echo "- Packs run: $(grep -c '^| ' "$ROWS"); works: $(grep -c '| works |' "$ROWS"); partial: $(grep -c '| partial |' "$ROWS"); broken: $(grep -c '| broken |' "$ROWS"); fails: $(grep -c '| fails |' "$ROWS")"
  echo "- works = loaded, no crash, no unsupported-feature report; partial = renders but features reported missing/skipped; broken = loaded but the frame is black or a flat colour (tools/FrameStats.java); fails = not loaded or crashed."
  echo "- DH programs that are not executed are informational (Distant Horizons is not installed in the sweep)."
  echo "- Screenshots must still be looked at: coverage and frame luminance only detect empty/sky-only/black frames, not wrong colours."
  echo; echo "## Table"; echo
  echo "| Pack | Version | Licence | Status | Missing features | Notes |"
  echo "|---|---|---|---|---|---|"
  cat "$ROWS"
} > "$OUT"
echo "wrote $OUT"
