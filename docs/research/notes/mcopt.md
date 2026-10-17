# mcopt audit (noahdunnagan/mcopt @ main 7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c, v0.3.0-alpha.2)

Paths below are relative to `/home/user/research/mcopt/`. `M/` = `metal/src/main/`, `J/` = `metal/src/main/java/mcopt/metal/`,
`N/` = `metal/src/main/native/`, `R/` = `metal/src/main/resources/`. Line numbers from the checked-out ref.

Labels: **[V]** Verified in code, **[SI]** Strongly inferred, **[H]** Hypothesis, **[U]** Unverified.

---

## 0. TL;DR (decision-relevant)

1. **mcopt is a full renderpearl `GpuBackend` (like Metallum), not a GL interop layer.** `MetalBackend implements com.mojang.renderpearl.api.device.GpuBackend`, injected first into `PreferredGraphicsApi.getBackendsToTry` (OpenGL/Vulkan stay behind as fallbacks). Window is SDL3 (`SDL_WINDOW_METAL`), surface is the CAMetalLayer from `SDL_Metal_CreateView`. **[V]** `J/MetalBackend.java:12-35`, `J/mixin/PreferredGraphicsApiMixin.java:47-57`, `J/MetalSurface.java:283-288`.
   - NB: in MC 26.3 the GPU API package is `com.mojang.renderpearl.*` (api / backend / frontend), *not* `com.mojang.blaze3d.systems.GpuDevice`; `com.mojang.blaze3d.*` still exists for vertex/RenderSystem/platform. Backend contract inferred from what mcopt implements: `GpuBackend{getName,loadLibrary,unloadLibrary,createWindow,createDevice}`, `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, `BackendRenderPipeline` (+ `.CreateInfo`, `.Pending`, `SpvModule`), `GpuSurfaceBackend`, wrapped by vanilla `FrontendGpuDevice`. **[SI]** (Minecraft source not available).
2. **On top of the backend, it replaces vanilla's *terrain drawing only*** (solid+cutout always; translucent only with classic transparency) via `@Redirect` of `ChunkSectionsToRender.renderGroup` in `LevelRenderer.executeSolid` / `executeClassicTransparency`; everything else (entities, sky, particles, GUI, post) is vanilla through the Metal backend. **[V]** `J/mixin/own/OwnLevelRendererMixin.java:46-74`.
3. **Default = `indie` profile** (no profile named ⇒ `indie`; `profile=none` ⇒ vanilla renderer on the Metal backend). Almost every code flag defaults **false** in code; real behaviour is the profile file. **[V]** `J/Profile.java:32,92-93`, `R/mcopt/profiles/indie.properties`.
4. **Terrain renderer (default path):** vanilla section graph + `SectionRenderDispatcher` kept; own mesher (vanilla `ModelBlockRenderer`/AO restated, no greedy meshing) writes 16-byte compact vertices / 40-byte quad records straight into **one shared, hazard-untracked MTLBuffer arena** from worker threads; per frame a **GPU compute cull** (per-section SIMD, frustum + distance + facing-bucket + per-unit box) emits classed instance lists, drawn by **instanced indirect draws with vertex pulling**; **two-phase GPU occlusion ("uocc")**: phase A draws units visible last frame, the render pass is split, a **Hi-Z pyramid** is built from depth by compute, every candidate unit box is tested, phase B draws the newly visible. No ICBs, no mesh shaders by default (mesh-shader variants exist behind flags). **[V]** see §5-§7.
5. **Far terrain (LOD) exists but is OFF by default** (`mcopt.lod` not in `indie`; README: opt-in, ≥10 GPU cores). **[V]**
6. **Shader packs / Iris: none.** The shaderpack runtime (`mcopt.metal.pack`, `mcpack.m`) and native shading (`mcopt.metal.shade`) were stripped from the public tree; only hooks remain (`MetalHooks` redirector "which nothing in this build installs", `OwnMaterials.ON = false`). `fabric.mod.json` `"breaks": {"sodium": "*"}` ⇒ **Iris, Sodium Extra, RSO cannot load**. **[V]** `J/MetalHooks.java:64-66`, `J/own/OwnMaterials.java:13`, `R/fabric.mod.json`.
7. **Benchmark claims are not reproducible from the repo** (no bench mod, no scripts, no tests; `mcopt.bench.Bench` referenced reflectively but absent) and **not apples-to-apples**: with VSync off mcopt's default **present pacing drops all but ~one frame per refresh before the present copy/nextDrawable** (`mcopt.metal.pace` default true), so "1200-2000 fps" counts rendered-not-presented frames, while vanilla/Sodium (VSync off) present every frame. **[V]** `J/MetalSurface.java:17,292-315`, `N/mcmetal.m:1700-1775`.
8. **Licence:** Apache-2.0 + NOTICE (attribution "credit mcopt"). Sodium-derived PolyForm-Shield files were removed at 0.3.0-alpha.1 (09fea99); 0.2.x tree contains PolyForm Shield files ⇒ only reuse from ≥ 09fea99. **[V]** `NOTICE`, `git show e900f08:NOTICE`.

---

## 1. Repo layout, build, packaging

- Gradle multi-project: `metal` (the mod, id `mcopt-metal`, name "mcopt") and `fpshud` (id `mcopt-fps`, a top-left fps HUD mixin). `settings.gradle`, `metal/build.gradle`, `fpshud/build.gradle`. **[V]**
- Loom `1.17-SNAPSHOT`, MC 26.3, loader 0.19.5, Java release 25; `--add-modules jdk.incubator.vector` for compile (LodNoiseVector, opt-in). `gradle.properties`, `metal/build.gradle:41-46`. **[V]**
- Only compile-only deps: `fabric-renderer-api-v1` 17.0.15 and `fabric-api-base` (for FRAPI meshing path). No Sodium dependency at all. `metal/build.gradle:8-13`. **[V]**
- **Native**: `compileNative` Exec task runs `clang -fno-objc-arc -O2 -arch arm64 -mmacosx-version-min=15.0 -dynamiclib` over `mcmetal.m mclod.m lodseam.m lodmesh.c lodnoise.c rec.m mcown.m mccap.m` → `libmcmetal.dylib`, linked against CoreGraphics, CoreVideo, Foundation, Metal, QuartzCore; packed into the jar at `/natives/`. **No prebuilt dylib in repo; building requires macOS + Xcode clang.** `metal/build.gradle:17-29`. **[V]**
- Loading: `Native.extract()` copies the dylib to a temp dir each launch (deleteOnExit) and binds with **Java FFM** (`java.lang.foreign.Linker`, `SymbolLookup.libraryLookup`). Hot encoder calls are linked `Linker.Option.critical(false)` (no thread-state transition). Pointers cross as `long`. No JNI, no Swift. `J/Native.java:20-27,127-143`. **[V]**
- Release: `tools/release` builds both jars, `tools/bundle.py` nests `fpshud` via Fabric jar-in-jar. **[V]**
- Preload: `Preload` (preLaunch entrypoint) class-loads ~12,183 names from `R/mcopt/metal/preload.txt.gz` on 2 daemon threads (502 of them are `sodium` classes — stale, silently skipped). Default ON (`mcopt.preload=true`). Generator `tools/preloadlist.py` referenced but **not in repo**. `J/Preload.java:24-58`. **[V]**
- PlatformCheck: aborts launch (Fabric error dialog) unless macOS major ≥ 26 (or "16" compat numbering) and aarch64. Note build targets min 15.0 but runtime gate is 26. `J/PlatformCheck.java:321-336`. **[V]**
- History: 10 squashed release commits (0.2.0-alpha.1 2026-10-04 → 0.3.0-alpha.2 2026-10-08). 0.3.0-alpha.1 deleted Sodium-coupled code (`mcterrain.m`, `mcprobe.m`, `mcframegen.m`, `MetalFx`, `FrameGen`, `CullReuse`, Sodium MSL). **[V]** `git log --stat`.
- **No tests, no CI, no benchmark harness** in tree. `find -iname '*test*'` empty. **[V]**
- Docs referenced from code but absent: `metal/OWN-RENDERER.md` (7 refs), `LATENCY.md`. **[V]**

## 2. Mixin configs and gating (13 configs, all listed in fabric.mod.json)

`R/fabric.mod.json`: mixins = metal, lod, gen, own, ownmesh, startup, qos, cpu, chunk, alloc, chunkio, edge, compat. `breaks: sodium *`. Entry: `preLaunch: mcopt.metal.Preload`. **[V]**

Every config except base `mcopt-metal.mixins.json` has an `IMixinConfigPlugin` whose static init calls `Profile.apply()` first, then gates each mixin on system properties. `required: true`, `defaultRequire: 1` (hard failure if a target shifts) except compat (`required:false, defaultRequire:0`). **[V]**

| config | plugin gate | default (indie) |
|---|---|---|
| base `mcopt.metal.mixin` | none (always applied): FramerateLimiterMixin, PreferredGraphicsApiMixin, AnimFramesResidentMixin, LightmapDoubleMixin, AtlasDouble* | applied; inner behaviour flag-gated |
| own | `mcopt.own && !sodium loaded`; Anim*/AtlasWrite/FrameLog sub-gates | ON |
| ownmesh | `mcopt.own && !sodium && (own.mesh∈{true,verify} or own.mesh.stats)`; region/frapiRegion/prefill/tint sub-gates | ON |
| lod | `mcopt.lod` or `mcopt.lod.radius`; LodTruth* on `mcopt.lod.truthRd` | OFF |
| gen | `mcopt.gen.worldgenQos`, `mcopt.gen.nativeNoise` | OFF |
| qos | `Qos.has(role)` | Render+Server ON (Worker off) |
| cpu | per-lever | Model* ON (cpu.model); Leash/EntityBox/Loading OFF |
| chunk | `ChunkOpt.mixinEnabled`; yields to lithium/scalablelux/c2me | POI, ServerLevelTick, ChunkAccessSection ON; light maps OFF |
| alloc | `mcopt.alloc.lightMap` | ON |
| chunkio | `ChunkIo.mixinEnabled` (saveSkip); off if c2me | ON |
| edge | `mcopt.edgeCap` | ON |
| startup | narrator lazy / crashPreload / blockCache / fadeMs | narrator=lazy ON only |
| compat | DH `MinecraftRenderWrapper.getMcRenderingApi` | applies only if DH present |

Refs: `J/mixin/own/OwnMixinPlugin.java:20-38`, `J/mixin/ownmesh/OwnMeshMixinPlugin.java:19-45`, `J/mixin/lod/LodMixinPlugin.java:18-37`, `J/mixin/gen/GenMixinPlugin.java:20-31`, `J/mixin/qos/QosMixinPlugin`, `J/mixin/cpu/CpuMixinPlugin.java:12-30`, `J/mixin/chunk/ChunkMixinPlugin.java:22-45`, `J/chunk/ChunkOpt.java:118-135`, `J/mixin/chunkio/ChunkIoMixinPlugin.java`, `J/mixin/startup/StartupMixinPlugin.java`. **[V]**

## 3. Profiles / config mechanism (the crucial "what is actually on")

`J/Profile.java`:
- Sources (precedence high→low): JVM `-Dmcopt.*` > `mcopt.*` keys in `<gameDir>/config/mcopt.properties` > `/mcopt/profiles/<NAME>.properties`. `profile=` key or `-Dmcopt.profile` picks NAME; empty ⇒ `DEFAULT = "indie"`; `none` ⇒ no profile. Applied by `System.setProperty` only if unset. `:82-123`. **[V]**
- First launch writes `config/mcopt.properties` with `profile=indie` and commented `#mcopt.own=false` / `#mcopt.lod=true`. `:168-188`. **[V]**
- `alpha` profile only: small tier (GPU cores <10 or RAM ≤8 GB) drops `mcopt.chunk.clones*`. `:125-135`. **[V]**
- GPU core count from `ioreg -rd1 -c AGXAccelerator` `"gpu-core-count"` (spawned process, once). Used for many per-GPU gates. `:140-147`. **[V]**
- DH: if Distant Horizons is loaded with Iris or its TOML says `renderingEngine = "OPEN_GL"`, sets `mcopt.metal=false` (whole Metal backend off, OpenGL used). `:52-80`. **[V]**

Profiles shipped: `alpha` (vanilla-renderer perf set, many Sodium-only keys), `indie` (default), `recommended` (far terrain ON + Sodium-era keys). **`alpha` and `recommended` are stale**: they set `mcopt.chunk.clones`, `chunk.clonesCleanup`, `cpu.lists`, `cpu.cullRecover`, `chunk.mesh=all,biome` whose mixins (`ClonedChunkSectionCache*`, `List*`, `Cull*`, `LevelSlice*`, `BlockRendererMixin`, `LevelBiomeSliceMixin`) are not listed in any mixins.json in this tree (or are force-rejected: `CpuMixinPlugin` rejects `CullFast*`/`*Access`) ⇒ no-ops. **[V]** (`R/mcopt-metal-chunk.mixins.json`, `R/mcopt-metal-cpu.mixins.json`, `J/mixin/cpu/CpuMixinPlugin.java:19-21`).

README says "The perf profile is on by default" — true in the sense that `indie` is applied. README "profile=none turns it off" — correct. **[V]**

### 3.1 Flag table (code default → indie value → what it gates)

~364 distinct `mcopt.*` property names are read (`grep` over `J/`; scratch list `scratchpad/mcopt_flags.txt`). The ones that matter:

**Backend (MetalSurface/MetalEncoder/MetalDevice/MetalPipeline)**

| flag | code default | indie | gates |
|---|---|---|---|
| `mcopt.metal` | true | – | Metal backend first in backend list (`PreferredGraphicsApiMixin:51`) |
| `mcopt.metal.pace` | **true** | – | VSync off: present only the last frame that makes each refresh; others rendered and dropped (`MetalSurface:17,312-315`; `mc_pace` `N/mcmetal.m:1756-1775`) |
| `mcopt.metal.paceAdapt` | false | **true** | learns compositor latch margin from `presentedTime` (`MetalSurface:33`; `N/mcmetal.m:1730-1754`) |
| `mcopt.metal.paceMarginMs` | 2 | – | pace lead margin |
| `mcopt.metal.paceSync` | false | – | paced presents with displaySync on |
| `mcopt.metal.drawables` | 0 (=3) | – | `maximumDrawableCount` |
| `mcopt.metal.inFlight` | 2 | – | frames in flight; submit N waits for N-2 (`MetalEncoder:36,241`) |
| `mcopt.metal.presentQueue` | off | – | present from 2nd queue w/ MTLEvent + staging slots (`MetalEncoder:76-78`, `N/mcmetal.m:1826-1981`) |
| `mcopt.metal.lazyClears` | false | – | deferred clears not flushed when other passes open (`MetalEncoder:99`) |
| `mcopt.metal.frameDepthDiscard` | false | – | main depth stored dontCare at frame end (`MetalEncoder:141,199`) |
| `mcopt.metal.residentAnim` | false | – | anim frame textures in a MTLResidencySet |
| `mcopt.metal.arenaReserve` | 256 MiB | – | pre-provisioned buffer pool, **only for buffers labelled "Arena buffer" (Sodium's label) ⇒ effectively dead without Sodium** **[SI]** (`MetalDevice:41,145-158`) |
| `mcopt.metal.occ` / `MetalTerrain.OCC` | **compile-time `false`** | – | Sodium terrain split-pass occlusion: dead code (`MetalTerrain:31`) |
| `mcopt.metal.builtinMsl` | true | – | dead: `SODIUM_TERRAIN` map & `/mcopt/metal/msl/` resource gone; `pulled = 0` always (`MetalPipeline:121,153-169`) |
| `mcopt.startup.mslCache` | false | – | on-disk SPIRV-Cross→MSL text cache (`MetalPipeline:242-300`) |
| `mcopt.metal.dumpMsl` / `overrideMsl` / `uncachedMsl` | off | – | debug |
| `mcopt.metal.trace/stats/gpuTimes/cbdiag/gpuCount/waitStats/jfr/latency` | off | – | measurement |
| `mcopt.preciseLimiter` | **true** | – | frame limiter wait on `kevent64 NOTE_CRITICAL` timer instead of park+spin (`FrameWait`, `FramerateLimiterMixin`, `N/mcmetal.m:1777-1787`) |
| `mcopt.metal.lightmapDouble`, `atlasDouble` | false | – | experiments |
| `mcopt.cpu.fence` | "" | **true** | fence created right after submit names last submit (`MetalEncoder.createFence`) |
| `mcopt.cpu.texelCache` | false | **true** | texel-buffer view cache |
| `mcopt.cpu.model` | false | **true** | ModelPart.compile indexed loop mixin |
| `mcopt.cpu.pass`, `cpu.cmdAhead` | false | – | native pass-descriptor reuse; next cmdbuf made on bg queue |

**Own terrain (indie values; code default false/0 unless noted)**

| flag | indie | gates |
|---|---|---|
| `mcopt.own` | true | master switch (`OwnTerrain:44`) |
| `own.compact` | true | 16-B vertex `CVtx` in arena instead of 28-B BLOCK vertex (`OwnTerrain:94`, `compact()` `:767-776`) |
| `own.run` | 32 (code default 64) | quads per draw unit/instance (`OwnTerrain:92`) |
| `own.translucent` | true | own translucent path (classic transparency only) (`OwnTerrain:104`, `takes()` `:532-541`) |
| `own.arena.preSize`/`preSizeMaxCores=8` | true | reserve 4M quad units at RD16 on <8-core GPUs (`OwnArenaSizing`) |
| `own.frag.sizes/lean/box/half` | true | frag path: size classes of 8 quads, lean vertex variant inside fog start, per-unit box frustum test, half4 colour varying |
| `own.frag.uocc` | true | two-phase unit occlusion (`OwnFrag` doc `:26-35`) |
| `own.frag.fine` | 3 | finer Hi-Z test level |
| `own.frag.msub` + `own.mesh.subbox` | true | two SAH sub-boxes per unit for the test |
| `own.frag.pipe` | true | double-buffered per-frame cull/test buffers |
| `own.hizTop`, `own.hizGather` | true | pyramid top levels in 1 threadgroup; mip0 by gathers |
| `own.frag.splitNear` | 216 | phase A split by section distance around the test |
| `own.frag.testLate/testFast/testGather` | true | test encoder ordering/fast variants |
| `own.frag.pyrSkip` | 1 | pyramid built from 4×4-pixel texels |
| `own.frag.uoccMaxMp=6.0`, `uoccMaxMpCores=12` | – | **uocc disabled above 6 MP on ≥12-core GPUs** (`OwnFrag:476-499`) |
| `own.frag.a2Exact` (+`a2ExactMaxCores=7`) | true | A2 drawn w/o padding on ≤7-core GPUs only |
| `own.frag.tieClose` | true | promotion closure for coplanar solid/cutout ties |
| `own.tl.a1Split=30` (`MaxCores=7`, `MinCores=12`) | – | split A1 into two render passes on ≤7 or ≥12 cores (not on 10-core) |
| `own.frag.vGroup=2` (`MinCores=12`) | – | grouped class draws on ≥12-core GPUs only |
| `own.int.cullR=true`, `cullW=8`, `preEarly` | true | SIMD-per-section cull, 8 lanes; pre cmdbuf committed early |
| `own.mesh` | true | own mesher replaces `SectionCompiler.compile` (`OwnMeshCompilerMixin`) |
| `own.mesh.region/tint/prefill/ao/exactPos/qrec` | true | region cache, tint cache, prefilled region, restated AO, exact off-grid positions table, 40-B quad records |
| `own.mesh.order=cell`, `cell=8`, `bucketClass=suffix` | – | unit formation |
| `own.mesh.tfrustum/tcull/tsorted/thalf` | true | translucent culls / sorted copies / half colour |
| `own.mesh.frapiRegion` | true | FRAPI path reads through own region copy |
| `own.int.lists/visible/compileRegionDrop/lifeFix/seam` | true | vanilla list-walk skip, visible-section filtering, drop region copy after compile, cancelled-compile publish fix, LOD hand-off |
| `own.int.animOnePass/atlasWrite` (`animMinCores=8`) | true | one-pass texture animation + compute copy into mips on ≥8-core GPUs (turns off atlas lossless compression) |
| `own.vignetteNoop` | true | skip vignette when black |
| `own.occ`, `own.quads`, `own.prefilter`, `own.prepass`, `own.ring`, `own.fat`, `own.cpuClip`, `own.level`, `own.graph`, `frag.qmesh/mesh/mesh4/occ`, `mesh.tieGroups` | **off** | alternative occlusion/draw paths (per-quad, mesh shaders, prepass…) — exist, not default |

**LOD** (`J/lod/LodConfig.java`): `mcopt.lod` **false** (indie leaves commented); if on: `radius` 512 chunks, `n` 2048 (small: 512), `render` code default `walk`, indie/recommended set `mesh`; `nativeNoise` code default off, profiles `neon`; `horizonCull` true; `cache` true; `trees/plants/textures/nearDetail/fineDensity` true; `pk`, `realOcc`, `taa`, `taaTile` off. **[V]**

**Other subsystems**: `mcopt.qos` default `on` (render 0x21 USER_INTERACTIVE, server 0x19 USER_INITIATED) (`J/Qos.java:29-37`); `mcopt.edgeCap` indie true (2× refresh cap while integrated server generates within RD+32) (`J/EdgeCap.java`); `chunk.saveSkip/poi/ticks/chunkGet`, `alloc.lightMap` true; `startup.narrator=lazy`; `startup.crashPreload/blockCache/fadeMs`, `gen.*`, `cpu.leash/entityBox/loadingFps`, `rec`, `own.capture` off. **[V]**

## 4. Metal backend internals

### 4.1 Device / init
- `mc_create`: `MTLCreateSystemDefaultDevice`, one `MTLCommandQueue` (`maxCommandBufferCount:64`), builtin library compiled from source string (present blit VS/FS with Y-flip, clear VS/FS), fan index buffer for TRIANGLE_FAN emulation. `N/mcmetal.m:43-82`. **[V]**
- `MetalDevice` reports `DeviceInfo(name, "Apple", "Metal 3 (mcopt)", …, "Metal")`, `DeviceFeatures` all true ("every multi-draw form is a native loop of Metal draws"), `DeviceType.INTEGRATED`, timestamp query pool returns empty values (no GPU timers to the game). `J/MetalDevice.java:50-75,198-227`. **[V]**
- Fallback: `BackendCreationException` on failure ⇒ frontend tries next backend (OpenGL/Vulkan) **[SI]**. Own renderer: `OwnTerrain.init()` returns null (vanilla draw) if backend isn't Metal or construction throws (`failed=true`). `J/own/OwnTerrain.java:501-517`. **[V]**

### 4.2 Surface / presentation
- SDL3 `SDL_Metal_CreateView` → `CAMetalLayer` (`J/MetalSurface.java:283-288`). Layer: `BGRA8Unorm`, `framebufferOnly`, `displaySyncEnabled` = FIFO present mode, `maximumDrawableCount` 3 (`N/mcmetal.m:1685-1696`). No colorspace/EDR setup. **[V]**
- Drawable acquired late in `blitFromTexture` (not at frame start); `mc_present` draws the frame texture into the drawable with a fullscreen triangle (Y flip) and `presentDrawable` on the frame cmdbuf. `present()` is empty. `J/MetalSurface.java:308-337`, `N/mcmetal.m:1796-1825`. **[V]**
- Pacing (`mc_pace`): CVDisplayLink on main display gives refresh grid; GPU latency EMA from completed handlers; present iff frame is first to make a refresh and next frame would miss it. **Unpresented frames skip nextDrawable + copy + present entirely.** `N/mcmetal.m:1700-1775`. **[V]**
- No Metal–GL interop anywhere (no IOSurface/CGL sharing). **[V]** (grep).

### 4.3 Command buffers, encoders, sync
- One `MTLCommandBuffer` per submit/frame (`cmd(enc)` lazily), plus an optional **"pre" command buffer** committed just before the frame's (compute cull, LOD cull, pre-blits) so its work runs first; ordering relies on hazard tracking + queue order; no events between them (deliberately, to keep frame overlap). `N/mcmetal.h:44-55`, `N/mcmetal.m:907-1003`; `mco_pre_commit` (`preEarly`) `N/mcown.m:1979-1985`. **[V]**
- **Render-encoder merging**: consecutive frontend render passes with identical attachments and no clear continue the same `MTLRenderCommandEncoder`; a depth clear in a continued pass is folded as a depth-only fullscreen triangle (`foldDepthClear`); store actions decided at `endRender` (dead/cleared attachments get `DontCare`). `N/mcmetal.m:1132-1145,1187-1223,1359-1400,784-810`. **[V]**
- **Deferred clears**: `clearColor/Depth` record a pending clear and `mc_discard`; folded into the next pass's load action, flushed with an empty pass only if sampled/copied first. `J/MetalEncoder.java:531-575` (flushClear/deferClear). **[V]**
- No barriers: Metal hazard tracking on default resources; own arena/lists buffers are `HazardTrackingModeUntracked` or private-tracked by choice (`N/mcown.m:257-262`). **[V]**
- Frames in flight: `MAX_IN_FLIGHT=2`; after commit, if >2 in flight the render thread **blocks on `waitUntilCompleted`** of the oldest (`retire`). Fences = submit index comparisons (`completedIndex`); `GpuFence.awaitCompletion` retires frames synchronously. No `MTLSharedEvent`, no completion-handler-driven semaphore. `J/MetalEncoder.java:36,225-317,716-750`. **[V]**
- Uploads: `writeToBuffer` ≤256 KiB is a CPU memcpy if the buffer is idle, or the buffer is **renamed** to spare memory if fully overwritten; else staged via transient memory + blit. `J/MetalEncoder.java:605-634`, `J/MetalBuffer.java:52-66`. Transient memory: 1 MiB shared blocks recycled after GPU completion (`J/MetalTransientMemory.java`). **[V]**

### 4.4 Pipelines & shaders
- Vanilla/mod pipelines: renderpearl gives **SPIR-V** (`SpvModule`) → LWJGL **SPIRV-Cross (`org.lwjgl.util.spvc`)** → MSL 3.0 (`FLIP_VERTEX_Y`, texture buffers native) → `newLibraryWithSource` at runtime → `MTLRenderPipelineState`. Two PSOs when the pipeline has no depth state (with/without depth attachment format); depth format baked as D32. Compiled on the frontend's worker thread (`compilePipeline` returns already-done Pending). No `MTLBinaryArchive`; relies on Metal's system shader cache. `J/MetalPipeline.java:86-138,302-331`, `J/MetalDevice.java:187-191`. **[V]**
- Binding model: fixed slots — uniform i → buffer/texture/sampler slot i (all three), push constants slot 26, vertex buffers from 27. Uniforms bound lazily at next draw only if changed. `J/MetalConst.java:127-128`, `J/MetalPipeline.java:322-338`, `J/MetalRenderPass.java:25-28`. No argument buffers. **[V]**
- Own terrain shaders: hand-written MSL `R/mcopt/own/terrain.metal` (3427 lines: cull kernels, Hi-Z, uocc tests, `own_vs`/`own_fs` restating vanilla `core/terrain`, mesh-shader variants, anim copy) compiled at runtime with `#define` prefixes and function constants (`COMPACT`, `FAT`, `QUADS`, `F_*`). LOD shaders `R/mcopt/lod/columns.metal` (3759), `seam.metal`; recorder `R/mcopt/rec/rec.metal`. **[V]**
- Possible bug **[H]**: a pipeline *with* a depth state used in a pass *without* depth binds `withoutDepth == 0` (never created). Vanilla likely never does this.
- Textures: all `MTLStorageModePrivate`; **array layers ignored** ("arrays aren't used by the game yet"); cube supported; D24S8 mapped to D32S8. `N/mcmetal.m:307-320`, `J/MetalConst.java:177`. **[V]**

## 5. Own terrain renderer ("own" + "ownmesh")

### 5.1 Integration points (vanilla kept, terrain draw replaced)
- `OwnLevelRendererMixin`: `render` HEAD → `OwnTerrain.init()`; `@Redirect` of `ChunkSectionsToRender.renderGroup` in `executeSolid` (OPAQUE group) and `executeClassicTransparency` (TRANSLUCENT) → `drawOpaque`/`drawTranslucent`; falls back to vanilla call if they return false. `J/mixin/own/OwnLevelRendererMixin.java:39-74`. **[V]**
- Geometry capture: `OwnRenderSectionMixin` cancels `RenderSection.addSectionBuffersToUberBuffer` for taken layers and stores into the arena on the worker; `OwnIntListsMixin` skips vanilla `extractSectionDrawGroups` when uber buffers are empty; `OwnSectionDispatcherMixin.dispose` → `disposeAll`. `J/mixin/own/OwnRenderSectionMixin.java:42-…`, `OwnIntListsMixin.java:24-40`. **[V]**
- Draws inside the *vanilla-opened* render pass using `MetalBridge` to reach native encoder handles, the game's projection/globals/fog/dynamic-transform uniform buffers, the lightmap and atlas; restores the pass's pipeline afterwards (`reapplyPipeline`/`restorePass`). `J/own/OwnTerrain.java:2032-2248`, `J/MetalBridge.java`. **[V]**
- Uses vanilla's `visibleSections()` only for translucent order and (opt-in `own.graph`) masking; **by default opaque visibility = GPU cull over all compiled sections + uocc**, not vanilla's cave-culling graph. `OwnTerrain.java:2052,2106-2117`. **[V]**

### 5.2 Mesher (`own.mesh=true`)
- `OwnMeshCompilerMixin` (priority 900) cancels `SectionCompiler.compile` at HEAD and returns `OwnMesher.compile` results. Vanilla's block loop restated: `ModelBlockRenderer`/`FluidRenderer` (or `OwnBlockRenderer` — vanilla AO/flat lighting restated with int coords, no BlockPos churn) compute every quad; solid/cutout quads go to `OwnQuads` (per-thread, 7 facing buckets × 2 layers, compact vertices); translucent stays on a vanilla `BufferBuilder` with vanilla `sortQuads`. Placeholder 4-byte `MeshData` keeps vanilla task flow. `J/mixin/ownmesh/OwnMeshCompilerMixin.java:39-53`, `J/own/OwnMesher.java:36-215`, `J/own/OwnBlockRenderer.java:25-33`. **[V]**
- **No greedy meshing / face merging** — quads are vanilla's, bit-exact goals ("exact vs vanilla"). **[V]**
- With Fabric API's FRAPI present, blocks go through `Renderer.altModelBlockRenderer` (`OwnFrapi`) emitting into compact quads — i.e. FRAPI models (Indigo/Continuity-style) work. `J/own/OwnApi.java`, `J/own/OwnFrapi.java:27-78`. **[V]**
- Own `VisGraph` replacement opt-in (`own.mesh.vis`, off). **[V]**

### 5.3 Vertex formats & compression
- Vanilla BLOCK vertex 28 B (pos 3×f32, RGBA8, uv 2×f32, uv2 2×i16). `terrain.metal:8-13`. **[V]**
- **Compact `CVtx` 16 B**: pos 3×u16 = (p+8)·2048 section-relative (range −8..24, 1/2048 block), light 2×u8 (uv2 clamped 0-255), colour RGBA8, uv 2×unorm16 (u·65536, exact for power-of-two atlas). `terrain.metal:1595-1603`, `OwnTerrain.compact` `:767-776`. **[V]**
- **exactPos**: codes ≥61440 index a float table (4096 entries) in the arena's first 256 units for off-grid coordinates; overflow rounds to grid. `J/own/OwnPosTable.java`. **[V]**
- **qrec (default)**: solid/cutout quads as **40-B records** (10 words: per-axis two codes + per-corner selector bits, uv two codes, tint, per-corner grey byte, per-corner light) ⇒ 10 B/vertex equivalent; quads not representable become "stubs" pointing to a 64-B copy. Decoded in VS (`ownQrec`). `J/own/OwnQrec.java:8-110`, `terrain.metal:1621-1647`. **[V]**
- Translucent stays 64 B/quad + u32 order (permutation) entries. **[V]**

### 5.4 Buffer allocation
- **One arena MTLBuffer** (`mco_buffer(ctx, size, shared=1)` ⇒ `StorageModeShared | HazardTrackingModeUntracked`), initial 1M units × 64 B = 64 MiB (4M units = 256 MiB with preSize on small GPUs), doubled on demand by **allocating a new buffer and CPU-memcpy'ing the whole arena** under a write lock, cap 3 GiB. Workers write their quads directly (read lock + `OwnAlloc` best-fit TreeMap allocator, units of 64 B). Frees deferred until frames in flight complete (`later()`). `J/own/OwnTerrain.java:448-470,821-833,963-1007`, `J/own/OwnAlloc.java`. **[V]**
- Record buffer (32-B `Rec` per unit, 64K initial, own `OwnAlloc`), section table (32-B `Section` per slot, per-frame copies ×FRAMES=3), lists/args private. **[V]**
- No `MTLHeap`, no placement sub-allocation; MTLResidencySet only for the (Sodium-era) provisioned pool and opt-in anim textures. `N/mcmetal.m:133-247`. **[V]**
- Perf concern **[SI]**: arena growth is a synchronous CPU copy of up to GBs on a meshing worker while all other workers block; GPU keeps old buffer until release queue drains.

### 5.5 Per-frame GPU pipeline (indie default = frag+uocc)
1. Render thread `processEvents` publishes compiled sections into slots (section table, records). **[V]** `OwnTerrain.java:1082-1232`.
2. **Cull** (`frag_cull_s8`, compute, in pre cmdbuf): per section, SIMD lanes over its unit records; tests vanilla's distance rule, 4 frustum side planes, facing-bucket vs camera plane bound, unit box; appends into classed lists (layer × size class × lean) with atomics, ordered append to preserve record order; writes indirect args. `terrain.metal:529-912`, `OwnFrag.java:557-580`. **[V]**
3. **Phase A**: indirect instanced draws (one per class list) of units visible last frame (occVis stamp = frame number), vertex pulling from arena (`own_vs`: `vertex_id`→quad/corner, `instance_id`→list entry→record→section origin). **[V]**
4. **Split**: `mc_render_suspend` ends the render encoder (store), compute encoder builds Hi-Z (mip0 at ≈480+ texels wide, power-of-two scale; top levels in one threadgroup), `frag_uocc_test_*` tests every candidate unit's box (or two sub-boxes) against the pyramid, writes phase-B lists + next-frame stamps; `mc_render_resume` reopens with Load. `N/mcown.m:1365-1490`, `N/mcmetal.m:1477-1514`, `terrain.metal:1090-1330`. **[V]**
5. **Phase B**: indirect draws of newly visible units. Exactness: anything visible is drawn in A or B. **[V]**
6. GPU-specific knobs (split A by distance, A1 split, vGroup, a2Exact) gated by GPU core count (§3.1). **[V]**
- Not used by default: ICBs (`executeCommandsInBuffer` appears only in a counting proxy), mesh shaders (`own_mesh*`, flag-only), per-quad occlusion (`own.quads`), CPU-side solid occluder (`own.solidOcc`). **[V]**
- Translucent: one draw/frame, instance order = vanilla's visible sections far→near, quads in vanilla's sorted order (or pre-sorted copies `tsorted`), CPU back-face filter `tcull`, frustum skip `tfrustum`; with Improved Transparency (OIT) vanilla keeps translucent. `OwnTerrain.java:104,532-541,2615-…`. **[V]**
- Shadow cull API (`shadowCull`) for native shading cascades is present but its consumer (`mcopt.metal.shade`) is stripped. `OwnTerrain.java:2250-2312`. **[V]**

## 6. LOD / far terrain (`lod` package, OFF by default)

- Gate `LodConfig.ENABLED` (`mcopt.lod` or radius). Hooks: camera far plane, fog extension, LevelRenderer draw after opaque terrain, client chunk load/unload. `J/lod/LodConfig.java:9`, `J/mixin/lod/*`. **[V]**
- Data: per-world **clipmap** (levels of 2^L-block cells, window N=2048 cells, 8·N² B/level), tiles filled by workers from a **disk cache** (`<gameDir>/mcopt-lod`) or **generated from the world generator's noise** — copies of the game's density functions with backends PLAIN/JAVA/VECTOR/**NEON** (`lodnoise.c`, claimed bit-exact), the game's own tree and plant features on level 0/1; real chunks overwrite estimates (`LodChunks` snapshots on render thread, colours on workers). `J/lod/LodField.java:13-50`, `J/lod/LodNativeNoise.java:55-90`, `J/lod/LodChunks.java`. **[V]**
- Render: `walk` = compute column ray-walk per screen column composited into level pass; `mesh` (profiles) = tiles meshed in C (`lodmesh.c`) into an arena (shared), GPU cull (`lod_mesh_cull`) incl. **horizon occlusion cull**, indexed indirect draws (`mclod.m:496-501`). Hand-off with own terrain via `OwnSeam` mask + depth push 0.9995. Optional TAA/tile-TAA, position-keyed lists `pk`. **[V]**
- Generation-vs-server contention: `lod.yield=missing:0` pauses generation while client lacks chunks; `LodGenQos` optional QoS for LOD workers/worldgen threads. **[V]**
- README: costs "most of the fps" on <10-core GPUs. Not exercised by default. **[V]** (README) / perf **[U]**.

## 7. CPU / QoS / startup / server-side

- **QoS (default on)**: render thread `pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE)` at `Minecraft.run` HEAD; integrated server thread `USER_INITIATED` at `MinecraftServer.runServer` HEAD; optional Worker-Main via `Util$2.onStart`. Via FFM downcall to libSystem. No explicit P/E-core affinity (macOS has none); `background` class (E-cores) only in opt-in LOD/worldgen experiments. `J/Qos.java:21-84`, `J/mixin/qos/*`, `J/lod/LodGenQos.java`. **[V]** QosMixinPlugin's comment "by default the game is unchanged" is stale. `mesh`/`cull` roles reference Sodium threads; no mixins for them ⇒ no-op. **[V]**
- Command buffer creation on `QOS_CLASS_USER_INTERACTIVE` global queue only with opt-in `cpu.cmdAhead`. `N/mcmetal.m:979-990`. **[V]**
- Frame limiter: kevent `NOTE_CRITICAL` zero-leeway timer (default on). **[V]**
- Startup: lazy narrator (default), async crash-report preload, parallel block-state cache init, fade duration (opt-in); class preload threads (default on). `J/Startup.java`. **[V]**
- Server-side (integrated server; mixins in "client" arrays): POI search as loops, chunk random-tick without per-pick overhead, section index offset once, copy-on-write light section map (`alloc.lightMap`), **region write skip when bytes identical except LastUpdate** (`chunkio/RegionSkip`). Step aside for Lithium/ScalableLux/C2ME. **[V]**
- `edgeCap`: caps fps at 2× refresh while chunks generate within RD+32 (default on) — interacts with benchmark "flying" numbers if terrain is new **[H]**.

## 8. Compatibility

- **Sodium: breaks** (`fabric.mod.json`), and own mixins refuse with Sodium anyway. ⇒ **Sodium Extra, Reese's Sodium Options, Iris (depends on Sodium), Indium: cannot co-load.** README admits "Mods that require Sodium (… Iris) can't be used". **[V]**
- **Shader packs: no support at all in this build** (pack runtime removed). **[V]**
- Mods calling OpenGL directly break on Metal backend; switch `mcopt.metal=false`. **[V]** README + design.
- Distant Horizons: Blaze3D/renderpearl renderer told backend is "VULKAN" (`DhRenderApiMixin`); DH OpenGL renderer ⇒ mcopt turns Metal off. `J/compat/DistantHorizons.java`, `J/Profile.java:52-80`. **[V]**
- FRAPI (Fabric Renderer API) models supported in own mesher. **[V]** Mods that mixin into `SectionCompiler.compile` body, `RenderSection.addSectionBuffersToUberBuffer`, or `@Redirect` the same `renderGroup` calls in `LevelRenderer.executeSolid/executeClassicTransparency` will conflict (redirects are exclusive; compile is cancelled at HEAD). **[SI]**
- Lithium/C2ME/ScalableLux: chunk mixins yield. **[V]**
- Strict `defaultRequire: 1` everywhere ⇒ any other mod's overwrite of the same injection point = crash. **[SI]**

## 9. Benchmarks & claims

- README table: fps "spinning / flying, same test world, 1920×1080, RD16, VSync off", Mac mini M4 / MacBook Neo A18 Pro / Mac mini M6, vanilla vs Sodium 0.9.3 vs mcopt 0.2/0.3 (e.g. M4: 156/146 vanilla, 275-281/253-267 Sodium, 1209-1211/1068-1069 mcopt 0.3). **[V]** README.
- **No harness in repo** (bench mod `mcopt.bench.Bench` referenced reflectively `J/cpu/Cpu.java:141-155`, `GpuTimes`, `OwnProbe` "bench.warm", absent). No world, no script, no raw data. Not reproducible. **[V]**
- **Comparability problems**: (a) present pacing drops non-presentable frames (default ON) — mcopt's counted fps includes frames never presented and skips per-frame drawable acquisition/present cost, vanilla/Sodium VSync-off present every frame **[V]** (mechanism) / impact **[U]**; (b) `edgeCap` may cap mcopt during generation **[H]**; (c) cells partially run on different commits (README notes) **[V]**; (d) vanilla/Sodium likely on OpenGL or Mojang's Vulkan/MoltenVK backend — not stated **[U]**.
- Many in-code micro-claims ("mini +12.6% spin 1% lows", "meshing CPU -37%") documented in profile comments only; extensive in-run A/B probe infrastructure (`OwnProbe`, `own.probe`, `*.ab` modes, `GpuTimes`, `FrameLog`, `FrameCapture`, `rec` recorder w/ ffmpeg) exists for producing them. **[V]**

## 10. Bugs / incomplete / dead code / doc-vs-code discrepancies

1. **Dead Sodium-era code shipped**: `MetalTerrain` (OCC=false), `MetalOccPick`, `MetalPipeline.SODIUM_TERRAIN/BUILTIN_MSL/resource()` (resource path no longer exists), `pulled` always 0, `MetalDevice` arena pool keyed on Sodium's "Arena buffer" label, `Lod.SODIUM`, `LodSodiumFadeMixin` gate (class not in json), preload list with 502 sodium classes. **[V]**
2. **Stale profiles**: `alpha`, `recommended` set Sodium-only flags that do nothing. **[V]**
3. **Stripped features with live hooks**: shaderpack runtime (`MetalHooks.redirector`, `PassDelegate`, `MetalBridge` "mcpack.m"), native shading (`OwnMaterials.ON=false`, `shadowCull`, memoryless G-buffer handling in `endRender`). **[V]**
4. `OwnTerrain` class doc says "Vanilla's section compiler still meshes … Translucent stays on vanilla's path" — stale vs default (`own.mesh=true`, `own.translucent=true`). `OwnLevelRendererMixin` doc "translucent stays vanilla's" — stale. **[V]**
5. `QosMixinPlugin` doc "by default the game is unchanged" contradicts `Qos.DEFAULT_MODE="on"`. **[V]**
6. Texture array layers ignored in `mc_texture_new` ⇒ any mod creating 2D-array textures gets a single-layer texture **[V]** (code) / impact **[SI]**.
7. `withoutDepth` PSO missing for depth-stated pipelines **[H]**.
8. Arena growth: whole-arena CPU copy + global write lock (stall) **[SI]**; cap 3 GiB then `IllegalStateException` **[V]**.
9. Render thread blocking `waitUntilCompleted` for in-flight throttling and fences **[V]** (common, but no event-driven pacing).
10. No GPU timestamp queries for the game (`createTimestampQueryPool` returns empties) — F3/profilers get nothing. **[V]**
11. Per-GPU magic gates keyed on core counts of 3 test machines (5,10,12 cores) — behaviour on M1/M2/Pro/Max/Ultra unmeasured (README "wider hardware testing" in Plans). **[V]** gates / **[U]** perf.
12. Native lib extracted to a fresh temp dir every launch. Minor. **[V]**
13. No TODO/FIXME markers anywhere (comments are prose). **[V]**

## 11. Strongest reusable ideas for Metallium

1. **Backend-level wins independent of the terrain renderer** (all in `mcmetal.m`/`MetalEncoder`): render-encoder continuation across same-attachment passes; deferred clears folded into load actions; store-action decided at encoder end with "dead" tracking; depth-clear folding with a fullscreen depth-only triangle; buffer **rename** for whole-buffer CPU rewrites; transient shared blocks recycled on completion; fixed slot binding + lazy dirty-only rebinds; FFM `critical` downcalls; SPIRV-Cross→MSL with fixed binding remap and optional on-disk MSL cache.
2. **Present pacing via CVDisplayLink** + adaptive latch margin; kevent `NOTE_CRITICAL` frame limiter. (Be honest in benchmarks about dropped frames.)
3. **GPU-driven terrain**: shared untracked arena written directly by meshing workers (unified memory, zero staging), compact 16-B vertex / 40-B quad record decoded in VS, facing buckets + per-unit plane bound for GPU back-face culling of whole units, SIMD-per-section cull with ordered appends (order preserved for exactness), size-class instanced indirect draws, two-phase Hi-Z unit occlusion with mid-pass split, double-buffered per-frame cull buffers so frame N+1 cull doesn't wait for frame N.
4. "pre" command buffer committed before the frame's main command buffer for compute that must precede draws, ordered by hazard tracking rather than events (keeps frame overlap).
5. Exactness discipline: every optimization has a `verify` mode/digest (`own.mesh=verify`, `cullR=verify`, `qrecVerify`, `lists=verify`, oracle) — good pattern for Iris/vanilla parity tests.
6. FRAPI-in-own-mesher approach (`OwnFrapi`) for compat with Fabric renderer API mods.
7. QoS: render thread USER_INTERACTIVE, integrated server USER_INITIATED via `pthread_set_qos_class_self_np` from each thread.
8. DH compatibility trick (report non-OpenGL API).
9. Integrated-server micro-opts with yield-to-Lithium/C2ME/ScalableLux pattern; region write skip.

Caveat for Metallium's goals (Iris + Sodium Extra compat): mcopt's architecture *excludes* Sodium and therefore Iris/Sodium Extra; its terrain path draws with hand-written MSL tied to vanilla `core/terrain` semantics — no hook for pack shaders. Reuse its backend techniques and GPU-culling ideas, not its integration strategy.

## 12. Open questions / not verified

- Exact renderpearl interfaces/method sets (inferred only from mcopt's implementations); whether vanilla 26.3 ships a Vulkan (MoltenVK) backend on macOS (only mcopt's comment `MetalBackend.java:11`). **[U]**
- Whether vanilla ever uses a depth-stated pipeline in a depthless pass (§10.7). **[U]**
- Real perf numbers: nothing in repo to reproduce; impact of present pacing on reported fps not quantified. **[U]**
- Whether the vanilla graph's cave culling is fully replaced by uocc in practice (GRAPH off by default) — correctness verified by authors' "exact" claims only. **[U]**
- Correctness of NEON noise "bit-exact" claim (LOD/gen, off by default). **[U]**
- Runtime behaviour on non-tested GPUs (M1/M2/M3, Pro/Max/Ultra) given hard-coded core-count gates. **[U]**
- Did not read in full: `OwnTieGroups` (1154 lines), `OwnFrag.draw` body, `mcown.m` uocc/vgroup internals, `columns.metal`, `LodPk`, `rec.m`/`Recorder` (opt-in recorder via ffmpeg), `mccap.m`/`FrameCapture` (debug capture), `AnimOnePass`/`AnimCopy` in detail.
