# Metallum (v0.0.25 @ 4da72f5) + metal-jvm (0.1.0 @ ccb7789) — deep audit

Paths below are relative to `/home/user/research/`. Abbreviation: `M/` = `metallum/src/main/java/com/metallum/`,
`R/` = `M/render/`, `MJ/` = `metal-jvm/src/main/java/io/github/kokodio/metaljvm/`.

All 32 non-.git files of Metallum were read; every Java file read completely (22 render/compat/mixin classes, ~4.2k LOC).
metal-jvm: the 4 runtime classes in `objc/` read fully; representative generated wrappers sampled (MTLRenderCommandEncoder,
MTLDevice, MTLCommandBuffer, CAMetalLayer, MTLRenderPassColorAttachmentDescriptor, Metal). metal-jvm is ~78k LOC of Java wrappers.

---------------------------------------------------------------------------------------------------------------------------
## 0. Headline findings (decision-relevant)

1. **Minecraft 26.3's backend abstraction is NOT `com.mojang.blaze3d.*` any more — it is `com.mojang.renderpearl.*`** [Verified
   from imports]. 26.2-era Metallum code imported `com.mojang.blaze3d.systems.GpuBackend` / `com.mojang.blaze3d.buffers.GpuBuffer`
   (`git show d79ffc1:src/main/java/com/metallum/mixin/PreferredGraphicsApiMixin.java`, `git show 4da72f5^:src/main/java/com/metallum/render/MetalDrawContext.java`);
   the 26.3 port commit `4da72f5` ("26.3") renamed everything to `com.mojang.renderpearl.{api,backend,frontend,util}`. Only
   `com.mojang.blaze3d.systems.ScissorState` / `RenderSystem` remain in the old package.
2. **26.3 has a front-end / back-end split**: mods implement `renderpearl.backend.api.*Backend` interfaces (GpuDeviceBackend,
   CommandEncoderBackend, RenderPassBackend, GpuSurfaceBackend, BackendRenderPipeline) and the vanilla
   `renderpearl.frontend.FrontendGpuDevice` wraps them (`R/MetalBackend.java:50`). Vanilla ships at least an OpenGL backend
   (`renderpearl.backend.opengl.GlDevice`) and a Vulkan backend (`renderpearl.backend.vulkan.VulkanDevice`) [Verified via Sodium/Iris imports:
   `sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java:5`, `Iris/.../iris/gl/IrisRenderSystem.java:579-580`].
3. **26.3 windowing is SDL3 (LWJGL `org.lwjgl.sdl`), not GLFW**: `GpuBackend.createWindow(title,w,h,flags)` →
   `SDLVideo.SDL_CreateWindow(title,w,h,SDL_WINDOW_METAL|flags)` (`R/MetalBackend.java:33-35`); the surface uses
   `SDL_Metal_CreateView` / `SDL_Metal_GetLayer` to get the CAMetalLayer (`R/MetalSurface.java:31-49`). No NSWindow/AppKit is touched. [Verified]
4. **Vanilla 26.3 hands backends SPIR-V** (`BackendRenderPipeline.CreateInfo.shaders()` → `SpvModule.spv()` ByteBuffer + `ShaderType`)
   (`R/MetalCrossShaderCompiler.java:53-62`). Metallum converts SPIR-V → MSL with **LWJGL's SPIRV-Cross binding (`org.lwjgl.util.spvc`)**
   at runtime; no glslang, no native code of its own. The spvc natives must come from Minecraft's own LWJGL distribution (Metallum's
   `build.gradle:13-20` only adds sodium + metal-jvm) [Strongly inferred].
5. **Sodium 0.9.2 terrain DOES run on top of Metallum's backend** — Sodium on 26.3 no longer issues raw GL draws; it draws through
   `renderpearl.api.commands.RenderPass`. Because Metallum's backend is not a `VulkanDevice`, Sodium picks its `DrawBackend.OPENGL`
   path, which is *backend-agnostic*: `RenderPass.multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, int)` →
   Metallum loops one `drawIndexedPrimitives` per section (`R/MetalRenderPass.java:226-249`). [Verified, see §9]
6. **Iris is neither supported nor blocked**: no Iris code/mixin/`breaks` in Metallum. Iris 26.3 applies its GL mixins whenever
   options.txt's `preferredGraphicsBackend` isn't "vulkan" and then hard-casts the backend to `GlDevice` → with Metallum, Iris
   should crash (ClassCastException) [Strongly inferred, §10].
7. **Metallum forces Metal unconditionally** (`M/mixin/PreferredGraphicsApiMixin.java:14-17` returns only `{new MetalBackend()}`):
   the user's graphics-API choice is ignored, there is no GL/Vulkan fallback, and since commit `d79ffc1` removed the
   `MetallumMixinConfigPlugin` (which had an `os.name contains "mac"` gate), there is **no OS check** at all [Verified].
8. Architecture is a straightforward "Metal 2/3 immediate" port: one MTLCommandQueue, one MTLCommandBuffer per `submit()`, all
   resources `MTLHazardTrackingModeUntracked`, a **single global MTLFence** chained between every encoder, 3 submits in flight
   with Java `Semaphore` + ObjC completion block, deferred destruction ring of 3, no argument buffers, no ICBs, no Metal 4,
   no binary archives / pipeline disk cache, no async pipeline creation, CPU `System.nanoTime()` fake GPU timestamps.
9. metal-jvm is **pure Java FFM (Panama)** — no JNI, no Objective-C/Swift sources, no bundled native libraries. It calls
   `objc_msgSend` directly through `Linker.downcallHandle` with `Linker.Option.critical(false)` for short calls; arm64 ABI struct
   rules are hand-encoded. Shader compilation is only `newLibraryWithSource` (runtime MSL compile); it does not bundle glslang or SPIRV-Cross.
10. Licenses: both MIT, "Copyright (c) 2026 kokodio" (`metallum/LICENSE:3`, `metal-jvm/LICENSE:3`). README says "vibecoded as hell"
    (`metallum/README.md:8`).

---------------------------------------------------------------------------------------------------------------------------
## 1. Repository inventory

| File | LOC | Role |
|---|---|---|
| `M/Metallum.java` | 14 | ModInitializer, empty `onInitialize`; only holds LOGGER |
| `M/MetallumConfig.java` | 39 | Gson config `config/metallum.json`, single field `displayP3` (default false) |
| `M/mixin/PreferredGraphicsApiMixin.java` | 23 | forces Metal backend, renames caption |
| `M/compat/sodium/MetallumSodiumConfig.java` | 26 | Sodium config-API page "Display P3" |
| `M/compat/sodium/mixin/SodiumPreferredGraphicsApiMixin.java` | 29 | label fix in Sodium's cycling control |
| `M/compat/distanthorizons/mixin/MinecraftRenderWrapperMixin.java` | 21 | DH: treat "Metal" backendName like Vulkan |
| `M/compat/shine/mixin/ShineRenderBackendMixin.java` | 23 | "Shine" mod: report "Metal" as "Vulkan" |
| `R/MetalBackend.java` | 55 | `GpuBackend` impl |
| `R/MetalDevice.java` | 265 | `GpuDeviceBackend` impl |
| `R/MetalCommandEncoder.java` | 742 | `CommandEncoderBackend` impl; submit/sync/clears/copies |
| `R/MetalRenderPass.java` | 671 | `RenderPassBackend` impl; draws, state binding |
| `R/MetalCompiledRenderPipeline.java` | 328 | `BackendRenderPipeline` impl; PSO variants |
| `R/MetalCrossShaderCompiler.java` | 370 | SPIR-V→MSL via spvc |
| `R/MetalUtilities.java` | 445 | present blit, clear-quad pipelines, misc |
| `R/MetalGpuBuffer.java` | 187 | extends vanilla `BaseGpuBuffer` |
| `R/MetalTransientMemory.java` | 225 | `TransientMemory` impl on vanilla `TransientBlockAllocator` |
| `R/MetalGpuTexture.java` / `MetalGpuTextureView.java` / `MetalGpuSampler.java` | 159/68/112 | textures/views/samplers |
| `R/MetalSurface.java` | 97 | `GpuSurfaceBackend` impl (CAMetalLayer) |
| `R/MetalFence.java` / `MetalGpuQueryPool.java` / `MetalDestructionQueue.java` | 27/49/48 | fences, fake timestamp queries, deferred destroy |
| `R/MetalConversions.java` | 202 | enum/format tables |
| `R/ResourceBindings.java` | 71 | 64-slot uniform binding table w/ dirty mask |
| `R/Stats.java` | 108 | **dead code** (no callers: `grep -rn "Stats\." metallum/src` → none) |
| resources | | `fabric.mod.json`, 4 mixin JSONs (all `required:true`, `defaultRequire:1`), icon |
| `build.gradle`, `gradle.properties` | | MC 26.3, loader 0.19.5, loom 1.16-SNAPSHOT, `sodium mc26.3-0.9.2-fabric` (compile `implementation`), `include(implementation("io.github.kokodio:metal-jvm:0.1.0"))`, Java 25 |
| `.github/workflows/build.yml` | | builds on ubuntu, publishes to Modrinth (alpha) on `v*` tags; Sodium (`AANobbMI`) listed as **optional** dependency |

`fabric.mod.json:31-35` depends only on fabricloader ≥0.19.5, minecraft `~26.3-`, java ≥25. No `breaks`/`conflicts` (Iris not declared),
no `suggests`. `"environment": "*"` though everything is client-only. Description: "This is an PoC Metal backend for Minecraft on macOS".

History: 84 commits, 2026-06 (26.2-rc) → 2026-10-10 (26.3). Notable: `fefc416` "Port to objc", `4f695c7` "Port to metal-jvm",
`d79ffc1` "Force metal backend, fix dh" (removed OS gate), `a12ffb3` "MRT", `4da72f5` "26.3" (removed Sodium DrawBackend/DrawContext
mixins and `MetalDrawContext`, added `ResourceBindings`, removed access widener).

---------------------------------------------------------------------------------------------------------------------------
## 2. Reconstructed Minecraft 26.3 "renderpearl" backend API (from Metallum's implementations)

Label: all signatures are **Verified as what Metallum compiles against** (`@Override` present). Semantics are inferred from
Metallum's implementation and labelled. Package prefix `com.mojang.renderpearl.` omitted.

### 2.1 `api.device.GpuBackend` (interface) — `R/MetalBackend.java`
| Method | Metallum impl |
|---|---|
| `String getName()` | `"Metal"` (L20-22) |
| `void loadLibrary()` / `void unloadLibrary()` | no-ops (L24-30) — presumably where GL/Vulkan backends load their native loader (Hypothesis) |
| `long createWindow(@Nullable String title, int width, int height, long flags)` | `SDL_CreateWindow(title,w,h, SDL_WINDOW_METAL \| flags)` (L33-35) → backend owns window creation flags (GL/Vulkan presumably pass SDL_WINDOW_OPENGL/VULKAN) [Strongly inferred] |
| `GpuDevice createDevice(GpuDebugOptions) throws BackendCreationException` | `MTLCreateSystemDefaultDevice`; wraps `new FrontendGpuDevice(new MetalDevice(...))` (L38-54) |

Related: `api.device.BackendCreationException(String, Reason)` with `Reason.PLATFORM_ERROR`, `Reason.OTHER` (L41, L52).
`GpuDebugOptions` record has `logLevel()`, `useLabels()`, `useValidationLayers()` (`R/MetalDevice.java:121-127`).

Backend list selection: `net.minecraft.client.PreferredGraphicsApi` enum (values seen: `DEFAULT`, `OPENGL`, `VULKAN` —
`sodium/.../gui/SodiumConfigBuilder.java:313-331`, Iris `ShaderPackScreenPlaceholder.java:45`) has
`GpuBackend[] getBackendsToTry()` and `Component caption()` (`M/mixin/PreferredGraphicsApiMixin.java:14-22`). Vanilla presumably
tries backends in order and falls back on `BackendCreationException` [Strongly inferred from the array return type].

### 2.2 `backend.api.GpuDeviceBackend` — `R/MetalDevice.java`
| Method | Metallum impl |
|---|---|
| `GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified)` | `new MetalSurface(windowHandle, device)`; **isIconified ignored** (L59-62) |
| `CommandEncoderBackend createCommandEncoder()` | returns the single shared `MetalCommandEncoder` (L64-67) → vanilla uses exactly one encoder [Strongly inferred] |
| `GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod)` | `new MetalGpuSampler` (L69-79). Note: no W address mode, no mip filter param, no compare op, no border color in the API |
| `GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int w, int h, int depthOrLayers, int mipLevels)` | `new MetalGpuTexture` (L81-92) |
| `GpuTextureView createTextureView(GpuTexture, int baseMipLevel, int mipLevels)` | `new MetalGpuTextureView` (L94-97) (mip-range views only; no layer/format views in API) |
| `GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size)` | `new MetalGpuBuffer` (L99-102) |
| `GpuBuffer createBuffer(Supplier<String>, int usage, ByteBuffer data)` | creates w/ `|USAGE_COPY_DST`, CPU write or staged blit (L104-113) |
| `List<String> getLastDebugMessages()` | `List.of()` (L115-118) |
| `boolean isDebuggingEnabled()` | logLevel>0 \|\| labels \|\| validation (L120-123) |
| `BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo)` | SPIR-V→MSL eagerly; returns lambda `Pending` that builds MTL objects; `Pending.NULL` on failure (L129-147) |
| `void close()` | waits GPU, releases (L155-167) |
| `GpuQueryPool createTimestampQueryPool(int size)` | `new MetalGpuQueryPool(size)` (L169-172) |
| `long getTimestampCalibrationOffset()` | `0L` (L174-177) |
| `DeviceInfo getDeviceInfo()` | built once (L183-186, L240-259) |

`BackendRenderPipeline.Pending` is a functional interface returning a `BackendRenderPipeline` (or null) with constant `Pending.NULL`
(L136, L139-146) → vanilla splits pipeline compilation into an eager stage and a deferred "resolve" stage (possibly for async
compile) [Strongly inferred; which threads call each stage is Unverified].

`DeviceInfo` record (11 components, `R/MetalDevice.java:246-258`): `(String name="device", String vendor="Apple", String driver="macOS x",
boolean ?=true, String backendName="Metal", float ?=1.0F, DeviceLimits, DeviceFeatures, Set<String> underlyingExtensions, HintsAndWorkarounds, DeviceType)`.
Accessor names observed in other mods: `backendName()`, `features()`, `limits()`, `underlyingExtensions()` (Iris `Iris.java:432`).
`DeviceLimits(16, 16, 16384, maxAlloc, Integer.MAX_VALUE, ColorTargetState.MAX_COLOR_TARGETS, Integer.MAX_VALUE)` — mods use
`limits().maxTextureSize()` and `limits().minUniformOffsetAlignment()` (positions unknown; 16384 is likely maxTextureSize, a 16 likely
minUniformOffsetAlignment) [Hypothesis on positions]. `DeviceFeatures(8 booleans, all true)` — accessors seen: `multiDrawDirectInterleaved()`,
`multiDrawIndirect()`, `persistentMapping()` (Sodium). `HintsAndWorkarounds(false,false,true,false)` — names Unverified.
`DeviceType.INTEGRATED`.

### 2.3 `backend.api.CommandEncoderBackend` — `R/MetalCommandEncoder.java`
| Method | Metallum impl |
|---|---|
| `TransientMemory transientMemory()` | per-encoder `MetalTransientMemory` (L114-117) |
| `void submit()` | commit cmd buffer, wait for submit N-3, rotate transient + destroy queues (L119-147) |
| `RenderPassBackend createRenderPass(RenderPassDescriptor)` | builds `MetalRenderPass`, folds pending clears into load actions (L270-329) |
| `void submitRenderPass()` | end-of-pass hook: materialise pending clear, pop debug group (L331-338) — encoder NOT ended here (pass merging) |
| `void clearColorTexture(GpuTexture, Vector4fc)` | deferred into `pendingColorClears` (L349-352) |
| `void clearColorAndDepthTextures(GpuTexture, Vector4fc, GpuTexture, double)` | deferred (L354-360) |
| `void clearColorAndDepthTextures(GpuTexture, Vector4fc, GpuTexture, double, int x, int y, int w, int h, int mip)` | deferred if full & mip=1, else clear-quad draw (L362-394) |
| `void clearDepthTexture(GpuTexture, double)` | deferred (L396-399) |
| `void writeToBuffer(GpuBufferSlice dst, ByteBuffer data)` | dynamic buffers: orphan+memcpy; else staging + blit (L401-423) |
| `void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst)` | blit (L457-470) |
| `void writeToTexture(GpuTexture, ByteBuffer, int mip, int depthOrLayer, int x, int y, int w, int h)` | staging + blit (L472-504) |
| `void copyBufferToTexture(GpuBufferSlice, int srcX, int srcY, int srcW, int srcH, GpuTexture, int dstX, int dstY, int copyW, int copyH, int mip, int layer)` | blit (L506-541) |
| `void copyTextureToBuffer(GpuTexture, GpuBuffer, long offset, Runnable callback, int mip)` + 9-arg region variant | blit; callback queued on destroy queue (L543-582) |
| `void copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int dstX, int dstY, int srcX, int srcY, int w, int h)` | blit, slice 0 only (L584-613) |
| `GpuFence createFence()` | `new MetalFence(this, currentSubmitIndex)` (L615-618) |
| `void writeTimestamp(GpuQueryPool, int index)` | CPU `System.nanoTime()` (L685-690) |

### 2.4 `backend.api.RenderPassBackend` — `R/MetalRenderPass.java`
`pushDebugGroup(Supplier<String>)`, `popDebugGroup()`, `setPipeline(BackendRenderPipeline)`, **`setUniform(int index, @Nullable Object value)`**
(value is `GpuBufferSlice` or `util.TextureViewAndSampler`; index = position in pipeline's uniform list — `R/ResourceBindings.java:347-361`),
**`pushConstants(ByteBuffer)`** (new vs 26.2 — Metallum caps at 128 B, L144-151), `enableScissor(x,y,w,h)`, `disableScissor()`,
`setVertexBuffer(int slot, @Nullable GpuBufferSlice)` (multi-stream, `RenderPass.MAX_VERTEX_BUFFERS`), `setIndexBuffer(@Nullable GpuBuffer, IndexType)`,
`drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)`, `multiDrawIndexed(IntBuffer drawParameters /*{firstIndex,indexCount,baseVertex}*3*/, int instanceCount, int firstInstance, int drawCount)`,
`multiDrawIndexed(PointerBuffer firstIndexByteOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount)` (GL-style),
`drawIndexedIndirect(GpuBufferSlice, int drawCount)` (Vk 20-byte command layout), `draw(vertexCount, instanceCount, firstVertex, firstInstance)`,
`multiDraw(IntBuffer {first,count}*2, int instanceCount, int firstInstance, int drawCount)`, `multiDraw(IntBuffer firsts, IntBuffer counts, int drawCount)`,
`drawIndirect(GpuBufferSlice, int drawCount)`, `writeTimestamp(GpuQueryPool, int)`.
Note: there is no viewport setter — viewport = render target size (Metallum sets it at encoder creation, `R/MetalCommandEncoder.java:266`).

`api.commands.RenderPassDescriptor`: `colorAttachments()` → `List<Attachment<Optional<Vector4fc>>>` (entries may be null → MRT with holes),
`depthAttachment()` → `Attachment<OptionalDouble>` (nullable), `renderArea()` → `RenderPass.RenderArea(x,y,w,h)` with `fillsTexture(GpuTextureView)`,
`label()` → `Supplier<String>`. `Attachment` has `textureView()` and `clearValue()` (`R/MetalCommandEncoder.java:270-314`).
→ Clear-on-load is part of the pass descriptor (like Vulkan/WebGPU) [Verified].

### 2.5 `backend.api.BackendRenderPipeline` + `CreateInfo` — `R/MetalCompiledRenderPipeline.java`, `R/MetalCrossShaderCompiler.java`
Interface methods: `boolean isClosed()`, `void close()`.
`CreateInfo` accessors used: `name()`, `shaders()` → list of `Shader` with `module()` → `SpvModule{type(): ShaderType.VERTEX|FRAGMENT, spv(): ByteBuffer}`;
`uniforms()` → `List<BindGroupLayout.UniformDescription>` (`name()`, `type(): UniformType.{UNIFORM_BUFFER, COMBINED_IMAGE_SAMPLER, TEXEL_BUFFER}`, `gpuFormat()` for texel buffers);
`vertexBuffers()` → `VertexBuffer{bufferSlot(), stride(), stepRate()}` (instancing via stepRate>0); `attribBindings()` → `AttribBinding{location(), format(): GpuFormat, offset(), bufferSlot()}`;
`cull()` (boolean — no front/back choice), `polygonMode()` (`PolygonMode.WIREFRAME`/fill), `primitiveTopology()` (`PrimitiveTopology.{TRIANGLES, QUADS, LINES, TRIANGLE_STRIP, TRIANGLE_FAN, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS}`);
`depthStencilState()` (nullable; `depthTest(): CompareOp`, `writeDepth()`, `depthBiasScaleFactor()`, `depthBiasConstant()` — no stencil fields used);
`colorTargetStates()` → `List<ColorTargetState>` (nullable entries; `format()`, `writeMask()` (WRITE_RED/GREEN/BLUE/ALPHA bits), `blendFunction(): Optional<BlendFunction{color(), alpha(): {sourceFactor(), destFactor(), op()}}>`), `ColorTargetState.MAX_COLOR_TARGETS` (≥8 given MSL `colors[8]`).
Enums: `BlendFactor` (15 incl. CONSTANT_*), `BlendOp` (ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX), `CompareOp` (8), `IndexType` (SHORT?/INT).

### 2.6 `backend.api.GpuSurfaceBackend` — `R/MetalSurface.java`
`configure(GpuSurface.Configuration{width(), height(), presentMode()}) throws SurfaceException`, `boolean isSuboptimal()`, `void acquireNextTexture()`,
`void blitFromTexture(CommandEncoderBackend, GpuTextureView)`, `void present()`, `void close()`, `Collection<GpuSurface.PresentMode> supportedPresentModes()`
(`PresentMode.FIFO`, `MAILBOX` at least). → vanilla renders into an offscreen texture and asks the surface to blit it to the swapchain [Verified].

### 2.7 Resource interfaces
- `api.buffers.GpuBuffer` via vanilla abstract `backend.common.BaseGpuBuffer(usage, size)`: implement `isClosed()`, `close()`, `MappedView map(long offset, long length, boolean read, boolean write)`; inherited `slice()`, `slice(off,len)`, `usage()`, `size()`.
  Usage bits: `USAGE_MAP_READ, USAGE_MAP_WRITE, USAGE_HINT_CLIENT_STORAGE, USAGE_COPY_DST, USAGE_COPY_SRC, USAGE_VERTEX, USAGE_INDEX, USAGE_UNIFORM, USAGE_UNIFORM_TEXEL_BUFFER, USAGE_INDIRECT_PARAMETERS` (`R/Stats.java:295-304`).
- `api.buffers.GpuBufferSlice(buffer, offset, length)`; `GpuBufferSlice.MappedView(slice, ByteBuffer data, Runnable onClose)` AutoCloseable.
- `api.buffers.TransientMemory`: `allocateCpu(size, align, minAlloc, elemSize)`, `allocateStaging(...)`, `allocateGpu(...)`, `allocateGpuMapped(...)`, `uploadStaging(List<ByteBuffer>, align, usage, minAlloc, elemSize)`, `uploadGpu(...)`, `multiUploadStaging(List, align, usage)`, `multiUploadGpu(...)` + shorter default overloads (e.g. `uploadStaging(ByteBuffer, align, usage)`, `allocateGpuMapped(size, align, usage)` used at `R/MetalCommandEncoder.java:411`, `R/MetalRenderPass.java:399`). Vanilla provides `backend.util.TransientBlockAllocator<Block>` with `allocate`, `rotate()` (returns Runnable), `canAllocateInCurrentBlock`, `close`, `Allocator.CpuBlock.memalloc()`, `Allocator.create(alloc, free)`.
- `api.textures.GpuTexture` via `backend.common.BaseGpuTexture(usage, label, format, w, h, depthOrLayers, mips)`; usage bits `USAGE_TEXTURE_BINDING, USAGE_COPY_DST, USAGE_COPY_SRC, USAGE_RENDER_ATTACHMENT, USAGE_CUBEMAP_COMPATIBLE`; `getWidth(mip)`, `getHeight(mip)`, `getMipLevels()`, `getFormat()`, `getLabel()`.
- `api.textures.GpuTextureView` via `backend.common.BaseGpuTextureView(texture, baseMip, mipLevels)`.
- `api.textures.GpuSampler`: getters `getAddressModeU/V()`, `getMinFilter/MagFilter()`, `getMaxAnisotropy()`, `getMaxLod()`, `close()`, `isClosed()`. `AddressMode{REPEAT, CLAMP_TO_EDGE}`, `FilterMode{NEAREST, LINEAR}`.
- `api.commands.GpuFence`: `close()`, `boolean awaitCompletion(long timeoutNS)`.
- `api.commands.GpuQueryPool`: `size()`, `OptionalLong getValue(int)`, `OptionalLong[] getValues(int, int)`, `close()`.
- `api.GpuFormat` enum: Metallum maps R8/RG8/RGBA8 (UNORM/SNORM/UINT/SINT), R16/RG16/RGBA16 (UNORM/SNORM/UINT/SINT/FLOAT), R32/RG32/RGBA32 (UINT/SINT/FLOAT), RGB10A2_UNORM/UINT, RG11B10_FLOAT, D16_UNORM, D32_FLOAT, S8_UINT, D24_UNORM_S8_UINT, D32_FLOAT_S8_UINT; enum also has RGB8_*, RGB16_*, RGB32_* (vertex-only mapping) (`R/MetalConversions.java:13-111`). `blockSize()` method exists. No sRGB, BGRA, or compressed formats are referenced anywhere [Verified for Metallum's references; whether the enum has more is Unverified — `default -> throw` covers unknowns].
- `util.ShaderCompileException`, `util.TextureViewAndSampler(view(), sampler())`, `api.vertex.VertexFormat/VertexFormatElement`, `api.pipeline.RenderPipeline.getVertexFormatBindings()` (only used by dead helper `MetalUtilities.vertexAttributeNames`, `R/MetalUtilities.java:407-417`).

---------------------------------------------------------------------------------------------------------------------------
## 3. Backend selection & initialisation

- `PreferredGraphicsApiMixin` (`M/mixin/PreferredGraphicsApiMixin.java:14-22`): `@Inject(HEAD, cancellable)` on
  `PreferredGraphicsApi#getBackendsToTry` → always `{new MetalBackend()}` regardless of enum value; `#caption` → always `"Prefer Metal"`
  for every enum constant. Effect: GL/Vulkan never attempted; failure in `createDevice` is fatal (no fallback). [Verified]
- No OS/arch gate: the old `MetallumMixinConfigPlugin` (`git show d79ffc1^:src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java`) only applied mixins on macOS
  and only when options.txt said `"default"`; deleted in `d79ffc1`. Now on Windows/Linux `ObjC` static init (`MJ/objc/ObjC.java:26`,
  `SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib")`) throws `IllegalArgumentException`, not `BackendCreationException`
  (`R/MetalBackend.java:38-53` only wraps the `MetalDevice` ctor) → crash at startup [Strongly inferred].
- `MetalBackend.createDevice` → `Metal.MTLCreateSystemDefaultDevice()`; device name logged; `new FrontendGpuDevice(new MetalDevice(...))`.
- `MetalDevice` ctor (`R/MetalDevice.java:46-57`): `newCommandQueue` (single queue), `MetalUtilities.init` (static: present PSO,
  2 present samplers, 3 pre-built clear PSOs — compiled from MSL source at startup, `R/MetalUtilities.java:113-122`), the single
  `MetalCommandEncoder`, `DeviceInfo`. `MetalUtilities` is static global state → only one device per process.
- Surface (`R/MetalSurface.java:31-62`): `SDL_Metal_CreateView(window)` → `SDL_Metal_GetLayer` → wrap as `CAMetalLayer`; `setDevice`,
  `framebufferOnly=false`, `opaque=true`, colorspace sRGB or Display-P3 (config `displayP3`, tooltip says P3 = "like before 26.2",
  `M/compat/sodium/MetallumSodiumConfig.java:16-24`). `configure`: `BGRA8Unorm`, drawableSize, `allowsNextDrawableTimeout=false`,
  `presentsWithTransaction=false`, `displaySyncEnabled = presentMode != MAILBOX`. `maximumDrawableCount` not set (CA default 3).

---------------------------------------------------------------------------------------------------------------------------
## 4. Command buffer / encoder lifecycle

- One lazily created `MTLCommandBuffer` (`commandBuffer()`, `R/MetalCommandEncoder.java:69-81`, retained inside an autorelease pool;
  label "Metallum frame N" when labels on). Recreated after each `submit()`.
- At most one live MTL encoder (`currentEncoder`). Any blit/copy/write ends the current render encoder (`blitCommandEncoder()` calls
  `endEncoder()`, L83-94) and the blit encoder is ended immediately after the single copy (`endEncoder()` at L422, 469, 503, 540, 580, 612).
- **Render-encoder reuse across Blaze passes**: `renderCommandEncoder(...)` (L149-179) returns the existing encoder if the attachment
  textures are identical (`sameAttachments` + `sameHandle` depth); clears for a reused encoder are done with a clear-quad draw
  (`MetalUtilities.clearDraw`). `submitRenderPass()` does not end the encoder → consecutive vanilla passes on the same target share
  one MTLRenderCommandEncoder (good for TBDR). [Verified]
- Synchronisation between encoders: **one global `MTLFence`** (L45, L61). Every render encoder `waitForFence(fence, Vertex|Fragment)` at
  begin and `updateFence(fence, Vertex|Fragment)` at end; every blit encoder `waitForFence`/`updateFence` (L91, L98-105, L212). All resources
  are `HazardTrackingModeUntracked` (buffers `R/MetalGpuBuffer.java:43`, textures `R/MetalGpuTexture.java:64`), so this fence chain fully
  serialises encoders (no GPU overlap between passes) [Verified; perf impact Strongly inferred].
- `submit()` (L119-147): `submitRenderPass()`, `endEncoder()`, `drainPermits` on slot semaphore, `addCompletedHandler(block)` (pre-built
  global ObjC block → `Semaphore.release`, L62-66), `commit()`, record `InFlight(index, cb)`, increment index, then **block until submit
  `index-3` completed (5 s timeout → IllegalStateException)**, release old cb, `transientMemory.rotate()`, `destroyQueue.rotate()`.
  The wait is per-`submit()`, not per frame: if vanilla/mods call `submit()` several times per frame, effective frames-in-flight shrinks
  [Strongly inferred].
- `MAX_SUBMITS_IN_FLIGHT = 3` (L35). No `waitUntilCompleted`/`waitUntilScheduled` anywhere in Metallum [Verified by grep].
- `waitForSubmittedGpuWork()` (L673-683): submits pending work then waits on latest submit (used by `close()` and `clearFunctionCache()`).

---------------------------------------------------------------------------------------------------------------------------
## 5. Render passes (load/store, attachments, clears)

- `createRenderPass` (`R/MetalCommandEncoder.java:270-329`) only records state; the MTL encoder is created lazily at first draw
  (`MetalRenderPass.renderEncoder()`, `R/MetalRenderPass.java:351-367`) or at `submitRenderPass` if a clear is pending (L345-349).
- `MTLRenderPassDescriptor` (`R/MetalCommandEncoder.java:219-268`): per color attachment `loadAction = Clear if clearColor else Load`,
  `storeAction = Store` always; depth same with `clearDepth`; `level` param; **never `DontCare`, never memoryless, no stencil attachment,
  no MSAA/resolve**. Viewport = full target size at encoder start (L266).
- Deferred clears: `clear*Texture` calls only fill `pendingColorClears` / `pendingDepthClears` (IdentityHashMap). They are folded into the
  next pass's load action if the pass covers the full texture and the texture has 1 mip (L287-297, L307-312); otherwise
  `flushPendingClear` creates one render encoder per mip level with Clear load action (L697-722). `MetalGpuTexture.recordMaterializedClear`
  / `clearIsRedundant` / `markContentsDirty` skip repeated identical clears (`R/MetalGpuTexture.java:76-93`). Partial-area clears use a
  fullscreen-triangle clear PSO with scissor (`R/MetalUtilities.java:144-197`); clear PSOs are keyed by (color formats, depth format,
  write-bits) and compiled from MSL on demand (L246-258) — runtime hitch on first use of a new format combo.
- `RenderArea` handling: scissor is always set (render area ∩ user scissor, `R/MetalRenderPass.java:561-584`).
- Bug-ish: `flushPendingClear` for a combined color+depth request on the *same* texture object passes the texture as both color and depth
  attachment (L708-717) — only happens if a caller clears color and depth of one texture, which the API doesn't do (Hypothesis, low risk).

---------------------------------------------------------------------------------------------------------------------------
## 6. Pipelines (creation, caching, state)

`MetalDevice.compilePipeline` → `MetalCrossShaderCompiler.compile` (eager) → `Pending` lambda constructs `MetalCompiledRenderPipeline`.

`MetalCompiledRenderPipeline` ctor (`R/MetalCompiledRenderPipeline.java:65-127`):
- Fixed-function state stored on the Java object, applied per draw-state flush (not in PSO): `cullMode = cull ? Back : None`, front
  face hard-coded `MTLWinding.Clockwise` (`R/MetalRenderPass.java:516`, compensates spvc `FLIP_VERTEX_Y`), `fillMode = WIREFRAME ? Lines : Fill`,
  topology via `MetalConversions.primitiveType` (QUADS and LINES map to Triangle — vanilla must pre-index quads/expand lines [Strongly inferred]),
  depth bias constant/slope (clamp 0).
- Depth-stencil: `MTLDepthStencilState` cached in `MetalDevice.depthStencilStates` keyed `(compareFn<<1)|writeDepth` (`R/MetalDevice.java:192-205`);
  null `depthStencilState` → Always/no-write. **No stencil support at all.**
- `MTLRenderPipelineDescriptor` (L129-173): vertex+fragment `MTLFunction`, `MTLVertexDescriptor` (L271-300: per buffer slot stride,
  PerVertex/PerInstance+stepRate; per attribute format/offset/bufferIndex = firstVertexBufferSlot+bufferSlot; unsupported format → throw),
  **depth attachment format hard-coded `Depth32Float`** (L147), per color target: pixel format from `ColorTargetState.format()` (or
  RGBA8Unorm if target 0 is null), write mask, blending enable + RGB/alpha factors and ops. Not handled: blend constant color
  (`setBlendColor` never called → CONSTANT_* factors use 0), alpha-to-coverage, sampleCount, logic ops, stencil format.
- **PSO variant cache**: `pipelinesByLayout: Int2ObjectOpenHashMap<MTLRenderPipelineState>` keyed by a *presence bitmask* (bit i = color
  attachment i present, bit MAX_COLOR_TARGETS = depth present). Variant = copy of descriptor with missing attachments set to `Invalid`
  (L175-195, L240-249). The full layout PSO is built eagerly; others lazily at draw time (synchronous `newRenderPipelineStateWithDescriptor:error:`).
  The key does **not** include actual attachment pixel formats → a pipeline used with a render target whose format differs from its
  declared `ColorTargetState.format()`, or a depth texture that isn't D32_FLOAT (D16/D24S8/D32S8), will produce a Metal validation error /
  undefined behaviour [Strongly inferred; Verified that formats are not part of the key].
- `isValid()` = full-layout PSO non-null; invalid → `Pending` returns null (`R/MetalDevice.java:139-146`).
- MTLFunction cache: `MetalDevice.functionCache: HashMap<MslFunctionKey(msl, entryPoint), MTLFunction>` (L43, L215-235) — keyed by the
  *entire MSL source string* (dedups identical shader stages across pipelines; hash cost on every lookup is O(source length)). One
  `MTLLibrary` per unique source via `newLibraryWithSource(source, null)` (default `MTLCompileOptions` → default language version,
  fast-math default) (`R/MetalUtilities.java:426-434`). **No disk cache, no `MTLBinaryArchive`, no async completion-handler compile.**
- Thread safety: `functionCache` and `depthStencilStates` are plain `HashMap`s. If vanilla resolves `Pending` off the render thread
  concurrently, races are possible [Hypothesis — vanilla threading model Unverified].

---------------------------------------------------------------------------------------------------------------------------
## 7. Shader compilation: SPIR-V → MSL (`R/MetalCrossShaderCompiler.java`)

- Input: SPIR-V from vanilla (`SpvModule.spv()`); vanilla therefore owns GLSL→SPIR-V (likely shaderc/glslang via LWJGL) [Strongly inferred].
- Tool: **SPIRV-Cross C API through LWJGL `org.lwjgl.util.spvc`** (`Spvc.*`), one `spvc_context` per stage per compile (L195-279).
- Options (L218-247): `MSL_PLATFORM = MACOS`, **`MSL_VERSION = 4.0` (0x040000)**, `MSL_ENABLE_DECORATION_BINDING = true`,
  `MSL_TEXTURE_BUFFER_NATIVE = true` (texel buffers → `texture_buffer`), `FLIP_VERTEX_Y = true`, `MSL_ENABLE_FRAG_DEPTH_BUILTIN = false`
  when the pipeline has no depth state. MSL 4.0 output targets the macOS 26 (Metal 4) language; `newLibraryWithSource` with nil options
  uses the OS default version, so on macOS < 26 compile errors are plausible [Hypothesis — depends on whether spvc emits 4.0-only constructs].
- Integer vertex inputs: for attributes whose `GpuFormat` name ends with `_UINT` and contains "8"/"16", registers
  `spvc_compiler_msl_add_shader_input_2` with UINT8/UINT16 format (L143-193) — string-matching on enum names.
- Binding remap (`MetalIndices.of`, L315-325): uniforms are numbered by their **position in `CreateInfo.uniforms()`**; uniform buffers get
  Metal buffer indices 0..nUBO-1, all non-UBO (combined image samplers **and texel buffers**) get texture/sampler indices 0..nTex-1
  (same index for texture and sampler). Push-constant block → Metal buffer index nUBO (L269, L113). Remap assumes SPIR-V
  `Binding` decoration == uniform list index (L297-309; bindings ≥ list size silently skipped).
- Reflection: `activeResources` = names of active UBO/sampled image/separate image/separate sampler per stage (L330-363); used to compute
  per-binding stage mask (vertex/fragment/both; fallback both if not found, L116-133). Entry point names extracted by regex on MSL
  (`vertex <type> (\w+)(`, fallback "main0") (L37-38, L81-84).
- Vertex buffers start at Metal buffer index `max(vertex-stage UBO index, push-constant index) + 1` (`R/MetalCompiledRenderPipeline.java:302-313`).
  With Metal's 31 buffer-slot limit, nUBO + vertex streams > 31 would fail (not checked) [Verified no check].
- No GLSL source handling, no shader preprocessing, no Iris-style transformation in Metallum.

---------------------------------------------------------------------------------------------------------------------------
## 8. Resource binding model

- No argument buffers / no bindless / no heaps. Direct per-slot binding in `MetalRenderPass.bindDrawState` (`R/MetalRenderPass.java:494-548`):
  - `pipelineDirty` → `setRenderPipelineState(getNativePipeline(layout))`, depth-stencil state + depth bias (only if pass has depth),
    winding, cull, fill; mark all resources dirty; push constants dirty.
  - `scissorDirty` → `setScissorRect`.
  - `vertexBuffersDirty` → `setVertexBuffer(buf, offset, firstSlot+slot)` for each slot < `vertexBufferCount` (L376-392).
  - `ResourceBindings` 64-bit dirty mask (`R/ResourceBindings.java`) → per dirty binding: UBO → `setVertexBuffer`/`setFragmentBuffer`
    per stage mask; sampled image → `setVertexTexture+setVertexSamplerState` / fragment equivalents; texel buffer →
    **creates a new `MTLTexture` buffer view (`MTLBuffer.newTexture`) on every bind** and queues it for deferred release
    (L627-661, `R/MetalUtilities.java:369-399`).
  - push constants → `setVertexBytes` / `setFragmentBytes` (≤4 KB inline) (L586-598).
- `setPipeline` with a different pipeline calls `bindings.reset()` (clears all uniform values, L125-133) → callers must re-set uniforms after
  every pipeline switch (consistent with vanilla usage [Strongly inferred]).
- `setUniform` with a texture flushes any pending clear on that texture (L136-141).
- Redundant-state filtering: pipeline identity, scissor value, vertex slice equality, per-binding dirty bits. Not filtered: identical
  UBO slice re-set (always dirties), winding/cull/fill re-sent on every pipeline change.

---------------------------------------------------------------------------------------------------------------------------
## 9. Buffers, transient memory, uploads

`MetalGpuBuffer` (`R/MetalGpuBuffer.java`):
- `dynamic = (usage & UNIFORM) && (usage & COPY_DST)` (L184-186). `cpuAccessible = INDEX || MAP_READ || MAP_WRITE || HINT_CLIENT_STORAGE || dynamic`
  (L177-182). Storage `Shared` if CPU-accessible else `Private`; always `Untracked`; size rounded to 16 B. CPU view =
  `ByteBuffer` over `contents()` (persistent mapping; `map()` just slices it, unmap no-op, L154-171). No `didModifyRange` (fine on Apple Silicon UMA).
- **Index buffers are always Shared** (so the CPU triangle-fan expansion can read them, `R/MetalRenderPass.java:448-456`). Consequence:
  Sodium's geometry arenas (`USAGE_INDEX|USAGE_VERTEX|COPY_DST|COPY_SRC`, `sodium/.../gpu/arena/ArenaAggregator.java:396`) are Shared too.
- `writeToBuffer` (`R/MetalCommandEncoder.java:401-455`):
  - dynamic (uniform+copy_dst) buffers: **orphaning** — grab a same-size MTLBuffer from `dynamicBackingPool` (keyed by size) or allocate,
    memcpy the *entire previous contents* if the write is partial, write new data, swap backing, recycle old backing after 3 submits.
    For large uniform arenas with many small writes per frame this is O(writes × buffer size) memcpy and many backing buffers alive
    [Verified mechanism; magnitude Hypothesis]. Also: already-bound encoder slots keep pointing at the old MTLBuffer until the binding is
    re-dirtied — a write between two draws without a `setUniform` would be invisible to the second draw [Hypothesis on reachability].
  - others: copy into transient staging (`uploadStaging`) then a dedicated blit encoder (breaks any open render encoder).
- `createBuffer(..., data)`: CPU write if Shared, else staging blit (`R/MetalDevice.java:104-113`).

`MetalTransientMemory` (`R/MetalTransientMemory.java`): wraps vanilla `TransientBlockAllocator` with 512 KiB blocks. CPU allocator =
`CpuBlock.memalloc()`; GPU allocator blocks = `MetalGpuBuffer(MAP_READ|MAP_WRITE)` → **all "GPU" transient memory is Shared**
(`allocateGpu` == mapped). `rotate()` per submit; GPU block release deferred via destroy queue. Each allocation creates a new
`TransientGpuBuffer` wrapper object (L288-290, L372-411) — a Java allocation per transient slice (per draw for immediate-mode geometry/uniforms).
`TransientGpuBuffer.isClosed()` becomes true after its submit index passes; `slice()`/`map()` throw.

Deferred destruction: `MetalDestructionQueue(3)` (`R/MetalDestructionQueue.java`) — ring of lists, `rotate()` runs the slot 3 submits old,
after `submit()` has waited for submit N-3 → safe [Verified by index arithmetic]. Used for buffer/texture/sampler/fence release,
texel views, dynamic backing recycling, and `copyTextureToBuffer` callbacks (callback fires ~3 submits later, not on GPU completion).

---------------------------------------------------------------------------------------------------------------------------
## 10. Textures & samplers

`MetalGpuTexture` (`R/MetalGpuTexture.java:29-70`): type = Cube / CubeArray (if `USAGE_CUBEMAP_COMPATIBLE`), 2DArray (layers>1), else 2D;
**no 3D textures**; storage Private, Untracked; usage: ShaderRead (+RenderTarget if render attachment); never ShaderWrite /
PixelFormatView. View refcount (`addView/removeView`) defers native release until all views closed.
`MetalGpuTextureView` (`R/MetalGpuTextureView.java`): full-range → retains parent MTLTexture; else
`newTextureViewWithPixelFormat(same format, same type, mip range, all slices)`. `close()` forces creation of the view just to release it (L216).

Formats (`R/MetalConversions.java:13-61`): unsupported → `IllegalStateException("Unsupported Metal texel buffer format")` (message wrong for
textures). Not mapped: RGB8_*, RGB16_*, RGB32_* (no 3-channel Metal pixel formats — correct), and anything else in the enum. Mapped but
problematic: **`D24_UNORM_S8_UINT → Depth24Unorm_Stencil8`, which Apple Silicon GPUs do not support** (`MTLDevice.depth24Stencil8PixelFormatSupported`
is false on Apple GPUs) → texture creation would fail/nil → `nonNil` throws [Strongly inferred]. `S8_UINT → Stencil8` ok.
Vertex formats (L63-111) cover 1–4 component 8/16/32-bit int/norm/float/half.

Uploads: `writeToTexture` uses `blockSize()` × width as row pitch, single layer/mip (L472-504); `copyBufferToTexture` handles sub-rect source
offset (L506-541); `copyTextureToTexture` ignores array layers (slice 0) (L584-613); `copyTextureToBuffer` slice 0 only.

`MetalGpuSampler` (`R/MetalGpuSampler.java:254-282`): min/mag from API; **mip filter Linear unless maxLod ≤ 0.25 then Nearest** — i.e. no
nearest-mip option; lodMaxClamp = max(0.25, maxLod or 1000); anisotropy `max(1, n)`; S/T address modes only (R default). One
`MTLSamplerState` per `createSampler` call (no dedup cache in Metallum; vanilla may cache [Unverified]).

---------------------------------------------------------------------------------------------------------------------------
## 11. Synchronisation, fences, queries

- `MetalFence` (`R/MetalFence.java`): captures `currentSubmitIndex` (the *not yet submitted* batch). `awaitCompletion(ns)` converts to ms
  (`ns/1_000_000`, so <1 ms → 0) and calls `awaitSubmitCompletion`. Waiting with timeout>0 on a fence of the current (unsubmitted) batch
  **throws `IllegalStateException("Cannot wait on a fence for the current submit")`** instead of flushing (`R/MetalCommandEncoder.java:624-630`).
  `close()` makes subsequent awaits return true. Slot-reuse semantics: if the slot was overwritten by a newer submit, returns true (correct
  because submit() already waited).
- CPU↔GPU: Java `Semaphore` per slot released by an ObjC global block completion handler (upcall from a Metal-owned thread).
- `MetalGpuQueryPool` (`R/MetalGpuQueryPool.java`) + `writeTimestamp` (`R/MetalCommandEncoder.java:685-690`, `R/MetalRenderPass.java:331-336`):
  **CPU `System.nanoTime()` at encode time**, `getTimestampCalibrationOffset()=0` → GPU profiler/timing values are meaningless. No
  `MTLCounterSampleBuffer` usage.

---------------------------------------------------------------------------------------------------------------------------
## 12. Presentation

- `acquireNextTexture()` no-op; `isSuboptimal()` false.
- `blitFromTexture` → `MetalCommandEncoder.presentTextureToDrawable` (`R/MetalCommandEncoder.java:340-347`) → `MetalUtilities.encodePresentTextureToDrawable`
  (`R/MetalUtilities.java:199-243`): `layer.nextDrawable()` (blocking; `allowsNextDrawableTimeout=false` → can block indefinitely),
  render pass on drawable (DontCare/Store), `waitForFence(Fragment)`, fullscreen triangle sampling the offscreen texture with Y-flipped UVs
  (nearest sampler if same size, linear if scaled), `updateFence`, `presentDrawable`. Drawable acquired as late as possible (good).
- `present()` → `pendingPresentEncoder.submit()` (`R/MetalSurface.java:83-86`) — NPE if `present()` precedes any `blitFromTexture`.
- VSync: FIFO → `displaySyncEnabled=true`; MAILBOX → `displaySyncEnabled=false` (that is "immediate/tearing", not true mailbox).
- Iconified state ignored → `nextDrawable` while minimised/occluded may stall [Hypothesis].
- sRGB: swapchain `BGRA8Unorm` (non-sRGB) + layer colorspace tag; implies vanilla renders gamma-encoded values into UNORM targets [Strongly inferred].

---------------------------------------------------------------------------------------------------------------------------
## 13. Draw paths & Sodium interaction

`MetalRenderPass` draws (`R/MetalRenderPass.java:199-329`):
- `drawIndexed` → `drawIndexedPrimitives(..., baseVertex, baseInstance)`; triangle-fan pipelines expand indices **on the CPU, reading the
  index buffer's `contents()`** into a transient UInt32 index buffer (L420-456) — would crash on a Private index buffer (never happens since
  INDEX ⇒ Shared).
- `multiDrawIndexed(IntBuffer{firstIndex,indexCount,baseVertex}, inst, firstInst, n)` → n × `drawIndexedPrimitives` (L208-223). Layout matches
  `VkMultiDrawIndexedInfoEXT` (Sodium VK_MULTIDRAW path).
- `multiDrawIndexed(PointerBuffer byteOffsets, IntBuffer counts, IntBuffer baseVertices, n)` → n × `drawIndexedPrimitives(…, offset, 1, baseVertex, 0)` (L225-249).
- `drawIndexedIndirect/drawIndirect` → **drawCount separate indirect draws** stepping by `VkDrawIndexedIndirectCommand.SIZEOF` (20) /
  `VkDrawIndirectCommand.SIZEOF` (16) (L251-318). Layout-compatible with `MTLDraw(Indexed)PrimitivesIndirectArguments`. No ICB, no
  GPU-driven multi-draw.
- `multiDraw*` → loops of `drawPrimitives`; triangle fan non-indexed generates indices on CPU (L394-418). Fans in indirect/PointerBuffer
  multidraw → `UnsupportedOperationException`.

**Sodium 0.9.2 on Metallum [Verified call path]:**
- `DrawBackend.chooseBackend()` (`sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java:15-29`): backend
  `instanceof VulkanDevice` → VK_MULTIDRAW/VK_INDIRECT; else **OPENGL**. Metallum's backend → OPENGL.
- `GLDrawContext` just stores the `RenderPass` (`.../gpu/device/context/GLDrawContext.java:6-26`); `GLDrawBatch.draw` calls
  `pass.multiDrawIndexed(PointerBuffer elementPointers (byte offsets = elementOffset<<2), IntBuffer counts, IntBuffer baseVertex, size)`
  (`.../gpu/device/batch/GLDrawBatch.java:33-38`). So "OPENGL" here means "GL-style multidraw arguments", not raw GL calls. Sodium has no raw
  GL draw code on 26.3 — the only `org.lwjgl.opengl` users are `GlContextInfo`, `NvidiaWorkarounds`, `GlSurfaceMixin`, `GlStateManagerMixin`,
  and the no-error-context option in `SodiumConfigBuilder.java:691-697`, all gated on `backendName().contains("OpenGL")` or GL-specific mixin targets.
- Sodium's chunk shaders are compiled through vanilla RenderPipelines → SPIR-V → Metallum's spvc path [Strongly inferred].
- Sodium uploads: `MojangStagingBuffer` uses `MappedStagingBuffer` because Metallum claims `persistentMapping()=true`; each consolidated
  copy → `copyToBuffer` → **one blit encoder per copy**, each with fence wait/update, and each breaking the render encoder if one is open
  (`sodium/.../gpu/arena/staging/MappedStagingBuffer.java:76-93`, `R/MetalCommandEncoder.java:457-470`). Fences: `awaitCompletion(0)` in
  `flip()` works; `delete()` uses `awaitCompletion(1000)` (=1 µs → 0 ms) safe.
- Sodium section-time texel buffer (`USAGE_UNIFORM_TEXEL_BUFFER|MAP_WRITE`, `.../render/chunk/UniformBufferManager.java:51-53`) → Metallum
  creates a new MTLTexture buffer view each time it is bound (per region/pipeline change).
- Sodium terrain uniform storage (`DynamicGpuDataStorageMapped`, `USAGE_COPY_DST|USAGE_UNIFORM`, L49) → Metallum treats it as "dynamic"
  → orphan-on-write path (vanilla class internals Unverified).
- 26.2 history: Metallum previously forced Sodium to `VK_INDIRECT` with a custom `MetalDrawContext` (removed in `4da72f5`); now the generic
  path needs no Sodium mixins beyond the label fix.
- `SodiumPreferredGraphicsApiMixin` (`M/compat/sodium/mixin/SodiumPreferredGraphicsApiMixin.java:14-28`): redirects
  `EnumOption.getElementName` in `CyclingControl$CyclingControlElement#extractRenderState`; for any `PreferredGraphicsApi` element returns
  `PreferredGraphicsApi.DEFAULT.caption()` → combined with the caption mixin, **every value (Default/OpenGL/Vulkan) is displayed as "Prefer
  Metal"** — cosmetic bug; and choosing any value has no effect anyway [Verified].
- `MetallumSodiumConfig` registers a Sodium options page "Display" with the Display-P3 toggle (requires restart) via entrypoint
  `sodium:config_api_user` (`fabric.mod.json:21-23`). Sodium is a compile-time `implementation` dependency but optional at runtime (classes
  only touched from the entrypoint and a `@Pseudo` mixin) [Strongly inferred].
- README "Compatible with Sodium" (`metallum/README.md:6`): consistent with code — Sodium works through the generic RenderPass path,
  but with the per-draw loop cost (no native multidraw/ICB).

---------------------------------------------------------------------------------------------------------------------------
## 14. Other compat mixins

- **Distant Horizons** (`M/compat/distanthorizons/mixin/MinecraftRenderWrapperMixin.java`): `@Pseudo` on
  `com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper#getMcRenderingApi`, redirecting every
  `String.equalsIgnoreCase` so that backendName "Metal" also matches (method named `treatMetalLikeVulkan`) → DH believes it is on the
  Vulkan/renderpearl path and presumably renders via the renderpearl API instead of raw GL [Strongly inferred intent; DH source not available → behaviour Unverified].
  Caveat: the redirect hits *all* `equalsIgnoreCase` calls in that method, so "Metal" would match whichever string is compared first (could
  be "OpenGL") [Hypothesis].
- **"Shine"** (`M/compat/shine/mixin/ShineRenderBackendMixin.java`): `@Pseudo` on `com.bloom.client.render.ShineRenderBackend#identify`,
  redirects `DeviceInfo.backendName()` so "Metal" reports as "Vulkan". Mod identity unknown (package `com.bloom`, not in local research
  checkouts) — likely a shader/post-processing mod with a Vulkan (renderpearl) path [Hypothesis].
- Both mixin JSONs are `required: true` with `@Pseudo` targets; missing target classes are tolerated by `@Pseudo` [Verified annotation present].
- **Iris**: not referenced anywhere in Metallum (grep "iris" → none). Iris 26.3 (`Iris/common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java:20-73`)
  decides `usingVulkan` by reading options.txt `preferredGraphicsBackend` *containing "vulkan"*; otherwise it applies all GL mixins, which
  cast the backend to `GlDevice` (`Iris/.../iris/gl/IrisRenderSystem.java:579-580`, `Iris/.../mixin/MixinShaderManager_Overrides.java:85`,
  `Iris/.../mixin/MixinRenderTarget.java:62`). With Metallum forcing Metal while options say "default"/"opengl" → ClassCastException at
  first use [Strongly inferred]. If the user sets "vulkan" in options, Iris enters VK-only mode (no shaders, placeholder screen) and
  Metallum still runs Metal → Iris inert but probably non-crashing [Strongly inferred].

---------------------------------------------------------------------------------------------------------------------------
## 15. metal-jvm (binding library)

- Approach: **Java 25 FFM (Panama) only**; `README.md` "thin wrappers over the Objective-C API ... in the spirit of metal-cpp";
  requires `--enable-native-access=ALL-UNNAMED`. No JNI, no `.m/.mm/.swift/.c` files, no native build, no bundled dylibs
  (`find metal-jvm -type f` shows only Java + gradle) [Verified]. Single "Initial commit" (2026-10-09). Built/published to Maven Central
  (`io.github.kokodio:metal-jvm:0.1.0`) with vanniktech plugin; only dependency `jspecify` compileOnlyApi. Automatic-Module-Name
  `io.github.kokodio.metaljvm`.
- Runtime core (`MJ/objc/`):
  - `ObjC.java`: `libobjc.A.dylib` lookup, `objc_msgSend` symbol; `msgSend(desc)` → normal downcall (for blocking/re-entrant calls),
    `msgSendCritical(desc)` → `Linker.Option.critical(false)` downcall (no thread-state transition, no heap access) (L89-97). Selector cache
    `ConcurrentHashMap`; `clazz`, `framework` (loads `/System/Library/Frameworks/X.framework/X`), `loadSymbol` for global constants,
    `nsString` (+1 retained), `javaString`, `byteBufferView`, autorelease pool push/pop.
  - `ObjCBlock.java`: hand-built **global** ObjC block (`_NSConcreteGlobalBlock`, `BLOCK_IS_GLOBAL`) whose invoke is an FFM upcall stub to a
    `Runnable`; allocated in `Arena.global()` (never freed — fine for the 3 pre-built completion blocks).
  - `NativeStack.java`: thread-local 64 KiB bump allocator for call-scoped structs/strings.
  - `AutoreleasePool.java`: try-with-resources wrapper.
- Generated-looking per-class wrappers (406 Metal, 46 Foundation, 33 AppKit, 18 MetalFX, 7 QuartzCore, 6 CoreGraphics classes): each
  selector cached as `static final long SEL_*` + `static final MethodHandle MH_*`; every call `invokeExact`s with `(handle, sel, args...)`.
  arm64 AAPCS is encoded by hand: HFAs passed as scalars (e.g. `setClearColor:` as 4 doubles,
  `MJ/metal/MTLRenderPassColorAttachmentDescriptor.java:24-25,66-72`), >16-byte non-HFA structs passed as a pointer to a NativeStack copy
  (`setViewport:`, `setScissorRect:`, `MJ/metal/MTLRenderCommandEncoder.java:471-477, 543-549`). → arm64-only by construction [Verified].
  Blocking calls (`commit`, `waitUntilCompleted`, `nextDrawable`, `newLibraryWithSource`, `newRenderPipelineState...`) use the non-critical
  variant (`MJ/metal/MTLCommandBuffer.java:23-39`, `MJ/quartzcore/CAMetalLayer.java:31-32`, `MJ/metal/MTLDevice.java:69-96`).
- API coverage: classic Metal (device, queue, command buffer, render/blit/compute encoders, heaps, argument encoders, ICBs, acceleration
  structures, binary archives domain, IO), **Metal 4 (`MTL4*` classes incl. `MTL4Compiler`, `MTL4CommandBuffer`, `MTL4RenderCommandEncoder`,
  `MTL4ArgumentTable`-style descriptors)**, MetalFX (spatial/temporal scalers, frame interpolator, denoised scaler), CAMetalLayer/CALayer,
  AppKit NSWindow/NSView/NSResponder, Foundation collections. Metallum uses only the classic subset (grep for MTL4/MTLFX/MTLHeap/ICB/
  argument encoder/binary archive/shared event/counter sample in `metallum/src` → none).
- Errors: `NSError**` out-params → `NSErrorException.of(selector, err)` (e.g. `MJ/metal/MTLDevice.java:638-653`); Metallum catches it in
  `MetalUtilities.newLibrary/newRenderPipelineState` and logs.
- Per-call cost: no JNI transition; critical downcalls ≈ a C call + `objc_msgSend`. Allocation: returned objects wrapped in new Java objects
  (e.g. `new CAMetalDrawable(result)`), struct args materialised via records → small per-call garbage (`new MTLScissorRect`, `new MTLViewport`
  per scissor/viewport set in Metallum) [Verified pattern].
- Packaging into Metallum: `include(implementation(...))` → Fabric jar-in-jar of the pure-Java library; no native extraction needed. Native
  dependencies at runtime: system frameworks + Minecraft's LWJGL (SDL, spvc, vulkan structs used only for SIZEOF constants).

---------------------------------------------------------------------------------------------------------------------------
## 16. Bugs / incomplete features / TODOs (Verified in code unless labelled)

1. No OS/arch guard; no fallback backend; user API selection ignored (`M/mixin/PreferredGraphicsApiMixin.java:14-17`).
2. Pipeline depth format hard-coded `Depth32Float`; PSO variant key ignores actual attachment formats (`R/MetalCompiledRenderPipeline.java:147, 240-249`).
3. `D24_UNORM_S8_UINT → Depth24Unorm_Stencil8` unsupported on Apple GPUs (Strongly inferred failure).
4. No stencil (state or attachment), no MSAA, no blend constant, no 3D textures, no ShaderWrite/compute, no depth clamp.
5. `writeTimestamp` uses CPU time; calibration offset 0 → GPU timings bogus.
6. `MetalFence.awaitCompletion` on current batch with timeout ≥1 ms throws instead of flushing (`R/MetalCommandEncoder.java:625-630`).
7. `MetalSurface.present()` NPE if no prior `blitFromTexture`; `isIconified` ignored; `MAILBOX` mapped to non-vsync immediate.
8. `pushConstants` fixed 128-byte buffer; larger payload → `IndexOutOfBoundsException` (`R/MetalRenderPass.java:144-151`).
9. `copyTextureToTexture`/`copyTextureToBuffer` ignore array layers / slice; `writeToTexture` ignores row-length/stride of source.
10. Texel-buffer view: `bytesPerRow` aligned up may exceed buffer range checked earlier (`R/MetalUtilities.java:383-397`); new MTLTexture per bind.
11. Triangle-fan support only for direct draws; CPU index expansion reads GPU-visible memory directly.
12. `SodiumPreferredGraphicsApiMixin` shows "Prefer Metal" for every enum value.
13. DH redirect catches all `equalsIgnoreCase` calls in target method (Hypothesis).
14. `Stats` class and `MetalUtilities.vertexAttributeNames` dead code; `//todo` on `pushDebugGroup`/`popDebugGroup` (`R/MetalRenderPass.java:103, 112`) — debug groups pushed on the *command buffer*, not the encoder.
15. `getLastDebugMessages()` empty; no Metal validation/log-state integration even when `useValidationLayers()`.
16. `MetalUtilities` static singleton state; `HashMap` caches not thread-safe (Hypothesis about concurrent use).
17. `Unsupported Metal texel buffer format` error message used for all texture formats (misleading).
18. Sampler mip filter cannot be Nearest unless maxLod ≤ 0.25.

## 17. Performance bottlenecks

- **Encoder churn on TBDR**: every buffer write (non-dynamic), copy, texture upload, and non-foldable clear ends the current render encoder
  and starts a blit encoder; the next draw starts a new render encoder with `Load` + `Store` on all attachments → extra tile load/store
  bandwidth. Uploads are not batched into one blit encoder per frame.
- **Single global MTLFence** serialising all encoders, plus all-Untracked resources → no inter-pass GPU overlap (vertex of pass N+1 waits
  for fragment of pass N even when independent).
- Always `StoreAction.Store` (depth is stored even when never read again); no memoryless depth.
- Multidraw / indirect implemented as CPU loops of individual draw calls (Sodium terrain = one `drawIndexedPrimitives` per section per
  pass) — no ICB/`drawIndexedPrimitives` batching, though each call is a cheap critical FFM downcall.
- Per-draw Java garbage: `TransientGpuBuffer` wrapper per transient allocation, `GpuBufferSlice`/`MappedView` objects, `MTLScissorRect`
  records; texel-buffer `MTLTexture` per bind.
- Orphaning memcpy of entire dynamic uniform buffers on partial writes.
- Synchronous pipeline-state creation on first use of a new attachment layout (draw-time hitch); MSL compile at runtime; no
  `MTLBinaryArchive`/disk cache → every launch recompiles all shaders.
- Function cache keyed by full MSL source string (hashing/equals cost on every pipeline build).
- `submit()` CPU wait for N-3 per submit (fine if 1 submit/frame).

## 18. Design strengths (worth reusing ideas)

- Clean mapping onto vanilla's 26.3 backend interfaces; tiny codebase (~4.2k LOC) proves the renderpearl backend API is implementable by a mod
  without touching vanilla rendering code.
- Deferred clears folded into load actions + redundant-clear elimination; render-encoder reuse across consecutive passes with the same
  attachments.
- Dirty-bit state tracking (pipeline/scissor/vertex buffers/64-bit binding mask); per-binding stage masks from spvc reflection to avoid
  binding unused stages.
- PSO variants per attachment-presence layout (handles MRT holes); depth-stencil state cache.
- Sound deferred-destruction ring tied to submit completion; semaphore-based frames-in-flight without `waitUntilCompleted`.
- Late drawable acquisition; present via shader blit (supports scaling).
- metal-jvm: zero-native-build FFM binding with critical downcalls and correct arm64 struct ABI handling; broad API surface including
  Metal 4 and MetalFX available for future work.

---------------------------------------------------------------------------------------------------------------------------
## Open questions / not verified

- Exact semantics/order of `DeviceInfo`, `DeviceLimits`, `DeviceFeatures`, `HintsAndWorkarounds` record components (vanilla source unavailable).
- Whether vanilla resolves `BackendRenderPipeline.Pending` on the render thread or a worker thread (thread safety of Metallum caches).
- Whether vanilla calls `CommandEncoderBackend.submit()` more than once per frame (affects frames-in-flight and transient rotation).
- Which depth format vanilla 26.3 uses for main/auxiliary targets (D32_FLOAT assumed by Metallum) and whether any vanilla path uses stencil or D24S8.
- Whether SPIRV-Cross MSL 4.0 output compiles on macOS < 26 (Tahoe) via `newLibraryWithSource` with nil options.
- How vanilla's GLSL→SPIR-V step works (shaderc vs glslang; LWJGL module) and whether it's shared across GL/Vulkan backends.
- Distant Horizons' `getMcRenderingApi` body and what DH does in "Vulkan" mode; identity of the "Shine" (`com.bloom`) mod.
- Iris crash path with Metallum not executed (inferred from code only).
- `DynamicGpuDataStorageMapped` (vanilla) write pattern → actual cost of Metallum's orphaning path.
- Runtime behaviour on real hardware (nothing was built or run; macOS unavailable).
