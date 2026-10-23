# Nativium roadmap

**Goal:** Minecraft running perfectly on Apple Silicon Macs through Metal — everything that can be done natively is done
natively on Metal; everything that cannot be rewritten (other mods' OpenGL code) is translated; anything that still
cannot run falls back to OpenGL with a clear message instead of crashing.

Guiding rules
- **Native first:** vanilla, Sodium, Sodium Extra, Reese's Sodium Options and Fabric rendering mods run on Metal through
  Minecraft's own graphics layer (renderpearl), unmodified.
- **Translate second:** mods that call OpenGL directly (Iris, Distant Horizons, Voxy, older HUD/minimap mods) go through
  the `nativium-gl` translation layer.
- **Fall back last:** if something is unsupported, switch to OpenGL at the next start and say exactly why.
- **Measure everything:** no optimisation stays without a benchmark showing it helps (`docs/research/…` §L).
- Evidence and reasoning: `docs/research/METALLIUM-RESEARCH-REPORT.md`. Current state: `docs/STATUS.md`.

Status legend: ✅ done · 🔄 in progress · ⏳ planned · 🔬 needs research/audit

## Phase 1 — Metal backend works ✅
- ✅ Metal backend under renderpearl (started from Metallum, MIT), Metal first on Apple Silicon, OpenGL/Vulkan fallback
- ✅ CI on GitHub's Mac runners: smoke test, game tests with screenshots and an automatic terrain check
- ✅ Sodium terrain renders correctly on Apple Silicon (the garbled output was GitHub's paravirtual GPU only; CI is now disabled)
- ✅ Conformance scenes: every vanilla pipeline compared against OpenGL screenshots (orientation, blending, depth, OIT):
  `-PconformanceTest` (30 shots, fancy + fabulous) vs `-PbackendOff`, `tools/conformance-compare.sh`; all scenes match
  within run-to-run noise, no Metal bug found (`docs/compat/conformance-2026-10-23.md`)

## Phase 2 — Backend performance (mcopt's techniques) 🔄
Audit and implementation 2026-10-23 (`MetalCommandEncoder`; every new step has a system property for A/B and is on by
default (except direct writes) after the regression suite - vanilla, Sodium, Iris + Complementary Reimagined, Voxy + Complementary - passed with
screenshots pixel-identical to the property off, apart from the run-to-run timing noise of animated scenes). Frame-rate
benchmarks on the G5 are still to be done (CPU-side encoder counters below, `-PperfCounters`).
- ✅ Merge consecutive render passes with the same targets (render encoder reused while the attachments match)
- ✅ Clears as load actions: clears are recorded lazily and become `MTLLoadActionClear` of the next pass, or one grouped
  load-action-clear encoder (up to 8 same-size targets) when something else needs them first
- ✅ Store actions chosen per pass (`-Dnativium.storeActions`, default on): pass encoders start with
  `MTLStoreActionUnknown`; an attachment whose whole contents a clear recorded while the encoder is still open will
  overwrite ends with `DontCare`, everything else with `Store`. Not done: `DontCare` for depth after its last use in the
  frame (would need frame-level lookahead; a depth texture may be read next frame by mods/packs) and memoryless/MSAA
  targets (Minecraft has none). Rarely triggers in the test scenes (0-0.2 per frame)
- ✅ One upload command buffer per frame (`-Dnativium.uploadHoist`, default on): `copyToBuffer`/`writeToBuffer` into a
  buffer nothing recorded in the current submit references yet go into a blit encoder of a separate command buffer
  committed right before the frame's, instead of ending the open render encoder (vanilla `StagedVertexBuffer` uploads
  between world, hand and GUI; Sodium geometry uploads). "Not referenced": every GPU use fetches the handle through
  `MetalGpuBuffer.metalBuffer()`, which stamps the submit; buffers handed to external code
  (`NativiumInterop.metalBuffer`) and GPU-written sources are never hoisted. Sodium test: render encoders per frame
  2.5 -> 1.5, blit encoders 1.0 -> 0.0; Iris + Complementary: render 9.4 -> 8.5, blit 3.4 -> 2.0 (the rest are
  GPU-to-GPU texture copies, which stay in place). Texture uploads (`writeToTexture`) are not hoisted yet (no per-texture
  use tracking; ~0 per frame in steady state)
- ✅ Frame timeline: 3 submits in flight, per-slot completion semaphores (no `waitUntilCompleted` per frame), VSync-off
  present in its own command buffer. 🔄 The global fence chain: hazard-tracked resources instead of the chain exist as
  opt-in `-Dnativium.gpu.overlap=true` (`docs/design/gpu-overlap.md`: Unbound +9.5 %, Reimagined +21.9 %, Rethinking
  Voxels -10.3 %), so it stays off by default. Remaining blocking waits are explicit only (GL readbacks
  `NativiumInterop.submitAndWait`, `GpuFence.awaitCompletion`)
- ✅ Pipeline compilation off the render thread for renderpearl pipelines (`compilePipeline` on the background
  executor) + disk cache: SPIR-V→MSL and GLSL→SPIR-V results, `MTLBinaryArchive` segments (also opened as a metallib),
  attachment-variant index prebuilt next session (`MetalPipelineCache`, `shadercache/*`). 🔄 Iris pack programs still
  compile on the render thread at first use (`finishCompile` right away; cached on disk, so only the first session pays)
- 🔄 Unified memory (`-Dnativium.directWrites=true`, **default off**): `writeToBuffer` into a shared buffer that no in-flight
  submit and nothing in the current one references is written directly (no staging + blit); uniform/dynamic buffers
  were already written directly (transient ring, orphaned backings), buffers created with data too. Private buffers
  (vertex) stay private and staged. Off by default because no game-test scene reaches the path (0 direct writes
  counted; staged writes into shared buffers are ~0 per frame), so it is unproven and the gain small
- ✅ Render-thread QoS (`-Dnativium.qos.render`, default `interactive`): the render thread runs at
  `QOS_CLASS_USER_INTERACTIVE` (idea from mcopt, own code). Not done: integrated-server thread QoS (mcopt also raises it)
- Source: mcopt (Apache-2.0, code from `09fea99` on, credited), minecraft-metal-renderer and MetalRender (ideas only)

## Phase 3 — Terrain performance (inside Sodium, not replacing it) ⏳
- ✅ Measured (`docs/benchmarks/terrain-2026-10-19.md`): Sodium terrain costs 0.07–0.29 ms CPU per frame on Metal
  (~60 ns per draw); with Complementary rd32 ~1 ms GPU, almost all in the main view. ICB/indirect for CPU savings and
  draw merging are not worth it (measured); main-view GPU occlusion culling is the remaining lever
- Sodium's indirect draw path on Metal, then indirect command buffers (ICB)
- ✅ GPU culling: two-phase Hi-Z occlusion in compute (ideas from mcopt / Nvidium; own code), main view only, Iris's
  shadow pass untouched (`docs/benchmarks/terrain-cull-2026-10-21.md`): pixel-identical on vs off; rd32 +4..+77%
  shaders off, +2..+16% with Complementary; rd16 neutral/slower. Config `terrainOcclusionCulling` AUTO = on at rd ≥ 24,
  also with shader packs since 2026-10-23: deterministic check with a frozen Iris frame clock (`-PterrainCullTest
  -Pshaderpack=…`, off-vs-off baselines, negative control) — Reimagined pixel-identical, BSL and Photon on-vs-off within
  their own frame-to-frame noise; packs with world curvature / extreme waving stay off. Fixed on the way: wrong depth
  source with Distant Horizons; never culls while Voxy is loaded (Voxy draws inside Sodium's opaque layer)
- Later, optional: mesh-shader terrain for shaders-off on M3+ — only if it beats the indirect path in benchmarks

## Phase 4 — `nativium-gl`: OpenGL → Metal translation layer 🔄
- ✅ Foundation (design: `docs/design/nativium-gl.md`): module `nativium-gl`, opt-in bytecode retargeting with
  OpenGL fallback bridges, per-mod unimplemented-function log, GL state tracker + Metal device for buffers (persistent
  mapping), 2D textures, FBOs, GLSL 330+ programs, VAOs, draws, blend/depth/cull state, fences; unit tests incl. real
  Metal readback; game test `-PwithGl` (raw-GL test mod renders on Metal, pixel-checked)
- ✅ Voxy runs unmodified through nativium-gl and renders its LOD terrain (routed automatically on Metal; macOS storage natives via the nested nativium-voxy-natives addon;
  compute, SSBO + atomics, image load/store, indirect + indirect-count draws, UBOs, DSA — design §12)
- Calls to `org.lwjgl.opengl.*` in mod code are redirected at load time to Nativium's layer
- Emulated GL state (textures, buffers, framebuffers, programs, blend/depth) with synthetic GL names → Metal objects
- Draws are recorded into Nativium's current render pass; GLSL goes through `GlslProgramTranslator`
- Logs every unimplemented GL function per mod → coverage grows from real mods, not guesses
- First user: the Iris adapter (~110 GL functions); then Distant Horizons, Voxy, HUD/minimap mods
- ✅ **Goal reached: Voxy runs on macOS** (on no other Mac setup today — Apple's OpenGL stops at 4.1, Voxy needs 4.6).
  Requires nativium-gl to cover compute shaders, SSBOs + atomics, indirect-count draws and persistent mapping.
  Voxy runs unmodified through the layer (its licence forbids porting/copying). Parallel option: ask MCRcortex about a
  Metal path on Nativium's extension API (together with the Nvidium-Reborn name/logo question).
- Limits: readbacks and frequent framebuffer switches are slower; immediate mode / geometry shaders later or never
- ⏸ Deferred until after the alpha (owner decision 2026-10-18): the broad coverage campaign (scan the top ~200
  Fabric 26.3 client mods, implement missing GL functions until ≥90% of mods work). Only the GL coverage that
  current targets (Iris adapter, Voxy, DH, the owner's modpack) need is done before the alpha. WIP scanner parked on
  branch `parked/gl-coverage`.

## Phase 5 — Iris shader packs (top feature priority) 🔄
- ✅ Shader translator: Iris-style GLSL → SPIR-V → Metal with uniform/binding reflection
- ✅ Stage 1: game starts on Metal with Iris + adapter
- ✅ Stage 2: begin/prepare/deferred/composite/final passes run on Metal over the vanilla/Sodium scene
  (`MetalPostPipeline`, design: `docs/design/iris-stage2-post-pipeline.md`). Open items:
  viewport scale, composite blend overrides, custom textures, `size.buffer.*`, explicit uniform blocks
- 🔄 Stage 3: gbuffers programs render the world into the pack's colortex buffers (design:
  `docs/design/iris-stage3-gbuffers.md`): Sodium terrain (solid/cutout/translucent), sky, particles, entities, hand,
  lines via Iris's own pipeline mapping and GLSL patcher, Iris's extended vertex formats; deferred passes between
  opaque and translucent geometry; depthtex1/2 copies. Open items: custom textures, PBR atlases, programs with >16
  textures or geometry shaders (vanilla shader used), a test with clouds/weather/block entities
- 🔄 Stage 4: shadow pass on Metal (design: `docs/design/iris-stage4-shadows.md`): Iris's shadow pass (adapted
  `ShadowRenderer`) retargeted to shadowtex0/1 + shadowcolorN, pack `shadow*` programs (cull off, forward-Z depth),
  shadow samplers incl. `sampler2DShadow` via a Metal depth-compare sampler, prepare passes after the shadow pass.
  Verified with a synthetic shadow pack and Complementary (matches Iris/OpenGL). Open items:
  mipmapped shadow maps, DH shadows, `ShadowRenderCallbacks`
- 🔄 Stage 5: compute, image load/store, SSBO (packs that macOS OpenGL 4.1 cannot run today; design:
  `docs/design/iris-stage5-compute.md`): compute passes in every Iris stage (setup, shadow, shadowcomp, begin/prepare/
  deferred/composite incl. compute-only, final; indirect dispatch) as Metal compute encoders inside Nativium's frame,
  colorimg/shadowcolorimg/custom 2D/3D images with atomics, persistent SSBOs with `.length()`, images/SSBOs/3D custom
  samplers in gbuffers/shadow/fullscreen programs, shadowcomp fragment passes, mipmapped buffers. Verified with a
  synthetic compute pack; custom textures, `size.buffer.*`, >16 textures, `MC_OS_MAC` hidden (Photon, Bliss, Unbound
  colored lighting render). Open items: Bliss night sky/rain, shadowcolor mipmaps
- 🔄 Stage 6: geometry shaders emulated in the vertex stage (design: `docs/design/iris-stage6-geometry.md`; idea from
  Mesa's Apple-GPU GS lowering, no code): VS+GS merged into one vertex shader that pulls its triangle's corners, runs
  the VS per corner and the GS per output slot, side effects once per primitive; default on
  (`-Dnativium.iris.geometryShaders=false` turns it off). Verified: Rethinking Voxels' voxelizing `shadow.gsh` (lit
  terrain near the camera, block lights). Open items: tessellation, non-triangle GS input/output, `invocations`,
  cost (VS 18x / GS 6x per shadow triangle for Rethinking Voxels, about half the fps of the broken fallback; a compute
  pre-pass would be cheaper)
- Unsupported features are detected at pack load and reported; never a crash or black screen
- **Pack sweep** (regression test after every Iris stage): a local corpus of popular packs (BSL, Complementary
  Reimagined/Unbound, Photon, Bliss, Sildur's Vibrant, MakeUp Ultra Fast, Solas, Rethinking Voxels, Nostalgia, Super
  Duper Vanilla) in `metallium-research/shaderpacks/` — local only, never committed (most licences forbid
  redistribution). One command loads each pack, takes screenshots and records translated/unsupported programs into a
  compatibility table under `docs/`.
- Design: `docs/design/iris-adapter-blueprint.md`

## Phase 6 — Mod ecosystem ⏳ / 🔬
- 🔄 **Distant Horizons** (audit: `docs/research/notes/voxy-distant-horizons.md`): on 26.3 its default renderer uses
  Minecraft's graphics layer, no raw OpenGL → runs natively on Metal. Backend-detection mixin fixed for newer DH builds.
  Target: "DH supported without shader packs" (needs a runtime test). DH + Iris shader packs is blocked by DH itself
  (it forces its OpenGL renderer when Iris is present) → later, together with the Iris adapter (DH ships LGPL/GPL: no code copying).
- ⏳ **Voxy**: raw OpenGL 4.6, compute-heavy (compute shaders, SSBOs, atomics, indirect-count draws); refuses non-OpenGL
  backends. macOS's OpenGL 4.1 cannot run it either. Licence "all rights reserved" → no porting/copying. Only realistic
  route: run Voxy **unmodified** through `nativium-gl` once that layer supports compute/SSBO/indirect (long-term,
  months). Short-term: make the game start cleanly with Voxy installed (disable it with a message instead of crashing).
- ⏳ HUD/minimap/overlay mods: most work as-is (vanilla API); CI test that loads popular mods and takes screenshots
- 🔬 Owner's must-work list (audit running → `docs/research/notes/mod-compat-batch1.md`): **ReGlass** (liquid-glass UI),
  **Meshelium** (mesh-shader terrain; must be installable alongside Nativium; evaluate its ideas for Metal mesh shaders),
  **Light Overlay**, **Cull Leaves**, **More Culling**. Each gets a game-test scenario once supported.
- ⏳ Rule of thumb verified in CI: mods that work on vanilla's Vulkan backend should work on Nativium
- ✅ Automatic fallback: detect a mod Nativium cannot handle → OpenGL at next start, with message (Phase 6c)

## Phase 6b — Apple-native features ⏳ / 🔬
- 🔬 **MetalFX upscaling** (research: `docs/research/notes/metalfx-upscaling.md`): spatial upscaler first (render scale
  option, UI at native resolution), then temporal upscaler (needs camera jitter + motion vectors + depth)
- ⏳ **MetalFX frame interpolation** (frame generation) after temporal upscaling works; depends on macOS version
- ⏳ **Game Mode**: launcher wrapper `.app` with game category so macOS enables Game Mode
- 🔄 **Low Power / efficiency mode** (owner request; done: mode setting, macOS Low Power Mode/battery/thermal detection
  via FFM polled every 5 s, live FPS cap — bench light scene 10 s: Performance 269 fps, Low Power 30.0 fps): detect macOS Low Power Mode (`NSProcessInfo.isLowPowerModeEnabled` +
  power-state change notification), thermal state and battery vs. AC; switch live, no restart. Measures, by expected
  savings: FPS cap + VSync; lower internal resolution + MetalFX upscaling; worker/chunk-builder threads at a lower QoS
  class so they run on efficiency cores; fewer frames in flight; optionally reduced shader-pack cost (shadow resolution).
  Measure watts (powermetrics / Metal HUD) per mode in the benchmark stage.
- ✅ **"Nativium" settings tab** (MetalFX/validation options still to come) (Sodium config API → also shown by Reese's Sodium Options): Graphics Engine, Power mode
  (Automatic / Performance / Balanced / Low Power), FPS limit on battery, MetalFX options (later), Display P3, diagnostics
  (Metal HUD toggle, validation).
- ⏳ Later, optional: hardware ray-traced effects (M3+), and a Metal-native GPU-driven terrain path (owner may start a
  separate Nvidium-style project)

## Phase 6c — Compatibility center ✅ (first version, 2026-10-18)
- ✅ Startup scan of all mods for direct OpenGL use (ASM, cached) → status per mod: native on Metal / via nativium-gl /
  partial (unimplemented GL functions seen) / will not render on Metal / disabled jars
- ✅ Order: native Metal first → automatic routing through nativium-gl (per-mod override, author opt-out) → on a crash
  in a routed mod, nativium-gl or the Metal backend: next start on OpenGL + screen "Disable mod (rename jar) / OpenGL
  this session / always OpenGL / try Metal again" (no crash loops). Game test `-PwithGl -PcrashRecovery` simulates it
- ✅ Status screen (engine, Sodium on Metal, Iris adapter, DH, Voxy, per-mod table with overrides, power state; from the
  Sodium tab or vanilla Video Settings) + "Nativium" Sodium tab (General + Power pages)
- ✅ `docs/MOD-AUTHORS.md`
- ✅ Power mode "Performance" ignores macOS Low Power Mode
- ⏳ nativium-gl is still a separate jar (not jar-in-jar in the core); GL calls from mod *mixins* merged into Minecraft
  classes are not attributed/retargeted; scan does not look into reflection-based GL use

## Phase 7 — Benchmarks and comparison ⏳
**Engine shootout (fixed test stage, owner requirement: Metal must prove it is the fastest engine on Apple Silicon)**
- Engines: Metal (Nativium) vs OpenGL (Apple GL 4.1, itself layered on Metal) vs Vulkan (MoltenVK → Metal)
- Scenes: (1) vanilla light, (2) Sodium RD 32 CPU-heavy, (3) Complementary shaders GPU-heavy, (4) chunk loading while flying
- Metrics: avg FPS + 1% lows, frame-time p50/p95/p99, rendered vs displayed frames, memory; identical seed, camera,
  window size, settings; one run at a time on an idle Mac
- One command runs the whole matrix and writes a Markdown table to `docs/benchmarks/`; re-run after every larger
  optimisation as a performance regression test. Where Metal loses, that becomes the next optimisation target.
- Built right after the Graphics Engine selector (Metal / OpenGL / Vulkan) lands.
- Same world, seed and camera path; every frame presented; frame time median/p95/p99, CPU/GPU time, memory
- Baselines: vanilla OpenGL, vanilla Vulkan via MoltenVK (and Mesa KosmicKrisp), Sodium on OpenGL, mcopt (pacing off)
- Metal Performance HUD (`MTL_HUD_ENABLED=1`) as extra GPU-side view for Nativium

## Phase 8 — Release 🔄
- ✅ VSync-off present pacing (one present per refresh, up to 3 in flight; Complementary fullscreen 54.7 -> 131.5 shown/s)
- ✅ Final pre-1.0 engine shootout (`docs/benchmarks/shootout-2026-10-20-final.md`) and release-jar production-style
  check (`docs/compat/release-check-2026-10-20.md`); install notes in `docs/RELEASE.md`
- ⏳ **Code cleanup (before release):** remove dead options and diagnostics no longer needed, unify duplicated helpers,
  consolidate the game-test flags, tidy `docs/STATUS.md`, reduce log noise, check licence headers
- Owner-tested on M3 Max; CI green; known limitations documented; credits complete (`README.md`, `NOTICE`)
- Releases per Minecraft version; port the backend when renderpearl changes

## Long-term vision (owner, 2026-10-18)

Order: (1) finish the Metal renderer + Iris support → (2) MetalFX upscaling and frame interpolation → (3) broaden the
project (✅ renamed to **Nativium**, formerly Metallium, on 2026-10-21; earlier working title "Metall/Nvid-ium Reborn"): one mod optimising for both Apple GPUs (Metal, MetalFX) and NVIDIA
GPUs (Nvidium-style GPU-driven terrain, DLSS upscaling and multi-frame generation).

Open items before the NVIDIA work:
- Name and logo derived from Nvidium (MCRcortex): ask MCRcortex for permission first; Nvidium code is LGPL-3.0, so any
  adapted Nvidium code stays in an LGPL module (like `nativium-iris`).
- DLSS / frame generation: NVIDIA Streamline/DLSS SDK licence terms; runs via Vulkan (vanilla 26.3 has a Vulkan
  backend) or DirectX, not Metal → a separate backend path with its own module.

## After 1.0 — only with owner consent

Not started without the owner's explicit agreement per item:
- Apple features: MetalFX upscaling/frame interpolation, Game Mode, hardware ray tracing
- NVIDIA / Nvidium-style GPU-driven path (the broadening; the rename to Nativium itself is ✅ done 2026-10-21)
- **Voxy / Distant Horizons far terrain under shader packs (first big job after 1.0, owner decision 2026-10-20).**
  ✅ Done (design: docs/design/iris-voxy-dh.md). 2026-10-21: Voxy's own shader-pack pipeline
  (`IrisVoxyRenderPipeline`, pack `voxy.json` + `voxy_*.glsl`) runs on Metal via `VoxyIrisBridge` for packs with a Voxy
  patch (Complementary Reimagined: pipeline created, LOD draws land in the pack's colortex0/6, `vxDepthTexOpaque/Trans`
  bound in the pack's passes, game test PASS incl. resizes); the safety net now only pauses Voxy when the bridge is
  not active (packs without `voxy.json`, failures). 2026-10-22: verified in the final image with Complementary
  Reimagined, Photon and BSL (fix: constant expressions in `layout(binding)`, see STATUS). Open: fps cost at
  2560x1440 in the owner's modpack. Distant Horizons: done 2026-10-23 (`DhIrisBridge`: DH's Blaze3D terrain pass
  runs the pack's `dh_terrain`/`dh_water` into the gbuffers; Reimagined, Photon, BSL verified; `dh_generic` and
  `dh_shadow` added the same day). Open: fps cost.
- GPU culling (two-phase Hi-Z occlusion)
- Broad nativium-gl coverage campaign
