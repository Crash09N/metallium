# Iris adapter stage 5: compute shaders, images, SSBOs and mipmapped buffers on Metal

Status: implemented 2026-10-18 (`metallium-iris`, package `dev.metallium.iris.post`: `ComputePass`, `MetalCompute`,
`StorageBuffers`, `CustomImages`, `StorageBindings`; core: `dev.metallium.shaders.ComputeTranslator`; backend
extensions in `MetalliumInterop`). Previous stages: `iris-stage2-post-pipeline.md` .. `iris-stage4-shadows.md`.

macOS OpenGL stops at 4.1: no compute shaders, no SSBOs, no image load/store. Packs that need them (Photon's sky
lighting, every pack's voxel / floodfill / LPV coloured-lighting option) have never run on a Mac through Iris; on
Metal they now can.

## Compute passes

`ComputePass` compiles one `*.csh`: `TransformPatcher.patchCompute` (Iris, unchanged) -> GLSL -> SPIR-V (core
translator, `Stage.COMPUTE`) -> `ComputeTranslator.toMsl` -> `MTLComputePipelineState`. `ComputeTranslator` gives the
kernel a fixed, host-known layout (only resources the entry point uses):

| Metal slot | Content |
|---|---|
| `buffer(0)` | the folded default uniform block (Iris uniforms via `PostUniforms.Holder`, `setBytes`, >4 KiB via a transient buffer) |
| `buffer(1..)` | SSBOs (keeping the GLSL `binding` = Iris `bufferObject.N` index) |
| next buffer | SPIRV-Cross buffer-size buffer (`uint` per buffer index) when the kernel uses `.length()` |
| `texture(0..)` / `sampler(0..)` | sampled textures, one sampler each |
| following textures | storage images (`colorimgN`, `shadowcolorimgN`, custom images); MSL 3.1 native texture atomics |

Where they run (same places and flip states as Iris's `IrisRenderingPipeline`/`CompositeRenderer`/`FinalPassRenderer`/
`ShadowCompositeRenderer`):

- `setup` programs: once after pipeline creation and after every resize (start of the frame, after the clears);
- `shadow` computes (`ProgramSet.getShadowCompute`): every frame before the shadow pass, sized to the shadow map;
- `begin/prepare/deferred/composite` computes (`compositeN_a.csh` ...): before fragment pass N with its flip state;
  a `compositeN.csh` without `compositeN.fsh` is a compute-only pass (Iris's `ComputeOnlyPass`);
- `shadowcomp` computes and **shadowcomp fragment passes** (new): after the shadow pass, on the shadow targets,
  flipping shadowcolor buffers (the result is what later passes sample);
- `final` computes: before the final pass.

Work groups follow Iris's `ComputeProgram`: `workGroups` (absolute), `workGroupsRender` (relative to the pass size),
otherwise one thread per pixel; `indirect.<pass> = <buffer> <offset>` uses `dispatchThreadgroupsWithIndirectBuffer` on
the SSBO. `colorimgN` binds the texture the pass reads from (Iris: "image bindings are impacted by buffer flips").

**Encoding.** `MetalCompute` opens an `MTLComputeCommandEncoder` on Metallium's frame command buffer through the
`MetalliumInterop` external-encoder contract (end Metallium's encoder, wait for / update the frame fence, closed again as
soon as Metallium encodes). Compute work is therefore ordered exactly where Iris issues it between render passes.
Dispatches in one encoder are serial (default dispatch type), which provides `glMemoryBarrier` ordering. All resources
are untracked like Metallium's own. Mipmap generation uses a blit encoder on the same path.

**Threadgroup memory.** GLSL `shared vec3 x[256][9]` is 27 KiB in GL but 36 KiB as MSL `float3` (16-byte stride),
over Metal's 32 KiB. When Metal reports that, `ComputeTranslator.packThreadgroupVectors` switches the threadgroup
declarations to `packed_float3` (plus a conversion operator so packed rows can be passed by value). Photon's sky SH
kernel needs this.

## SSBOs and custom images

- `StorageBuffers` (Iris's `ShaderStorageBufferHolder`): one `MTLBuffer` per `bufferObject.N`, shared storage, created
  with the pipeline and kept across frames, zero-filled or filled from the pack file, screen-relative ones re-created
  on resize. Unused bindings get a zero buffer (GL reads zeros).
- `CustomImages` (Iris's `GlImage`): `image.<name> = <sampler> <format> <internalFormat> <type> <clear> <relative> w h [d]`
  as Metal textures (1D as 2D, 2D, 3D; relative sizes; `ShaderAtomic` usage for R32 integer formats), zeroed on
  creation, per frame when `clear = true`. They are wrapped as renderpearl `GpuTexture`s
  (`MetalliumInterop.wrapTexture`), so `<sampler>` names bind in render programs like any pack sampler, 3D included.
- colortex/shadowcolor buffers used as images are created with `MetalliumInterop.TEXTURE_USAGE_STORAGE` (a usage bit
  the renderpearl frontend passes through; the backend adds ShaderWrite / ShaderAtomic). Which buffers need it is known
  from the compiled computes plus a scan of all pack sources (gbuffers programs compile lazily, mid-frame).

## Images and SSBOs in render programs

gbuffers/shadow/fullscreen programs may also use them (voxelization in `shadow.vsh`, composite passes reading voxel
data or SSBOs). renderpearl's binding table has no storage resources, so `StorageBindings` writes Metal indices above
the backend's own into the SPIR-V binding decorations (images `texture(32+k)`, SSBOs `buffer(30..26)`; the backend
leaves bindings outside its table alone and SPIRV-Cross uses them as MSL indices). They are bound per draw through a
new backend hook, `MetalliumInterop.setDrawBindings(pass, binder)`: `MetalRenderPass` applies the binder before the
next draw and again whenever it re-creates its encoder.

## Mipmapped buffers

`colortexNMipmapEnabled` in a program's source: the buffer is allocated with a full mip chain (render passes attach a
mip-0 view), and right before that pass the chain of the texture it reads is generated (`generateMipmapsForTexture`),
like Iris's `setupMipmapping`. Until then in a frame the buffer is sampled without mip filtering (GL: the texture's
min filter is non-mipmapped until Iris's `turnOnMips`).

## Iris capabilities

On Metal, `IrisRenderSystem.supportsCompute/SSBO/ImageLoadStore` answer true and `getMaxImageUnits` 8, SSBO bindings 16
(`SamplerLimits`), so packs get `IRIS_FEATURE_COMPUTE_SHADERS`/`SSBO`/`CUSTOM_IMAGES` and Iris accepts `bufferObject`
and `image.` directives.

## Translation fixes found on the way (core translator / backend)

- GL identifiers that are reserved only in Vulkan GLSL (`sampler`, `samplerShadow`) or that are Metal standard-library
  functions packs define themselves (`length_squared`, `saturate`, ...) are renamed before glslang (Photon).
- Struct varyings take one location per member (Photon's `flat out CloudsParameters`), fixing overlapping locations.
- Backend: fragment outputs are padded to four components (packs write `vec3` into RGBA buffers).

## Results (M3 Max, 2026-10-18)

- Synthetic test pack `src/gametest/resources/metallium-computepack` (game test `metallium-iris-computepack`): setup
  pass fills an SSBO and a 3D custom image; `composite.csh` writes colorimg2 and counts every pixel with an image atomic
  into a cleared custom image; compute-only `composite1.csh` verifies counter, SSBO and `.length()`; `composite2.fsh`
  reads the SSBO and the 3D image from a fragment program, stores into colorimg5, and checks the mip chain of the
  mipmapped colortex2; `composite3.fsh` shows colorimg5. All bands pass (1.00), magenta marker left half 1.00 / right
  0.00.
- Photon v1.3b: its compute program `deferred4_a` (sky SH) translates and runs; with the translator fixes above all its
  gbuffers/shadow/composite/final programs translate except four (`deferred`, `deferred1`, `deferred4`, `composite`)
  that sample 3D **custom textures** (`texture.deferred.colortex6.1 = *.dat TEXTURE_3D`), a stage-2 gap; the image is
  black without them. OpenGL comparison is impossible (macOS GL 4.1 has no compute).
- Complementary Unbound r5.6.1 (default settings, no compute): unchanged, now with its mipmapped colortex0/3.
  With `COLORED_LIGHTING=128`: the pack forces it to 0 when `MC_OS_MAC` is defined (Apple GL), so the test uses the
  opt-in `-PirisHideMacOs` (packs see `MC_OS_UNKNOWN` on Metal). Then voxel_img/floodfill (3D, 128x64x128) are written
  by `shadow.vsh` image stores and the floodfill `shadowcomp.csh`; all programs translate; in the night scene the
  soul lantern casts teal light, the lamp/glowstone warm light (game test shot `external-pack-night-lights`).
- BSL v10.1.8 with `MULTICOLORED_BLOCKLIGHT` (+ `-PirisHideMacOs`): 256^3 voxel/light images, shadowcomp compute; all
  programs translate and run (after renaming GLSL identifiers that are C++ keywords, BSL's `bool new`).
- Bliss v2.1.2 with `LPV_ENABLED`: setup and shadowcomp computes run; several other programs fail on stage-3 limits
  (9 draw buffers, >16 samplers) and glslang's precision-qualifier overload check.

**Decision for the owner:** hide `MC_OS_MAC` from packs on Metal by default? Packs use it as "Apple OpenGL 4.1" and
disable compute/voxel features (Complementary) or apply GL driver workarounds. On Metal the former are wrong, the
latter mostly unnecessary. Currently opt-in (`-Dmetallium.iris.hideMacOs=true`).

## Not supported yet

- Custom textures (`texture.<stage>.<sampler>`, incl. 3D `.dat`), `size.buffer.*` (Photon), shadowcolor mipmaps.
- Image formats Metal cannot read-write (only write) are not checked up front (Metal validation would reject the pipeline).
- More than 5 SSBOs or 16 images in one render program; geometry shaders (voxelization in `.gsh`).
- Memory barriers between draws inside one render pass (fragment writes read by later draws of the same pass).
