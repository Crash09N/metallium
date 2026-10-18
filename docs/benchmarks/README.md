# Engine shootout benchmarks

Compares the Metallium graphics engines (Metal, OpenGL, Vulkan/MoltenVK) on identical scenes. Re-run after every larger
optimisation as a performance regression test.

## Real run

1. Quit everything else that uses the GPU (including your own Minecraft). The script waits for other dev clients, but
   cannot see foreign launchers. Keep the Mac on power, idle, display awake.
2. From the repository root: `tools/engine-shootout.sh` (about 12 cells at 3-5 minutes each, ~45 min).
3. Output: `docs/benchmarks/shootout-<date>.md`. Commit it. Per-cell logs and game-test reports are kept in
   `build/shootout-logs/`.

Overrides: `ENGINES="METAL OPENGL"`, `SCENES="light flight"`, `BENCH_SECONDS=30`, `SHADERPACK=/path/pack.zip`,
`OUT=file.md`. The shader pack defaults to the local Complementary Unbound zip (never commit third-party packs); the
shaders scene is skipped when the file is missing. Iris on Vulkan is recorded as n/a.

`SMOKE=1` marks the output as a smoke run. Smoke results (short `BENCH_SECONDS`, partial matrix) are not measurements: do
not commit them.

## Scenes

Run through `./gradlew :runClientGameTest -PwithSodium -Pbench -Pengine=<E> -PbenchScene=<S>`: fixed seed
`metallium`, spectator camera 30 blocks above the terrain at the origin, VSync off, no FPS cap, chunk fade off.

| Scene | What |
|---|---|
| `light` | Sodium, render distance 12 |
| `rd32` | Sodium, render distance 32 (CPU heavy), longer settle |
| `shaders` | `light` with the shader pack via Iris (`-PwithIris -Pshaderpack=`) |
| `flight` | Spectator flies in a straight line at 60 blocks/s through not-yet-loaded chunks (frame times during chunk loading) |

## Metrics

Every rendered frame is timed with a HUD hook in the game test (one call per rendered frame). Avg FPS = frames /
total time; 1% low = 1000 / mean frame time of the slowest 1% of frames; p50/p95/p99/max are frame-time percentiles.
Rendered frames are counted, frames actually presented to the display are not.
