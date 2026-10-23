#!/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Crash09N – NPR Media
#
# Compares the conformance screenshots of two game-test runs (./gradlew :runClientGameTest -PconformanceTest, once on
# Metal and once with -PbackendOff for OpenGL) shot by shot with ImageMagick.
#
# Usage: tools/conformance-compare.sh <metal-screenshot-dir> <opengl-screenshot-dir> <out-dir>
#
# For each conformance-*.png (the game test's 4-digit counter prefix is ignored) it prints a Markdown table row:
#   shot | % pixels differing by more than 10% | % pixels differing by more than 25% | diff image
# and writes <out-dir>/<shot>-diff.png (differing pixels red over a faded Metal shot) and
# <out-dir>/<shot>-side.png (Metal | OpenGL | absolute difference x4).
set -euo pipefail
a=$1; b=$2; out=$3
mkdir -p "$out"
echo "| shot | >10% | >25% |"
echo "|---|---:|---:|"
for fa in "$a"/*conformance-*.png; do
  name=$(basename "$fa" .png); name=${name#[0-9][0-9][0-9][0-9]_}
  fb=$(ls "$b"/*_"$name".png 2>/dev/null | head -1 || true)
  if [ -z "$fb" ]; then echo "| $name | missing in $b | |"; continue; fi
  total=$(magick identify -format '%[fx:w*h]' "$fa")
  ae10=$(magick compare -metric AE -fuzz 10% "$fa" "$fb" "$out/$name-diff.png" 2>&1 >/dev/null | awk '{print $1}' || true)
  ae25=$(magick compare -metric AE -fuzz 25% "$fa" "$fb" null: 2>&1 >/dev/null | awk '{print $1}' || true)
  magick "$fa" "$fb" \( "$fa" "$fb" -alpha off -compose difference -composite -evaluate multiply 4 \) -alpha off +append "$out/$name-side.png"
  printf "| %s | %.2f%% | %.2f%% |\n" "$name" "$(echo "$ae10 * 100 / $total" | bc -l)" "$(echo "$ae25 * 100 / $total" | bc -l)"
done
