# MetalRender audit (origin/26.1 @ 13d9794; glance at origin/1.21.8-stable, origin/main)

Repo: /home/user/research/MetalRender. All paths below are relative to that repo root at origin/26.1 unless prefixed with a branch.
Abbreviations: `J/` = `src/client/java/com/pebbles_boon/metalrender/`, `N/` = `src/main/resources/native/`, `S/` = `N/shaders/`.
Labels: **V** = Verified from code, **SI** = Strongly inferred, **H** = Hypothesis, **U** = Unverified.

---

## 0. TL;DR (decision-relevant)

1. **26.1 branch is NOT a "Metal terrain bolted onto GL" mod any more; its default mode is a full Blaze3D GPU backend.** A mixin on `Minecraft.<init>` prepends `MetalGpuBackend` to vanilla's `GpuBackend[]` list (`J/sodium/mixins/BackendInstallMixin.java:12-24`). `MetalGpuBackend` sets `GLFW_CLIENT_API=GLFW_NO_API` and builds a `GpuDevice(new MetalDeviceBackend(...))` (`J/backend/metal/MetalGpuBackend.java:21-49`). Vanilla's entire frame (GUI, text, sky, entities, particles, clouds, weather, post chain, block outline, lightmap, sprite animation) is then encoded via `GpuDeviceBackend`/`CommandEncoderBackend`/`RenderPassBackend` implementations onto Metal and presented through a **CAMetalLayer attached to the GLFW NSWindow's content view** (`N/metal_backend.mm:414-440`). No GL context exists in this mode. **V**
   - Inference about vanilla 26.1 (label **SI**, derived only from what MetalRender implements/mixins): Blaze3D in 26.1 has a pluggable backend layer: `com.mojang.blaze3d.systems.GpuBackend` (getName, setWindowHints, handleWindowCreationErrors(GLFWErrorCapture.Error), createDevice(long window, ShaderSource, GpuDebugOptions)), `GpuDevice` wrapping a `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, `BackendCreationException`, `CompiledRenderPipeline`, `GpuQuery`, `GpuFence`; `Minecraft.<init>` holds a local `GpuBackend[]` (LVT index 9) iterated in order with fallback on `BackendCreationException`. This is the single most important finding for Metallium: **a Metal backend can be a drop-in Blaze3D backend instead of a GL interceptor.**
2. **Terrain is NOT drawn by vanilla's chunk renderer**: vanilla section compilation is cancelled (`J/sodium/mixins/VanillaSectionCompileTaskMixin.java:114-152`) and vanilla's `ChunkSectionsToRender.renderGroup` calls are redirected (`J/sodium/mixins/WorldRendererTerrainMixin.java:42-72`) to MetalRender's own mesher + native terrain path, which is injected **inside the vanilla main-pass render pass** by grabbing the raw `MTLRenderCommandEncoder` from the backend's `RenderPassBackend` (`J/render/MetalWorldRenderer.java:1084-1105`). **V**
3. **Shader strategy = hand-written MSL "families" keyed on vanilla shader IDs**, selected by function constants. No GLSL→MSL translation. Any pipeline whose vertex/fragment shader namespace is not `minecraft` (or `metalrender`) gets `variant=-1` → not drawable → rendered with a **magenta `fallback_fragment`** (`J/backend/metal/FamilyTable.java:191-235`, `J/backend/metal/MetalCompiledPipeline.java:183-191`, `S/families.metal:1109-1131`). Resource-pack core shader overrides are ignored (MSL is fixed). **V** → modded RenderPipelines, Iris, and core-shader packs are fundamentally unsupported. Iris: zero code. **V**
4. Terrain mesher is **fully custom** (4.8k-line `J/render/chunk/CustomChunkMesher.java`): reads vanilla `BlockStateModel.collectParts` → `BakedQuad`s; own AO/smooth light, own water/lava-only fluid renderer, greedy meshing, distance LOD tiers, compressed 16/32/64-byte quad "tiers" stored in a native GPU arena; **GPU frustum + per-unit backface culling compute** writes indirect draw args (`S/cull_and_encode.metal:189-294`, `N/metalrender.mm:8102-8200`), vertex-pulling `terrain_vs` decodes quads (`S/metalrender.metal:319-358`). No Fabric Rendering API / FRAPI / Indium, no modded fluid handlers, no translucent block layer (stained glass etc. go through solid/cutout path). **V/SI**
5. Native bridge: plain **JNI** into one Objective-C++ dylib (`N/metalrender.mm` 8.3k lines, `N/metal_backend.mm` 1.3k, `N/meshshader.mm` 0.7k). **Source is present**; **prebuilt arm64 dylib is checked in** (`src/main/resources/libmetalrender.dylib`, identical copy `libmetalrender_debug_v2.dylib`, 329,728 bytes, force-added despite `.gitignore: *.dylib`). Its 208 exported `Java_*` symbols exactly match the JNI definitions in source (checked with llvm-nm vs grep) **V**. `shaders.metallib` is **not** checked in (`.gitignore: *.metallib`) but is required at runtime by the backend (`J/backend/metal/MetalDeviceBackend.java:224-246`) → a clean clone cannot run without macOS Xcode toolchain (`compile_native.sh`). **V**
6. Licence: custom "Pebbles_boon Software Licence" on 26.1/main — permissive grant **plus** copyleft-ish conditions (same-licence for Derivative Works, source disclosure for binaries, modified-file notices, "not an Official Release" statement). The **older `origin/1.21.8-stable` branch is Apache-2.0** (2025). Repo also carries AGENTS.md/CLAUDE.md/CODEX.md/GEMINI.md stating the owner prohibits AI use and claiming "All rights reserved" — contradicts LICENSE.md but signals owner hostility to AI-assisted reuse. **Recommendation: treat as design reference only; do not copy code into Metallium.** (§17)

---

## 1. Versions, loader, build

| Item | Value | Evidence |
|---|---|---|
| MC | 26.1 (build) / `"minecraft": ">=26.1"` (no upper bound) | `build.gradle:29`, `src/main/resources/fabric.mod.json:22-26` **V** |
| Loader | Fabric Loader 0.19.2, Fabric API 0.149.0+26.1.2, Loom 1.15-SNAPSHOT, Gradle 9.2.1 | `build.gradle:2,30-31`, `gradle/wrapper/gradle-wrapper.properties` **V** |
| Java | toolchain 25, `options.release = 25`, `--enable-preview` for compile and run | `build.gradle:9,47,68-71` **V** |
| Mixins | `metalrender.mixins.json`, `compatibilityLevel JAVA_21`, `defaultRequire: 0` | `src/main/resources/metalrender.mixins.json` **V** |
| Conflicts | `"conflicts": {"sodium": "*"}` (26.1); 1.21.8-stable had `"suggests": {"sodium": ">=0.8.0"}` | fabric.mod.json both branches **V** |
| Entrypoints | `preLaunch` → `MetalRenderPreLaunch` (NativeLoader.load), `client` → `MetalRenderClient` | fabric.mod.json, `J/MetalRenderPreLaunch.java` **V** |
| Version | 2.0.0 | fabric.mod.json **V** |
| Remap | `minecraft "com.mojang:minecraft:26.1"` with no mappings line + `loom.disableRemappedSources` and Mojang-named classes in mixins (`net.minecraft.client.renderer.LevelRenderer`, `Identifier`) → 26.x unobfuscated/Mojang names | `build.gradle`, mixin targets **SI** |
| Dead build flags | `-Pgl2metal*` → `-Dmetalrender.gl2metal.*` vmArgs; no Java code reads `gl2metal` (grep empty) | `build.gradle:57-66` **V** |
| Odd | `programArgs "--quickPlaySingleplayer", "New World"` is inside the `.each` loop (added 8×) | `build.gradle:62-65` **V** |

Runtime OS gating:
- `BackendInstallMixin` installs Metal backend only if `os.name` contains "Mac" **and** `os.arch == "aarch64"`, unless `-Dmetalrender.backend=gl` (`J/sodium/mixins/BackendInstallMixin.java:14-23`). **V**
- `NativeLoader.load()` runs at **preLaunch** for any OS containing "mac"; on failure it throws `RuntimeException` (`J/nativebridge/NativeLoader.java:26-67`). Called un-guarded from `MetalRenderPreLaunch.onPreLaunch` → on Intel Macs (arm64-only dylib) or if `codesign` fails, **game launch crashes at preLaunch** (the client entrypoint's try/catch is too late). **V** (code path) / **SI** (crash outcome).
- `StartupBlocker.shouldBlockStartup()` = Windows → forces an overlay that blocks every `setScreen/setOverlay` (`J/StartupBlocker.java:18-20`, `J/sodium/mixins/MinecraftClientMixin.java:35-82`): the mod deliberately bricks the game UI on Windows instead of disabling itself. Linux: everything silently skipped. **V**
- NativeLoader extracts to `~/.metalrender/natives/`, decides "outdated" by **size only**, runs `xattr -d com.apple.quarantine` and `codesign -s - --force` subprocesses (`NativeLoader.java:37-66, 82-96, 108-132`). **V**

---

## 2. Architecture overview (26.1)

Two rendering modes coexist in one codebase:

### Mode A — "backend mode" (default on Apple Silicon)
- `MetalGpuBackend` → `MetalDeviceBackend` (`J/backend/metal/*`) implements Blaze3D's backend interfaces; sets `MetalRenderClient.setBackendMode(true)` (`MetalGpuBackend.java:39-41`). **V**
- Vanilla renders everything through Blaze3D → Metal. MetalRender's custom entity/particle/cloud/weather renderers are **inactive** because their capture mixins check `captureActive()` = `!isBackendMode() && metalActive()` (`J/render/MetalWorldRenderer.java:593-595`; mixins `EntityRenderMixin`, `ParticleCaptureMixin`, `WeatherRenderMixin`, `CloudRenderMixin`, `LevelCloudWeatherMixin`). **V**
- Terrain: custom mesher → native arena → drawn into vanilla's main pass via `renderTerrainOpaque()/renderTerrainTranslucent()` (`MetalWorldRenderer.java:1046-1105`). Gate `metalrender.backendTerrain` (default true) (`:587-601`). **V**
- Also, `MetalRenderClient` tick handler still calls `initMetal` → `NativeBridge.nInit` (legacy global renderer state, offscreen targets via `ensure_offscreen()` — `N/metalrender.mm:2626-2652`) even in backend mode; device/queue are re-pointed to the backend's via `nBackendAttach` (`N/metalrender.mm:1228-1245`). So legacy globals (proj/MV matrices, fog, atlas) are reused to feed the terrain path. Likely allocates unused IOSurface/offscreen RTs in backend mode. **V** (calls) / **H** (waste; depends on `ensure_offscreen` internals not fully read).

### Mode B — "legacy/capture mode" (`-Dmetalrender.backend=gl`, or if backend creation fails and vanilla falls back to GL)
- GL window as usual; Metal renders world into triple-buffered **IOSurface-backed MTLTextures** (`N/metalrender.mm:2360-2420, 2559`), GL imports them with `CGLTexImageIOSurface2D` into a `GL_TEXTURE_RECTANGLE` (`N/metalrender.mm:5871-5890`), then `IOSurfaceBlitter` does `glBlitFramebuffer` (Y-flip) into an intermediate FBO and composites (`J/render/IOSurfaceBlitter.java:340-400`). **V**
- Custom renderers active: `MetalEntityRenderer` (captures entities via `MetalVertexConsumer`/`MetalRenderCommandQueue`), `MetalParticleRenderer`, `MetalCloudRenderer`, `MetalWeatherRenderer`, block outline, OIT/deferred water (`MetalWorldRenderer.endFrame`, `:1000-1039`). Vanilla entity/particle/weather/cloud passes cancelled. **V**
- Block atlas copied via `glGetTexImage` readback each change (`J/render/MetalTextureManager.java:111-141`) vs. backend mode borrows the vanilla `MetalTexture` directly (`:104-110`). **V**
- MetalFX spatial/temporal upscalers + resolution scale exist only here (`N/metalrender.mm:2330-2560`); the backend present path just draws the source texture full-screen (`N/metal_backend.mm:1103-1146`). **V** (no scaler in present) / **SI** (MetalFX inert in backend mode).

---

## 3. Mixins (26.1) — all registered, all target vanilla

`metalrender.mixins.json` package is `com.pebbles_boon.metalrender.sodium.mixins` but **every class in it targets vanilla**; no `net.caffeinemc`/Sodium imports exist in 26.1 (grep). The "sodium" package name is a leftover from 1.21.8-stable. All 17 mixins + 5 accessors listed in json exist and are active (client side). `J/sodium/backend/MeshShaderBackend.java` is also live (instantiated in `MetalWorldRenderer.onWorldLoad`). **V**

| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| BackendInstallMixin | `Minecraft.<init>` | `@ModifyVariable(at=STORE, index=9)` on `GpuBackend[]` | prepend MetalGpuBackend (fallback = original[0]) |
| MinecraftClientMixin | `Minecraft` | `<init>` TAIL, `setScreen`/`setScreenAndRender`/`setOverlay` HEAD (Windows blocker), `runTick` HEAD/TAIL (QoS, world load, `prepareMeshes`), `@Redirect renderFrame→RenderSystem.flipFrame` (timing), `@Redirect renderFrame→FramerateLimiter.limitDisplayFPS` (precise sleep+spin limiter) | frame lifecycle |
| GameRendererMixin | `GameRenderer.render` / `renderLevel` | before `GuiRenderer.render` (glass backdrop capture, `require=1`); before `CommandEncoder.clearDepthTexture` (legacy blit before hand) | UI + legacy blit |
| VanillaSectionCompileTaskMixin | `SectionRenderDispatcher$RenderSection$RebuildTask`, `$ResortTransparencyTask`, `$RenderSection` | HEAD cancel `rebuildSectionAsync/compileSync/resortTransparency`; `doTask` → `CANCELLED` | kill vanilla meshing |
| WorldRendererTerrainMixin | `LevelRenderer.lambda$addMainPass$0` | `@Redirect` ordinal 0/1 of `ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup,GpuSampler)`; HEAD cancel in legacy | replace terrain draw |
| LevelReadyMixin | `LevelRenderer.isSectionCompiledAndVisible` | force `true` | keep vanilla entity/section logic working with cancelled compiles |
| WorldRendererBlitMixin | `LevelRenderer.renderLevel` | HEAD (capture matrices/fog → `beginFrame`), before `LevelRenderState.reset()` (`require=1`) → `endFrame`/`endFrameExternal` | world frame bracket |
| EntityRenderMixin | `LevelRenderer.extractVisibleEntities` TAIL, `submitEntities` HEAD cancel | legacy capture |
| ParticleCaptureMixin | `ParticleEngine.extract` HEAD cancel | legacy capture |
| WeatherRenderMixin | `WeatherEffectRenderer.render` | legacy |
| CloudRenderMixin | `CloudRenderer.render(...)` (two overloads) | legacy |
| LevelCloudWeatherMixin | `LevelRenderer.addCloudsPass/addWeatherPass(FrameGraphBuilder…)` | legacy |
| ClientWorldMixin | `ClientLevel.sendBlockUpdated` RETURN | schedule section rebuild |
| ChunkLoadMixin | `ClientChunkCache.replaceWithPacketData` RETURN | chunk load → mesh |
| LightUpdateMixin | `LevelLightEngine.queueSectionData` HEAD | light change → remesh |
| SpriteContentsTickerMixin | `SpriteContents$AnimationState.tick` TAIL | animated texture sync (legacy atlas copy) |
| OptionsScreenMixin | `OptionsScreen` lambda (`method_19828` / `lambda$init$2`) | replaces Video Settings screen with `MetalRenderSettingsScreen` |
| accessors | `TextureAtlas.sprites`, `RenderPass.backend`, `SingleQuadParticle.*`, `Particle.*`, `ParticleEngine.particles` | |

Note `defaultRequire: 0` + `require = 0` on most injectors → **silent no-op** when targets move (e.g. on 26.3). Only `GameRendererMixin` (one injector) and `WorldRendererBlitMixin` end hook use `require = 1`. **V**

---

## 4. Blaze3D Metal backend (Mode A) in detail

Files: `J/backend/metal/{MetalGpuBackend,MetalDeviceBackend,MetalCommandEncoderBackend,MetalRenderPassBackend,MetalCompiledPipeline,FamilyTable,MetalBindings,MetalBuffer,MetalTexture,MetalTextureView,MetalSampler,MetalFence,MetalQuery,MetalStagingRing,MetalSurface,MetalNative,MetalBackendStats}.java`, native `N/metal_backend.mm`.

### 4.1 Device/queue/library/surface
- `nDeviceCreate`: `MTLCreateSystemDefaultDevice()` + `newCommandQueueWithMaxCommandBufferCount:64` (`N/metal_backend.mm:326-344`). **V**
- `nBackendAttach(device, queue)` shares them with the legacy native globals (`N/metalrender.mm:1228-1245`). **V**
- Shader library: `/shaders.metallib` read from classpath → `newLibraryWithData`; probes `gui_vertex` to sanity-check (`MetalDeviceBackend.java:224-246`, `N/metal_backend.mm:362-388`). **V**
- Surface: `GLFWNativeCocoa.glfwGetCocoaWindow` → `nSurfaceCreate`: `view.wantsLayer=YES`, new `CAMetalLayer` (BGRA8Unorm, framebufferOnly, maximumDrawableCount=3, displaySyncEnabled=vsync, opaque, contentsScale=backingScaleFactor), `view.layer = layer`; starts a `CVDisplayLink` for pacing (`MetalSurface.java:70-88`, `N/metal_backend.mm:414-440, 315-324`). **V** No GL/IOSurface interop in this mode. **V**
- Capabilities reported: `getMaxTextureSize 16384`, `getUniformOffsetAlignment 256`, `isZZeroToOne true`, `getMaxSupportedAnisotropy 16`, `getVersion "Metal 3"` (hard-coded) (`MetalDeviceBackend.java:361-447`). **V**

### 4.2 Pipelines
- `precompilePipeline`/`compiledPipeline` cache per `RenderPipeline` → `MetalCompiledPipeline` which lazily creates MTLRenderPipelineState per (colorFormat, depthFormat) (`MetalCompiledPipeline.java:174-202`). PSO creation is **synchronous on the render thread** with `newRenderPipelineStateWithDescriptor` and **no MTLBinaryArchive** on the backend path (`N/metal_backend.mm:592-687`); legacy path does use a binary archive at `~/Library/Caches/metalrender_pipeline_cache.metallib` (`N/metalrender.mm:1364-1392`). **V**
- Function constants encode vanilla `ShaderDefines` (ALPHA_CUTOUT float, NO_OVERLAY, EMISSIVE, PER_FACE_LIGHTING, NO_CARDINAL_LIGHTING, APPLY_TEXTURE_MATRIX, DISSOLVE, PORTAL_LAYERS), VS/FS variant ids, vertex-format presence flags, and uniform presence (`MetalCompiledPipeline.java:317-371`; `S/families.metal:5-24`). **V**
- Vertex descriptor generated from `VertexFormat` elements (fixed attribute indices POSITION=0, COLOR=1, UV0=2, UV1=3, UV2=4, NORMAL=5, LINE_WIDTH=6) (`:212-315`). Blend factors / compare ops map 1:1 to MTL enum values (checked against MTLBlendFactor/MTLCompareFunction ordering) (`:385-435`); blend op always Add (`N/metal_backend.mm:664-671`). **V**
- TRIANGLE_FAN emulated by CPU-generated index lists in the staging ring, reading the source index buffer via CPU pointer (`MetalRenderPassBackend.java:288-318`). **V**
- `FamilyTable`: maps `minecraft:core/*` vertex shaders to families TERRAIN, ENTITY, TEXT, GUI, SKY, CLOUDS_WEATHER("world"), LINES, PARTICLE, POST, SPRITE_ANIMATION, plus RenderPipelines field-name overrides via reflection (END_SKY, SUNRISE_SUNSET, WEATHER_*, ANIMATE_SPRITE_*), and per-shader variant ids (`FamilyTable.java:54-170`). Logs coverage of `RenderPipelines.getStaticPipelines()` at first use (`:253-278`). **V**
- Unmapped/foreign pipelines → `fallback_vertex(_empty)` + `fallback_fragment` = solid magenta (`S/families.metal:1109-1131`). **V** Consequence: any mod adding its own RenderPipeline with own shaders draws magenta; mods reusing vanilla shader IDs work. **SI**

### 4.3 Resource binding
- Fixed slot table: uniform blocks by name → Metal buffer slots 16–28 (`Projection 16, Globals 17, Fog 18, DynamicTransforms 19, Lighting 20, ChunkSection 21, LightmapInfo 22, SpriteAnimationInfo 23, CloudInfo 24, SamplerInfo 25, post configs 26/27, CloudFaces texel buffer 28`); samplers by name → texture slots 0–11, cube textures forced to slot 15 (`J/backend/metal/MetalBindings.java:13-60`). Unknown uniform names are silently skipped (`MetalRenderPassBackend.java:236-256`). **V**
- Uniform block size is checked against native `sizeof(MR*)` structs from `S/families_layout.h` (`N/metal_backend.mm:392-412`); a too-small block **throws IllegalStateException** (crash) (`MetalBindings.java:85-99`). **V**
- Redundant-state filtering: per-slot buffer/offset, texture/sampler, vertex buffer, PSO+depth state, scissor caches, invalidated on encoder change (`MetalCommandEncoderBackend.java:87-151`). Buffers/textures bound with plain `setVertexBuffer/setFragmentBuffer` (no argument buffers in backend path). **V**

### 4.4 Command buffers, encoders, passes
- **One `MTLCommandBuffer` per frame** (lazily created; flushed early only on sync points), one render encoder at a time; blits end the render encoder (`N/metal_backend.mm:149-185`). **V**
- **Render-pass coalescing**: `createRenderPass` reuses the currently open encoder if the targets are the same and no clear is requested; depth-only clears can be "folded" into the open encoder via a fullscreen clear draw (`nCtxClearDepthFolded`) (`MetalCommandEncoderBackend.java:256-308`; `N/metal_backend.mm:923-1010`). Passes labelled `metalrender_*` always split (`SPLIT_PASSES`). **V**
- **Lazy clears**: `clearColorTexture/clearDepthTexture/clearColorAndDepthTextures` are recorded and turned into `MTLLoadActionClear` on the next pass that targets the texture, or flushed before sampling/copying (`:153-169, 319-372`). TBDR-friendly. **V**
- Store actions set to `Unknown` at encoder start and resolved at `endEncoding`: color always Store; main depth `DontCare` at present time (`N/metal_backend.mm:157-175, 1103-1110`). **V**
- `drawMultipleIndexed` is just a loop of individual draws (no multi-draw/indirect batching) (`MetalRenderPassBackend.java:173-186`). **V**
- Uploads: `writeToBuffer` writes directly into shared memory if the buffer's last GPU use has completed, otherwise stages + blit copy (`MetalCommandEncoderBackend.java:375-391`); textures always staged via 16 MiB-block `MetalStagingRing` + blit with CPU format conversion for RGB/LA/L (`:440-511`, `MetalStagingRing.java`). **V**
- `mapBuffer(write=true)` hands back the shared memory directly **without waiting** for in-flight GPU use (only `read` waits) → potential CPU/GPU write-after-read race on buffers mapped for writing while still in flight (`MetalCommandEncoderBackend.java:394-410`). **V** (code) / **H** (whether vanilla ever maps in-flight buffers for write; vanilla uses ring-buffered uniform storage, so maybe benign).
- `copyTextureToBuffer` / `copyTextureToTexture` ignore array layers (slice 0 only) (`:521-542`). **V**
- Timer queries implemented with CPU-side `addScheduledHandler/addCompletedHandler` timestamps, not GPU counters (`N/metal_backend.mm:1220-1260`); separate optional GPU timestamp sampling per pass via `MTLCounterSampleBuffer` for the profiler (`:196-313`). **V**

### 4.5 Sync, frames in flight, present
- `MTLSharedEvent` serials: every submit signals `openSerial++`; `GpuFence` = serial; buffers track `lastUseFrame` serial (`N/metal_backend.mm:1148-1210`, `MetalFence.java`). **V**
- **≤2 frames in flight**: at frame end `frameSerials` shift; the next `mr_cmd()` waits for serial of frame N-2 (`mr_frame_wait`, 10×1 s timeout loop) (`N/metal_backend.mm:130-155, 1196-1201`). **V**
- Present: `nextDrawable` → fullscreen-triangle blit of vanilla's final color texture into the drawable (`mr_present_vertex/fragment`) → `presentDrawable`. **"Paced present"** (default on, `metalrender.pacedPresent`): with vsync off, uses CVDisplayLink ticks and skips presenting (but still renders) frames that arrive within the same refresh (`mr_should_present`), counting `g_backendSkippedPresents`; toggles `displaySyncEnabled` dynamically (`N/metal_backend.mm:1077-1146`). **V** This trades latency/tearing for frame drops; uncapped FPS counter will exceed presented frames. **SI**

### 4.6 Textures/samplers
- `TextureFormat` handled: RGBA8, RED8, RED8I, DEPTH32 (exhaustive switch → these are 26.1's whole enum) (`MetalTexture.java:44-51`). Render-attachment textures are Private, everything else Shared. Cube & 2D-array support. **V/SI**
- Samplers cached by (address U/V, min/mag, aniso, maxLod); only REPEAT vs clamp and linear vs nearest; mip filter hard-coded `2` (linear) (`MetalDeviceBackend.java:288-309`). **V**

---

## 5. Terrain pipeline

### 5.1 Scheduling & threading
- Chunk load (`ChunkLoadMixin`), block updates (`ClientWorldMixin`), light updates (`LightUpdateMixin`) enqueue work into `MetalWorldRenderer` queues; `prepareMeshes()` runs at `runTick` HEAD on the render thread with a large set of hand-tuned budgets/priorities (scan rings, "turn priority" cone, urgent edits, backlog pressure levels, LOD ring refresh, recency eviction, far pruning) (`J/render/MetalWorldRenderer.java:200-275, 614-752, 1420-1700, 2086-2680, 2804-3195`). **V**
- Workers: two `ThreadPoolExecutor`s with `PriorityBlockingQueue` (immediate = perf/2 threads, background = perf-1; perf-core count via native `nGetPerfCoreCount`) (`J/render/chunk/CustomChunkMesher.java:587-618`). **V**
- **Section snapshots are captured on the worker thread** directly from the live `ClientLevel` (`captureSectionSnapshot(world, …)` inside the submitted task, `CustomChunkMesher.java:686-721`), including `PalettedContainer` states and light engine reads (`:1780-1900`). Vanilla/Sodium copy section data on the main thread before handing to workers. → data race with main-thread chunk/light updates (torn reads, CME risk). **SI**
- Cave culling worker thread `MetalRender-CaveCull` runs a CPU BFS over per-section 6×6 visibility masks (`computeVisibilityMask`) — vanilla-style occlusion graph — at ≤10 Hz, results uploaded as visible-section key set (`MetalWorldRenderer.java:44-197`, `J/culling/CullingOrcreator.java`, `N/metalrender.mm:6247-6265`). **V**

### 5.2 Meshing
- Uses vanilla model data: `mc.getModelManager().getBlockStateModelSet()`, `BlockStateModel.collectParts(random, parts)`, `BlockStateModelPart.getQuads(dir)` / `useAmbientOcclusion()`, `BakedQuad.position(i)/packedUV(i)/direction()/materialInfo().{sprite,shade,tintIndex,isTinted}`, `BlockColors` for tints (`CustomChunkMesher.java:2420-2460, 3515-3580, 3933-4000`). **V** (Also documents 26.x model API names — useful for Metallium, label SI for semantics.)
- Does **not** call vanilla `ModelBlockRenderer`/`SectionCompiler`; **no Fabric Rendering API (FRAPI) hooks** → custom `FabricBakedModel`/`emitBlockQuads` models (Continuity, CTM-style, connected textures, many decorative mods) render only their vanilla-fallback quads or nothing. **SI**
- Own smooth lighting/AO; own fluid mesher that **returns early for any fluid other than water/lava** and hard-codes `minecraft:block/water_*`/`lava_*` sprites (`:1670-1691, 3629-3690`) → modded fluids invisible. **V**
- Block entities: no handling in 26.1 code (grep). Vanilla collects renderable block entities from compiled section meshes; since compilation is cancelled, **chests/signs/banners/beds etc. may not render** (unless 26.1 sources them elsewhere). **H** (needs MC source).
- Translucency: block-model quads always go to `solidBuffer` (`emitBakedQuad(..., water=false, ...)` only call sites, `:3563, 3572, 3939`); only fluids go to the water buffer. Arena shaders have only solid (alpha=1) and cutout (`discard if a<0.5`, alpha forced 1) variants (`S/metalrender.metal:362-400`). → **stained glass/ice/slime/honey lose translucency** (become cutout/opaque). **SI**
- Greedy meshing for full cubes at LOD≥1, cross-plant culling at LOD≥2, "skeleton first", per-distance LOD tiers (`J/render/lod/LodPolicy.java`, `CustomChunkMesher.java:2969-3010, 3515-3535`). LOD alters geometry (drops detail quads) — visible popping by design. **V**
- Compression: `QuadTiers` packs quads into tier A/B/C = 16/32/64 bytes (origin, edges, sprite id (13 bits), UV code, tint, light byte, shade), grouped into "units" (≤32 quads, single facing bucket + plane for unit backface culling, 5-bit AABB) and 32-byte section records (`J/render/chunk/QuadTiers.java:10-45, 780-990`; `S/terrain_decode.h`). Only active when arena ready and backend mode (`tiersActive()`, `CustomChunkMesher.java:403-405`). **V**

### 5.3 GPU memory & upload
- Native "arena": one big shared `MTLBuffer` sub-allocated in KiB units (free-list allocator), plus unit and section buffers; cap = min(8 GiB, RAM/4) (`-Dmetalrender.arenaCapMb`); grow/evict farthest sections when >90% (`CustomChunkMesher.java:411-433`; `N/metalrender.mm:7718-7750, 7813-7860`). **V**
- `nArenaPublish` memcpy's quad payload directly from a Java direct `ByteBuffer` into the shared arena (UMA, no blit), patches unit firstQuad, writes section record, publishes with release fence on `flags` (`N/metalrender.mm:7868-7960`). Old allocations are "retired" and collected later (deferred reuse). **V**
- Per-frame snapshot of buffer handles (double-buffered by frame parity) in `nTerrainPrepare` (`N/metalrender.mm:8102-8130`). **V**

### 5.4 Culling & draw
- Per frame (`beginFrame` → `nTerrainPrepare`): waits for frame N-2 (`mr_backend_wait_frame`), then either CPU cull (`mr_arena_cpu_cull`) or **GPU compute cull** (`terrain_cull_reset` → `terrain_cull` (1 thread/section, distance + frustum (4 planes) + per-unit backface-plane + unit AABB test, SIMD prefix sums, atomics) → `terrain_cull_finish`) writing 8 indirect `drawIndexedPrimitives` arg records (2 layers × 4 size classes 24/48/96/192 verts) and per-class unit lists. Separate command buffer committed on the same queue before the frame CB (`N/metalrender.mm:8130-8200`; `S/cull_and_encode.metal:189-294`). Optional CPU "oracle" comparison (`-Dmetalrender.cullOracle`). **V**
- Draw: `mr_arena_encode` sets 8 draws with a 32-quad static uint16 index buffer, `terrain_vs` pulls unit/quad by `instance_id`/`vertex_id` (`N/metalrender.mm:8202-8245`; `S/metalrender.metal:319-358`). Camera-relative integer block + fraction for precision. **V**
- No Hi-Z / occlusion queries in the active path: `S/occlusion_culling.metal` (`occlusion_cull`), `S/culling.metal` (`cull_regions`) and `S/visibility_buffer.metal` are compiled into the metallib but never looked up by name in any .mm (grep). **V** Occlusion = CPU cave BFS only.
- List overflow: units beyond `listCapacity` are silently dropped (`S/cull_and_encode.metal:276-281`, `terrain_cull_finish` clamps count). **V** (missing geometry if capacity too small — **H** whether capacity grows).
- Opaque terrain is drawn in vanilla's main pass using the main render target's color+depth (`drawInBackendPass`), with projection Y-flip for Metal in external mode (`MetalWorldRenderer.java:805-818`) and RGBA8 "twin" PSOs because Blaze3D targets are RGBA8 while legacy PSOs were BGRA8 (`N/metalrender.mm:1290-1345`). Water drawn in the translucent group via `nWaterExternalDraw` → `nDrawDeferredWaterPass`. **V**
- Alternative terrain paths still in code: per-chunk indexed draws (`nDrawAllVisibleChunks`, `N/metalrender.mm:3742`, also called after arena encode for non-tiered meshes), ICB path (`fragment_terrain_icb*`, `cull_and_encode` kernel), **mesh-shader path** (`object_terrain`/`mesh_terrain`, Apple7+/macOS13, `S/mesh_terrain.metal:97-330`, `N/meshshader.mm`) selectable via `-Dmetalrender.terrainPath=mesh|vertex` or config `terrainPath`. **V** (exists) / **SI** (default "auto" picks arena in backend mode).

---

## 6. Shaders (`S/`) — inventory

| File | Contents | Used? |
|---|---|---|
| `families.metal` (1170 l) + `families_layout.h` | Backend family shaders: gui, text, sky, lines (screen-space line expansion via vertex_id), post (blit, box_blur, color_convolve, bits, entity_sobel, outline blur, invert, spiderclip, transparency/fabulous composite), sprite (animate_sprite blit/interpolate), terrain, entity (incl. glint, end portal, beacon, shadow, lightning, leash, outline, water mask, dissolve), particle, world (clouds via `CloudFaces` texel buffer, world border), fallback (magenta), `mr_present_*`, `mr_clear_*`; std140-mirroring MSL structs for every vanilla UBO | **Active (backend)** **V** |
| `metalrender.metal` (611 l) | legacy terrain VS/FS variants, `terrain_vs` + `fragment_arena_solid/cutout` (arena), water surface, debug lines, `mfx_preserve_alpha` | arena + legacy active; several variants (`vertex_terrain`, `fragment_terrain_cutout`, `_solid`, `_cutout_inhouse`, `_icb_cutout`) never looked up **V** |
| `terrain_decode.h` | Section/Unit records, tier A/B/C bit decoding, face-vertex tables | Active **V** |
| `cull_and_encode.metal` | `cull_and_encode` (ICB encode), `reset_cull_stats`, `terrain_cull_reset/terrain_cull/terrain_cull_finish` | terrain_cull* active; cull_and_encode only on ICB path **V** |
| `mesh_terrain.metal` (Metal 3) | object/mesh shader terrain + 3 fragment variants | optional path **V** |
| `entity.metal` | legacy entity/particle/weather/shadow shaders | legacy only **V** |
| `oit_transparency.metal` | weighted-blended OIT accum/composite, TBDR variant | legacy (`fragment_oit_terrain_accum`, `*_composite_tbdr` looked up); `vertex_oit`/`fragment_oit_accum` unused **V** |
| `translucency_sort.metal` | `translucency_bitonic_step` GPU sort | gated by `enableGpuTranslucencySort` (default **false**) **V** |
| `motion_vectors.metal` | `generate_motion_vectors` (for MetalFX temporal) | legacy only **V** |
| `occlusion_culling.metal`, `culling.metal`, `visibility_buffer.metal` | Hi-Z occlusion, region cull, visibility buffer/deferred | **dead** (never referenced) **V** |

Also an inline MSL source string fallback in `load_shaders()` if no metallib is found (`N/metalrender.mm:1420-1750`). **V**

---

## 7. Entities, particles, clouds, weather
- Backend mode: all vanilla (through ENTITY/PARTICLE/CLOUDS_WEATHER families). **V**
- Legacy mode: `J/entity/MetalEntityRenderer.java` (1.4k l), `MetalRenderCommandQueue` (1k l), `MetalVertexConsumer` capture vanilla submit calls and re-encode in Metal; `J/particle/MetalParticleRenderer.java` reads particle internals via accessors (billboard only); `J/render/MetalCloudRenderer.java`, `MetalWeatherRenderer.java` are full reimplementations. **V** These are inherently fragile vs. modded entity renderers. **SI**
- `LevelReadyMixin` forces `isSectionCompiledAndVisible` → true, so vanilla never hides entities in uncompiled/invisible sections (perf cost, no occlusion-based entity culling). **V/SI**

## 8. Performance package
`J/performance/`: `PerformanceController` (frame start/end), `BuildBudgetEstimator` (in-flight build budget), `AdaptiveResolutionController` (frame-budget-driven scale; only affects legacy renderer resize), `MetalRenderProfiler` (1.1k l; CSV dev-run logging started unconditionally in `onInitializeClient` → `startDevelopmentRunCsv()` `J/MetalRenderClient.java:50-51`), HUD/profiler overlays. Native: thread QoS `nSetThreadQos(0x21)` (USER_INTERACTIVE) on render thread, `nPreciseSleep` + spin limiter replacing vanilla FPS limiter (`MinecraftClientMixin.java:84-200`). **V** `prioritizeFpsOverTps` silently **rewrites the user's simulation distance to 5 and saves options** (`J/MetalRenderClient.java:208-220`). **V**

## 9. Config / GUI / commands
- `config/metalrender.json` (`J/config/MetalRenderConfig.java`, many fields; notable defaults: mesh shaders on, ICB on, argument buffers on, cluster frustum culling **off**, GPU translucency sort **off**, programmable blending off, distance LOD on, gpuDrivenTerrain on). Plus ~25 `-Dmetalrender.*` system-property flags (backend, backendTerrain, gpuCull, cullOracle, lazyClears, splitPasses, pacedPresent, preciseLimiter, qos, caveCulling, circularDistance, cancelVanillaCompile, quadTiers/quadCensus/verifyCompress, arenaCapMb, terrainPath, atlas*, backend.failInit, profileVanilla). **V**
- One-shot deep debug via `config/metalrender-debug-next-run.flag`. **V**
- `MetalRenderSettingsScreen` (1.8k l, "Liquid Glass" theme, custom glass blur shaders `metalrender:core/glass(_blur)` mapped to GUI family variants 3/4 in `FamilyTable.java:210-216`) replaces vanilla Video Settings (`OptionsScreenMixin`). Status panel shows a "Sodium" dot (`MetalRenderSettingsScreen.java:654-655`) although the mod declares a conflict with Sodium. **V**
- `/metalrender` client commands (`J/command/MetalRenderCommands.java`). **V**

## 10. Error handling & fallback
- Backend creation failure → `BackendCreationException` (sneaky-thrown) → vanilla tries `original[0]` (presumably GL) (`MetalGpuBackend.java:34-49`, `BackendInstallMixin.java:23`). Test switch `-Dmetalrender.backend.failInit=true`. **V** (code) / **SI** (vanilla fallback semantics).
- After that, legacy mode would auto-start (config default `enableMetalRendering=true`, `initMetal` on tick) → GL+IOSurface path. **SI**
- Runtime: PSO failure → draw skipped and pipeline marked failed; unmapped pipeline → magenta; uniform too small → exception; native code does no handle validation (raw pointers through JNI; misuse = segfault). JNI layer returns 0/false + `g_backendLastError` string. **V**
- Toggle off at runtime (`syncCfgLocked`) tears down the legacy renderer and calls `levelRenderer.allChanged()`; but cannot remove the Blaze3D backend at runtime (vanilla terrain compile cancel depends on `metalActive()`, so disabling returns terrain to vanilla compile through the Metal backend). **SI**
- HEAD commit "error handling" (13d9794). No TODO/FIXME markers anywhere (grep). Stub JNI no-ops remain (`nDrawTerrain`, `nDrawOverlay`, `nOnWorldLoaded` — `N/metalrender.mm:2675-2688`); legacy handle is a constant `0x1` and all legacy state is global singletons (`:2651`). **V**

## 11. Shader packs / Iris / mod compatibility
- **Iris/shaderpacks: no support of any kind** (no Iris classes, no shaderpack parsing, no GLSL translation). In backend mode there is no GL context, so any GL-dependent mod (Iris, Sodium, raw `GL11` callers, GL-based post-processing) cannot work. **V/SI** README lists Iris/Sodium/VulkanMod/C2ME as incompatible — consistent with code. **V**
- Fabric API rendering: no FRAPI/Indium path (§5.2); `WorldRenderEvents`-style hooks still fire if vanilla code path is intact, but anything that draws with custom shaders → magenta (§4.2). **SI**
- README "should be compatible with most other mods" is not supported by code given the above. **V/SI**
- Sodium Extra / Reese's options: N/A (Sodium conflict). **V**

## 12. Likely correctness gaps (ranked)
1. Modded RenderPipelines/shaders → magenta; core-shader resource packs ignored (§4.2). **V/SI**
2. Translucent blocks rendered cutout/opaque (§5.2). **SI**
3. Modded fluids invisible; FRAPI models degraded (§5.2). **V/SI**
4. Possible missing block entities due to cancelled compiles (§5.2). **H**
5. Off-thread live world reads in mesher (§5.1). **SI**
6. `mapBuffer(write)` doesn't wait for GPU (§4.4). **H**
7. Unit-list overflow silently drops geometry (§5.4). **H**
8. preLaunch hard crash on Intel Mac / codesign failure (§1). **SI**
9. Paced present drops frames rather than pacing rendering (§4.5). **SI**
10. Mixins `require=0` → silent breakage on version bumps; `minecraft >=26.1` with no upper bound (§1, §3). **V**
11. Layer-0-only texture copies (§4.4). **V**
12. `prioritizeFpsOverTps` mutates user options (§8). **V**

## 13. Strongest ideas worth borrowing (as concepts, not code)
1. **Implement Metal as a Blaze3D `GpuBackend`** inserted into vanilla's backend list, with GLFW `NO_API` + CAMetalLayer on the NSWindow content view. Gives whole-frame Metal, zero GL interop, clean fallback to the next backend.
2. **Render-pass coalescing + lazy clears + deferred store-action resolution** (DontCare for dead main depth at present) — directly targets Apple TBDR bandwidth.
3. **Function-constant specialization of a small set of hand-written MSL families keyed off `RenderPipeline` shader IDs/defines** — fast PSO count, no runtime translation for vanilla.
4. **UMA arena with direct memcpy publish** (no staging/blit), KiB free-list sub-allocation, deferred retirement by frame serial, eviction by distance.
5. **Compressed quad tiers (16 B/quad for greedy-tiling quads) + vertex pulling + per-"unit" (≤32 quads, single facing) backface-plane culling + GPU compute frustum cull writing indirect args with SIMD prefix sums**, with a CPU "oracle" mode for validation.
6. Injecting custom terrain **inside vanilla's main-pass encoder** (grab native encoder from `RenderPass.backend`) so vanilla depth/ordering stays intact.
7. MTLSharedEvent serial tracking for buffer reuse/fences, 2 frames in flight; GPU per-pass timestamps via `MTLCounterSampleBuffer` for a profiler.
8. RGBA8 "twin" PSO trick when mixing BGRA drawables with RGBA Blaze3D targets (better: render all in RGBA and convert at present, as the backend already does).

## 14. Most important limitations (for Metallium's goals)
- Fundamentally incompatible with Iris/shaderpacks and Sodium (both by declaration and design: no GL, no GLSL). Metallium's Iris goal requires a GLSL→MSL (glslang+SPIRV-Cross) path that MetalRender lacks entirely.
- Vanilla-only shader coverage; modded pipelines magenta.
- Own mesher re-implements vanilla/Sodium semantics incompletely (translucency, fluids, FRAPI, possibly block entities) — the opposite of "max compatibility".
- Synchronous PSO compiles (hitches) on the backend path; no binary archive there.
- Large amount of hand-tuned heuristics (dozens of magic constants in `MetalWorldRenderer`), global-singleton native state, dead/alternate paths (mesh shaders, ICB, visibility buffer, Hi-Z, OIT legacy) — high maintenance surface.
- Build reproducibility: metallib not committed; dylib committed; macOS-only toolchain.

---

## 15. origin/1.21.8-stable (Sodium-integrated variant) — glance
- Despite the branch name, targets **MC 1.21.11**, Fabric Loader 0.17.3, Fabric API 0.141.3+1.21.11, `modCompileOnly maven.modrinth:sodium:mc1.21.11-0.8.2-fabric`; fabric.mod.json `version 0.1.7`, `"license": "Apache-2.0"`, `suggests sodium >=0.8.0` (`git show origin/1.21.8-stable:build.gradle`, `:src/main/resources/fabric.mod.json`). **V**
- LICENSE.md on that branch: Apache 2.0, "Copyright 2025 Pebbles_boon". **V**
- Sodium integration via `@Pseudo` mixins: `RenderSectionManager.renderLayer` HEAD cancel → iterates Sodium's `SortedRenderLists` / `ChunkRenderList.sectionsWithGeometryIterator` and issues **one Metal draw per section** from MetalRender's own meshes (`sodium/mixins/ChunkRenderManagerMixin.java:15-110`); `SodiumWorldRenderer.setupTerrain/drawChunkLayer` hooks (`sodiumhook/SodiumWorldRendererMixin_CM.java`, `SodiumRendererMixin.java`); also a mixin on old `me.jellysquid` package. Uses Sodium only for visibility; meshes are MetalRender's. **V**
- LWJGL GL interception mixins (`sodium/mixins/lwjgl/GL11CMixin.java` on `org.lwjgl.opengl.GL11C.glDrawElements`, `GL15CMixin`) feeding `backend/GLIntercept` — an earlier "GL→Metal swap" idea. Note: `GL11CMixin`/`GL15CMixin` are **not listed** in that branch's mixins json (json lists 15 entries, no `lwjgl.*`) → dead code there. **V**
- Composition via IOSurface (`render/IOSurfaceBlitter.java`), custom GUI/text renderers (`render/gui/MetalGuiRenderer.java`, `render/text/MetalTextRenderer.java`). `bin/main/*` and `shaders.metallib` checked in. **V**
- History: 8 commits (2025-09-16 → 2026-03-12). Very early-stage. **V**

## 16. origin/main
Stub: `LICENSE.md` (custom licence), `gibberish.txt`, `metalrender.java`, `readme.md`; last commit "new license forgot about this branch". **V**

## 17. Licence analysis (26.1 `LICENSE.md`, "Pebbles_boon Software Licence", © 2026)
Key terms (quoted):
- Grant: "worldwide, non-exclusive, royalty-free licence to use, reproduce, modify, prepare derivative works of, and distribute the Software and Derivative Works, including for commercial purposes."
- "Derivative Work" = "a work based upon or incorporating the Software or a substantial portion of it … Any separate work that merely accompanies, uses, is distrubuted alongside, communicates with, or interacts with the Software does not, by itself, constitute a Derivative Work."
- §2(a) "You MUST, where reasonably practicable, cause the modified files to carry prominent notices stating that you changed them."
- §2(b) "You MUST explicitly state that the Software is a Modified Version and is NOT an Official Release of the original Software."
- §2(c) "All Derivative Works must be distributed under the version of this Licence under which the Software was originally received, or any later version expressly designated by the Licensor".
- §2(d) "If you distribute a Derivative Work in compiled, executable, or other object-code form, you MUST also make the corresponding Source Code of that Derivative Work available under this Licence".
- Also: recipients must get a copy of the licence; no additional restrictions; §3 no use of project name to imply endorsement; §4 patent grant with litigation termination; §6 30-day cure period; §7 no sublicensing (each recipient licensed directly).
Assessment:
- Effectively a **strong, project-scoped copyleft** (closer to GPL/MPL-at-work-level than to MIT): copying "a substantial portion" of MetalRender into Metallium would force **all of Metallium** (the Derivative Work) to be distributed under this bespoke licence, with source disclosure and modified-file notices. It is **not OSI-approved and not GPL/LGPL/Apache/MIT-compatible** in the outbound direction (Metallium couldn't stay LGPL/MIT/Apache; combining with LGPL-3.0 Sodium/Iris-derived code would create conflicting requirements). "Any later version expressly designated by the Licensor" gives the author unilateral future control.
- "Interacts with / communicates with" carve-out means a separate mod that merely coexists is fine; re-implementing ideas (not expression) is fine.
- **Apache-2.0 alternative**: code as it existed on `origin/1.21.8-stable` (2025) is Apache-2.0 → reusable with NOTICE/attribution, but that branch lacks all the valuable 26.1 work (Blaze3D backend, arena, tiers, GPU cull). **V**
- Provenance/attitude: AGENTS.md/CLAUDE.md/CODEX.md/GEMINI.md (35 lines each) state the owner prohibits AI training/inference/reproduction and say "All rights reserved. See LICENSE." — inconsistent with LICENSE.md's grant, but a clear signal the owner objects to AI-assisted reuse; combined with the copyleft terms, the safe course is **clean-room: learn architecture, write Metallium independently, cite as prior art.** **V** (content) / legal conclusion = **H** (not legal advice).

## 18. Commit history quality
- 463 commits on 26.1 (2025-09-16 → 2026-10-17); authors `webblepebbles` 249, `NewUsername` 207, a few others. 74/463 subjects match fix/oops/forgot/revert/wip/stash. Messages are terse and informal ("netive rebuild", "left these in stash oops looks good tho", "remove smh that was unintentionally capping FPS", "GPU lodded"). Many "native rebuild" commits only replace the binary dylib (e.g. `eed9079`: 2 binary files changed). 40 commits dated 2026-10-17 (today) suggest a bulk push. No tests, no CI files. **V**
- Log/UI strings use joke spelling ("metalrender weady", "wendewer", "scween") — cosmetic but indicates hobby-grade polish. **V**

## 19. Doc-vs-code discrepancies
- AGENTS/CLAUDE/etc. claim "All rights reserved" vs LICENSE.md permissive-copyleft grant. **V**
- README "compatible with most other mods" vs magenta fallback / no FRAPI / no GL. **SI**
- README `/metalrender status` lists "temporal AA" — only MetalFX temporal in legacy mode; no TAA in backend path. **SI**
- Settings screen shows Sodium status while fabric.mod.json conflicts with Sodium. **V**
- `build.gradle` gl2metal flags have no consumer. **V**
- 1.21.8-stable branch name vs actual MC 1.21.11 target. **V**

## Open questions / not verified
- Exact vanilla 26.1 `GpuBackend` iteration/fallback semantics and whether 26.3 changed `GpuBackend`/`GpuDeviceBackend`/`CommandEncoderBackend` signatures (no MC source available). All Blaze3D API statements are inferred from MetalRender's implementations.
- Whether vanilla 26.1 includes a Vulkan backend in the `GpuBackend[]` list (BackendInstallMixin keeps only `original[0]` as fallback, discarding any others — if vanilla lists [Vulkan, GL], MetalRender would drop GL fallback). **U**
- Block-entity rendering with cancelled section compiles (§5.2) — needs MC source or runtime test.
- `ensure_offscreen()` behaviour in backend mode (wasted IOSurfaces?) — not read in full.
- Whether `listCapacity` for arena unit lists grows dynamically.
- Actual visual coverage: the runtime `family coverage: N mapped, M unmapped` log was not observed; static list may miss some 26.1 shaders.
- Whether `--enable-preview`-compiled classes actually use preview features (would require `--enable-preview` at runtime in the official launcher).
- Binary reproducibility of the checked-in dylib (only exported-symbol parity with source verified).
- Performance claims: none measured; no benchmark data in repo.
