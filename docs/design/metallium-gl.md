# metallium-gl: OpenGL → Metal translation layer

Status: **foundation + GL 4.x (compute, SSBO, MDI-count, DSA) implemented; Voxy renders unmodified** (2026-10-18). Roadmap phase 4. Module `metallium-gl/` (Fabric mod id `metallium-gl`,
Apache-2.0, depends on `metallium`, not on Iris).

## 1. Purpose and scope

Some mods call LWJGL's OpenGL directly and cannot be adapted with mixins: Voxy (raw GL 4.6, compute-driven; licence
forbids porting), old HUD / minimap / overlay mods. On Metal there is no GL context, so those calls crash. metallium-gl
runs them **unmodified**: their `org.lwjgl.opengl.GL*` calls are retargeted at class-load time to an emulated GL context
that renders with Metal into Metallium's frame.

Not for Iris: the Iris adapter (`metallium-iris`, LGPL) replaces Iris's GL pipeline with Metal passes (stages 2-3) and
deliberately does not go through a GL shim. metallium-gl only touches mods that **opt in** (§8).

## 2. Architecture

```
mod class (opted in)                       retargeted at load time (GlCallRetargeter, §8)
  INVOKESTATIC GL15C.glBufferData(...)  →  synthetic bridge in the same class:
                                             MetalGL.isActive() ? MetalGL.glBufferData(...)   // Metal
                                                                : GL15C.glBufferData(...)     // OpenGL backend
MetalGL            static sink, LWJGL-identical names/descriptors, adapts NIO/array overloads only
GlContext          name spaces, state tracker, GL error semantics; resolves state into fully specified ops
GlDevice           backend interface: buffers, textures, programs, clear/draw ops, fences, release
MetalGlDevice      Metal implementation: encoders, pipeline/sampler/depth-state caches, uploads as blits
MetalHost          where commands go:  GameMetalHost → MetalliumInterop → Metallium's frame command buffer
                                       HeadlessMetalHost → private queue + offscreen target (unit tests)
```

Package `dev.metallium.gl` (state, no Metal types), `dev.metallium.gl.metal` (Metal), `dev.metallium.gl.retarget`
(class-load hook, opt-in, bootstrap).

## 3. Name spaces (GL names → objects)

`GlObjects.Names<T>`: one int → object table per GL namespace (buffers, textures, renderbuffers, framebuffers, vertex
arrays; shaders and programs share one, as in GL). `glGen*` reserves a name (`null` value), the object is created on
first bind (GL semantics: `glIsBuffer` is false before the bind); binding a never-generated name is
`GL_INVALID_OPERATION` (core profile). `glCreateBuffers` creates immediately. Name 0 is never handed out. Renderbuffers
are textures with a flag. Sync objects are `long` handles in their own table.

Objects hold GL state plus an opaque device store (`BufferStore`, `TextureStore`, `ProgramStore`); deleting an object
hands the store to `GlDevice.release`, which frees it only after the GPU finished the frame.

## 4. State tracker and draw resolution

`GlContext` keeps all GL state as GL defines it (bindings per target, texture units × `GL_TEXTURE_2D`, VAO state incl.
`GL_ELEMENT_ARRAY_BUFFER`, current generic attributes, draw/read FBO, draw buffers, viewport/scissor, per-buffer blend
func/equation/enable/colour mask, depth/cull/polygon offset/polygon mode, clear values, unpack state). Errors follow GL:
the first error is kept until `glGetError`.

Nothing is sent to the device until a draw/clear/upload. At `glDraw*` the context resolves the state into one
`GlDevice.DrawOp`: program store, render targets (FBO attachments mapped through `glDrawBuffers`, or the host's
framebuffer 0), vertex attributes for exactly the locations the linked program reads (enabled arrays → buffer, offset,
stride, divisor; disabled → the constant `glVertexAttrib*` value), index buffer, uniform bytes per stage, texture +
sampler state per sampler uniform, and the fixed-function `RasterState`. Primitive modes Metal lacks (fans, compat
`GL_QUADS`, line loops) and `GL_UNSIGNED_BYTE` indices are expanded into a temporary 32-bit index buffer.

## 5. Flushing into Metallium's frame (core extension API)

The only core change is `dev.metallium.backend.MetalliumInterop` (+ one hook line in `MetalCommandEncoder.endEncoder`
and one in the `MetalDevice` constructor):

1. `beginExternalEncoding(client)` ends Metallium's open encoder and returns the frame's `MTLCommandBuffer`.
2. External encoders `waitForFence(frameFence())` at start and `updateFence` at end, like Metallium's own encoders
   (Metallium's resources are not hazard-tracked).
3. Before Metallium encodes again (next pass, blit, submit, present) `endEncoder()` calls
   `client.endExternalEncoding()`; `MetalGlDevice` closes its encoder and calls `begin` again on its next operation.
4. `metalTexture(GpuTexture, willWrite)` applies Metallium's lazily recorded clears first (so a GL draw onto the main
   target sees the cleared image) and marks the texture dirty.
5. `releaseAfterGpu` (deferred destruction) and `createFence` (completion of the current frame).

So GL work lands in the same command buffer as the game, in call order. `MetalGlDevice` keeps one render encoder open
while consecutive draws use the same attachments (Apple GPUs are tile-based: each encoder switch stores and reloads the
attachments); a full-target `glClear` becomes the next encoder's load action (free), a scissored or colour-masked clear
a full-screen triangle. Texture uploads are blits from a staging buffer in the same command buffer, so they are
ordered with draws exactly like GL; anything that may blit is done before a draw acquires its render encoder.

Framebuffer 0 is Minecraft's main render target (colour + depth). Calls made from a 26.3 HUD element run during the
**extract** phase, before the frame's world is drawn (and cleared), so they are overwritten — on OpenGL too. Raw-GL
overlays must draw after level rendering (the game test uses Fabric `LevelRenderEvents.END_MAIN`).

## 6. Coordinates and conventions

Metallium's render targets store images bottom row first, like GL (vertex shaders flip Y via SPIRV-Cross
`FLIP_VERTEX_Y`, as for vanilla pipelines). Consequences, all verified by unit tests with pixel readback:
- `glViewport`/`glScissor`/`glReadPixels` use GL window coordinates unchanged as Metal coordinates;
- texture row 0 of `glTexImage2D` is the bottom row when sampled with GL texture coordinates;
- the Y flip mirrors winding: GL `GL_CCW` front faces → `MTLWinding.Clockwise`;
- GL clip-space depth (-w..w) is remapped to Metal's 0..w in the vertex stage (SPIRV-Cross
  `FIXUP_DEPTH_CONVENTION`, new `GlslProgramTranslator.spirvToMsl(stage, mslVersion, glClipSpace)` overload).

## 7. Shaders, programs, uniforms

`glCompileShader` compiles the single stage with shaderc to report status and info log. `glLinkProgram`:
`GlslProgramTranslator.compileToSpirv` (both stages, varyings matched by name in SPIR-V, loose uniforms folded into a
std140 default block with full layout reflection) → `spirvToMsl(..., glClipSpace=true)` → `MTLLibrary` per stage.
`glBindAttribLocation` is applied by adding `layout(location = N)` to the vertex input before compiling.

Reflection exposed through GL: uniform locations (one per default-block member, arrays take consecutive locations so
`name[i]` works), sampler uniforms (Metal texture + sampler slot per stage), attribute locations (`[[attribute(n)]]`
of the MSL vertex inputs). `glUniform*` writes into per-stage CPU copies of the default block using the reflected std140
offsets / array and matrix strides (e.g. `mat3` columns padded to 16 bytes), with GL's type/size checks
(`GL_INVALID_OPERATION` on mismatch). At draw time the bytes go to `set{Vertex,Fragment}Bytes` (≤ 4 KiB) or a transient
buffer. Vertex buffers use Metal buffer slots from 30 downwards; SPIRV-Cross assigns uniform buffers from 0.

Pipeline states are cached per (program, attachment formats, depth/stencil format, vertex layout, blend/write mask).

Not yet: explicit uniform blocks (`glUniformBlockBinding`/`glBindBufferBase(GL_UNIFORM_BUFFER)`), sampler arrays,
`#version` < 140 sources (glslang's Vulkan-relaxed mode; would need a 1.10/1.20 → 1.40 rewrite), geometry/tessellation.

## 8. Retargeting (bytecode)

**Hook.** Fabric has no public class-transformer API. `MetalliumGlBootstrap` (run from the mixin config plugin's
`onLoad`, the earliest point a mod can run code; `preLaunch` retries) wraps Knot's `IMixinTransformer`
(`KnotClassDelegate.mixinTransformer`, reflection) in a `java.lang.reflect.Proxy`. After Mixin has transformed a class,
classes of opted-in mods go through `GlCallRetargeter`. No hook is installed when no mod opted in.

**Rewrite.** Every `INVOKESTATIC org/lwjgl/opengl/{GLnn, GLnnC, ARB*, EXT*, KHR*, NV*, AMD*, ...}.gl*` (and
`GL.getCapabilities/createCapabilities`) is redirected to a synthetic static bridge in the same class (one per distinct
owner + name + descriptor). Call sites keep their descriptor, so no stack-map frames change; the bridge has one SAME
frame. The bridge calls `MetalGL` when Metal is active and the original LWJGL function otherwise, so a retargeted mod
keeps working if Metallium falls back to OpenGL (verified: with `-PbackendOff` the test mod's 68 retargeted call
sites run on real OpenGL without GL errors).

**Unimplemented functions.** If `MetalGL` has no method with the same name and descriptor, the bridge calls
`MetalGL.unimplemented(modId, "GL11.glAccum(IF)V")` and returns a default (0/false/null). `MetalGLLog` logs each
(mod, function) once and counts calls, so coverage grows from what real mods call. Fail-fast (throw) is the default in
the dev environment and configurable with `-Dmetallium.gl.failFast=true|false`. Unsupported *parameter values* of
implemented functions (e.g. a texture target other than `GL_TEXTURE_2D`) are logged the same way and raise the GL
error GL would. The F3 screen shows a `metallium-gl:` status line.

**Which mods (since 2026-10-18: automatic).** The core's start-up scan (`dev.metallium.compat.ModScan`, ASM, cached
in `config/metallium/scan-cache.json`) finds every class that calls `org.lwjgl.opengl.*`; when Metal is used, the
`GlRouting` policy routes exactly those classes through metallium-gl without any opt-in. Opt-outs: the user's per-mod
override *Native* (status screen) or the author's `"custom": {"metallium-gl": false}`; switch *Translate raw OpenGL
mods* off in the Metallium tab. Explicit opt-ins still work and also apply on OpenGL: `"metallium-gl": true` or
`{"packages": [...]}` in `fabric.mod.json`, `-Dmetallium.gl.mods=a,b`, `mods=a,b` in `config/metallium-gl.properties`,
override *metallium-gl*. Never scanned or retargeted: `ModScan.EXCLUDED` (Minecraft, loader, Fabric API, Metallium's
modules, Sodium, Iris, Distant Horizons, Voxy). A crash in a routed mod or in metallium-gl is caught on the next start
by `CrashGuard` (OpenGL for that session + recovery screen; see `docs/MOD-AUTHORS.md`).

**Capabilities.** `GL.getCapabilities()` returns a `GLCapabilities` allocated without LWJGL's constructor that claims
OpenGL 1.1-4.6 core, only the extensions in `MetalGLStrings.EXTENSIONS` (those actually implemented), and a non-zero
function address for every implemented entry point. `glGetString(GL_VERSION)` = "4.6.0 Core Profile (metallium-gl)".

Limits: GL method references (`GL11::glEnable` as a lambda) and reflective / function-pointer calls (`GL.getFunctionProvider`,
`JNI.call*`) are not retargeted (method references are counted and logged); GL calls from threads other than the render
thread (shared contexts) are not supported.

## 9. Buffers, persistent mapping, sync

Buffers are `MTLStorageModeShared` (unified memory): `contents()` is the storage itself. `glBufferSubData` and
synchronised `glMapBufferRange` writes on a buffer that pending GPU work still reads get fresh storage (contents copied;
`GL_MAP_INVALIDATE_BUFFER_BIT` skips the copy) — GL's ordering guarantee without stalls. `glBufferStorage` +
`GL_MAP_PERSISTENT_BIT` returns the storage directly and is never renamed; coherent by construction, so
`glFlushMappedBufferRange` is a no-op. The application synchronises with fences, as in GL.

Sync objects: `glFenceSync` records a `GpuUse` = completion of the current frame's command buffer (`MetalliumInterop.createFence`,
backed by Metallium's per-submit semaphores). `glClientWaitSync` returns `GL_ALREADY_SIGNALED` / `GL_CONDITION_SATISFIED`
/ `GL_TIMEOUT_EXPIRED`; waiting on work of the frame being recorded cannot succeed before it is submitted and returns
`GL_TIMEOUT_EXPIRED` instead of deadlocking. `glWaitSync` is a no-op (one queue). Next step: an `MTLSharedEvent`
signalled at the fence's position (encoder boundary) for sub-frame granularity, needed when a mod waits for its own
uploads within one frame.

## 10. Implemented subset (first step)

| Area | Functions |
|---|---|
| Errors / queries | glGetError, glGetString(i), glGetInteger(v), glGetFloat, glGetBoolean, glIsEnabled, `GL.getCapabilities` |
| Buffers | glGen/Create/Delete/IsBuffers, glBindBuffer (array, element, copy, pixel pack/unpack, uniform), glBufferData, glBufferSubData, glBufferStorage, glMapBuffer(Range), glUnmapBuffer, glFlushMappedBufferRange |
| Textures (2D) | glGen/Delete/IsTextures, glActiveTexture, glBindTexture, glTexImage2D (client memory or PBO), glTexSubImage2D, glTexStorage2D, glTexParameteri/f/iv, glGetTexParameteri, glGetTexLevelParameteri, glGenerateMipmap, glPixelStorei; formats RGBA8/RGB8 (→RGBA)/sRGB/R8/RG8/16F/32F/depth/depth-stencil, client formats RGBA/BGRA/RGB/RG/RED × ubyte/byte/short/int/half/float/8888(_REV) |
| Framebuffers | glGen/Delete/Bind/IsFramebuffer, glFramebufferTexture(2D), glFramebufferRenderbuffer, glCheckFramebufferStatus, glDrawBuffer(s), glReadBuffer, renderbuffers, glReadPixels (RGBA8; headless only) |
| Programs | glCreate/Delete/IsShader, glShaderSource, glCompileShader, glGetShaderi(v)/InfoLog, glCreate/Delete/IsProgram, glAttach/DetachShader, glBindAttribLocation, glLinkProgram, glValidateProgram, glUseProgram, glGetProgrami(v)/InfoLog, glGetAttribLocation, glGetUniformLocation, glUniform{1-4}{f,i,ui}(v), glUniformMatrix{2,3,4}fv |
| Vertex input | glGen/Delete/Bind/IsVertexArray, glEnable/DisableVertexAttribArray, glVertexAttrib(I)Pointer, glVertexAttribDivisor, glVertexAttrib{1-4}f |
| Draws | glDrawArrays(Instanced), glDrawElements (buffer offset or client indices), glDrawElementsInstanced, glDrawElementsBaseVertex; all core primitive modes + fans/quads/line loops |
| State | glEnable/Disable(i), glViewport, glScissor, glBlendFunc(Separate)(i), glBlendEquation(Separate)(i), glBlendColor, glColorMask(i), glDepthFunc/Mask/Range, glCullFace, glFrontFace, glPolygonOffset, glPolygonMode (fill/line), glLineWidth, glClear, glClearColor/Depth/Stencil, glHint, glFlush, glFinish |
| Sync | glFenceSync, glClientWaitSync, glWaitSync, glDeleteSync, glIsSync, glGetSynci |

`GlCallRetargeterTest.metalGlMirrorsLwjglSignatures` checks that every implemented entry point has an LWJGL twin with
the same descriptor (otherwise retargeting could never select it).

## 11. Tests

- `GlContextTest` (no GPU, runs on Linux CI): names, error semantics, buffer renaming and persistent mapping, pixel
  conversion, FBO completeness, shader info logs, uniform std140 writes, draw resolution, queries, sync.
- `GlCallRetargeterTest`: transforms a real class calling GL11C/GL11/GL15/GL, loads it, checks Metal routing,
  unimplemented logging/fail-fast, and fallback to LWJGL when inactive.
- `MetalGlDeviceTest` (macOS, real Metal device, skipped elsewhere): renders through the whole path and reads pixels
  back — viewport origin, texture orientation, culling winding, GL clip-space depth, FBO + scissored clear + blending,
  persistent-mapped vertex data, unbound sampler = black.
- Game test (`./gradlew :runClientGameTest -PwithGl`): `dev.metallium.gametest.gl.RawGlQuads` is a small raw-GL "mod"
  (opted in by package) drawing three quads (uniform colour, 2x2 texture, FBO texture) after level rendering; the test
  checks their pixels in the screenshot on Metal.

## 12. GL 4.x for GPU-driven mods (Voxy) — implemented (step 2)

### 12.1 Voxy's GL surface (inventory)

Voxy 0.2.20-beta, branch `263` @ 247adf2 (local build, never committed; licence "All rights reserved": only names are
referenced). Static inventory with `javap -c` over all 363 classes: **206 distinct GL call targets** (owner + name +
descriptor) plus Minecraft OpenGL-backend internals. Before step 2, **106** of the 206 had no implementation; now **1**
(`ARBSparseBuffer.glBufferPageCommitmentARB`, deliberately not claimed — Voxy only uses it when
`GL_ARB_sparse_buffer` is reported). Runtime inventory: §12.4.

| Area | What Voxy calls |
|---|---|
| Compute | `glDispatchCompute`, `glDispatchComputeIndirect`, `glMemoryBarrier`, compute shaders (`#version 430/460`, shared memory, `barrier()`, atomics, subgroup arithmetic + clustered ops in Hi-Z / prefix sum) |
| Binding points | `glBindBufferBase/Range` (SSBO, UBO), `glBindImageTexture` (r32f / rgba8, one mip), sampler objects (`glGenSamplers`, `glCreateSamplers`, `glSamplerParameteri`, `glBindSampler`), `glBindTextureUnit` |
| Draws | `glMultiDrawElementsIndirectCountARB` (main terrain draw, `GL_UNSIGNED_SHORT`, up to 100k draws), `glDrawElementsIndirect`, `glDrawElementsInstancedBaseInstance`, `gl_BaseInstance` in vertex shaders, `glProvokingVertex(FIRST)` |
| Buffers (DSA) | `glNamedBufferStorage` (persistent + coherent), `ngl`/`gl` `MapNamedBuffer(Range)`, `glCopyNamedBufferSubData`, `nglClearNamedBufferSubData/Data`, `glGetNamedBufferSubData`, `glFlushMappedNamedBufferRange` |
| Textures (DSA) | `glCreateTextures`, `glTextureStorage2D`, `nglTextureSubImage2D`, `glTextureParameteri/f`, `glTextureView`, `nglClearTexImage`, `glGetTextureImage` (reads Minecraft's block atlas), `glGetTextureLevelParameteri` |
| Framebuffers (DSA) | `glCreateFramebuffers`, `glNamedFramebufferTexture/Renderbuffer`, `glNamedFramebufferDrawBuffer(s)`, `glClearNamedFramebufferfv/iv/fi`, `glBlitNamedFramebuffer`, depth-stencil renderbuffers, stencil test (`glStencilFunc/Op/Mask`) |
| Queries / misc | `glGetInteger64` (SSBO limits), `glGetIntegeri` (SSBO binding), timer queries (`glQueryCounter`, `glGetQueryObjecti64`), `glObjectLabel`, `nglShaderSource`, `nglUniformMatrix4fv`, `nglGetSynciv`, fences |
| Capability probe | `GL.getCapabilities()`: `glDispatchComputeIndirect != 0`, `glMultiDrawElementsIndirectCountARB != 0`, `GL_ARB_sparse_buffer`, NV extensions, `GL_KHR_shader_subgroup`; compiles probe shaders (int64, subgroups) and runs a depth-sampler compute test with readback |
| Minecraft internals | `GlStateManager._*` (11 functions, state reset after rendering); `((GlTexture) t).glId()` / `((GlTextureView) v).glId()` for the main target colour/depth, lightmap, block atlas — also inside Voxy's **Sodium mixin** (merged into `DefaultChunkRenderer`, the opaque LOD render entry point) |

### 12.2 Design as implemented

- **Compute** (`GlCompute`, `MetalGlDevice.dispatch`): `GL_COMPUTE_SHADER` → translator (`Stage.COMPUTE`, workgroup size
  from SPIR-V `LocalSize`) → `MTLComputePipelineState`. Consecutive dispatches share one *serial* compute encoder in the
  frame's command buffer (each dispatch sees the previous one's writes); `glMemoryBarrier` therefore needs no work.
  metallium-gl's buffers/textures are Metal-hazard-tracked across encoders; Metallium's (untracked) textures are ordered
  by the frame fence as before.
- **Binding points** (`GlBindings`): per-stage reflection gives each UBO/SSBO/image its Metal slot; the GL binding comes
  from the source's `layout(binding = N)` (preprocessed with shaderc, `GlslProgramTranslator.explicitLayouts`, because
  glslang auto-assigns numbers to unbound resources), default 0 as in GL. Same for sampler units and explicit
  `layout(location = N)` uniforms. Storage images bind single-mip texture views. Sampler objects override texture state.
- **Indirect-count draws** (`IndirectCountDraws`): *compute pre-pass → indirect command buffer*. A kernel reads GL's
  `DrawElementsIndirectCommand`s and the GPU-written count and encodes an `MTLIndirectCommandBuffer` (indexed draws;
  commands ≥ count or with 0 instances are reset); the render encoder executes it with inherited pipeline and buffers
  (pipelines get `supportIndirectCommandBuffers`). No CPU readback, CPU cost independent of the draw count; one ICB per
  call per frame, pooled in power-of-two size classes (§12.6). Rejected alternative: zeroing `instanceCount` and encoding `maxdrawcount` indirect draws on the
  CPU (100k draw calls per frame for Voxy). Plain indirect draws use Metal's `drawIndexedPrimitives(indirectBuffer)`
  (identical argument layout). `gl_InstanceID` is fixed to exclude the base instance (Metal's `[[instance_id]]` includes
  it) for GL-semantics programs.
- **Buffers**: copies / clears / uploads into GPU-written buffers are blits / a fill kernel in the command stream
  (GL order). Buffers written by the GPU are never "renamed" on CPU updates (that would lose GPU writes).
  `glGetBufferSubData` / `glGetTextureImage` of GPU-busy data use the new core call `MetalliumInterop.submitAndWait()`
  (commits the frame's work so far mid-frame and waits; Metallium continues in a new command buffer).
- **DSA** (`GlDsa`): every named call binds the object to a scratch binding, runs the bind-to-edit path and restores —
  identical validation and errors. Separate vertex formats (`glVertexArrayAttribFormat/VertexBuffer`).
- **Minecraft's textures by GL name** (`MetalGLInterop`, `GlExternalTextures`): the retargeter rewrites
  `CHECKCAST GlTexture(View)` + `glId()` into one bridge; on Metal it returns a name in metallium-gl's texture space
  wrapping Metallium's `MTLTexture` (lazy clears applied before each use; never released by GL).
- **GlStateManager** calls in retargeted code go to `MetalGLStateManager` (same names/descriptors).
- **Mixin-merged code**: methods that an opted-in mod's mixins merge into *other* classes (`@MixinMerged`) are retargeted
  too (Voxy's render entry lives in Sodium's `DefaultChunkRenderer`).
- **Capabilities**: claimed extensions = implemented ones (compute, SSBO, image load/store, UBO, copy/clear buffer,
  (multi) draw indirect, indirect parameters, base instance, shader draw parameters, DSA, sampler objects, texture view,
  texture barrier, timer query (CPU times), KHR_debug (no-op), KHR_shader_subgroup). Not claimed: NV/AMD extensions,
  sparse buffers, int64 (shaders requiring `GL_ARB_gpu_shader_int64` fail to compile, so Voxy's probe takes its 32-bit
  path), bindless. Second sink class `MetalGL4` keeps `MetalGL` focused.
- **Voxy gate**: core's `VoxyClientMixin` steps aside when metallium-gl routes Voxy (system property
  `metallium.gl.routedMods`, set by the bootstrap).

### 12.3 Tests

`MetalGl4DeviceTest` (real Metal device): compute + atomics + UBO + explicit uniform location; indirect dispatch whose
group count a previous dispatch wrote; buffer clear/copy/readback; `imageStore` into mip 1 + `glGetTextureImage`; texture
+ sampler object in compute; MDI-count with GPU-written commands and count (count 3 of 4 → 4th not drawn) and
`gl_BaseInstance`; `glDrawElementsIndirect` + base-instance draw; stencil; blit + texture view. `GlCallRetargeterTest`:
GlStateManager + `glId()` rewrite; `MetalGL4` and `MetalGLStateManager` mirror their originals' descriptors.
Translator tests: GL bindings/workgroup size/explicit layouts, subgroup clustered ops compile in Metal, `gl_InstanceID`.

### 12.4 Runtime status with Voxy

**Voxy renders its LOD terrain on Metal, unmodified** (2026-10-18, M3 Max, macOS 27.0.1, Voxy 247adf2 + Sodium 0.9.2).
Command: `gpu-lock.sh ./gradlew :runClientGameTest -PwithSodium -PwithGl -PwithVoxy=<jar> -PglMods=voxy -PglFailFast=false`
(since 2026-10-19 Voxy is routed automatically — `GlRouting.WHOLE_MOD`, §12.5 — and `-PglMods=voxy` is no longer needed).
Scenario: natural world, spectator at y 160, vanilla render distance 16 for 60 s (Voxy ingests the loaded chunks), then 4.

- Runtime inventory: 36 Voxy classes / 603 GL call sites retargeted, **0 unimplemented functions called**; per minute
  ≈13k dispatches and ≈5k indirect draw calls (≈7-8 GPU-generated draws per MDI-count call); Voxy's F3 data: 198 models
  baked, ~90 nodes, render-system frame time ≈0.9-1.8 ms.
- Screenshots: at render distance 4 Sodium draws 0 sections, yet the full 16-chunk terrain is visible — Voxy's LODs; a
  reference run without Voxy (`-PvoxyScenario`) shows the same view at distance 16 and only sky at distance 4, and the
  Voxy image matches the vanilla rd-16 image closely. Frame time ≈2 ms (the game test caps at 30 fps).
- Fixes found by running Voxy (each with a unit test): >2 GiB buffers (Voxy's 4 GiB geometry SSBO); `gl_DepthRange`;
  `#extension X : enable` of unsupported extensions is a warning and glslang's predefined extension macros are hidden;
  GL 4.1+ framebuffer completeness; resources SPIRV-Cross leaves unassigned are never bound (driver crash on index -1);
  `glFinish` / waiting `glClientWaitSync` complete fences of the frame being recorded (Voxy spun forever on shutdown);
  `glClipControl` + the context starts in Minecraft's zero-to-one depth convention (Voxy compiles for it);
  **Metal forbids textures in ICB pipelines** ("Fragment shader cannot be used with indirect command buffers"), so
  textured programs (Voxy's terrain) use the compaction fallback: a kernel writes Metal indirect arguments with
  instanceCount 0 beyond the GPU count and the render encoder issues one indirect draw per slot (CPU cost per slot,
  `maxdrawcount` clamped to the command buffer). Texture-free programs keep the ICB path. *Superseded (§12.5): textured
  programs now run as ICBs too, with their textures in argument buffers.*
- Game-test plumbing: dev-only macOS natives for Voxy's storage (RocksDB / zstd: the 263 jar bundles none for macOS).

Not done / known limits:
- Iris + Voxy (Voxy's Iris pipeline, shader-pack programs) is untested; `glBlitFramebuffer` only 1:1.
- Timer queries report CPU times; sparse buffers / int64 / NV paths are reported unsupported (Voxy takes its generic paths).
- ~~The MDI-count fallback costs one encoded draw per `maxdrawcount` slot~~ — done in §12.5.
- Shadow / translucent / SSAO passes run (no errors) but were not checked pixel by pixel.
- Diagnostics: `-PglTrace=N -PglTraceAfter=S` logs device operations, `-PglDump=n,...` texture statistics after them.

### 12.5 Step 3: textured ICBs, automatic routing, combinations (2026-10-19)

**Textured indirect-count draws as ICBs** (`ArgumentBufferTextures`, `MetalGlDevice.icbVariant`). Metal rejects ICB
pipelines whose functions take textures/samplers as direct arguments, but an ICB inherits *buffer* bindings. Each program
gets an ICB variant on first use: the entry point's `[[texture(n)]]` / `[[sampler(n)]]` arguments move into one
`constant MlTextureTable& [[buffer(b)]]` (tier-2 layout: one 8-byte `MTLResourceID` per member, `b` = highest buffer
index + 1) and are copied into locals of the same names, so the rest of SPIRV-Cross's output is unchanged. Per draw the
CPU writes `gpuResourceID`s with `set{Vertex,Fragment}Bytes` and declares the textures with `useResource`; samplers are
created with `supportArgumentBuffers`. Texture arrays are not rewritten (the per-slot fallback remains for them, and
behind `-Dmetallium.gl.icbTextures=false` / `-PglIcbTextures=false` for A/B runs). Tests: `ArgumentBufferTexturesTest`
(rewrite), `MetalGl4DeviceTest` (textured MDI-count, vertex texelFetch + two fragment textures + an unbound unit, all
asserted to run as ICBs).

Voxy A/B (`-Pbench -PbenchScene=voxy`: seed "metallium", ingest at vanilla rd 32 for 150 s, measure 30 s at rd 4, all
via `gpu-lock.sh --exclusive`; counters from the status line before/after the window):

| Run | MDI-count calls | CPU-encoded draws | CPU time in drawIndirect | fps (30 s) |
|---|---|---|---|---|
| per-slot fallback (round 5) | 5 382 | 1 813 734 (≈1 013 / frame) | 485 ms (0.27 ms / frame) | 59.7 * |
| ICB + argument buffers (round 5) | 5 358 | 0 | 238 ms (0.13 ms / frame) | 59.4 * |
| per-slot fallback (round 3, forest seed, y 200) | 407 874 total | — | — | 970.0 avg, 1 % low 347.5, p99 2.39 ms |
| ICB (round 3) | 398 472 total | 0 | — | 997.0 avg, 1 % low 376.3, p99 2.36 ms |

\* Round 5 ran on battery (58 %, discharging) with the display not presenting (`presented_fps=0`, the frame rate pinned at
60): only the CPU columns are meaningful. Round 3 (uncapped, ≈1000 fps, power state not recorded) is a light scene where
Voxy's per-call `maxdrawcount` is small; the gain there is ≈3 % fps. To repeat on AC power with the display on.
Rendering is identical in both modes (same screenshot).

**Automatic routing**: `GlRouting.WHOLE_MOD = {voxy}` replaces `EXPLICIT_ONLY`: on Metal with metallium-gl installed
and automatic routing on, all of Voxy's classes are retargeted without `-Dmetallium.gl.mods`; a `NATIVE` override,
automatic routing off or no metallium-gl keep the safe-disable mixin. The status screen's key-mods row reads
"Voxy …: runs via metallium-gl (OpenGL 4.6 translated to Metal)" (asserted by the Voxy game test).

**Combination game tests** (2026-10-19, on battery, `gpu-lock.sh`; screenshots looked at; scripts: `-PwithBobby`,
`-PwithPhysics=<jar>` together with an LOD mod runs the LOD scenario plus `physicsInWorld`, `-Pshaderpack` activates the
pack in the DH / Voxy / Bobby scenarios):

| Combination | Result |
|---|---|
| Voxy (auto-routed, no `-PglMods`) + Sodium | PASS; LODs at vanilla rd 4 match the rd-16 view; MDI-count only as ICBs; status row "runs via metallium-gl" |
| Bobby 5.2.16 alone (vanilla renderer) | runs; after the server view distance drops 16 → 4 the client keeps 157 of 168 chunks and the view is unchanged |
| Bobby + Sodium + Voxy | PASS; Sodium keeps 96 sections at server distance 4; Voxy LODs over Bobby's chunks at rd 4 |
| Physics Mod 3.2.5 + Voxy + Sodium | PASS; brick debris, zombie ragdoll and TNT on Metal with Voxy running (3 routed mods, 0 unimplemented GL functions) |
| Iris + Voxy (Complementary Reimagined) | runs, no errors; Voxy draws, but the pack fogs everything beyond the vanilla distance: Voxy's Iris integration (`IrisVoxyRenderPipeline`, pack `voxy_*.glsl` programs) hooks Iris's GL pipeline, which metallium-iris replaces — needs metallium-iris work |
| DH + Iris (+ Physics Mod) | was a hang (DH forces its OpenGL renderer when Iris is present; on Metal its start fails and the render thread waits forever). Fixed by `DhIrisEngineMixin` (Blaze3D stays). Now runs; LODs fogged by the pack ("2 DH programs are not executed yet on Metal"), physics debris works |

### 12.6 Owner session fixes (2026-10-19: modpack + Voxy 0.2.20, 2560x1440 on the 144 Hz G5)

- **Resize / fullscreen crash** (`GlFramebuffer.verify`: "Framebuffer incomplete with error code: 36054"). Voxy's
  `NormalRenderPipeline.setup` deletes its colour textures, then `DepthFramebuffer.resize` re-creates the depth texture,
  attaches it and checks completeness *before* the colour is re-attached. `glDeleteTextures` released the storage at
  once, so the unbound framebuffer's colour attachment had none → `INCOMPLETE_ATTACHMENT`. GL keeps a deleted texture
  or renderbuffer alive while a framebuffer that is not bound references it (deletion detaches it from the bound ones
  only). Attachments are now reference-counted (`Texture.attachmentRefs` / `deletePending`); storage is released when
  the last attachment drops it (re-attach, framebuffer deletion). Unit test
  `GlContextTest.deletedTextureStaysAliveWhileAttachedToUnboundFramebuffer`; the Voxy game test now resizes the window
  three times while Voxy renders (`-PvoxyFullscreen` also toggles fullscreen). With the fix reverted that game test
  crashes with the owner's exact stack; with it, PASS and the LOD view is unchanged after the resizes.
- **ICB churn**: the log had 154 ICB allocations / 6.1 million commands in 5 minutes. Capacities were rounded to
  multiples of 1024, so every rise of Voxy's `maxdrawcount` (by ~1024 at a time while it loads) allocated two new ICBs
  (frames in flight), and outgrown ones stayed pooled forever. `SizeClassPool`: power-of-two capacities, the smallest
  fitting pooled ICB is reused once the GPU finished, ICBs idle for 512 acquisitions are freed; the compaction-fallback
  scratch buffer (allocated per call before) uses the same pool. Voxy bench (2560x1440 windowed, rd 32 ingest then
  rd 4): **16 ICB allocations in the whole run, none during the 30 s measurement, 6 alive (16 MiB)**; Metal allocated
  size flat at 5128 MiB before and after the measurement. `diagnostics()` now reports ICBs alive / MiB / allocations,
  the device's `currentAllocatedSize` and mid-frame GPU waits.
- **Per-frame waits**: Voxy calls `glFinish` only when its upload/download streams are full (none in the owner's log)
  and polls its fences; measured GPU waits: 22 in the whole Voxy bench (98 ms, all during loading), **0 during the
  measurement**. So neither metallium-gl waits nor ICB allocation starve presents now; the remaining displayed-fps gap is
  the core VSync-off present pacing (docs/STATUS.md).

## 12b. Engine objects behind Minecraft's GL backend types (Physics Mod GPU compute)

Mods written against Minecraft's OpenGL backend cast renderpearl objects to `GlTexture`, `GlTextureView`, `GlBuffer`
and `GlSampler` (or to their own Mixin accessor interfaces on those classes, e.g. Physics Mod's
`((GlBufferAccessor) buffer).physicsmod$getHandle()`) and use the GL names with GL calls: a compute shader fills a
renderpearl buffer that the mod then draws from with `RenderPass.drawIndexedIndirect`, or writes a 3D texture that a
renderpearl pass samples.

- **Stand-ins** (`GlShadowObjects`, `MetalGLInterop.shadow`): in retargeted code every `CHECKCAST` to one of the four
  types, and to types in Mixin packages, gets `shadow(obj, "type")` inserted before it. On Metal an engine object
  becomes a stand-in of the GL type (cached per engine object): `GlTexture`/`GlTextureView` built with their
  GL-free constructors around the metallium-gl name of the same Metal texture; `GlBuffer` and `GlSampler` allocated
  without constructor (Unsafe) with `handle`/`id` set. Accessor interfaces are resolved at run time (an interface that
  one of the four classes implements). On OpenGL the call returns the object unchanged.
- **Engine buffers by GL name** (`GlExternalBuffers`): the Metal buffer is resolved at each use (Metallium may swap
  dynamic buffers' backing); GPU access only (SSBO, copies, clears, pixel-pack target), never released by GL.
- **Engine 3D / 2D-array textures**: external textures keep their Metal type (`GL_TEXTURE_3D`, `GL_TEXTURE_2D_ARRAY`,
  real pixel format incl. `R32UI`/`R32I`); layered image bindings, sampling in compute, clears and readbacks cover all
  slices / layers; glGetTexImage into a pixel-pack buffer is a GPU copy. A unit holds one texture of any of these
  targets (the last bound).
- **Metal detail found on the way**: texture atomics only work on textures created with `ShaderAtomic` usage; without
  it, atomics on a 3D texture land in slice 0. Integer storage textures must be created with it.
- Tests: `GlEngineResourcesTest` (3D R32UI atomics + clear + readback; 2D array layered store, sampler2DArray,
  pack-buffer readback into an engine buffer).

## 13. Performance notes

Per draw: one pipeline-cache lookup (string key; to be replaced by a packed key), state set on the encoder, uniform bytes
copied by Metal. Encoders are reused across draws with the same attachments; every switch between GL and Metallium
encoding costs an encoder end/begin (load/store of the attachments). Mods that interleave GL draws with game rendering
(e.g. per-entity) will pay this; batching across the boundary is a later optimisation.
