# Metallium status

## 2026-10-18 (first implementation night)

Verified on GitHub's macOS arm64 runners (virtual M1, "Apple Paravirtual device", macOS 15.7.9) by the CI game test:

| Item | State |
|---|---|
| Metal backend (renderpearl SPI, from Metallum) | Boots Minecraft 26.3; title screen, flat and natural worlds render correctly (screenshots in branch `ci-artifacts-vanilla`) |
| Backend selection | Metal first only for "Default" on Apple Silicon; OpenGL/Vulkan fallbacks kept; Iris without adapter → OpenGL |
| Shader-pack translator (`dev.metallium.shaders.GlslProgramTranslator`) | Iris-style GLSL → SPIR-V (glslang, Vulkan-relaxed) → MSL with uniform-block and binding reflection; unit tests pass, MSL compiles with the Metal compiler on macOS |
| Sodium on Metal | Loads, meshes and uploads, but **terrain renders garbled** on the virtual GPU — see `TESTING-ON-MAC.md` |
| Distant Horizons 3.3.4 (M3 Max, `-PwithDH`) | Works on Metal: DH picks its renderpearl ("Blaze3D: VULKAN" via our mixin) renderer, far LODs render beyond vanilla RD 5 (screenshot checked), no ClassCastException/GlTexture errors. Known: closing a world with DH loaded deadlocks the Fabric client-gametest threading, so the DH test halts the JVM after writing its report |
| Voxy (locally built branch 263, `-PwithVoxy=<jar>`) | Default: disabled on Metal (Metallium cancels `VoxyClient.initVoxyClient`; game starts, one chat message). **With `-Dmetallium.gl.mods=voxy` Voxy runs unmodified through metallium-gl and renders its LOD terrain** (game test `-PwithSodium -PwithGl -PwithVoxy=<jar> -PglMods=voxy`: at render distance 4 the ingested 16-chunk terrain is drawn by Voxy, 0 unimplemented GL functions; see `docs/design/metallium-gl.md` §12.4). Iris + Voxy untested. |
| Sodium F3 label | "Terrain Rendering: metal_multidraw (sodium, via Metallium)" on Metal (verified by screenshot + programmatic check) |
| Iris adapter stage 1 (`metallium-iris`) | Game starts on Metal with Sodium + Iris + adapter (no crash) |
| Iris adapter stage 2 (M3 Max, local game test) | Shader packs' begin/prepare/deferred/composite/final passes run on Metal over the vanilla/Sodium scene (`docs/design/iris-stage2-post-pipeline.md`). Test pack: effect verified by pixel check. Complementary Unbound r5.6.1: all 8 fullscreen programs + final translate and run (clouds/tonemap visible); its 20 gbuffers/shadow programs are reported as not executed yet. Shaders off afterwards renders exactly like before |
| Iris adapter stage 3 (M3 Max, local game test) | The packs' gbuffers programs render the world into their colortex buffers on Metal (`docs/design/iris-stage3-gbuffers.md`): Sodium terrain solid/cutout/translucent, sky (basic/textured, stars, sunrise), entities, particles, lines; deferred passes between opaque and translucent geometry, composite/final afterwards. Gbuffers test pack (`gbuffers_terrain` marker in colortex1 -> composite): pixel check passes (terrain band 1.00 marked, sky 0.00). Complementary Unbound r5.6.1: `gbuffers_terrain`, `_water`, `_skybasic`, `_skytextured`, `_entities`, `_textured`, `_line` translate and run, plus `gbuffers_hand` (hand drawn inside the level with Iris's extended vertex formats, as on GL; attachments colortex0,3,4,6); not yet: DH programs |
| Iris adapter stage 4 (M3 Max, local game test) | The packs' `shadow` programs render the shadow maps on Metal (`docs/design/iris-stage4-shadows.md`): Iris's shadow pass (adapted `ShadowRenderer`: sun-view matrices, shadow frustum culling, Sodium shadow render lists, terrain/entities/block entities/translucents) into shadowtex0/1 (D32F, `shadowMapResolution`) and shadowcolorN; gbuffers/composite programs sample them, `sampler2DShadow` through a new Metal depth-compare sampler; prepare passes after the shadow pass. Shadow test pack (floating platform at noon, composite marks shadowed pixels): shadowed 1.00 under the platform, 0.00 without it (identical numbers on Iris/OpenGL). Complementary Unbound r5.6.1: shadow 2048 px; tree canopy, trunk and wall shadows in a flat-world morning scene match Iris on OpenGL (`-Pengine=OPENGL`) screenshot for screenshot. Not yet: mipmapped shadow maps, DH shadows (shadowcomp: stage 5) |
| Iris adapter stage 5 (M3 Max, local game test) | Compute shaders, image load/store and SSBOs on Metal - features macOS OpenGL 4.1 never had (`docs/design/iris-stage5-compute.md`): compute passes of every Iris stage (setup, shadow, shadowcomp, begin/prepare/deferred/composite incl. compute-only passes, final; `workGroups`/`workGroupsRender`/indirect) as Metal compute encoders inside Metallium's frame; colorimg/shadowcolorimg/custom 1D/2D/3D images with atomics; persistent SSBOs with `.length()`; images, SSBOs and 3D custom samplers in gbuffers/shadow/fullscreen programs; shadowcomp fragment passes; mipmapped buffers. Compute test pack (setup -> SSBO, colorimg writes, image atomics, compute-only pass, fragment SSBO/3D reads and image stores, mip chain): all checks 1.00. Complementary Unbound with `COLORED_LIGHTING=128` (needs `-PirisHideMacOs`, the pack forces it off on `MC_OS_MAC`): voxelization in `shadow.vsh` + floodfill `shadowcomp.csh` run, soul-lantern light is teal at night; all programs translate. BSL (`MULTICOLORED_BLOCKLIGHT`): all programs translate and run. Follow-up 2026-10-18: custom textures (PNG/raw 1D/2D/3D, `texture.noise`), `size.buffer.*`, >16 textures per pass (constexpr samplers), 8 pack gbuffer buffers without colortex0, built-in redefinitions (`tanh`), SSBOs in `#version 330`; `MC_OS_MAC` hidden on Metal by default. Photon v1.3b and Bliss v2.1.2 render (Bliss night sky/rain still differ from OpenGL); Unbound colored lighting with the owner's settings runs. No OpenGL comparison possible: macOS GL has no compute. |
| Iris adapter stage 3 (M3 Max, local game test) | The packs' gbuffers programs render the world into their colortex buffers on Metal (`docs/design/iris-stage3-gbuffers.md`): Sodium terrain solid/cutout/translucent, sky (basic/textured, stars, sunrise), entities, particles, lines; deferred passes between opaque and translucent geometry, composite/final afterwards. Gbuffers test pack (`gbuffers_terrain` marker in colortex1 -> composite): pixel check passes (terrain band 1.00 marked, sky 0.00). Complementary Unbound r5.6.1: `gbuffers_terrain`, `_water`, `_skybasic`, `_skytextured`, `_entities`, `_textured`, `_line` translate and run, plus `gbuffers_hand` (hand drawn inside the level with Iris's extended vertex formats, as on GL; attachments colortex0,3,4,6); not yet: shadow pass, DH programs |
| metallium-gl foundation (M3 Max, `-PwithGl`) | Raw OpenGL calls of opted-in mods are retargeted at class load and rendered on Metal into Metallium's frame (`docs/design/metallium-gl.md`). Game test: a raw-GL test mod (GLSL 330 program, VAO/VBO/EBO, texture, FBO + glClear) draws after level rendering; pixels match on Metal; with `-PbackendOff` the same calls run on real OpenGL. 23+ unit tests incl. Metal readback. Not yet: compute/SSBO/indirect (Voxy) |
| Compatibility center (M3 Max, local game tests) | Start-up mod scan finds the gametest's raw-GL class (`RawGlQuads`, 16 GL functions) and, with `-PwithGl`, routes it through metallium-gl **without any opt-in** (pixels still match on Metal). Status screen, Sodium "Metallium" tab (General/Power) and vanilla Video Settings section verified by screenshots. `-PcrashRecovery` (seeded crash report naming the routed mod): game starts on OpenGL, recovery screen before the title, "Use OpenGL for this session" continues to the title screen. Power: Low Power caps live to 30 fps (bench light scene, 10 s each: Performance 269 fps, Low Power 30.0 fps). Vanilla, Sodium, Iris and metallium-gl game tests pass |
| CI | Linux build + unit tests; macOS: Metal smoke test, translator tests compiled with Metal, game tests (vanilla / Sodium / Iris) with screenshot-based terrain check; results pushed to `ci-artifacts-*` branches |

Full plan: `docs/ROADMAP.md`.

### Backend performance (Roadmap phase 2), M3 Max, 2026-10-18

Bench: `./gradlew :runClientGameTest -PwithSodium -Pbench` (seed `metallium`, fixed spectator camera, RD16, VSync off,
854×480 window). Add `-PperfCounters` for per-submit backend counters (encoders, blits by kind, draws, submit wait, GPU time).

| | avg fps | frame p50 |
|---|---|---|
| Metal before (vsync-locked) | 119.7 | 8.01 ms |
| Metal after, window visible | 3507–3914 | 0.07 ms |
| Metal after, screen locked / window occluded | 277 (was 160 with the blocking acquire) | 0.47 ms |
| OpenGL, same scene (coordinator) | ~1017 | 0.21 ms |

- Cause of the cap: with VSync off the frontend picks MAILBOX (`displaySyncEnabled=false`), but a windowed CAMetalLayer
  only returns drawables at the display refresh, so the blocking `nextDrawable()` capped Metal at 120 fps (ProMotion).
  Fix: with VSync off, present only when the previous present reached the display and a drawable acquired by a helper
  thread is ready; otherwise skip the drawable blit (the frame is still rendered) — mailbox-like frame dropping.
- Per frame the bench scene needs only ~1 render encoder, 0.5 blit encoders (a `copyToBuffer`), ~9 draw calls and
  0.04 ms of GPU time, so the encoder/fence/upload items of phase 2 (global fence, blit batching, load/store actions)
  have nothing measurable to gain here; they need a heavier scene (Iris passes, larger window) to be evaluated.
- Tried and dropped (no measurable gain): polling `MTLCommandBuffer.status` instead of completed-handler upcalls;
  4 instead of 3 submits in flight.

Next steps:
1. Owner test on M3 Max (`docs/TESTING-ON-MAC.md`): decides whether the Sodium bug is backend or virtual-GPU specific.
2. Fix Sodium terrain on Metal.
3. Iris adapter stage 5 gaps: Bliss night sky and rain differ from OpenGL (see the stage-5 note; custom textures, `size.buffer.*`, >16 textures, >8 gbuffer outputs and hiding `MC_OS_MAC` are done); stage 4 gaps: mipmapped shadow maps (see the stage-4 design note); stage-3 gaps: custom textures/PBR atlases, programs with more than 16 textures
   (see the stage-3 design note); stage-2 gaps: mipmapped buffers, viewport
   scale, blend overrides in composite passes, custom textures (see the stage-2 design note).
4. Benchmark baseline: vanilla 26.3's own Vulkan backend on macOS through MoltenVK (and Mesa KosmicKrisp if available),
   next to OpenGL and Metallium, same world/camera path (see report §L.4). Comparison only; it cannot run Iris.
5. Later stage, geometry/tessellation shaders for packs: study Mesa's compute-based geometry/tessellation emulation for
   Apple GPUs (Asahi/Honeykrisp; Mesa is MIT, credit in README if code is adapted) as the route to packs that need `.gsh`.
   Rejected: Zink (OpenGL→Vulkan) + MoltenVK under unmodified Iris — three translation layers, missing Vulkan features
   on MoltenVK, expected slower than Apple's OpenGL.

### Shader / pipeline cache (2026-10-18)

Owner's Metal HUD flagged "Shader Compiler Activity" (178 pipeline states, 283 shaders compiled at runtime). Findings:
the MSL front-end compile (`newLibraryWithSource`, ~40–55 ms per library) is the cost; macOS keeps its own per-app
cache (`$DARWIN_USER_CACHE_DIR/net.java.openjdk.java/com.apple.metal`), shared by every Java app and evicted often
(cold in 2 of 4 runs here), so we cannot rely on it. Implemented (`dev.metallium.shadercache`, `MetalPipelineCache`):

- `<game dir>/.metallium-cache/env-<hash>/` (versioned by Metallium, Minecraft, macOS, GPU, MSL, LWJGL and a hash of
  the translator classes; stale environments deleted). Entries: magic + format + length + CRC32, atomic writes, LRU size
  cap 1 GiB. `-Dmetallium.shaderCache=false` disables, `.maxMB=`, `.dir=` override.
- SPIR-V→MSL (SPIRV-Cross) and GLSL→SPIR-V (shaderc, Iris adapter / metallium-gl) results cached by content hash.
- Metal binary archive `pipelines.mtlarchive`: pipeline states are looked up with `FailOnBinaryArchiveMiss`, misses
  added and the archive re-serialized (every 20 s / on close). The archive file is also a metallib: it is opened as an
  `MTLLibrary`, and functions found there skip the MSL compile entirely (entry points get unique content-derived names
  `mtl_vs_/mtl_fs_<key>` so all stages share one namespace). Crash marker discards an archive that was in use when the
  game died; a pipeline that fails with archive functions is rebuilt from source and the archive dropped.
- Attachment-layout variants built lazily on the render thread are recorded and built on the compile thread next time.
- `compilePipeline` now builds the pipeline state on renderpearl's worker thread (`Pending` only returns it).

Measured (M3 Max, `-PwithIris -Pshaderpack=ComplementaryUnbound_r5.6.1 -PplaySeconds=60`, exclusive GPU lock, no other
client running; numbers from the game-test report lines `timing:` / `shader work`):

| | startup→title | first world join | Complementary load (reload + 1st frame) | MSL compiles until title | render-thread PSOs |
|---|---|---|---|---|---|
| cache off, system Metal cache cold | 10.0 s | 4.24 s | 1965 ms | 215 (11.6 s CPU on workers) + 197 PSOs (3.4 s) | 1 at join, 9 at pack load |
| cache cold, system cache warm | 9.0 s | 3.79 s | 956 ms | 215 (43 ms) | 1 / 9 |
| cache warm (2 runs) | 8.9 / 9.1 s | 3.88 / 3.82 s | 520 / 551 ms | **0** (215 functions from the archive, 198/198 archive hits) | **0 / 0** |

Runtime compiles in the first 60 s of play: 0 in every configuration (vanilla pipelines are all compiled during
startup; the stutter sources were the join variant and shader pack reloads, both 0 when warm). The Iris adapter still
builds a pack's programs synchronously on the render thread; when warm they need no shader compile at all (0 shaderc,
0 SPIRV-Cross, 0 MSL compiles, 20/20 archive hits). Binary archive after these runs: ~9 MB.

### Note on VSync-off frame rates (2026-10-18)
With VSync off, Metallium uses mailbox semantics: every frame is rendered and submitted, but a frame is only handed to
the display once the previous one was shown (CAMetalLayer returns drawables at display refresh). FPS counters therefore
count **rendered** frames, not displayed frames — the same as Vulkan MAILBOX, and comparable to macOS OpenGL with
swap interval 0 (the compositor also shows at most one frame per refresh). Benchmark comparisons must say this.

**Open bug (owner, M3 Max, external 144 Hz fullscreen, Complementary via Iris):** VSync off renders ~200 fps but the
Metal Performance HUD shows only ~70 displayed fps; VSync on shows 144 correctly. Investigation 2026-10-18 (not fixed):
- The bench report now has a `bench-present:` line: display refresh (SDL), rendered fps, submitted presents/s,
  presented/s (drawable presented handler with `presentedTime > 0`), dropped presents (`presentedTime == 0`),
  skipped frames and the window state (visible/occluded/focused, sampled every second). `-PbenchVsync` runs the
  bench with VSync on. Counters live in `dev.metallium.backend.PresentStats`.
- Allowing more than one present in flight (instead of waiting for the presented handler) makes things worse. Light
  bench, exclusive GPU, window visible, 60 Hz E2211: 1 in flight → 3918 rendered / 179 shown per s; 2 → 3427 / 83
  (53 dropped/s); 3 → 963 / 74; 100 → 381 / 63. The layer drops superseded presents and rendering stalls. Kept at 1.
- With the unchanged 1-in-flight logic, presented/s varies from 21 to 179 between runs on the same machine (window
  visible but unfocused; WindowServer load from a video call during testing). `presentedTime > 0` at 179/s on a 60 Hz
  display means it marks hand-off to the compositor, not scan-out, so this counter is not the HUD's "displayed fps".
- VSync on is unchanged: 60.1 rendered / 60.0 presented on the 60 Hz test display.
- Likely mechanism for the owner's ~70 (not yet measured with a pack): a new present is only possible at the end of the
  first rendered frame after the previous present's handler; with ~5 ms GPU frames that is ~3 rendered frames per
  present (~67/s). Next step: measure on the owner's setup with the Metal HUD (`MTL_HUD_ENABLED=1`) and the `shaders`
  bench scene, then try deciding the present when the previous present's command buffer completes (not its presented
  handler), or a separate small present command buffer so the decision doesn't wait for frame end.
- Do not release a presented drawable from Java before its presented handler has run: Quartz then calls the handler
  synchronously inside the (critical) `release` downcall and the JVM aborts ("wrong thread state for upcall").
- 2026-10-19 follow-up: `-PpresentPacing=gpu` (`-Dmetallium.present.pacing=gpu`, default stays `handler`) presents
  when the GPU finished the previous present and half a refresh period passed; the drawable blit + present go into a
  small command buffer of their own after the frame's (the frame is copied first, so no frame waits for the display).
  While the screen is locked every client is throttled by macOS (Metal either pacing: 59.6 rendered, all presents
  dropped; OpenGL fullscreen ~22 fps) — such runs are invalid. Measured windowed on the 144 Hz Odyssey G5
  (`-PbenchDisplay`, default = highest-refresh display), AC power, owner partly using the Mac:

  | pacing | scene | rendered fps | presented/s | note |
  |---|---|---|---|---|
  | handler | light | 3377 | 151.8 | exclusive, nothing else running |
  | handler | light | 2889 | 106.1 | Metal HUD on, owner active |
  | handler | shaders | 601 | 184.5 | Metal HUD on |
  | gpu | shaders | 482 | 137.7 | |
  | gpu | shaders | 437 | 139.8 | Metal HUD on |
  | gpu, VSync on | light | 140.9 | 140.8 | |

  With the default handler pacing the windowed presents already reach or exceed the refresh rate; `gpu` pacing caps
  them just below it (138-140 of 144) and costs ~20 % rendered fps (frame copy + extra command buffer), so it stays
  opt-in. Runs with the window occluded (owner's windows in front) showed 2-120 presents/s and were discarded. The
  owner's ~70 shown fps was not reproduced windowed; fullscreen on the G5 and the Metal HUD's own counter still need a
  run while nobody uses the Mac (the HUD cannot be read from logs: `MTL_HUD_LOG_ENABLED=1` wrote nothing to the unified
  log here; `-PmetalHud` sets both variables). The "HUD shows no GPU time" report is not investigated yet. `bench-present:` also has `displayed_fps` now (presents with a distinct `presentedTime`);
  windowed it still reports 142.8/s on the 60 Hz display, so it is the compositor latch, not scan-out.

### Encoder count (2026-10-19)

Complementary Unbound r5.6.1 (shaders bench, 854x480, exclusive GPU, AC, screen unlocked; `-PperfCounters`, and
`-PperfTrace` logs every encoder of one submit per window with the caller that opened it):

| | avg fps | p50 | render enc / submit | blit enc / submit | GPU |
|---|---|---|---|---|---|
| before | 435.6 | 2.26 ms | 26.6 (20.0 clear encoders) | 3.5 | 1.09 ms |
| batched clears | 638.4 | 1.56 ms | 7.1 (0.5) | 4.0 | 0.76 ms |
| + blit coalescing | 667.6 | 1.51 ms | 7.1 | 2.0 | 0.74 ms |

- Cause: Iris clears both textures of every colortex each frame; the mipmapped ones (Complementary) were cleared with
  one render encoder per mip level (10 each). A materialised clear now clears every pending clear of the same size in
  one load-action encoder (up to 8 attachments) and fills levels > 0 by blit copies of level 0.
- Consecutive copies share one blit encoder unless they touch what an earlier copy in it wrote (resources are
  untracked). Light scene unchanged (1 render encoder per submit).
- Not done: hoisting uploads to the start of the frame (none of the bench scenes has staged uploads; Sodium uploads
  through mapped memory), store-action DontCare, replacing the global fence chain. Metal HUD numbers from the owner's
  setup are needed to see which of these still matter after the clear fix.
- Pipeline binary archive: "expecting 'fragment' stage in pipeline no. N" at save (whole archive not written that
  session). Diagnosed, not fixed: every pipeline serialises alone; it fails as soon as two pipelines with the same
  vertex+fragment functions and different blend state are both in the archive (Apple archiver). Restricting the
  archive to one pipeline per fragment function and serialising adds did not remove all failures; reverted.
