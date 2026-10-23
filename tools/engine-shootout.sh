#!/usr/bin/env bash
# Engine shootout: runs engines x scenes sequentially through the client game test benchmark and writes a Markdown
# table to docs/benchmarks/shootout-<date>.md. See docs/benchmarks/README.md.
#
# Environment overrides:
#   ENGINES="METAL OPENGL VULKAN"        SCENES="light rd32 shaders flight"
#   BENCH_SECONDS=30                     SHADERPACK=/path/to/pack.zip
#   GPU_LOCK=/path/gpu-lock.sh           (each gradle run is wrapped: "$GPU_LOCK --exclusive ./gradlew ...")
#   RD=16 (render distance override)     VSYNC=1 (VSync on)    LABEL=baseline (tag for log names/title)
#   OUT=docs/benchmarks/shootout-DATE.md SMOKE=1 (marks the output as a smoke run, not a measurement)
set -u
cd "$(dirname "$0")/.."
ROOT=$PWD

export JAVA_HOME="${JAVA_HOME:-/Users/noah/Library/Java/JavaVirtualMachines/temurin-25.0.4.1/Contents/Home}"
ENGINES="${ENGINES:-METAL OPENGL VULKAN}"
SCENES="${SCENES:-light rd32 shaders flight}"
BENCH_SECONDS="${BENCH_SECONDS:-30}"
SHADERPACK="${SHADERPACK:-/Users/noah/Desktop/Nativium/nativium-research/shaderpacks/ComplementaryUnbound_r5.6.1.zip}"
SMOKE="${SMOKE:-0}"
DATE="$(date +%Y-%m-%d)"
OUT="${OUT:-docs/benchmarks/shootout-$DATE.md}"
REPORT="build/run/clientGameTest/nativium-gametest-report.txt"
LABEL="${LABEL:-}"
LOGDIR="build/shootout-logs${LABEL:+/$LABEL}"
mkdir -p "$LOGDIR" "$(dirname "$OUT")"

ROWS="$(mktemp)"
trap 'rm -f "$ROWS"' EXIT

field() { # field <key> <line>
  printf '%s\n' "$2" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1
}

for scene in $SCENES; do
  for engine in $ENGINES; do
    note=""
    if [ "$scene" = shaders ] && [ ! -f "$SHADERPACK" ]; then
      echo "| $engine | $scene | - | - | - | - | skipped: shader pack not found |" >> "$ROWS"
      continue
    fi
    if [ "$scene" = shaders ] && [ "$engine" = VULKAN ]; then
      echo "| $engine | $scene | n/a | n/a | n/a | n/a | Iris refuses to run on Vulkan |" >> "$ROWS"
      continue
    fi

    echo "=== $engine / $scene ($(date +%H:%M:%S)) ==="
    [ -n "${GPU_LOCK:-}" ] || until ! pgrep -f "[d]evlaunchinjector.Main" >/dev/null; do sleep 5; done
    rm -f "$REPORT"
    args=(--no-daemon :runClientGameTest -PwithSodium -Pbench "-Pengine=$engine" "-PbenchScene=$scene" "-PbenchSeconds=$BENCH_SECONDS")
    [ -n "${RD:-}" ] && args+=("-PbenchRd=$RD")
    [ "${VSYNC:-0}" = 1 ] && args+=(-PbenchVsync)
    if [ "$scene" = shaders ]; then
      args+=(-PwithIris "-Pshaderpack=$SHADERPACK")
    fi
    ${GPU_LOCK:+$GPU_LOCK --exclusive} ./gradlew "${args[@]}" > "$LOGDIR/$engine-$scene.log" 2>&1
    status=$?

    if [ ! -f "$REPORT" ]; then
      echo "| $engine | $scene | - | - | - | - | FAILED: no report (gradle exit $status) |" >> "$ROWS"
      continue
    fi
    cp "$REPORT" "$LOGDIR/$engine-$scene-report.txt"
    result="$(grep '^bench-result:' "$REPORT" | tail -1)"
    backend="$(sed -n 's/^backend: //p' "$REPORT" | head -1 | cut -d'|' -f1,2 | sed 's/ *| */ \/ /')"
    if [ -z "$result" ]; then
      if grep -q '^result: SKIP' "$REPORT"; then note="skipped by the game test"; else note="FAILED: $(grep '^result:' "$REPORT" | tail -1)"; fi
      echo "| $engine | $scene | - | - | - | - | $note |" >> "$ROWS"
      continue
    fi
    frames="$(field frames "$result")"
    heap="$(field heap_mb "$result")"
    pres="$(grep '^bench-present:' "$REPORT" | sed 's/ (Metal.*//; s/^bench-present: //')"
    note="backend: $backend; $frames frames; heap ${heap} MB; $pres"
    [ "$status" -ne 0 ] && note="$note; gradle exit $status"
    printf '| %s | %s | %s | %s | %s / %s / %s | %s | %s |\n' "$engine" "$scene" \
      "$(field avg_fps "$result")" "$(field low1_fps "$result")" \
      "$(field p50_ms "$result")" "$(field p95_ms "$result")" "$(field p99_ms "$result")" \
      "$(field max_ms "$result")" "$note" >> "$ROWS"
  done
done

{
  if [ "$SMOKE" = 1 ]; then
    echo "# Engine shootout $DATE (SMOKE RUN - NOT A MEASUREMENT)"
    echo
    echo "> Smoke run to validate the script ($BENCH_SECONDS s per cell, subset of the matrix). Do not cite or commit."
  else
    echo "# Engine shootout $DATE"
  fi
  echo
  echo "## Machine"
  echo
  echo "- CPU: $(sysctl -n machdep.cpu.brand_string)"
  echo "- macOS: $(sw_vers -productName) $(sw_vers -productVersion) ($(sw_vers -buildVersion))"
  echo "- Display refresh: $(system_profiler SPDisplaysDataType 2>/dev/null | sed -n 's/.*@ *\([0-9.]*\)Hz.*/\1 Hz/p' | paste -sd, -) (VSync is off in the benchmark)"
  echo "- Commit: $(git rev-parse --short HEAD)$(git diff --quiet || echo ' (dirty)')"
  echo "- Seconds per cell: $BENCH_SECONDS; engines: $ENGINES; scenes: $SCENES"
  echo
  echo "## Results"
  echo
  echo "Frame times are per rendered frame (HUD hook), 1% low = 1000 / mean of the slowest 1% of frames in ms."
  echo "Presented/displayed frames are not counted."
  echo
  echo "| Engine | Scene | Avg FPS | 1% low FPS | p50 / p95 / p99 ms | max ms | Notes |"
  echo "|---|---|---|---|---|---|---|"
  cat "$ROWS"
} > "$OUT"
echo "wrote $OUT"
