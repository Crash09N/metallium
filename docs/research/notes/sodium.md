# Sodium audit (tag mc26.3-0.9.2 @ 03198cb3; origin/dev = 0.9.3-beta.1 @ 148151cf)

Paths below are relative to `/home/user/research/sodium` unless prefixed with `metallum/`.
Abbreviations: `C/` = `common/src/main/java/net/caffeinemc/mods/sodium/`, `CC/` = `C/client/`, `API/` = `common/src/api/java/net/caffeinemc/mods/sodium/api/`, `BOOT/` = `common/src/boot/java/net/caffeinemc/mods/sodium/client/`, `FR/` = `frapi/src/main/java/net/caffeinemc/mods/sodium/`.

Labels: **[V]** Verified (read in code), **[SI]** Strongly inferred, **[H]** Hypothesis, **[U]** Unverified.

---

## 0. TL;DR (decision-critical)

1. **[V] Sodium 0.9.2 for 26.3 does NOT have its own OpenGL renderer anymore.** The old `net.caffeinemc.mods.sodium.client.gl.*` package (GlBufferArena, GlFence, GlProgram, CommandList, etc.) is gone. Terrain rendering goes entirely through Minecraft's GPU abstraction, which in 26.3 lives in **`com.mojang.renderpearl.*`** (`api.device.GpuDevice`, `api.commands.RenderPass/GpuFence`, `api.pipeline.RenderPipeline/BindGroupLayout`, `api.buffers.GpuBuffer/GpuBufferSlice`, `api.vertex.VertexFormat`, `api.GpuFormat`, `backend.api.GpuDeviceBackend`, `backend.opengl.*`, `backend.vulkan.VulkanDevice`, `frontend.FrontendGpuDevice`). `com.mojang.blaze3d.*` remains only for `RenderSystem`, vertex building (`VertexConsumer`, `BufferBuilder`, `PoseStack`), `Std140Builder`, platform/window. **[SI]** (package naming inferred from imports, Mojang source unavailable).
2. **[V] Only 5 files import `org.lwjgl.opengl`**, and all are diagnostics/workarounds, gated to the GL backend (§2). No terrain/draw/upload code calls GL.
3. **[V] Sodium has an explicit backend switch `DrawBackend {OPENGL, VK_MULTIDRAW, VK_INDIRECT}`** (`CC/gpu/device/backend/DrawBackend.java:8-29`). Choice is `backend instanceof VulkanDevice ? (multiDrawDirectInterleaved ? VK_MULTIDRAW : multiDrawIndirect ? VK_INDIRECT : throw) : OPENGL`. **Any non-Vulkan backend (including Metallum's `MetalBackend`) is classified as `OPENGL`** — but the "OPENGL" path does NOT call GL: it calls `RenderPass.multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, int)` (`CC/gpu/device/batch/GLDrawBatch.java:33-38`), which Metallum implements (`metallum/src/main/java/com/metallum/render/MetalRenderPass.java:226-250`). Sodium does **not** refuse or disable itself on non-GL backends; it has no OS/backend whitelist.
4. **[V] Graphics API selection is vanilla's** (`net.minecraft.client.PreferredGraphicsApi`, option `Options#preferredGraphicsBackend()`); Sodium only surfaces it in its settings page (`CC/gui/SodiumConfigBuilder.java:314-331`, values DEFAULT/OPENGL/"Prefer Vulkan", REQUIRES_GAME_RESTART). Metallum overrides `PreferredGraphicsApi#getBackendsToTry` to always return `[new MetalBackend()]` and `#caption` to "Prefer Metal" (`metallum/src/main/java/com/metallum/mixin/PreferredGraphicsApiMixin.java:12-22`), and its Sodium compat mixin redirects Sodium's `CyclingControl$CyclingControlElement#extractRenderState` → `EnumOption#getElementName` so every `PreferredGraphicsApi` element shows `DEFAULT.caption()` (= "Prefer Metal") (`metallum/src/main/java/com/metallum/compat/sodium/mixin/SodiumPreferredGraphicsApiMixin.java:11-28`). i.e. it is purely cosmetic; Sodium's choice is ignored by Metallum's backend injection.
5. **[V] Consequence:** Sodium 0.9.2 already runs on any conformant renderpearl backend. A Metal backend at the renderpearl layer (Metallum's approach) gets Sodium's entire terrain pipeline for free, unmodified. A Sodium *fork* is not technically necessary for Metal.
6. **[V] License: PolyForm Shield 1.0.0** (root `LICENSE.md`; `fabric.mod.json` `"license": "Polyform-Shield-1.0.0"`; `neoforge.mods.toml:3`). Noncompete clause forbids using/modifying/distributing Sodium to provide any product that competes with Sodium. No `Required Notice:` and no `Licensor Line of Business:` lines exist anywhere in the repo. The `api` source set has **no separate license** (falls under PolyForm Shield). 24 files carry Apache-2.0 headers (FRAPI/Indigo-derived, from FabricMC), interval tree is MIT. A Metal *fork/derivative* of Sodium marketed as a Sodium alternative is very likely prohibited (§16).

---

## 1. Repository layout / modules

- `common/src/main` — 588 java files total in `common` (all source sets) [V: `find common -name '*.java' | wc -l` = 588]. Core renderer.
- `common/src/api` — public API (`net.caffeinemc.mods.sodium.api.*`): config API, vertex API, memory intrinsics, colors, block-entity predicates (§14).
- `common/src/boot` — pre-launch environment probing, Windows/Unix platform natives, driver workarounds (`GlContextInfo`, `NvidiaWorkarounds`, `AmdWorkarounds`, `IntelWorkarounds`, `Workarounds`).
- `common/src/desktop` — LaunchWarn (running jar directly).
- `fabric/` — 17 java files; entrypoints `SodiumFabricMod` (client), `SodiumPreLaunch` (preLaunch) (`fabric/src/main/resources/fabric.mod.json`), Fabric-specific mixins (`sodium-fabric.mixins.json`: `core.model.quad.BakedQuadMixin`, `fabric.LevelSliceMixin`, `features.render.model.ItemModelGeneratorMixin`, `features.world.biome.BiomeMixin`), config entrypoint collector (`fabric/.../config/ConfigLoaderFabric.java:15-23`), mod-defined mixin overrides (`fabric/.../FabricMixinOverrides.java:14,24` reads custom key `"sodium:options"`).
- `neoforge/` — 23 java files; NeoForge platform glue.
- `frapi/` — 20 java files; Fabric Renderer API implementation (§13).
- `fabric.mod.json`: depends `minecraft 26.3.x`, `fabricloader >=0.16.0`, `fabric-block-getter-api-v2`, `fabric-rendering-fluids-v1 >=2.0.0`, `fabric-resource-loader-v0`; `provides: ["indium"]`; custom `"fabric-renderer-api-v1:contains_renderer": true`; `breaks` includes `vulkanmod: *`, `canvas: *`, `embeddium: *`, `iris <=1.11.2`, `sodium-extra <0.8.0`, `reeses-sodium-options <=2.2.0`, `fabric-api <0.145.1`. **[V]** No `breaks` on Metallum/MetalRender.

---

## 2. Every direct GL / backend-specific call site (complete list) [V]

grep over the whole repo for `org.lwjgl.opengl`, `GL\d+C?.`, `ARB*.`, `GlStateManager`, `renderpearl.backend`, `renderpearl.frontend`, `org.lwjgl.vulkan`:

| File | What | Gate / reachability under Metal |
|---|---|---|
| `BOOT/compatibility/environment/GlContextInfo.java:3-13` | `GL11C.glGetString(VENDOR/RENDERER/VERSION)` | Only called from `GlSurfaceMixin#doChecksOnce` inside `if backendName().contains("OpenGL")` (`C/mixin/workarounds/context_creation/GlSurfaceMixin.java:50` at tag). **Unreachable on Metal.** |
| `BOOT/compatibility/workarounds/nvidia/NvidiaWorkarounds.java:14-16,133` | `GL32C.glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS)` in `applyContextChanges` | Via `PostLaunchChecks.onContextInitialized`, same OpenGL gate. Unreachable on Metal. `applyEnvironmentChanges` (env vars) is called from `workarounds.context_creation.WindowMixin` which targets `GlBackend#createWindow` → only runs if vanilla's GL backend creates the window. |
| `C/mixin/workarounds/context_creation/GlSurfaceMixin.java` | `@Mixin(GlSurface)`, inject `present` RETURN; `WGL.wglGetCurrentContext`; ModuleScanner | Target is vanilla `com.mojang.renderpearl.backend.opengl.GlSurface`; never instantiated on Metal → post-launch checks and ModuleScanner never run. Explicit `backendName().contains("OpenGL")` checks at :50 and :67. |
| `C/mixin/core/WindowMixin.java:27-36` | `@Mixin(GlBackend)`, `SDL_GL_SetAttribute(SDL_GL_CONTEXT_NO_ERROR)` before `SDL_CreateWindow` | GL backend only. Note vanilla 26.3 uses **SDL3** (`org.lwjgl.sdl.SDLVideo`) for windows [SI]. |
| `C/mixin/workarounds/context_creation/WindowMixin.java:57-80` | `@Mixin(GlBackend)` @Redirect `SDL_CreateWindow` to wrap NVIDIA/AMD env changes (`require = 0`) | GL backend only. |
| `C/mixin/features/render/viewport/GlStateManagerMixin.java:190-214` | `@Mixin(renderpearl.backend.opengl.GlStateManager)` `@WrapWithCondition` on `GL33C.glViewport` in `_viewport` to skip redundant viewport calls | GL backend only. |
| `CC/gui/SodiumConfigBuilder.java:690-703` | `GL.getCapabilities()` (OpenGL46 / GL_KHR_no_error) for "use no-error context" option enabled-provider | Guarded by `if (!backendName().contains("OpenGL")) return false;` (:694). Option becomes disabled under Metal. |
| `C/mixin/core/GpuDeviceAccessor.java:9-13` | `@Mixin(FrontendGpuDevice) @Accessor("backend") GpuDeviceBackend` | Used by `DrawBackend` to test `instanceof VulkanDevice`. Backend-agnostic accessor. **Requires `RenderSystem.getDevice()` to be a `FrontendGpuDevice`** — a ClassCastException would occur if a mod replaced the frontend device itself [SI]. Metallum plugs in at backend level (`GpuBackend`), so frontend remains vanilla [SI from `PreferredGraphicsApiMixin` returning `GpuBackend[]`]. |
| `CC/gpu/device/backend/DrawBackend.java:5` | `import renderpearl.backend.vulkan.VulkanDevice` | instanceof only. |
| `CC/gpu/arena/staging/MappedStagingBuffer.java:6-7` | `import backend.opengl.GlBuffer/GlDevice` | **Unused imports** (no references in file body). Harmless; class loads regardless since imports aren't linked [V]. |
| `CC/gpu/device/batch/VKIndirectDrawBatch.java:9`, `VKMultiDrawBatch.java:7`, `context/VKDrawContext.java:5` | `org.lwjgl.vulkan.VkDrawIndexedIndirectCommand/VkMultiDrawIndexedInfoEXT/VkCommandBuffer` | Used only for struct SIZEOF/offset constants; only constructed for VK_* backends. |
| origin/dev only: `C/mixin/core/GlFallbackAllocatorMixin.java` (commits 69a05a10, 3e53f5c5) | `@Mixin(targets="com.mojang.renderpearl.backend.opengl.GlTransientMemory$Fallback")`; `GL33C.glFinish()` in `close`; delays rotation runnables by 1 frame (IN_FLIGHT=2) | Workaround for vanilla 26.3 GL fallback allocator lacking sync. GL backend only. Not in 0.9.2. |

No other GL. No `glXXX` in render code, no `GlStateManager` use other than the mixin above, no ARB.

**Every Blaze3D/renderpearl API use** (import counts across repo [V]): `renderpearl.api.vertex.VertexFormat` 18, `renderpearl.api.buffers.GpuBuffer` 18, `GpuBufferSlice` 11, `api.commands.RenderPass` 9, `api.pipeline.RenderPipeline` 7, `api.textures.GpuSampler` 5, `FilterMode` 4, `GpuTextureView` 3, `IndexType` 3, `GpuFormat` 2, `VertexFormatElement` 2, `GpuDevice` 2, `UniformType/PrimitiveTopology/DepthStencilState/ColorTargetState/BlendFunction/BindGroupLayout/GpuFence` 1 each; `blaze3d.systems.RenderSystem` 14, `blaze3d.buffers.Std140Builder` 1. Vanilla client helpers used: `net.minecraft.client.renderer.MappableRingBuffer` (VK indirect), `DynamicGpuDataStorage(Mapped|NonMapped)` (uniforms), `oit.OitPipelineSet/OitStage/OitRenderPassProvider` (OIT), `chunk.ChunkSectionsToRender/ChunkSectionLayerGroup`.

Device API surface Sodium requires from a backend [V, collected from call sites]:
- `GpuDevice.createBuffer(label, usage, size)` with usages `USAGE_INDEX|VERTEX|COPY_DST|COPY_SRC` (arenas), `USAGE_INDEX|MAP_WRITE|COPY_DST` (shared quad index), `USAGE_COPY_SRC|MAP_WRITE|COPY_DST` (staging), `USAGE_UNIFORM_TEXEL_BUFFER|COPY_SRC|COPY_DST|MAP_WRITE` (section time info), `USAGE_MAP_WRITE|INDIRECT_PARAMETERS` (VK indirect only).
- `GpuBuffer.map(read=false, write=true)` → `MappedView.data()`; persistent mapping held across frames when `getDeviceInfo().features().persistentMapping()` (`MojangStagingBuffer.java:13-17`, `UniformBufferManager.java:55-63`).
- `CommandEncoder.copyToBuffer(srcSlice, dstSlice)`, `writeToBuffer(slice, ByteBuffer)`, `createFence()`; `GpuFence.awaitCompletion(timeoutMs)`, `close()`.
- `RenderPass.setPipeline(RenderSystem.getCompiledPipeline(p))`, `setIndexBuffer(buf, IndexType.INT)`, `setVertexBuffer(0, slice)`, `setUniform(name, GpuBufferSlice|GpuBuffer|GpuTextureView+GpuSampler)`, `pushConstants(ByteBuffer 20 bytes)`, `multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, count)` (GL-style), `multiDrawIndexed(IntBuffer, instanceCount, firstInstance, count)` (VK multi-draw EXT style), `drawIndexedIndirect(slice, count)`.
- `RenderPipeline.builder()` with `withBindGroupLayout`, `withPushConstantSize(20)`, `withPrimitiveTopology(QUADS)`, `withVertexBinding(0, fmt)`, `withShaderDefine`, `withColorTargetState`, `withDepthStencilState`, `withCull`.
- `DeviceInfo.backendName()`, `features().persistentMapping()/multiDrawDirectInterleaved()/multiDrawIndirect()`.

---

## 3. Backend handling / Vulkan / "PreferredGraphicsApi" [V]

- `DrawBackend.BACKEND` is a static final computed on first class init via `RenderSystem.getDevice()` (`DrawBackend.java:13-29`). Name strings: `"gl_multidraw (sodium)"`, `"ext_multidraw (sodium)"`, `"indirect (sodium)"`.
- `DrawContext.create()` (`CC/gpu/device/context/DrawContext.java:10-20`) → `GLDrawContext` (no-op), `VKMultiDrawContext` (no-op), `VKIndirectContext` (vanilla `MappableRingBuffer` of 512 000 bytes, doubles on overflow, `rotate()` per frame: `VKIndirectContext.java:5-67`).
- `MultiDrawBatch.newBatch(capacity)` per backend (`MultiDrawBatch.java:9-15`). Batches are cached per (region, pass) (`CC/render/chunk/region/RenderRegion.java:161-170`, capacity `ModelQuadFacing.COUNT*REGION_SIZE+1` = 7*256+1 = 1793).
- GL-style batch: three native arrays (element byte offsets as pointers, counts, base vertices) → `pass.multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, size)` (`GLDrawBatch.java:23-38`). Element offset stored as `elementOffset << 2` (bytes, INT indices).
- VK multidraw: packed `VkMultiDrawIndexedInfoEXT` {firstIndex, indexCount, vertexOffset} → `multiDrawIndexed(IntBuffer, 1, 0, size)` (`VKMultiDrawBatch.java:17-29`).
- VK indirect: `VkDrawIndexedIndirectCommand` array memcpy'd into ring buffer in `prepare()`, `drawIndexedIndirect(slice, size)` in `draw()` (`VKIndirectDrawBatch.java:24-52`).
- `LevelRendererMixin#init` forces vanilla `multiDrawIndirectAvailable = false` (`C/mixin/core/render/world/LevelRendererMixin.java:79,115`).
- **No code refuses non-GL backends.** Sodium's settings expose vanilla's API preference (`SodiumConfigBuilder.java:314-331`); the "Prefer Vulkan" string is a literal (not translated).
- Under Metallum: `MetalDevice#buildDeviceInfo` returns backendName `"Metal"`, `DeviceFeatures(true × 8)` (`metallum/src/main/java/com/metallum/render/MetalDevice.java:240-258`) ⇒ Sodium picks `DrawBackend.OPENGL` (not VulkanDevice) and `MappedStagingBuffer` (persistentMapping true) and persistently-mapped section-time buffer. Metallum's `multiDrawIndexed(PointerBuffer…)` loops `drawIndexedPrimitives` per sub-draw with `indexBufferOffset = firstIndexOffset`, `baseVertex` (MetalRenderPass.java:226-250) [V]. Its `drawIndexedIndirect` also loops one Metal indirect draw per command (:252-268) [V].
- Shader `#ifdef VULKAN` selects push-constant block vs. loose uniforms (`assets/sodium/shaders/blocks/block_layer_opaque.vsh:18-28`). Metallum consumes **SPIR-V** modules from vanilla's frontend (`MetalCrossShaderCompiler.compile` reads `shader.module().spv()`: `metallum/src/main/java/com/metallum/render/MetalCrossShaderCompiler.java:53-78`) and maps push constants (:77, :269) → [SI] vanilla's frontend compiles GLSL→SPIR-V with `VULKAN` defined for non-GL backends; [U] whether `VULKAN` is defined for Metal compilation (not visible in either repo).

### origin/dev (mc26.3-0.9.2..origin/dev, 17 commits) [V]
Backend-relevant: only `GlFallbackAllocatorMixin` (GL-only sync fix), `GlSurfaceMixin` refactor (window handle now `long`, checks still gated on "OpenGL"), `SodiumConfigBuilder` refactor sourcing vanilla option ranges (#3947) and macOS fullscreen-menu option (d51de379), SDL message box instead of TinyFD (#3923). Nothing adds or removes backend support; nothing Metal-specific. FRAPI fixes (7dc27ec6, new `ItemSheetedDecalTextureGenerator`), quad API churn (`ModelQuadView`, `QuadViewImpl`, `EncodingFormat`) — affects Iris/FRAPI integration points, not GPU.

---

## 4. Renderer init / lifecycle [V]

- Hook: `LevelRendererMixin` (`C/mixin/core/render/world/LevelRendererMixin.java`):
  - `<init>` RETURN → `new SodiumWorldRenderer(mc)` (:102-116).
  - `invalidateCompiledGeometry` HEAD cancel → `renderer.reload()`, install `IgnoringSectionRenderDispatcher`/`IgnoringViewArea` (vanilla compile disabled) (:162-183); also redirect `getEffectiveRenderDistance` → 0 to stop vanilla allocating (:91-100).
  - `@WrapOperation render → prepareChunkRenders(...)`: builds `ChunkRenderMatrices`, `renderer.prepareChunkRendering(...)` (fills draw batches) and returns a **`SodiumChunkSection extends ChunkSectionsToRender`** (:137-151). Vanilla then opens the render passes and calls `renderGroup(group, renderPass, sampler, atlas, wireframe)` or `renderOit(sampler, stage, params, atlas, lightmap)` (`CC/util/SodiumChunkSection.java:38-48`; OIT path opens its own pass via vanilla `OitRenderPassProvider.createRenderPass`). → **Sodium does not own render targets or pass creation; vanilla does.**
  - `@Overwrite hasRenderedAllSections`, `isSectionCompiledAndVisible`; `endFrame` → `renderer.endFrame()` (uniform storage endFrame).
  - Redirect of `FilterMode.LINEAR` in `lambda$addMainPass$0` → user option `pixelFilteringMode` (:189-197).
- `SodiumWorldRenderer` (`CC/render/SodiumWorldRenderer.java`, 539 lines): `setupTerrain(camera, viewport, fog, occlusion, immediate, cullMatrix)` (:184-280): chunk events → camera change detection → `RenderSectionManager.prepareFrame`, GFNI movement triggering, loop {`prepareRender`, `prepareRenderTrees`, `cleanupAndFlip`, `updateChunks`, `processChunkBuilds`} up to renderDistance iterations if `updateChunksImmediately`, `finalizeRenderLists`, `tickVisibleRenders`. `drawChunkLayer` (:291-298): OPAQUE group → SOLID + CUTOUT passes; TRANSLUCENT group → TRANSLUCENT pass. `renderLayer` (:300-306) updates UBO then `ChunkRenderer.render(...)`. `initRenderer` (:322-336) builds `RenderSectionManager` + `UniformBufferManager`; sort behavior `DYNAMIC_DEFER_NEARBY_ZERO_FRAMES` unless dev env with sorting disabled.
- `RenderSectionManager` (1221 lines) ctor (:128-151): `DefaultChunkRenderer(ChunkMeshFormats.getCurrent())`, `ChunkBuilder`, `SortTriggering` (if sorting), `RenderRegionManager`, `ClonedChunkSectionCache`, `OcclusionCuller`, `RemovableMultiForest`.

## 5. Chunk compile / scheduling / upload [V]

- `ChunkBuilder` (`CC/render/chunk/compile/executor/ChunkBuilder.java`): N dedicated platform threads `"Chunk Render Task Executor #i"`, priority NORM-2 (:30-46); count = option `chunkBuilderThreads` or `getOptimalThreadCount()` (:116-123); `ChunkJobQueue`, job stealing (`tryStealTask`), per-thread `ChunkBuildContext`. Tracy zones (`com.mojang.jtracy`).
- Tasks: `ChunkBuilderMeshingTask` (rebuild, optionally with sort), `ChunkBuilderSortingTask` (translucent re-sort only). Outputs `ChunkBuildOutput`/`ChunkSortOutput` (CPU `NativeBuffer`s).
- Scheduling `updateChunks` (`RenderSectionManager.java:714-757`): three collectors (this-frame blocking, next-frame blocking, deferred) with `JobDurationEstimator`, `MeshTaskSizeEstimator`, `UploadDurationEstimator`, `LimitedResourceBudget(time, stagingBuffer.getUploadSizeLimit())`. DeferMode option `chunkBuildDeferMode` (default ALWAYS).
- Upload: `RenderRegionManager.uploadResults` (`CC/render/chunk/region/RenderRegionManager.java:64-…`) groups per region, removes old data, creates `PendingUpload`s → `ArenaAggregator`/`SharedBufferArena` allocate and enqueue copies through `StagingBuffer`. Staging is `MojangStagingBuffer(32_000_000)` (:281-283): if `persistentMapping` → `MappedStagingBuffer` (ring of 32 MB persistently mapped, copy-commands consolidated, `copyToBuffer` per merged run, one `GpuFence` per flush, `flip()` polls fences with `awaitCompletion(0)`; oversize uploads fall back to `writeToBuffer`) (`CC/gpu/arena/staging/MappedStagingBuffer.java:33-145`); else every upload is `writeToBuffer` and upload limit is 25.6 MB (`MojangStagingBuffer.java:13-40`).
- Note: each copy calls `RenderSystem.getDevice().createCommandEncoder()` (MappedStagingBuffer.java:47,86,90) — cost depends on backend [V call; cost H].

## 6. Visibility / occlusion [V]

- `OcclusionCuller` (`CC/render/chunk/occlusion/OcclusionCuller.java`): BFS over sections with `DoubleBufferedQueue`, 64-bit per-section visibility (`VisibilityEncoding`, direction pair bits), three simultaneous cull widths (LOCAL/REGULAR/WIDE: `getAngleVisibilityMaskLocal/Wide`, `getDirectionSetsLocal/Wide`), frustum test `isWithinFrustum`, distance test with fog (`useFogOcclusion`), out-of-world init (`initOutsideWorldHeight`).
- Runs **asynchronously** on `"Sodium Async Cull Thread"` (`RenderSectionManager.java:252-271` `CullTask`), producing `SectionTree`s per `CullType`; main thread picks narrowest valid tree (`findBestTree` :274-288) and traverses with `VisibleChunkCollector` into `SortedRenderLists` (:290-316); fallback `FallbackVisibleChunkCollector` when out of graph. `RayOcclusionSectionTree`, `tree/*Forest` structures for render-list construction. `QueuedSectionStorage` safe-read phases for concurrent access.
- Frustum: `SimpleFrustum` reflects JOML `FrustumIntersection.planes` (`CC/render/viewport/frustum/SimpleFrustum.java:24`); `Viewport` margins (CHUNK_SECTION_MARGIN 1.125 etc.).
- Block-face culling per section on CPU while building batches (`DefaultChunkRenderer.getVisibleFaces` :342-382, branchless).
- Entirely CPU, API-independent. Sodium Extra mixes into `OcclusionCuller`, `TraversableTree`, `RenderSectionManager`.

## 7. Vertex format [V]

`CompactChunkVertex` (`CC/render/chunk/vertex/format/impl/CompactChunkVertex.java:11-80`): stride **20 B**; attributes `a_Position RG32_UINT` (20-bit x/y/z split hi10/lo10 across two u32), `a_Color RGBA8_UNORM` (color×AO), `a_TexCoord RG16_UINT` (15-bit UV + 1 bias bit per axis, centroid-relative bias), `a_LightAndData RGBA8_UINT` (block light, sky light, material bits, section index in region = draw id). Position range 32 blocks with origin −8 (`VERTEX_SCALE=32/2^20`). Only format (`ChunkMeshFormats.getCurrent()` → COMPACT) and shader `#error`s without `USE_VERTEX_COMPRESSION` (`include/chunk_vertex.glsl:64-66`). Iris mixes into `ChunkMeshFormats` to substitute its own extended format.

## 8. Mesh storage, regions, arenas [V]

- Regions: 8×4×8 sections (`RenderRegion.java:33-45`), `REGION_SIZE` 256. Per-region: `SectionRenderDataStorage` per pass (native per-section data: base vertex, base element, per-facing vertex counts, facing list, slice mask, local-index flag — `SectionRenderDataUnsafe`), `DeviceResources` with geometry + index `RegionAllocatorHandle`s (:391-436), cached `MultiDrawBatch` per pass.
- **New allocation system (0.9.x):** `ArenaAggregator` (`CC/gpu/arena/ArenaAggregator.java`, 581 lines) — multiple regions share large `SharedBufferArena`s per data type (index stride 4, geometry stride 20); initial arena sizes index 16/32/64 MiB, geometry 32/128/256 MiB growing factors, max dynamic 1.5 GiB; free-buffer reuse pool of 8; incremental defragmentation with per-frame budget (`DEFRAG_BYTES_PER_FRAME` = 32 MiB per GiB), arena emptying/compaction, allocation-rate-based dealloc pausing. `DefragmentingBufferArena`, `SingleOwnerBufferArena`, `BufferArena` (505 lines). Defrag moves use `copyToBuffer` GPU→GPU (`DefragmentingBufferArena.java:211,284`, `BufferArena.java:104`). Arena buffers are created `USAGE_INDEX|USAGE_VERTEX|COPY_DST|COPY_SRC` (`ArenaAggregator.java:395-396`) — one buffer used as both vertex and index storage.
- Draw: whole arena buffer bound as vertex buffer (`resources.getGeometryBuffer().slice()`), per-draw base vertex selects section (DefaultChunkRenderer.java:163).

## 9. Index buffers [V]

- `SharedQuadIndexBuffer` (`CC/render/chunk/SharedQuadIndexBuffer.java`): INT indices, pattern 0,1,2,2,3,0; grows to max(2×, n+16384) primitives; created MAP_WRITE and filled through a one-shot map (:41-54). Used for opaque/cutout and for translucent sections whose sort type permits shared indices.
- Translucent: per-section sorted index data in region index arena (local index) or region-shared index (`SharedIndexSorter`) (`RenderRegionManager.java:118-…`).
- Pipeline topology is `PrimitiveTopology.QUADS` with an explicit index buffer (`ShaderChunkRenderer.java:79,112`); Metallum maps `QUADS` → `MTLPrimitiveType.Triangle` (`metallum/src/main/java/com/metallum/render/MetalConversions.java:158`) [V].

## 10. Draw submission [V]

`DefaultChunkRenderer` (`CC/render/chunk/DefaultChunkRenderer.java`):
- `prepare()` (:52-102) runs before vanilla opens passes: per pass, per region render list, fills cached batch (`fillCommandBuffer` :194-254) using per-facing slices (merging adjacent facings into one draw when using shared indices `addSharedIndexedDrawCommands` :285-331; one draw per facing for local indices :260-280), ensures shared index capacity, `batch.prepare(drawContext)` (VK indirect only).
- `render()` (:109-187): `pass.setPipeline(RenderSystem.getCompiledPipeline(activeProgram))`; set shared index buffer; uniforms `u_Globals` (UBO slice), `u_SectionTimeInfo` (texel buffer), `u_LightTex` (lightmap + clamp LINEAR sampler), `u_BlockTex` (atlas + terrain sampler); per region: optional per-region index buffer, `setVertexBuffer(0, geometry.slice())`, **20-byte push constants** {region offset xyz (camera-relative float), u_CurrentTime ms since region creation, regionId} (:169-179), `batch.draw(drawContext)`.
- So: **one multi-draw per (region, pass)**; up to 1793 sub-draws each; command lists built on CPU, cached until invalidated (`clearCachedBatchFor`).

## 11. Uniforms / per-frame state [V]

`UniformBufferManager` (`CC/render/chunk/UniformBufferManager.java`): global UBO via vanilla `DynamicGpuDataStorageMapped` (256 B entries, `USAGE_COPY_DST|USAGE_UNIFORM`) written with `Std140Builder` (proj, modelview, fog color/env/render ranges, atlas texel size, `u_TexCoordShrink` from `GPULimits.getSubTexelPrecisionBits()`, fade period, RGSS flag) (:98-123, :188-218). `sectionTimeInfo` = R32_SINT texel buffer sized maxRegions×256×4 B, persistently mapped if supported and written directly from CPU (:49-63, :158-180), resize path copies via `copyToBuffer`.
- `GPULimits.getSubTexelPrecisionBits()` returns **4 on macOS (any backend), 8 elsewhere** (`CC/gpu/GPULimits.java:6-17`) — OS check, not API check; the comment says it's an OpenGL-on-macOS limitation. Under Metal this conservative value still applies [V]; [H] Apple GPUs under Metal likely provide 8 bits, so `u_TexCoordShrink` over-shrinks slightly (minor texel-edge inset).

## 12. Shaders / pipelines [V]

- One program: `sodium:blocks/block_layer_opaque` (`.vsh` `#version 330` + `GL_ARB_separate_shader_objects`; `.fsh` `#version 460 core`), includes `sodium:globals.glsl` (std140 UBO), `fog.glsl`, `chunk_vertex.glsl`, `chunk_material.glsl`, and vanilla `minecraft:oit.glsl`. Defines: `USE_VERTEX_COMPRESSION`, `USE_FOG`, `ALPHA_CUTOUT` 0.5 (cutout) / 0.01 (translucent); OIT variants via vanilla `OitPipelineSet.builder("sodium_terrain", builder)` with `OIT_ACCUMULATE`/`OIT_ALPHA_ONLY` (`ShaderChunkRenderer.java:47-136`). Fragment does RGSS or "nearest with derivatives" sampling (`textureGrad`, `dFdx`), discard for alpha cutout. Vertex reads `texelFetch(isamplerBuffer)` for fade-in.
- Bind groups: `BIND_GROUP` {u_BlockTex combined image sampler, u_Globals UBO, u_SectionTimeInfo TEXEL_BUFFER R32_SINT}, `LIGHT_GROUP` {u_LightTex} (:29-35). Translucent blend `BlendFunction.TRANSLUCENT` RGBA8.
- Pipelines cached in static maps keyed by `TerrainRenderPass` (:27-28); compiled lazily by vanilla `RenderSystem.getCompiledPipeline`. GLSL is backend-neutral source; vanilla's frontend handles compilation per backend [SI].
- Metal relevance: requires texel buffers (Metal texture buffers), push constants (setVertexBytes), `discard` + derivatives (fine), 4 vertex attributes with integer formats (fine). Discard in cutout pass disables early-Z/HSR on Apple TBDR for that pass [H, well-known Apple guidance].

## 13. Textures [V]
- Terrain atlas from vanilla (`renderPass.getAtlas()`), sampler from vanilla with filter override. Animated textures: only visible sprites tick (`features.textures.animations.tracking.*` mixins, option `animateOnlyVisibleTextures`). `features.textures.scan.TextureAtlasSpriteMixin` scans for translucency. **Doc discrepancy:** `thirdparty/NOTICE.txt` lists MPL-2 `common/.../mixin/features/textures/mipmaps` but that directory no longer exists at this tag (`ls C/mixin/features/textures/` = NativeImageAccessor, animations, scan) [V].

## 14. Translucency sorting [V]
`CC/render/chunk/translucent_sorting/` (5814 lines): `SortType` {EMPTY_SECTION, NO_TRANSLUCENT, NONE, STATIC_NORMAL_RELATIVE, STATIC_TOPO, DYNAMIC} (`SortType.java:9-56`); `SortBehavior` {OFF, STATIC, DYNAMIC_DEFER_ALWAYS, …NEARBY_ONE_FRAME, …NEARBY_ZERO_FRAMES (default), …ALL_*}; `QuadSplittingMode` {OFF, SAFE (default), UNLIMITED}; BSP tree builder (`bsp_tree/*`), topological sort (`TopoGraphSorting`), GFNI (Global Face Normal Indexing) triggers (`trigger/GFNITriggers`, `GeometryPlanes`, `NormalPlanes`) plus direct triggers; `TranslucentGeometryCollector` during meshing. All CPU; result is index data uploaded via the staging buffer. API-independent. With vanilla OIT (`improvedTransparency`), translucent is rendered through `renderOit` with OIT stages.

## 15. Entity / block entity / particles / other immediate paths [V]
- Entities: `features.render.entity.ModelPartMixin`, `CubeMixin` → `CC/render/immediate/model/EntityRenderer` writes `EntityVertex` directly via `VertexBufferWriter` (CPU). Entity culling via `features.render.entity.cull.EntityRendererMixin` + `SodiumWorldRenderer.isEntityVisible` using the section graph.
- Block entities: `extractBlockEntities` (`SodiumWorldRenderer.java:338-400`) iterates visible sections' BE lists; `BlockEntityRenderHandler`/`BlockEntityRenderPredicate` API.
- `BufferBuilder` intrinsics, `VertexConsumer` fast paths, `SheetedDecalTextureGenerator`, `SpriteCoordinateExpander`, glyph (`BakedGlyphMixin`), particles (`QuadParticleRenderStateMixin`), shadows, clouds meshing (`CloudRendererMixin` overwrite of `buildMesh`, pointer writes), `VertexSortingMixin`. All CPU-side; draw submission stays vanilla.
- `ParticleFeatureRendererMixin` re-binds `Globals`/`Lighting` uniforms on the particle `RenderPass` because Sodium bypasses vanilla's chunk path that left them bound (`C/mixin/core/render/world/ParticleFeatureRendererMixin.java:20-43`) — a renderpearl-level fix; backend-independent.
- `VertexFormatMixin` assigns global ids to every `renderpearl.api.vertex.VertexFormat` for the serializer registry.

## 16. FRAPI (frapi/) [V]
- `SodiumRenderer implements net.fabricmc.fabric.api.client.renderer.v1.Renderer` (`FR/client/render/frapi/SodiumRenderer.java:37-72`): `quadEmitter`, `mutableMesh`, `altModelBlockRenderer` (→ `NonTerrainBlockRenderContext`). Registered via `SodiumRegistrar.register → Renderer.register(SodiumRenderer.INSTANCE)` (`FR/.../SodiumRegistrar.java:6-8`) using a service provider (`SodiumRegistrarProvider implements FRAPIRegistrarProvider`).
- Terrain path: `BlockRenderer extends AbstractBlockRenderContext` (`CC/render/chunk/compile/pipeline/BlockRenderer.java:37`) consumes FRAPI quads (`MutableQuadViewImpl`, `EncodingFormat`) → `bufferQuad` → chunk vertex encoder. Non-terrain: `ExtendedBlockModelFeatureRenderer`, `ExtendedItemFeatureRenderer` via `FeatureRenderDispatcherMixin`.
- Indium is built in (`"provides": ["indium"]`, `contains_renderer: true`). All CPU-side; no GPU calls. Files carrying Apache-2.0 FabricMC headers: 10 of 20 frapi files plus 14 in common (list in §17).

## 17. Public API (`common/src/api`) [V]
Packages: `blockentity` (BlockEntityRenderHandler/Predicate), `config` (ConfigEntryPoint, ConfigEntryPointForge, ConfigState, StorageEventHandler, `option.*` flags/impact/range/validators, `structure.*` builders incl. `registerOptionReplacement`, `registerOptionOverlay`, `registerFlagHook`, `ExternalPageBuilder`), `math.MatrixHelper`, `memory.MemoryIntrinsics`, `texture.SpriteUtil`, `util.Color*`, `NormI8`, `vertex.*` (attributes, `VertexBufferWriter`, `VertexFormatRegistry`, `VertexSerializer(Registry)`, common formats Entity/Glyph/Particle). `internal.DependencyInjection`. **No GPU/backend abstraction is exposed** (backend selection is not an extension point).
- Config entrypoint: Fabric entrypoint key `"sodium:config_api_user"` (`CC/config/ConfigManager.java:24`), `ConfigEntryPoint#registerConfigEarly/Late(ConfigBuilder)` (`API/config/ConfigEntryPoint.java:10-29`). `OptionFlag` {REQUIRES_RENDERER_RELOAD, REQUIRES_RENDERER_UPDATE, REQUIRES_ASSET_RELOAD, REQUIRES_VIDEOMODE_RELOAD, REQUIRES_GAME_RESTART}.
- Mixin disabling by other mods: custom `"sodium:options": {"mixin.x.y": false}` in their fabric.mod.json (`FabricMixinOverrides.java:14-24`) + user file `config/sodium-mixins.properties` (`C/mixin/SodiumMixinPlugin.java:25-30`). Iris uses it to disable `mixin.features.render.world.sky` (`Iris/common/src/main/resources/fabric.mod.json:61-63`). SodiumMixinPlugin disables all mixins if `embeddium` is loading.

### How ecosystem mods plug in [V] (import counts from their source)
- **Sodium Extra**: config via API (`ConfigEntryPoint`, builders) **plus internal** `client.config.structure.{Config,Option,IntegerOption}`, `ConfigManager`, `ControlValueFormatterImpls`, and mixins on `RenderSectionManager`, `OcclusionCuller`, `TraversableTree`.
- **Reese's Sodium Options**: almost entirely internals: `client.config.structure.*` (Option×11, ModOptions×11, …), `client.gui.*` (VideoSettingsScreen, widgets, controls, prompts), mixins on `VideoSettingsScreen`, `Option`, `AbstractWidget`.
- **Iris**: mixins on `RenderSectionManager`×2, `BlockRenderer`×2, `FluidRendererImpl`×2, `ChunkVertexConsumer`, `RenderSection`, `UniformBufferManager`, `SodiumWorldRenderer`, `SodiumConfigBuilder`, `ShaderChunkRenderer`, `RenderRegionManager`, `RenderRegion`, `DefaultChunkRenderer`, `ChunkVertexEncoder.Vertex`, `ChunkMeshFormats`, `ChunkBuilderMeshingTask`, `ArenaAggregator`, `AbstractBlockRenderContext`, `DefaultFluidRenderer`, `ExtendedBlockModelFeatureRenderer`, `IntegerOptionBuilderImpl`, `VideoSettingsScreen`; plus vanilla `GlBackend` (priority 1010), `RenderPipeline`, `LevelRenderer`; injected interfaces into `renderpearl.backend.api.RenderPassBackend`, `renderpearl.api.commands.RenderPass`, `GpuTexture` (`Iris/common/src/main/resources/fabric.mod.json:70-75`). → **Compat surface = Sodium's internal class/method shapes, not only its API.**
- **Metallum**: config page via `ConfigEntryPoint` (`metallum/src/main/java/com/metallum/compat/sodium/MetallumSodiumConfig.java:34-48`) and the cosmetic mixin above.

## 18. Mixin list [V]
- `sodium-common.mixins.json` (client, 76 entries, plugin `SodiumMixinPlugin`, defaultRequire 1, MixinExtras ≥0.5.2): core (GpuDeviceAccessor, MinecraftMixin, WindowMixin[GlBackend], BlockColors, BlockEntityType, TextureAtlas, VertexFormat, frustum Camera/Frustum/GameRendererAccessor, BufferBuilder/SheetedDecal/SpriteCoordinateExpander consumers, FogRenderer, GameRenderer, LevelExtractor, LevelRenderer, ParticleFeatureRenderer, RenderBuffers, ViewArea, ClientLevel biome, PalettedContainer/SimpleBitStorage/ZeroBitStorage, ClientChunkCache/ClientLevel/ClientPacketListener map), features (gui hooks/debug/settings, entity Cube/ModelPart/cull/shadows, font BakedGlyph, immediate Direction/BufferBuilder intrinsics/sorting/VertexConsumer, particle, viewport GlStateManagerMixin, clouds, sky, textures animations/scan), workarounds (GlSurfaceMixin, WindowMixin[GlBackend], `event_loop.RenderSystemMixin` (empty body), window_minimized_state GameRendererMixin).
- `sodium-fabric.mixins.json`: 4; `sodium-frapi.mixins.json`: 4 (FeatureRenderDispatcherMixin, ItemFeatureRendererAccessor, MutableQuadViewImplMixin, QuadViewImplMixin); `sodium-neoforge.mixins.json` (not enumerated).
- GL-targeting mixins (`GlBackend`×2, `GlStateManager`, `GlSurface`) target vanilla classes that still exist in the jar on macOS, so they apply cleanly (defaultRequire 1 satisfied) but their code never executes under a Metal backend [SI: classes exist since Metallum's own build compiles against the same 26.3 jar; behaviour V from targets].

## 19. Config/options system [V]
- Storage `sodium-options.json` (`CC/gui/SodiumOptions.java:18-70`): quality {hiddenFluidCulling, improvedFluidShaping, useClosestPointEntitySort, pixelFilteringMode}, performance {chunkBuilderThreads, chunkBuildDeferMode, animateOnlyVisibleTextures, useEntityCulling, useFogOcclusion, useBlockFaceCulling, useNoErrorGLContext, quadSplittingMode}, advanced {enableMemoryTracing}, debug {terrainSortingEnabled}, notifications.
- Builder impls `CC/config/builder/*`, structure `CC/config/structure/*`, search index `CC/config/search/*`; screen `VideoSettingsScreen`. `SodiumConfigBuilder` builds Sodium's own pages including vanilla options (graphics API, etc.).

---

## 20. The 8 questions

**Q1. Which parts are preservable (for a Metal-based Metallium)?**
Essentially all of it, **if Metal is introduced as a renderpearl backend** (Metallum model): section graph/occlusion (§6), async cull thread, chunk builder & scheduling/estimators (§5), meshing incl. FRAPI/Indium (§16), compact vertex format (§7), arena allocator/defrag (§8), translucency sorting/GFNI/BSP (§14), command list building (§10), config system and API (§17-19), entity/immediate CPU optimizations (§15). [V by absence of GL in these paths.]

**Q2. Which parts assume OpenGL?**
Only: GL context diagnostics/driver checks/ModuleScanner (GlSurface-gated), NVIDIA/AMD env workarounds and no-error-context (GlBackend-gated), viewport dedupe (GlStateManager), the `useNoErrorGLContext` option, and dev's `GlFallbackAllocatorMixin`. Semantic GL-isms that remain: the "OPENGL" `DrawBackend` naming and its `multiDrawIndexed(PointerBuffer offsets…)` signature mirroring `glMultiDrawElementsBaseVertex`; GLSL shaders (`#version 330`/`460 core`, `isamplerBuffer`, `#ifdef VULKAN` push-constant switch); `GPULimits` macOS 4-bit subtexel assumption (OS-based). [V]

**Q3. Which parts are API-independent?** Everything in Q1 plus the draw-batch abstraction (`MultiDrawBatch`/`DrawContext`) and staging abstraction (`StagingBuffer`) which are already parameterized over backend capabilities. [V]

**Q4. Extension points for a Metal backend.**
- Primary (already used by Metallum): vanilla renderpearl `GpuBackend` → Sodium automatically uses it via `RenderSystem.getDevice()`. [V]
- Sodium-internal switch points (not public API, mixin-only): `DrawBackend.chooseBackend` (static enum; adding `METAL_*` requires mixin/fork — enum constants can't be added by mixin cleanly; could instead map Metal to `VK_INDIRECT` semantics by making `DrawBackend.BACKEND` return VK_INDIRECT, but the `instanceof VulkanDevice` check and `ClassCastException` in `VKIndirectDrawBatch.draw` casting to `VKIndirectContext` are fine since context is created by the same switch) [V code; H feasibility]; `MultiDrawBatch.newBatch`, `DrawContext.create`, `MojangStagingBuffer`/`RenderRegionManager.createStagingBuffer`, `ShaderChunkRenderer.createShader` (pipeline/defines), `ChunkMeshFormats.getCurrent()` (Iris already hooks it), `DeviceFeatures` flags exposed by the backend steer staging/persistent mapping/multidraw choice.
- No public "renderer backend" API in `sodium.api`. [V]

**Q5. What needs a fork?** Nothing, for basic Metal support via a renderpearl backend (proven by Metallum's design). A fork/heavy mixins would be needed only for Metal-specific optimizations beyond renderpearl's API: GPU-driven culling/indirect command buffers (ICB), argument buffers / bindless, mesh shaders, heap-based sub-allocation, removing staging copies on UMA, per-region push-constant elimination. Note the license makes a fork legally problematic (§21).

**Q6. How much could stay shared core?** ~100% of Sodium's Java code is already backend-neutral; the backend-specific code is ~400 lines in `CC/gpu/device/**` (397 lines incl. VK) plus ~5 GL diagnostic files. [V line counts: `wc -l device/*/*.java GPULimits.java` = 397]

**Q7. Does retaining the architecture restrict Apple Silicon performance?** Partially [H, reasoned from code]:
- Draw path under Metal = "OPENGL" batch → Metallum loops one `drawIndexedPrimitives` per sub-draw (MetalRenderPass.java:226-250). Up to 1793 draws/region/pass, CPU encoding cost; no Metal equivalent of `glMultiDrawElementsBaseVertex`. An ICB/indirect-based Metal path would cut CPU cost; Sodium's VK_INDIRECT batch layout (`VkDrawIndexedIndirectCommand`) already matches `MTLDrawIndexedPrimitivesIndirectArguments` layout field-for-field (indexCount, instanceCount, indexStart, baseVertex, baseInstance) [SI], so selecting VK_INDIRECT semantics for Metal would let a backend consume the commands as a GPU buffer (still one indirect call per command unless ICB is used).
- Per-region push constants + per-region `setVertexBuffer`/`setIndexBuffer` state churn: modest.
- Staging: `MappedStagingBuffer` does CPU memcpy into a mapped ring then GPU `copyToBuffer` into arenas; on unified memory this extra copy is avoidable but needs backend/arena changes (arena buffers are not MAP_WRITE).
- Defrag GPU copies and frequent `createCommandEncoder()` calls depend on backend efficiency.
- CPU culling/meshing are architecture-neutral and benefit from Apple P-cores; chunk builder thread priority NORM-2 maps to QoS on macOS [U].
- Alpha-tested cutout pass with `discard` hurts TBDR HSR [H]; vanilla design, not Sodium-specific.
- 20 B vertices are bandwidth-friendly for TBDR. [V size]
- Not restricted: no GL driver overhead, no GL state machine — Sodium speaks renderpearl only.

**Q8. Can compatibility surfaces be preserved without GL limitations?** Yes, if Sodium itself is kept (unmodified) and Metal is a renderpearl backend: Iris/Sodium Extra/RSO/FRAPI mods target Sodium's internal classes and vanilla renderpearl interfaces, none of which are GL-bound. Iris additionally mixes into vanilla `GlBackend` and injects into `RenderPassBackend` — that part is Iris's GL coupling (see Iris audit), not Sodium's. A re-implementation (not using Sodium) would have to replicate Sodium's internal class shapes to keep Iris/Sodium Extra/RSO working — high cost and license-sensitive.

---

## 21. License (decision-critical) [V text]

Root `LICENSE.md` = **PolyForm Shield License 1.0.0**. Key quotes:

> **Noncompete** — "Any purpose is a permitted purpose, except for providing any product that competes with the software or any product the licensor or any of its affiliates provides using the software."

> **Competition** — "Goods and services compete even when they provide functionality through different kinds of interfaces or for different technical platforms. Applications can compete with services, libraries with plugins, frameworks with development tools, and so on, even if they're written in different programming languages or for different computer architectures. Goods and services compete even when provided free of charge. If you market a product as a practical substitute for the software or another product, it definitely competes."

> **Notices** — "You must ensure that anyone who gets a copy of any part of the software from you also gets a copy of these terms or the URL for them above, as well as copies of any plain-text lines beginning with `Required Notice:` that the licensor provided with the software."

> **Changes and New Works License** — "…make changes and new works based on the software for any permitted purpose."

> **New Products** — later-version cutoff if licensor brings product into competition. **Discontinued Products** — may compete with a discontinued product unless a `Licensor Line of Business:` line is included.

> **Violations** — 32-day cure period after written notice.

- `Required Notice:` lines: **none** in repo (only the example inside LICENSE.md:38). `Licensor Line of Business:`: **none** (only example at :85-88). [V grep]
- `fabric.mod.json` / `neoforge.mods.toml:3` license field `Polyform-Shield-1.0.0`. Build copies `LICENSE.md` into jars (`buildSrc/src/main/kotlin/multiloader-platform.gradle.kts:25`, `neoforge/build.gradle.kts:91,103,113`).
- **API module**: no per-file headers, no separate LICENSE → PolyForm Shield. [V] (contrast: historically some ecosystems had LGPL APIs; not here.)
- **frapi module**: no separate LICENSE file; 10/20 files carry Apache-2.0 "Copyright (c) 2016-2019 FabricMC" headers (SodiumRenderer, MeshImpl, MeshViewImpl, MutableMeshImpl, ExtendedBlockModelFeatureRenderer, ExtendedItemFeatureRenderer, NonTerrainBlockRenderContext, QuadToPosPipe, FeatureRenderDispatcherMixin, ItemFeatureRendererAccessor); the other 10 (SodiumRegistrar(Provider), FRAPIEmitter(Provider), wrapper/*, MutableQuadViewImplMixin, QuadViewImplMixin) have no header → PolyForm Shield by default [V]. Common Apache-headed files: `CC/render/helper/{Color,Geometry,Model,Normal,Texture}Helper`, `CC/render/model/{AbstractRenderContext,EncodingFormat,ItemGlintRenderType,ItemGlintSpecialRenderType,ItemRenderType,MutableQuadViewImpl,QuadViewImpl}`, `CC/render/texture/SodiumSpriteFinder(Impl)`. MIT: `CC/util/interval_tree/*` (lodborg). `thirdparty/NOTICE.txt` lists Fabric API (Apache-2.0), interval tree (MIT), coderbot mipmap (MPL-2, files now absent).
- Implications [H — not legal advice]:
  - **Forking Sodium into a Metal renderer marketed as a faster/Metal alternative to Sodium is very likely "a product that competes"** (explicit "different technical platforms" and "practical substitute" language) → not a permitted purpose → no license to modify/distribute.
  - Copying Sodium-licensed code (incl. API sources, arena, culling, sorting) into Metallium carries the same restriction. Apache-2.0 files (Indigo-derived) can be taken from FabricMC's upstream under Apache instead.
  - A **separate mod that runs alongside an unmodified, user-installed Sodium** (renderpearl Metal backend + Sodium config API entrypoint, like Metallum) does not distribute or modify Sodium; whether *using* Sodium's API to build a complementary backend "competes" is arguable but much weaker — a backend that makes Sodium run on Metal complements Sodium. Mixins into Sodium classes are runtime modification, not distribution of the software; still, legal review recommended.
  - Competing with Sodium "using the software" also matters for the "New Products" clause if CaffeineMC later ships a Metal path.

---

## Open questions / not verified
- [U] Exact vanilla 26.3 `renderpearl` frontend behaviour: whether `VULKAN` is defined when compiling Sodium's GLSL for non-GL backends (Metal), and whether push constants are emulated as uniforms on GL; Mojang source not available.
- [U] Whether `RenderSystem.getDevice()` is always `FrontendGpuDevice` (Sodium's accessor cast assumes it; CCE if a mod wraps the device differently).
- [U] neoforge module mixin list not enumerated; NeoForge irrelevant for Fabric target.
- [H] Performance claims in Q7 (per-draw CPU cost in Metal loop, staging copy overhead on UMA, subtexel precision on Apple GPUs) not measured.
- [H] Feasibility of steering `DrawBackend` to VK_INDIRECT for Metal via mixin (static final enum field initialised at class load; would need @Redirect/@ModifyReturnValue on `chooseBackend` before first access).
- [U] Legal interpretation of PolyForm Shield for a complementary backend mod — requires counsel; CaffeineMC's own public statements on permitted forks not checked (docs only).
- Not reviewed line-by-line: `BufferArena`/`SharedBufferArena`/`DefragmentingBufferArena` internals, `bsp_tree` algorithms, `ChunkJobQueue`, neoforge glue, `VideoSettingsScreen` internals.
