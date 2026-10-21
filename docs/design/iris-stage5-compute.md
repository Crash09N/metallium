# Iris adapter stage 5: compute shaders, images, SSBOs and mipmapped buffers on Metal

Status: implemented 2026-10-18 (`nativium-iris`, package `de.nprmedia.nativium.iris.post`: `ComputePass`, `MetalCompute`,
`StorageBuffers`, `CustomImages`, `StorageBindings`; core: `de.nprmedia.nativium.shaders.ComputeTranslator`; backend
extensions in `NativiumInterop`). Previous stages: `iris-stage2-post-pipeline.md` .. `iris-stage4-shadows.md`.

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

**Encoding.** `MetalCompute` opens an `MTLComputeCommandEncoder` on Nativium's frame command buffer through the
`NativiumInterop` external-encoder contract (end Nativium's encoder, wait for / update the frame fence, closed again as
soon as Nativium encodes). Compute work is therefore ordered exactly where Iris issues it between render passes.
Dispatches in one encoder are serial (default dispatch type), which provides `glMemoryBarrier` ordering. All resources
are untracked like Nativium's own. Mipmap generation uses a blit encoder on the same path.

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
  (`NativiumInterop.wrapTexture`), so `<sampler>` names bind in render programs like any pack sampler, 3D included.
- colortex/shadowcolor buffers used as images are created with `NativiumInterop.TEXTURE_USAGE_STORAGE` (a usage bit
  the renderpearl frontend passes through; the backend adds ShaderWrite / ShaderAtomic). Which buffers need it is known
  from the compiled computes plus a scan of all pack sources (gbuffers programs compile lazily, mid-frame).

## Images and SSBOs in render programs

gbuffers/shadow/fullscreen programs may also use them (voxelization in `shadow.vsh`, composite passes reading voxel
data or SSBOs). renderpearl's binding table has no storage resources, so `StorageBindings` writes Metal indices above
the backend's own into the SPIR-V binding decorations (images `texture(32+k)`, SSBOs `buffer(30..26)`; the backend
leaves bindings outside its table alone and SPIRV-Cross uses them as MSL indices). They are bound per draw through a
new backend hook, `NativiumInterop.setDrawBindings(pass, binder)`: `MetalRenderPass` applies the binder before the
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

- Synthetic test pack `src/gametest/resources/nativium-computepack` (game test `nativium-iris-computepack`): setup
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

**`MC_OS_MAC` (owner decision 2026-10-18):** hidden from packs on Metal by default (packs see `MC_OS_UNKNOWN`):
packs use it as "Apple OpenGL 4.1" and disable compute/voxel features. Config `irisHideMacOs = false` (or
`-Dnativium.iris.hideMacOs=false`, game test `-PirisHideMacOs=false`) restores it.

### Photon / Bliss / Unbound follow-up (2026-10-18)

- **Custom textures** (`CustomTextures`, like Iris's `CustomTextureManager`): `texture.<stage>.<sampler>` PNG /
  `minecraft:` resource / lightmap overrides per stage (also reported through `getTextureOverrides`), raw
  `customtexN` / `customTexture.<name>` textures (1D as 2D, 2D, 3D; data converted by the core `RawTexels` with GL
  unpack rules: row alignment 4, RGB widened to RGBA, normalised type conversion, BGR swizzle) and `texture.noise`.
  Blur/clamp flags pick the sampler; bound in fullscreen, gbuffer and compute passes. Backend samplers now wrap R like U.
- **`size.buffer.<colortexN>`**: absolute or relative buffer sizes (`PostTargets.size`).
- **More than 16 textures in one pass** (Bliss's composite4): Metal has 16 sampler states per stage. Textures whose
  sampler is fixed (colortex/depthtex/noise/custom; not mipmapped or integer buffers, not shadow maps) move to the
  end of the binding list and are compiled as MSL `constexpr sampler`s (`NativiumInterop.setConstexprSamplers`,
  per pipeline name, part of the MSL cache key). Metal counts identical constexpr samplers once (verified: 16 bound +
  2 identical constexpr = 17 fails, 2 bound + 18 identical constexpr compiles). Textures at index >= 16 are bound
  without a sampler state.
- **More than 8 gbuffer outputs** (Bliss: colortex1,2,7,8,9,11,14,15): programs without fragment outputs do not count,
  colortex0 (vanilla fallbacks) is only added when it fits, outputs without attachment become plain globals, and
  outputs a program never assigns get an empty write mask.
- **Translator**: packs' own definitions of built-ins newer than their `#version` (`float tanh(float)`,
  `fma`) are renamed (glslang: "'highp' : overloaded functions must have the same parameter precision qualifiers");
  `#version 330` sources with `GL_ARB_shader_storage_buffer_object` are raised to 430, other pre-4.20 sources with
  `binding` qualifiers get `GL_ARB_shading_language_420pack` (Unbound's colored lighting SSBO).
- Test pack `nativium-texpack` (game test `nativium-iris-texpack`): PNG override with repeat, raw RGB16F 3D texture,
  custom noise, `size.buffer`, an 18-texture pass that also passes textures to a function; all 1.00.
- Results (M3 Max, game test with `-Pshaderpack`): Photon v1.3b renders (volumetric clouds, shadows, night lights);
  Bliss v2.1.2 renders day scenes like Iris on OpenGL, but its night sky shows a noisy terrain-like pattern and rain
  streaks are too strong (OpenGL reference: dark-blue sky, faint rain) - open; Complementary Unbound r5.6.1 with the
  owner's settings (`COLORED_LIGHTING=512`) runs colored lighting without the "not supported on Apple" notice.

## Not supported yet

- Shadowcolor mipmaps; PBR suffixes (`_n`/`_s`) on `minecraft:` custom textures; Bliss night sky / rain (above).
- Image formats Metal cannot read-write (only write) are not checked up front (Metal validation would reject the pipeline).
- More than 5 SSBOs or 16 images in one render program; geometry shaders (voxelization in `.gsh`).
- Memory barriers between draws inside one render pass (fragment writes read by later draws of the same pass).

## Performance: Complementary Unbound with colored lighting (2026-10-19)

Owner settings (ULTRA-like: `COLORED_LIGHTING=512`, `WORLD_SPACE_REFLECTIONS=1`, `SHADOW_QUALITY=4`,
`shadowDistance=256`, `ANISOTROPIC_FILTER=8`), 2560x1440 window on the 144 Hz display, bench scene `shaders`
(`-PbenchWindow=2560x1440 -PshaderpackOptions=...`), M3 Max. Per-pass GPU time from Metal System Trace
(`xcrun xctrace record --template 'Metal System Trace' --attach <pid>`; encoder order mapped with `-PperfTrace`):

| pass | GPU ms/frame |
|------|-------------:|
| Sky + Main (gbuffers_terrain/entities, 5 colour attachments) | ~9.6 |
| composite (volumetric light, clouds) | ~3.0 |
| floodfill (`shadowcomp.csh`, 512x256x512 RGBA16F ping-pong, ~2 GB/frame of traffic) | ~2.8 |
| deferred1 | ~2.5 |
| composite1 | ~2.5 |
| shadow pass (2048 px, voxelization in shadow.vsh) | ~1.6 |
| custom image clears (`voxel_img`, `wsr_img`, clear=true) | 1.3 → 0.4 (512-thread groups) |
| translucent terrain, composite3-7, final | ~3 |

Total ~30 ms GPU per frame in the plains view, ~32 ms over an ocean (`-PbenchBiome=minecraft:ocean`).
Colored lighting off: ~14 ms. Same pack/settings with colored lighting off on Apple's OpenGL: 33.9 fps vs 67.1 fps
on Metal; Reimagined (owner settings): 26.8 fps OpenGL vs 138.6 fps Metal. The floodfill is bandwidth-bound
(close to the M3 Max's memory bandwidth); the rest is the pack's own fragment work at 3.7 MP, no translation
pathology found in the dumped MSL (`-PshaderDump=<dir>`). Bench FPS of heavy packs is unreliable in the game
test (client and integrated server run in lock-step: runs settle at 20 fps = the tick rate when a frame takes longer
than ~30 ms); use GPU time from the trace or the `gpu=` perf counter.

### Default settings, per pass, and what the translation can still change (2026-10-19, second pass)

Complementary Unbound r5.6.1 with default settings plus `COLORED_LIGHTING`, `-PirisHideMacOs`, bench scene `shaders`,
`-PbenchWindow=2560x1440`. Per-encoder GPU time is from Metal System Trace, summarised by
`tools/metal-trace-encoders.py` (pass names from a `-PperfTrace` run). These are preliminary: the window was
visible but not focused and the owner was at the Mac. Re-measure with the focused bench helper.

| encoder (pass) | CL off | CL 512 + `shadowDistance=256` |
|---|---:|---:|
| `voxel_img` clear (compute, 512x256x512 R8) | – | 0.31 |
| shadow terrain (voxelization in `shadow.vsh`) | 0.76 (vertex 0.49) | 1.56 (vertex 1.25) |
| floodfill `shadowcomp.csh` | – | 3.14 |
| Sky + Main (gbuffers terrain) | 1.45 | 1.86 |
| deferred1 | 0.77 | 0.70 |
| translucent terrain | 0.81 | 0.90 |
| composite | 0.31 | 0.29 |
| composite1 (coloured light fog: up to 24 trilinear floodfill samples per pixel) | 1.21 | 2.08 |
| composite3-7, final (with their mip blits) | 1.28 | 1.18 |
| **frame (command buffer wall)** | **7.0** | **12.5** (77.9 fps) |

Benchmark pitfall: `COLORED_LIGHTING=512` with the default `shadowDistance` (192) makes the pack draw its "Advanced
Color Tracing must not be higher than Shadow Distance" error text in `final` (+1.2 ms, 14.0 ms per frame). Pair 512
with `shadowDistance=256`, as the ULTRA profile does.

What was checked against the generated MSL, the AIR and isolated Metal micro-benchmarks (Swift harness running the
translated `shadowcomp` kernel on 512x256x512 textures):

- Formats: the images already use the declared formats (`r8ui`, `rgba16f`). Nothing wider is allocated.
- Access: `writeonly` images become `access::write`. The ping-pong inputs are sampled `texture3d<float>` read with
  `.read()`, so they take the texture-cache path. No `read_write` access is generated.
- Floodfill: 3.06 ms in isolation, the same (±1 %) for threadgroup shapes 8x8x8, 4x4x4, 32x4x4, 16x8x4, 32x8x2,
  32x32x1 and 8x8x4, for all-zero or non-zero light data, and with `allowGPUOptimizedContents` off. It moves about
  1.1 GB per frame (every voxel reads 8 B and writes 8 B, plus the 1 B voxel reads; the six neighbour reads hit the
  cache), which is about 360 GB/s and close to the M3 Max's 400 GB/s. It is bound by memory bandwidth, so no
  translation-side change can speed it up without changing the pack's algorithm.
- Global GLSL arrays (`vec3[] specialTintColor = ...`, not `const`) become per-thread copies in SPIRV-Cross's MSL.
  The Metal frontend's -O2 turns these into direct `constant` loads: no stack allocations are left in the AIR of the
  hot programs (the only ones left are a nether-portal branch in gbuffers terrain and the text renderer in `final`).
  Making the copies constant by hand gave no measurable change.
- `voxel_img` clear: the compute clear takes 0.307 ms for every threadgroup shape tried and also with 4 texels per
  thread (store-bound, about 218 Gtexel/s). Render-pass load-action clears of the 3D texture are slower: 1.76 ms with
  8 depth planes per pass and 19.9 ms with one plane per pass. The clear stays as it is.
- The rest of the extra cost is the pack's own work: voxelization per shadow vertex, `GetLightVolume` per terrain
  fragment and the fog raymarch in composite1.

Remaining structural option (not done; it would change the backend's fence chain, which other work owns): with the
floodfill ping-pong, frame N+1's clear, shadow pass and floodfill only conflict with frame N's terrain passes (the
last readers of `voxel_img`). They could overlap frame N's composite/final passes, which take about 5 ms, if those
encoders waited on a resource-specific fence instead of the single frame fence.
