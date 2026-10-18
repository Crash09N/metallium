# metallium-iris: implementation blueprint

Goal: run Iris 1.11.6 (MC 26.3, `origin/26.3` @ `671a5c877`) on Metallium's renderpearl Metal backend with no GL context.
Inputs: `docs/research/notes/iris-arch.md`, `iris-shaders-translation.md`, report section D, Iris source, vanilla 26.3 decompiled
source (`.../scratchpad/mc/src/com/mojang/renderpearl`), Metallium source (`src/main/java/dev/metallium/backend`).
Labels: **[V]** verified in code read for this document, **[SI]** strongly inferred, **[H]** hypothesis / needs a spike.
Paths: `I/` = `Iris/common/src/main/java/net/irisshaders/iris/`; `V/` = `com/mojang/renderpearl/` (vanilla); `M/` = `src/main/java/dev/metallium/backend/`.
Licence note: everything here lives in a separate LGPL-3.0 module `metallium-iris` (CLAUDE.md credits rule); no Sodium/MetalRender code.

---------------------------------------------------------------------------------------------------

## 0. Findings that shape the whole design

1. **The GL surface is narrow and concentrated.** Raw LWJGL GL function calls in Iris: only 19 files, of which `IrisRenderSystem` 98,
   `GLDebug` 34, DH 45 (skipped), and <=6 each elsewhere (`FallbackShader` 6, `IrisBindings` 4, `ExtendedShader` 3, others 1-2) [V, grep
   `GLxx.glFoo(` per file]. Everything else reaches GL through two static facades: `IrisRenderSystem` (68 distinct public statics, all taking
   GL `int` names/enums) and vanilla `GlStateManager` (50 distinct methods used by Iris; list in 3.2). Distinct raw LWJGL entry points: ~110 [V].
2. **Iris never uses GL objects as objects, only as `int` names** (`GlResource.getGlId()`, `iris$getGlId()`, program ids, FBO ids). A GL-name
   handle space (`GlNames`) owned by Metallium therefore lets unchanged Iris classes keep working; that is the seam recommended in section 4.
3. **The pass model is the real problem, not the GL calls.** Iris draws gbuffers inside vanilla/Sodium `RenderPass`es created on the
   main target, then re-binds its own FBO inside `GlRenderPipeline.bind` (`I/mixin/MixinGlRenderPipeline.java:277-283` -> `ExtendedShader.iris$setupState`
   `I/pipeline/programs/ExtendedShader.java:255-259`). The frontend validates the pass against the *vanilla* colour-target list (`V/frontend/FrontendRenderPass.java:105-123`
   requires `colorTargetStates.size() == colorAttachments.size()` and equal formats); Iris keeps `old2.colorTargetStates()` in its replacement
   `FrontendRenderPipeline` (`I/mixin/MixinShaderManager_Overrides.java:98-99`) so that check passes and the lie lives only in the backend.
   Metal backend must therefore be able to **retarget an open pass at draw time** (section 2, H1).
4. **Metallium's backend is already shaped for this.** `MetalCommandEncoder.renderCommandEncoder(...)` reuses `currentEncoder` when the attachment
   set is unchanged and otherwise ends it and opens a new one (`M/MetalCommandEncoder.java:154-196`); `MetalRenderPass` fixes colour/depth in its constructor
   (`M/MetalRenderPass.java:39-93`, final fields) and `MetalCompiledRenderPipeline` builds per-"layout" PSO variants (`M/MetalCompiledRenderPipeline.java:~120-200`, hard-codes
   `Depth32Float`, colour formats from `CreateInfo.colorTargetStates`). Needed change: make the attachment set mutable per draw and key PSOs by the *actual* attachment formats.
5. **Metallium's backend selection never touches the options string** (`PreferredGraphicsApiMixin` returns `{new MetalBackend()}` unconditionally,
   `metallum`-derived; our copy `dev/metallium/mixin/PreferredGraphicsApiMixin.java`), so `options.txt` keeps `preferredGraphicsBackend:"default"|"opengl"`,
   `IrisMixinPlugin.usingVulkan` is `false` and **every Iris mixin applies on Metal** (section 3). The gate is irrelevant; the problem is runtime behaviour of the applied mixins.
6. Sodium 0.9.2 has no GL-backend references in Iris's compat mixins (`I/compat/sodium/mixin/*`: no `opengl`/`GlStateManager` hits [V]); Sodium itself has GL-specific bits
   (`WindowMixin`, `GlSurfaceMixin`, `MappedStagingBuffer`, `GlStateManagerMixin`, git grep of `mc26.3-0.9.2`) which are Metallium's separate "Sodium on Metal" problem and a hard prerequisite of stage 3.

---------------------------------------------------------------------------------------------------

## 1. Catalogue of Iris classes that touch GL

Scope: 146 files / 20,126 LOC (grep for `org.lwjgl.opengl|GlStateManager|IrisRenderSystem|renderpearl.backend.opengl|glId|iris$getGlId|GlProgram`).
Column **T** = treatment: **N** none (constants/data/pure type reference), **S** class unchanged, behaviour supplied by the shim (section 4) or by a Metal-side class that implements an Iris-injected interface,
**R** targeted redirect/overwrite of a few methods (mixin from `metallium-iris`), **X** logic must be replaced/re-implemented against the pass-hook SPI, **D** disabled/dead on Metal.
"Calls" = number of other Iris files that mention the class name [V, grep -w]. Service names are defined in section 5.

### 1.1 Capability, debug, window (subsystem: debug/capabilities)

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/IrisRenderSystem.java` | 879 | The facade: 98 raw calls; DSA tiering `initRenderer` L59-78 (`GL.getCapabilities()`), `supportsSSBO/Image/Compute/Tess/BufferBlending` L299-337, `getVRAM` L477 (NVX/ATI), `getGlDevice()` L579-581 hard cast `(GlDevice)`, bind-to-edit helpers L370-420, `DSAAccess` L621-879 | 74 files | R | One mixin overwriting all 68 public statics -> `MetalGL` sink (section 4.2). `initRenderer` sets `dsaState = new DSACore()` via accessor and claims GL 4.5. `getGlDevice()` -> returns `null`; its 3 callers patched (see `MixinRenderTarget`). `getVRAM` -> `DeviceCapabilities.vramBytes()`. |
| `I/gl/GLDebug.java` | 378 | KHR/ARB/AMD debug callbacks, `glObjectLabel`, `glPush/PopDebugGroup` (34 raw) | 18 | R | no-op `nameObject`; `pushGroup/popGroup` -> `DebugGroups` (MTL debug groups, `MetalRenderPass.pushDebugGroup` exists `M/MetalRenderPass.java:110`) |
| `I/features/FeatureFlags.java` | 84 | none directly; suppliers call `IrisRenderSystem.supportsImageLoadStore/BufferBlending/Compute/Tesselation/SSBO` L14-22 | 12 | S | `DeviceCapabilities` flags (stage 1: all false; stage 5: compute/SSBO/image true; tess false permanently) |
| `I/gl/shader/StandardMacros.java` | 367 | `GlStateManager._getString(GL_VERSION/SHADING_LANGUAGE_VERSION/VENDOR/RENDERER)` L52-53, L208; every GL extension string becomes a `#define` | 3 (`IrisDefines`, `Iris`, `UpdateChecker`) | R | `DeviceCapabilities.glStrings()` curated: version "4.6.0 Metallium", GLSL "4.60", vendor "Apple" (-> `MC_GL_VENDOR_OTHER` L267-283), extension list = what the shim really implements |
| `I/gl/sampler/SamplerLimits.java` | 39 | `GL_MAX_TEXTURE_IMAGE_UNITS/DRAW_BUFFERS/MAX_SHADER_STORAGE_BUFFER_BINDINGS` | 6 | S | `DeviceCapabilities.limits()` via `_getInteger` sink |
| `I/gl/image/ImageLimits.java` | 24 | `IrisRenderSystem.getMaxImageUnits` | 2 | S | same |
| `I/mixin/MixinWindow.java` (target `GlBackend#createWindow`) | 51 | SDL GL debug-context attributes | - | D | target never runs on Metal |
| `I/compat/sodium/config/IrisConfig.java` | 204 | `getBackend() instanceof GlDevice` L107 (false on Metal = harmless); `IrisMixinPlugin.usingVulkan` L40 | 5 | N | - (optionally hide the "switch to Vulkan removes shaders" confirm, L106-123) |
| `I/Iris.java` | 840 | `onRenderSystemInit` L128-152: `GL.getCapabilities().GL_KHR_parallel_shader_compile` -> `glMaxShaderCompilerThreadsKHR/ARB`; L429-432 `GLDebug`, `GlDebug.enableDebugCallback(IrisRenderSystem.getGlDevice()...)` | 166 | R | `@WrapOperation` the two `GL.getCapabilities()` invocations (return all-false caps object) and guard L429-432 on `debug` only |
| `I/mixin/MixinRenderSystem.java` | 33 | `RenderSystem#initRenderer` RETURN L25 -> `GLDebug.reloadDebugState, IrisRenderSystem.initRenderer, IrisSamplers.initRenderer, Iris.onRenderSystemInit` | 1 | S | runs unchanged once its callees are shimmed |

### 1.2 Program creation / linking

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/pipeline/programs/ShaderCreator.java` | 435 | `link()` L186-200: `glCreateProgram`, `glCreateShader`, `glShaderSource`, `glCompileShader`, `glGetShaderi(COMPILE_STATUS)`, `glAttachShader`, `VertexFormat.bindAttributesIris`, `glLinkProgram`; `createShader` L224-243; builds fake program-JSON + `IrisProgramResourceFactory` (vestigial) | `IrisRenderingPipeline` (via `ShaderLoadingMap`) | S | `MetalliumShaderPrograms` (compile at `glCompileShader`, link at `glLinkProgram`) |
| `I/pipeline/programs/PartialShader.java` | 83 | `getFinally()` L26-35: `IrisBindings.apply`, `glDetachShader`, `glDeleteShader` | 2 | S | shim |
| `I/pipeline/programs/IrisBindings.java` | 70 | `glGetProgrami(LINK_STATUS)` L28, `glGetUniformBlockIndex/glUniformBlockBinding` L55-58, `glUniform1i` for samplers `tex/texture/gtexture/Sampler0/u_BlockTex`->0, overlay->1, lightmap->2, aux (`CloudFaces`, `u_SectionTimeInfo`)->3 (L44-47), UBO binding points DYN_TRANSFORMS=0, CLOUD_INFO=1, PROJECTION=2, GLOBALS=3, FOG=4, LIGHTING=5, PUSH_CONSTANTS=6, SODIUM_GLOBALS=0 (L15-22) | 3 | R | the alias table (names -> well-known binding) becomes `IrisBindingTable` consumed by the Metal Iris pipeline (section 2, H3); GL calls retargeted (`glGetUniformBlockIndex` returns reflection block index, `glUniformBlockBinding` records) |
| `I/pipeline/programs/ExtendedShader.java` | 282 | `extends GlProgram` L51 (private ctor reached through accesswidener), `GlStateManager._glGetUniformLocation` L148,168-169, `iris$setupState` L208-260 (`_glUseProgram`, `uniformMatrix4fv/3fv`, `bindTextureToUnit`, `samplers/uniforms/images.update()`, blend overrides, **FBO bind** L255-259), `iris$getBlockIndex` L267-272 (`GL46C.glGetUniformBlockIndex`), `iris$getGlId` for glint/white pixel L174-187 | `ShaderCreator`, `IrisRenderingPipeline`, mixins | R | keep class; `super.setupBindGroupLayouts` is cancelled by `MixinGlProgram:33-39` and builds `Uniform.Ubo/Sampler/Utb` records (`Utb` ctor calls `GlStateManager._genTexture()` `V/backend/opengl/Uniform.java`) -> works only because the sink hands out handles; `iris$setupState` is invoked by the Metal hook H3 |
| `I/pipeline/programs/FallbackShader.java` | 162 | same pattern (6 raw, 11 GSM) | 3 | R | same as ExtendedShader |
| `I/pipeline/programs/ShaderMap.java`, `ShaderLoadingMap.java`, `ShaderSupplier.java`, `IrisProgram.java` | 55/33/8/19 | type references to `GlProgram` | 2-8 | S,N,N,N | - |
| `I/gl/shader/GlShader.java`, `ProgramCreator.java` | 64/57 | `glCreateShader/ShaderSource/Compile/GetShaderi`; `glBindAttribLocation(0,"Position")(1,"UV0")` L25-26, attach, link, detach, link-status | 6 / 1 (`ProgramBuilder`) | S | shim |
| `I/gl/shader/ShaderWorkarounds.java` | 53 | AMD `nglShaderSource` raw | 1 | R | retarget to `MetalGL.nglShaderSource` (or bypass) |
| `I/gl/shader/ShaderType.java` | 25 | GL enum constants only | 17 | N | shim understands the enum ints |
| `I/gl/program/ProgramBuilder.java` | 157 | `begin` L34 / `beginCompute` L71 / `build` L101,105 -> `ProgramCreator`, `IrisRenderSystem` x2 | 7 | S | shim |
| `I/gl/program/Program.java` | 52 | `use()` L27-34 = `glMemoryBarrier(IMAGE|FETCH|SSBO)` **on every program use**, `glUseProgram`, then `uniforms/samplers/images.update()` | 7 | S | barrier sink is a no-op outside compute/RW-image use (3.4 note) |
| `I/gl/program/ComputeProgram.java` | 114 | `glGetProgramiv(COMPUTE_WORK_GROUP_SIZE)` L30, `glDispatchCompute(Indirect)` L91-95, `glMemoryBarrier` L77-88 | 6 | S | `ComputeDispatcher` + reflection `LocalSize` |
| `I/mixin/vertices/MixinVertexFormat.java` | 44 | `bindAttributesIris`: `glBindAttribLocation(prog, j, "iris_"+name)` L35 | - | S | shim stores attribute locations; applied to SPIR-V `Location` decorations at link |
| `I/mixin/MixinShaderManager_Overrides.java` | 161 | **central override**: `RenderSystem#getCompiledPipelineNullable` RETURN L68-110; `(GlDevice)` cast L85, `GL33C.glGetAttribLocation` L125-127, `VertexArray.createSource(GL.getCapabilities(),..)` L97, `new GlRenderPipeline(device, createInfo, program, vertexArray)` L98; cache `iris$overrides` L66 | 0 | X | Metal variant (section 2, H2) |
| `I/mixin/MixinGlProgram.java` / `MixinCompiledShaderProgram.java` | 116/141 | target `GlProgram`: `setupBindGroupLayouts` cancel + custom binding indices L33-56, `iris$uniformBufferBinding/SamplerBinding` name tables L92-115; the second only redirects `glGetUniformBlockIndex` L85-92 (the rest is commented "TODO 1.21.6") | - | R / D | the name tables move to `IrisBindingTable`; the mixin still runs because `ExtendedShader` calls `super.setupBindGroupLayouts` |

### 1.3 Uniforms (loose `glUniform*`)

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/program/ProgramUniforms.java` | 373 | `GlStateManager._glGetUniformLocation` L294, `glGetProgrami(GL_ACTIVE_UNIFORMS)` L315, `IrisRenderSystem.getActiveUniform` L320 (diagnostics); GL type enums L66-178 | 10 | S | `UniformBlockWriter.location()` returns a synthetic location per reflected default-block member; type enums interpreted by the shim |
| `I/gl/uniform/*Uniform.java` (10: Float, Int, Matrix3, Vector2/3/4 and Integer variants, Vector4Array) | ~450 | `IrisRenderSystem.uniformNf/Ni/Matrix*` with value caching | 1-3 each | S | `UniformBlockWriter.set*` via the sink |
| `I/gl/uniform/MatrixUniform.java`, `MatrixFromFloatArrayUniform.java` | 54/37 | raw `GL46C.glUniformMatrix4fv` L51 / L34 | 2/1 | R | retarget raw call to sink (ASM, 4.2) |
| `I/uniforms/custom/cached/*CachedUniform.java` (10) | ~340 | `IrisRenderSystem.uniform*` | 1-2 | S | same |
| `I/uniforms/CommonUniforms.java` | 383 | reads `GlStateManagerAccessor.getBLEND()/BLEND_ENABLE()` L107-109 for `blendFunc` | 8 | S | requires vanilla tracking arrays to stay authoritative (4.2) |
| `I/uniforms/FogUniforms.java`, `VanillaUniforms.java` | 56/17 | `GL11.GL_LINEAR/GL_EXP2` constants | 1 | N | - |
| `I/shaderpack/properties/ShaderProperties.java` | 997 | one capability call `supportsBufferBlending` L317 | 15 | S | `DeviceCapabilities` |
| `I/pipeline/programs/IrisBindings`, `I/mixin/MixinUniform.java` (target `GlStateManager#_glGetUniformLocation` RETURN L17: aliases Sampler0/u_BlockTex -> tex/gtexture/texture, Sampler1 -> iris_overlay, Sampler2/u_LightTex -> lightmap) | 48 | | | S | alias logic runs inside the shim's `_glGetUniformLocation`; same alias set reused by `IrisBindingTable` |

### 1.4 Samplers and textures

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/program/ProgramSamplers.java` | 346 | `glUniform1i(location, unit)` L67, `glActiveTexture` L81, `IrisRenderSystem.unbindAllSamplers` L43, `_glGetUniformLocation` L136,151,190; reads `GlStateManagerAccessor.getActiveTexture()` L75 | 11 | S | shim: sampler members bound to units; units resolved to textures at draw |
| `I/gl/program/ProgramImages.java`, `I/gl/image/ImageBinding.java` | 99/25 | `glUniform1i`, `glBindImageTexture(unit, tex, 0, true, 0, READ_WRITE, fmt)` ImageBinding L23 | 7 / 1 | S | `ImageBindingService` |
| `I/gl/sampler/GlSampler.java` | 59 | `glGenSamplers`, `glSamplerParameteri` MIN/MAG/WRAP/COMPARE_MODE/FUNC (L25-43); statics NEAREST/LINEAR/LINEAR_REPEAT/... | 17 | S | `SamplerService` (MTLSamplerState cache keyed by the parameter tuple; compare func for shadow samplers) |
| `I/gl/sampler/SamplerBinding.java` | 38 | `bindSamplerToUnit` | 1 | S | - |
| `I/samplers/IrisSamplers.java`, `IrisImages.java` | 265/~120 | only `iris$getGlId()` (15 uses) / image bindings | 8 | S | `GpuTextureInterface.iris$getGlId` implemented by `MetalGpuTexture` |
| `I/gl/texture/GlTexture.java` (Iris's own) | 102 | `GlStateManager._genTexture`, `bindTextureForSetup`, `TextureType.apply` -> `texImage1D/2D/3D`, `texParameteri` (MIN/MAG/WRAP/MAX_LEVEL/LOD) L26-55 | 10 | S | `TextureService` |
| `I/gl/texture/{TextureType,TextureUploadHelper,PixelFormat,PixelType,InternalTextureFormat,DepthBufferFormat}.java` | 48/24/62/66/125/74 | GL enum data (58 internal formats); `TextureUploadHelper` `_pixelStore` x5 | 6-34 | N,S,N,N,N,N | `TextureService` interprets the ints; 3-component formats padded to RGBA (translation notes) |
| `I/gl/texture/DepthCopyStrategy.java` | 134 | `fastest()` L46-115: `glCopyImageSubData` (GL43), `glBlitFramebuffer` depth, `glCopyTexSubImage2D`; reads `GlStateManagerAccessor.getTEXTURES()[active].binding` L60 | 4 | S | `TextureService.copy*` (blit encoder); strategy choice irrelevant (all map to one blit) |
| `I/pipeline/CustomTextureManager.java` | 217 | creates Iris `GlTexture` for raw 1D/RECTANGLE/2D/3D L104-121; per-unit `_activeTexture/_bindTexture` L153-184 | 1 | S | `TextureService` (1D -> 2D height 1; RECTANGLE -> 2D + unnormalised sampling note) |
| `I/targets/backed/{NoiseTexture,SingleColorTexture,NativeImageBackedCustomTexture,NativeImageBackedNoiseTexture}.java` | 80/46/87/56 | `glGenTextures/texImage2D/texParameteri`; two use frontend `writeToTexture` | 0-2 | S | same |
| `I/pbr/texture/PBRAtlasTexture.java` | 375 | `(GlTexture) texture).glId()` L153 -> `TextureManipulationUtil.fillWithColor`; frontend render passes "Animate" L279,313 | 4 | R | replace the cast by `iris$getGlId`; fill via `TextureService.clear` |
| `I/pbr/util/TextureManipulationUtil.java`, `pbr/TextureInfoCache.java`, `pbr/texture/PBRTextureManager.java` | 47/94/243 | `glClear*`/FBO fill; `_getTexLevelParameter` (width/height/internal format query, TextureInfoCache L61-89) | 1-5 | S | `TextureService.query*` |
| `I/mixin/texture/{MixinAbstractTexture,MixinGlStateManager}.java`, `texture/pbr/{MixinReloadableTexture,MixinTextureAtlas}.java` | 58/37/19/52 | `iris$getGlId()` on every texture access / upload (L28, L50, L17); `_texImage2D/_deleteTexture` tail hooks | - | S | **first-boot blocker**: `MetalGpuTexture` must return a stable handle (stage 1) |
| `I/mixin/MixinGpuTexture.java` (GlTexture), `MixinGpuTexture2.java` (BaseGpuTexture: `iris$getGlId` throws `AssertionError("Why.")`, L11) | 44/19 | | - | D / S | `MetalGpuTexture` overrides the BaseGpuTexture default |
| `I/mixinterface/GpuTextureInterface.java` | 13 | interface | 3 | N | Metal side implements it (module has Iris on its compile classpath) |

### 1.5 Images, SSBO, compute

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/image/GlImage.java` | 152 | `IrisRenderSystem.createTexture`, `texImage*`, `texParameteri`, `bindTextureForSetup` | 7 | S | `TextureService` (usage `RW`) |
| `I/gl/buffer/ShaderStorageBuffer.java` | 83 | `createBuffers`, `bufferStorage`, `clearBufferSubData(GL_R8)`, `bindBufferBase(GL_SHADER_STORAGE_BUFFER)`, `deleteBuffers` L20-75 | 1 | S | `StorageBufferService` |
| `I/gl/buffer/ShaderStorageBufferHolder.java` | 108 | VRAM check `getVRAM` L28, unbind `bindBuffer(SSBO,0)` L46 | 6 | S | same |
| `I/gl/program/ComputeProgram.java` | 114 | see 1.2 | 6 | S | `ComputeDispatcher` |
| `I/pathways/colorspace/ColorSpaceComputeConverter.java` | 72 | `glDispatchCompute(w/8,h/8,1)`, barrier; **disabled upstream** ("grid appearing" TODO, `IrisRenderingPipeline`) | 1 | S | - |

### 1.6 Framebuffers and render targets

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/framebuffer/GlFramebuffer.java` | 117 | `createFramebuffer` (DSA), `glFramebufferTexture2D` (L36,45,53), `glDrawBuffers` (L58,77), `glReadBuffer`, `glBindFramebuffer` (L93-101), `glCheckFramebufferStatus` (L111), `_getInteger(MAX_DRAW_BUFFERS/MAX_COLOR_ATTACHMENTS)`; **casts `(GlTexture) texture).glId()` L36** | 15 | R | `RenderTargetService`; fix the cast to `iris$getGlId` |
| `I/targets/RenderTargets.java` | 403 | owns colortex FBOs, `createColorFramebuffer`/`createGbufferFramebuffer`, `glCopyTexImage2D` + DepthCopyStrategy for depthtex1/2 L225-241, `checkFramebufferStatus` L378 | 7 | S | `RenderTargetService` |
| `I/targets/RenderTarget.java` | 217 | two textures per colortex: `_genTexture` L44-45, `texParameteri`, `texImage2D` L71, `_deleteTexture` | 14 | S | `TextureService` |
| `I/targets/DepthTexture.java` | 41 | create/params/`texImage2D` depth | 1 (DH) | S | - |
| `I/targets/ClearPass.java`, `ClearPassCreator.java` | 46/131 | `glClearColor`, `_clear`, `_viewport`; FBO per clear group | 2/1 | S | clear -> load action `Clear` on the next retarget, or clear-draw (`MetalUtilities.clearDraw` exists, `M/MetalCommandEncoder.java:178-186`) |
| `I/shadows/ShadowRenderTargets.java` | 388 | shadowtex0/1 `D32_FLOAT` frontend textures (L65-66), shadowcolor via `RenderTarget`, `blitFramebuffer` L184, `DepthCopyStrategy` L189, status L319 | 9 | S | same services |
| `I/pathways/CenterDepthSampler.java` | 147 | 1x1 raw GL textures `_genTexture` L54-55, own render pass L98-108 via `createRenderPass(... mainRenderTarget color)` + `iris$setCustomPass(EMPTY_STATE)`, `_viewport(0,0,1,1)` | 3 | S | custom-pass hook H4 |
| `I/mixin/MixinRenderTarget.java` | 65 | `iris$bindFramebuffer` L61: `getGlDevice().frameBufferCache().getFbo(...)` + `_glBindFramebuffer` | 1 | R | `RenderTargetService.framebufferFor(colorView, depthView)` (a memoised FBO descriptor, replaces `FrameBufferCache`) |
| `I/mixin/state_tracking/MixinRenderTarget.java` | 51 | inject commented out (L43 "TODO 1.21.5") | 1 | N | - |

### 1.7 Composite / deferred / final pass rendering

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/pipeline/CompositeRenderer.java` | 535 | `renderAll` L271-361: computes + `glMemoryBarrier` L298, mipmaps `setupMipmapping` L311, `Pass.setupState` (colorMask, FBO bind, blend) L513-522, `_viewport/_scissorBox` L322-324, `program.use()` L326, **`_glBindBuffer(ELEMENT_ARRAY, ((GlBuffer) indices).handle)` L330 + `_drawElements(GL_TRIANGLES,6,..)` L332**, texture unbind loop L346-353 reading `GlStateManagerAccessor.getTEXTURES()`; vertex array via `FullScreenQuadRenderer.bind()` | 7 | X | `PassEncoder.drawFullscreenQuad()` replaces L330-332 (cast would CCE); rest runs on the shim |
| `I/pipeline/FinalPassRenderer.java` | 477 | same loop; **uses a vanilla `RenderPass`** (`createRenderPass(() -> "Final pass", mainRenderTarget color, empty)` L249) + `setPipeline(COMPOSITE_PIPELINE)` + `iris$setCustomPass(STATE)` L254 + `renderPass.drawIndexed(6,1,0,0,0)` L264; no-pass fallback `glCopyTexSubImage2D` from `baseline` FBO L278-280; swap passes `GL46C.glCopyTexSubImage2D` L302; `getColorTexture().iris$getGlId()` L217-220 | 2 | X | custom-pass hook H4 (draw uses shim state, not the pipeline) |
| `I/shadows/ShadowCompositeRenderer.java` | 406 | `generateMipmaps` L173, `_viewport/_scissorBox` L239-241, uses custom pass L198,233; `blend` | 2 | X (shared with CompositeRenderer) | H4 |
| `I/pathways/FullScreenQuadRenderer.java` | 60 | `bind()` L55-59: pulls `GlRenderPipeline.vertexArray()` out of the compiled `COMPOSITE_PIPELINE` and `bind(quad)` | 6 | X | `PassEncoder.setFullscreenQuad(GpuBuffer quad)` (vertex layout Position@loc0 float3, UV0@loc1 float2, stride 20; fixed by `ProgramCreator.java:25-26`) |
| `I/pathways/colorspace/ColorSpaceFragmentConverter.java`, `ColorSpaceConverter.java` | 124/9 | `process(GlTexture)` signature; `_genTexture`, `texImage2D`, `glCopyTexSubImage2D` L122; own program + custom pass L108-110 | 1 / 3 | R | change parameter type to `GpuTexture` (cast site `I/pipeline/IrisRenderingPipeline.java:1095`, lambda L516) |
| `I/pipeline/IrisRenderingPipeline.java` | 1387 | ctor: SSBO unbind `bindBufferBase` L241-250, `_activeTexture(TEXTURE2)` L299-305, `GlTexture` casts L516,1095, `GLDebug` push/pop L780-816, clear pass L887-994, texture/FBO unbind L1210-1237; `beginLevelRendering` shadow depth clear via **frontend** `clearDepthTexture` L906 | 19 | R | the 2 `GlTexture` casts; everything else on the shim |
| `I/pipeline/PipelineManager.java`, `VanillaRenderingPipeline.java` | 113/243 | texture unbind loop L104-111; `_glUseProgram(0)` per frame L44 | 1 / 3 | S | sink |

### 1.8 Blend / depth / state overrides

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/gl/blending/BlendModeStorage.java` | 90 | snapshots `GlStateManagerAccessor.getBLEND()/BLEND_ENABLE()` L8-48; `IrisRenderSystem.enable/disableBlend`, `enable/disableBufferBlend`, `blendFuncSeparatei` (L33-56) | 3 | S | `PipelineStateService` via sink; vanilla tracking arrays must stay live |
| `I/gl/blending/DepthColorStorage.java` | 61 | snapshots `COLOR_MASK`, `DEPTH`; `_depthMask`, `_colorMask` L30-58 | 5 | S | same |
| `I/gl/blending/{AlphaTestFunction,BlendModeFunction}.java` | 63/39 | GL enum constants | 4/2 | N | - |
| `I/mixin/MixinGlStateManager_BlendOverride.java` (L14,22,34), `_DepthColorOverride` (L18,25,33,41,50), `_FramebufferBinding` (L24,35,41), `MixinBooleanState` (L14), `statelisteners/MixinGlStateManager` (L20), `MixinGlStateManager` (`<clinit>` constant 12 -> 128, L11), `MixinUniform` (L17), `GlStateManagerAccessor` | 48/54/49/42/26/17/48/38 | HEAD-injects that implement the *locks* and tracking on vanilla `GlStateManager` | 2-11 | S | keep; the sink must sit *behind* them (priority, 4.2). `_DepthColorOverride:41` (`_drawElements` -> `GL_PATCHES`) is tessellation only (dead). `_FramebufferBinding:35-39` `_activeTexture` range check fine |
| `I/mixin/MixinGlRenderPipeline.java` | 101 | target `GlRenderPipeline`: capture `CreateInfo` L222-224; `bind` L231-283: cull forced off in shadows L231-234, depth func inverted in shadows L237-256, blend through `IrisRenderSystem` for IrisProgram with 1 colour target L259-275, `iris$setupBindings + iris$setupState` L277-283 | 0 | X | H3 + H6 on the Metal pipeline |
| `I/mixin/MixinGlCommandEncoder.java` | 183 | see 2.1 | 0 | X | H1/H3/H4/H5 |

### 1.9 Shadow pass

| File | LOC | GL it does | Callers | T | Replacement |
|---|---|---|---|---|---|
| `I/shadows/ShadowRenderer.java` | 832 | `configureDepthSampler` texParameters (compare mode/func GEQUAL, swizzle RED,RED,RED,ONE, filters) L239-300, `_viewport(0,0,res,res)` L386,542,646, `setShadowProjection` L440, `_disableCull/_enableCull` L516,642, **three `createRenderPass("Terrain", mainRenderTarget color+depth, ...)`** L526,602,620 whose FBO/viewport/scissor are suppressed by `MixinGlCommandEncoder:65-110` | 13 | X | H1 shadow retarget (attachments = shadow FBO, viewport/scissor = `RESOLUTION`) |
| `I/shadows/ShadowRenderTargets.java`, `ShadowCompositeRenderer.java` | see 1.6/1.7 | | | | |

### 1.10 Distant Horizons and Sodium config

`I/compat/dh/{DHCompatInternal 292, DhFrameBufferWrapper 53, IrisGenericRenderProgram 349, IrisLodRenderProgram 273, LodRendererEvents 385}` = raw `GL43C` programs (45 raw calls) bound to DH's own GL renderer [V]. **D** on Metal
(DH's GL texture ids do not exist). `Iris.onRenderSystemInit` defers `loadShaderpack()` to DH when DH is loaded (`Iris.java:150-152`); with DH present on Metal the adapter must force the non-DH branch.

### 1.11 Summary by treatment (146 files, files counted per row)

COUNTS-PLACEHOLDER

---------------------------------------------------------------------------------------------------

## 2. Iris mixins into GL-specific / vanilla GL classes and the SPI they need

### 2.1 Mixin-by-mixin (all mixins whose target is `renderpearl.backend.opengl.*`, `GlStateManager`, `BaseGpuTexture`, `RenderPassBackend`, or whose body is GL-bound)

| Iris mixin (file:line) | Target | What it achieves | Equivalent hook on Metal |
|---|---|---|---|
| `MixinGlCommandEncoder.java:65-72` `changeViewport` | `GlCommandEncoder#createRenderPass` `_viewport` | do not set the viewport during shadow rendering (shadow viewport was set by Iris) | **H1**: `PassContext.viewportOverride` (shadow: `0,0,RESOLUTION,RESOLUTION`) |
| `:74-93` `changeViewport2/3` | `_scissorBox`, `ScissorState#enable` | force scissor to shadow resolution while shadows render | H1 (`scissorOverride`) |
| `:96-104` `changeFramebuffer` | `_glBindFramebuffer` | when shadows are rendering or `ImmediateState.safeToMultiply`, do **not** bind the pass FBO; remember it as `tempFBO` | H1: `attachmentOverride(ctx)` returns the shim's current FBO (shadow FBO) instead of the descriptor's attachments |
| `:106-111` `iris$changeDrawBuffers` | `GL33C.glDrawBuffers` in createRenderPass | keep Iris draw buffers | H1: draw-buffer list is part of the override (FBO state) |
| `:116-153` `iris$bypassSetup` | `GlCommandEncoder#setupDraw` HEAD | unlock depth/colour masks, `lastPipeline=null` when a pack is active so `bind()` runs every draw; if `iris$getCustomPass()!=null`: cancel vanilla setup, `BlendModeOverride.restore(); disableBlend; customPass.setupState()`, scissor, depth off, cull off, polygonoffset off, colormask 15 | **H4**: custom-pass draw path that ignores the bound `BackendRenderPipeline` and draws with shim state; **H3**: `beforeDraw` called on every draw while a pack is active (no "same pipeline" short-circuit) |
| `:155-160` `iris$trackProgram`, `:162-166` `iris$clearState` | `setupDraw` RETURN, `submitRenderPass` HEAD | collect `IrisProgram`s used in the pass; call `iris$clearState` (clear active uniform/sampler caches, restore blend override) at pass end | **H5**: `afterPassSubmit()` |
| `:168-182` tessellation `GL_TRIANGLES -> GL_PATCHES` | `executeDraws/executeDraw` | tessellation | D (tess unsupported; `ExtendedShader.usesTessellation` must be false -> pack requiring tess rejected via FeatureFlags) |
| `MixinGlRenderPipeline.java:222-224` | `<init>` | keep `CreateInfo` for `iris$setupBindings(uniforms, pushConstantsSize)` | Metal Iris pipeline keeps its own `CreateInfo` (it is constructed from it) |
| `:231-234` `iris$redirectCull`, `:237-256` `iris$pass` | `bind` cull / `_depthFunc` | shadow pass: no culling; reverse the depth comparison (LESS<->GREATER, LEQUAL<->GEQUAL; others identity) | **H6**: `PipelineStateTweaks` applied when the PSO/encoder state is built while `ShadowRenderingState.areShadowsCurrentlyBeingRendered()` |
| `:259-275` `iris$enableBlend/disableBlend` | `bind` | for `IrisProgram` with exactly 1 colour target, blend state comes from `IrisRenderSystem` (pack directive / override) rather than the vanilla pipeline | H3: PSO blend state for an Iris pipeline is taken from the shim `PipelineStateService` snapshot at draw time; per-attachment blends from `glEnablei/glBlendFuncSeparatei` |
| `:277-283` `iris$bind` | `bind` RETURN | `GlProgramBindings.iris$setupBindings(createInfo.uniforms(), pushConstantsSize)` then `IrisProgram.iris$setupState(uniforms)` | **H3** `beforeDraw`: call the same two methods on the program object stored in the Metal Iris pipeline |
| `MixinGlProgram.java:33-72` | `GlProgram#setupBindGroupLayouts` HEAD / `close` | give Iris programs fixed binding indices (UBO 0..6, samplers 0..3), create `Uniform.Utb` texel buffers by name | `IrisBindingTable` in the Metal Iris pipeline: block/sampler name -> `UniformDescription` index (what `FrontendRenderPass.setUniform(index, ..)` delivers to `RenderPassBackend#setUniform(int, Object)`) |
| `MixinCompiledShaderProgram.java:85-92` | `GlProgram#setupBindGroupLayouts` redirect | `iris_`-prefixed UBO names | folded into the same table; remaining members commented out |
| `MixinRenderPass.java:15` / `MixinRenderPass3.java:12` / `MixinRenderPass_Stub.java:15,20` | `GlRenderPass`, `FrontendRenderPass`, `RenderPassBackend` | `iris$setCustomPass/getCustomPass` plumbing; **`RenderPassBackend` default methods throw `UnsupportedOperationException`** | `MetalRenderPass` must implement `RenderPassInterface` (mixin from `metallium-iris`, class is package-private/final) - otherwise `FinalPassRenderer.java:254` crashes |
| `MixinGlRenderPass.java:16` | `FrontendRenderPass#setUniform(String,TextureView,Sampler)` | capture albedo for PBR | frontend-only, works unchanged |
| `MixinGpuTexture.java:35` / `MixinGpuTexture2.java:11` | `GlTexture`, `BaseGpuTexture` | `iris$getGlId()` (49 call sites), `iris$markMipmapNonLinear` | `MixinMetalGpuTexture implements GpuTextureInterface` backed by `GlNames` |
| `MixinRenderTarget.java:31,61` | blaze3d `RenderTarget` | depth/colour buffer versions; `iris$bindFramebuffer` via `GlDevice.frameBufferCache()` | version counters unchanged; `bindFramebuffer` -> `RenderTargetService.framebufferFor` |
| `MixinShaderManager_Overrides.java:68-110` | `RenderSystem#getCompiledPipelineNullable` RETURN | swap the compiled pipeline for the Iris `ExtendedShader` program | **H2** (below) |
| `MixinGlStateManager_*` (see 1.8), `MixinBooleanState`, `MixinUniform`, `statelisteners`, `texture.MixinGlStateManager`, `MixinGlStateManager` | `GlStateManager` | locks, tracking, aliasing, texture-unit constant 12->128 | stay; sink behind them |
| `MixinWindow.java:26,28` | `GlBackend#createWindow` | GL debug context | D |
| `MixinRenderSystem.java:25` | `RenderSystem#initRenderer` RETURN | Iris init | unchanged |
| `UndoReverseZOne` (`DeviceInfo`), `UndoReverseZTwo/Three/Five` | - | `One` is empty; `Two`-`Five` are not in `mixins.iris.json` (inactive) [V] | none (Iris's own `DepthTransformer` handles reverse-Z via `DeviceInfo.isZZeroToOne`, translation notes 1.3) |

### 2.2 SPI that Metallium's backend must expose (`dev.metallium.iris.spi.IrisBackendHooks`, registered at runtime by `metallium-iris`, consulted from `dev.metallium.backend`)

```java
public interface IrisBackendHooks {
    boolean active();                       // cheap volatile read; false => backend behaves exactly as today
    // H1 attachment retarget: called from MetalRenderPass on setPipeline/draw (not only at createRenderPass)
    @Nullable AttachmentOverride attachmentOverride(BackendRenderPipeline pipeline, @Nullable CustomPassView custom);
    // H2 pipeline substitution factory (called by Iris's override mixin, Metal flavour)
    BackendRenderPipeline createIrisPipeline(BackendRenderPipeline.CreateInfo base, IrisProgramRef program);
    // H3 per-draw state flush: Iris iris$setupBindings + iris$setupState equivalent
    void beforeDraw(MetalPassView pass, BackendRenderPipeline pipeline);
    // H4 custom pass: draw with shim state instead of pipeline state
    @Nullable CustomPassView customPass(RenderPassBackend pass);
    // H5 pass end
    void afterPassSubmit(MetalPassView pass);
    // H6 pipeline-state tweaks (shadow cull/depth flip, per-attachment blend, polygon mode)
    PipelineStateTweaks tweaks(BackendRenderPipeline pipeline);
}
record AttachmentOverride(MetalTextureRef[] color /*null = unused slot*/, @Nullable MetalTextureRef depth,
                          int[] drawBufferMap, @Nullable Viewport viewport, @Nullable Rect scissor, LoadPolicy load);
```

Backend changes that implement the SPI (all in `dev.metallium.backend`):
- **H1**: `MetalRenderPass` fields `colorAttachments/colorFormats/depthTexture` (lines 39-93) become mutable; new `MetalRenderPass.retarget(AttachmentOverride)` ends the current encoder via the existing mechanism in `MetalCommandEncoder.renderCommandEncoder(...)` (`:154-196`, attachment comparison `sameAttachments` L211). Retarget loads with `MTLLoadAction.Load` (Iris has already cleared). `MetalCommandEncoder.createRenderPass` (`:276-335`) stays lazy: no encoder is opened until the first draw/clear, which is what makes "create pass on main target, then Iris re-binds" free (no extra empty encoder).
- **PSO keying**: `MetalCompiledRenderPipeline.createPipeline(layout)` (`:~190-215`) currently derives formats from `CreateInfo.colorTargetStates` and fixes `Depth32Float`. Iris pipelines key PSOs by (actual colour formats[8], depth format, blend state per attachment, write masks, sample count) built from the override.
- **H2**: `MetalCompiledRenderPipeline` gets a sibling `MetalIrisRenderPipeline` created from SPIR-V `SpvModule`s of the linked Iris program (`MetalCrossShaderCompiler` already consumes `CreateInfo.shaders()` SPIR-V -> MSL, `M/MetalCrossShaderCompiler.java`), adding: shim-driven resource binding, default-block buffer, Iris binding table.
- **H3/H4**: `MetalRenderPass.drawIndexed/multiDrawIndexed/draw/...` (`:205-333`) call `hooks.beforeDraw(...)` first when `hooks.active()`; with a custom pass set, draws use `PassEncoder` shim state.
- **H5**: `MetalCommandEncoder.submitRenderPass()` (`:337`) -> `hooks.afterPassSubmit`.
- **Vertex setup for Iris**: `MixinShaderManager_Overrides.iris$createInfo` (`:112-138`) rebuilds `AttribBinding`s using `glGetAttribLocation(program, "iris_"+element.name())` then plain name; shim answers from reflection `inputs()` (`SpvModule.Reflection.InterfaceVariable`, `V/backend/api/SpvModule.java`, `location(int)` is settable).

### 2.3 H2 in detail: pipeline compile substitution

`RenderSystem#getCompiledPipelineNullable` (vanilla) is frontend, so Iris's hook point survives; only its body is GL (`MixinShaderManager_Overrides.java:85,96-99,125-127`).
Metal replacement (mixin in `metallium-iris`, priority 1500 so it applies after Iris's, HEAD-inject with `cancellable`; see 3.3 for why HEAD-cancel pre-empts Iris's RETURN injector):
1. same early-outs as Iris L70-76 (`COMPOSITE_PIPELINE`, `ANIMATE_SPRITE_*`, `ImmediateState.bypass`, pipeline instanceof `IrisRenderingPipeline && shouldOverrideShaders()`);
2. `ShaderKey key = IrisPipelines.getPipeline(irisPipeline, renderPipeline)`; `GlProgram program = irisPipeline.getShaderMap().getShader(key)` (unchanged; returns `ExtendedShader`/`FallbackShader` whose int program id is a shim handle);
3. `BackendRenderPipeline.CreateInfo createInfo = iris$createInfo(...)` unchanged except `glGetAttribLocation` goes through shim;
4. `new FrontendRenderPipeline(old2.name(), hooks.createIrisPipeline(createInfo, program), vertexFormats, old2.uniformIndices(), old2.uniforms(), old2.colorTargetStates(), old2.wantsDepthTexture(), old2.pushConstantSize())` - keeping `old2.colorTargetStates()` is what satisfies `FrontendRenderPass.setPipeline` validation;
5. cache identical to `iris$overrides` (identity map keyed by old pipeline x program x vertex-format list).

---------------------------------------------------------------------------------------------------

## 3. IrisMixinPlugin / "Vulkan gate"

### 3.1 What the code does [V]
- `I/mixin/IrisMixinPlugin.java:18` `public static boolean usingVulkan;` (non-final). Static initialiser L20-59 reads `<gameDir>/options.txt`, sets `usingVulkan = value("preferredGraphicsBackend").toLowerCase().contains("vulkan")`; no file / no key -> `false`.
- L71-74 `shouldApplyMixin`: `if (mixinClassName.contains("VKOnly")) return usingVulkan; return !usingVulkan;`. Only `VKOnly_InitKeys` (`mixins.iris.json:87`) is Vulkan-only.
- The same plugin class is the `plugin` of five configs: `mixins.iris.json:7`, `mixins.iris.vertexformat.json:6`, `mixins.iris.compat.sodium.json:6`, `mixins.iris.bettermipmaps.json:6` (0 mixins), `mixins.iris.fantastic.json:6` (7 mixins, not referenced by `fabric.mod.json`). DH uses its own plugin; `mixins.iris.fixes.maxfpscrash.json` has none.
- `IrisConfig.java:40` reads `usingVulkan` to build the placeholder UI.

### 3.2 Consequence for Metallium
`options.txt` never contains "vulkan" under Metallium (backend list is overridden unconditionally; if the user chooses Vulkan in Sodium's UI the Sodium-config overlay `IrisConfig.java:101-123` is skipped because `getBackend() instanceof GlDevice` is false and the string would flip the gate). So `usingVulkan=false` => **all 145+7+21 mixins apply**. Every GL-backend-targeted mixin still resolves because the GL backend classes exist in the vanilla jar; they are simply never exercised.
The harm is only in applied mixins whose *hosts are shared/active* and whose bodies are GL-bound: `MixinShaderManager_Overrides` (cast `GlDevice`), `MixinRenderSystem` (via `IrisRenderSystem.initRenderer -> GL.getCapabilities()`), `MixinRenderTarget#iris$bindFramebuffer`, `Iris.onRenderSystemInit`. Everything else is repaired by callee-side shims.

### 3.3 Options and feasibility

| # | Option | Feasible? | Notes |
|---|---|---|---|
| A | **No gate; runtime neutralisation** (recommended for stages 1-5) | yes [SI] | (a) callee shim (4.2). (b) for the 3-4 GL-bound *RETURN/TAIL* injectors, add a `metallium-iris` mixin on the same host with **higher priority number (applied later)** and `@Inject(at=HEAD, cancellable=true)` that handles the Metal case and cancels; Iris's RETURN callback sits before the original `return` opcodes and is skipped by the early return [SI; Mixin semantics, verify in spike]. Iris HEAD injectors (the GlStateManager locks) still run first and are wanted. |
| B | Set `IrisMixinPlugin.usingVulkan=true` reflectively | no | disables **all** non-VKOnly mixins including level/world hooks. Useful only as an emergency "Iris menu only" mode. |
| C | Mixin into `IrisMixinPlugin` | no [SI] | the plugin class is defined while configs are registered (`FabricMixinBootstrap` -> `Mixins.addConfiguration` -> plugin instantiated, static init runs), before the transformer has any selected config, so mixins cannot apply to it. |
| D | A Metallium mixin config with higher `priority` | does not help | `priority` orders mixins within a target; it cannot veto another config's `shouldApplyMixin`. |
| E | Reflectively wrap the live Iris config's plugin with a filtering delegate from a Metallium `IMixinConfigPlugin.onLoad` / pre-launch entrypoint (`Mixins.getConfigs()` -> `Config.getConfig()` -> private `plugin` field) | maybe [H] | depends on Mixin internals and on Metallium's config registering after Iris's (Fabric load order is dependency-based; add `"depends": {"iris": "*"}` to `metallium-iris` so it registers after Iris). Needs a half-day spike. Gives exact exclusion lists if A proves insufficient. |
| F | 1-file Iris patch / upstream PR: `shouldApplyMixin` consults a `BackendSupport` ServiceLoader and a `GL_ONLY` set | yes | cleanest long term; only valid if we accept a patched Iris or upstream acceptance. |

Recommendation: A now, E as spike in stage 1 (to learn if exclusion is possible without a fork), F as the upstream ask.
Also needed: hide/redirect `ShaderPackScreenPlaceholder` ("Iris cannot run when using Vulkan", `compat/sodium/config/ShaderPackScreenPlaceholder.java:30`) only for the Vulkan case; no change.

---------------------------------------------------------------------------------------------------

## 4. Strategy: mixin adapter vs fork

### 4.1 Counts (from section 1.11)
COUNTS-STRATEGY-PLACEHOLDER

### 4.2 Recommended mechanism: "GL-name shim at the facade seam" + 12 explicit Metal hooks (no Iris source fork)
1. **`MetalGL` sink**: generated class `dev.metallium.iris.gl.MetalGL` with the ~110 LWJGL entry points Iris uses (same names/descriptors; list in appendix A), implemented on the services of section 5. GL constants are `static final int` and inline at compile time, so Iris code compiles to the same ints; the shim interprets them.
2. **Retarget, do not cancel**: an ASM step in `metallium-iris`'s mixin plugin (`preApply`/`postApply`, which fire for every class that any of our mixins targets - declare an empty `@Mixin` for each class below) rewrites `INVOKESTATIC org/lwjgl/opengl/GL*.x` to `INVOKESTATIC dev/metallium/iris/gl/MetalGL.x` inside exactly these classes: vanilla `GlStateManager` (+ nested `BooleanState`), Iris `IrisRenderSystem`, `GLDebug`, `Iris`, `ExtendedShader`, `FallbackShader`, `IrisBindings`, `MatrixUniform`, `MatrixFromFloatArrayUniform`, `ShaderWorkarounds`, `FinalPassRenderer`, `MixinBooleanState`. Reason for retargeting rather than HEAD-cancelling `GlStateManager` methods: vanilla's own tracking (`BLEND`, `BLEND_ENABLE`, `COLOR_MASK`, `DEPTH`, `TEXTURES`, `activeTexture`, viewport/scissor) must stay authoritative because Iris reads it back (`BlendModeStorage.java:24-48`, `DepthColorStorage.java:20-24`, `CommonUniforms.java:107-109`, `CompositeRenderer.java:349`, `DepthCopyStrategy.java:60`, `TextureInfoCache.java:33`, `MixinFramebufferBinding`), and Iris's lock mixins are HEAD injects on the same methods. Retargeting keeps both intact and sends only effective GL calls to the sink.
3. **Priority**: all `metallium-iris` mixins on shared hosts use `priority = 1500` (Iris default 1000) so Iris HEAD locks run first.
4. **Capabilities**: `MetalGL.getCapabilities()` returns an all-false-except-claimed `GLCapabilities` (allocated without a context, `Unsafe.allocateInstance` [H]); claim `OpenGL45` so `IrisRenderSystem` picks `DSACore` (`IrisRenderSystem.java:60-62`) and avoids bind-to-edit code paths.
5. **Texture-name space**: `GlNames` allocates ints; `MetalGpuTexture.iris$getGlId()` returns its name; Iris-created textures (colortex, shadow colour, custom, noise) are created through the sink and get `MetalGpuTexture`-compatible `MTLTexture`s with a `GpuTexture` wrapper so they can also be passed to frontend APIs when needed (Iris mixes both: e.g. `RenderTargets.java:72-73` allocates depthtex1/2 via the frontend and copies with GL names).
6. **State realisation** (`MetalGlState`): holds current program, FBO (draw/read), draw-buffer map, texture units [128] x (target,name), sampler units, image units [16], SSBO bindings [16], bound buffers per target, viewport/scissor, blend/depth/colour-mask/cull, polygon mode. A "flush" at draw/dispatch resolves it into Metal: attachment retarget (H1) -> PSO lookup -> bind default-block bytes, UBO slices, textures+samplers, images, SSBOs -> encode.

### 4.3 Why not the alternatives
- **Source fork of Iris**: gives clean seams (rename `IrisRenderSystem` to an interface etc.) but must be rebased on every Iris release (Iris moves with MC snapshots; 26.3 already has reworked pipelines `IrisPipelines`, `ShaderKey`). The GL-bound surface that *needs logic change* is ~9 classes (4.1), small enough for mixins.
- **Build-time bytecode transform of the Iris jar** (same ASM retarget run by Gradle against the released jar, shipping `iris-metallium-<ver>.jar`): fallback if runtime ASM through the mixin plugin proves brittle; redistribution of a modified LGPL jar is permitted but must carry notices; no source maintenance.
- **Explicit-API rewrite of `gl/*`** (replace the whole `gl` package with Metal-native services, ~6k LOC + ~3k call sites per the report): best end state for performance (no state-emulation overhead) but is a fork by another name; keep as phase-2 optimisation of hot paths only (`ProgramUniforms`, composite loop) after first light.
- **Leaf wrappers vs intertwined logic**: leaf (best handled by redirecting their methods): `IrisRenderSystem` (1 class, 68 methods), `GLDebug`, the 22 uniform classes, `GlSampler`, `GlFramebuffer`, `GlImage`, `GlTexture`, `ShaderStorageBuffer(Holder)`, `GlShader`, `ProgramCreator`, `DepthCopyStrategy`. Intertwined with the pass model (cannot be redirected, need hooks or replacement): `MixinGlCommandEncoder`, `MixinGlRenderPipeline`, `MixinShaderManager_Overrides`, `CompositeRenderer` (draw part), `FinalPassRenderer`, `ShadowCompositeRenderer`, `ShadowRenderer` (pass creation), `FullScreenQuadRenderer`, `ExtendedShader.iris$setupState` ordering (uniform upload -> FBO bind).

### 4.4 Risks of the shim seam [SI]
- Redundant state churn: Iris calls `glMemoryBarrier` on every `Program.use()` (`Program.java:28`); sink must make it a no-op unless the previous op wrote RW images/SSBOs.
- TBDR cost of FBO switching: `before/after translucent` FBOs (`ExtendedShader.java:255-259`), per-composite-pass FBOs, shadow FBO. Each switch = end encoder + `Load/Store` of up to 8 colour attachments. Mitigation: batch by FBO identity, `Store`/`DontCare` hints from `RenderTargets` clear info; measure before optimising.
- 16-sampler limit/stage in Metal vs packs with >16 samplers (translation notes 3): constexpr samplers for Iris's fixed `GlSampler` set or argument buffers; decided in `MetalliumShaderPrograms.link`.
- Fast-math: compile pack MSL with safe maths (translation notes 5.1).
- Silent skip of failed pipelines (`MetalDevice` returns `Pending.NULL`, translation notes 5.1) is unacceptable for packs: the shim must raise `ShaderCompileException` with the compile log (Iris shows it in chat).

---------------------------------------------------------------------------------------------------

## 5. Proposed services (Java interfaces)

Package `dev.metallium.iris.service`. All "names" are ints from one `GlNames` space so that unchanged Iris code keeps passing ints. Services are implemented in `dev.metallium.backend` side (they need `MTLDevice`) and exposed through a static `MetalliumIris.services()`; `MetalGL` is only a thin dispatcher.

```java
/** GLSL (post-Iris, GLSL 330+ core, GL semantics) -> glslang -> SPIR-V -> SPIRV-Cross -> MTLLibrary. */
public interface MetalliumShaderPrograms {
    int createShader(ShaderStage stage);                              // glCreateShader  (VERTEX,FRAGMENT,COMPUTE; GEOMETRY/TESS_* => reject at link with a clear message)
    void shaderSource(int shader, String glsl);                       // glShaderSource
    CompileResult compile(int shader);                                // glCompileShader: glslang (Vulkan 1.2 rules-relaxed + auto-map), returns status+info log
    int createProgram(String debugName);                              // glCreateProgram
    void attach(int program, int shader);  void detach(int program, int shader);
    void bindAttribLocation(int program, int index, String name);     // MixinVertexFormat, ProgramCreator:25-26
    LinkResult link(int program);                                     // IO mapping, remap Location by bindAttribLocation, SPIRV-Cross, async MTLLibrary; log text
    ProgramReflection reflection(int program);
    List<BackendRenderPipeline.CreateInfo.Shader> spirv(int program); // for MetalIrisRenderPipeline (H2)
    void delete(int program);
    /** convenience for non-GL-faithful callers (e.g. phase-2 rewrite of composite) */
    int build(Map<ShaderStage, String> sources, String programName, ProgramKind kind) throws ShaderCompileException;
}
public record ProgramReflection(
    int defaultBlockSize, List<UniformMember> defaultBlock,           // name,type(GL enum),arraySize,offset (std140)
    List<SamplerMember> samplers,                                    // name,GL sampler type,isShadow,metalTextureIndex,metalSamplerIndex
    List<ImageMember> images,                                        // name,format,access,metalTextureIndex
    List<BlockMember> uniformBlocks, List<StorageBlock> ssbos,       // name,binding,size
    List<VertexInput> inputs,                                        // name,location,SPIR-V type
    int[] computeLocalSize) {}

/** glGetUniformLocation / glUniform*: CPU shadow of each program's std140 default block. */
public interface UniformBlockWriter {
    int location(int program, String name);                          // -1 if absent; applies MixinUniform aliases
    void setFloat(int program, int location, int components, float... v);
    void setInt(int program, int location, int components, int... v);
    void setMatrix(int program, int location, int cols, int rows, float[] m, boolean transpose);
    void applyDeclaredDefaults(int program);                         // glslang drops `uniform float x = 1.0;` initialisers (translation notes 4.2)
    BufferSlice flush(int program, CommandContext ctx);              // dirty-tracked upload (setBytes <=4 KiB, else ring); bound to vertex+fragment/compute
}

/** Textures, FBO descriptors, copies, clears (RenderTargetService + TextureService). */
public interface RenderTargetService {
    int createTexture(int glTarget);                                 // DSA glCreateTextures
    void texImage(int tex, int target, int level, int internalFormat, int w, int h, int d, int format, int type, @Nullable ByteBuffer data);
    void texParameter(int tex, int pname, int value);                // MIN/MAG/WRAP/MAX_LEVEL/LOD/swizzle/compare (stored; realised as SamplerState + texture view)
    void generateMipmaps(int tex);
    void copyTexSubImage(int dstTex, int level, int x, int y, int srcX, int srcY, int w, int h); // reads current READ fbo colour attachment
    void copyImageSubData(int srcTex, int dstTex, /*region*/ ...);
    void blit(int srcFbo, int dstFbo, /*rects*/ int mask, int filter);
    void clearTexture(int tex, int level, ...);                      // glClearTexImage / TextureManipulationUtil
    int createFramebuffer();
    void attach(int fbo, int attachmentPoint, int tex, int level);
    void setDrawBuffers(int fbo, int[] points);  void setReadBuffer(int fbo, int point);
    int status(int fbo);                                             // GL_FRAMEBUFFER_COMPLETE or reason
    int framebufferFor(GpuTextureView color, @Nullable GpuTextureView depth); // replaces GlDevice.frameBufferCache (MixinRenderTarget:61)
    FramebufferState describe(int fbo);                              // input to H1
    void clearBuffer(int fbo, int bufferKind, int drawBuffer, float[]/int[] value);
    int glNameOf(GpuTexture t);   GpuTexture wrap(int glName);
}

public interface SamplerService {                                    // GlSampler, glBindSampler(s)
    int create();  void parameter(int sampler, int pname, int value);  void bind(int unit, int sampler);  void destroy(int sampler);
    MTLSamplerState resolve(int sampler, int boundTextureName);      // merges texture params when no sampler object bound
}

/** Everything that today is GlStateManager/glEnable/glBlend*/glDepth*/glColorMask/glViewport/glScissor/glPolygonMode. */
public interface PipelineStateService {
    PipelineStateKey snapshot();                                     // used by H1/H3/H6 to key PSO + depth-stencil + raster state
    void enableBlend(int attachment, boolean on); void blendFuncSeparate(int attachment, int srcRgb, int dstRgb, int srcA, int dstA);
    void colorMask(int attachment, int mask); void depthMask(boolean on); void depthFunc(int func);
    void cull(boolean on); void polygonMode(int mode);               // POINT unsupported
    void viewport(int x, int y, int w, int h); void scissor(boolean on, int x, int y, int w, int h);
}

/** Replaces CompositeRenderer.java:330-332, FinalPass, Shadow composite, CenterDepth, ColorSpace draws. */
public interface PassEncoder {
    void retarget(AttachmentOverride a);                             // H1 realisation
    void setFullscreenQuad(GpuBuffer quad);                          // FullScreenQuadRenderer.bind
    void drawFullscreenQuad();                                       // glDrawElements(TRIANGLES, 6) with current shim state
    void drawShim(DrawParams p);                                     // custom-pass draw (H4): program/state/textures from shim
    void flushResources(int program, CommandContext ctx);            // default block + UBO slices + textures/samplers + images + SSBO
}

public interface ComputeDispatcher {                                 // ComputeProgram
    void dispatch(int program, int x, int y, int z);                 // MTLComputeCommandEncoder; local size from reflection
    void dispatchIndirect(int program, int bufferName, long offset);
    void memoryBarrier(int glBits);                                  // -> encoder boundary / fence / no-op (3.4 risk)
}
public interface StorageBufferService {                              // ShaderStorageBuffer(Holder), indirect buffers
    int create(); void storage(int buf, long size, int flags); void clear(int buf, long off, long size, byte value);
    void bindBase(int target, int index, int buf); void delete(int buf);  long vramBytes();
}
public interface ImageBindingService {                               // glBindImageTexture
    void bind(int unit, int tex, int level, boolean layered, int layer, int access, int glFormat);
}
public interface DeviceCapabilities {
    boolean supportsCompute(); boolean supportsSsbo(); boolean supportsImageLoadStore(); boolean supportsBufferBlending(); boolean supportsTessellation();
    int glInteger(int pname); String glString(int pname); String glStringi(int pname, int index);
    long vramBytes();
}
public interface DebugGroups { void push(int id, String label); void pop(); void name(int kind, int name, String label); }
```

`MetalGL` mapping (appendix A lists every function): program/shader fns -> `MetalliumShaderPrograms`; `glUniform*`/`glGetUniformLocation` -> `UniformBlockWriter`; texture/FBO/blit/clear fns -> `RenderTargetService`; sampler fns -> `SamplerService`; `glEnable/glBlend*/glDepth*/glColorMask/glViewport/glScissor/glCullFace/glPolygonMode` -> `PipelineStateService`; `glDispatchCompute*/glMemoryBarrier` -> `ComputeDispatcher`; `glBindBufferBase/glBufferStorage/glClearBufferSubData` -> `StorageBufferService`; `glBindImageTexture` -> `ImageBindingService`; `glDrawElements`/`glDrawArrays` -> `PassEncoder.drawShim`; `glGet*` -> `DeviceCapabilities`; KHR_debug -> `DebugGroups`.

---------------------------------------------------------------------------------------------------

## 6. Staged implementation (first visible result soonest)

Stage 0 (precondition, outside this document): Metallium backend renders vanilla and Sodium chunks on Metal (`M/*` exists; Sodium `WindowMixin/GlSurfaceMixin/GlStateManagerMixin` neutralised on Metal).

### Stage 1 - Iris loads on Metal, shaders OFF (no crash, Iris UI usable) - ~1-2 days
Visible: game boots with Iris installed; Iris keybinds/menu work; "no shader pack" renders exactly like plain Metallium.
Crash points in boot order and fixes [V unless noted]:
1. `RenderSystem#initRenderer` RETURN -> `MixinRenderSystem.java:25` -> `GLDebug.reloadDebugState`, `IrisRenderSystem.initRenderer` (`GL.getCapabilities()` `:60-72`), `IrisSamplers.initRenderer` -> `SamplerLimits` (`GlStateManager._getInteger`), `Iris.onRenderSystemInit` (`Iris.java:133-137`, `PBRTextureManager.init()`). Fix: `metallium-iris` mixins: `IrisRenderSystem.initRenderer` HEAD-cancel + set `dsaState` (accessor), `GLDebug.*` no-ops, `@WrapOperation` on the 2 `GL.getCapabilities()` in `Iris.onRenderSystemInit`; sink answers `glGetIntegerv` limits.
2. First texture access: `MixinAbstractTexture.java:28` / `MixinTextureAtlas:50` / `MixinReloadableTexture:17` call `iris$getGlId()`; Metal textures extend `BaseGpuTexture` and would hit `AssertionError("Why.")` (`MixinGpuTexture2:11`). Fix: `MixinMetalGpuTexture implements GpuTextureInterface` + `GlNames`.
3. `GlStateManager` use by Iris GUI (`IrisButton.java:38-39`, `OldImageButton.java:117`) and `VanillaRenderingPipeline.beginLevelRendering` (`_glUseProgram(0)`, `:44`): vanilla `GlStateManager` methods would call real LWJGL -> ASM retarget to `MetalGL` no-ops (stage-1 `MetalGL` is a ~40-method stub that only tracks state).
4. Per frame `MixinLevelRenderer.java:130` `IrisRenderSystem.backupAndDisableCullingState` (no GL) fine; `setPolygonMode` only for wireframe key.
5. `FeatureFlags` must report compute/SSBO/images/tess/bufferblend = false (so a pack requiring them shows Iris's unsupported screen instead of crashing).
Metallium APIs needed: `GlNames`, `MetalGL` stub, `MixinMetalGpuTexture`, `DeviceCapabilities` (static answers). Spikes: option E of 3.3; confirm HEAD-cancel pre-empts Iris RETURN injectors; confirm ASM retarget through `preApply` for classes with empty mixins.
Exit test: launch, open Iris pack screen, toggle shaders on with a pack -> `Iris.handleException` falls back to vanilla pipeline cleanly (`Iris.java:648-670`) instead of crashing.

### Stage 2 - composite/deferred/final passes render on the vanilla scene - ~2-3 weeks
Choice made for speed: do **not** override gbuffer programs yet. Mixin `IrisRenderingPipeline.shouldOverrideShaders()` (`:1259-1261`) -> `false` (Metallium flag `metallium.iris.stage<3`), so vanilla/Sodium draw into the main target as today; at `finalizeLevelRendering` HEAD (`:1086`) copy main colour into `colortex0` main texture (`RenderTargets.get(0).getMainTexture()`; `TextureService.copySub`), then `compositeRenderer.renderAll(); finalPassRenderer.renderFinalPass()` run as Iris wrote them and write back to main colour. `depthtex0` is already the main depth; `depthtex1/2` come from `copyPreTranslucentDepth` (`:1069`) unchanged. Packs that are composite-only (post-processing: tonemap, bloom, FXAA/vignette, DOF if depth only) display.
Iris classes exercised (all on the shim except the X ones): `ShaderPack`/`TransformPatcher` (unchanged, produce GLSL), `ProgramBuilder`, `ProgramCreator`, `GlShader`, `ProgramUniforms`+`gl/uniform/*`, `ProgramSamplers`, `GlSampler`, `GlFramebuffer`, `RenderTargets`/`RenderTarget`/`DepthTexture`, `ClearPass(Creator)`, `CustomTextureManager`, `NoiseTexture`, `CenterDepthSampler`, `CompositeRenderer` (X: draw), `FinalPassRenderer` (X: custom pass), `FullScreenQuadRenderer` (X), `DepthCopyStrategy`, `IrisDefines/StandardMacros`.
Metallium APIs: `MetalliumShaderPrograms` (full: compile+link; glslang relaxed, `LayoutTransformer`-equivalent cross-stage location assignment, y-flip fix-ups per translation notes 6), `UniformBlockWriter`, `RenderTargetService`, `SamplerService`, `PipelineStateService`, `PassEncoder` (retarget, fullscreen quad, `drawShim`), H1 (retarget), H4 (custom pass; `MetalRenderPass implements RenderPassInterface`), H5. Formats: RGBA8/16F/32F, R*, RG*, RGB10_A2, R11G11B10F first; 3-component formats padded.
Constraint check: only vertex+fragment programs; reject `.gsh/.tcs/.tes/.csh` with the Iris feature-missing path.
Exit test: a simple post pack (e.g. one with `composite.fsh` + `final.fsh` only) shows its effect; frame times logged; `iris.features.required` of the pack reports correctly.

### Stage 3 - gbuffers via Sodium (terrain) and vanilla pipelines - ~3-4 weeks
Remove the stage-2 flag. Classes: `MixinShaderManager_Overrides` (Metal body, H2), `IrisPipelines` (unchanged; Sodium detection `:227-242`), `ShaderCreator` (+`link`), `ExtendedShader`, `FallbackShader` (+ `ShaderSynthesizer` GLSL), `PartialShader`, `IrisBindings`, `MixinGlProgram` tables -> `IrisBindingTable`, `MixinVertexFormat`, `ShaderMap/ShaderLoadingMap`, `BlendModeOverride`/`BlendModeStorage`/`DepthColorStorage`, `IrisSamplers.addGbufferOrShadowSamplers`, `DepthCopyStrategy` (`RenderTargets.copyPreTranslucentDepth`), `HandRenderer`/`MixinLevelRenderer` translucent wrap (`MixinLevelRenderer.java:266-280`, closes and recreates a vanilla pass: new encoder boundary on Metal).
Metallium APIs: H2 (`createIrisPipeline`, `MetalIrisRenderPipeline`), H3 (`beforeDraw` calls `iris$setupBindings` + `iris$setupState`), H1 retarget to `writingToBeforeTranslucent/AfterTranslucent` FBOs (MRT up to 8), `IrisBindingTable` (UBOs `DynamicTransforms/Projection/Globals/Fog` + Sodium `u_Globals`, push constants `iris_SodiumPushConstants`, texel buffer `u_SectionTimeInfo` as AUX sampler binding 3), PSO keyed by actual attachment formats, per-attachment blend (`glEnablei/glBlendFuncSeparatei`), integer vertex attributes (`mc_Entity` uint, `at_midBlock` snorm: `MetalCrossShaderCompiler` already registers uint8/16 attrib formats `M/MetalCrossShaderCompiler.java:143-193`), `multiDrawIndexed` base-vertex (needed by Sodium anyway). Sodium side is untouched Iris mixins (`compat/sodium/mixin/*` have no GL).
Exit test: pack with only gbuffers_terrain/basic + composite (e.g. a minimal "vanilla-like" pack) renders terrain through Iris programs into colortex, composited to screen; then entities/sky/clouds via vanilla-pipeline overrides.

### Stage 4 - shadow pass - ~2 weeks
Classes: `ShadowRenderer` (3 pass creations L526,602,620), `ShadowRenderTargets` (D32F `shadowtex0/1`, shadowcolorN), `ShadowCompositeRenderer`, `MixinGlCommandEncoder` viewport/scissor/FBO logic -> H1 `viewportOverride/scissorOverride` while `ShadowRenderingState.areShadowsCurrentlyBeingRendered()`, `MixinGlRenderPipeline` cull/depth-flip -> H6, `RenderSystem.setProjectionMatrix` (frontend, unchanged) via `IrisRenderSystem.setShadowProjection`, Sodium shadow scope (`MixinRenderSectionManagerShadow`, `MixinDefaultChunkRenderer`, `MixinUniformData` - pure Sodium/frontend).
Metallium APIs: depth-compare samplers (`SamplerService`: `GL_TEXTURE_COMPARE_MODE/FUNC` -> `MTLSamplerState.compareFunction`, shadow samplers `depth2d` + `sample_compare` via SPIRV-Cross), `RenderTargetService` depth-only FBO (no colour attachment: `FrontendCommandEncoder.createRenderPass` allows depth-only), `TextureService.copy` for shadowtex1 (`ShadowRenderTargets.java:184`), mipmaps for `shadowcolor` (`ShadowCompositeRenderer.java:173`).
Exit test: pack with `shadow.vsh/fsh` + `shadowcomp` shows shadows; resolution up to 4096+.

### Stage 5 - compute, images, SSBO, indirect - ~3 weeks
Classes: `ComputeProgram`, `ProgramImages`, `ImageBinding`, `GlImage`, `ImageClearPass`, `ShaderStorageBuffer(Holder)`, `FilledIndirectPointer`, `IrisRenderingPipeline.createSetupComputes` (`:599`), `CompositeRenderer`/`FinalPassRenderer`/`ShadowCompositeRenderer` compute loops, `FeatureFlags` flipped true.
Metallium APIs: `ComputeDispatcher` (compute encoder; local size from SPIR-V `LocalSize`; barriers = encoder boundaries or `MTLFence`; `allowConcurrentCompute`), `StorageBufferService`, `ImageBindingService` (RW textures, MSL >= 3.1 for image atomics), `MetalliumShaderPrograms.build(.., COMPUTE)`.
Exit test: pack using `composite_a.csh`, `image.*`, `bufferObject.*` (e.g. a compute-based bloom or SSBO-driven exposure).

### Stage 6 - deferred / known gaps
Geometry (`.gsh`) and tessellation packs: rejected (SPIRV-Cross has no GS/isolines, translation notes 3); DH compat; mipmapped/other-format edge cases; wireframe (`setPolygonMode(POINT)` unsupported); GL debug output; pack-compile disk cache (post-Iris GLSL hash, translation notes 6); parallel compile via worker pool replacing `KHR_parallel_shader_compile`.

---------------------------------------------------------------------------------------------------

## 7. Module layout and build

`metallium-iris/` (Fabric mod `metallium_iris`, LGPL-3.0; `depends`: `iris >=1.11.6 <1.12`, `metallium`, `minecraft ~26.3-`):
- `dev.metallium.iris.spi` (hooks, records) - also published for `dev.metallium.backend` (backend depends on the SPI only; Iris is never on the backend classpath).
- `dev.metallium.iris.service` (interfaces above) + `...impl` (Metal-side implementations; may live in the main mod and be bound via ServiceLoader).
- `dev.metallium.iris.gl.MetalGL` (generated), `dev.metallium.iris.asm.RetargetPlugin` (IMixinConfigPlugin), `dev.metallium.iris.mixin.*` (Iris/vanilla/Metal-class mixins, priority 1500).
- compile against Iris + Sodium as `modCompileOnly`; no Iris/Sodium code copied; README credits row for Iris = "runtime dependency (LGPL-3.0), adapter in a separate LGPL module".

---------------------------------------------------------------------------------------------------

## Appendix A - GL surface to implement in `MetalGL` (from grep over `common/src/main/java`)

`GlStateManager` methods used by Iris (count): `_bindTexture`28 `_activeTexture`26 `_glBindFramebuffer`23 `_glGetUniformLocation`22 `_glUseProgram`13 `_getInteger`13 `_viewport`10 `_deleteTexture`10 `_glUniform1i`9 `_glBindAttribLocation`8 `_genTexture`7 `_pixelStore`6 `_glBindBuffer`6 `_scissorBox`5 `_disableScissorTest`5 `_colorMask`5 `glGetProgrami`3 `glDeleteShader`3 `glDeleteProgram`3 `_glGenBuffers`3 `_glBindVertexArray`3 `_getTexLevelParameter`3 `_enableDepthTest`3 `_enableBlend`3 `_disableCull`3 `_depthMask`3 `_clear`3 `glLinkProgram`2 `glGetShaderi`2 `glGenFramebuffers`2 `glCreateShader`2 `glCreateProgram`2 `glCompileShader`2 `glAttachShader`2 `_glFramebufferTexture2D`2 `_disableBlend`2 `_blendFuncSeparate`2 and singletons (`glShaderSource`, `glGetProgramInfoLog`, `glBlendFuncSeparate`, `_glGenVertexArrays`, `_glDeleteFramebuffers`, `_glBufferSubData`, `_getString`, `_enableScissorTest`, `_enableCull`, `_drawElements`, `_disablePolygonOffset`, `_disableDepthTest`, `_depthFunc`) - 50 distinct.

`IrisRenderSystem` statics used from outside (count): `texParameteri`53 `bindTextureForSetup`9 `texImage2D`7 `samplerParameteri`7 `disableBlend`7 `memoryBarrier`6 `uniformMatrix3fv`5 `generateMipmaps`4 `framebufferTexture2D`4 `createTexture`4 `bindTextureToUnit`4 `bindBufferBase`4 `uniformMatrix4fv`3 `uniform4f`3 `texParameterf`3 `getGlDevice`3 `detachShader`3 `copyTexSubImage2D`3 `copyTexImage2D`3 `clearColor`3 and 49 singletons/pairs (`dispatchCompute`, `dispatchComputeIndirect`, `blitFramebuffer`, `bindImageTexture`, `genSampler`, `bindSamplerToUnit`, `unbindAllSamplers`, `copyImageSubData`, `clearBufferSubData`, `bufferStorage`, `getActiveUniform`, `getVRAM`, `supports*`, ...) - 68 distinct.

Raw LWJGL functions (distinct ~110): program `glCreateProgram glAttachShader glDetachShader glLinkProgram glUseProgram glDeleteProgram glGetProgrami/iv glGetProgramInfoLog glGetShaderInfoLog nglShaderSource glBindAttribLocation glGetAttribLocation glGetUniformLocation glGetActiveUniform glGetUniformBlockIndex glUniformBlockBinding`; uniforms `glUniform1f/2f/3f/4f/1i/2i/3i/4i glUniformMatrix3fv/4fv`; textures `glBindTexture glBindTextureUnit glBindTextures glCreateTextures glTexImage1D/2D/3D glTexParameteri/f/iv glTextureParameteri/f/iv glGetTexParameteri glGetTextureParameteri glGenerateMipmap glGenerateTextureMipmap glCopyTexImage2D glCopyTexSubImage2D glCopyTextureSubImage2D glCopyImageSubData glClearTexImage`; samplers `glGenSamplers glDeleteSamplers glBindSampler glBindSamplers glSamplerParameteri/f/iv`; FBO `glCreateFramebuffers glFramebufferTexture2D glNamedFramebufferTexture glNamedFramebufferDrawBuffers glNamedFramebufferReadBuffer glDrawBuffers glReadBuffer glCheckFramebufferStatus glBlitFramebuffer glBlitNamedFramebuffer glClearBufferfv/iv/uiv glClearNamedFramebufferfv/iv/uiv glClearColor`; buffers `glGenBuffers glCreateBuffers glBindBuffer glBindBufferBase glBufferData glNamedBufferData glBufferStorage glClearBufferSubData glDeleteBuffers glVertexAttribPointer glEnableVertexAttribArray glVertexAttrib4f`; image/compute `glBindImageTexture glBindImageTextureEXT glDispatchCompute glDispatchComputeIndirect glMemoryBarrier`; state `glEnable glDisable glEnablei glDisablei glBlendFuncSeparateiARB glPolygonMode glDrawElements glReadPixels`; query `glGetInteger glGetIntegerv glGetFloatv glGetStringi`; debug `glDebugMessageControl(ARB) glDebugMessageCallback(ARB/AMD) glDebugMessageEnableAMD glObjectLabel glPushDebugGroup glPopDebugGroup`; `glMaxShaderCompilerThreadsKHR/ARB`.

## Open questions / not verified
1. HEAD-cancel pre-empting another mixin's RETURN injector (3.3 A) and ASM retarget through `preApply` on classes with empty mixins - [SI], first stage-1 spike.
2. Option E (reflective plugin wrap) feasibility and Fabric/Mixin load order - [H].
3. `Unsafe.allocateInstance(GLCapabilities)` as the fake-capabilities strategy - [H]; alternative is `@WrapOperation` on each `GL.getCapabilities().GL_x` read.
4. How `ImmediateState.safeToMultiply` (`MixinGlCommandEncoder.java:98,120`) interacts with the H1 override - not traced.
5. Whether Sodium 0.9.2's chunk `RenderPass` sets uniforms by names that `IrisBindingTable` can alias 1:1 (needs a read of `ShaderChunkRenderer.BIND_GROUP`, only the Iris-side use `ExtendedShader.java:139` was read).
6. Per-frame cost of the GL-state emulation vs explicit rewrite - needs measurement after stage 3.
7. DH + Iris on Metal (DH's renderer is GL) - out of scope; behaviour with DH installed must be forced to non-DH init.
8. Vanilla `Minecraft.java:495-501` rewrites `DEFAULT -> OPENGL` after an unclean shutdown; harmless for Iris's gate but confirm no other Iris code branches on `preferredGraphicsBackend`.
