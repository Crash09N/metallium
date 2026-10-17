# Metallium — Source-Code Audit, Architecture Decision and Performance Strategy

Status: **research and planning only.** No Metallium implementation code exists or was written.
Date: 2026-10-17. Target: **Minecraft 26.3 (Fabric), Java 25, Apple Silicon macOS.**

This report is the synthesis. The detailed evidence (file:line citations, per-claim labels, open questions) is in
[`notes/`](notes/), one file per investigated area:

| Notes file | Scope |
|---|---|
| [`notes/vanilla-renderpearl.md`](notes/vanilla-renderpearl.md) | Minecraft 26.3's own GPU abstraction (`com.mojang.renderpearl`), decompiled from the official client jar |
| [`notes/sodium.md`](notes/sodium.md) | Sodium 0.9.2 renderer, backend coupling, extension points, license |
| [`notes/iris-arch.md`](notes/iris-arch.md) | Iris 1.11.6 runtime architecture, GL coupling, Sodium/vanilla hooks |
| [`notes/iris-shaders-translation.md`](notes/iris-shaders-translation.md) | Iris shader transformation, glslang, SPIRV-Cross MSL, feature matrix |
| [`notes/sodium-extra-rso.md`](notes/sodium-extra-rso.md) | Sodium Extra full feature/mixin inventory, Reese's Sodium Options |
| [`notes/metallum.md`](notes/metallum.md) | Metallum + metal-jvm Metal backend |
| [`notes/metalrender.md`](notes/metalrender.md) | MetalRender (26.1 branch + 1.21.x branch) |
| [`notes/mcopt.md`](notes/mcopt.md) | mcopt Metal backend + own terrain renderer + LOD |
| [`notes/nvidium-embeddium-frapi.md`](notes/nvidium-embeddium-frapi.md) | Nvidium, Embeddium, Indium, Fabric API rendering modules |

Evidence labels used throughout: **[V] Verified** (read in implementation code), **[SI] Strongly inferred**, **[H] Hypothesis**,
**[U] Unverified**, **[M] Measured** (nothing in this report is measured — no code was built or run).
Citation format: `repo/path/File.java:L1-L2`. Paths for the notes are relative to the clone roots listed in §B.
Decompiled Minecraft citations are prefixed `mc26.3/` and use Vineflower output line numbers.

---

## A. Executive summary

### The single most important finding

**Minecraft 26.3 already has a pluggable, backend-neutral GPU layer (`com.mojang.renderpearl`) with OpenGL and Vulkan
backends, and Sodium 0.9.2 already renders all of its terrain through that layer with no raw OpenGL.** [V]

- Vanilla: `api/*` (data model) → `frontend/*` (validation, GLSL→SPIR-V via shaderc, reflection via spvc) →
  `backend/api/*` (a 5-interface SPI: `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`,
  `GpuSurfaceBackend`, `BackendRenderPipeline`). Backends receive **SPIR-V**, not GLSL. Even vanilla's GL backend
  consumes SPIR-V and cross-compiles it back to GLSL 330 (`notes/vanilla-renderpearl.md` §0.1–0.3, §3).
- Backend selection is hard-coded: `PreferredGraphicsApi.getBackendsToTry()` returns `[GL, Vulkan]` or `[Vulkan, GL]`
  (`mc26.3/net/minecraft/client/PreferredGraphicsApi.java:L32-36`). A Metal backend must be injected by mixin [V].
- Sodium 0.9.2: only 5 files import `org.lwjgl.opengl`, all diagnostics gated on the GL backend
  (`notes/sodium.md` §2). Its draw-path switch `DrawBackend {OPENGL, VK_MULTIDRAW, VK_INDIRECT}`
  (`sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java:8-29`) treats every
  non-Vulkan backend as "OPENGL", and that path calls the backend-neutral `RenderPass.multiDrawIndexed(...)` [V].
- **Metallum (MIT) already proves this end-to-end**: it implements the renderpearl SPI on Metal and runs unmodified
  Sodium terrain (`notes/metallum.md` §0.5, §9) [V call path; runtime behaviour not tested by us].

This disproves a premise of the brief: **Metallium does not need a Sodium fork or a Sodium "Metal backend".** The correct
integration point is *below* Sodium, at vanilla's renderpearl backend SPI. Everything that sits on renderpearl —
vanilla, Sodium, Sodium Extra, Reese's Sodium Options, Fabric Rendering API, mods' own `RenderPipeline`s — comes along
for free, provided the backend is semantically faithful.

### Recommended architecture (detail in §I)

**A renderpearl-level Metal backend under unmodified Sodium (Strategy A, re-scoped), plus a Metal-native Iris
adapter built on a Metallium extension API (Strategy C, hybrid with reused Iris front-end).**

1. `metallium-backend`: Metal implementation of vanilla's renderpearl SPI. Highest priority, smallest dependency surface,
   benefits every mod. Falls back to vanilla GL if Metal init fails.
2. Terrain performance comes from (a) an efficient backend (encoder merging, load/store actions, no per-upload encoder
   splits, real frames-in-flight), (b) a small Sodium adapter that steers Sodium's existing indirect-draw batch onto
   Metal indirect draws, and later (c) benchmark-gated GPU culling. **No separate custom terrain renderer in v1.**
3. Iris: keep Iris's API-neutral ~30k lines (pack loading, options, program sets, the GLSL transformer, uniform value
   providers); replace its ~20k GL-bound lines with a Metal implementation via an adapter module, through
   `Iris transform → glslang (Vulkan-relaxed) → SPIRV-Cross MSL`. Requires an Iris fork or upstream abstraction work
   (LGPL-3.0). Geometry shaders, doubles and 64-bit atomics are unsupported in v1 and must be reported clearly.
4. Sodium Extra and Reese's Sodium Options: **run unchanged** (all features except Adaptive VSync work on any
   conformant renderpearl backend). No native reimplementation needed.

### Feasibility of the four goals

| Goal | Assessment |
|---|---|
| Max Apple Silicon performance | Achievable incrementally. Backend overhead is the first bottleneck (Metallum's design serialises every encoder and splits encoders per upload, `notes/metallum.md` §0.8). Terrain gains beyond that need Metal indirect/ICB and GPU culling. All performance statements here are **[H]** until measured. |
| Iris shader-pack compatibility | **Feasible for a large subset, not universal.** Hardest part is not GLSL→MSL but Iris's reliance on mutable GL state inside vanilla render passes (`notes/iris-arch.md` §0.1). Geometry-shader packs: infeasible in v1. Tessellation: very high effort. Upside: macOS GL 4.1 today blocks compute/SSBO/image packs; a Metal path can enable them. |
| Sodium Extra coverage | **Effectively complete** with Sodium kept: every feature works unchanged on a conformant backend except Adaptive VSync (gracefully hidden). |
| Fabric ecosystem compatibility | Strong for anything on renderpearl/FRAPI (Fabric's rendering modules issue no raw GL). Breaks for mods with raw GL — Iris (handled by the adapter), Nvidium (NVIDIA-only anyway), Distant Horizons' GL renderer (unverified), any mod calling LWJGL GL directly. |

### Major risks

1. **Iris maintenance**: an Iris fork or a large mixin patch-set against ~145 GL-bound files that change upstream.
   Mitigation: push for an upstream Iris backend abstraction; keep the adapter in its own LGPL module.
2. **Semantic fidelity of the backend**: implicit "global barrier after every op" ordering, Y/winding conventions,
   reverse-Z + explicit depth writes, TRIANGLE_FAN, close-while-in-flight lifetimes (`notes/vanilla-renderpearl.md` §11.1).
   Getting these subtly wrong produces upside-down post passes, flicker, or use-after-free on the GPU.
3. **Sodium's PolyForm Shield licence**: forbids derivatives that compete with Sodium. Metallium must never contain
   Sodium code; runtime mixins into Sodium are probably fine but not certain — ask CaffeineMC (§K).
4. **Vanilla churn**: renderpearl was renamed/reshaped between 26.2 and 26.3 (`notes/metallum.md` §0.1). The SPI is
   internal, unversioned API; expect per-version porting.

---

## B. Repository and version inventory

Version re-verified against Mojang's manifest: latest release **26.3** (2026-09-15); latest snapshot 26.4-snapshot-4.
All active projects converge on 26.3.

| Project | URL | Ref audited (SHA) | MC support (verified from build files) | License (verified) | Status |
|---|---|---|---|---|---|
| Minecraft client | piston-data.mojang.com | 26.3 client.jar sha1 `e877b6a07acd633fb3bb475002175cec036e7b87`, decompiled with Vineflower 1.11.1 | 26.3 | Mojang EULA (read-only reference, no reuse) | Audited: renderpearl, blaze3d, client renderer |
| Sodium | github.com/CaffeineMC/sodium | tag `mc26.3-0.9.2` = `03198cb36ab831c99694403d18e4cf4a1726df72`; diffed `dev` `148151cf` (0.9.3-beta.1, 17 commits ahead) | 26.3 (also branches 1.20.1–26.2) | **PolyForm Shield 1.0.0**; 24 FRAPI files Apache-2.0 (FabricMC); interval tree MIT | Deep audit |
| Iris | github.com/IrisShaders/Iris | branch `26.3` = `671a5c87747d33983d7ed659461a64f46dfcb1f0` (1.11.6) | 26.3, Sodium 0.9.2 | LGPL-3.0; bundles glsl-transformer (AGPL-3.0) | Deep audit |
| Sodium Extra | github.com/FlashyReese/sodium-extra-fabric (the `SodiumExtra` URL in the brief does not resolve) | `26.3/dev` = `b0bbae55629d078e0b58a0f99465ee142f18465b` (0.9.4) | 26.3, Sodium ≥0.9.2 | LGPL-3.0 | Full inventory |
| Reese's Sodium Options | github.com/FlashyReese/reeses-sodium-options | `26.3/dev` = `b6261b136e56baf7aa0f7d243be84e0b2af0b178` (2.2.6-beta.1) | 26.3, **pins Sodium 0.9.3-beta.1** | MIT | Audited |
| Nvidium | github.com/MCRcortex/nvidium | branch `26.3` = `27ceb7299e8a8de9ccca69bb4700909e600ea10f` (0.4.4-beta7) | 26.3 only, Sodium 0.9.2 | LGPL-3.0 | Audited |
| Metallum | github.com/kokodio/metallum | `master` = `4da72f52a4d994efff39ce6f90b37f3a2ce66834` (v0.0.25) | ~26.3 | MIT | Every file read |
| metal-jvm | github.com/kokodio/metal-jvm | `ccb77899cf961a38b20424c08afb2ea83fe89aed` (0.1.0) | n/a (library) | MIT | Runtime classes read |
| mcopt | github.com/noahdunnagan/mcopt | `main` = `7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c` (v0.3.0-alpha.2) | ~26.3; `breaks: sodium` | Apache-2.0 + NOTICE (pre-0.3.0 history contains Sodium code) | Deep audit |
| MetalRender | github.com/webblepebbles/MetalRender | `26.1` = `13d979498f0e7fbc1b72bc5a9126d6cc1ef4b34a`; `main` is a stub; `1.21.8-stable` targets 1.21.11 | ≥26.1 (no upper bound); `conflicts: sodium` | Custom "Pebbles_boon Software Licence" (copyleft-like); 1.21.8-stable branch Apache-2.0; repo AGENTS.md states the owner bans AI use | Audited (ideas only) |
| Embeddium | github.com/embeddedt/embeddium | `21.1/neoforge` = `fc5c69b0` | 1.21.1 NeoForge; no 26.x; last code commit 2025-02 | LGPL-3.0 | Moderate audit |
| Indium | github.com/comp500/Indium | `1.21.x/main` = `c4203e6` | ≤1.21.1; superseded (Sodium ships FRAPI) | Apache-2.0 | Historical |
| Fabric API | github.com/FabricMC/fabric | `26.3` = `33b369075baa5ad6c969f44787a18bf2c3ea0a5d` (0.163.0+26.3) | 26.3 | Apache-2.0 | Rendering modules audited |
| glsl-transformer | github.com/IrisShaders/glsl-transformer | `9d26f0f` | n/a | AGPL-3.0 | Audited for capability/licence |
| SPIRV-Cross | github.com/KhronosGroup/SPIRV-Cross | `300681b` | n/a | Apache-2.0 | MSL backend limits audited |
| glslang | github.com/KhronosGroup/glslang | `5cf91e7` | n/a | BSD-3/Apache-2.0 family | GLSL→SPIR-V rules audited |

**Coexistence check [V from build files]**: Sodium 0.9.2 + Iris 1.11.6 + Sodium Extra 0.9.4 + Nvidium + Metallum + mcopt
all target 26.3. Exception: RSO 2.2.6-beta.1 pins Sodium exactly `0.9.3-beta.1+mc26.3`, so RSO-dev cannot load with the
0.9.2 set; the released RSO `mc26.3-2.2.5` tag should be used for a 0.9.2 test matrix (`notes/sodium-extra-rso.md` §0).
mcopt and MetalRender both exclude Sodium, therefore also exclude Iris, Sodium Extra and RSO.

---

## C. Source-code architecture audit

### C.1 Vanilla 26.3 renderpearl (the platform Metallium plugs into)

Full SPI tables and checklist: `notes/vanilla-renderpearl.md` §1, §11.

- **Bootstrap**: `GpuBackend {getName, loadLibrary, createWindow, createDevice}`; `createDevice` returns
  `new FrontendGpuDevice(backendDevice)`. Using the frontend is effectively mandatory (it does `instanceof` checks on its
  own types) [V].
- **Window**: SDL3, not GLFW. A Metal backend creates the window with `SDL_WINDOW_METAL` and obtains a `CAMetalLayer`
  via `SDL_Metal_CreateView` (Metallum `MetalBackend.java:33-35`, `MetalSurface.java:31-49`) [V].
- **Shaders**: vanilla GLSL `#version 330` → shaderc (Vulkan 1.2 env, -O0) → SPIR-V, descriptors rewritten to set 0 with
  dense bindings → `BackendRenderPipeline.CreateInfo` carries `SpvModule`s. Injected macros only
  `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`,
  `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` [V].
- **Resource model**: std140 UBOs, 2D/Cube combined samplers, texel buffers, one ≤128 B push-constant block (unused by
  vanilla). No SSBOs, storage images, compute, geometry/tessellation, stencil, MSAA, 3D/array textures, compare samplers,
  logic ops, blend constants. `MAX_COLOR_TARGETS = 8` [V].
- **Conventions**: reverse-Z, zero-to-one depth, clear 0.0, default test `GREATER_THAN_OR_EQUAL`. Vulkan backend does not
  flip the viewport; intermediate targets have row 0 == clip y=-1, `frontFace=CLOCKWISE`, only the final swapchain blit
  flips Y. `isExplicitDepthRequired` is true on Apple Silicon, so OIT shaders write `gl_FragDepth` [V].
- **Ordering**: there is no barrier API. The Vulkan backend issues a global memory barrier after every operation, keeps
  images in GENERAL layout, two submits in flight, deferred destruction two submits later [V]. A Metal backend must give
  the same "everything is ordered" guarantee.
- **Terrain**: default path is `drawIndexedIndirect` with an instanced per-section vertex stream (binding 1, step rate 1,
  `firstInstance` = section index), gated on `maxDrawIndirectDrawCount>0 && nonZeroFirstInstance &&
  !multiDrawIndirectHasKnownIssues`; the 20-byte record equals `MTLDrawIndexedPrimitivesIndirectArguments` [V/SI].
- **Transparency**: classic sorted path by default; optional OIT (`improvedTransparency`, default off): 3 stages + composite,
  2×RGBA16F MRT, RGBA32F with MAX blend, shader depth writes [V].
- **Vulkan on macOS** is attempted (portability enumeration, MoltenVK workarounds) but GL is tried first on every platform;
  Vulkan is the default nowhere [V].
- **Raw GL outside `backend/opengl`**: none in decompiled scope [V].
- Discrepancy note: the vanilla notes' §9.2 remark that "Sodium/Iris talk raw GL" is wrong for Sodium (re-checked: 5 GL
  files, all diagnostics) and right for Iris (87 files).

### C.2 Sodium 0.9.2

Detail: `notes/sodium.md`.

- **Backend coupling [V]**: the old `client.gl.*` layer is gone. Buffers, arenas, uploads, fences, pipelines, draws all go
  through renderpearl. Sodium never opens its own render passes for terrain: it returns a
  `SodiumChunkSection extends ChunkSectionsToRender` to vanilla, which opens the passes (and runs OIT when enabled).
- **Draw paths [V]**: `DrawBackend.chooseBackend` → `OPENGL` (any non-Vulkan device; `GLDrawBatch` →
  `RenderPass.multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, n)`), `VK_MULTIDRAW`, `VK_INDIRECT`
  (`VKIndirectDrawBatch` writes `VkDrawIndexedIndirectCommand` records, layout-identical to Metal's indirect args [SI]).
- **Preserved unchanged on Metal**: chunk builder + scheduling, async cull thread, occlusion graph traversal, frustum
  culling, 20-byte compact vertex format, shared arenas with defragmentation, translucency sorting (GFNI/BSP/topological),
  FRAPI implementation (Indium functionality built in), config API/UI.
- **Backend-specific code**: ~400 lines in `client/gpu/device/**`.
- **Extension points**: internal only, reachable by mixin: `DrawBackend.chooseBackend`, `MultiDrawBatch.newBatch`,
  `DrawContext.create`, staging buffer, `ShaderChunkRenderer`. The public `api` module has no backend extension point.
- **Answers to the brief's eight Sodium questions**:
  1. Preservable with minimal changes: essentially all of it — with zero changes if the integration point is renderpearl.
  2. Assumes OpenGL: only diagnostics/workarounds (unreachable on Metal) and the naming of the "OPENGL" draw path.
  3. API-independent: everything else.
  4. Extension points for Metal: renderpearl SPI (primary); `DrawBackend`/`MultiDrawBatch`/`DrawContext` via mixin (secondary, for an indirect path).
  5. Needs a fork: nothing for correctness. A first-class Metal draw path would need an upstream hook or a mixin.
  6. Shared rendering core: Sodium itself, unmodified, is the shared core.
  7. Does it restrict Apple Silicon performance? **[H]** Partially: on the "OPENGL" path Metal has no multi-draw, so the
     backend encodes up to ~1,793 draws per region per pass on the CPU; on unified memory the staging copy is an avoidable
     memcpy + GPU copy; `GPULimits` hard-codes 4 bits of sub-texel precision on macOS. All addressable without forking.
  8. Preserve compatibility surfaces without GL limitations: yes — keeping real Sodium keeps Iris's ~25, Sodium Extra's and
     RSO's mixins into Sodium internals working.

### C.3 Iris 1.11.6

Detail: `notes/iris-arch.md`, `notes/iris-shaders-translation.md`.

- **Hybrid but GL-bound [V]**: uses renderpearl for some allocation and for vanilla/Sodium draws, but all Iris-owned GPU
  work is raw GL: 198 direct LWJGL GL calls, 236 `GlStateManager._*` calls, 207 `IrisRenderSystem` calls, 87 files
  importing `org.lwjgl.opengl`.
- **Program override mechanism [V]**: `ExtendedShader extends GlProgram` (`pipeline/programs/ExtendedShader.java:51`);
  `MixinShaderManager_Overrides` (`:68-110`) builds `GlRenderPipeline`s and splices them into vanilla's compiled pipelines;
  hard cast `(GlDevice)` at `MixinShaderManager_Overrides.java:85` and `gl/IrisRenderSystem.java:580`.
- **The architectural blocker [V]**: after the GL backend binds a pipeline inside a *vanilla* render pass,
  `ExtendedShader#iris$setupState` re-binds Iris's own FBO, uploads loose `glUniform*`, binds textures/images and overrides
  blend. During the shadow pass `MixinGlCommandEncoder` (`:65-110`) suppresses vanilla's framebuffer bind, draw buffers and
  viewport. On Metal, attachments are fixed when the encoder is created, so this "mutate global state mid-pass" model has
  no direct equivalent.
- **Backend check [V]**: `IrisMixinPlugin.java:54` sets `usingVulkan` from the options string and disables every mixin
  except `VKOnly_*` → Iris never initialises on Vulkan and offers to switch back to GL. **On a Metal backend whose option
  string does not contain "vulkan", Iris applies all GL mixins and crashes on the `(GlDevice)` cast [SI].** Metallium
  must handle Iris explicitly from day one (adapter, or a clean refusal with a message).
- **Size [V]**: common module 59,329 LOC: 145 files / 20.3k LOC GL-coupled; 95 files / 9.2k LOC renderpearl-only;
  452 files / 29.8k LOC graphics-API-free (shader patcher ~3.8k, `stareval` custom-uniform engine ~2.6k, pack/properties/
  options ~7k, GUI, shadow culling math).
- **Sodium integration [V]**: 21 mixins into Sodium internals (36-byte extended vertex format, separate shadow render
  lists/batches/UBOs in `RenderSectionManager`, `RenderRegion`, `UniformBufferManager`, `SodiumWorldRenderer`). Sodium's
  terrain shaders are swapped through the same vanilla pipeline-override hook (no `IrisChunkShaderInterface` anymore).
- **Shader transformation [V]**: `TransformPatcher` (`:146-203`) emits GLSL ≥330 core for a GL driver: removes legacy
  built-ins but leaves loose uniforms, unlocated in/out, unbound samplers. `LayoutTransformer` (explicit locations) exists
  but is disabled by the compile-time constant `IrisLimits.VK_CONFORMANCE = false` (`gl/IrisLimits.java:14`).
  `DepthTransformer` (`:62-164`) already rewrites depth to reverse-Z [0,1] when the device reports `isZZeroToOne`.
- **macOS today [SI]**: Iris on macOS GL 4.1 cannot offer compute, SSBOs or image load/store; Iris carries a dedicated
  "macOS is known to have issues with many shader packs" message (`shaderpack/ShaderPack.java:231-233`).
- Dead code noted: `mixins.iris.fantastic.json` never registered; `UndoReverseZTwo..Five`, `MixinTweakFarPlane` unregistered.

### C.4 Sodium Extra 0.9.4 and Reese's Sodium Options

Detail and full table: `notes/sodium-extra-rso.md` §2–§6; summary in §E below.

- 5 option pages, ~60 fixed options + one toggle per particle type, VSync OFF/ON/ADAPTIVE replacing `sodium:general.vsync`.
  45 mixins in a single common config, `defaultRequire 1`. Six declared mixin options have no mixin code [V].
- **No raw GL anywhere [V]**. The only GL-specific feature is Adaptive VSync (mixin on vanilla
  `renderpearl.backend.opengl.GlSurface` + `SDL_GL_*` gated on backend name "OpenGL"); on Metal it is hidden, no crash.
- Uses Sodium internals for fog-shape culling (`RenderSectionManager`, `OcclusionCuller`, `TraversableTree`) and patches
  Sodium's terrain shader source `sodium:blocks/block_layer_opaque` through vanilla `ShaderManager.Configs#getShader` [V].
- RSO replaces Sodium's video settings screen at the end of `VideoSettingsScreen.init`
  (`MixinSodiumOptionsGUI.java:27-34`), builds tabs from `ConfigManager.CONFIG.getModOptions()` (so third-party pages incl.
  a future Metallium page appear), no GPU code [V].
- Bug to avoid: Metallum's cosmetic `SodiumPreferredGraphicsApiMixin` relabels *every* graphics-API value as "Prefer Metal"
  and doesn't apply under RSO, which calls `getElementName` directly (`notes/sodium-extra-rso.md` §0.8).

### C.5 Nvidium

Detail: `notes/nvidium-embeddium-frapi.md` §A.

- Activation [V]: capability check in a mixin on vanilla's GL `GlDevice` constructor requiring `GL_NV_mesh_shader`,
  NV unified memory, `GL_NV_representative_fragment_test`, `GL_ARB_sparse_buffer`, `GL_NV_bindless_multi_draw_indirect`
  (`Nvidium.java:29-37`). On the Vulkan (or any non-GL) backend it never runs → off [SI].
- **With an Iris shader pack active, Nvidium disables itself** and Sodium renders terrain:
  `IS_ENABLED = !FORCE_DISABLE && IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable()`, re-evaluated per new
  `RenderSectionManager` (`IrisCheck.java:13-15`, `MixinRenderSectionManager.java:30-33`) [V]. Its specialised renderer is
  therefore *never* used with shader packs.
- Replaces Sodium's `renderLayer`, `uploadResults`, vertex type, region arenas; keeps Sodium meshing and traversal [V].
- Algorithms: two-phase temporal occlusion (draw last frame's visible set, rasterise region then section boxes with the
  representative fragment test, 8-frame visibility history bytes, subgroup-prefix-sum compaction into per-region
  `glMultiDrawMeshTasksIndirectNV`); 16-byte vertex, 4 per quad, no index buffer; mesh shader 2 threads/quad with sub-pixel
  triangle culling; 80 GB sparse buffer, 1 MB pages [V].
- Portable to Apple GPUs: two-phase temporal occlusion, region/section hierarchy, GPU compaction, face-group culling,
  vertex pulling by primitive ID, GPU pointers (Metal `gpuAddress`). Not portable: representative fragment test (no Metal
  equivalent; a compute Hi-Z is the better fit for TBDR), NV bindless/unified-memory, NVX memory info [SI].
- Cautions [SI]: solid and cutout share one pipeline with `discard` (hurts Apple's hidden-surface removal); the mesh path
  cannot run shader-pack vertex stages.

### C.6 Embeddium and Indium

- Embeddium (1.21.1 NeoForge) is effectively unmaintained (last code commit 2025-02; later commits are bot updates to
  `signatures.json`). NeoForge-specific: ModelData, ChunkRenderTypeSet, NeoForge light pipeline, AddSectionGeometryEvent,
  fluids; FRAPI via an Indigo `BlockRenderContext` subclass. Ideas Sodium 0.9.2 lacks: adaptive CPU render-ahead fencing,
  quad-normal lighting. API ideas worth copying in spirit: `ChunkMeshEvent`/`MeshAppender`,
  `RenderSectionDistanceFilterEvent` [V]. **Not a sensible base for a Fabric 26.3 Metal renderer.**
- Indium is obsolete: Sodium registers its own FRAPI renderer (`SodiumRegistrar` → `Renderer.register(SodiumRenderer.INSTANCE)`,
  `provides: ["indium"]`). Terrain supports per-quad render layer, AO TriState, emissive, shade mode/direction override,
  tint; quad reorientation is an explicit TODO; `animated` flag ignored [V].

### C.7 Metallum + metal-jvm

Detail: `notes/metallum.md`.

- Implements the full renderpearl SPI on Metal; injects `{new MetalBackend()}` as the *only* backend
  (`mixin/PreferredGraphicsApiMixin.java:14-17`): user choice ignored, **no GL fallback, no OS check** since commit `d79ffc1` [V].
- SPIR-V → MSL 4.0 at runtime via LWJGL `org.lwjgl.util.spvc` (Minecraft's own natives), flip-Y, native texel buffers,
  decoration bindings; default Metal compile options (fast-math likely on) [V/SI].
- Binding: no argument buffers; UBOs at buffer slots 0..n-1, push constants at n, vertex buffers after the highest
  vertex-stage slot; textures/samplers own sequence; 64-bit dirty mask [V].
- Pipelines: variants keyed only by attachment presence; depth hard-coded `Depth32Float`; no stencil/MSAA/blend constant/
  disk cache/async creation [V].
- Sync: one command buffer per `submit()`, blocks until submit N-3 completes; all resources untracked; **one global
  `MTLFence` chained through every encoder (serialises all passes)**; every upload/copy ends the render encoder and
  starts a blit encoder; load/store always Load/Store; GPU timestamps faked with `System.nanoTime()` [V].
- Sodium hot spots on this backend [V mechanism, cost unmeasured]: blit encoder per staging copy; new texture view per
  texel-buffer bind; terrain UBO hits whole-buffer copy-on-write.
- Bugs [V/SI]: `D24_UNORM_S8_UINT` mapped to a format Apple GPUs lack; fence wait on unsubmitted batch throws; possible NPE
  in `present()`; MAILBOX → vsync off; push constants capped at 128 B; `Stats` dead.
- metal-jvm: pure Java 25 FFM, calls `objc_msgSend` with critical downcalls, hand-coded arm64 struct ABI, covers classic
  Metal + Metal 4 + MetalFX; no JNI, no bundled natives, no shader compiler [V].
- Assessment: **the right architecture with a prototype-quality implementation.** Reusable (MIT) as a starting point, but
  synchronisation, encoder management, pipeline caching and format handling need redesign.

### C.8 mcopt

Detail: `notes/mcopt.md`.

- Also a full renderpearl Metal backend (`MetalBackend implements GpuBackend`, placed *first* in `getBackendsToTry`, so
  GL/Vulkan remain fallbacks) plus its own terrain renderer; `breaks: sodium` [V].
- Native Obj-C/C `libmcmetal.dylib` built with clang at build time, called via FFM critical downcalls [V].
- Backend techniques worth reusing [V]: consecutive passes with identical attachments share an encoder; clears deferred
  into load actions; store actions decided at encoder end; whole-buffer rewrites redirected to spare memory instead of
  waiting; separate culling command buffer committed before the frame's main one. Weaknesses: 2 frames in flight but the
  render thread blocks on `waitUntilCompleted`; no argument buffers, heaps or binary archives; all buffers shared.
- Terrain [V]: `@Redirect`s `ChunkSectionsToRender.renderGroup` in `LevelRenderer.executeSolid` /
  `executeClassicTransparency`; cancels `SectionCompiler.compile` and re-implements vanilla block/AO meshing; workers write
  16-byte vertices or 40-byte quad records into one shared hazard-untracked arena; GPU compute cull → instanced indirect
  draws; vertex shader pulls from the arena; two-phase GPU occlusion with a compute Hi-Z pyramid. ICBs and mesh shaders
  only behind flags.
- **Flags vs docs [V]**: almost every flag defaults to false in code; real defaults come from the `indie` profile applied
  when no profile is named. `MetalTerrain.OCC = false` and `OwnMaterials.ON = false` are compile-time constants. Several
  thresholds were tuned on only three Macs (5/10/12 GPU cores). LOD (noise-based far terrain) is real but off by default.
- **Benchmark claims are not comparable [V]**: no harness, scripts or data in the repo; with VSync off, `mcopt.metal.pace=true`
  skips presenting all but ~one frame per refresh, so its 1,200–2,000 fps counts frames never displayed while
  vanilla/Sodium present every frame.
- Compatibility: no shader-pack support (runtime removed from the public tree); Iris, Sodium Extra, RSO cannot load.

### C.9 MetalRender

Detail: `notes/metalrender.md`.

- 26.1 branch: whole-frame Metal backend via a mixin on `Minecraft.<init>` putting `MetalGpuBackend` first; GLFW `NO_API`
  + its own `CAMetalLayer` on the NSWindow content view; falls back to `original[0]` if Metal init fails [V].
- Own terrain (cancels vanilla compile, ~4.8k-line mesher, 16/32/64-byte compressed quad tiers in one shared buffer, GPU
  compute frustum + per-group backface culling → indirect args; CPU flood-fill occlusion; Hi-Z/region/visibility-buffer
  shaders compiled but unused) [V].
- **Hand-written MSL per vanilla core shader**, no GLSL→MSL translation; non-vanilla shader namespaces draw solid magenta;
  core-shader resource packs ignored; no Iris; conflicts with Sodium; no FRAPI path; fluids only water/lava [V].
- Ideas worth borrowing: render-pass merging, deferred clears into load actions, depth "don't care" store at present,
  `MTLSharedEvent` serial fences with ≤2 frames in flight, CPU reference mode for GPU culling [V].
- Mostly `require=0` mixins with no `minecraft` upper bound → silent breakage on 26.3 [SI].
- **Licence and owner policy forbid using it as a code source for an AI-assisted project** (§K).

---

## D. Iris compatibility report

### D.1 What must be reproduced (not just "translate GLSL")

From `notes/iris-arch.md` and `notes/iris-shaders-translation.md`:

1. Pack loading, properties, options, includes/preprocessing (JCPP), program sets and fallback chains — **API-neutral, reuse**.
2. GLSL transformation (TransformPatcher, ~4.8k LOC of quirks) — **API-neutral, reuse**; enable `LayoutTransformer`.
3. GLSL → SPIR-V: glslang with **Vulkan-rules-relaxed** (`setEnvInputVulkanRulesRelaxed`): folds loose uniforms into one
   std140 `gl_DefaultUniformBlock` (`glslang/MachineIndependent/ParseHelper.cpp:9590-9645`), auto-maps bindings/locations,
   retargets `gl_VertexID/gl_InstanceID`; drops uniform initialisers (host must apply). Raw pack GLSL cannot go directly
   (legacy built-ins only declared when not targeting SPIR-V, `Initialize.cpp:1797, 4821, 6087`) [V]. Residual legacy
   calls Iris doesn't rename (`texture1D*`, `textureCube*`, `shadow2DProj`, `gl_TextureMatrix[2+]`) and
   `GL_EXT_gpu_shader4 : require` need a Metallium fix-up pass [V].
4. SPIR-V → MSL: SPIRV-Cross. Hard limits: **no geometry shaders** (`spirv_msl.cpp:14681-14721`), no isoline tessellation,
   no doubles (`:17518`), no 64-bit atomics (`:11656`), image atomics native only ≥ MSL 3.1, no `OriginLowerLeft` handling [V].
5. Conventions: depth already handled by Iris (`DepthTransformer`) — do **not** also enable `fixup_clipspace`. Y-orientation
   is handled nowhere: needs vertex Y-flip, `gl_FragCoord.y` rewrite, `dFdy` negation, flipped winding, converted
   viewport/scissor (reasoned, untested) [SI].
6. Host runtime (the larger part): uniform-buffer writer keyed by reflected offsets (replacing ~150–190 `glUniform*`
   uploads); sampler/image/SSBO binding by reflection; render targets and ping-pong flipping; MRT render-pass descriptors
   per Iris pass; clears as load actions; mip generation; shadow maps with compare samplers; compute dispatch incl.
   indirect; per-buffer blending; custom textures (PNG/raw 1D/2D/3D); `StandardMacros` GL vendor/version/extension defines
   must be faked plausibly.
7. **The pass-model mismatch**: Iris's "rebind FBO + state after vanilla bound its pipeline" must become "decide the
   attachments when the render pass is created". This needs a Metallium hook at `createRenderPass` that asks the active
   Iris pipeline for the attachment set of the current phase, plus a hook at pipeline bind time for Iris's uniforms and
   resources. This is the key adapter and the main reason a plain GL-emulation layer is the wrong tool.

### D.2 Approach comparison

| | A: Adapt Iris | B: Native reimplementation | C: GL-on-Metal translation layer |
|---|---|---|---|
| What changes | Replace ~20k GL-bound LOC (145 files) behind an abstraction; keep ~39k neutral LOC | Write pack loader, options, transformer, uniforms, pipeline from scratch | Emulate ~120 distinct GL entry points Iris uses, plus GL state machine, under unmodified Iris |
| Pack compatibility ceiling | Highest: identical parsing, fallbacks and patcher quirks | Lower for years: must rediscover ~4.8k LOC of patcher quirks and pack-loading behaviour | High for features GL-emulation covers; mid-pass FBO rebinding forces pass splitting on every rebind |
| Performance | Good: native Metal passes, explicit attachments | Good | **Poor-to-unknown**: render-pass splits on rebinds (TBDR load/store traffic), state-tracking overhead, implicit sync |
| Maintenance | Fork/patch-set vs fast-moving Iris; mitigated by upstreaming an abstraction | Track Iris + pack ecosystem behaviour forever | Track GL semantics Iris uses + Iris changes; hardest to debug |
| Licence | LGPL-3.0 for the adapter/fork; don't bundle glsl-transformer (use Iris's) | Clean if no code copied | Clean layer; Iris unmodified |
| Verdict | **Recommended core** | Rejected for v1 | Rejected as primary; small shims acceptable |

### D.3 Recommendation: hybrid A + targeted C

1. **Reuse unchanged**: Iris pack loading, options/menus, program sets/fallbacks, `stareval` custom uniforms, uniform value
   providers, shadow culling, the GLSL transformer (with `LayoutTransformer` on).
2. **Replace (adapter, LGPL module)**: Iris's GL execution layer — program objects, uniform upload, sampler/image/SSBO
   binding, FBO/render-target management, composite/deferred/final/compute execution — with a Metal implementation that
   calls Metallium's **extension API** (§13 below), sharing Metallium's `MTLDevice`, command buffers and resource lifetime.
3. **Hooks in Metallium's backend**: `createRenderPass` attachment override, pipeline-bind resource injection, shadow-pass
   viewport/renderArea control, pipeline compile substitution (Iris MSL instead of vanilla/Sodium MSL for gbuffer/shadow
   programs).
4. **Small C-style shims** only where Iris code reads GL capabilities or vendor strings (`FeatureFlags`, `StandardMacros`).
5. **Upstream strategy**: propose to Iris an `IrisRenderSystem`-level backend interface (it is already a partial choke
   point with 207 call sites). Until accepted, maintain a fork pinned per Minecraft version.

### D.4 Feature-level compatibility matrix (via the recommended path)

Source: `notes/iris-shaders-translation.md` §8 (with the Metallium host runtime assumed).

| Feature | Classification | Technical reason |
|---|---|---|
| gbuffers/shadow/composite/deferred/final VS+FS | Supportable with adapter | Iris transform + glslang relaxed + SPIRV-Cross + Y fix-ups |
| Legacy GLSL 1.10–1.50 packs | Supportable with adapter | Iris upgrades to 330 core; residual legacy calls need fix-up pass |
| Core-profile packs | Supportable with adapter | `*CoreTransformer` paths exist |
| Built-in + custom uniforms (loose) | Requires Iris modification | Iris uploads via `glUniform*`; needs reflected UBO writer |
| Samplers (colortex/depthtex/shadowtex/noisetex/custom) | Requires Iris modification + native impl | Name→binding reflection; Metal 16-sampler-state limit needs constexpr samplers or argument buffers [U limit value] |
| MRT, DRAWBUFFERS / RENDERTARGETS | Native implementation | Render-pass descriptor per pass; flip logic stays in Iris |
| colortex formats (4-component, packed) | Fully supported | Direct Metal formats |
| 3-component formats (RGB8/RGB16F/RGB32F) | Adapter | Pad to RGBA + swizzle |
| Clears, mipmapped buffers | Native implementation | Load actions; blit `generateMipmaps` |
| Shadow maps + hardware PCF | Adapter | `depth2d` + compare sampler; Iris depth conventions |
| Custom textures PNG/raw 2D/3D; raw 1D/rectangle | Native impl; adapter for 1D/rect | Upload/repack; 1D as 2D |
| Compute shaders (incl. indirect dispatch) | Native implementation | Kernel translation reliable; dispatch/UAV/barriers are new host code |
| Image load/store, SSBOs, 32-bit atomics | Native implementation | RW textures/device buffers; per-format RW tiers [U] |
| Image atomics | Supported on MSL ≥ 3.1 | Emulation below 3.1 fragile |
| Per-buffer blending, alpha-test override, wide lines | Native / fully supported | Per-attachment PSO blend; Iris does alpha test and line widening in shader |
| `gl_Layer` (VS-only path) | Adapter | VS layer output translates; GS layering doesn't |
| **Geometry shaders** | **Unsupported (v1)** | SPIRV-Cross has no GS; only bespoke compute/mesh emulation |
| **Tessellation** | Native implementation, very high effort — defer | Metal compute-based tessellation; isolines infeasible |
| **Doubles, 64-bit atomics** | **Infeasible** | Not expressible in MSL via SPIRV-Cross |
| Logic ops | Infeasible / unused by Iris | No Metal equivalent |
| Distant Horizons programs | Not verified | DH's own GL renderer is the bigger obstacle |
| `iris.features.required` reporting | Requires Iris modification | FeatureFlags read GL caps |
| Precision parity | Native (config) | Compile pack MSL with safe math, not fast-math |
| Compile time / caching | Native implementation | glslang + spvc + Metal per program; needs disk cache |

**Unverified and important**: how many popular packs (BSL, Complementary, Photon, Bliss, SEUS PTGI, Rethinking Voxels)
require geometry/tessellation shaders. No pack sources were in scope. This must be measured before promising coverage
numbers (§N).

**Diagnostics requirement**: unsupported features must be detected at pack load (shader stage presence, `double`/int64
use from SPIR-V capabilities, SPIRV-Cross exceptions) and reported per program with a clear message and a fallback to
"shaders off", never a crash or silent black screen.

---

## E. Sodium Extra feature inventory

Full per-row evidence: `notes/sodium-extra-rso.md` §5. "S1" = Metallium keeps Sodium and swaps the backend (recommended).

| Feature | Original implementation | Dependencies | Works unchanged? (S1) | Adapter needed? | Native implementation needed? | Recommended strategy |
|---|---|---|---|---|---|---|
| Option pages / config storage | `SodiumExtraConfig`, `SodiumExtraGameOptions` | Sodium config API + Sodium internals (`ConfigManager`, `structure.*`) | Yes | No | No | Keep Sodium loaded (hard dep) |
| VSync OFF/ON | `SodiumExtraConfig.java:938-988` | Sodium option `sodium:general.vsync` | Yes | No | No | Keep |
| Adaptive VSync | `adaptive_sync/MixinGlSurface`, `SDL_GL_*` gated on "OpenGL" | Vanilla GL backend class | Hidden on Metal (no crash) | Optional small mixin | Optional: Metal FIFO_RELAXED analogue | Accept loss in v1; add Metallium present-mode support later |
| Reduce resolution on macOS | `reduce_resolution_on_mac/MixinWindow` | Vanilla `Window`, SDL3 flags | Yes (if backend ORs window flags, as Metallum does) | No | No | Keep; verify drawable `contentsScale` |
| Fog distance / start / off, per-dimension | `fog/*` mixins, `FogDistanceHelper` | Vanilla `FogData` | Yes | No | No | Keep |
| Fog shape (cylindrical/radial/planar) | Offset-band encoding in `FogData` + text patch of `sodium:blocks/block_layer_opaque` | Sodium shader source + `ShaderManager.Configs#getShader` | Yes, if backend compiles the patched GLSL (it arrives as SPIR-V from the frontend) | No | No | Keep; add to backend shader test suite |
| Cylindrical fog cull expansion | Mixins on `RenderSectionManager`, `OcclusionCuller`, `TraversableTree` | Sodium internals | Yes | No | No | Keep |
| Cloud fog %, protected gameplay fog | Fog environment mixins; Greenlight policy | Vanilla; Greenlight API (bundled) | Yes | No | No | Keep |
| Cloud height | `cloud/MixinLevelRenderer` | Vanilla `CloudRenderer` | Yes | No | No | Keep |
| Sky/sun/moon/stars/sunrise/end sky toggles; sky colours | `sky/*`, `sky_colors/*` | Vanilla | Yes | No | No | Keep |
| Biome colours | `biome_colors/MixinBiomeColors` | Vanilla color providers (used by Sodium mesher) | Yes | No | No | Keep |
| Rain/snow toggle | `particle/MixinLevelRenderer` | Vanilla `WeatherEffectRenderer` | Yes | No | No | Keep |
| Particles (all / per-type / splash / block break / firework) | `particle/*` | Vanilla (CPU) | Yes | No | No | Keep |
| Texture animation toggles | `animation/*` | Vanilla atlas ticking | Yes | No | No | Keep |
| Light updates toggle | `light_updates/MixinLevelLightEngine` | Vanilla (CPU) | Yes | No | No | Keep |
| Entity toggles (item frames, armor stands, paintings, name tags) | `render/entity/*` | Vanilla | Yes | No | No | Keep |
| Block-entity toggles (beacon beam/height, enchanting book, pistons) | `render/block/entity/*` | Vanilla | Yes | No | No | Keep |
| FPS / extended FPS / coords overlay | `gui/*`, `fps/*`, `SodiumExtraHud` | Vanilla GUI | Yes | No | No | Keep |
| Debug screen entries, steady debug HUD | `SodiumExtraClientMod`, `steady_debug_hud/*` | Vanilla debug API | Yes | No | No | Keep |
| Toasts (all/advancement/recipe/system/tutorial) | `toasts/*` (synthetic `lambda$update$1` target) | Vanilla | Yes [U: synthetic target must match 26.3] | No | No | Keep; startup test |
| Instant sneak | `instant_sneak/MixinCamera` | Vanilla | Yes | No | No | Keep |
| Prevent (spectator) post shaders | `prevent_shaders/MixinGameRenderer` | Vanilla | Yes | No | No | Keep |
| Panini projection | `panini_projection/*`, `panini.fsh` + `post_effect/panini.json` | Vanilla PostChain, `CommandEncoder#writeToBuffer`; Iris API (skips when pack active) | Yes, if backend compiles mod post shaders and supports COPY_DST uniform writes | No | No | Keep; add to backend tests |
| Advanced item tooltips, "More HUD overlays" | Vanilla options | Vanilla | Yes | No | No | Keep |

Features the brief listed that **do not exist in the 0.9.4 source** [V]: reduced light flicker, entity-culling distance,
render-distance extension, cloud distance. Six mixin option keys are declared without mixin code.

Mod-level policy: other mods can disable Sodium Extra mixin groups via `custom."sodium-extra:options"` in their
`fabric.mod.json`. Metallium can use this to switch off `mixin.adaptive_sync` cleanly if needed.

**Recommendation**: Option A (run Sodium Extra unchanged) for every feature. No native reimplementation. The only work is
backend conformance (tested by the Sodium Extra rows above) and an optional Metal present-mode for Adaptive VSync.

**Reese's Sodium Options**: preserve unchanged; do not fork or replace. Metallium registers its options through Sodium's
public config API so they appear in both Sodium's and RSO's screens. Label graphics-API options at the option level
(`getElementName`), not via a GUI-element mixin. Version pinning (RSO-dev requires Sodium 0.9.3-beta.1) is an ecosystem
issue to track, not a Metallium design issue.

---

## F. Metal renderer comparison

| Area | MetalRender (26.1) | Metallum | mcopt | Sodium-on-renderpearl Metal backend | Recommended Metallium design |
|---|---|---|---|---|---|
| Integration point | Vanilla backend list (mixin on `Minecraft.<init>`), own terrain | renderpearl SPI, Sodium terrain | renderpearl SPI, own terrain, breaks Sodium | renderpearl SPI | **renderpearl SPI**, mixin on `getBackendsToTry`, Metal first + GL fallback |
| Native layer | JNI + Obj-C++ dylib (prebuilt checked in) | metal-jvm (pure FFM) | Obj-C/C dylib via FFM | — | **FFM** (metal-jvm style, no JNI, no shipped dylib needed); consider a thin Obj-C helper only if FFM struct/block handling proves costly [H] |
| Window/surface | GLFW NO_API + own CAMetalLayer | SDL3 `SDL_Metal_CreateView` | SDL3 `SDL_Metal_CreateView` | — | SDL3 Metal view (matches 26.3) |
| Shaders | Hand-written MSL per core shader | SPIR-V→MSL 4.0 via spvc | SPIR-V→MSL 3.0 via spvc | Vanilla SPIR-V | spvc from vanilla SPIR-V; MSL version by OS capability; **disk cache + binary archives** |
| Resource binding | Hand-assigned | Fixed slots, dirty mask | Fixed slots | — | Fixed slots for vanilla (simple, cheap); argument buffers for Iris programs exceeding slot limits [H] |
| Pipelines | Synchronous, no cache | Synchronous, no cache, depth hard-coded D32F | Synchronous | — | Async compile honouring frontend contract, pipeline-state cache keyed on full state + attachment formats |
| Command submission | Merged passes, deferred clears | One CB per submit, **global fence serialising all encoders**, blit encoder per upload | Merged passes, deferred clears, store at end | — | One CB per frame (+ early CB for culling/uploads), merged passes, deferred clears, batched uploads in one blit at frame start, store/load chosen per pass |
| Synchronisation | `MTLSharedEvent` serials, ≤2 frames | Semaphore N-3, untracked resources | 2 frames, `waitUntilCompleted` on render thread | — | `MTLSharedEvent` timeline, 2–3 frames, no render-thread `waitUntilCompleted`; hazard model that provably matches renderpearl's "ordered" contract |
| Resource lifetime | Fence-tracked | Destruction ring of 3 | Spare-memory rewrite | — | Deferred destruction keyed on submit serial; transient ring allocator |
| Terrain | Own mesher, GPU frustum cull, CPU occlusion | Sodium (per-draw loop) | Own mesher, GPU two-phase Hi-Z | Sodium | Sodium + indirect path → later ICB/GPU culling (benchmark-gated) |
| Shader packs | None | None (Iris would crash) | None | None by itself | Iris adapter (§D) |
| Mod compatibility | Low (no Sodium/FRAPI, magenta mod shaders) | High (anything on renderpearl) | Low (no Sodium) | High | High |
| Fallback | Falls back to GL | None, no OS check | GL/Vulkan remain in list | — | Metal first, GL fallback; refuse on non-Apple/Intel Macs |
| Maintainability | Low (no tests/CI, 74 "fix/oops" commits) | Small, readable, prototype | Large flag surface, profile-driven | — | Small backend core with conformance tests |
| Licence | Copyleft custom + AI ban | MIT | Apache-2.0 (from 0.3.0-alpha.1) | — | Apache-2.0 core (proposal, §K) |

**Reuse / redesign / avoid**

- **Reuse (code, with attribution)**: metal-jvm bindings (MIT); Metallum's renderpearl SPI skeleton and SPIR-V→MSL setup
  (MIT) as a starting point; mcopt's encoder-merging, deferred-clear, store-at-end and spare-memory techniques (Apache-2.0,
  only from commit `09fea99` onward).
- **Redesign**: synchronisation (global fence, N-3 semaphore, blocking waits), upload path (per-upload blit encoders),
  pipeline compile/caching, format table (D24S8, depth formats), present modes.
- **Avoid**: replacing Sodium's terrain renderer (kills Iris/Sodium Extra/RSO); hand-written MSL per vanilla shader
  (breaks every mod pipeline and resource-pack shader); "paced present" that skips presentation; profile-driven hidden
  defaults; any MetalRender code.

---

## G. Performance strategy (Apple Silicon)

All items are **[H]** (no measurements yet). Hardware facts marked **[ext]** come from Apple's public Metal documentation
and general knowledge, not from code in the audited repos; they must be confirmed per GPU family at runtime.

### G.1 Ranked highest-value opportunities

1. **Backend submission efficiency** (affects every frame, every mod, shaders on or off). One command buffer per frame;
   merge consecutive passes with identical attachments; clears as load actions; `dontCare`/`memoryless` where a target is
   never read back (TBDR bandwidth); batch all uploads into one blit encoder per frame instead of splitting render encoders;
   remove the global fence chain; never block the render thread on `waitUntilCompleted`. Evidence: Metallum's current
   design does the opposite on each point (`notes/metallum.md` §4–§6); mcopt/MetalRender show the techniques work.
2. **Terrain draw submission**. Sodium's "OPENGL" path costs one `drawIndexedPrimitives` per section. Steer Sodium onto its
   indirect batch (record layout already equals Metal's indirect args) via a small mixin, then encode with
   `drawIndexedPrimitives(indirectBuffer:)` loops, later **indirect command buffers** to remove per-draw CPU encoding.
3. **Pipeline compilation**: async compile per frontend contract, persistent MSL/SPIR-V hash cache, `MTLBinaryArchive`
   for pipeline states. Kills resource-reload and shader-pack-load stalls (~150 vanilla pipelines + Sodium + mod pipelines
   + Iris programs).
4. **Unified memory**: skip Sodium's staging memcpy + GPU copy when the destination is shared storage and not in use by
   in-flight frames (needs careful hazard tracking); keep large static data in private storage only if measurements show a
   GPU-side win **[ext]**.
5. **Hidden-surface removal friendliness**: keep opaque and cutout in separate pipelines (no `discard` in opaque), avoid
   shader depth writes outside the passes that need them (vanilla OIT requires `gl_FragDepth` on Apple Silicon).
6. **GPU-driven culling** (two-phase temporal Hi-Z in compute, à la mcopt/Nvidium): large potential at high render
   distance, but competes with Sodium's CPU graph culling and Sodium Extra's cull mixins, and Iris's shadow pass needs its
   own visibility. Benchmark-gated, phase 6.
7. **Shader-pack GPU cost**: render-target format choice (avoid needless RGBA32F), avoid redundant full-screen passes,
   compile with safe-but-not-slow math, use lossless compression-friendly formats **[ext]**.
8. **Thread QoS** (`pthread_set_qos_class_self_np` user-interactive for the render thread), as mcopt does.

### G.2 Likely CPU bottlenecks

Per-draw encoding of terrain sections; per-draw binding (UBO slices, texel-buffer views recreated per bind); pipeline
creation on the render thread; FFM call overhead per Metal call (critical downcalls help) **[H]**; Sodium chunk meshing and
translucency sorting (unchanged by Metal); Iris uniform evaluation each frame (`stareval`); render-thread waits on GPU.

### G.3 Likely GPU bottlenecks

Fill rate and bandwidth in shader packs (many full-screen passes, high-precision MRT); shadow-map passes re-rendering
terrain; overdraw from cutout with `discard`; load/store traffic from split render passes; OIT's RGBA32F MAX-blend target.

### G.4 Memory and synchronisation risks

Untracked resources + reordering = GPU use-after-free or stale reads; close-while-in-flight semantics; mapped ring buffers
rewritten while frames are in flight; transient-memory exhaustion; arena growth copies (mcopt holds a global lock and
hard-fails above 3 GB); memory pressure on 8 GB Macs with shader packs; pipeline cache growth.

### G.5 Features that help performance but can hurt compatibility

Replacing Sodium's terrain renderer; mesh-shader terrain (cannot run pack vertex shaders unchanged); skipping frames at present;
fast-math for pack shaders; aggressive GPU occlusion that disagrees with mods querying Sodium's visibility; skipping
vanilla `ChunkSectionsToRender` (breaks Fabric `LevelRenderEvents`).

### G.6 Features that help both

Async + cached pipelines; correct render-pass merging; batched uploads; indirect draws through Sodium's own batches;
a Metal path that unlocks compute/SSBO/image packs that macOS GL 4.1 cannot run at all.

### G.7 Metal mesh shaders — decision

- **[ext]** Metal mesh shaders (object + mesh functions, Metal 3) are available on Apple-family GPUs from M1; hardware
  acceleration of mesh shading was introduced with M3/A17 Pro. Treat both as unverified until queried at runtime.
- **Not in v1.** Reasons: (1) shader packs supply their own vertex stages, and a mesh-shader terrain path would have to
  replace them — Nvidium has the same limitation and disables itself whenever an Iris pack is active (§C.5);
  (2) compute culling + indirect draws work on every Apple Silicon GPU, with or without packs; (3) the benefit over a good
  indirect path is unmeasured.
- **Possible later (phase 6, optional)**: a mesh-shader terrain path for shaders-off rendering on M3+, enabled only if
  benchmarks beat the indirect path.
- **Shader packs and mesh shaders [H]**: a pack's vertex shader cannot run *unchanged* in a mesh pipeline, but it could in
  principle be translated into a function invoked per vertex from a Metallium mesh shader (adding per-quad culling in
  front of the pack's code). No project does this; it is unproven and adds per-pack risk. It would also target the
  smaller cost: with packs, GPU time is dominated by fragment work, full-screen deferred/composite passes and the shadow
  pass. Compute culling + indirect draws give most of the culling benefit (including for the shadow pass) while running
  the pack's vertex shader as written, so they are the recommended path for packs.

### G.8 Benchmarks needed per optimisation

Each optimisation needs: a fixed seed/world/camera path; frame-time distribution (median, p95, p99, max), CPU main-thread
time, GPU frame time (Metal counters/`MTLCounterSampleBuffer`), memory over time; compared against the same build with the
optimisation disabled, and against baselines vanilla-GL, vanilla-Vulkan (MoltenVK), Sodium-GL. Detail in §L.

### G.9 Staged optimisation strategy

Correct first → backend overhead → terrain submission → pipeline caching → shader-pack costs → GPU culling → mesh shaders
(optional). Do not start a stage without the previous stage's benchmark baseline.

---

## H. Compatibility matrix

| Mod / area | Expected status | Reason / evidence | Confidence |
|---|---|---|---|
| Vanilla rendering (all `RenderPipeline`s, post effects, OIT) | Works unchanged if backend conformant | Vanilla is backend-neutral (`notes/vanilla-renderpearl.md` §10) | SI |
| Sodium 0.9.2 | Works unchanged | Metallum precedent; no raw GL in render path (`notes/sodium.md` §0) | V (call path), runtime U |
| Sodium Extra 0.9.4 | Works unchanged except Adaptive VSync | `notes/sodium-extra-rso.md` §5 | V |
| Reese's Sodium Options | Works unchanged (version pairing caveat) | Pure GUI; consumes Sodium config API | V |
| Fabric Rendering API (in Sodium) | Works unchanged | Sodium's FRAPI is backend-neutral | V |
| Indium | n/a | Superseded | V |
| Fabric API rendering modules (`LevelRenderEvents`, HUD, fluids, model loading) | Works unchanged | No raw GL; events fire if vanilla `LevelRenderer` flow and Sodium's `ChunkSectionsToRender` subclass are kept | V/SI |
| **Iris + shader packs** | **Requires native adapter + Iris fork/upstream change** | §D; without it Iris crashes on `(GlDevice)` cast | V/SI |
| Iris shaders-off | Requires adapter (Iris must not apply GL mixins) | `IrisMixinPlugin` keys on option string | V |
| Nvidium | Inactive (incompatible by design) | Requires NVIDIA GL extensions | V |
| Embeddium-dependent mods | n/a on Fabric | NeoForge-only | V |
| Distant Horizons | Not verified | Metallum/mcopt pretend "Vulkan"; DH's actual renderer path unaudited | U |
| Mods that call LWJGL OpenGL directly | Incompatible | No GL context exists on the Metal backend | V (by construction) |
| Mods with custom `RenderPipeline`s / GLSL shaders | Works if shaders fit renderpearl's resource model | Frontend compiles to SPIR-V for any backend | SI |
| Connected textures / material mods via FRAPI or model API | Expected to work | FRAPI per-quad properties supported by Sodium (quad reorientation TODO) | SI |
| Dynamic lighting mods | Not verified | Usually mixin into Sodium/vanilla lighting (CPU) — likely fine; GL-calling variants break | U |
| Particle/visual-effect mods | Mostly works if renderpearl-based | Same as custom pipelines | SI |
| Minimap/world overlays | Not verified | Those using renderpearl/GUI APIs work; raw-GL framebuffer readers break | U |
| Screenshot/recording tools | Vanilla screenshot works if `copyTextureToBuffer` implemented; external GL-hooking recorders break; macOS screen capture works | SI/U |
| Resource packs (textures, models) | Works | CPU/asset side | SI |
| Resource packs with core-shader overrides | Works if GLSL fits renderpearl model | Frontend recompiles overrides to SPIR-V | SI |
| mcopt, MetalRender, Metallum | Incompatible (competing backends) | Each injects its own `GpuBackend` | V |
| Chunk-loading performance mods (non-render) | Expected to work | Not graphics-bound | SI |
| macOS Vulkan (MoltenVK) via vanilla | Coexists (user choice) | Vanilla tries GL first; Metallium should respect an explicit Vulkan choice | V/SI |

Shader-feature compatibility: see §D.4.

---

## I. Architecture decision

### I.1 Strategy comparison

| Criterion | A: Sodium-based (re-scoped: renderpearl Metal backend under unmodified Sodium) | B: Custom Metal renderer (replace terrain) | C: Metal + Iris compatibility layer | D: Dual rendering modes | E: Native shader-pack runtime |
|---|---|---|---|---|---|
| Vanilla perf potential | Good; bounded by Sodium's architecture until indirect/ICB/GPU-culling work | Highest | Same as A | Highest in native mode | n/a |
| Shader-enabled perf | Good (native Metal passes via adapter) | Poor (no packs) | Good | Good in compat mode | Good |
| Iris pack compatibility | Needs C | None (mcopt, MetalRender prove the loss) | High (adapter) | High in compat mode only | Lower for years |
| Sodium Extra | Full | Lost (Sodium excluded) or partial native rewrite | Full | Full in compat mode | n/a |
| Other rendering mods | High | Low | High | Split behaviour per mode | n/a |
| Initial effort | Moderate | High | High | Very high | Very high |
| Maintenance | Low–moderate (vanilla SPI churn) | High | High (Iris fork) | Two renderers × test matrix | Highest |
| MC upgrades | Port SPI per version | Port everything | Port SPI + Iris adapter | Both | Track pack conventions forever |
| Regression risk | Low (shared upstream code) | High | Moderate | High | High |
| Upstream benefit | Gets Sodium/Iris/vanilla improvements automatically | None | Partial (Iris front-end) | Partial | None |
| Licensing | Clean (no Sodium code) | Clean | LGPL for adapter | Mixed | Clean |
| Small-team feasibility | **Yes** | Marginal | Yes, staged | No | No |

### I.2 Decision

**Primary: A (re-scoped) + C (hybrid Iris adapter).** Build the Metal renderpearl backend first, keep Sodium, Sodium Extra
and RSO unmodified, and design the backend from day one with the hooks the Iris adapter needs (render-pass attachment
override, pipeline-bind resource injection, viewport/renderArea control, compute and storage resources in the extension
API). This avoids "building an incompatible renderer first and bolting Iris on later".

**Rejected / reserved**

- **B rejected** for v1: mcopt and MetalRender demonstrate exactly the trade — high vanilla fps, zero shader packs,
  no Sodium ecosystem. The brief ranks pack compatibility above raw fps.
- **D reserved, in a reduced form**: the "compatibility mode" already exists for free — vanilla's GL backend as a fallback
  entry in `getBackendsToTry`. A second Metal *terrain* renderer is not justified until benchmarks show Sodium-on-Metal with
  indirect/ICB/GPU culling is the bottleneck. If ever built, it must be an internal acceleration of Sodium's draw step, not
  a user-visible mode.
- **E rejected** as a standalone runtime; its useful parts (native Metal pass execution) are the Iris adapter's back half.

---

## J. Proposed module structure

```
metallium/
├─ metallium-metal            Metal FFM bindings (metal-jvm, MIT, vendored or dependency)
├─ metallium-backend          renderpearl SPI on Metal  ← required for v1
│   ├─ device, encoder, render pass, surface, pipeline
│   ├─ resource lifetime / transient memory / submit timeline
│   └─ format & state translation tables
├─ metallium-shaders          SPIR-V→MSL (spvc), GLSL→SPIR-V (glslang/shaderc via LWJGL) service, caches, diagnostics
├─ metallium-api              stable public API: capability queries, extension API (compute, storage buffers/images,
│                             compare samplers, viewport/renderArea, render-pass hooks), Metal handle interop
├─ metallium-config           options registered through Sodium's public config API (vanilla options fallback)
├─ metallium-diagnostics      GPU capture toggle, Metal validation, counters, shader-failure reports, crash context
├─ metallium-compat-sodium    optional: indirect draw-path steering, Sodium label fixes
├─ metallium-compat-iris      Iris adapter (LGPL-3.0 if derived from Iris) ← phase 5
├─ metallium-compat-misc      optional small adapters (Distant Horizons etc., each behind mod-presence checks)
├─ metallium-testmod          dev-only: synthetic pipelines, synthetic shader-pack programs, conformance scenes
└─ metallium-bench            dev-only: benchmark harness, camera paths, result export
```

| Module | Responsibilities | Public interface | Depends on | Must not depend on | Independent testing | Phase |
|---|---|---|---|---|---|---|
| metallium-metal | Thin Metal/ObjC FFM layer | Java wrappers for MTLDevice etc. | JDK FFM | Minecraft | Unit tests on macOS CI (headless Metal device) | 1 |
| metallium-backend | Implement `GpuBackend` + 5 SPI interfaces; fallback to GL | none (internal) | metal, shaders, vanilla renderpearl | Sodium, Iris, any mod | Renderpearl conformance scenes vs GL reference images | 1–3 |
| metallium-shaders | SPIR-V→MSL, GLSL→SPIR-V for adapters, caching, reflection | `ShaderTranslator` | LWJGL spvc/shaderc (vanilla's natives) | Minecraft game classes | Golden-file translation tests | 1, extended in 5 |
| metallium-api | Versioned public API + capability queries | `MetalliumApi`, `MetalliumCapabilities`, extension interfaces | backend (impl side only) | compat modules | API contract tests | 2–5 |
| metallium-config | Options UI/storage | config entrypoint | Sodium config API (optional) | Iris | Unit tests | 2 |
| metallium-diagnostics | Debug/validation/telemetry | log + overlay hooks | backend | compat modules | — | 2 |
| metallium-compat-sodium | Indirect path steering | none | Sodium (mixins), api | Iris | Scene tests with Sodium | 4/6 |
| metallium-compat-iris | Iris execution layer on Metal | none | Iris, api, shaders | backend internals | Synthetic pack suite + real packs | 5 |
| metallium-testmod / bench | Dev tooling | — | everything | — | — | 1+ |

Renderer selection: Metallium inserts `MetalBackend` first in `getBackendsToTry` only on Apple Silicon macOS and only when
the user's preference is DEFAULT or an explicit Metallium option says "Metal"; an explicit OPENGL/VULKAN preference is
respected; Metal init failure falls back to GL with a visible notice. No dual Metal modes.

---

## K. Licensing report

Not legal advice. Facts are verified from licence files; interpretations are labelled.

| Source | Licence (verified) | Can Metallium reuse code? | Obligations / notes |
|---|---|---|---|
| Sodium | PolyForm Shield 1.0.0 (`LICENSE.md:54-68`: "Any purpose is a permitted purpose, except for providing any product that competes with the software…"; competing includes "different technical platforms"; "If you market a product as a practical substitute… it definitely competes") | **No.** Ideas only. Runtime dependency + mixins: **probably fine** (Metallium requires and complements Sodium) — confirm with CaffeineMC | Never copy Sodium code. FRAPI/Indigo-derived files: take from FabricMC (Apache-2.0) instead |
| Iris | LGPL-3.0 | Yes, under LGPL | Adapter/fork module must be LGPL-3.0, source available, users able to replace it |
| glsl-transformer | AGPL-3.0 | Avoid bundling | Use the copy Iris already ships at runtime |
| Sodium Extra | LGPL-3.0 | Not needed (runs unchanged) | If fog-shape logic is ever needed natively, reimplement from the observable contract |
| Reese's Sodium Options | MIT | Not needed | — |
| Nvidium, Embeddium | LGPL-3.0 | Not needed; algorithms re-implemented for Metal | Credit ideas |
| Indium, Fabric API | Apache-2.0 | Yes | Keep NOTICE/headers |
| Metallum, metal-jvm | MIT ("Copyright (c) 2026 kokodio") | **Yes** | Keep copyright + licence text |
| mcopt | Apache-2.0 + NOTICE requesting credit | **Yes, only from commit `09fea99` (0.3.0-alpha.1) onward**; earlier history contains Sodium (PolyForm Shield) code | Keep NOTICE; state changes |
| MetalRender | Custom "Pebbles_boon Software Licence" (copyleft: derivatives under same licence, source for binaries, modification notices, "NOT an Official Release"); repo AGENTS.md: owner bans AI use, "All rights reserved" | **No.** Ideas only, credited | The Apache-2.0 `1.21.8-stable` branch is legally reusable but has little of value |
| SPIRV-Cross, glslang | Apache-2.0 / BSD-family | Use via LWJGL natives vanilla already ships | — |
| Minecraft | Mojang EULA | No | Decompiled only for research |

**Proposed Metallium licence [H]**: Apache-2.0 for core modules (compatible with MIT and Apache sources, patent grant);
`metallium-compat-iris` as LGPL-3.0 in a separate jar. Owner decision.

**Open licensing questions**: whether CaffeineMC considers a Metal backend that requires Sodium a "competing product";
licence of Sodium Extra's bundled CaffeineConfig code; Greenlight internals.

---

## L. Testing and benchmarking plan

### L.1 Functional correctness

- **Backend conformance scenes** (testmod): every vanilla `RenderPipeline` drawn into offscreen targets; compare against
  the GL backend with per-pixel tolerance. Covers blend modes, depth tests, cull/winding, scissor, Y orientation, MRT,
  depth-only passes, TRIANGLE_FAN, texel buffers, mip levels, readback.
- World scenarios: superflat, plains, dense forest, ocean/water, nether, end, caves, villages, large builds; render distance
  2–32+; translucency (glass, ice, water, stained glass stacks) in classic and OIT modes; entities, block entities,
  particles, weather; resource-pack reloads (textures and core-shader overrides); world joins/leaves, dimension changes;
  window resize, fullscreen, Retina vs reduced resolution; long sessions (memory growth).
- Failure handling: Metal init failure → GL fallback; shader compile failure → clear error; device loss (not expected on
  Metal) → crash report with context.

### L.2 Shader-pack compatibility

- **Synthetic pack suite** (written by us, licence-clean): one minimal pack per feature in §D.4 (shadow, deferred,
  composite, MRT, depth sampling, each blend mode, options, formats incl. 3-component, compute, images, SSBO, custom
  textures, unsupported features → expected diagnostic).
- **Real packs locally** (only where pack licences permit; never redistributed): a spread across architectures — e.g.
  Complementary, BSL, Photon, Bliss, Sildur's, a voxel/PTGI-style pack. Record exact versions. Report per pack: loads /
  renders correctly / visual diffs vs Iris-on-GL (Linux/Windows reference capture) / unsupported features.
- Reload and switching: load → change options → switch pack → disable → re-enable, checking leaks.

### L.3 Mod compatibility

Matrix of Metallium + {Sodium, Sodium Extra, RSO (matching version), Iris adapter, FRAPI-heavy mods, popular rendering
mods}. Record exact versions; separate "launches" from "functions correctly".

### L.4 Performance

- Hardware: at least M1 (base), M1/M2 Pro, M3 or newer (mesh-shader acceleration), an 8 GB machine.
- Baselines: vanilla GL, vanilla Vulkan (MoltenVK), Sodium on GL, Sodium + Iris on GL — same world, seed, camera path,
  settings, VSync off **with every frame presented** (unlike mcopt's paced present).
- Scenes: static vista RD 12/32, fly-through with chunk generation, dense forest, city build, modded scene, shaders off/on
  at three pack complexity levels.
- Metrics: frame-time median/p95/p99/max, stutter count (> 2× median), CPU main-thread time, GPU frame time (Metal
  counters), memory (resident + GPU), chunk rebuild/upload time, shader/pipeline compile time and cache hit rate,
  load/transition stalls; 30-minute soak for degradation.
- Rule: no performance claim without a reproducible run; results labelled **[M]** with build, hardware and settings.

---

## M. Phased implementation roadmap (planning only)

Effort assumes **1–2 experienced developers**, part-time-to-full-time; ranges are wide on purpose.

| Phase | Objectives | Key components | Depends on | Main risks | Effort (person-months) | Acceptance criteria |
|---|---|---|---|---|---|---|
| 0 Research | This report; owner decisions (licence, Iris strategy, target version) | — | — | — | done | Architecture approved |
| 1 Minimal Metal backend | Boot 26.3 to title screen and into a vanilla world on Metal; GL fallback | metal bindings, device/queue, SDL Metal surface, SPIR-V→MSL, basic passes, buffers, textures, timeline sync | 0 | Y/winding, sync semantics | 1.5–3 | Vanilla world renders with no visual diffs in conformance scenes; no Metal validation errors; clean fallback |
| 2 Backend conformance + Sodium | All vanilla pipelines, OIT, post effects; Sodium/Sodium Extra/RSO working | full SPI, format tables, async pipeline cache, diagnostics, config | 1 | Subtle ordering bugs | 2–4 | Conformance suite green vs GL; Sodium Extra feature checklist (§E) passes |
| 3 Backend performance | Remove backend overhead | pass merging, deferred clears, batched uploads, no blocking waits, binding cost | 2 | Regressions in ordering | 1–2 | Measured CPU frame time ≤ Sodium-on-GL baseline on reference scenes; no tail-latency regression |
| 4 Compatibility foundation | Public API, capability queries, Iris-ready hooks, Sodium indirect steering | metallium-api, compat-sodium | 2 | API churn | 1–2 | API documented + tested; indirect path measured |
| 5 Iris & shader packs | Iris adapter on Metal | compat-iris, glslang path, host runtime, diagnostics | 2, 4 (hooks designed in 1–2) | Iris fork maintenance; pack quirks | 4–8 | Synthetic suite green; ≥N agreed real packs render correctly; unsupported features reported, never crash |
| 6 Optimisation | Benchmark-justified wins | ICB terrain, GPU culling, binary archives, optional mesh shaders (M3+) | 3, 5 | Compatibility side effects | 2–4 (open-ended) | Each optimisation shows a measured win and passes the full compat matrix |
| 7 Stabilisation | Releases | CI on macOS, regression capture tests, crash reporting, version-port playbook | all | Vanilla SPI churn | ongoing | Release criteria: no known crashers in matrix, documented unsupported features |

Mandatory: phases 1, 2, 4 (hooks), 5. Optional/enhancement: phase 6 items, Adaptive VSync analogue, DH adapter, mesh shaders.

---

## N. Open questions and unknowns

1. **Pack feature prevalence**: how many target packs require geometry/tessellation/double — decides the realistic
   coverage promise (needs a pack corpus scan).
2. Whether LWJGL's spvc build bundled with 26.3 includes the MSL backend on macOS (Metallum's working code says yes [SI]).
3. Exact Y-flip/winding combination matching Vulkan's `frontFace=CLOCKWISE` + no viewport flip (prototype test).
4. Metal limits per GPU family (sampler states, buffer slots, RW-texture format tiers, `maxBufferLength`, mesh-shader
   availability and acceleration) — runtime capability queries **[ext]**.
5. Whether a renderpearl-level pass-attachment override is enough for all Iris phases (shadow, gbuffers translucent,
   hand, DH) — prototype in phase 4.
6. CaffeineMC's view of PolyForm Shield for a Sodium-requiring Metal backend.
7. Iris upstream's willingness to accept a backend abstraction.
8. Distant Horizons behaviour on a non-GL backend (Metallum/mcopt spoof "Vulkan"; unaudited).
9. MoltenVK on 26.3: whether `triangleFans` is reported (decides whether vanilla Vulkan actually works on macOS — a
   useful baseline).
10. Real cost of FFM per-call overhead at Metallium's call rate (measure in phase 1).
11. No code was built or run in this research; all runtime claims about Metallum/mcopt/MetalRender behaviour are from
    reading code.

---

## O. Immediate next steps

1. Owner decisions: approve A + C; choose licence; decide whether to approach CaffeineMC and IrisShaders now.
2. Scan a corpus of popular shader packs (local only) for stage usage (geometry/tessellation), doubles, compute, images,
   formats — turns §D.4 into coverage numbers.
3. Phase-1 spike (smallest useful prototype): Metal `GpuBackend` that boots to the title screen with the GL fallback path,
   reusing metal-jvm; validate Y/winding and reverse-Z with three conformance scenes.
4. Write the conformance-scene testmod and the benchmark harness skeleton before optimising anything.
5. Prototype the `createRenderPass` attachment-override hook with one Iris composite pass to de-risk §D early.
