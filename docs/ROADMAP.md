# Metallium roadmap

**Goal:** Minecraft running perfectly on Apple Silicon Macs through Metal — everything that can be done natively is done
natively on Metal; everything that cannot be rewritten (other mods' OpenGL code) is translated; anything that still
cannot run falls back to OpenGL with a clear message instead of crashing.

Guiding rules
- **Native first:** vanilla, Sodium, Sodium Extra, Reese's Sodium Options and Fabric rendering mods run on Metal through
  Minecraft's own graphics layer (renderpearl), unmodified.
- **Translate second:** mods that call OpenGL directly (Iris, Distant Horizons, Voxy, older HUD/minimap mods) go through
  the `metallium-gl` translation layer.
- **Fall back last:** if something is unsupported, switch to OpenGL at the next start and say exactly why.
- **Measure everything:** no optimisation stays without a benchmark showing it helps (`docs/research/…` §L).
- Evidence and reasoning: `docs/research/METALLIUM-RESEARCH-REPORT.md`. Current state: `docs/STATUS.md`.

Status legend: ✅ done · 🔄 in progress · ⏳ planned · 🔬 needs research/audit

## Phase 1 — Metal backend works ✅ / 🔄
- ✅ Metal backend under renderpearl (started from Metallum, MIT), Metal first on Apple Silicon, OpenGL/Vulkan fallback
- ✅ CI on GitHub's Mac runners: smoke test, game tests with screenshots and an automatic terrain check
- 🔄 **Sodium terrain renders garbled** (virtual GPU) — owner test on M3 Max decides where the bug is, then fix
- ⏳ Conformance scenes: every vanilla pipeline compared against OpenGL screenshots (orientation, blending, depth, OIT)

## Phase 2 — Backend performance (mcopt's techniques) ⏳
- Merge consecutive render passes with the same targets; clears as load actions; store actions chosen per pass
- One blit encoder per frame for all uploads instead of splitting render passes per upload
- Replace the global fence chain and blocking waits with a frame timeline (2–3 frames in flight)
- Pipeline compilation off the render thread + disk cache (SPIR-V→MSL cache, `MTLBinaryArchive`)
- Unified memory: write directly into shared buffers instead of staging copies where safe
- Render-thread QoS (as mcopt does)
- Source: mcopt (Apache-2.0, code from `09fea99` on, credited), minecraft-metal-renderer and MetalRender (ideas only)

## Phase 3 — Terrain performance (inside Sodium, not replacing it) ⏳
- Sodium's indirect draw path on Metal, then indirect command buffers (ICB)
- GPU culling: two-phase Hi-Z occlusion in compute (ideas from mcopt / Nvidium), compatible with Sodium's culler,
  Sodium Extra's fog culling and Iris's shadow pass
- Later, optional: mesh-shader terrain for shaders-off on M3+ — only if it beats the indirect path in benchmarks

## Phase 4 — `metallium-gl`: OpenGL → Metal translation layer 🔄
- ✅ Foundation (design: `docs/design/metallium-gl.md`): module `metallium-gl`, opt-in bytecode retargeting with
  OpenGL fallback bridges, per-mod unimplemented-function log, GL state tracker + Metal device for buffers (persistent
  mapping), 2D textures, FBOs, GLSL 330+ programs, VAOs, draws, blend/depth/cull state, fences; unit tests incl. real
  Metal readback; game test `-PwithGl` (raw-GL test mod renders on Metal, pixel-checked)
- ⏳ Next for Voxy: compute, SSBO + atomics, image load/store, indirect + indirect-count draws, UBOs, DSA (design §12)
- Calls to `org.lwjgl.opengl.*` in mod code are redirected at load time to Metallium's layer
- Emulated GL state (textures, buffers, framebuffers, programs, blend/depth) with synthetic GL names → Metal objects
- Draws are recorded into Metallium's current render pass; GLSL goes through `GlslProgramTranslator`
- Logs every unimplemented GL function per mod → coverage grows from real mods, not guesses
- First user: the Iris adapter (~110 GL functions); then Distant Horizons, Voxy, HUD/minimap mods
- **Explicit goal: Voxy running on macOS** (it runs on no Mac today — Apple's OpenGL stops at 4.1, Voxy needs 4.6).
  Requires metallium-gl to cover compute shaders, SSBOs + atomics, indirect-count draws and persistent mapping.
  Voxy runs unmodified through the layer (its licence forbids porting/copying). Parallel option: ask MCRcortex about a
  Metal path on Metallium's extension API (together with the Nvidium-Reborn name/logo question).
- Limits: readbacks and frequent framebuffer switches are slower; immediate mode / geometry shaders later or never

## Phase 5 — Iris shader packs (top feature priority) 🔄
- ✅ Shader translator: Iris-style GLSL → SPIR-V → Metal with uniform/binding reflection
- ✅ Stage 1: game starts on Metal with Iris + adapter
- ✅ Stage 2: begin/prepare/deferred/composite/final passes run on Metal over the vanilla/Sodium scene
  (`MetalPostPipeline`, design: `docs/design/iris-stage2-post-pipeline.md`). Open items: mipmapped buffers,
  viewport scale, composite blend overrides, custom textures, `size.buffer.*`, explicit uniform blocks
- 🔄 Stage 3: gbuffers programs render the world into the pack's colortex buffers (design:
  `docs/design/iris-stage3-gbuffers.md`): Sodium terrain (solid/cutout/translucent), sky, particles, entities, hand,
  lines via Iris's own pipeline mapping and GLSL patcher, Iris's extended vertex formats; deferred passes between
  opaque and translucent geometry; depthtex1/2 copies. Open items: custom textures, PBR atlases, programs with >16
  textures or geometry shaders (vanilla shader used), a test with clouds/weather/block entities
- ⏳ Stage 4: shadow pass
- ⏳ Stage 5: compute, image load/store, SSBO (packs that macOS OpenGL 4.1 cannot run today)
- ⏳ Stage 6: geometry/tessellation shaders via compute emulation, learning from Mesa's Apple-GPU emulation (MIT)
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
  route: run Voxy **unmodified** through `metallium-gl` once that layer supports compute/SSBO/indirect (long-term,
  months). Short-term: make the game start cleanly with Voxy installed (disable it with a message instead of crashing).
- ⏳ HUD/minimap/overlay mods: most work as-is (vanilla API); CI test that loads popular mods and takes screenshots
- 🔬 Owner's must-work list (audit running → `docs/research/notes/mod-compat-batch1.md`): **ReGlass** (liquid-glass UI),
  **Meshelium** (mesh-shader terrain; must be installable alongside Metallium; evaluate its ideas for Metal mesh shaders),
  **Light Overlay**, **Cull Leaves**, **More Culling**. Each gets a game-test scenario once supported.
- ⏳ Rule of thumb verified in CI: mods that work on vanilla's Vulkan backend should work on Metallium
- ⏳ Automatic fallback: detect a mod Metallium cannot handle → OpenGL at next start, with message

## Phase 6b — Apple-native features ⏳ / 🔬
- 🔬 **MetalFX upscaling** (research: `docs/research/notes/metalfx-upscaling.md`): spatial upscaler first (render scale
  option, UI at native resolution), then temporal upscaler (needs camera jitter + motion vectors + depth)
- ⏳ **MetalFX frame interpolation** (frame generation) after temporal upscaling works; depends on macOS version
- ⏳ **Game Mode**: launcher wrapper `.app` with game category so macOS enables Game Mode
- ⏳ Later, optional: hardware ray-traced effects (M3+), and a Metal-native GPU-driven terrain path (owner may start a
  separate Nvidium-style project)

## Phase 7 — Benchmarks and comparison ⏳
**Engine shootout (fixed test stage, owner requirement: Metal must prove it is the fastest engine on Apple Silicon)**
- Engines: Metal (Metallium) vs OpenGL (Apple GL 4.1, itself layered on Metal) vs Vulkan (MoltenVK → Metal)
- Scenes: (1) vanilla light, (2) Sodium RD 32 CPU-heavy, (3) Complementary shaders GPU-heavy, (4) chunk loading while flying
- Metrics: avg FPS + 1% lows, frame-time p50/p95/p99, rendered vs displayed frames, memory; identical seed, camera,
  window size, settings; one run at a time on an idle Mac
- One command runs the whole matrix and writes a Markdown table to `docs/benchmarks/`; re-run after every larger
  optimisation as a performance regression test. Where Metal loses, that becomes the next optimisation target.
- Built right after the Graphics Engine selector (Metal / OpenGL / Vulkan) lands.
- Same world, seed and camera path; every frame presented; frame time median/p95/p99, CPU/GPU time, memory
- Baselines: vanilla OpenGL, vanilla Vulkan via MoltenVK (and Mesa KosmicKrisp), Sodium on OpenGL, mcopt (pacing off)
- Metal Performance HUD (`MTL_HUD_ENABLED=1`) as extra GPU-side view for Metallium

## Phase 8 — Release ⏳
- Owner-tested on M3 Max; CI green; known limitations documented; credits complete (`README.md`, `NOTICE`)
- Releases per Minecraft version; port the backend when renderpearl changes

## Long-term vision (owner, 2026-10-18)

Order: (1) finish the Metal renderer + Iris support → (2) MetalFX upscaling and frame interpolation → (3) rename and
broaden the project to **"Metall/Nvid-ium Reborn"**: one mod optimising for both Apple GPUs (Metal, MetalFX) and NVIDIA
GPUs (Nvidium-style GPU-driven terrain, DLSS upscaling and multi-frame generation).

Open items before the rename / NVIDIA work:
- Name and logo derived from Nvidium (MCRcortex): ask MCRcortex for permission first; Nvidium code is LGPL-3.0, so any
  adapted Nvidium code stays in an LGPL module (like `metallium-iris`).
- DLSS / frame generation: NVIDIA Streamline/DLSS SDK licence terms; runs via Vulkan (vanilla 26.3 has a Vulkan
  backend) or DirectX, not Metal → a separate backend path with its own module.
