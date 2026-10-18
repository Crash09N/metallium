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
| Voxy (locally built branch 263, `-PwithVoxy=<jar>`) | Needs raw OpenGL 4.6: Metallium cancels `VoxyClient.initVoxyClient` on Metal. Game starts and renders worlds; Voxy disabled, one chat message + log + F3 line "Voxy needs OpenGL 4.6 and is disabled on Metal". No 26.3 build on Modrinth yet (latest is 26.2) |
| Sodium F3 label | "Terrain Rendering: metal_multidraw (sodium, via Metallium)" on Metal (verified by screenshot + programmatic check) |
| Iris adapter stage 1 (`metallium-iris`) | Game starts on Metal with Sodium + Iris + adapter (no crash) |
| Iris adapter stage 2 (M3 Max, local game test) | Shader packs' begin/prepare/deferred/composite/final passes run on Metal over the vanilla/Sodium scene (`docs/design/iris-stage2-post-pipeline.md`). Test pack: effect verified by pixel check. Complementary Unbound r5.6.1: all 8 fullscreen programs + final translate and run (clouds/tonemap visible); its 20 gbuffers/shadow programs are reported as not executed yet. Shaders off afterwards renders exactly like before |
| Iris adapter stage 3 (M3 Max, local game test) | The packs' gbuffers programs render the world into their colortex buffers on Metal (`docs/design/iris-stage3-gbuffers.md`): Sodium terrain solid/cutout/translucent, sky (basic/textured, stars, sunrise), entities, particles, lines; deferred passes between opaque and translucent geometry, composite/final afterwards. Gbuffers test pack (`gbuffers_terrain` marker in colortex1 -> composite): pixel check passes (terrain band 1.00 marked, sky 0.00). Complementary Unbound r5.6.1: `gbuffers_terrain`, `_water`, `_skybasic`, `_skytextured`, `_entities`, `_textured`, `_line` translate and run, plus `gbuffers_hand` (hand drawn inside the level with Iris's extended vertex formats, as on GL; attachments colortex0,3,4,6); not yet: DH programs |
| Iris adapter stage 4 (M3 Max, local game test) | The packs' `shadow` programs render the shadow maps on Metal (`docs/design/iris-stage4-shadows.md`): Iris's shadow pass (adapted `ShadowRenderer`: sun-view matrices, shadow frustum culling, Sodium shadow render lists, terrain/entities/block entities/translucents) into shadowtex0/1 (D32F, `shadowMapResolution`) and shadowcolorN; gbuffers/composite programs sample them, `sampler2DShadow` through a new Metal depth-compare sampler; prepare passes after the shadow pass. Shadow test pack (floating platform at noon, composite marks shadowed pixels): shadowed 1.00 under the platform, 0.00 without it (identical numbers on Iris/OpenGL). Complementary Unbound r5.6.1: shadow 2048 px; tree canopy, trunk and wall shadows in a flat-world morning scene match Iris on OpenGL (`-Pengine=OPENGL`) screenshot for screenshot. Not yet: shadowcomp passes, mipmapped shadow maps, DH shadows |
| Iris adapter stage 3 (M3 Max, local game test) | The packs' gbuffers programs render the world into their colortex buffers on Metal (`docs/design/iris-stage3-gbuffers.md`): Sodium terrain solid/cutout/translucent, sky (basic/textured, stars, sunrise), entities, particles, lines; deferred passes between opaque and translucent geometry, composite/final afterwards. Gbuffers test pack (`gbuffers_terrain` marker in colortex1 -> composite): pixel check passes (terrain band 1.00 marked, sky 0.00). Complementary Unbound r5.6.1: `gbuffers_terrain`, `_water`, `_skybasic`, `_skytextured`, `_entities`, `_textured`, `_line` translate and run, plus `gbuffers_hand` (hand drawn inside the level with Iris's extended vertex formats, as on GL; attachments colortex0,3,4,6); not yet: shadow pass, DH programs |
| metallium-gl foundation (M3 Max, `-PwithGl`) | Raw OpenGL calls of opted-in mods are retargeted at class load and rendered on Metal into Metallium's frame (`docs/design/metallium-gl.md`). Game test: a raw-GL test mod (GLSL 330 program, VAO/VBO/EBO, texture, FBO + glClear) draws after level rendering; pixels match on Metal; with `-PbackendOff` the same calls run on real OpenGL. 23+ unit tests incl. Metal readback. Not yet: compute/SSBO/indirect (Voxy) |
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
3. Iris adapter stage 4 gaps: shadowcomp passes, mipmapped shadow maps (see the stage-4 design note); stage-3 gaps: custom textures/PBR atlases, programs with more than 16 textures
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
