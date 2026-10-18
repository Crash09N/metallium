# Voxy and Distant Horizons vs Metallium (audit)

Date: 2026-10-18. Method: source reading of local clones in /home/user/research (read-only). READMEs were not used as evidence.
Labels: **V** Verified (read in code), **SI** Strongly inferred, **H** Hypothesis, **U** Unverified.
Vanilla 26.3 decompile used for API existence checks: `/tmp/claude-0/-home-user-metallium/9a116912-204a-5100-a967-bcdd5ddbf209/scratchpad/mc/src` (`com/mojang/renderpearl/{api,backend/{opengl,vulkan},frontend}`).
Metallium side: `src/main/java/dev/metallium/...`.

## 0. Bottom line

| Mod | MC 26.3 | License | Renders via | Metallium status today | Class | Effort | Recommendation |
|---|---|---|---|---|---|---|---|
| Distant Horizons | yes (main, 3.3.5-dev) | LGPL-3 file headers, GPL-3 text in root LICENSE.txt | renderpearl ("Blaze") renderer is the 26.3 default; raw-GL renderer still exists but is not selected on 26.3 | **Currently broken by Metallium's own mixin** (see 1.4), fixable with a deletion/one-line change | (b) small compat fix, possibly (a) with no mixin at all | S (0.5-2 days incl. macOS testing) for DH without Iris | Do it. Remove or rewrite `MinecraftRenderWrapperMixin`. DH+Iris shaders is blocked separately (1.7) |
| Voxy | yes (branch `263`, MC 26.3) | Proprietary: "All rights reserved. Do not redistribute." | 100 % raw OpenGL 4.6 compute-driven (GL 4.5/4.6, SSBO, MDI-count, sparse buffers) | Crashes at startup on Metal; explicitly rejects the Vulkan backend | (d) port, plus (c) extension API prerequisite | L-XL (3-6+ engineer-months for Metallium to do it itself; blocked by license for copying) | Do not plan a port. Offer an extension API only if/when the author wants a Metal backend. Tell users Voxy is unsupported |

The premise "mods that support vanilla's Vulkan backend are likely backend-neutral" holds for DH (it is written against renderpearl) and is **false for Voxy** (it contains a mixin that refuses the Vulkan device).

---

## 1. Distant Horizons

### 1.1 Version, commit, license
- Clone: `/home/user/research/distant-horizons`, branch `main`, HEAD `2206fe7a4c496a97641b31c58e126754316d291f` (2026-10-17, "overrides_clear merge"). Core submodule `coreSubProjects` (gitlab.com/jeseibel/distant-horizons-core) initialised at `d7a62ce4a416c2fd8b25cbc60573850333017815` (`git submodule status`). **V**
- MC support: `gradle.properties:` `mod_version=3.3.5-dev`, `api_version=7.3.0`. `versionProperties/26.3.0.properties` -> `minecraft_version=26.3`, `builds_for=fabric,neoforge`, `sodium_version=mc26.3-0.9.2-fabric`, `iris_version=1.11.6+26.3-fabric`. Also `26.2.0.properties`, `26.1.2.properties`, older. Source is multi-version via a preprocessor (`#if MC_VER <= MC_26_2_0 ... #else`). **V**
- Other branches of interest: `origin/Vulkan`, `origin/blazeRender` (not audited; main already contains the Blaze renderer). **U**
- License: every audited source header says "licensed under the GNU LGPL v3 License" (e.g. `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/DhApiRenderProxy.java:1-8`, `coreSubProjects/api/.../DhApi.java:1-8`); root `LICENSE.txt` is the GPL-3 text, `LICENSE.LESSER.txt` / `coreSubProjects/LICENSE.LESSER.txt` is LGPL-3. Doc-vs-code discrepancy: root text GPL vs headers LGPL. **V** Metallium is Apache-2.0: do not copy DH code into Metallium; interop by name via `@Pseudo` mixins is fine (as today). LGPL code adapted would have to live in a separate LGPL module (same rule CLAUDE.md applies to Iris).

### 1.2 Where the renderer lives
- Core (platform-independent logic, API, config, LOD data, `IDhApi*Renderer` interfaces): `coreSubProjects/core`, `coreSubProjects/api`. Shaders are core resources: `coreSubProjects/core/src/main/resources/assets/distanthorizons/shaders/{terrain,generic,fog,ssao,fade,antialias,apply,copy,debug,test}/{gl,blaze}/...` (`gl/` = `.vert/.frag`, `blaze/` = `.vsh/.fsh`).
- MC-bound renderers: `common/src/main/java/com/seibel/distanthorizons/common/render/{openGl,blaze}`.
  - `render/openGl`: 43 files, 542 lines matching `org.lwjgl.opengl|GLxx.|GlStateManager` (V, `git grep -c`). Only `GlDhRenderApiDefinition` chooses it.
  - `render/blaze`: 4,752 lines total. **No raw GL call**: only 2 files import `org.lwjgl.opengl` (`render/blaze/objects/BlazeGenericObjectVertexContainer.java:30,221` uses constant `GL33.GL_UNSIGNED_INT` only for a size lookup; `render/blaze/wrappers/texture/BlazeBlockTextureAtlas.java:33` imports `GL32`, no `GL32.` use found). **V**
- Total files importing `org.lwjgl.opengl` in repo: 67, but most are old-MC modules (`forge112`, `forge17`, `lwjgl2`, GUI for 1.12) that are version-gated out on 26.3. **V**

### 1.3 Blaze/renderpearl renderer: what it needs from the GPU API (all expressible in renderpearl)
- Selected on 26.3 by default: `common/.../wrappers/VersionConstants.java:115-122` (`getDefaultRenderingEngine`: `#if MC_VER <= MC_1_21_11 OPEN_GL #else BLAZE_3D`), consumed by `DependencySetup#setRenderingApiBindingsFromConfigAsync` (`DependencySetup.java:~134-160`), config default `AUTO` (`Config.java:960-962`). **V**
- Imports for 26.3 are `com.mojang.renderpearl.api.{pipeline,commands,device,buffers,vertex,textures}` (e.g. `BlazeDhTerrainRenderer.java:16-21`); `com.mojang.blaze3d.*` imports in the same files are the `#if MC_VER <= MC_26_2_0` branch. **V**
- Calls actually used (`git grep` over `render/blaze`): `GpuDevice.createCommandEncoder/createBuffer/createTexture/createTextureView/createSampler`, `CommandEncoder.writeToBuffer/writeToTexture/createRenderPass/clearColorTexture/clearDepthTexture`, `RenderPass.bindTexture/setUniform/setVertexBuffer/setIndexBuffer/setPipeline/drawIndexed`, `RenderPipeline.builder()` with `withVertexShader/withFragmentShader/withSampler/withUniform(UNIFORM_BUFFER)/withVertexFormat/withDepthTest/withBlend/withPolygonMode/withFaceCulling` (`render/blaze/wrappers/RenderPipelineBuilderWrapper.java:61,134-210,357-365`; `RenderPassWrapper.java:65-125`). **V**
- No compute, SSBO, image load/store, mesh shader, bindless, indirect draw, instanced multi-draw in the Blaze path (`git grep` for `dispatch|compute|SSBO|shader_storage|imageLoad|MultiDraw|Indirect` in `render/blaze` returned nothing). **V**
- Shaders are `#version 330` (all 45 shader files are 330 / 330 core). Blaze variants use `#extension GL_ARB_separate_shader_objects : require` and explicit `layout(location=N)`, std140 UBO blocks with `bool`, `ivec3`, `mat4` members, integer vertex inputs (`uvec4 vPositionAndMeta`, `uint irisMaterial`, `uint textureTile`) and `flat out uint` varyings (`shaders/terrain/blaze/vert.vsh:1-35`). **V**
- Vertex formats: `RGBA16_UINT`, `R8_UINT`, `R16_UINT`, `RGBA8_UNORM`, `RGB32_FLOAT`, `RG32_FLOAT`, `RGBA32_FLOAT` (`BlazeDhVertexFormatUtil.java:114-130`); depth `D32_FLOAT` (grep `GpuFormat` in render/blaze). Metallium already maps these: `backend/MetalConversions.java:22,26,53,81,94,98`. **V**
- Draw model: one `drawIndexed` per LOD buffer per pass, index type INT, reverse-Z supported via depth test `GREATER` (`BlazeDhTerrainRenderer.java:~107-114,362-368`). Per-draw CPU loop, no GPU-driven stuff; same cost class as vanilla chunk drawing on Metallium. **V**
- Residual risk is therefore in Metallium's GLSL->SPIR-V->MSL path handling of: the `GL_ARB_separate_shader_objects` extension line, integer vertex attributes and flat uint varyings, std140 `bool`, and `ivec3` in UBOs. Vanilla 26.3 shaders use the same idioms (the comment at `terrain/blaze/vert.vsh:2` says it is "required as of MC 26.3"), so Metallium's translator must already cope. **SI**, not tested.

### 1.4 The backend check and what Metallium's mixin really does (BUG)
DH code, `common/.../wrappers/minecraft/MinecraftRenderWrapper.java:557-592` (`getMcRenderingApi`):
```
#if MC_VER <= MC_26_1_2  -> OPEN_GL
#else
 backendName = RenderSystem.getDevice().getDeviceInfo().backendName();
 isOpenGl = backendName.equalsIgnoreCase("OpenGL");
 isVulkan = backendName.equalsIgnoreCase("Vulkan");
 isOpenGl -> OPEN_GL ; else isVulkan -> VULKAN ; else -> UNKNOWN
```
**V**. History (`git show 2ff0af18`, 2026-10-13, J. Seibel "MinecraftRenderWrapper handle unknown rendering APIs"): before that commit the method had a single `equalsIgnoreCase("Vulkan")` and `isVulkan ? VULKAN : OPEN_GL`. **V**

Metallium mixin `src/main/java/dev/metallium/compat/distanthorizons/mixin/MinecraftRenderWrapperMixin.java:16-25` is a `@Redirect` (no ordinal) of `String.equalsIgnoreCase` inside `getMcRenderingApi` returning `backendName.equalsIgnoreCase(expected) || "Metal".equalsIgnoreCase(backendName)`. Metallium reports `backendName` = "Metal" (`backend/MetalDevice.java:251-256`). **V**

Consequences:
1. Against DH builds before 2026-10-13 (one `equalsIgnoreCase`): Metal -> VULKAN, works as intended. **V** (by reading old diff)
2. Against current main (two calls): a @Redirect with no ordinal rewrites **every** matching call site in the method, so `isOpenGl` is evaluated true for Metal first and the method returns **OPEN_GL**. **SI** (standard Mixin @Redirect semantics; not run). This is exactly the caveat Metallum's notes flagged as Hypothesis (`docs/research/notes/metallum.md:462-466`); it is now real.
3. Effects of OPEN_GL on Metal:
   - `BlazeDhRenderApiDefinition` (`render/blaze/BlazeDhRenderApiDefinition.java:71-80`) copies the MC api, so the check in `DependencySetup#trySetRenderDefinitionAsync` (`DependencySetup.java:196-201`) passes and the **Blaze** renderer binds (so terrain itself would still go through renderpearl). **V**
   - But `fabric/.../mixins/client/MixinLightTexture.java:111-122` (and neoforge twin) does `if (mcRender.getMcRenderingApi() == OPEN_GL) { GlTexture glTexture = (GlTexture) this.texture; ... glTexture.glId() }`. Metallium's lightmap texture is not a `GlTexture` -> `ClassCastException` on the lightmap update (every frame / every lightmap upload). **SI**
   - `MinecraftRenderWrapper#getGlDepthTextureId/getGlColorTextureId` (`:655-710`) cast to `GlTexture` but swallow `Exception` and return -1; their only callers are in `render/openGl/...` (V, `git grep`), so not reached on the Blaze path.
4. Therefore **current Metallium + current DH main = likely crash** in the lightmap mixin. Correct behaviour requires the answer to be anything other than OPEN_GL.

Fix options (all tiny):
- **Preferred: delete the mixin** for DH >= 2026-10-13. Metal then maps to `UNKNOWN`; `BlazeDhRenderApiDefinition` also reports UNKNOWN (copies MC), the api-match check passes, `MixinLightTexture` skips the GL cast. Nothing else in DH consumes `getMcRenderingApi` (only `DependencySetup.java:196`, `GLProxy.java:139`, `BlazeDhRenderApiDefinition.java:77`, `MixinLightTexture`, neoforge `NeoforgeClientProxy.java:266`). **V**
- If old DH builds must be supported: keep the mixin but redirect only when `expected` is "Vulkan" and return false for "OpenGL": `expected.equalsIgnoreCase("Vulkan") ? (name is Vulkan or Metal) : name.equalsIgnoreCase(expected)`. Works with both old and new DH. **SI**
- Version-gate on DH mod version via the mixin plugin if both are needed. **H**

### 1.5 What the "Vulkan" path actually switches
Only three things (grep of all `EDhApiRenderingApi` users, listed above):
1. Which `AbstractDhApiRenderDefinition` may bind: GL definition requires `OPEN_GL` (`GlDhRenderApiDefinition.java:64`), Blaze takes whatever MC is (`BlazeDhRenderApiDefinition.java:77`). A user/API choosing the GL engine under a non-GL MC gets an error string and falls back to `AUTO` (`DependencySetup.java:175-181,196-201`), no GL touched. **V**
2. `GLProxy` ctor throws if MC api != OPEN_GL (`GLProxy.java:139-143`), so a mis-spoofed GL engine does not silently proceed. **V**
3. `MixinLightTexture`'s GlTexture cast gate (above). **V**
There is no VULKAN-specific rendering code; "Vulkan" and "Unknown" both just mean "use renderpearl objects only". DH's Blaze renderer has never been given a non-GL, non-Vulkan device in DH CI as far as the code shows; correctness on Metal depends on Metallium's renderpearl fidelity. **SI**

### 1.6 Mixins into vanilla / Sodium
`fabric/src/main/resources/DistantHorizons.fabric.mixins.json`: MixinLevelRenderer, MixinGameRenderer, MixinProjectionMatrixBuffer, MixinFogRenderer, MixinLightTexture, MixinChunkSectionsToRender (order 800 so it runs before Sodium cancels `renderGroup`, `MixinChunkSectionsToRender.java:~160`), MixinIrisFrameBuffer, MixinMinecraft, etc. On 26.3 the only raw-GL touch among them is `MixinIrisFrameBuffer` (Iris `IrisRenderSystem.framebufferTexture2D`, only active if Iris is loaded; targets Iris's FBO class by name) and the `GL33.glGetFloatv` in `MixinLevelRenderer.java:162` which is inside `#if MC_VER <= MC_1_16_5`. **V** DH does not mixin Sodium chunk renderer internals on 26.3; interaction with Sodium is via `sodium_version` dependency and render-order hooks (`MixinChunkSectionsToRender`). Sodium 0.9.2 on Metallium works through the generic RenderPass path (`docs/research/notes/metallum.md`, `sodium.md`), so DH rides on that. **SI**

### 1.7 Iris (shader packs) + DH: blocked, independent of Metal-vs-GL spoofing
- `common/.../AbstractModInitializer.java:476-503`: if Iris is present and `renderingEngine == BLAZE_3D`, DH shows a dialog and **crashes MC** ("Iris doesn't support DH when using the BLAZE_3D rendering engine"). If `AUTO`, DH forces the engine to `OPEN_GL` for Iris ("will be unavailable once Minecraft moves to Vulkan and must be fixed on Iris' end"). **V**
- On Metallium with Iris installed and AUTO: DH sets OPEN_GL engine -> `GlDhRenderApiDefinition` (renderApi OPEN_GL). With mixin removed, MC api=UNKNOWN != OPEN_GL -> bind refused (`DependencySetup.java:196-201`), DH has no renderer (logs error, resets to AUTO). With the current broken mixin (api=OPEN_GL) the GL definition binds and `GLProxy` (LWJGL GL calls, `GLProxy.java:~150-200`) would hit raw GL -> crash. **SI**
- Iris side: `compat/dh/*` uses raw GL programs `IrisLodRenderProgram`/`IrisGenericRenderProgram` and the DH GL atlas id (`docs/research/notes/iris-arch.md:222-224`). Metallium's Iris adapter skips `dh_*` programs (`docs/research/notes/minecraft-metal-renderer.md:92`).
- Net: DH works on Metallium only with **vanilla shaders** (no Iris shader pack). With Iris installed but no pack active, DH's AUTO still forces OPEN_GL (the check keys on mod presence, not pack state) - needs a DH-side or compat workaround (see 1.9). **V** (code) / **SI** (consequence)
- To support packs with DH, Iris (or the Metallium Iris adapter) must implement the `IDhApi*Renderer` override path on a non-GL backend and DH must stop forcing OPEN_GL. This is an upstream (Iris/DH) feature; Metallium cannot do it with a mixin of this size. Rates as (d) for the Iris adapter's DH part: M-L. **H**

### 1.8 GPU features required
- GL path (not used on 26.3): requires GL 3.3 (`GLProxy.java:167,205`, optional 4.3 vertex attrib binding, ARB_buffer_storage). **V**
- Blaze path: nothing beyond baseline renderpearl (table 1.3). No GL 4.x, no compute, no SSBO. **V**

### 1.9 What Metallium needs for DH
| Item | Class | Effort |
|---|---|---|
| Replace `MinecraftRenderWrapperMixin` with "do nothing" (Metal -> UNKNOWN) or the safer Vulkan-only redirect | (b) | 1-2 h code + test |
| Gate/disable DH's "force OPEN_GL when Iris present" (`AbstractModInitializer.java:~490-503`): small `@Pseudo` mixin to skip that block when backend != OpenGL, or document "DH + Iris unsupported" | (b) | 0.5-1 day |
| Verify DH shaders through Metallium's GLSL->MSL pipeline (separate-shader-objects extension, uint attributes, std140 bool/ivec3, reverse-Z, D32_FLOAT, MRT-less) | test | 1-3 days on macOS |
| DH + Iris shader packs on Metal | (d), upstream | M-L, depends on Iris adapter; not a DH-side problem alone |
Recommendation: do the first row now, test, ship a "DH supported without shader packs" statement. **SI**

---

## 2. Voxy

### 2.1 Version, commit, license
- Clone `/home/user/research/voxy`: remote default `dev` (`534d58ec`, gradle.properties `minecraft_version=26.2`); the branch targeting 26.3 is **`origin/263`**, checked out at `247adf2625324782e13a3fa8b646c0f95e68031b` (2026-10-16, merge PR #718 "263"), `gradle.properties: minecraft_version=26.3`. `git diff --stat origin/dev origin/263` = 32 files, so 26.3 is a thin port of dev. Other branches: `2612, 2622, 2612, mc_1217_mesh*, ...`. **V**
- License: `LICENSE.md`: "Copyright 2025 MCRcortex. All rights reserved. Do not redistribute." (plus an MIT-style warranty disclaimer). **V** Proprietary; **no code may be copied or adapted** (also not "ideas+code" for an AI-assisted port). Ideas-only is the CLAUDE.md-consistent stance (same class as Sodium/MetalRender).
- Source size: 231 Java files, 31,471 lines; 47 shader files in `src/main/resources/assets/voxy/shaders`. **V**

### 2.2 How it renders: raw OpenGL 4.6, GPU-driven
- 45 Java files contain `gl*(` calls (590 call-lines, 138 distinct GL function names, `git grep`); 43 files import `org.lwjgl.opengl`; only 14 files mention renderpearl/blaze3d, and these are mostly mixin targets or `GlStateManager` calls (`com.mojang.renderpearl.backend.opengl.GlStateManager`, `GlConst`, `GlDebug`), i.e. renderpearl **OpenGL backend internals**, not backend-neutral API. **V**
- Pipeline (MDIC = multi-draw-indirect-count): `core/rendering/section/backend/mdic/MDICSectionRenderer.java` (only backend; `AbstractSectionRenderer`):
  - compute `prep.comp`, occlusion-raster pass with `glDrawElementsIndirect` (`:~283-300`), `cmdgen.comp` via `glDispatchComputeIndirect` + SSBO bindings 1-7 (`:~315-330`), final draw with `glMultiDrawElementsIndirectCountARB` (`:204,245`), prefix-sum compute (subgroup variant `util/prefixsum/inital3.comp`), `buildtranslucents.comp`. **V**
  - Hierarchical LOD traversal fully on GPU: `lod/hierarchical/traversal_dev.comp` (`#version 460`, SSBO queues, `restrict buffer` std430, atomics), `cleaner/*.comp`, `hiz/hiz.comp` (r32f `image2D` writes, 3 mips), `post/ssao.comp` (`imageStore`), `util/{memcpy,scatter,set}.comp`. **V**
  - Vertex shader reads per-section data from SSBO using `gl_BaseInstance` as an index (`lod/gl46/quads3.vert:54`), requires `GL_ARB_shader_draw_parameters` (`hierarchical/debug/node_outline.vert`). **V**
- Buffers/sync: persistent-mapped coherent buffers (`gl/GlPersistentMappedBuffer.java:16-17`), `glFenceSync` (`gl/GlFence.java`), `glCopyNamedBufferSubData`, DSA everywhere, `GL_TEXTURE_2D` DSA textures, sparse buffers (`ARBSparseBuffer.glBufferPageCommitmentARB`, `BasicSectionGeometryData.java:89,133`, optional), GPU timer queries (`GPUTiming`). **V**
- It consumes **MC's GL texture ids** (depth/colour source) and the GL FBO binding, and draws into MC's framebuffer state (`VoxyRenderSystem.java:~270-350`: `glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING)`, `glGetIntegerv(GL_VIEWPORT)`, `glGetTextureLevelParameteri(sourceDepthTexture,...)`), and resets GL state afterwards via `GlStateManager` (`:355-384`). **V**

### 2.3 Backend checks
- `client/mixin/minecraft/session/MixinMinecraft.java:19-25`: `@WrapOperation` on `GpuBackend#createDevice`; `if (instance instanceof VulkanBackend) throw new BackendCreationException("Voxy does not presently support vulkan", ...)` (method name `voxy$forceOpenglDevice`). So Voxy does not run on vanilla's Vulkan backend; it never checks `backendName`. A Metal backend is not a `VulkanBackend`, so this check does not fire and Voxy proceeds to GL. **V**
- `client/mixin/minecraft/MixinRenderSystem.java:14-19`: `@Inject(RenderSystem.initRenderer, RETURN, order 900)` calls `VoxyClient.initVoxyClient()` -> `Capabilities.init()` -> `GL.getCapabilities()` (`gl/Capabilities.java:58`) and compiles probe compute shaders (`:67,76`) with raw GL. Mixin config `required:true`, `defaultRequire:1` (`client.voxy.mixins.json`). On Metal there is no GL context: LWJGL throws at `GL.getCapabilities()` (or crashes in GL calls) during RenderSystem init -> **game fails to start with Voxy installed**. **SI** (code path clear; LWJGL behaviour not run here)
- Also mixins `MixinGlDebug` (targets `renderpearl.backend.opengl.GlDebug`; class exists in 26.3 jar so it applies), `MixinGPUSelect` (Windows-only GPU selector; sets realtime thread priority via Win32 JNI, `ThreadUtils.SetSelfThreadPriorityWin32`: no-ops/fails off-Windows, **U** on macOS). **V**
- `VoxyClient.java:25-35`: `systemSupported = compute && indirectParameters && !hasBrokenDepthSampler`; otherwise logs "Voxy is unsupported on your system" and disables itself. No GL-less fallback. **V**

### 2.4 GPU feature requirements and renderpearl expressibility
| Feature (Voxy uses) | Evidence | renderpearl can express? | Metal native? |
|---|---|---|---|
| Compute shaders, indirect dispatch | `glDispatchCompute*` in 8 files; 15+ `.comp` files | No (no compute API in renderpearl `api/`; confirmed by absence in `com/mojang/renderpearl/api` listing: buffers, commands, device, pipeline, textures, vertex) | Yes (MTLComputeCommandEncoder, indirect dispatch) |
| SSBO std430, `restrict buffer`, atomics (`atomicAdd`, `atomicCompSwap`, custom `atomicDerefMaxExchange*`) | `traversal_dev.comp`, `queue.glsl`, `cmdgen.comp` | No | Yes (device buffers; atomics) |
| 64-bit integers/atomics | `#extension GL_ARB_gpu_shader_int64` in 5 shaders, `Capabilities.INT64_t` probe (`Capabilities.java:67`) | No | Partial: Metal supports 64-bit int; 64-bit atomics only on newer Apple GPUs and limited ops **H** |
| `glMultiDrawElementsIndirectCount` (required: `indirectParameters`) | `MDICSectionRenderer.java:204,245`, `Capabilities.java:61` | No | No direct equivalent; would need indirect command buffers (ICB) filled by compute, or `drawIndexedPrimitives(indirectBuffer)` per command **SI** |
| `gl_BaseInstance`/draw parameters | `quads3.vert:54` | No | base-instance supported in Metal; mapping via SPIRV-Cross works **SI** |
| Subgroup ops (arithmetic/clustered) | `inital3.comp` etc; `KHR_shader_subgroup_*` | No | Yes (SIMD-group functions; Apple GPU SIMD width 32) |
| Image load/store (r32f, rgba8) | `hiz.comp`, `ssao.comp` | No | Yes |
| Persistent-mapped coherent buffers + fences | `GlPersistentMappedBuffer`, `GlFence` | partial (GpuBuffer map exists in API; semantics differ) **U** | Yes (shared storage, MTLSharedEvent) |
| Sparse buffers | optional (`sparseBuffer`) | No | Metal sparse buffers/heaps are different; optional so can be skipped |
| Mesh shaders, rep-frag-test, barycentrics (NV) | `Capabilities.meshShaders/repFragTest/nvBarryCoords`; used only if present (`MDICSectionRenderer.java:113-115,279,298`) | No | NV-only, skipped on Apple. No mesh-shader backend exists in the 26.3 tree (only MDIC) **V** |
| Bindless | not used | n/a | n/a |
| DSA, depth-texture sampling, `glTextureBarrier`, stencil | various | No (pass-level API only) | Yes with different structure (render encoder boundaries) |

Conclusion: Voxy's core loop (GPU hierarchical traversal -> GPU command generation -> MDI-count) cannot be expressed in renderpearl, which exposes only render passes, pipelines, uniform buffers and per-draw calls. Metal can do all of it natively, but only through a Metallium-specific API with Metal-flavoured design. **SI**

### 2.5 Sodium and Iris integration
- Voxy requires Sodium (`fabric.mod.json` `depends sodium` via `allowed_sodium_versions` from `build.gradle:126-136`; `gradle.properties: sodium_version_modrinth=mc26.2-0.9.2-fabric` on dev) and hooks its internals: `mixin/sodium/{MixinDefaultChunkRenderer,MixinRenderSectionManager,MixinSodiumWorldRenderer,MixinVisibleChunkCollector,MixinFallbackVisibleChunkCollector,MixinRenderRegionManager,MixinChunkJobQueue,AccessorChunkTracker,AccessorSodiumWorldRenderer}` (`client.voxy.mixins.json`). These are Java-side (section visibility / chunk replacement) and would work on Metallium only if Metallium keeps Sodium class shapes (it does: Sodium runs unmodified on Metallium). **SI**
- Nvidium mixin (`mixin/nvidium/MixinRenderPipeline`) and Flashback mixins: optional integrations.
- Iris: 10 mixins/accessors in `mixin/iris/*`, `client/iris/{IrisShaderPatch,IrisVoxyRenderPipelineData,VoxySamplers,VoxyUniforms}`, `core/IrisVoxyRenderPipeline.java`: Voxy injects its own programs (`voxy*`) into the shader pack and renders using Iris's GL framebuffers/samplers (`MixinIrisRenderingPipeline.java:43` targets `renderpearl/backend/opengl/GlStateManager._activeTexture` inside Iris). Pure GL-coupled. Metallium's Iris adapter skips `voxy*` programs (`minecraft-metal-renderer.md:92`). **V/SI**

### 2.6 What Metallium would need
| Option | Class | Effort | Comment |
|---|---|---|---|
| Works as-is | (a) | - | No. Crashes at RenderSystem init |
| Compat mixins only | (b) | - | Not sufficient. Could at best add a mixin to cancel `initVoxyClient`/make Voxy self-disable cleanly on Metal (0.5 day) so the game starts; that is a courtesy fix, not support |
| Extension API: compute pipelines, storage buffers (SSBO), indirect dispatch, ICB/MDI-count equivalent, image store, subgroup, shared/persistent buffers, GPU fences, 64-bit atomics query | (c) | L: ~6-10 weeks to design+implement+test in Metallium (new `GpuDevice` extension surface, GLSL 4.60 compute -> SPIR-V -> MSL path via glslang/SPIRV-Cross for compute, resource binding model) | Prerequisite for any port; useful beyond Voxy |
| Port Voxy renderer to Metallium API | (d) | XL: 3-6+ engineer-months (rewrite ~30 k lines of GL-coupled render/upload/traversal code, replace MDI-count with ICB generation, re-derive FBO/sync assumptions, Iris interplay) | License forbids deriving from the code; would have to be clean-room or done by MCRcortex |
| Feasible at all | | | Technically feasible on Apple GPUs (Metal is a better fit than the GL path); organisationally/legally infeasible for Metallium to do on its own |
Recommendation: Do not schedule Voxy. If Voxy is a headline goal, contact the author about a native-Metal render backend using a Metallium extension API (option (c) + author-written (d)); otherwise treat as (e) infeasible for the project. Provide the safe-disable mixin only if users report startup crashes. **SI**

---

## 3. Comparison with the premise "Vulkan-capable mods are backend-neutral"

- DH: the Blaze renderer was built against renderpearl and contains zero GL calls; it needs no GL even though DH's file `BlazeDhTerrainRenderer` is labelled "Blaze". Backend neutrality holds for rendering; GL leaks exist only in the Iris interplay and a lightmap cast keyed to the OPEN_GL value.
- Voxy: explicit anti-Vulkan guard plus unconditional GL init. Not neutral at all.
- Nvidium (for context, from the preamble) is NVIDIA-only; Voxy shares the "mesh shader if available" capability probes but has no mesh-shader backend in this branch.

## 4. Doc-vs-code discrepancies found
- `docs/research/notes/metallum.md:462-466` called the multi-redirect effect a Hypothesis and DH behaviour Unverified: now verified from DH source (1.4); the redirect hits both `equalsIgnoreCase` calls on current DH and yields OPEN_GL, not VULKAN.
- `docs/research/METALLIUM-RESEARCH-REPORT.md:79,465,663,859` marks DH renderer as "DH's GL renderer is the obstacle": on 26.3 the default is not the GL renderer; the Blaze renderer is the default and GL-free.
- `docs/research/notes/mcopt.md:69,233`: mcopt turns Metal off when DH + Iris or DH config `OPEN_GL`; consistent with DH's own force-OPEN_GL-for-Iris block (`AbstractModInitializer.java:476-503`).
- DH licence: headers LGPL-3, root LICENSE.txt GPL-3 text.

## 5. Open questions / not verified
- Nothing was run (no macOS, no Minecraft jars, no DH build). All runtime consequences (CCE in MixinLightTexture, LWJGL failure without GL context, Mixin redirect covering both calls) are inferred from code, label SI.
- Whether Metallium's lightmap `GpuTexture` really is non-`GlTexture` and how `LightTexture` is shaped on 26.3 (MixinLightTexture target members were not cross-checked against the decompile).
- Whether released DH 26.3 builds predate commit `2ff0af18` (2026-10-13) - determines whether the current Metallium mixin works against the public jar. `git tag --contains 2ff0af18` returned nothing (no tag; describe `3.3.4-67-g2206fe7a`).
- Metallium's translator handling of DH shader idioms (1.3) and of reverse-Z/D32_FLOAT.
- DH `Vulkan` and `blazeRender` branches, DH behaviour with Sodium's own render ordering at runtime, Chunky/other integrations.
- Voxy on branch `263` with Sodium 0.9.2 vs the dependency range in `build.gradle`'s `sodium_versions` (not resolved); Voxy's behaviour when `GL.getCapabilities()` is called with no context (LWJGL specifics); Win32 thread-priority JNI on macOS.
- Whether 64-bit atomics (`atomicDerefMax*`, uint64 in 5 shaders) are only an optimisation (`INT64_t` probe) or required by the traversal: grep shows they are conditionally compiled but not traced end to end.
- Metal ICB feasibility for MDI-count and exact Apple GPU generation requirements: **H**.
