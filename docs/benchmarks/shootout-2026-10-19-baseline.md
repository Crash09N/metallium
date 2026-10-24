# Engine shootout 2026-10-19 - BASELINE (before the encoder optimisation)

Baseline measured on `claude/lucid-heisenberg-f4n1hp` (now `main`) @ 8c5c9ea (+ bench tooling commit: GPU_LOCK hook, `-PbenchRd`). Label all later
comparisons against this file. Produced by `tools/engine-shootout.sh` (one invocation per block, 30 s per cell, each run under
`gpu-lock.sh --exclusive`, so no other Minecraft client ran during a cell).

## Machine / conditions

- Apple M3 Max, macOS 27.0.1, display 60 Hz (VSync off except the VSync block), Sodium always loaded; Iris loaded for the shaders scene.
- Frame times are per *rendered* frame (HUD hook). "Metal presented FPS" is the Metal surface counter of frames that really reached the display.
- Noise: other agents were compiling/testing in parallel the whole time (sampled every 20 s in the first ~70 min: load average 5-10, 2 Gradle daemons, 4 java
  processes alive during all benchmark blocks). `--exclusive` only excludes other game clients, not Gradle/javac CPU load. Not repeated: the same Metal/OpenGL
  scenes were measured independently several times (light RD12/8/16, rd32) and agree within about 3 % (Metal 4150-4200, OpenGL 911-946), so CPU noise did not visibly move the numbers.
  The Vulkan rows are low enough that noise is irrelevant for the ranking.

## Block A: light / rd32 / flight

| Engine | Scene | Avg FPS (rendered) | 1% low FPS | p50 / p95 / p99 ms | max ms | Metal presented FPS | frames |
|---|---|---|---|---|---|---|---|
| METAL | light | 4192.1 | 1049.5 | 0.21 / 0.38 / 0.74 | 41.66 | 160.7 | 125762 |
| OPENGL | light | 945.4 | 138.4 | 0.86 / 2.33 / 4.29 | 38.92 | n/a | 28362 |
| VULKAN | light | 76.8 | 26.3 | 16.40 / 17.82 / 32.40 | 94.13 | n/a | 2307 |
| METAL | rd32 | 4084.7 | 1177.0 | 0.22 / 0.35 / 0.63 | 44.71 | 181.3 | 122543 |
| OPENGL | rd32 | 927.3 | 146.3 | 0.88 / 2.33 / 4.23 | 47.36 | n/a | 27820 |
| VULKAN | rd32 | 102.4 | 26.1 | 8.69 / 17.79 / 33.13 | 90.35 | n/a | 3075 |
| METAL | flight | 4488.3 | 978.8 | 0.21 / 0.29 / 0.72 | 16.02 | 186.1 | 134648 |
| OPENGL | flight | 929.5 | 151.6 | 0.86 / 2.36 / 4.16 | 16.62 | n/a | 27885 |
| VULKAN | flight | 107.0 | 29.2 | 4.33 / 17.93 / 27.07 | 46.57 | n/a | 3211 |

## Block B: shaders scene (Sodium + Iris, shader pack active)

| Engine | Scene | Avg FPS (rendered) | 1% low FPS | p50 / p95 / p99 ms | max ms | Metal presented FPS | frames |
|---|---|---|---|---|---|---|---|
| METAL | shaders (Complementary Unbound r5.6.1) | 449.3 | 224.8 | 2.26 / 2.91 / 3.28 | 124.77 | 98.1 | 13500 |
| OPENGL | shaders (Complementary Unbound r5.6.1) | 272.2 | 72.2 | 2.89 / 7.49 / 11.43 | 109.72 | n/a | 8177 |
| METAL | shaders (Complementary Reimagined r5.9.3) | 459.8 | 217.4 | 2.25 / 2.91 / 3.26 | 119.55 | 102.6 | 13815 |
| OPENGL | shaders (Complementary Reimagined r5.9.3) | 323.0 | 98.8 | 2.78 / 5.07 / 6.51 | 101.70 | n/a | 9702 |

Vulkan: Iris refuses to run on Vulkan, not measured.

## Block C: render distance 8 / 16 / 32 (light scene, `-PbenchRd`; RD12 = block A `light`, RD32 = block A `rd32`)

| Engine | Scene | Avg FPS (rendered) | 1% low FPS | p50 / p95 / p99 ms | max ms | Metal presented FPS | frames |
|---|---|---|---|---|---|---|---|
| METAL | light RD8 | 4199.9 | 1159.4 | 0.22 / 0.36 / 0.63 | 44.57 | 179.3 | 125998 |
| OPENGL | light RD8 | 925.0 | 153.1 | 0.87 / 2.44 / 4.47 | 41.70 | n/a | 27749 |
| METAL | light RD16 | 4148.9 | 1141.1 | 0.22 / 0.38 / 0.65 | 44.43 | 186.2 | 124466 |
| OPENGL | light RD16 | 911.2 | 147.2 | 0.89 / 2.37 / 4.33 | 47.39 | n/a | 27337 |

## Block D: VSync on (light scene, `-PbenchVsync`)

| Engine | Scene | Avg FPS (rendered) | 1% low FPS | p50 / p95 / p99 ms | max ms | Metal presented FPS | frames |
|---|---|---|---|---|---|---|---|
| METAL | light VSync on | 59.9 | 35.5 | 16.59 / 19.67 / 21.90 | 99.38 | 59.9 | 1797 |
| OPENGL | light VSync on | 59.8 | 35.8 | 16.65 / 19.21 / 20.71 | 91.19 | n/a | 1795 |
| VULKAN | light VSync on | 59.8 | 30.7 | 16.56 / 19.77 / 23.06 | 97.90 | n/a | 1795 |

All three engines are locked to 60 fps: Metal rendered 60.0 = submitted 60.0 = presented 59.9 (no dropped frames). With VSync on Metal's frame-time p99 is 21.9 ms vs 20.7 (OpenGL) and 23.1 (Vulkan).

## Findings

1. **Metal rendered FPS (about 4100-4500) is not a displayed frame rate.** With VSync off the Metal surface presents only 98-186 frames per second
   ("submitted_fps" equals "presented_fps", e.g. 160.7 of 4193 rendered in `light`; `skipped` about 96 % of frames). The HUD-hook frame time (p50 0.21 ms) is the CPU
   time to build and submit a frame, with no GPU back-pressure at the frame boundary. OpenGL (about 930 fps) and Vulkan (77-107 fps) are synchronous per frame, so the
   raw "Avg FPS" column is not comparable across engines. The honest Metal-vs-OpenGL comparison with VSync off is presented FPS: Metal 160-190 (light/rd32/flight),
   98-103 (shaders) against OpenGL 930 / 272-323 (shaders). In other words Metal currently displays far fewer frames than OpenGL renders when uncapped, even though its CPU frame is 4x cheaper. This is the number the
   encoder optimisation should be judged on (together with the real GPU frame time, which this harness does not measure).
2. Shaders (Complementary): Metal rendered 449-460 fps vs OpenGL 272-323 (1.4-1.65x) but presented only 98-103.
3. Vulkan (MoltenVK) is 8-12x slower than OpenGL (77-107 fps, p99 27-33 ms). It is not competitive.
4. **Render distance has no measurable effect, and probably is not applied to what is loaded.** RD8/12/16/32 give the same fps on both engines and the same sodium line
   (`C: 92/2616 (F)`, 2616 loaded sections in every run; only `D:` changes). The scene is also mostly sky/near terrain (terrain coverage 0.19). So the "rd32" scene is
   not a heavier scene than `light` in this harness; the RD numbers above do not show RD scaling. Needs a benchmark fix (wait for the chunks of the new RD to load, or a camera that sees more terrain) before RD scaling can be claimed.
5. The `flight` scene sees the same 90/2616 sections as the static ones, so chunk loading during flight is not stressing the renderer either.

Raw per-cell reports and logs: `build/shootout-logs/base-*/` in the worktree (not committed); sweep data in `/Users/noah/Desktop/Nativium/nativium-research/sweep-results/2026-10-19-baseline/`.
