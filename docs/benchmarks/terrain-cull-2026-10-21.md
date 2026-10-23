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
- **Default (`nativium.json` `terrainOcclusionCulling`, AUTO):** on at effective render distance ≥ 24, since
  2026-10-23 also with shader packs (deterministic check below); off below rd 24 and while Voxy is loaded. `ON` forces
  it, `OFF` disables it; `-Dnativium.terrain.gpuCull=off|auto|on|count|verify` overrides (count = measure only, verify = full scheme but draw everything).
- Known limits: packs that displace terrain vertices by more than 1.5 blocks (world curvature) or that write images from
  gbuffers_terrain would need `OFF` (AUTO detects the first case by option name, see below); results are ~3 frames late
  (only costs culling efficiency while moving).

## Shader-pack exactness check (2026-10-23)

`./gradlew :runClientGameTest -PwithIris -PterrainCullTest -Pshaderpack=<zip> -PsysProps=nativium.gametest.cullRd=24`
(854x480 game-test window; add `-PwithDH`, or `-PwithSodium -PwithGl -PwithVoxy=<jar>`, for the LOD mods).

- **Determinism:** with a pack the test freezes Iris's frame clock (`ShaderClock`, nativium-iris
  `IrisFrameCounterMixin`/`IrisFrameTimerMixin`): `frameCounter` and `frameTimeCounter` stand still (waving plants,
  clouds, TAA jitter, dithered shadows keep one phase) and `frameTime` is a fixed 2 s, so smoothed uniforms
  (`eyeBrightnessSmooth`) settle in a few frames. World time, weather and game ticks are frozen as before. On and off
  go through identical steps (teleport, settle 60 ticks, turn, screenshot).
- **Noise floor:** two `baseline` views compare off with off through the same steps. **Negative control:**
  `control-drop` defers everything and lets phase 2 draw nothing; the comparison must report it. Beside the pixel
  counts the test reports the largest mean difference of an 8x8 tile above the image-wide mean (a missing block covers
  whole tiles; noise is scattered, a fade is uniform) and, with a pack, fails above 32. "tile" below = that value
  (BSL/Photon/DH rows measured with an earlier control that dropped every 4th batch and a tile value without the mean
  subtracted).

| Pack (rd 24) | off vs off (still / turn) | on vs off, views without a turn (wall, open, 2x defer-all) | on vs off, turn views (behind, turnback, under) | negative control |
|---|---|---|---|---|
| Complementary Reimagined r5.9.3 | identical / max 26, tile 1.0 | **pixel-identical** (also at rd 12) | max diff ≤ 17, 0 pixels > 24 | detected (20% pixels > 64, tile 176) |
| BSL 10.1.8 | max 6 / max 4, tiles ≤ 0.3 | max 10, tiles ≤ 0.2 | max ≤ 4, tiles ≤ 0.2 | detected (20% pixels > 64, tile 163) |
| Photon 1.3b | max 110, 0.016% > 64, tile 7.5 / tile 10.9 | 0.006% > 64, tiles ≤ 8.1 | 0.0007% > 64, tiles ≤ 18.9 (sky) | detected (18% > 64, tile 169) |
| Reimagined + Distant Horizons (after the fix below) | max 37, tile 1.2 / tile 6.4 | max 15, 0 pixels > 24, tiles ≤ 1.4 | max 45, 0 pixels > 64, tiles ≤ 7.8 | detected (21% > 64, tile 200) |
| Reimagined + Voxy (culling off, see below) | identical / max 26 | identical | max ≤ 14, 0 pixels > 24 | — (nothing culled) |
| no pack, Sodium only | — | identical except the vanilla vignette fading after a teleport (radial, ≤ 38, 0 pixels > 64) | identical; under: vignette ≤ 54 | detected (14% > 64) |

Reading: Reimagined is exact wherever its temporal state is equal (still views); after a turn its TAA history depends
on how many frames passed since the turn, which varies by one or two frames, and off-vs-off differs as much. BSL is
nearly static. Photon is not deterministic even with a frozen clock (its sky/clouds flicker frame to frame, see
`baseline`); its on-vs-off differences are no larger than off-vs-off and than the defer-all views, which cull
nothing. Screenshots inspected: no missing blocks in any view; the differing pixels are scattered (sky, TAA edges).
Shadows: the wall view keeps the shadows of terrain behind the wall pixel for pixel (Reimagined), so Iris's shadow
pass is unaffected.

**Bug found and fixed (Distant Horizons):** when phase 1 drew nothing (every sub-draw deferred), the split read the
depth attachment of whatever render encoder was open — with DH under a pack that is DH's own `dhDepthTex`, and almost
all Sodium terrain was culled (defer-all views: 15–40% of pixels wrong, LODs showing through). Now the deferred draws
are drawn unconditionally in that case; the defer-all test keeps the first batch direct, so it still exercises the
GPU decisions. After the fix DH is at noise level (table). (Game-test note: with DH loaded the test client did not
shut down after the scenario — render thread waiting for the integrated server's halt, server thread parked in
Fabric's game-test phaser; not related to culling, the report was complete.)

**Bug found and fixed (Voxy):** Voxy draws its LODs from inside Sodium's opaque layer (its `DefaultChunkRenderer` mixin,
cutout pass). With culling on, the split saw Voxy's encoder and phase 2 (the layer run a second time) ran Voxy again,
and Sodium's terrain disappeared from the image — with and without a shader pack, so AUTO at rd ≥ 24 without a pack
was affected too. Culling is now never used while Voxy is loaded (also not with `ON`); the test checks that.

**Vertex displacement:** the section boxes grow by 1.5 blocks with a pack. Complementary waves leaves/plants by at
most ~0.07 blocks at its default intensity (0.14 at 2.0, but 3.2 at the joke value 50.0); BSL's and Photon's waving
looked of the same order in the screenshots (not measured exactly); BSL's `WORLD_CURVATURE` (off by default) bends the world by many blocks. AUTO therefore stays
off for a pack whose enabled boolean options match `CURVATURE|CURVED|PLANET|WORLD_BEND` or whose waving intensity
option (`*WAV*_I`, `*WAV*INTENSITY|STRENGTH|AMPLITUDE`) is ≥ 10 (`IrisPackState.displacesTerrain`, logged once).

**Decision:** AUTO now culls with shader packs at rd ≥ 24 (the test checks that AUTO culls at rd 24 with a pack);
never while Voxy is loaded.
Not covered: packs that write images/SSBOs from `gbuffers_terrain` (a culled section would not write them — none of
the tested packs needs this for visible output) and terrain displacement under other option names (`ON`/`OFF` per
user).
