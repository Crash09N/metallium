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
| Distant Horizons + Iris (+ Physics Mod) (`-PwithDH -PwithIris -Pshaderpack=…`) | Fixed a hang: with Iris installed DH forces its OpenGL renderer, whose start fails on Metal, and the client waited forever on the title screen. `DhIrisEngineMixin` keeps DH on Blaze3D on Metal. Now runs (Complementary Reimagined, Physics Mod debris on top); DH LODs are drawn but the pack fogs them at the vanilla distance ("2 DH programs are not executed yet on Metal"). |
| Voxy (locally built branch 263, `-PwithVoxy=<jar>`) | **Runs on Metal through metallium-gl, routed automatically** (`GlRouting.WHOLE_MOD`; no `-Dmetallium.gl.mods` needed; without metallium-gl / with a NATIVE override the safe-disable mixin keeps it off). Status screen: "Voxy …: runs via metallium-gl (OpenGL 4.6 translated to Metal)". Textured indirect-count draws run as ICBs (textures in argument buffers): 0 CPU-encoded fallback draws, drawIndirect CPU time 0.27 → 0.13 ms/frame (`docs/design/metallium-gl.md` §12.5). Combinations (2026-10-19, game tests, screenshots checked): Bobby + Voxy and Physics Mod + Voxy work; Iris + Voxy (Complementary Reimagined) runs without errors but the pack fogs Voxy's LODs beyond the vanilla distance (Voxy's Iris pipeline is not used by metallium-iris). |
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
- Metal binary archive, one segment per session (`pipelines-<time>.mtlarchive`): pipeline states are looked up with
  `FailOnBinaryArchiveMiss` in all segments; misses are added (exactly once per full-descriptor key) to this session's
  fresh archive, written every 20 s / on close. Never appended to a loaded archive: Metal then re-serializes it at
  several times its size (standalone test 755 KB + 1 pipeline = 2.2 MB; Iris game test 9.9 -> 115 MB per warm
  session) and moves the source file on an unchanged serialize. Reset after 24 segments or half the cache cap. Each
  segment file is also a metallib: opened as an `MTLLibrary`, functions found there skip the MSL compile entirely
  (entry points get unique content-derived names `mtl_vs_/mtl_fs_<key>`). Crash marker discards the segments; a
  pipeline that fails with archive functions is rebuilt from source and the archive dropped. If the archiver rejects
  the session archive, it is rebuilt off-lock from the retained descriptors, bisecting out the culprits
  (`pipelines.excluded`, compiled at load from then on); `-PsimulateArchiveFailure` exercises that path,
  `-PlogArchiveMisses` logs every miss.
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
- 2026-10-19, owner with Voxy (modpack, 2560x1440 windowed on the G5, HUD on): Minecraft 165 fps, HUD 65-70; VSync
  on 140/140. Ruled out on the metallium-gl side (docs/design/metallium-gl.md §12.6): 0 GPU waits and 0 ICB
  allocations during a 30 s Voxy bench measurement. Working hypothesis: handler pacing at frame times close to the
  refresh period. A present is decided only at frame end and needs the previous present's handler, which comes about
  1-2 refreshes later. At ~6 ms frames that means one present every 2-3 frames (55-83/s), while the light scene
  (0.3-1.7 ms frames) presents ~150/s. New opt-in `-PpresentPacing=refresh`: like `handler`, plus a second present in
  flight once 3/4 of a refresh period has passed since the last one. `-PbenchFpsLimit=165` emulates the owner's frame
  time. **Not measured yet**: every bench run on 2026-10-19 between 16:40 and 17:30 (owner at the Mac) got 0 presents,
  even the light scene at 1920x1080 (2336 rendered fps, all frames skipped: `nextDrawable` never returned). That is
  environmental (no present-path commit since the 152/s measurement), so those runs are invalid for presents. To run
  when nobody uses the Mac:
  `gpu-lock.sh --exclusive ./gradlew :runClientGameTest -Pbench -PwithSodium -PwithGl -PwithVoxy=<jar> -PbenchScene=voxy -PbenchWindow=2560x1440 -PbenchFpsLimit=165
  [-PpresentPacing=refresh|gpu]` (handler / refresh / gpu), plus the real-fullscreen resize repro (`-PvoxyFullscreen`).
- 2026-10-19 evening (present-pacing agent):
  - **Cause of the 0-presents runs found (backend bug, not the environment).** `MetalSurface` released every drawable
    whose size differed from the configured surface ("acquired before a resize"). macOS shrinks a 2560x1440 window
    on a 2560x1440 screen to 2560x1382, SDL's Metal view sets the layer's drawable size to that, the configured
    surface stays 2560x1440 (Fabric's game test also pins the framebuffer at 854x480), so every drawable was dropped
    and nothing was presented, ever. Fix: present it anyway (the present blit scales; logged once).
  - **Owner's symptom reproduced** (`handler` pacing, Complementary Reimagined, 2560x1440 windowed on the 144 Hz G5,
    owner at the Mac so INVALID for absolute numbers): rendered 158 fps, presented 50.9/s, mean present latency
    (submit to presented handler) 16.4 ms. Light scene focused (854x480, `bench-env: VALID`): rendered 1735,
    presented 46.8/s, latency 16.9 ms. A present needs the previous present's handler, which comes ~2.4 refreshes
    after the submit, so presents/s ≈ 1 / (latency + time to the next frame end), whatever the GPU load is.
  - New `bench-present:` fields: `pacing=` and `present_latency_ms=`. New `bench-env:` line (commit 7735229) with
    VALID/INVALID, focus, occlusion, display, resolution, refresh, power; the bench raises and focuses the window and
    runs `caffeinate`. `-PbenchFullscreen` now resizes the framebuffer to the real fullscreen size (it rendered
    854x480 scaled up before).
  - **New and only VSync-off pacing (the `handler`, `refresh` and `gpu` options and `-PpresentPacing` are gone):**
    a time budget of one present per refresh on average (a credit of one period carries over), at most 3 presents in
    flight. The drawable blit and the present go into a command buffer of their own, after a copy of the frame (ring
    of 3 copies), so no frame waits for the display.
  - Decisive runs (owner away, exclusive GPU, every row `bench-env: VALID`, window focused, AC power, 144 Hz Odyssey
    G5). In fullscreen, display mode, framebuffer, main target, drawable and presented texture are all 2560x1440. In
    the 2560x1440 windowed rows the drawable is 2560x1382 (window shrunk by macOS), scaled. 30 s settle, 20 s measure,
    Metal HUD on.

    | scene | mode | pacing | rendered fps | presented/s | present latency |
    |---|---|---|---|---|---|
    | Complementary Reimagined | fullscreen 2560x1440 | handler (old default) | 160.9 | 54.7 | 12.9 ms |
    | Complementary Reimagined | fullscreen 2560x1440 | refresh (Voxy agent's option) | 157.9 | 99.8 | 13.3 ms |
    | Complementary Reimagined | fullscreen 2560x1440 | **rate (new)** | 159.6 | **131.5** | 13.1 ms |
    | Complementary Reimagined | fullscreen 2560x1440 | VSync on | 132.9 | 132.9 | 14.8 ms |
    | Complementary Reimagined | windowed 2560x1440 | handler | 160.9 | 50.8 | 14.8 ms |
    | Complementary Reimagined | windowed 2560x1440 | rate | **19.0** | 19.0 | 14.9 ms |
    | light | fullscreen 2560x1440 | handler | 2280 | 312.6 (`presentedTime` > refresh: not scan-out) | 2.8 ms |
    | light | fullscreen 2560x1440 | rate | 2358 | 135.0 | 3.8 ms |
    | light | fullscreen 2560x1440 | VSync on | 131.8 | 131.8 | 14.6 ms |
    | light | windowed 2560x1440 | rate | 1766 | 132.4 | 11.7 ms |

    With VSync off, the new pacing shows about as many frames as VSync on (131.5 vs 132.9 in Complementary) and costs
    no rendered fps. `refresh` reached 100/s; the `gpu` row was not measured (time).
    **Open problem:** Complementary windowed in the game test runs at 19 fps with 50 ms frames, every frame
    presented. The same 50 ms pattern showed up earlier with VSync on windowed (20 fps, owner at the Mac, invalid
    run), and the light scene windowed with the new pacing is fine (132/s). Suspect: the game test's surface stays
    at 854x480 because Fabric cancels resize events (`surface=854x480` in `bench-env`) while the drawable is
    2560x1382 and the frame is 2560x1440. In a real game window the surface follows the window. Check the owner's
    windowed Iris play first; if it shows the same thing, `bench-env` gives the sizes to compare.
  - In-game fps counter: it stays Minecraft's own number, frames rendered per second, which is what it means on
    OpenGL and in vanilla too. With VSync off, Metal renders frames beyond the display rate but shows at most one per
    refresh. The F3 screen now has a second line, `Metal present: N shown/s of M rendered/s`.

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
- Pipeline binary archive "expecting 'fragment' stage in pipeline no. N" (2026-10-18 22:13 to 10-19 03:43 only):
  not reproducible since, on current code or on 11210cc (the commit of a failing run), cold Metallium cache and cold
  macOS Metal compiler cache, nor in standalone Swift tests (same functions + different blend/write mask/formats,
  concurrent adds, functions from the archive library). The exact functions that failed then
  (`mtl_vs_f71ece…`/`mtl_fs_231d29…`, old key) serialize fine now, so the cause was outside Metallium (state of
  Apple's archiver / compiler service). Now handled: the archive is rebuilt without the culprits (above), no more
  lost archives. Found on the way: the archive grew several-fold per warm session (append path, above; the owner's
  play instance had 178 MiB) and some pipelines missed every warm session; both gone with segments.
