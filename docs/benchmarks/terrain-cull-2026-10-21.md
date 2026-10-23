# GPU occlusion culling of Sodium terrain on Metal (Phase 3 step 2) — 2026-10-21

M3 Max, macOS 27.0.1, Sodium 0.9.2, Odyssey G5 144 Hz, **2560x1440 fullscreen**, AC power, `gpu-lock.sh --exclusive`,
every run `bench-env: VALID`. Seed `nativium`, spectator camera, VSync off, no FPS cap. Shaders = Iris + Complementary
Reimagined r5.9.3. Per cell one session (`-Pterrain=gpuCull=off -PbenchCullTour -PbenchCullModes=count,off,on,off,on
-PbenchCullSeconds=6`): each view settles, then the culling modes alternate in the same session (same chunks), 6 s each;
off/on numbers are the mean of two windows each. GPU ms = Metal command-buffer GPU time per frame; CPU ms = frame time
minus the wait for a free frame slot (render-thread work). Raw logs: scratchpad `cull/results/ab1-*`.

## How it works (`TerrainOcclusion`, `HiZCuller`, never in Iris's shadow pass, never translucent)

1. Phase 1: Sodium's opaque main-view batches are submitted without the sub-draws of sections that the latest GPU
   result (a few frames old) reported hidden; sections without a result are drawn.
2. Split: after solid + cutout the render encoder ends; compute builds a max-"farness" Hi-Z pyramid from the depth
   attachment (26.3 uses **reversed depth**, `GREATER_EQUAL`; the convention is read from the projection) and tests
   every section box (grown by 1 block, 1.5 with a pack; 2 px screen margin).
3. Phase 2: every deferred sub-draw is encoded as an indexed indirect draw whose instance count the GPU sets to 1 only if
   its section passed the test. So a section that became visible is drawn in the same frame — nothing visible is
   dropped; a hidden section's fragments would have failed the depth test anyway.

Correctness (`./gradlew :runClientGameTest -PwithSodium -PterrainCullTest`): wall, turn-round, turn-back, open, underground
and two "defer everything" stress views (all 651–721 sub-draws drawn only via the GPU-decided phase 2), each rendered
with culling on and off in one session: **pixel-identical** (0.0000% of pixels differ by more than 64; the underground
view differs only by a uniform in-block overlay fade ≤ 33). With Complementary the comparison is not exact: waving leaves
and shadow flicker differ between any two frames (the defer-all views, which cull nothing, differ as much as the culled
ones: 1.5–3.3% of pixels > 64); no missing terrain was visible in the screenshots, but this is not a proof.

## Results

| Cell | View | sections | hidden (count) | sub-draws hidden | indices hidden | fps off | fps on | Δ fps | GPU ms off → on | CPU ms off → on |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| rd16 shaders off | elevated | 904 | 31.1% | 35.0% | 23.7% | 2360 | 2222 | -5.8% | 0.412 → 0.439 | 0.119 → 0.148 |
| rd16 shaders off | ground | 870 | 39.2% | 44.7% | 29.9% | 2694 | 2586 | -4.0% | 0.366 → 0.382 | 0.131 → 0.155 |
| rd16 shaders off | plains | 1005 | 54.4% | 59.1% | 44.5% | 1938 | 1979 | +2.1% | 0.503 → 0.492 | 0.231 → 0.244 |
| rd16 shaders off | forest | 953 | 82.4% | 84.0% | 75.9% | 2097 | 2368 | +12.9% | 0.464 → 0.408 | 0.228 → 0.221 |
| rd16 shaders off | mountains | 1902 | 12.1% | 12.5% | 13.8% | 2997 | 2763 | -7.8% | 0.324 → 0.350 | 0.119 → 0.157 |
| rd32 shaders off | elevated | 3200 | 31.1% | 31.9% | 19.2% | 959 | 998 | +4.0% | 1.034 → 0.992 | 0.284 → 0.363 |
| rd32 shaders off | ground | 3009 | 36.0% | 41.0% | 22.7% | 1041 | 1110 | +6.6% | 0.960 → 0.896 | 0.281 → 0.354 |
| rd32 shaders off | plains | 3329 | 47.4% | 51.9% | 32.9% | 1031 | 1209 | +17.3% | 0.969 → 0.821 | 0.293 → 0.349 |
| rd32 shaders off | forest | 3344 | 87.5% | 89.4% | 80.8% | 1000 | 1768 | +76.9% | 0.998 → 0.556 | 0.304 → 0.312 |
| rd32 shaders off | mountains | 8740 | 34.7% | 36.3% | 38.4% | 763 | 1020 | +33.7% | 1.303 → 0.958 | 0.412 → 0.567 |
| rd16 Complementary | elevated | 902 | 29.7% | 33.1% | 22.7% | 196 | 198 | +1.0% | 5.108 → 5.054 | 0.842 → 1.091 |
| rd16 Complementary | ground | 869 | 38.0% | 43.3% | 28.7% | 215 | 215 | +0.1% | 4.660 → 4.656 | 0.984 → 1.011 |
| rd16 Complementary | plains | 1003 | 52.3% | 57.4% | 42.1% | 264 | 267 | +1.1% | 3.788 → 3.746 | 0.950 → 0.970 |
| rd16 Complementary | forest | 950 | 84.9% | 86.1% | 77.0% | 254 | 275 | +8.4% | 3.939 → 3.632 | 1.012 → 1.001 |
| rd16 Complementary | mountains | 1901 | 10.8% | 11.2% | 12.2% | 248 | 247 | -0.2% | 4.027 → 4.044 | 1.036 → 1.093 |
| rd32 Complementary | elevated | 3202 | 29.6% | 30.4% | 18.4% | 158 | 162 | +2.6% | 6.342 → 6.180 | 1.126 → 1.286 |
| rd32 Complementary | ground | 3011 | 34.6% | 39.5% | 20.9% | 179 | 185 | +3.2% | 5.588 → 5.415 | 1.157 → 1.270 |
| rd32 Complementary | plains | 3331 | 45.2% | 49.6% | 30.9% | 201 | 210 | +4.6% | 4.984 → 4.764 | 1.115 → 1.237 |
| rd32 Complementary | forest | 3346 | 90.4% | 91.8% | 85.1% | 197 | 229 | +16.4% | 5.075 → 4.316 | 1.272 → 1.458 |
| rd32 Complementary | mountains | 8741 | 32.7% | 34.2% | 36.3% | 171 | 188 | +9.7% | 5.844 → 5.327 | 1.296 → 1.501 |

"hidden (count)" = share of the sections Sodium's graph culling kept that are behind this frame's opaque terrain (Hi-Z
of all opaque terrain: best case of the two-phase scheme). The cave view was skipped in this matrix (the position search
found no cave; fixed afterwards, not re-measured). Main bench (culling off, 20 s): rd16 1865 fps, rd32 479 fps,
Complementary rd16 116 fps, rd32 94 fps.

## What it means / decision

- The split + Hi-Z build + test costs ~0.07 ms GPU at 2560x1440 (count mode vs off). Shaders off at rd16 the main-view
  terrain is only ~0.3 ms, so culling loses 4–8% in open views and wins only in forests (+13%).
- rd32 shaders off: faster in every view, +4% (elevated) to +77% (forest), mountains +34%.
- Complementary: rd16 neutral (−0.2..+8%), rd32 +2.6..+16% (GPU −0.16..−0.76 ms per frame).
- CPU cost: +0.03..+0.16 ms per frame (section boxes, filtered submission, phase-2 encode); every cell is GPU-bound.
- **Default (`nativium.json` `terrainOcclusionCulling`, AUTO):** on at effective render distance ≥ 24 without a shader
  pack; off below and with shader packs (the measured rd32 gain with Complementary is real, but exactness with packs is
  not proven by an exact comparison — next step: a deterministic pack check, e.g. freezing `frameTimeCounter`, then
  enable AUTO for packs at rd ≥ 24). `ON` forces it everywhere, `OFF` disables it; `-Dnativium.terrain.gpuCull=
  off|auto|on|count|verify` overrides (count = measure only, verify = full scheme but draw everything).
- **Never while Voxy renders (any mode, 2026-10-23):** Voxy draws its LODs from inside Sodium's cutout pass, and phase 2
  re-runs the opaque layer, so Voxy rendered twice per frame. Result in the Voxy bench scene (rd 32 ingest): the vanilla
  terrain drawn in phase 1 was gone (sky with loose cave sections), and after the drop to rd 4 the near water stayed
  missing although culling was then inactive. `verify` (draw everything) broke the same way, `off` was correct, so it
  was the double render, not a wrong cull decision.
- Known limits: packs that displace terrain vertices by more than 1.5 blocks (world curvature) or that write images from
  gbuffers_terrain would need `OFF`; results are ~3 frames late (only costs culling efficiency while moving).
