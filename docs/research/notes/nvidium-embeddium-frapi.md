# Audit: Nvidium, Embeddium, Indium, Fabric Rendering API (for Metallium)

Refs audited (read-only):
- nvidium @ origin/26.3 27ceb72 (2026-09-23, "fixing translucent pass disapearing"); depends `minecraft 26.3`, `sodium 0.9.2` exactly (`nvidium/src/main/resources/fabric.mod.json:30-34`).
- embeddium @ origin/21.1/neoforge fc5c69b0 (2025-02-07), MC 1.21.1, mod_version 1.0.15.
- Indium @ 1.21.x/main c4203e6 (2024-11-13, "Add deprecation notice").
- sodium @ mc26.3-0.9.2 (frapi module).
- fabric (Fabric API) @ 26.3 33b36907 (2026-10-14), version 0.163.0.

Labels: **[V]** Verified in code, **[SI]** Strongly inferred, **[H]** Hypothesis, **[U]** Unverified.

Side note on MC 26.3 naming (inferred from mod imports only, Minecraft source unavailable): the Blaze3D GPU abstraction lives in `com.mojang.renderpearl.*` (`api.device`, `api.commands.RenderPass`, `api.textures.GpuSampler`, `frontend.FrontendRenderPass`, `backend.opengl.GlDevice/GlStateManager/GlCommandEncoder`, `backend.vulkan.VulkanGpuSurface`), while `com.mojang.blaze3d.systems.RenderSystem` still exists. `LevelRenderer` is split with a `LevelExtractor` (`Minecraft.levelExtractor.allChanged()`), rendering goes through `SubmitNodeCollector`/`FeatureRenderDispatcher`. **[SI]** (e.g. `nvidium/.../mixin/minecraft/MixinGlDevice.java:3-5`, `fabric-client-gametest-api-v1/.../VulkanGpuSurfaceMixin.java:32`, `Iris/.../pipeline/PipelineManager.java:37-38`).

---------------------------------------------------------------------------------------------------

## A) Nvidium (26.3 branch)

License: LGPL-3.0 **[V]** (`nvidium/LICENSE.txt:1-2`, `fabric.mod.json:18`). Author Cortex, contributor drouarb.

### A.1 Renderer selection / capability checks

- Capability check hook: `MixinGlDevice` injects into `com.mojang.renderpearl.backend.opengl.GlDevice.<init>` right after `GL.createCapabilities()` and calls `Nvidium.checkSystemIsCapable()` (`nvidium/src/main/java/me/cortex/nvidium/mixin/minecraft/MixinGlDevice.java:12-17`). **[V]**
  - Consequence: it only runs when MC uses the **OpenGL backend**. If 26.3 selects the Vulkan backend (backend exists: `backend.vulkan` referenced by Sodium/Fabric gametest), `GlDevice` is never constructed, `IS_COMPATIBLE` stays `false` (default `Nvidium.java:14`) and Nvidium is inert. **[SI]**
- Required GL extensions (`Nvidium.java:29-37`) **[V]**:
  `GL_NV_mesh_shader && GL_NV_uniform_buffer_unified_memory && GL_NV_vertex_buffer_unified_memory && GL_NV_representative_fragment_test && GL_ARB_sparse_buffer && GL_NV_bindless_multi_draw_indirect`.
  Not checked but required by shaders: `GL_NV_gpu_shader5`, `GL_NV_shader_buffer_load`, `GL_NV_bindless_texture` (`#extension ... : require` in all shaders, e.g. `shaders/occlusion/region_raster/mesh.glsl:5-8`), KHR subgroup ballot/vote/arithmetic/shuffle, optional `GL_NV_fragment_shader_barycentric` (`terrain/frag.frag:11-13`, config `use_nv_fragment_shader_barycentric` default true, `config/NvidiumConfig.java:26` — not capability checked → could fail compile on a GPU lacking it, practically all Turing+ have it). Also uses `GL_NVX_gpu_memory_info` unconditionally when `automatic_memory` (`NvidiumWorldRenderer.java:126-134` `update_allowed_memory`). **[V]**
- Linux: sparse buffer disabled (`SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER=false`, `Nvidium.java:43-46`) → fallback fixed-size device buffer. **[V]**
- Enable flag: `IS_ENABLED = !FORCE_DISABLE && IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable()` re-evaluated at every Sodium `RenderSectionManager` construction and when choosing the chunk vertex type (`mixin/sodium/MixinRenderSectionManager.java:30-53`). **[V]**
  - `FORCE_DISABLE` is a non-persisted Sodium-options toggle "Disable nvidium" with `REQUIRES_RENDERER_RELOAD` (`config/ConfigGuiBuilder.java:26-35`). There is **no persisted config enable flag** in `NvidiumConfig` (`config/NvidiumConfig.java:16-30`). **[V]**
- Fallback when unsupported: no separate code path — every Nvidium mixin into Sodium checks `Nvidium.IS_ENABLED` and otherwise falls through to Sodium's own renderer (e.g. `MixinSodiumWorldRenderer.java:52-79`, `MixinRenderRegionManager.java:23-32`, `MixinChunkBuilderMeshingTask.java:28-36`). **[V]**
  - Unconditional side effects even when disabled **[V]**: `MixinFogRenderer#clampSkyEnd` clamps sky fog end to [32, 512] blocks always (`mixin/minecraft/MixinFogRenderer.java:23-30`); `MixinChunkJobQueue` redirects `availableProcessors()` in `ChunkJobQueue.shutdown` to `Integer.MAX_VALUE>>1` (`MixinChunkJobQueue.java:8-13`); `MixinOptionFlag` appends a new enum constant `REQUIRES_SHADER_RELOAD` to Sodium's `OptionFlag` (`MixinOptionFlag.java:12-29`).

### A.2 Iris interaction (exact code)

`nvidium/src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java:6-15` **[V]**:
```java
public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
private static boolean checkIrisShaders() { return IrisApi.getInstance().isShaderPackInUse(); }
public static boolean checkIrisShouldDisable() { return !(IRIS_LOADED && checkIrisShaders()); }
```
Note the inverted naming: `checkIrisShouldDisable()` returns **true when Nvidium may run** (Iris absent, or no pack in use). Used in `MixinRenderSectionManager#updateNvidiumIsEnabled` (`MixinRenderSectionManager.java:30-33`):
```java
Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable();
```
- So **yes: with a shader pack active Nvidium disables itself and Sodium's normal renderer (which Iris patches) renders terrain**. **[V]**
- Re-evaluation happens when Sodium constructs a new `RenderSectionManager` (`SodiumWorldRenderer#initRenderer`, `sodium/common/.../SodiumWorldRenderer.java:320-331`). Iris triggers that via `Minecraft.getInstance().levelExtractor.allChanged()` when a pipeline needs reload (`Iris/common/.../pipeline/PipelineManager.java:36-41`, `IrisRenderingPipeline.java:882`). **[SI]** that toggling a pack always triggers a renderer reload (Iris reload path not fully traced).
- GUI shows a pseudo-option "Nvidium disabled due to shaders being loaded" if `IS_COMPATIBLE && !IS_ENABLED && !FORCE_DISABLE` (`ConfigGuiBuilder.java:37-46`). **[V]**
- Priority 1500 on `MixinRenderSectionManager` "so Iris doesn't hijack our ChunkVertexFormat" (`MixinRenderSectionManager.java:24`); `@ModifyArg` on `ChunkBuilder.<init>` vertex type swaps in `NvidiumCompactChunkVertex.INSTANCE` only when enabled and `!use_sodium_vertex_format` (`:46-53`). **[V]**
- Iris codebase has no reference to Nvidium (grep "nvidium" in Iris: no hits). **[V]**
- Implication: Nvidium is fundamentally incompatible with shader packs; the mesh-shader path never runs pack shaders. **[V]**

### A.3 What it replaces in Sodium (mixin targets)

`nvidium/src/main/resources/nvidium.mixins.json:5-21` **[V]**:

| Mixin | Target | Effect |
|---|---|---|
| `sodium.MixinSodiumWorldRenderer` | `SodiumWorldRenderer` | `setupTerrain` HEAD: capture `Viewport`; `renderLayer` HEAD cancellable: when enabled cancel Sodium draw; skip CUTOUT (merged into SOLID); invalidate B3D GL program cache via `GlCommandEncoder.lastProgram=null`; raw `GlStateManager` state (scissor off, cull on, depth GEQUAL reverse-Z, depth mask); SOLID → `renderFrame`, TRANSLUCENT → `renderTranslucent` (`MixinSodiumWorldRenderer.java:47-79`) |
| `sodium.MixinRenderSectionManager` | `RenderSectionManager` (prio 1500) | create/destroy `NvidiumWorldRenderer`; vertex type swap; `onSectionRemoved` redirect to also delete Nvidium section (subject to `region_keep_distance`); override F3 debug strings (`:35-86`) |
| `sodium.MixinRenderRegionManager` | `RenderRegionManager.uploadResults` | HEAD cancel; route every `BuilderTaskOutput` to `NvidiumWorldRenderer.uploadBuildResult` (`:23-32`) — Sodium's GPU arenas are bypassed entirely |
| `sodium.MixinChunkBuilderMeshingTask` | `ChunkBuilderMeshingTask.execute` | HEAD capture vertex stride; TAIL (worker thread) repackage `ChunkBuildOutput` into `RepackagedSectionOutput` (`:22-36`) |
| `sodium.MixinChunkBuildOutput` | `ChunkBuildOutput` | adds `RepackagedSectionOutput` field, frees it in `destroy` |
| `sodium.MixinRenderSection` | `RenderSection` | adds volatile `isEnqueued/isSeen` flags (no reader found besides interface) |
| `sodium.MixinChunkJobQueue` | `...executor.ChunkJobQueue.shutdown` | redirect availableProcessors |
| `sodium.MixinOptionFlag` | Sodium API `OptionFlag` enum | adds enum constant |
| `sodium.ViewportAccessor` | `Viewport` | accessors `frustum`, `transform` |
| `minecraft.MixinGlDevice` | renderpearl `GlDevice.<init>` | capability check |
| `minecraft.MixinCamera` | `Camera.update` `Math.max(FF)` | depth far = 16*512 when enabled (`MixinCamera.java:11-23`) |
| `minecraft.MixinFogRenderer` | `FogRenderer.setupFog` / `updateBuffer` | extend fog distance to `region_keep_distance*16`; clamp sky end |
| `minecraft.FrontendCommandEncoderAccessor/GlCommandEncoderAccessor/FrontendRenderPassAccessor/TextureAtlasAccessor` | renderpearl frontend/GL backend, atlas | access backend encoder, OIT pass uniforms, atlas size |

What Sodium still does while Nvidium is enabled **[SI]**: section graph traversal / occlusion culling for *build scheduling*, chunk meshing (worker threads, block renderer, FRAPI, translucency sort tasks producing `ChunkSortOutput`). Nvidium ignores Sodium's render lists for drawing; it draws everything it holds in frustum regions with its own GPU culling.

### A.4 Terrain representation / vertex format

- Region = 8x4x8 sections (256 slots), section index = region<<8 | local (`managers/RegionManager.java:19` comment "8x4x8", `SectionManager.removeRegionById` loops 8/4/8 at `SectionManager.java:318-331`). maxRegions 50,000, maxSections = 200*maxRegions (`SectionManager.java:51,59`). **[V]**
- Region metadata 16 B: packed size(3+2+3 bits), last section idx (8 bits), x/y/z 24-bit positions, transformation id (10 bits) (`RegionManager.java` `setRegionMetadata` ~L99-127; GLSL decode `shaders/occlusion/scene.glsl:31-54`). **[V]**
- Section metadata 48 B (`SectionManager.SECTION_SIZE = 32+16`, `SectionManager.java:30`): `ivec4 header` (x/y/z: 4-bit min offset, 4-bit size, chunk coord; y also has hide bit 17 and translucent sort ref id bits 18-25; w = quad offset into terrain arena), `ivec4 renderRanges` (six 16-bit per-face quad counts + unassigned count + translucent base), `int translucentQuadCount`, `int translucencyDataIdx` (`SectionManager.java:213-257`, `scene.glsl:20-29`). **[V]**
- Geometry per section is one contiguous run: translucent quads first, then for each of 7 `ModelQuadFacing`s solid+cutout quads interleaved per facing (`sodiumCompat/SodiumResultCompatibility.java:94-257`). SOLID and CUTOUT are merged into one draw with alpha-test discard at 0.5 in the shared fragment shader (`renderers/PrimaryTerrainRasterizer.java:24-29`, `terrain/frag.frag:150-153`). **[V]**
- Quads = 4 vertices, no index buffer for opaque. **[V]**
- Nvidium vertex format, 16 B (`sodiumCompat/NvidiumCompactChunkVertex.java:13-97`) **[V]**:
  - dword0: x u16 | y u16 (pos = v*32/65536 − 8)
  - dword1: z u16 | material u8 (bit0 = mip) | block light u8
  - dword2: RGB (pre-multiplied by AO "brightness") u8x3 | sky light u8
  - dword3: u u16 | v u16 (scale 1/32768)
  - shader decode: `shaders/terrain/vertex_format/nvidium_vertex_format.glsl:1-29`.
- Optional Sodium COMPACT format (20 B; 20-bit interleaved position, 15-bit UV + bias bit, light, material, section byte) if `use_sodium_vertex_format` (`terrain/vertex_format/sodium_vertex_format.glsl:1-45`, `scene.glsl:1-14`). **[V]**
- Light sampled from vanilla lightmap texture (`terrain/vertex_format/vertex_format.glsl:9-23`). **[V]**

### A.5 Mesh generation

- Uses Sodium's meshing unchanged; only the `ChunkVertexType` encoder is swapped. Repackaging happens on the worker thread at end of `execute` (`MixinChunkBuilderMeshingTask.java:28-36`), computing per-section AABB in 1-block units and per-facing ranges. **[V]**
- Translucency sorting modes (`TranslucencySortingLevel`, default SODIUM, `NvidiumConfig.java:28`):
  - SODIUM: copies translucent quads per facing, and on `ChunkSortOutput` uploads Sodium's index buffer converted to 1 int per quad into the terrain arena (`SectionManager.uploadChunkSort` `:80-138`, `uploadIndexBuffer :68-78`), consumed in translucent mesh shader (`terrain/translucent/mesh.glsl` ~L165-170 `translucencyIndexData[...]`). **[V]**
  - non-SODIUM: CPU radix sort of quads by distance to a camera position projected into the chunk (mutates input) at build time (`SodiumResultCompatibility.java:115-215`); GPU per-region section sorting network (bitonic, 256-wide) `shaders/sorting/region_section_sorter.comp` writes section order into header bits 18-25; QUADS level adds an in-meshlet 32-quad sort + "jiggle" (`terrain/translucent/task.glsl:46-53`). **[V]**

### A.6 GPU culling (exact algorithm)

Per frame in `RenderPipeline.renderFrame` (`nvidium/src/main/java/me/cortex/nvidium/RenderPipeline.java:203-496`) **[V]**:

1. CPU: iterate all regions, frustum-test region AABB via Sodium `Frustum.testAab` (`RegionManager.isRegionVisible`, `RegionManager.java:267-283`), sort visible regions front-to-back by Manhattan-ish distance (`IntAVLTreeSet` keyed `distance<<16|id`, `RenderPipeline.java:246-262`). Regions leaving the frustum get their section visibility bytes cleared (`:268-274`; clears 255 not 256 bytes — off-by-one, minor). Upload region id list into tail of the scene UBO (`:284-291`).
2. Scene UBO (std140) holds MVP, chunk pos, sub-chunk offset and **raw GPU pointers** (NV_shader_buffer_load) to all buffers (`:298-376`, `scene.glsl:62-116`). Bound via `glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, ...)` (`:408`).
3. **Pass 1 – draw last frame's visible set**: `terrainRasterizer.raster(pass, prevRegionCount, terrainCommandBuffer)` = `glMultiDrawMeshTasksIndirectNV` over per-region commands built last frame (`:410-414`; `PrimaryTerrainRasterizer.java:43-57`). This produces this frame's depth from previously visible geometry (temporal coherence / two-phase occlusion).
4. Optional region section sort compute (`:416-420`).
5. **Region occlusion raster**: depth test GEQUAL, depthMask false, color mask off, `glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV)` (`:422-435`), `glDrawMeshTasksNV(0,(regionCount+3)/4)` (`RegionRasterizer.java:80-86`). Mesh shader: 32 threads = 4 regions × 8 corners, emits 12 tris per box (48 prims) (`occlusion/region_raster/mesh.glsl:15-101`); writes `regionVisibility[i]=1` if camera is inside the box (`:94-97`), else 0. Fragment shader with `layout(early_fragment_tests)` writes `regionVisibility[gl_PrimitiveID]=1` (`region_raster/fragment.frag:10-22`). Representative fragment test makes only ~1 fragment per primitive execute the side-effecting FS.
6. **Section occlusion raster**: task shader per visible region (local_size 1) early-outs if region invisible, emits `(count+3)/4` mesh groups (`occlusion/section_raster/task.glsl:28-62`); mesh shader 4 sections × 8 corners, box from per-section min/size (+0.1 margin) (`section_raster/mesh.glsl:53-131`). Primitive ID payload = `(visibilityIndex<<8) | ((lastData<<1)&0xff) | 1`; tid0 writes `sectionVisibility = lastData<<1 | cameraInside` (8-frame visibility history in a byte) (`:112-127`). FS (early fragment tests) writes `sectionVisibility[id>>8] = uint8(gl_PrimitiveID)` → sets bit0 for visible-this-frame while preserving shifted history (`section_raster/fragment.glsl:20-22`). Then representative test disabled, masks restored (`RenderPipeline.java:455-458`).
7. **Command buffer build** compute, one workgroup (32 threads, subgroup ops) per visible region (`occlusion/command_buffer/command_buffer_builder.comp:26-80`): for each of 256 sections computes three lists with `subgroupExclusiveAdd`: opaque visible (bit0), translucent visible (uses sorted/reflected section id, requires translucent quads), **temporal = visible now but not last frame `(v&3)==1`**; writes compacted `u8vec3 sectionIndices` and per-region `uvec2(count, baseSectionId)` commands into terrain / translucency (written in reverse region order for back-to-front) / temporal command buffers.
8. **Pass 2 – temporal raster**: if `enable_temporal_coherence` (default true), draws newly visible sections with `temporal_task.glsl` (`RenderPipeline.java:469-472`).
9. Region visibility statistics: `RegionVisibilityTracker.computeVisibility` re-rasterizes region boxes and downloads the visibility bytes 3 frames later through `DownloadTaskStream` to rank least-recently-seen regions for eviction when over memory budget (`managers/RegionVisibilityTracker.java:266-308`, `NvidiumWorldRenderer.renderFrame` evicts while used > max−100 MB). **[V]**

Opaque draws therefore lag by one frame for newly disoccluded sections only via the temporal pass (same frame), not visually lagging. **[SI]**

### A.7 Draw submission (mesh shaders)

- All terrain: `glMultiDrawMeshTasksIndirectNV(0, regionCount, 0)` with indirect buffer bound by GPU address (`GL_DRAW_INDIRECT_ADDRESS_NV`, `RenderPipeline.java:48-49`; `PrimaryTerrainRasterizer.java:52-54`). One indirect command per region: `{count = #visible sections, first = baseSectionId}` → `gl_WorkGroupID.x` in the task shader = section slot. **[V]**
- Terrain task shader (local_size 1 per section) (`terrain/task.glsl:26-48`, `task_common2.glsl:13-62`): decodes section header, computes camera-relative chunk, builds up to 4 "bins" of face-ranges visible from camera (per-axis backface culling of whole facing groups: `relative <= 0` / `>= 0`, plus unassigned quads) and emits `ceil(quadCount*2/32)` mesh groups. Respects Sodium option `useBlockFaceCulling` via flags bit0 (`RenderPipeline.java:368`). **[V]**
- Terrain mesh shader (`terrain/mesh.glsl:24-190`): 32 threads, 16 quads per workgroup, **2 threads per quad (one per triangle)**; each thread loads the shared corner and its unique vertex; optional **sub-pixel/degenerate triangle culling** using `subgroupShuffleXor` to exchange the partner's screen-space bounds (`:130-156`); primitive compaction by `subgroupExclusiveAdd`; `gl_PrimitiveID = quadId<<1 | tri`. max_vertices 64, max_primitives 32. **[V]**
- Fragment (`terrain/frag.frag:120-171`): with NV barycentrics, re-fetches the 3 vertices from the terrain buffer by `gl_PrimitiveID` and interpolates UV/colour manually (no varyings); fog reconstructed from depth via `MVPInv`. Custom texture filtering: nearest-with-AA (`sampleNearest`) or RGSS 4-tap (when vanilla `TextureFilteringMethod.RGSS`). Alpha cutout 0.5 (opaque) / 0.01 (translucent). **[V]**
- Translucent: 3 OIT variants matching vanilla 26.3 OIT stages (DEPTH_BOUNDS / TRANSMITTANCE / ACCUMULATE) plus classic blend; samples vanilla OIT textures pulled from the `FrontendRenderPass` uniform map (`renderers/TranslucentTerrainRasterizer.java:256-378`). This shows MC 26.3 has a built-in wavelet OIT pipeline (`LevelRenderer.OIT_COEFFICIENT_COUNT`, `OitStage`). **[SI]**
- No `glDrawElements`; bypasses renderpearl `RenderPass` entirely, issues raw GL and fixes up B3D state cache manually (`MixinSodiumWorldRenderer.java:59-70`). **[V]**

### A.8 Buffer management / sync

- `DeviceOnlyMappedBuffer`: immutable storage, made resident, GPU address via `glGetNamedBufferParameterui64vNV(GL_BUFFER_GPU_ADDRESS_NV)` (`gl/buffers/DeviceOnlyMappedBuffer.java:9-23`). **[V]**
- `PersistentSparseAddressableBuffer`: **80 GB virtual sparse buffer** (`util/BufferArena.java:24-25`), `GL_SPARSE_STORAGE_BIT_ARB`, 1 MB pages committed/decommitted by refcount via `glBufferPageCommitmentARB` (`gl/buffers/PersistentSparseAddressableBuffer.java:29-71`). Fallback (Linux) = fixed `max_geometry_memory` device buffer. **[V]**
- Terrain allocation: `SegmentedManager` allocator in quad units; reuse if same size (`BufferArena.java:33-89`). **[V]**
- Uploads: 32 MB `PersistentClientMappedBuffer` (`GL_MAP_PERSISTENT|CLIENT_STORAGE|WRITE`, explicit flush, unsynchronized) as staging ring with per-frame `GlFence`s; `commit()` flushes ranges, `glMemoryBarrier(CLIENT_MAPPED_BUFFER_BARRIER)`, `glCopyNamedBufferSubData` per upload, `glMemoryBarrier(BUFFER_UPDATE)` (`util/UploadingBufferStream.java:44-129`). If full: commit + `glFinish` up to 10 times (`:59-69`). Fences polled with `glClientWaitSync(…,0,0)` (`gl/GlFence.java:11-22`). **[V]**
- Downloads: 8 MB persistent mapped ring, results consumed after N=3 frames without fences (`util/DownloadTaskStream.java:179-192`) — relies on frame latency, not a fence. **[V]**
- Region/section metadata: CPU shadow copy per region, whole 256×48 B block re-uploaded when dirty (`RegionManager.commitChanges`). **[V]**
- Memory budget: auto = `GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX` + current usage − 1 GB, min 2 GB, resampled every 60 s (`NvidiumWorldRenderer.java` `update_allowed_memory`). **[V]**
- Extra feature: `region_keep_distance` keeps far regions beyond the vanilla render distance (up to 256 chunks) and extends fog (`MixinRenderSectionManager.java:67-76`, `MixinFogRenderer.java:11-21`). Public API `api0/NvidiumAPI`: hide/show sections, per-region transformation matrices (up to 1024) and origins (`api0/NvidiumAPI.java:76-144`). **[V]**

### A.9 Portability to Apple GPUs (Metal)

Algorithmic / portable ideas (**[H]** on Metal specifics; Metal facts below are from prior knowledge, **[U]** offline):
1. **Two-phase temporal occlusion culling**: draw last-frame-visible set → depth → test section bounds → draw newly visible. Fully portable; on Apple TBDR the occlusion test is better done as a **compute Hi-Z** (depth pyramid from pass-1 depth) than as box rasterization with side-effect fragment shaders (fragment side effects + `early_fragment_tests` work in Metal (`[[early_fragment_tests]]`, device-buffer writes from fragment functions), but there is **no equivalent of GL_NV_representative_fragment_test** → every covered pixel would run the FS; a Metal `MTLVisibilityResultMode` occlusion query is per-draw, not per-primitive).
2. **Hierarchical region(8x4x8)/section grouping + 1-byte visibility history + subgroup prefix-sum compaction** to build per-region draw lists on GPU. Portable to Metal compute with SIMD-group functions (`simd_prefix_exclusive_sum`, `simd_sum`, `simd_shuffle_xor`; Apple SIMD width 32 matches Nvidium's 32-wide assumptions).
3. **Per-section face-group backface culling in the task/object stage** (Sodium does the same on CPU). Portable.
4. **Quad = 4 verts, no index buffer, 2 threads per quad, sub-pixel triangle culling** in the mesh stage. Portable to Metal mesh functions (object→mesh). Limits differ (Metal: up to 256 vertices / 512 primitives per mesh threadgroup, payload ≤16 KB **[U]**).
5. **Barycentric re-fetch instead of varyings** (fetch vertices by primitive ID in FS): Metal has `[[barycentric_coord]]` and `[[primitive_id]]` (GPU-family dependent **[U]**). On TBDR, cutting varyings reduces parameter buffer traffic — potentially a real win on Apple.
6. **Compact 16-byte vertex** (u16 pos, u8 colour/light, u16 UV). Portable.
7. **GPU pointers in a uniform block**: Metal 3 `MTLBuffer.gpuAddress` + device pointers inside argument/constant buffers is the direct equivalent (requires `useResource` residency or residency sets). Portable.
8. **Sparse 80 GB virtual terrain buffer**: Metal has sparse textures; sparse/placement buffers depend on OS/Metal version **[U]**. Simpler portable alternative: MTLHeap-backed page allocator with a page table or a growable arena (Sodium's approach). Apple unified memory reduces the need for staging copies (shared storage mode buffers can be written directly; staging only for private-storage buffers).
9. **Single MDI over regions**: Metal mesh draws via `drawMeshThreadgroups(indirectBuffer:)` take one threadgroup count per call; Nvidium's "one indirect command per region" needs either an ICB (Metal 3 ICBs support mesh draws **[U]**) or, simpler, one indirect object-dispatch over a GPU-compacted global section list.

NVIDIA-specific (not portable as-is): `GL_NV_mesh_shader` API surface, `NV_*_unified_memory` / bindless MDI / `glBufferAddressRangeNV`, `GL_NV_representative_fragment_test`, `NVX_gpu_memory_info`, NV driver page-size tuning (1 MB pages comment, `PersistentSparseAddressableBuffer.java:61-64`), `#pragma optionNV(unroll all)`.

Apple-TBDR-specific caution: Nvidium merges SOLID+CUTOUT into one pipeline with `discard` (`frag.frag:150-153`). On Apple GPUs `discard` in a pipeline defeats hidden-surface removal for that pipeline **[SI from general Apple guidance, U here]** → keep solid and alpha-tested geometry in separate pipelines (Sodium already separates SOLID/CUTOUT passes).

Mesh-shader availability on Apple: task statement says M3/A17; Apple's Metal feature tables (from memory) list mesh shaders for Apple7+ (A14/M1) as well — **[U]**, must be verified against the current Metal Feature Set Tables before deciding the hardware floor.

Iris constraint for Metallium: a mesh-shader terrain path like Nvidium's cannot run shader-pack vertex/geometry stages; Nvidium simply turns itself off under Iris (A.2). A Metallium design that wants both must either keep a classic vertex-pipeline path for shader packs or translate pack vertex stages into mesh functions **[H]**.

---------------------------------------------------------------------------------------------------

## B) Embeddium (21.1/neoforge, MC 1.21.1)

License: LGPL-3.0 **[V]** (`embeddium/COPYING.LESSER`, `src/main/resources/META-INF/*.toml:3 license="LGPL-3.0-only"`). README: "based on the last FOSS-licensed version of the Sodium codebase" = Sodium ≤0.5.8 (`README.md` Highlights). Contrast: Sodium 0.9.2 is **PolyForm Shield 1.0.0** (`sodium/LICENSE.md:1`) **[V]** — relevant: Embeddium/Nvidium code is LGPL, Sodium's current code is not OSI-open; FRAPI-derived files in Sodium keep FabricMC Apache-2.0 headers (24 files, e.g. `sodium/frapi/.../SodiumRenderer.java:1-15`, `sodium/common/.../render/model/MutableQuadViewImpl.java` header).

### B.1 Maintenance status
- Branch heads **[V]**: `21.1/neoforge` fc5c69b0 2025-02-07; `21.4/neoforge` aa6657df 2025-02-01; `20.1/forge` 2024-09-29; `20.1/fabric` 2024-07-10.
- `origin/dco` f70fa296 2026-09-29 — but every commit since 2025-03 across all branches is `github-actions[bot] Add <user> to the DCO signature list` touching only `signatures.json` (`git log --since=2025-03-01 --all`). **No code commits after Feb 2025 → effectively unmaintained**; no 1.21.5+/26.x port exists in the repo. **[V]**

### B.2 Forge/NeoForge-specific compat (vs Sodium of that era)
- **ModelData**: `model.getModelData(slice, pos, state, slice.getModelData(pos))` then iterate `model.getRenderTypes(state, random, modelData)` per block (multi-layer models), `ctx.update(..., modelData, layer)` (`impl/render/chunk/compile/tasks/ChunkBuilderMeshingTask.java:119-131`); quads fetched with `getQuads(state, face, random, modelData, renderLayer)` (`impl/render/chunk/compile/pipeline/BlockRenderer.java` `getGeometry`, ~L189-194). Cloned sections capture `ModelDataManager` data (`impl/world/cloned/ClonedChunkSection.java`). **[V]**
- **ChunkRenderTypeSet** optimizations, multipart/weighted model `getRenderTypes` mixins (`impl/mixin/features/model/MultipartBakedModelMixin.java`, `WeightedBakedModelMixin.java`, `mixin/features/render/model/ChunkRenderTypeSetMixin.java`). **[V]**
- **NeoForge experimental light pipeline**: if `NeoForgeConfig.CLIENT.experimentalForgeLightPipelineEnabled`, use `ForgeLightPipeline` wrapping NeoForge `QuadLighter`s (`impl/model/light/LightPipelineProvider.java:12-25`, `impl/render/chunk/light/ForgeLightPipeline.java`). **[V]**
- **AuxiliaryLightManager** (per-block light emission from BE) in `WorldSlice`/`ClonedChunkSection`. **[V]** (import presence)
- **Fluids**: `IClientFluidTypeExtensions` for fluid sprites/tint (4 import sites). **[V]**
- **AddSectionGeometryEvent** (NeoForge) bridged from Embeddium's `ChunkMeshEvent` (`impl/chunk/AddSectionGeometryHandler.java:12-27`). **[V]**
- **FRAPI on NeoForge** (Forgified Fabric API): not its own renderer — if Indigo classes exist (`FRAPIRenderHandler.java:9-18` probes `net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderContext`), non-vanilla-adapter models (`!FabricBakedModel.isVanillaAdapter()`, `FRAPIModelUtils.java:7-13`) are delegated to `IndigoBlockRenderContext`, a subclass of Indigo's `BlockRenderContext` whose AO lookups and output go back into Embeddium buffers; handles both Fabric and Forgified `render(...)` signatures by MethodHandle (`impl/render/frapi/IndigoBlockRenderContext.java:31-70`; call site `BlockRenderer.java:156-160`). Item sprite-animation tracking via mixin into Indigo `ItemRenderContext.renderQuad` (`mixin/modcompat/fabric_renderer_indigo/ItemRenderContextMixin.java:14-36`). **[V]**
- Other compat: ModernUI scale hook (`impl/compat/modernui`), CCL compat removed in last commit b30a7800. **[V]**

### B.3 Public API (`org.embeddedt.embeddium.api`)
**[V]** (`src/main/java/org/embeddedt/embeddium/api/`):
- `ChunkMeshEvent` (main thread, per section; register `MeshAppender`s run on worker threads with `Function<RenderType,VertexConsumer>` + `BlockAndTintGetter` + `SectionPos`) (`api/ChunkMeshEvent.java:27-67`, `api/MeshAppender.java:77-93`). Fired from `RenderSectionManager.java:286` (also makes "empty" sections non-empty if appenders exist) and `WorldSlice.java:125`.
- `BlockRendererRegistry` – per-block custom renderers with `OVERRIDE/PASS` (`api/BlockRendererRegistry.java:133-191`), invoked before FRAPI/vanilla path (`BlockRenderer.java:140-153`).
- `ChunkDataBuiltEvent` (modify `SectionInfoBuilder` before finalize; fired `ChunkBuilderMeshingTask.java:194`).
- `RenderSectionDistanceFilterEvent` (custom render-distance cull function; fired once in `OcclusionCuller.java:325-326`).
- `ModifyCloudRenderingEvent` (cloud render distance), `FlawlessFramesService` (FREX flawless frames), option-GUI construction events (`OptionGUIConstructionEvent`, `OptionPageConstructionEvent`, `OptionGroupConstructionEvent`), options structure API, vertex format/serializer API (`VertexBufferWriter`, `VertexFormatRegistry`, ... mirrors Sodium 0.5 API), `MemoryIntrinsics`, `SpriteUtil`.
- Own event bus `api/eventbus/EventHandlerRegistrar` + `EmbeddiumEvent` (also posted on NeoForge bus, given `@EventBusSubscriber` handler in `AddSectionGeometryHandler`).
Fabric/Sodium 0.9.2 has no direct equivalent of `ChunkMeshEvent`/`MeshAppender` in the audited Fabric API **[SI]** (no such event under fabric-renderer/rendering modules).

### B.4 Optimizations/features not in Sodium 0.9.2 (moderate depth)
- Adaptive CPU render-ahead fencing: spin `glClientWaitSync` 40×1 µs then `Thread.yield()` loop, with `cpuRenderAheadLimit` option (`impl/mixin/core/MinecraftClientMixin.java:47-86`, commit 5263007e). Sodium 0.9.2 options have no CPU-frames-ahead setting (`sodium/common/.../gui/SodiumOptions.java:39-69`). **[V]**
- Option "use quad normals for lighting" (non-axis-aligned shading) in Flat/Smooth pipelines (`impl/model/light/flat/FlatLightPipeline.java:33-59`, `smooth/SmoothLightPipeline.java:63-72`). Not in Sodium 0.9.2 options. **[V]**
- Render-pass optimization toggle (`StandardOptions.Option.RENDER_PASS_OPTIMIZATION`, `gui/EmbeddiumGameOptionPages.java:395`), compact vertex format toggle (`:353`), persistent mapping toggle (`:430`). Sodium 0.9.2 has its own pass optimization without toggle **[SI]**.
- NeoForge-specific allocation reductions (ModelData maps, render-layer cache, multipart model) per `git log`. Not applicable to Fabric/Metallium.
- Conversely Sodium 0.9.2 has features Embeddium lacks: hidden fluid culling, improved fluid shaping, quad splitting modes, closest-point entity sort (`SodiumOptions.java:39-55`). **[V]**
Relevance for Metallium: low (NeoForge 1.21.1, unmaintained). Reusable *ideas*: `ChunkMeshEvent/MeshAppender` as a renderer-owned meshing hook; delegating FRAPI to an Indigo-derived context while keeping own buffers.

---------------------------------------------------------------------------------------------------

## C) Indium

License: Apache-2.0 **[V]** (`Indium/LICENSE:1-2`, `src/main/resources/fabric.mod.json "license": "Apache-2.0"`).

- What it did: FRAPI renderer implementation for Sodium 0.5.x, a port of Indigo. Registers `IndiumRenderer` via `RendererAccess.INSTANCE.registerRenderer` (`src/main/java/link/infra/indium/Indium.java:116-141`), declares `"fabric-renderer-api-v1:contains_renderer": true` to suppress Indigo (`fabric.mod.json`). Main hook: redirect Sodium `BlockRenderer.renderModel` in `ChunkBuilderMeshingTask.execute`; non-vanilla-adapter models (or all, if `always-tesselate-blocks`) go to `TerrainRenderContext.tessellateBlock` (`mixin/sodium/MixinChunkBuilderMeshingTask.java:22-47`). Also item and non-terrain block renderer mixins (`indium.mixins.json`). Config `indium-renderer.properties`: `always-tesselate-blocks`, `ambient-occlusion-mode` (default ENHANCED) (`Indium.java:92-112`). **[V]**
- Last supported versions **[V]**: `1.21.x/main` = 1.0.35, MC 1.21/1.21.1 ("mark 1.21.1 as supported", 2024-08-15), Sodium 0.5.11, FRAPI ≥3.4.0 (`gradle.properties`, `fabric.mod.json depends`). `1.20.x` = 1.0.36 (2025-02-25), MC 1.20.1, Sodium 0.5.11.
- Deprecation: README (c4203e6, 2024-11-13) "Since Sodium 0.6.0, Indium is no longer required… isn't compatible with Sodium 0.6.0+". **[V]** (doc; consistent with code findings below.)

### C.1 Sodium 0.9.2 ships FRAPI itself
- Module `sodium/frapi` (Apache-licensed Indigo derivative). Registration: `SodiumRegistrarProvider.isEnabled()` = `isModInLoadingList("fabric-renderer-api-v1")`; `SodiumRegistrar.register()` → `Renderer.register(SodiumRenderer.INSTANCE)` (`sodium/frapi/src/main/java/net/caffeinemc/mods/sodium/client/render/frapi/SodiumRegistrar.java:6-9`, `SodiumRegistrarProvider.java:17-26`), service files `frapi/src/main/resources/META-INF/services/*`. Called at mod init: `sodium/fabric/.../SodiumFabricMod.java:30`, `sodium/neoforge/.../SodiumForgeMod.java:39`. Sodium's `fabric.mod.json:57` sets `"fabric-renderer-api-v1:contains_renderer": true` → Indigo disabled (`fabric-renderer-indigo/.../IndigoMixinConfigPlugin.java:32-56`, `Indigo.java:134-136`). **[V]**
- `SodiumRenderer` implements 26.3 FRAPI `Renderer`: `quadEmitter`, `mutableMesh`, `altModelBlockRenderer` (→ `NonTerrainBlockRenderContext`) (`SodiumRenderer.java:64-100`). **[V]**
- Terrain path: **every** block model goes through `PlatformModelEmitter.emitModel` (`BlockRenderer.renderModel`, `sodium/common/.../chunk/compile/pipeline/BlockRenderer.java:74-97`); with FRAPI present `FRAPIEmitter.emitModel` calls `FabricBlockStateModel.emitQuads(...)` (`frapi/.../FRAPIEmitter.java:16-20`); without FRAPI `DefaultModelEmitter` collects parts (`common/.../services/DefaultModelEmitter.java:19-27`). Vanilla quads use a fast path `bufferDefaultModel` (`common/.../render/model/AbstractBlockRenderContext.java` ~L214-240).
- Feature support in terrain (`BlockRenderer.processQuad`, `BlockRenderer.java:103-121`; `bufferQuad :143-184`; `AbstractBlockRenderContext.shadeQuad` ~L197-212) **[V]**:
  - per-quad **render layer**: `quad.getRenderType()` (FRAPI `chunkLayer`) → `DefaultMaterials.forChunkLayer` (forced SOLID for opaque-leaves setting) — supported.
  - **AO**: TriState per quad; DEFAULT → block default light mode; TRUE/FALSE → SMOOTH/FLAT gated by global AO — supported.
  - **emissive**: sets all 4 lightmaps to `FULL_BRIGHT` — supported (terrain + non-terrain + items: `ExtendedBlockModelFeatureRenderer.java:123`, `ExtendedItemFeatureRenderer.java:100`).
  - **shadeMode** ENHANCED/VANILLA and **shadeDirectionOverride** passed to lighter — supported.
  - **tintIndex** via Sodium `ColorProvider` — supported.
  - **glint/foil**: items only, per-quad `foilType` → `itemGlintRenderType`/`itemGlintSpecialRenderType` (`ExtendedItemFeatureRenderer.java:112-123`) — supported for items; n/a in terrain.
  - **animated** flag: stored (`EncodingFormat.animated`) but terrain uses sprite lookup via `SpriteFinderCache.forBlockAtlas()` to mark animated sprites (`BlockRenderer.java:166-183`); no reader of `quad.animated()` in chunk path **[V]** → flag effectively ignored in terrain **[SI]**. Non-block-atlas `QuadAtlas` in terrain not handled (sprite finder is block atlas only) **[SI]**.
  - **Quad reorientation**: explicit `// TODO: Find a way to reimplement quad reorientation` (`BlockRenderer.java:144-145`) → unsupported.
  - Translucent quads go into Sodium's translucency sorter (`BlockRenderer.java:172-177`).
- 26.3 FRAPI has no `RenderMaterial`/`MaterialFinder` anymore: properties are set directly on `MutableQuadView` (`chunkLayer`, `itemRenderType`, `itemGlintRenderType`, `itemGlintSpecialRenderType`, `emissive`, `shadeDirectionOverride`, `ambientOcclusion(TriState)`, `foilType`, `shadeMode`, `animated`, `tintIndex`, `tag`, `atlas`, `materialBake`) (`fabric/fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView.java:228-543`; `ShadeMode {ENHANCED, VANILLA}`). The task's "RenderMaterial" item does not exist in 26.3. **[V]**

---------------------------------------------------------------------------------------------------

## D) Fabric API @ 26.3 rendering modules

License: Apache-2.0 **[V]** (`fabric/LICENSE:1-2`). Module versions (`fabric/gradle.properties`): renderer-api-v1 17.0.16, renderer-indigo 9.1.24, rendering-v1 27.1.0, rendering-fluids-v1 6.0.6, model-loading-api-v1 8.0.36, block-getter-api-v2 2.0.9. **`fabric-block-view-api` no longer exists; replaced by `fabric-block-getter-api-v2`** (`FabricBlockGetter`, `RenderDataBlockEntity`). **[V]**

### D.1 fabric-renderer-api-v1
- `Renderer` (`get`, `register`, `quadEmitter(Consumer)`, `mutableMesh()`, `altModelBlockRenderer(ao, cull, blockColors)`) (`api/client/renderer/v1/Renderer.java`). **[V]**
- `mesh/`: `Mesh`, `MeshView`, `MutableMesh`, `QuadEmitter`, `QuadView`, `MutableQuadView`, `QuadTransform`, `QuadAtlas`, `ShadeMode`. No MeshBuilder/RenderMaterial in 26.3. **[V]**
- `model/`: `FabricBlockStateModel` (`emitQuads(QuadEmitter, BlockAndTintGetter, BlockPos, BlockState, RandomSource, Predicate<Direction>)` default delegates to parts, `createGeometryKey`, `particleMaterial`) (`model/FabricBlockStateModel.java:90-157`), `FabricBlockStateModelPart`, `FabricBlockStateModelSet`, `MeshQuadCollection`, `ModelHelper`. **[V]**
- `render/`: `AltModelBlockRenderer`, `ChunkSectionLayerHelper`, `FabricBlockModelRenderState`, `FabricLayerRenderState`, `FabricOrderedSubmitNodeCollector`, `submit/ExtendedBlockModelSubmit`, `submit/ExtendedItemSubmit`; `sprite/` `SpriteFinder`, `FabricTextureAtlas`, etc. **[V]**
- Vanilla terrain integration: `SectionCompilerMixin` redirects `ModelBlockRenderer.tesselateBlock` in `SectionCompiler.compile` to `Renderer.get().altModelBlockRenderer(...)` with a `quadEmitter` writing into the vanilla `BufferBuilder` of `quad.chunkLayer()` (`mixin/client/renderer/block/render/SectionCompilerMixin.java`). Also moving-block (pistons), block-breaking overlay, particles, item render state mixins. **[V]**
- → A Metallium terrain mesher that replaces Sodium must call `FabricBlockStateModel.emitQuads` (or use Sodium's FRAPI) and honor per-quad `chunkLayer`/AO/emissive/shade/tint, or FRAPI mods (Continuity, etc.) break. **[SI]**

### D.2 fabric-renderer-indigo
- Default FRAPI impl; registered only if no mod declares `fabric-renderer-api-v1:contains_renderer` (`IndigoMixinConfigPlugin.java:30-56`, `Indigo.java:132-145`); registers `ExtendedBlockModelFeatureRenderer`/`ExtendedItemFeatureRenderer` via `FeatureRendererRegistry`. No chunk context class in 26.3 (terrain goes through renderer-api `SectionCompilerMixin` + `AltModelBlockRendererImpl`). **[V]**

### D.3 fabric-rendering-v1 (27.1.0)
- **`WorldRenderEvents` is gone** — removed in f77d8fd6f (2025-09-22, "Remove WorldRenderEvents and DimensionRenderingRegistry"), replaced by "New World Render Events" becb7e54c (2025-11-02), renamed to Mojang names 574290ba3 (2025-12-31). Current API: `level/LevelRenderEvents`, `level/LevelExtractionEvents`, contexts `LevelTerrainRenderContext` (`sectionsToRender()` **@Nullable** `ChunkSectionsToRender`), `LevelRenderContext` (+`submitNodeCollector()`, `poseStack()`), `AbstractLevelRenderContext` (`gameRenderer()`, `levelRenderer()`, `levelState()`). **[V]**
- `LevelRenderEvents` (`api/client/rendering/v1/level/LevelRenderEvents.java:46-181`): START_MAIN, AFTER_OPAQUE_TERRAIN, COLLECT_SUBMITS, AFTER_SOLID_FEATURES, AFTER_TRANSLUCENT_FEATURES, BEFORE_BLOCK_OUTLINE (cancellable), BEFORE_GIZMOS, BEFORE_TRANSLUCENT_TERRAIN, AFTER_TRANSLUCENT_TERRAIN, END_MAIN; deprecated aliases for extraction events. `LevelExtractionEvents`: AFTER_BLOCK_OUTLINE_EXTRACTION, END_EXTRACTION. `InvalidateRenderStateCallback` fired at `LevelExtractor.allChanged` HEAD. **[V]**
- Where they fire (`fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/LevelRendererMixin.java:67-132`) **[V]**:
  - `render` HEAD: context prepare.
  - `prepareChunkRenders`/`prepareChunkRendersIndirect` RETURN: store `ChunkSectionsToRender`.
  - `executeSolid`: `@WrapOperation` around `ChunkSectionsToRender.renderGroup(...)` → START_MAIN before, AFTER_OPAQUE_TERRAIN after.
  - `submitFeatures` RETURN → COLLECT_SUBMITS; before `finalizeGizmoCollection` → BEFORE_GIZMOS; `new PoseStack` captured.
  - `executeSolid` after `FeatureRenderDispatcher$PreparedFrame.executeSolid` → AFTER_SOLID_FEATURES.
  - `executeClassicTransparency` after `PreparedFrame.executeTranslucent`, and `executeOit` RETURN → AFTER_TRANSLUCENT_FEATURES.
  - `submitBlockOutline` at `CameraRenderState.pos` GETFIELD → BEFORE_BLOCK_OUTLINE.
  - `executeClassicTransparency`: wrap `renderGroup` → BEFORE/AFTER_TRANSLUCENT_TERRAIN. **There is no wrap in the OIT path** (OIT uses `ChunkSectionsToRender.renderOit`), so BEFORE/AFTER_TRANSLUCENT_TERRAIN do not fire when vanilla OIT is used **[SI]**.
  - `lambda$addMainPass$0` RETURN → END_MAIN.
  - `LevelExtractorMixin`: `extractBlockOutline` RETURN, `extract` RETURN, `allChanged` HEAD.
- **How Sodium keeps these firing** (the pattern Metallium must copy): Sodium `@WrapOperation`s `LevelRenderer.render → prepareChunkRenders(...)` and returns `new SodiumChunkSection(...)`, a subclass of vanilla `ChunkSectionsToRender` whose `renderGroup` / `renderOit` draw Sodium terrain (`sodium/common/.../mixin/core/render/world/LevelRendererMixin.java:137-151`, `sodium/common/.../client/util/SodiumChunkSection.java:18-48`). Vanilla `executeSolid/executeClassicTransparency` still call `renderGroup`, so Fabric's wraps fire. Because Sodium never calls the original `prepareChunkRenders`, Fabric's RETURN inject never runs → `LevelTerrainRenderContext.sectionsToRender()` is **null under Sodium** **[SI]** (consistent with the `@Nullable`). Nvidium piggybacks on this (it cancels inside `SodiumWorldRenderer.renderLayer`), so events still fire with Nvidium. **[SI]**
- HUD: `hud/HudElementRegistry` (`addFirst/addLast/attachElementBefore/After/removeElement/replaceElement`, `HudElementRegistry.java:81-152`), `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)` (render-state extraction model), `VanillaHudElements`, `HudStatusBarHeightRegistry`. **[V]**
- Other render-state/registry APIs: `FabricRenderState`/`RenderStateDataKey`, `FeatureRendererRegistry`, `SubmitRenderPhase(s)`, `FabricOrderedSubmitNodeCollector`, `PictureInPictureRendererRegistry`, `FabricRenderPipeline` (adds `usePipelineDrawModeForGui` to vanilla `RenderPipeline`/`Builder`/`Snippet` via `RenderPipelineMixin/RenderPipelineBuilderMixin/RenderPipelineSnippetMixin`), `BlockColorRegistry`, `ColorResolverRegistry`, `BlockTintsFactory`, `EntityRendererRegistry`, `BlockEntityRendererRegistry`, `ArmorRenderer`, `LivingEntityFeatureRenderEvents`, `ModelLayerRegistry`, `AtlasRegistry`, `SpriteSourceRegistry`, `ClientTooltipComponentCallback`, `ExtractItemDecorationsCallback`, `BuiltInBlockModelsCallback`, advancement renderers. **[V]**

### D.4 fabric-rendering-fluids-v1
- `FluidRenderHandler.renderFluid(FluidRenderer, BlockPos, BlockAndTintGetter, FluidRenderer.Output, BlockState, FluidState)` default → `FluidRenderingImpl.renderDefault`; `FluidRenderingRegistry`, `FluidRendering.render(...)` (`api/client/render/fluid/v1/*`). **[V]**
- Sodium integrates in `sodium/fabric/.../render/FluidRendererImpl.java:52-85` (lookup handler, override detection, `FluidRendering.render` with Sodium vertex consumers per layer, guarding re-entry into vanilla `FluidRenderer#render`). **[V]**

### D.5 fabric-model-loading-api-v1
- `ModelLoadingPlugin`, `PreparableModelLoadingPlugin`, `ModelModifier`, `BlockStateResolver`, `CustomUnbakedBlockStateModel`, `CompositeBlockStateModel`, `UnbakedModelDeserializer`, `ExtraModelKey`, wrappers; mixins only into `ModelBakery/ModelManager/BlockStateModel/CuboidModel`. Purely CPU/model-baking; renderer agnostic. **[V]**

### D.6 fabric-block-getter-api-v2
- `FabricBlockGetter` (`getBlockEntityRenderData`, `hasBiomes`, `getBiomeFabric`), `RenderDataBlockEntity`; client mixins into `RenderSectionRegion/RenderRegionCache/MovingBlockRenderState`. Sodium implements it on `LevelSlice` (`sodium/fabric/.../mixin/fabric/LevelSliceMixin.java:16-77`). A custom mesher's world snapshot must implement this. **[V]**

### D.7 Raw GL usage in Fabric API
- grep for `org.lwjgl.opengl|GL11..GL45|GlStateManager|renderpearl.backend|RenderSystem.` across renderer-api, indigo, rendering-v1, rendering-fluids, model-loading, block-getter (main sources): **no hits** **[V]**. Only `fabric-client-gametest-api-v1` touches backends: `GlCommandEncoderMixin` (`com.mojang.renderpearl.backend.opengl.GlCommandEncoder`) and `VulkanGpuSurfaceMixin` (`backend.vulkan.VulkanGpuSurface`), plus `RenderSystem.getDevice().createCommandEncoder().submit()` (`ClientGameTestContextImpl.java:405`). rendering-v1 testmod uses `RenderSystem` matrix/fog setters (test only). **[V]**
- → Fabric's rendering modules are backend-agnostic (go through renderpearl abstractions / render state / submit nodes). A Metal backend implementing renderpearl `GpuDevice` would keep them working, except client gametests (backend-specific mixins). **[SI]**

### D.8 Hooks a Metal renderer must keep firing (summary)
1. Vanilla `LevelRenderer` main-pass structure (`render`, `executeSolid`, `executeClassicTransparency`, `executeOit`, `submitFeatures`, `submitBlockOutline`, `lambda$addMainPass$0`, `LevelExtractor.extract/extractBlockOutline/allChanged`) must stay intact for `LevelRenderEvents`/`LevelExtractionEvents`. Return a `ChunkSectionsToRender` subclass from `prepareChunkRenders` (Sodium pattern) so `renderGroup` wraps fire. Consider calling/setting `sectionsToRender` or accept null like Sodium. **[SI]**
2. If replacing the GPU backend, events give mods no `RenderPass`; mods render via `SubmitNodeCollector` or their own `RenderSystem.getDevice()` passes → Metal `GpuDevice`/`CommandEncoder`/`RenderPass` must support arbitrary mod `RenderPipeline`s incl. Fabric's `usePipelineDrawModeForGui` extension. **[H]**
3. FRAPI: terrain meshing must route through `FabricBlockStateModel.emitQuads` + honor per-quad properties (C.1), set `contains_renderer` or rely on Sodium's renderer. **[V]/[SI]**
4. `FluidRenderingRegistry`/`FluidRendering.render` in the fluid mesher. **[V]**
5. `FabricBlockGetter` on the meshing world snapshot. **[V]**
6. `FeatureRendererRegistry`/`ExtendedBlockModelSubmit`/`ExtendedItemSubmit` feature renderers (FRAPI items/blocks outside terrain) — registered by the active FRAPI renderer (Sodium `FeatureRenderDispatcherMixin.java:38-48`). **[V]**
7. HUD/GUI (`HudElementRegistry`, PiP renderers) — renderer-agnostic as long as the GUI renderer path works on the backend. **[SI]**

### D.9 Licenses summary
| Project | License | Evidence |
|---|---|---|
| Nvidium | LGPL-3.0 | `nvidium/LICENSE.txt`, `fabric.mod.json:18` |
| Embeddium | LGPL-3.0-only (+ RSO MIT portions) | `COPYING.LESSER`, mods.toml `license`, README |
| Indium | Apache-2.0 | `Indium/LICENSE`, `fabric.mod.json` |
| Fabric API | Apache-2.0 | `fabric/LICENSE` |
| Sodium 0.9.2 | PolyForm Shield 1.0.0 (frapi/model files keep FabricMC Apache-2.0 headers) | `sodium/LICENSE.md:1`, file headers |

---------------------------------------------------------------------------------------------------

## Doc-vs-code discrepancies
- Nvidium README says "nvidia gtx 1600 series or newer (turing+)"; code gates purely on GL extension presence, not vendor/arch (`Nvidium.java:29-37`), and Linux silently disables sparse buffers. **[V]**
- Embeddium README claims "Integrated Fabric Rendering API support (Indium no longer required)": on 21.1/neoforge this is delegation to an Indigo `BlockRenderContext` subclass that only works if Indigo classes (Forgified Fabric API) are present (`FRAPIRenderHandler.java:9-18`). **[V]**
- Indium README "Fabric Rendering API support is not currently a priority for upstream Sodium" (old text) vs deprecation header; Sodium 0.9.2 ships FRAPI (C.1). **[V]**
- Task brief mentions "RenderMaterial", "MeshBuilder", "WorldRenderEvents", "fabric-block-view-api": none exist in Fabric API @ 26.3 (see D.1, D.3, D intro). **[V]**

## Open questions / not verified
- Whether toggling an Iris shader pack always reconstructs Sodium's `RenderSectionManager` (needed for Nvidium to re-evaluate `IS_ENABLED`); Iris reload path only partially traced (`PipelineManager.java:36-41`). **[SI]**
- Whether MC 26.3 defaults to Vulkan or OpenGL backend on any platform (affects Nvidium's `MixinGlDevice` capability path); only existence of `backend.vulkan` is verified.
- Metal facts used in A.9 (mesh-shader GPU family floor, ICB mesh-draw support, `[[barycentric_coord]]` availability, sparse/placement buffers, mesh threadgroup limits, HSR-vs-discard behavior) are from memory and unverified offline.
- Nvidium `MixinRenderSection` flags (`isSubmittedRebuild`, `isSeen`) have no readers in the 26.3 code (dead). Not exhaustively confirmed beyond grep of interface usage.
- Sodium FRAPI: whether non-block-atlas `QuadAtlas` quads in terrain are rejected/mis-mapped was inferred from `SpriteFinderCache.forBlockAtlas()` use, not tested.
- Exact behavior of Fabric `BEFORE/AFTER_TRANSLUCENT_TERRAIN` under vanilla OIT (inferred no-fire from absence of a `renderOit` wrap).
- Housekeeping: running `git log -S` in the fabric checkout triggered git's automatic background `gc` (object packing only; working tree and refs unchanged).
