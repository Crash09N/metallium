# Iris shader packs on Metal: shader-language & translation feasibility

Scope: GLSL (shader pack) -> Iris preprocess/transform -> SPIR-V -> MSL -> MTLLibrary, for Metallium (Fabric, MC 26.3, Apple Silicon).
Refs: Iris @ 671a5c877 (26.3, 1.11.6), glsl-transformer @ 9d26f0f (3.0.0-pre3), SPIRV-Cross @ 300681b, glslang @ 5cf91e7,
metallum @ 4da72f5, metal-jvm @ ccb7789, mcopt @ 7b1d0fb.
Paths below are relative to /home/user/research. Labels: **V** = Verified in code, **SI** = Strongly inferred, **H** = Hypothesis, **U** = Unverified / external knowledge (Apple/Khronos docs, not checkable in these repos).

---------------------------------------------------------------------------------------------------

## 0. TL;DR (decision-relevant)

1. **Iris is a hard OpenGL consumer, not a shader-language problem only.** It compiles via raw `glCreateShader/glCompileShader/glLinkProgram`
   (`Iris/common/src/main/java/net/irisshaders/iris/gl/shader/GlShader.java:30-50`, `pipeline/programs/ShaderCreator.java:186-200`),
   sets loose uniforms with `glUniform*`, binds samplers by `glGetUniformLocation` name lookup, images with `glBindImageTexture`, SSBOs with
   `glBindBufferBase`, dispatches compute with `glDispatchCompute(Indirect)` (`gl/IrisRenderSystem.java`; 87 Iris files import `org.lwjgl.opengl`,
   95 import `com.mojang.renderpearl.backend.opengl`). **V**. Translation of the GLSL is necessary but only ~1/3 of the work; the other parts are
   reflection-driven uniform/sampler/image binding and the GL state model.
2. **Iris's transformer output is GLSL >=330 *core* for a GL driver**: profile forced to CORE (or left core/none) and version bumped to >=330
   (`pipeline/transform/TransformPatcher.java:146-203`), legacy built-ins replaced (`gl_ModelViewMatrix`, `gl_Vertex`, `texture2D`, `gl_FragData[i]`,
   `ftransform`, `gl_Fog`, `attribute/varying` ...). But it still emits **loose non-opaque uniforms** (e.g. `uniform mat3 iris_NormalMat;`,
   `uniform float iris_currentAlphaTest;` and every pack uniform like `uniform float frameTimeCounter;`), **in/out without `location`**, and
   samplers/images without `binding`. That is legal GL, illegal Vulkan-GLSL. **V**
3. **glslang can ingest that output into SPIR-V** using Vulkan target + **"Vulkan rules relaxed"** (`TShader::setEnvInputVulkanRulesRelaxed`,
   CLI `-R`): loose uniforms are folded into one std140 block `gl_DefaultUniformBlock` (`glslang/MachineIndependent/ParseHelper.cpp:9590-9645`,
   `:286-292`, `:296-300`), `atomic_uint` -> SSBO, `gl_VertexID/gl_InstanceID` retargeted (`Initialize.cpp:9230-9234`), plus
   `setAutoMapBindings/setAutoMapLocations` (`glslang/Public/ShaderLang.h:498-499`). Raw pack GLSL with **compatibility-profile built-ins cannot go
   to SPIR-V** (legacy built-ins are only declared when `spvVersion.spv == 0`: `Initialize.cpp:1797`, `:6087`, `:4821`) -> Iris's transformer (or an
   equivalent) is mandatory in front of glslang. **V**
4. **SPIRV-Cross MSL has no geometry-shader support at all** (entry-point switch falls to `"unknown"`: `SPIRV-Cross/spirv_msl.cpp:14681-14721`),
   no isoline tessellation (`:14693`, `:14707`), no doubles (`:17517-17518` "Currently unsupported"), no 64-bit atomics (`:11656`), no
   `OriginLowerLeft` handling (zero hits in `spirv_*.cpp`), and image atomics need MSL 3.1 for arrays/natively (`:19377-19383`, `:2189-2193`). **V**
5. **Precedent exists for the SPIR-V->MSL half**: Metallum (`metallum/src/main/java/com/metallum/render/MetalCrossShaderCompiler.java`) and mcopt
   (`mcopt/metal/src/main/java/mcopt/metal/MetalPipeline.java:302-326`) both run LWJGL `spvc` (SPIRV-Cross C API) on vanilla's **already-SPIR-V**
   `SpvModule`s, MSL 4.0 / 3.0, `FLIP_VERTEX_Y`, `MSL_TEXTURE_BUFFER_NATIVE`, decoration bindings. Neither contains a GLSL->SPIR-V compiler: the GLSL
   front-end is vanilla's (SI: MC 26.3 compiles its GLSL to SPIR-V itself and hands `SpvModule` to backends). metal-jvm is pure FFM Metal bindings
   (MIT), no glslang/SPIRV-Cross. **V** (for what each repo contains)
6. **Depth convention is already solved by Iris itself** when the device reports `isZZeroToOne()`: Iris rewrites `gl_Position.z` to MC 26.x reverse-Z
   [0,1], inverts depth-texture reads (`1.0 - x`), `gl_FragCoord.z`, `gl_FragDepth` (`transformer/DepthTransformer.java:62-128`). Metal must
   therefore **not** also enable SPIRV-Cross `fixup_clipspace`. **V**
7. **Y-orientation is NOT solved anywhere in the chain**: GL lower-left `gl_FragCoord`, `dFdy` sign, viewport/scissor origin and winding need
   Metallium-side handling (vertex Y flip + `gl_FragCoord.y` rewrite + `dFdy` negation + front-face flip). **V** that nothing does it; fix is **SI**.
8. **glsl-transformer is AGPL-3.0** (`glsl-transformer/LICENSE`, `glsl-transformer/glsl-transformer/build.gradle:127-130`); Iris itself is LGPL-3.0 and
   ships `LICENSE-DEPENDENCIES` warning about it. It only prints GLSL. Reusing Iris's transformer *in-process via Iris* is fine licensing-wise for a
   separate mod that doesn't bundle it; bundling/forking it brings AGPL obligations. **V** (license text) / legal interpretation **U**.

---------------------------------------------------------------------------------------------------

## 1. Iris: how pack GLSL is transformed before compile

### 1.1 Pipeline stages (Verified)

| Step | Code | Notes |
|---|---|---|
| Source discovery | `shaderpack/programs/ProgramSet.java:107-124` (`.vsh .gsh .tcs .tes .fsh`), `:153` (`.csh`); `shaderpack/include/ShaderPackSourceNames.java:76-96` | Geometry and tess sources are first-class inputs. |
| `#include` graph | `shaderpack/include/IncludeGraph.java`, `IncludeProcessor.java` | Text level. |
| Options / `#define` injection | `shaderpack/ShaderPack.java:317` -> `JcppProcessor.glslPreprocessSource` | Environment defines (MC_VERSION, IS_IRIS, ...) via `IrisDefines`. |
| C preprocessor (JCPP, anarres) | `shaderpack/preprocessor/JcppProcessor.java:12-71` | `#version`/`#extension` replaced by markers (`:28-29`) then hoisted to top by `GlslCollectingListener` so `#extension` lines appear only once and first (Mesa strictness). Output has **no** preprocessor directives except `#version/#extension/#pragma`. |
| Comment/const directive scan | `shaderpack/parsing/CommentDirectiveParser.java`, `ConstDirectiveParser.java:21-113`, `DispatchingDirectiveHolder.java` | DRAWBUFFERS/RENDERTARGETS, `const int shadowMapResolution = ...` etc. are parsed **textually from the preprocessed source** (not from the AST). |
| AST transform (glsl-transformer) | `pipeline/transform/TransformPatcher.java:97-224` | `EnumASTTransformer` over VERTEX/GEOMETRY/TESS_CONTROL/TESS_EVAL/FRAGMENT/COMPUTE, LRU cache 400 (`:91`). Throws if any non-#extension/#pragma directive remains (`:80-89`) and if pack references `iris_`/`irisMain`/`moj_import` (`:92`, `:129-137`). |
| GL compile/link | `pipeline/programs/ShaderCreator.java:186-200`, `gl/shader/GlShader.java:30-50`, `gl/program/ProgramBuilder.java:42-50` | Raw GL. |

### 1.2 Version/profile handling (Verified, `TransformPatcher.java:105-116, 140-203`)
- `#version N` found by regex `#version\s+(\d+)` and fed to the lexer (`:93`, `:108-113`); missing `#version` -> exception.
- COMPUTE: profile forced `CORE`, `CommonTransformer.transform(..., core=true)` (`:146-148`). Version not raised (assumes >=400 per comment; GL requires 430 for compute).
- Non-compute, "core path" taken if `profile == CORE || (version >= 150 && profile == null) || isLines` (`:152`): version raised to >=330 (`:154-156`), then
  `CompositeCoreTransformer` / `SodiumCoreTransformer` / `VanillaCoreTransformer`, plus `CompatibilityTransformer.transformFragmentCore` for FS (`outColor0..7` -> `layout(location=N)`; `CompatibilityTransformer.java:636-700`).
- Otherwise ("compatibility path", i.e. `#version 120`, `#version 150 compatibility`, etc.): version raised to >=330 and **profile forced to CORE** (`:178-181`), then
  `CompositeTransformer` / `SodiumTransformer` / `VanillaTransformer` / `DHTerrainTransformer` / `DHGenericTransformer` (`:183-202`).
- Then for all: `GlintTransformer`, `TextureTransformer` (custom-texture renames), `CommonTransformer.transformDepth`, `CompatibilityTransformer.transformEach` (`:205-209`);
  grouped: `GlintTransformer.transformGrouped`, `CompatibilityTransformer.transformGrouped` (in/out mismatch repair), optionally `LayoutTransformer` (only if
  `IrisLimits.VK_CONFORMANCE`, which is `false`: `gl/IrisLimits.java:14`; **dead code today**), `CommonTransformer.transformDepthPosition` (`:213-221`).
- => **Output contract: GLSL 330..460, core profile (or none for >=150 which means core), GL semantics.** No GLSL ES, no Vulkan GLSL.

### 1.3 What the transformer rewrites (Verified)

`CommonTransformer.transform` (`transformer/CommonTransformer.java:319-473`), used by every compatibility-path patcher:
- `gl_FogFragCoord` -> `iris_FogFragCoord` (+ VS out / FS in) (`:327-337`); `gl_FrontColor` -> dead global `iris_FrontColor` (`:339-347`, comment: "incorrect ... bare minimum").
- FS: `gl_FragColor` -> `gl_FragData[0]` (`:352-354`); `gl_TexCoord` -> `in vec4 irs_texCoords[3]` (`:356-359`); `gl_Color` -> `in vec4 irs_Color` (`:361-364`).
- FS: **`gl_FragData[<int literal>]` -> `iris_FragData<i>` declared `layout(location = i) out vec4`** (`:64-72`, `:366-391`). Non-literal index is *not* matched (left as `gl_FragData[...]`).
- Alpha test injected at end of FS main for compatibility path when `AlphaTest != ALWAYS` (`:394-398`, `uniform float iris_currentAlphaTest;`). (In-shader alpha test, no fixed-function needed.)
- `attribute`->`in`, `varying`->`out`/`in` (`:303-317`, `:401-403`).
- `texture`/`gcolor` sampler names -> `gtexture` (`:405-435`).
- `gl_Fog` -> struct `irisInt_Fog` built from loose uniforms `iris_FogDensity/Start/End/Color` (non-vanilla) (`:440-455`).
- Legacy sampling functions renamed: `texture2D/3D`->`texture`, `texture2DLod/3DLod`->`textureLod`, `*Proj`, `*Grad(ARB)`, `texelFetch2D/3D`, `textureSize2D`;
  `shadow2D`/`shadow2DLod` -> `vec4(texture(...))` (`:457-472`). TODO notes "Add similar functions for all legacy texture sampling functions" (`:457`):
  **`texture1D*`, `textureCube*`, `texture2DProjLod`, `shadow2DProj`, `texture2DRect` etc. are NOT renamed** -> they would fail under glslang SPIR-V (see 4.3).
- `CompatibilityTransformer.transformEach`: Sildur water fix, removes `const` on locals initialized from const params, removes unused functions, renames reserved
  identifiers `texture`, `sample` -> `iris_renamed_*` (`CompatibilityTransformer.java:113`, `:301-310`), moves unsized array specifiers.
- `CompatibilityTransformer.transformGrouped`: adds missing `out` decls for FS/GS `in`s and fixes type mismatches between stages (`:372-630`).

`VanillaTransformer` (`transformer/VanillaTransformer.java:13-375`) — entities/particles/sky/etc. on vanilla `RenderPipeline`s:
- Injects std140 UBOs mirroring MC 26.3 vanilla blocks: `iris_Fog`, `iris_DynamicTransforms {ModelViewMat, TextureMat, ColorModulator, ModelOffset}`,
  `iris_Projection {iris_ProjMat}`, `iris_Globals {CameraBlockPos, GlintAlpha, CameraOffset, GameTime, ScreenSize, MenuBlurRadius, UseRgss}` (`:30-75`).
- `gl_MultiTexCoord0/1/2/3` -> `iris_UV0`/`iris_UV2`/`iris_LightUV`/`mc_midTexCoord`; `gl_MultiTexCoord4-7` -> constant (`:76-118`).
- `gl_Color` -> `iris_Color * ColorModulator` variants (`:120-144`); `gl_Normal` -> `iris_Normal` (`:146-163`).
- `gl_TextureMatrix[0]` -> `iris_transforms.TextureMat`, `[1]` -> loose `uniform mat4 iris_LightmapTextureMatrix` (`:165-170`); **`gl_TextureMatrix[2+]` untouched**.
- `gl_NormalMatrix`, `gl_ModelViewMatrixInverse`, `gl_ProjectionMatrixInverse` -> loose uniforms `iris_NormalMat` (mat3), `iris_ModelViewMatInverse`, `iris_ProjMatInverse` (`:174-184`).
- `ftransform()` defined in-shader (`:189-192`). `gl_Vertex` -> `vec4(iris_Position,1)` or cloud-generated (`:194-341`).
- **Lines: wide lines emulated in the vertex shader** by running `irisMain()` twice and offsetting in screen space by `iris_LineWidth` using `gl_VertexID % 2` (`:194-228`). (Good for Metal: no wide-line HW.)
- Clouds: builds geometry from `uniform isamplerBuffer CloudFaces` with `texelFetch` (`:229-338`) -> **texel buffer**.
- `gl_ModelViewMatrix` -> `(iris_transforms.ModelViewMat * _iris_internal_translate(ModelOffset))`, `gl_ProjectionMatrix` -> `iris_undoRevZ(iris_ProjMat)` (`:346-374`).

`SodiumTransformer` (`transformer/SodiumTransformer.java:19-370`) — terrain:
- Replaces inputs with Sodium 0.9 compact vertex decoding: `a_Position (uvec2)`, `a_TexCoord (uvec2)`, `a_Color`, `a_LightAndData (uvec4)`, `iris_Normal (uint)` (`:251-255`),
  oct-decoded normals, diamond-encoded tangents (`:166-242`), `u_Globals` std140 block (`:100-114`), `iris_SodiumPushConstants` std140 block (`:136-143`),
  `uniform isamplerBuffer u_SectionTimeInfo` for chunk fade (`:130-134`, `:231`) -> **texel buffer**.
- `mc_Entity` -> `in uint mc_Entity` decode (`:260-319`), `mc_midTexCoord` -> `in uvec2` (`:322-369`), `at_tangent`, `at_midBlock` (`CommonTransformer.java:250-301`).
- Integer vertex attributes everywhere (`uvec2/uvec4/uint`) -> MSL needs matching `MTLVertexFormat` (Metallum already handles uint8/16 conversions: `MetalCrossShaderCompiler.java:143-193`).

`CompositeTransformer` (`transformer/CompositeTransformer.java:32-85`): `gl_TextureMatrix[0..7]`->identity, `gl_MultiTexCoord0`->`in vec2 UV0`, `gl_Vertex`->`in vec3 Position`,
`gl_ProjectionMatrix` -> fixed [0,1]->[-1,1] matrix, `centerDepthSmooth` -> `texture(iris_centerDepthSmooth, vec2(0.5)).r` (`CompositeDepthTransformer`, `:247-276`).

`DepthTransformer` (`transformer/DepthTransformer.java`) — reverse-Z / clip range (all paths, `TransformPatcher.java:208,221`):
- Depth sampler set: `depthtex0/1/2, gdepthtex, dhDepthTex*, vxDepthTex*` (`:35-36`); every read via ~90 texture functions (`:37-60`) is wrapped `vec4(1.0 - x)`
  (`textureGather` inverted per-component) incl. specialization of user functions taking depth samplers (`:173-253`).
- Non-shadow FS: `gl_FragCoord` -> `vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)`; `gl_FragDepth = v` -> `= 1.0 - (v)` (`:78-89`).
- Last vertex-like stage `main` wrapped: `gl_Position.z = 0.5*(w - z)` (zZeroToOne, non-shadow), `= 0.5*(z + w)` (shadow, zZeroToOne), `= -z` (GL, non-shadow)
  (`:92-128`); geometry: inserted before each `EmitVertex/EmitStreamVertex` (`:100-116`).
- `iris_undoRevZ(p)`/`iris_toMinusOneToOne(p)` projection-matrix fixups depend on `RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()` (`:130-164`).
- => Iris already targets **[0,1] reverse-Z** when the backend says so. Metallum's DeviceInfo passes `true` as 4th ctor arg (`metallum/.../MetalDevice.java:246-257`);
  that it is the `isZZeroToOne` slot is **H** (positional; Mojang record not available).

`LayoutTransformer` (`transformer/LayoutTransformer.java:155-345`, gated off by `VK_CONFORMANCE=false`): assigns `layout(location=N)` to every non-layout `out`
in stage k and the same N to the matching-by-name `in` in stage k+1 (VS->TCS->TES->GS->FS). Only handles `BuiltinNumericTypeSpecifier` declarations (no structs,
no blocks, no arrays-of-struct), logs a WARN per declaration (`:242`, `:305`). **This is exactly the cross-stage location assignment SPIR-V/MSL needs** (SPIRV-Cross
links VS->FS by location only). Turning it on is "Requires Iris mod" (it's `static final`).

### 1.4 Extensions/versions Iris allows/forces (Verified)
- No whitelist: `#extension` lines are hoisted verbatim (`JcppProcessor.java:18-29`), glsl-transformer's grammar parses `#extension name : behavior`
  (`glsl-transformer/src/main/antlr/GLSLParser.g4:81`). Iris never adds `#extension` itself. Version floor 330 forced; no ceiling.
- Common pack extensions (`GL_EXT_gpu_shader4`, `GL_ARB_shader_texture_lod`, `GL_ARB_gpu_shader5`, `GL_ARB_shader_image_load_store`, `GL_ARB_shader_bit_encoding`,
  `GL_ARB_shading_language_packing`, `GL_NV_gpu_shader5`, `GL_AMD_gpu_shader_half_float`, `GL_KHR_shader_subgroup_*`) pass through; GL driver decides. For glslang see 4.4.

### 1.5 Feature inventory Iris supports (evidence)

| Feature | Iris evidence | Notes for Metal |
|---|---|---|
| Compute shaders (`*.csh`, `composite_a..z`, `setup`, `begin`, `prepare`, `deferred`, `shadowcomp`...) | `ProgramSet.java:153`; `ShaderPackSourceNames.java:96`; `gl/program/ComputeProgram.java:18-56` (local size via `GL_COMPUTE_WORK_GROUP_SIZE`); `IrisRenderSystem.java:320-324, 512` (`glDispatchCompute(Indirect)`); `FeatureFlags.COMPUTE_SHADERS` (`features/FeatureFlags.java:16`) | Local size must come from SPIR-V reflection (`LocalSize` exec mode). Work groups from `const ivec3 workGroups`/`const vec2 workGroupsRender` (`shaderpack/parsing/ComputeDirectiveParser.java:10-44`). `allowConcurrentCompute` (`ShaderProperties.java:209`). |
| Indirect dispatch | `ShaderProperties.java:374-382` (`indirect.<pass> = <ssbo> <offset>`), `IrisRenderSystem.java:512` | Metal `dispatchThreadgroups(indirectBuffer:)` — note Metal's indirect args are threadgroup counts (same as GL). |
| Image load/store (`image.<name>` custom images, `colorimgN`, `shadowcolorimgN`) | `ShaderProperties.java:514-571` (<=16 custom images, 1D/2D/3D, relative size, clear flag); `samplers/IrisImages.java:21-77`; `IrisRenderSystem.java` `glBindImageTexture`; `FeatureFlags.CUSTOM_IMAGES` (`:14`) gated on `supportsImageLoadStore` (`IrisRenderSystem.java:303-305`) | MSL `access::read_write`; format-tier restrictions (U: Apple Silicon = RW texture tier 2). |
| SSBO (`bufferObject.N = size [relative scaleX scaleY]`) | `ShaderProperties.java:386-443` (index <=12, "higher reserved"); `gl/buffer/ShaderStorageBuffer*.java`; `IrisRenderSystem.java:299-301`; `FeatureFlags.SSBO` (`:22`) | Metal `device` buffers; `glClearBufferSubData` (`IrisRenderSystem` GL43C) -> blit fill. |
| Atomics | SSBO / image atomics via pack GLSL (no Iris code needed) | 64-bit atomics infeasible via SPIRV-Cross (`spirv_msl.cpp:11656`). |
| Custom textures: PNG, raw 1D/2D/3D/rect (`texture.<stage>.<name>`, `customTexture.<name>`) | `ShaderProperties.java:445-512`; renames via `TextureTransformer.java:174-223` (types `TEXTURE_1D/RECTANGLE/2D/3D`) | 1D -> `texture_1D_as_2D` or native texture1d (no mips in Metal, **U**); RECTANGLE -> 2D with unnormalized coords (`MSLSamplerCoord PIXEL`, `spirv_msl.hpp:118-123`). |
| MRT `gl_FragData[i]` / `layout(location)` outs | `CommonTransformer.java:366-391`, `CompatibilityTransformer.java:636-700` (outColor0..7) | Metal max 8 color attachments (U) — same as macOS GL `GL_MAX_DRAW_BUFFERS`. |
| `/* DRAWBUFFERS:0123 */`, `/* RENDERTARGETS: 0,1,15 */` | `shaderpack/parsing/CommentDirective.java:34-37`, `properties/ProgramDirectives.java:54-70, 132-147` (DRAWBUFFERS = single digits; RENDERTARGETS = comma list) | Host-side: map FS location i -> colortex index -> `colorAttachments[i]`. |
| `const` options in source | `ConstDirectiveParser.java:21-113` (types int/float/vec4/bool/string); `PackShadowDirectives.java:301-353` (`shadowMapResolution`, `shadowDistance`, `shadowMapFov`, `shadowNearPlane/FarPlane`, `voxelDistance`, `shadowcolorNFormat/Clear/ClearColor`, `shadowHardwareFiltering[N]`, `generateShadowMipmap`, `shadowtexNMipmap`, `shadowtexNearest`...); `PackDirectives.java:271-292` (`noiseTextureResolution`, `sunPathRotation`, `ambientOcclusionLevel`, `*Halflife`); `PackRenderTargetDirectives.java:96-123` (`colortexNFormat`, `colortexNClear`, `colortexNClearColor`, legacy `gcolor/gdepth/...`, `GAUX4FORMAT`); `ProgramDirectives.java:108-111` (`colortexNMipmapEnabled`) | Pure host-side; format list below drives Metal format mapping. |
| Render target formats | `gl/texture/InternalTextureFormat.java:13-88` (RGBA/R8..RGBA32UI, *_SNORM, RGB*, RGBA2/4, R3_G3_B2, RGB5_A1, RGB565, RGB10_A2(UI), R11F_G11F_B10F, RGB9_E5) | See §5.3 for Metal mapping (RGB 3-component formats have no Metal equivalent). |
| Depth formats | `gl/texture/DepthBufferFormat.java:10-14` (DEPTH16, DEPTH32F, DEPTH24_STENCIL8, DEPTH32F_STENCIL8) | D24S8 not on Apple Silicon (U) -> map to D32F_S8. |
| Mipmaps | `colortexNMipmapEnabled`; `glGenerateMipmap`/`glGenerateTextureMipmap` (`IrisRenderSystem` GL32C/ARBDirectStateAccess) | Metal `generateMipmaps` blit (color-renderable+filterable only, U). |
| Clears | `colortexNClear(Color)`, `image` clear flag (`gl/image/ImageClearPass.java`), `glClearBuffer{f,i,ui}v` | Metal load action clear or compute/blit clear for images. |
| shadowtex0/1 (+HW/DH variants), shadowcolor0..N, shadowcolorimg | `samplers/IrisSamplers.java:122-184`; `FeatureFlags.HIGHER_SHADOWCOLOR`, `SEPARATE_HARDWARE_SAMPLERS` (`:12-13`) | Depth compare samplers -> MSL `depth2d` + `sample_compare` (constraints §3). |
| noisetex | `IrisSamplers.java:115` (`GlSampler.LINEAR_REPEAT`), `texture.noise` (`ShaderProperties.java:156`) | trivial. |
| depthtex0/1/2 | `IrisSamplers.java:222-235` (`GlSampler.NEAREST`), copies via `gl/texture/DepthCopyStrategy.java:46-103` (`glCopyTexSubImage2D` / blit FB / `glCopyImageSubData`) | Metal blit `copy(from:to:)` same-format depth->depth OK (U). |
| Per-buffer blending (`blend.<pass>.<buffer>`) | `ShaderProperties.java:314-372`; `ShaderCreator.java:156-162`; `IrisRenderSystem.supportsBufferBlending` (`:335-337`); `glBlendFuncSeparateiARB`, `glEnablei/glDisablei` | Native in Metal (per-attachment blend in PSO). |
| Alpha test override (`alphaTest.<pass>`) | `ShaderProperties.java:280-312`; injected in shader (`CommonTransformer.java:394-398`, `SodiumCoreTransformer.java:112`) | No fixed-function needed. |
| Polygon mode | `IrisRenderSystem.java:494-499` (`glPolygonMode`) | Metal `setTriangleFillMode(.lines)` only; no POINT mode (U). |
| Texture swizzle (intensity) | `IrisRenderSystem.java:545-553` (`ARBTextureSwizzle`), `ExtendedShader.java:61,171` | Metal texture swizzle (macOS 10.15+, U) or shader swizzle. |
| Geometry shaders (`*.gsh`) | `ProgramSet.java:110`; passed to `TransformPatcher.patch*` and `ShaderCreator.link` (`:192`); `DepthTransformer.java:100-116` handles EmitVertex | **No Metal stage** (§3, §6). |
| Tessellation (`*.tcs`, `*.tes`) | `ProgramSet.java:117-120`; `FeatureFlags.TESSELLATION_SHADERS` (`:17`) gated by `supportsTesselation` (`IrisRenderSystem.java:428-430`); `ExtendedShader` gets `tessControl != null || tessEval != null` (`ShaderCreator.java:170`) | Metal tess = compute pre-pass + post-tess vertex fn; SPIRV-Cross supports (MoltenVK style). |
| Feature flags requested by packs (`iris.features.required/optional`) | `ShaderProperties.java:605-606`; `features/FeatureFlags.java:11-25` | Metallium can report `false` for unsupported hardware features -> Iris shows "unsupported" (if Iris consults a Metallium-provided capability; today it queries GL caps). |
| gl_Layer / layered rendering | No Iris-specific code; only pack GLSL (GS or `GL_ARB_shader_viewport_layer_array`) | MSL `render_target_array_index` (`spirv_msl.cpp:18618-18619`). |
| Derivatives, textureLod/Grad/Gather, bit ops | Plain GLSL; Iris only renames legacy names (`CommonTransformer.java:457-472`) | Translate (with `dFdy` sign caveat §5.1). |
| Double precision | Nothing in Iris; pack GLSL could use `double` (GL 4.0) | **Infeasible** via SPIRV-Cross MSL (`spirv_msl.cpp:17517-17518`); Metal has no fp64 (U). |

---------------------------------------------------------------------------------------------------

## 2. glsl-transformer (Verified)

- What: ANTLR4 GLSL lexer/parser (`glsl-transformer/src/main/antlr/GLSLLexer.g4`, `GLSLParser.g4`) + typed AST (`ast/node/**`), query indices
  (identifier/prefix/node indices used heavily by Iris), templates/matchers (`ast/query/match/*`, `ast/transform/Template`), and printers
  (`ast/print/ASTPrinter.java`, `SimplePrinter`, `IndentingPrinter`, `CompactPrinter`). Version 3.0.0-pre3 (`glsl-transformer/build.gradle:22`), matching Iris's
  dependency (`Iris/common/build.gradle.kts:63`, `Iris/fabric/build.gradle.kts:78` `implementAndInclude`).
- Profiles modelled: CORE / COMPATIBILITY / ES (`ast/node/Profile.java:8-11`).
- **Output dialect: GLSL only.** No HLSL/MSL/SPIR-V emitter; the printers are token printers for the GLSL grammar. It can be used to *produce Vulkan-flavoured
  GLSL* (add `layout(set,binding)`, wrap uniforms in blocks, add locations) because those are just GLSL syntax — that is what `LayoutTransformer` sketches.
- **License: GNU AGPL v3** (`glsl-transformer/LICENSE` line 1; POM metadata `glsl-transformer/build.gradle:127-130`). Iris is LGPL-3.0 (`Iris/LICENSE`) and
  ships `Iris/LICENSE-DEPENDENCIES`: "glsl-transformer is licensed under the GNU Affero General Public License version 3. This may affect your ability to
  distribute Iris." Implication for Metallium: calling Iris's transformer at runtime (Iris JIJ-bundles glsl-transformer) avoids Metallium redistributing it;
  forking/bundling glsl-transformer or writing a derived transformer makes Metallium AGPL-encumbered (legal reading **U**).

---------------------------------------------------------------------------------------------------

## 3. SPIRV-Cross MSL backend: concrete limitations (Verified unless noted)

Options struct: `SPIRV-Cross/spirv_msl.hpp:297-560` (`CompilerMSL::Options`). Defaults: `platform = macOS`, `msl_version = 1.2` (`:306`) — callers must raise it.

| Topic | Code | Finding / implication |
|---|---|---|
| **Geometry shaders** | `spirv_msl.cpp:14679-14721` (`func_type_decl` entry-type switch) | No `ExecutionModelGeometry` case -> `entry_type = "unknown"` -> invalid MSL. No GS emulation anywhere in `spirv_msl.cpp` (grep "geometry" hits only ray-query/IFB). MoltenVK likewise lacks GS (U). **Infeasible via this path.** |
| Tessellation | `:14684-14708`; options `vertex_for_tessellation` (`spirv_msl.hpp:435`), `multi_patch_workgroup`, `raw_buffer_tese_input`, `tess_domain_origin_lower_left` | TCS -> `kernel`, VS-for-tess -> `kernel`, TES -> `[[patch(triangle|quad, N)]] vertex`. Requires MSL>=1.2; **isolines throw** (`:14693`, `:14707`). Host must implement the MoltenVK-style multi-pass (VS kernel -> TCS kernel -> tess factors buffer -> drawPatches). High effort. |
| Mesh/task | `:14713-14717` | `[[mesh]]`/`[[object]]` supported (MSL 3) — only relevant as a *hand-written* GS replacement target. |
| Image atomics | `:19377-19383` (pre-MSL-3.1 -> emulation), `:2181-2193` ("Cannot emulate array of storage images with atomics. Use MSL 3.1"), `:11649-11764` (native path; cube needs MSL 4.0 `:11756`), `:15721-15726` (`device atomic_*` buffer arg), `r32ui_linear_texture_alignment` (`spirv_msl.hpp:308`) | MSL >=3.1 (macOS 14+, U) gives native `texture.atomic_*` for 2D/3D/array. Below 3.1, emulation requires the image to be a linear texture aliased to a buffer (host work). Use MSL 3.1+. |
| 64-bit atomics | `:11656` | Throws. Infeasible. |
| Doubles | `:17517-17518` `type_name = "double"; // Currently unsupported`; `:12740` | Emits `double` -> Metal compile error. Infeasible (would need fp64 emulation pass). |
| SSBO / runtime arrays | `:13782` (OpArrayLength needs rich descriptor if in arg buffer); `buffer_size_buffer_index` (`spirv_msl.hpp` Options) + `needs_buffer_size_buffer()` | `.length()` on SSBO runtime arrays requires host-provided size buffer. Packing failures possible: `:5092-5455` ("Cannot represent buffer block correctly in MSL", std140 wide arrays/ArrayStride 3). |
| Argument buffers | `argument_buffers` (`spirv_msl.hpp:350`, needs MSL 2.0: `spirv_msl.cpp:1843`), `argument_buffers_tier` (`:365`), `:1573` (unsized descriptor arrays need Tier2), `:13996` (writable images Tier1 iOS) | Optional. Discrete binding is simpler; arg buffers lift per-stage slot limits (Metal: 31 buffers/128 textures/16 samplers per stage, U). |
| Combined image-samplers | `:15634` (`ep_args += "Smplr"`), `:17700-17776` | Each GLSL `sampler2D` becomes `texture2d<T> name [[texture(n)]]` + `sampler nameSmplr [[sampler(m)]]`. **16-sampler-per-stage Metal limit (U)** collides with Iris programs that declare >16 samplers (colortex0-15 + depthtex0-2 + shadowtex0/1 + shadowcolor0/1 + noisetex + gtexture/lightmap/normals/specular + custom). Mitigations: `remap_constexpr_sampler(_by_binding)` (`spirv_msl.hpp:781-786`, `MSLConstexprSampler` `:215`) to inline the few distinct Iris sampler states (`GlSampler.NEAREST/LINEAR/LINEAR_REPEAT/...`, `IrisSamplers.java:115-235`), or argument buffers. |
| Depth compare | `:8322-8330` (`spvDepthCast`), `:13083-13123` (non-constant 0.0 `gradient()`/`bias()` with `sample_compare` throws), `sample_dref_lod_array_as_grad` (`spirv_msl.hpp` Options) | `sampler2DShadow` -> `depth2d<float>` + `sample_compare`. `textureLod` with non-zero/non-const LOD on shadow samplers -> throw/limitations. Compare func lives in `MTLSamplerDescriptor` / constexpr sampler (`MSLSamplerCompareFunc`, `spirv_msl.hpp:152-160, 224`). |
| 1D textures | `texture_1D_as_2D` (`spirv_msl.hpp:344`), `:10351-10352` (ImageQueryLod not on 1D), `:11746`, `:12857`, `:12956` | Native `texture1d` has no mips (U); enable `texture_1D_as_2D` for packs' 1D LUTs that use LOD/derivatives. |
| 3D / arrays | `:17752-17758` (arrays of textures need MSL 2.0; arrays-of-arrays unsupported), `:17829` (MS arrays need 2.1) | OK at MSL 3.x. |
| Texel buffers | `texture_buffer_native` (`spirv_msl.hpp:393`), `:17795-17800` | Native `texture_buffer` needs MSL 2.1; Metallum already sets it (`MetalCrossShaderCompiler.java:233-236`). Required for Sodium `u_SectionTimeInfo` and vanilla `CloudFaces` (`isamplerBuffer`). |
| gl_Layer / ViewportIndex | `:18618-18623` (`render_target_array_index`, `viewport_array_index`), `:14217` (ViewportIndex needs Metal 2.0), `:18495-18509` | Supported from vertex stage. |
| Framebuffer fetch / subpass input | `use_framebuffer_fetch_subpasses` (`spirv_msl.hpp:381`), `:15716` (Mac needs MSL 2.3), `:14474-14477` (`[[color(n)]]`) | Only reachable from Vulkan `subpassInput`; GL packs don't use it. Potential tool for emulating GL logic ops/blend modes Metal lacks. |
| Clip/cull distance | `:3062-3074`, `:3295-3307`, `:4036`, `:4105`, `enable_clip_distance_user_varying` (`spirv_msl.hpp:420`), `:9810-9812` | `[[clip_distance]]` supported; cull distance maps via same path. |
| Point size | `enable_point_size_builtin` (`spirv_msl.hpp:331`), `:13848-13851` | `[[point_size]]` emitted; must be disabled for non-point topologies (Metal validation, U). Metallum doesn't touch it. |
| Discard | `:1755-1764` (`discard_fragment()`, helper-invocation tracking), `:10729` (demote semantics need MSL 2.3), `manual_helper_invocation_updates` (`spirv_msl.hpp:489`), `check_discarded_frag_stores` (`:497`) | GL `discard` translated; Metal side-effect/early-Z quirks have opt-in workarounds (`force_fragment_with_side_effects_execution`). |
| Invariant | `:9347`, `:18610`; `invariant_float_math` (`spirv_msl.hpp:384`) | `[[invariant]]` on position also requires `MTLCompileOptions.preserveInvariance` (exposed in metal-jvm `MTLCompileOptions.java:61-63`). |
| Half precision | `:17512` (`half`), `:12740` | GLSL desktop precision qualifiers become `RelaxedPrecision` decorations; MSL backend emits `float` (no automatic `half`). Fine. |
| Fragment output width | `pad_fragment_output_components` (`spirv_msl.hpp:375`, comment "Fragment output in MSL must have at least as many components as the render pass") | Packs commonly write `vec3`/`float` into RGBA targets -> enable. Integer vs float output/attachment type must match. |
| Subgroups | `:16020-16186` (ballot needs Metal 2.1), `:17926-17939`, `emulate_subgroups`, `fixed_subgroup_size` | OK on Apple Silicon at MSL >= 2.1. |
| Cube grad bug | `agx_manual_cube_grad_fixup` (`spirv_msl.hpp:526`) | Enable on Apple GPUs for `textureGrad` on cubemaps. |
| R/W texture coherence | `readwrite_texture_fences` (`spirv_msl.hpp:512`, default true) | Keeps GL-like `imageLoad` after `imageStore` coherence within an invocation. |
| Clip-space / Y | `fixup_clipspace` (`spirv_glsl.hpp:175-180`; emitted at `spirv_msl.cpp:13855-13857`), `flip_vert_y` (`spirv_glsl.hpp:182-183`; `spirv_msl.cpp:13886-13887`), `emulate_reversed_depth_viewport` (`spirv_msl.hpp:345`) | Leave `fixup_clipspace` OFF (Iris already remaps z). `flip_vert_y` ON (Metallum/mcopt precedent). |
| Fragment origin | grep `OriginLowerLeft|OriginUpperLeft` in `spirv_*.cpp` -> **0 hits** (only `main.cpp:498-499` CLI printing) | SPIRV-Cross never compensates GL lower-left `gl_FragCoord`. Must be fixed in GLSL (or a SPIR-V pass). |
| Derivatives | `:9945-9948` (`OpDPdy` -> `dfdy` 1:1) | No sign compensation for flipped Y. |
| gather offsets | `:6110-6116`, `:6836-6842` (`spvGatherConstOffsets` emulation) | `textureGatherOffsets` emulated (4 gathers). |
| Query LOD | `:10334` (needs MSL 2.2) | `textureQueryLod` OK at MSL >= 2.2. |
| Sparse | `:11252` | not supported (packs don't use). |

---------------------------------------------------------------------------------------------------

## 4. glslang: GLSL (Iris output) -> SPIR-V (Verified)

### 4.1 Compatibility profile & legacy built-ins
- `#version ... compatibility` with any SPIR-V target: `ShaderLang.cpp:675-676` logs "#version: compilation for SPIR-V does not support the compatibility profile"
  (note: it does **not** set `correct=false`, so whether it fails depends on later errors — **H**). Irrelevant for Iris output because Iris forces CORE
  (`TransformPatcher.java:181`).
- Legacy built-ins are only declared when not generating SPIR-V: `texture2D` & friends guarded by `spvVersion.spv == 0` (`Initialize.cpp:1792-1799`), fixed-function
  matrix state (`gl_ModelViewMatrix` ...) by `spvVersion.spv == 0 && IncludeLegacy(...)` (`:6087-6092`, `IncludeLegacy` `:536-540`), `ftransform()` by
  `spvVersion.vulkan == 0 && IncludeLegacy` (`:4815-4821`). => **raw pack GLSL cannot be compiled to SPIR-V**; only post-Iris GLSL has a chance.
- `gl_FragData` remains declared for non-ES `version < 420` even in SPIR-V mode (`Initialize.cpp:12163-12170`); no `EbvFragData` handling found in
  `SPIRV/GlslangToSpv.cpp` (grep 0 hits) -> leftover non-literal `gl_FragData[i]` writes likely produce a location-less output (**H**, test needed).
- Version floors: Vulkan SPIR-V needs desktop >=140, OpenGL SPIR-V needs >=330 (`ShaderLang.cpp:678-688`) — satisfied by Iris's >=330 bump.
  Compute needs >=420 (`:627-633`) — Iris doesn't bump compute versions (`TransformPatcher.java:146-148`); packs declare 430 anyway.

### 4.2 Loose uniforms / bindings / locations
- `TParseContext::transparentOpaqueCheck` (`ParseHelper.cpp:5489-5503`):
  - Vulkan target, not relaxed: `vulkanRemoved("non-opaque uniforms outside a block")` -> error (`:5499-5500`; `Versions.cpp:1660-1664`).
  - OpenGL target (`-G`, `EShClientOpenGL`): "non-opaque uniform variables need a layout(location=L)" unless `getAutoMapLocations()` (`:5501-5502`).
- **Vulkan relaxed mode** (`TShader::setEnvInputVulkanRulesRelaxed()`, `ShaderLang.h:584`; CLI `-R` `StandAlone.cpp:1003-1004`; C API `glslang_c_interface.cpp:390`):
  - `vkRelaxedRemapUniformVariable` (`ParseHelper.cpp:9590-9645`): loose non-opaque uniforms grown into the global uniform block; `layout(location)` on them
    ignored with warning (`:9601-9604`); **uniform initializers dropped with warning** (`:9606-9609`) — GL honors `uniform float x = 1.0;` defaults, so such packs
    would read 0 unless the host writes defaults (Iris custom uniforms assign every frame; pack-declared-but-Iris-unknown uniforms would change behaviour).
  - Block name default `gl_DefaultUniformBlock` (`ParseHelper.cpp:286-292`), overridable `setGlobalUniformBlockName/Set/Binding` (`ShaderLang.h:516-519`,
    `ShaderLang.cpp:1893`); std140 column-major default (`ParseHelper.cpp:296-300`); storage override via `addBlockStorageOverride` (`StandAlone.cpp:1461-1466`).
  - `atomic_uint` -> members of a coherent SSBO (`ParseHelper.cpp:9626-9640`; atomicCounter* remapped to plain atomics `Initialize.cpp:1963-1972`, `:11351-11420`).
  - `gl_VertexID`/`gl_InstanceID` declared and **retargeted to `gl_VertexIndex`/`gl_InstanceIndex`** (`Initialize.cpp:6470-6474`, `:9230-9234`). Semantic delta:
    GL `gl_InstanceID` excludes base instance, Vulkan `InstanceIndex` includes it (Khronos spec, U). Iris line widening uses `gl_VertexID % 2` — parity-safe only if base vertex is even (**H**).
  - `packed` layout silently ignored (`ParseHelper.cpp:7370-7377`); function-call / member-access remaps for GL-only forms (`:1072-1077`, `:1418-1426`).
- Auto mapping: `setAutoMapBindings`, `setAutoMapLocations`, `setShiftBinding*`, `setResourceSetBinding`, `setUniformLocationBase` (`ShaderLang.h:488-503`).
  Caveat: per-shader auto-mapped in/out locations are not guaranteed to match across stages by name -> do cross-stage assignment in GLSL (Iris `LayoutTransformer`)
  or link all stages in one `TProgram` and use the IO mapper (**H**: glslang's `TGlslIoMapper` matches by name when linking; not verified here).
- Vulkan target sets fragment `OriginUpperLeft` (`ShaderLang.cpp:919-920`); OpenGL target keeps lower-left — but SPIRV-Cross MSL ignores both (§3).

### 4.3 Practical glslang configuration for Iris output (SI)
`EShClientVulkan` (1.2+) + `EShTargetSpv_1_5` + `setEnvInputVulkanRulesRelaxed()` + `setAutoMapBindings(true)` + `setAutoMapLocations(true)` (only after Iris layout
assignment) + `EShMsgRelaxedErrors` optional (`ShaderLang.h:257`) + one `TProgram` per Iris program (all stages linked so the default uniform block layout is
identical across stages, `linkValidate.cpp:522` merges `vulkanRelaxed`). Leftover legacy calls Iris doesn't rename (`texture1D*`, `textureCube*`, `shadow2DProj`,
`texture2DRect`, `texture2DProjLod`, `gl_TextureMatrix[2+]`, `gl_LightSource`, `gl_FrontLightModelProduct`...) will be undeclared identifiers -> need an
extra Metallium transform pass (glsl-transformer via Iris, or regex/AST shim).

### 4.4 Extensions
Unknown extension with `: require` -> **error** "extension not supported" (`Versions.cpp:1196-1201`); with `enable/warn/disable` -> warning (`:1202-1206`).
glslang knows ~247 `E_GL_*` (`Versions.h`), including `GL_ARB_shader_image_load_store`, `GL_ARB_shader_storage_buffer_object`, `GL_ARB_shading_language_packing`,
`GL_ARB_shader_bit_encoding`, `GL_ARB_texture_gather`, `GL_ARB_gpu_shader5`, `GL_ARB_shader_texture_lod`, `GL_NV_gpu_shader5`, `GL_AMD_gpu_shader_half_float`,
`GL_KHR_shader_subgroup_*` (`Versions.h:127-170`, `Versions.cpp:196-293`). **`GL_EXT_gpu_shader4` is not in the table** -> `#extension GL_EXT_gpu_shader4 : require`
fails; `: enable` only warns (its features are core at >=330 anyway). Mitigation: rewrite `require`->`enable` for a known-harmless list.

---------------------------------------------------------------------------------------------------

## 5. Precedent: Metallum / metal-jvm / mcopt (Verified)

### 5.1 Metallum `MetalCrossShaderCompiler`
- Input is **vanilla `SpvModule`** from `BackendRenderPipeline.CreateInfo` (`MetalCrossShaderCompiler.java:53-65`) — the GLSL->SPIR-V step is done by MC 26.3's
  renderpearl frontend (**SI**; MC source not available). Metallum's build declares no glslang/shaderc/spvc dependency (`metallum/build.gradle`), yet imports
  `org.lwjgl.util.spvc.*` (`:16-20`) -> **SI** MC 26.3 ships LWJGL `spvc` (and likely `shaderc`) for its Vulkan backend.
- Options (`:218-247`): `MSL_PLATFORM=MACOS`, **`MSL_VERSION=4.0`** (`:30`, `:225-228`), `ENABLE_DECORATION_BINDING` (`:229-232`), `TEXTURE_BUFFER_NATIVE` (`:233-236`),
  **`FLIP_VERTEX_Y`** for all stages (`:237-240`), disables frag-depth builtin when no depth target (`:241-246`).
- Integer vertex inputs: registers `msl_shader_input` with UINT8/UINT16 formats from the pipeline's attribute formats (`:143-193`).
- Bindings: SPIR-V `binding` = index into vanilla `UniformDescription` list; buffers and textures numbered in separate Metal index spaces; sampler index ==
  texture index (`:281-325`). Push constants -> next buffer index (`:263-270`, `:315-324`). Stage masks from active-resource reflection (`:116-133`, `:330-363`).
- Only vertex+fragment supported ("requires both a vertex and a fragment shader", `:63-65`); no compute/tess/geometry path.
- Library compile: `MetalUtilities.newLibrary` -> `device.newLibraryWithSource(source, null)` (`MetalUtilities.java:427-433`): **default `MTLCompileOptions`** (fast-math
  default on in Metal, U) — risky for packs relying on IEEE NaN/Inf behaviour; cache keyed by (msl, entry) (`MetalDevice.java:215-235`). No binary archive cache.
- Pipeline failure -> `Pending.NULL` (`MetalDevice.java:130-147`): silent skip, fine for vanilla, unacceptable for Iris (should surface ShaderCompileException).
- No Iris integration anywhere in Metallum (`grep -ri iris` = 0 hits).

### 5.2 metal-jvm
- Pure Java FFM bindings to Metal (MIT, `metal-jvm/build.gradle.kts` pom `MIT License`); exposes `MTLDevice.newLibraryWithSource(:options:error:)` + async variant
  (`MTLDevice.java:69-72`), `newLibraryWithData`, `MTL4LibraryDescriptor`, `MTLCompileOptions` with `fastMathEnabled/mathMode/languageVersion/preserveInvariance/
  optimizationLevel` (`MTLCompileOptions.java:29-91`). **No glslang/SPIRV-Cross inside.**

### 5.3 mcopt
- Same pattern: vanilla `SpvModule` -> `spvc` MSL 3.0 macOS, `TEXTURE_BUFFER_NATIVE`, `FLIP_VERTEX_Y` (vertex only), push constants remap, optional on-disk MSL
  cache keyed by SPIR-V hash (`mcopt/metal/src/main/java/mcopt/metal/MetalPipeline.java:242-326`). Hand-written MSL override for terrain (`:153-155`).
- mcopt forces OpenGL when Distant Horizons + Iris are present (`mcopt/metal/src/main/java/mcopt/metal/Profile.java:44-64`): practical signal that Iris requires GL today.

---------------------------------------------------------------------------------------------------

## 6. Proposed pipeline: pack GLSL -> Iris transform -> SPIR-V -> MSL

```
pack .vsh/.gsh/.tcs/.tes/.fsh/.csh
  -> [Iris, unchanged] include graph + JCPP (#define options, extension hoist)          (ShaderPack.java:317, JcppProcessor)
  -> [Iris, unchanged] comment/const directive parse (DRAWBUFFERS, formats, shadow...)   (CommentDirectiveParser, ConstDirectiveParser)
  -> [Iris, unchanged] TransformPatcher (core 330+, legacy built-in removal, reverse-Z)    (zZeroToOne=true from Metallium DeviceInfo)
  -> [Iris, needs flag/hook] LayoutTransformer (cross-stage locations)                    (VK_CONFORMANCE gate, IrisLimits.java:14)
  -> [Metallium "MetalGlslFixups" pass, new; ideally an Iris extension point]:
        * rename leftover legacy samplers (texture1D*, textureCube*, shadow2DProj...)
        * gl_FragCoord.y  -> (iris_metal_ViewportHeight - gl_FragCoord.y)  [y-down Metal]
        * dFdy(x) -> -dFdy(x), dFdyFine/Coarse likewise; fwidth unaffected
        * #extension X : require -> enable for glslang-unknown list (GL_EXT_gpu_shader4...)
        * reject/flag: geometry stage, double, 64-bit atomics, isolines
  -> glslang: Vulkan 1.2 client, SPIR-V 1.5, VulkanRulesRelaxed, autoMapBindings,
              one TProgram per Iris program (gl_DefaultUniformBlock std140 shared across stages)
  -> SPIR-V reflection (spvc or glslang reflection): default-block member offsets, sampler/image/SSBO names -> bindings, compute LocalSize
  -> SPIRV-Cross MSL (MSL 3.1+ min for image atomics; Metallum uses 4.0):
        platform macOS, enable_decoration_binding, texture_buffer_native, flip_vert_y (vertex-like last stage),
        fixup_clipspace OFF, pad_fragment_output_components ON, texture_1D_as_2D ON, agx_manual_cube_grad_fixup ON,
        enable_point_size_builtin = (topology == points), readwrite_texture_fences ON,
        constexpr samplers for Iris's fixed GlSampler set OR argument_buffers Tier2 to escape 16-sampler limit,
        vertex_for_tessellation & tess stages only if tess supported.
  -> MTLDevice.newLibraryWithSource(options: languageVersion, fastMath OFF/safe mathMode, preserveInvariance when needed)
  -> PSO creation (color formats from colortexNFormat, per-attachment blend, depth format, winding flipped for y-flip)
  -> Iris uniform/sampler/image/SSBO binding layer re-targeted from glUniform*/glBind* to Metal argument tables  [Requires Iris mod / large mixin surface]
```
Compile cost: Iris packs have dozens-hundreds of programs; glslang+spvc+Metal compile per program at pack load. Add an on-disk cache keyed by post-Iris GLSL hash
(mcopt precedent `MetalPipeline.java:242-300`) and/or `MTLBinaryArchive` (U).

---------------------------------------------------------------------------------------------------

## 7. OpenGL semantics classification

Legend: **Translates** = glslang+SPIRV-Cross handle it; **Emulate** = adapter/shader rewrite needed; **Metal impl** = explicit host-side Metal code; **Infeasible** = no reasonable path.

### 7.1 Shader-language semantics
| Semantic | Class | Reason / evidence |
|---|---|---|
| Legacy built-ins (`gl_ModelViewMatrix`, `gl_Vertex`, `texture2D`, `ftransform`, `gl_FragColor`, `gl_FragData[lit]`) | Translates *after Iris* | Iris rewrites (§1.3); glslang has none of them in SPIR-V mode (§4.1). |
| Remaining legacy (`texture1D*`, `textureCube*`, `shadow2DProj`, `gl_TextureMatrix[2+]`, `gl_LightSource`, non-literal `gl_FragData[i]`) | Emulate | Not handled by Iris (`CommonTransformer.java:457`); add rename pass. |
| Loose uniforms | Emulate | glslang relaxed -> `gl_DefaultUniformBlock` std140 (§4.2); host must pack values by reflected offsets instead of `glUniform*`. |
| Uniform initializers | Emulate | Dropped by glslang relaxed (`ParseHelper.cpp:9606-9609`); host must apply defaults (parse from AST). |
| in/out matching by name | Emulate (Requires Iris mod: flag) | SPIR-V/MSL need locations; Iris `LayoutTransformer` exists but disabled. |
| Sampler binding by name (`glUniform1i` units) | Metal impl | SPIR-V auto-bound; name->index map from reflection; Iris `ProgramSamplers`/`IrisSamplers` rewired. |
| Combined image/sampler, per-texture GL sampler state | Emulate | MSL splits texture+sampler (`spirv_msl.cpp:15634`); Iris uses sampler objects + texture params; Metal sampler-per-binding; 16-sampler limit -> constexpr samplers / arg buffers. |
| Depth compare (`sampler2DShadow`, `shadow2D`) | Translates (with limits) | `depth2d` + `sample_compare`; non-const LOD/grad restrictions (`:13083-13123`). |
| `textureLod/Grad/Gather/GatherOffsets/Offset/texelFetch/textureSize/QueryLod` | Translates | `:6836` gather offsets emulated; QueryLod MSL>=2.2. Cube grad AGX fixup. |
| Derivatives `dFdx/dFdy/fwidth` | Emulate (dFdy sign) | 1:1 mapping (`:9945-9948`); with Y-flipped rendering dFdy sign inverts (SI). |
| `gl_FragCoord` | Emulate | z handled by Iris (`DepthTransformer.java:78-80`); y origin not handled by SPIRV-Cross (0 hits) -> rewrite. |
| `gl_FragDepth` | Translates | Iris inverts (`DepthTransformer.java:81-88`); MSL `[[depth(any)]]`. |
| Clip-space depth [-1,1] vs [0,1] | Translates (Iris does it) | `DepthTransformer.transformPosition` with zZeroToOne=true; keep SPIRV-Cross `fixup_clipspace` off. |
| `discard` | Translates | `discard_fragment()`; Metal side-effect quirks optional. |
| `invariant` | Translates + Metal impl | needs `preserveInvariance` compile option. |
| Bit ops / packing / `floatBitsToInt` / `bitfieldExtract` | Translates | core GLSL 4.x -> SPIR-V -> MSL `extract_bits` etc. (U per function; standard SPIRV-Cross coverage). |
| `double` / `dvec*` | Infeasible | `spirv_msl.cpp:17517-17518`. |
| 64-bit int | Translates (MSL>=2.2) | `:17501-17509`. |
| 64-bit atomics | Infeasible | `:11656`. |
| SSBO atomics (32-bit) | Translates | standard. |
| Image atomics | Translates (MSL>=3.1) | `:19377-19383`; cube needs MSL 4.0 (`:11756`). |
| Image load/store w/o format qualifier | Translates (H) | Metal RW textures typed by pixel format at bind time; tier-2 RW formats (U). |
| Compute shaders | Translates + Metal impl | `kernel`; local size from reflection; dispatch & barriers host-side. |
| Shared memory / barriers | Translates | `threadgroup`, `threadgroup_barrier`. |
| Subgroup ops | Translates | MSL >= 2.1. |
| `gl_VertexID/gl_InstanceID` | Translates (semantic caveat) | Retargeted to Index variants (`Initialize.cpp:9230-9234`). |
| `gl_Layer` / `gl_ViewportIndex` from VS | Translates | `:18618-18623`. |
| `gl_ClipDistance` | Translates | `[[clip_distance]]`. |
| `gl_PointSize` | Translates (configure) | Must disable builtin for non-point PSOs. |
| Geometry shaders | Infeasible via SPIRV-Cross; Native impl only by hand (compute-expansion or Metal mesh shaders) | `:14719-14721` "unknown". GS used by voxelization/shadow tricks in some packs; would require a bespoke GS->compute/mesh compiler. |
| Tessellation (tri/quad) | Native impl (high effort) | SPIRV-Cross support + multi-pass host (§3). |
| Tessellation isolines | Infeasible | `:14693`. |
| Subpass/framebuffer fetch | n/a for GL packs | — |

### 7.2 Fixed-function / state semantics (host side)
| Semantic | Class | Reason |
|---|---|---|
| Y-up window origin / texture memory layout | Metal impl | Use `flip_vert_y` so memory layout matches GL (row 0 = bottom); convert viewport/scissor rects `y' = H - y - h`; present with Y-flip (Metallum present shader already flips: `MetalUtilities.java:47-52`). SI. |
| Front-face winding | Metal impl | Y-flip inverts winding -> set `MTLWinding` opposite of GL `glFrontFace` (SI). |
| Reverse-Z depth func/clear | Metal impl | MC 26.x reverse-Z (Iris `iris_undoRevZ`); depth compare func + clear value 0.0 for main, forward for shadow (shadow path uses 0.5*(z+w)). |
| Per-target blend (`blend.<pass>.<buf>`, `glBlendFuncSeparatei`) | Native (Metal) | Per-attachment in `MTLRenderPipelineColorAttachmentDescriptor`; but baked into PSO -> PSO variants keyed by blend state. |
| Blend mode override / off | Native | same. |
| Alpha test | Translates (in-shader) | Iris injects (`CommonTransformer.java:394-398`). |
| Logic ops (`glLogicOp`) | Infeasible natively / Emulate via framebuffer fetch | Metal has no logic ops (U); Iris doesn't use them (grep 0 hits). |
| Line width > 1 | Translates (Iris VS widening) | `VanillaTransformer.java:194-228`. Metal has no wide lines (U). |
| Polygon mode LINE | Native | `setTriangleFillMode(.lines)`; POINT infeasible (U). |
| Polygon offset | Native | `setDepthBias(_:slopeScale:clamp:)` (U). |
| Stencil | Native | `MTLDepthStencilDescriptor` (front/back). |
| Depth clamp / depth clip | Native | `setDepthClipMode` (U). |
| Color write masks | Native | per attachment `writeMask`. |
| `glGenerateMipmap` | Native (limits) | Blit `generateMipmaps` (filterable color formats only, U). |
| `glCopyTexSubImage2D` / `glCopyImageSubData` / `glBlitFramebuffer` depth copies | Native (limits) | Blit copy same pixel format; depth<->color or scaled blit -> shader pass (U). `DepthCopyStrategy.java:46-103`. |
| `glClearBuffer*`, image clears | Native | load actions / blit fill / compute clear. |
| `glReadPixels` (`IrisRenderSystem` GL32C) | Native | blit to shared buffer + sync. |
| `glMemoryBarrier` | Native | encoder boundaries / `memoryBarrier(scope:)`; Metal hazard tracking for tracked resources. |
| Texture swizzle | Native | `MTLTextureSwizzleChannels` (U). |
| Rectangle textures | Emulate | 2D + pixel-coord sampler (`MSLSamplerCoord PIXEL`). |
| Max 8 MRT | Native (same as macOS GL) | Metal 8 color attachments (U). |

### 7.3 Texture/render-target formats (`InternalTextureFormat.java:13-88`; Metal pixel format availability **U** from Apple docs)
| GL format(s) | Metal | Class |
|---|---|---|
| R8, RG8, RGBA8, R8/RG8/RGBA8_SNORM, R16, RG16, RGBA16, *16_SNORM, R16F..RGBA16F, R32F, RG32F, RGBA32F, all R/RG/RGBA 8/16/32 I/UI | direct (`.r8Unorm`, `.rgba16Snorm`, `.rgba32Float`, ...) | Fully supported (RGBA32F not filterable on some GPUs/blendable limits, U) |
| RGB10_A2, RGB10_A2UI | `.rgb10a2Unorm`, `.rgb10a2Uint` | Fully supported |
| R11F_G11F_B10F | `.rg11b10Float` | Fully supported |
| RGB9_E5 | `.rgb9e5Float` (render/write capability GPU-family dependent, U) | Not verified |
| RGB8, RGB16, RGB16F, RGB32F, RGB*I/UI, RGB*_SNORM (3-component) | none | Adapter: allocate RGBA equivalent + alpha swizzle to 1 for sampling, mask alpha writes; 33% (or more) memory; raw uploads need repack |
| RGB565, RGB5_A1, RGBA4 | `.b5g6r5Unorm`, `.bgr5A1Unorm`/`.a1bgr5Unorm`, `.abgr4Unorm` (Apple GPUs only, U) | Adapter (channel order/swizzle) |
| RGBA2, R3_G3_B2 | none | Adapter -> RGBA8 |
| DEPTH16, DEPTH32F, DEPTH32F_STENCIL8 | `.depth16Unorm`, `.depth32Float`, `.depth32Float_stencil8` | Fully supported |
| DEPTH24_STENCIL8 | not on Apple Silicon (U) | Adapter -> `.depth32Float_stencil8` |
| PixelFormat BGR/BGRA(_INTEGER), packed PixelTypes (`PixelType.java:19-34`) for raw custom textures | n/a (upload path) | Adapter: CPU repack on upload |

---------------------------------------------------------------------------------------------------

## 8. Feature-level compatibility matrix (Iris pack features on Metal via proposed pipeline)

| Feature | Status | Technical reason |
|---|---|---|
| gbuffers_* / shadow / composite / deferred / final VS+FS programs | Adapter | Iris transform + glslang relaxed + SPIRV-Cross; plus GLSL fixups (§6). |
| Legacy GLSL 1.10-1.50 packs | Adapter | Iris upgrades to 330 core; residual legacy calls need extra pass. |
| Core-profile packs (`#version 330+ core`, `vaPosition`, `modelViewMatrix`...) | Adapter | `*CoreTransformer` paths exist (`SodiumCoreTransformer.java:107-158`, `VanillaCoreTransformer`). |
| Loose uniforms (all Iris built-in uniforms + custom `uniform.` / `variable.`) | Requires Iris mod | Iris uploads via `glUniform*` per location; needs a uniform-buffer writer keyed by reflected offsets. |
| Samplers (colortex, depthtex, shadowtex/color, noisetex, custom) | Requires Iris mod + Native impl | Name->binding reflection; GL texture ids replaced by Metal textures; 16-sampler limit handling. |
| MRT + DRAWBUFFERS/RENDERTARGETS | Native impl | Attachment mapping in render pass descriptor; ping-pong (flip) logic stays in Iris. |
| colortexNFormat (4-component & packed) | Fully supported | direct Metal formats. |
| colortexNFormat 3-component (RGB8/RGB16F/RGB32F...) | Adapter | pad to RGBA + swizzle. |
| colortexNClear / ClearColor | Native impl | load actions. |
| Mipmapped buffers (`colortexNMipmapEnabled`) | Native impl | blit generateMipmaps after pass. |
| Shadow map (depth + hardware filtering) | Adapter | `depth2d` + compare sampler; reverse/forward depth conventions from Iris. |
| shadowcolorN | Native impl | as colortex. |
| Custom textures PNG / raw 2D / 3D | Native impl | upload + format repack. |
| Custom textures raw 1D / RECTANGLE | Adapter | `texture_1D_as_2D`; pixel-coord sampler. |
| Noise texture | Fully supported | trivial texture. |
| Compute shaders (incl. workGroupsRender, indirect dispatch) | Native impl | kernel translation is reliable; dispatch/barrier/UAV binding is new host code. |
| Image load/store (`image.`, colorimg, shadowcolorimg) | Native impl | RW textures; format tier restrictions (Not verified per format). |
| SSBO (`bufferObject.`) | Native impl | device buffers; `.length()` needs size buffer. |
| SSBO atomics (32-bit int, float add) | Fully supported | MSL atomics (float add needs MSL 3.0, U). |
| Image atomics | Fully supported (MSL>=3.1) | native; emulation below 3.1 is fragile (`:2189-2193`). |
| 64-bit atomics / doubles | Infeasible | SPIRV-Cross throws / emits invalid `double`. |
| Per-buffer blending | Native impl | per-attachment PSO blend. |
| Alpha test override | Fully supported | in-shader. |
| Geometry shaders | Infeasible (v1) | SPIRV-Cross has no GS; only bespoke compute/mesh emulation. Packs requiring GS must be reported unsupported. |
| Tessellation shaders | Native impl (very high effort) / defer | Metal compute-based tess + SPIRV-Cross; isolines infeasible. |
| `gl_Layer` (layered shadow/voxel) | Adapter | VS-only path translates; GS-based layering infeasible. |
| Wide lines / block outline | Fully supported | Iris VS widening. |
| Logic ops | Infeasible / not used | none in Metal; Iris doesn't use. |
| Polygon mode lines (debug) | Native impl | Metal fill mode lines. |
| DH (Distant Horizons) programs (`DHTerrainTransformer`, `DHGenericTransformer`) | Not verified | Same pipeline; DH's own GL renderer is the larger obstacle. |
| Voxy / Nvidium-style mesh paths | Not verified | out of scope. |
| `iris.features.required` reporting | Requires Iris mod | FeatureFlags use GL caps (`IrisRenderSystem.java:299-305, 335-337, 424-430`). |
| Fast-math precision parity | Native impl (config) | Metallum uses default compile options (`MetalUtilities.java:429`); set safe math for packs. |
| Pack-load compile time | Native impl | glslang+spvc+Metal per program; cache needed. |

---------------------------------------------------------------------------------------------------

## 9. Open questions / not verified

1. Whether MC 26.3's renderpearl exposes a reusable GLSL->SPIR-V compiler (LWJGL `shaderc`/glslang) that Metallium could call for Iris output (MC source unavailable; Metallum only consumes `SpvModule`). **U**
2. Exact positional meaning of Metallum's `DeviceInfo(..., true, ...)` (`MetalDevice.java:246-257`) as `isZZeroToOne`. **H**
3. glslang behaviour for leftover `gl_FragData` (non-literal index) in SPIR-V mode — no `EbvFragData` handling in `GlslangToSpv.cpp`; needs a compile test. **H**
4. Whether glslang's IO mapper reliably matches VS/FS varyings by name when all stages are linked in one `TProgram` with auto-mapped locations (alternative to enabling Iris `LayoutTransformer`). **H**
5. Whether `#version ... compatibility` in SPIR-V mode is fatal (message without `correct=false`, `ShaderLang.cpp:675-676`). **H** (moot if Iris is in front).
6. Metal per-stage limits (16 samplers, 31 buffers, 128 textures), RW-texture tiers, pixel-format capabilities (RGB9E5 renderability, 4-bit/565 formats), 1D-mip support, `generateMipmaps` constraints, fast-math default — external Apple documentation, not verifiable in these repos. **U**
7. How many distinct sampler states an Iris program actually needs at once (to size constexpr-sampler strategy) — would need runtime instrumentation of `ProgramSamplers`. **U**
8. Prevalence of geometry/tessellation/double usage across popular packs (BSL, Complementary, Photon, Bliss, SEUS PTGI, Rethinking Voxels) — no pack sources in the research set. **U**
9. `gl_InstanceID` vs `gl_InstanceIndex` base-instance semantic delta under Metallium's draw path (whether Sodium/vanilla use non-zero base instance). **H**
10. dFdy sign and gl_FragCoord.y rewrite correctness under `flip_vert_y`: derived by reasoning, not tested. **SI**
11. Iris 26.3 also wraps vanilla `RenderPipeline`s (`mixin/MixinShaderManager_Overrides.java:137`) — the extent to which Iris-on-26.3 already routes some programs through Blaze3D/renderpearl (which Metallum could translate for free) was not traced in this note. **U**
