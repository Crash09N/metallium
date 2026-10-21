# Engine shootout 2026-10-20 (final pre-1.0)

## Conditions

- Apple M3 Max, macOS 27.0.1, AC power, caffeinate, owner away, `gpu-lock.sh --exclusive`. Commit d4c6882.
- Odyssey G5, 2560x1440 @ 144 Hz, **real fullscreen** (`-PbenchDisplay -PbenchFullscreen`). Every row below has
  `bench-env: VALID` (focused, not occluded, framebuffer/drawable/display all 2560x1440, AC).
- 30 s measured per cell, `-PperfCounters`, VSync off (v0) and on (v1). Seed `metallium`, fixed spectator camera.
- Scenes: `light` (RD12, no shaders), `rd16`, `rd32` (no shaders), `reimagined` = Complementary Reimagined r5.9.3 and
  `unbound` = Complementary Unbound r5.6.1 (default settings, Iris), `voxy` (Voxy 0.2.20-beta-247adf2, vanilla RD 4).
- Columns: rendered fps (per-frame HUD hook), shown/s = frames actually presented with a distinct presentedTime
  (Metal only: the counter does not exist for OpenGL/Vulkan, shown as 0), frame time p50 / p99, 1% low fps.
- GPU ms (Metal command-buffer GPU time per submit, mean of the last 10 perf windows; not available for OpenGL/Vulkan):
  see last column.
- Vulkan = vanilla 26.3 Vulkan backend through MoltenVK. Iris refuses Vulkan (n/a); Voxy needs GL 4.6.

## Results, VSync off

| Scene | Engine | rendered fps | shown/s | p50 / p99 ms | 1% low | Metal GPU ms |
|---|---|---|---|---|---|---|
| light | METAL | 2689.6 | 136.7 | 0.36 / 0.78 | 1038.6 | 0.16 |
| light | OPENGL | 683.2 | - | 1.20 / 4.12 | 134.1 | - |
| light | VULKAN | 428.2 | - | 2.04 / 7.07 | 71.6 | - |
| rd16 | METAL | 1931.0 | 136.5 | 0.50 / 1.05 | 843.6 | 0.25 |
| rd16 | OPENGL | 549.8 | - | 1.51 / 5.03 | 107.8 | - |
| rd16 | VULKAN | 449.9 | - | 1.96 / 7.25 | 72.4 | - |
| rd32 | METAL | 716.6 | 134.6 | 1.38 / 2.27 | 370.5 | 0.69 |
| rd32 | OPENGL | 383.1 | - | 2.29 / 8.06 | 78.6 | - |
| rd32 | VULKAN | 460.1 | - | 1.94 / 7.26 | 76.1 | - |
| Reimagined | METAL | 164.5 | 131.8 | 6.05 / 7.96 | 121.1 | 3.03 |
| Reimagined | OPENGL | 33.1 | - | 40.31 / 59.97 | 16.4 | - |
| Reimagined | VULKAN | n/a (Iris refuses Vulkan) | | | | |
| Unbound | METAL | 151.3 | 123.7 | 6.57 / 8.50 | 109.3 | 3.30 |
| Unbound | OPENGL | 107.4 | - | 9.09 / 12.42 | 65.0 | - |
| Unbound | VULKAN | n/a | | | | |
| Voxy | METAL (+metallium-gl) | 661.5 | 135.2 | 1.49 / 2.68 | 339.9 | 0.72 |
| Voxy | OPENGL | cannot run: "Voxy is unsupported on your system" (GL 4.1) | | | | |
| Voxy | VULKAN | n/a | | | | |

## Results, VSync on (144 Hz display)

| Scene | METAL fps (shown/s) | p50 / p99 ms | OPENGL fps | p50 / p99 ms | VULKAN fps | p50 / p99 ms |
|---|---|---|---|---|---|---|
| light | 136.4 (132.2) | 6.94 / 19.07 | 59.9 | 16.62 / 21.82 | 135.4 | 6.92 / 20.03 |
| rd16 | 136.8 (132.7) | 6.93 / 17.42 | 59.9 | 16.58 / 21.53 | 135.7 | 6.92 / 20.08 |
| rd32 | 137.9 (133.7) | 6.92 / 15.87 | 59.8 | 16.51 / 20.21 | 137.6 | 6.93 / 20.63 |
| Reimagined | 138.3 (134.0) | 6.93 / 15.84 | 59.9 | 16.63 / 19.79 | n/a | |
| Unbound | 138.8 (134.6) | 6.93 / 15.96 | 59.4 | 16.64 / 21.77 | n/a | |
| Voxy | 137.9 (133.7) | 6.93 / 21.00 | cannot run | | n/a | |

## Conclusions

- VSync off, Metal renders 3.9x (light), 3.5x (rd16), 1.9x (rd32) the fps of OpenGL and 6.3x / 4.3x / 1.6x of Vulkan
  (MoltenVK). With shaders, Reimagined: Metal 164.5 vs OpenGL 33.1 (5.0x, p99 8 ms vs 60 ms); Unbound: 151.3 vs 107.4 (1.4x).
- Metal shows 124-137 of the 144 refreshes per second with VSync off (no tearing, one present per refresh); with VSync on
  it holds ~136-139 fps / 132-135 shown, equal to Vulkan and 2.3x the 60 fps OpenGL gets in this setup.
- Vulkan is flat (430-460 fps) across light/rd16/rd32: bound by per-frame overhead in the MoltenVK path, not terrain.
- Voxy runs only on Metal (metallium-gl routes its GL 4.6 calls); real OpenGL on macOS (GL 4.1) cannot run it.
- **Intermittent slow run (Voxy + VSync on):** one Metal run measured 23.7 fps (p50 48.8 ms, all frames presented, VALID
  env); an identical re-run gave 137.9 fps. Same 50 ms pattern as the 19 fps windowed case in STATUS. Resolved 2026-10-21: a game-test lock-step artifact (Fabric's client/server tick barrier after a "Can't keep up" server schedule restart), fixed in the bench harness (STATUS, `bench-lockstep:`); the other rows were measured in the normal state (light fix-on rerun 2691 fps). Was: unresolved flake
  (kept: `METAL-voxy-v1-try1`). Not reproduced in any other cell.
- Harness finding: the game test bench with `-PwithVoxy` does not load the nested `metallium-voxy-natives` addon, so Voxy
  stays disabled ("no macOS natives") and the voxy scene silently measures vanilla RD 4. The numbers above were taken
  with `-PextraMods=<addon jar>`. (The release-jar check in docs/compat covers the production path.)
- Not measured: GPU ms for OpenGL/Vulkan, shown/s for OpenGL/Vulkan (no present counters for those engines).
