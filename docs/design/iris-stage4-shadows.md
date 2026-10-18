# Iris adapter stage 4: the shadow pass on Metal

Status: implemented 2026-10-18 (`metallium-iris`, package `dev.metallium.iris.post`: `MetalShadowRenderer`,
`ShadowTargets`; shadow mode in `GbufferState`/`GbufferProgram`; backend extension `MetalDepthCompareSamplers`).
Blueprint context: `iris-adapter-blueprint.md` §6 stage 4; stage 3: `iris-stage3-gbuffers.md`.

## How Iris does it on OpenGL

`MixinLevelRenderer` calls `WorldRenderingPipeline.renderShadows` right before the main pass is added to the frame
graph (so the shadow map is drawn before any level pass executes). `ShadowRenderer.renderShadows` sets
`ShadowRenderer.ACTIVE`, builds the sun-view model-view (`ShadowMatrices`) and an orthographic projection, prepares a
shadow frustum (advanced / safe-zone / distance culling), switches Sodium to its separate shadow render lists (Iris's
Sodium mixins), and opens ordinary render passes **on the main target** for opaque terrain, entities + block entities
and translucent terrain (copying `shadowtex0` to `shadowtex1` before the translucents). On GL those passes never bind
the main framebuffer: `MixinGlCommandEncoder` keeps the shadow FBO bound and the shadow viewport, `MixinGlRenderPipeline`
disables culling and flips the depth comparison, and `IrisPipelines` maps every pipeline to a `SHADOW_*` `ShaderKey`
while `ACTIVE` is set. Then `prepare` passes run.

## Metal: the stage-3 mechanism with a second capture target

Nothing in Iris's shadow logic is GL-specific except the state tricks above, so stage 4 reuses stage 3's capture:

1. **`MetalPostPipeline.renderShadows`** (overrides the no-op of `VanillaRenderingPipeline`; Iris's mixin calls it because
   `isPackInUseQuick()` is true for our pipeline) runs `MetalShadowRenderer` and then the `prepare` passes (they used to
   run at `beginLevelRendering`; without a shadow pass they still do).
2. **`MetalShadowRenderer`** is Iris's `ShadowRenderer` adapted (LGPL-3.0 notice kept, see README credits): same matrices,
   frustum setup, Sodium shadow-list scope, entity/block-entity extraction and pass order; the GL calls are gone, the
   projection goes through `RenderSystem.setProjectionMatrix` with its own `ProjectionMatrixBuffer`.
3. **Retargeting.** The stage-3 hook on `FrontendCommandEncoder#createRenderPass` sees those passes (main colour target,
   capture active). While `ShadowRenderingState.areShadowsCurrentlyBeingRendered()`, `GbufferState.descriptor` builds a
   shadow descriptor instead: colour attachments = the union of the `DRAWBUFFERS` of all `shadow*` programs (plus
   shadowcolor0, max 8), depth = `shadowtex0`. The Metal viewport comes from the attachment size, so the shadow-map
   resolution needs no viewport override.
4. **Substitution.** `IrisPipelines.getPipeline` returns `SHADOW_*` keys during the pass; `GbufferState.substitute`
   compiles them like gbuffers programs (`ProgramFallbackResolver` -> `shadow_solid`/`shadow_cutout`/... -> `shadow`;
   `TransformPatcher.patchSodium(..., shadow=true)` / `patchVanilla` with the `shadow_*` key name, so Iris's
   `DepthTransformer` produces forward-Z clip depth) with the shadow attachments, **cull off** and the **depth test
   flipped** (`GREATER*` <-> `LESS*`, `GbufferProgram.shadowDepth`), exactly what Iris's `MixinGlRenderPipeline` does.
   Pipelines without a shadow program get a copy of the vanilla pipeline that writes depth only.
5. **Uniforms.** `iris_ProjMatInverse` uses `undoRevZ(ShadowRenderer.PROJECTION)` during the pass (as `ExtendedShader`);
   `shadowModelView`/`shadowProjection` uniforms come from Iris's `MatrixUniforms` unchanged.

## Shadow targets and samplers

`ShadowTargets`: `shadowtex0`/`shadowtex1` `D32_FLOAT` at `shadowMapResolution`, cleared to 1.0 per frame (forward Z);
`shadowcolor0..7` main/alt pairs created on first use, formats/clear colours/`shadowcolorNClear` from
`PackShadowDirectives`. They always exist, so packs that sample shadow maps without a shadow pass read "fully lit".

Binding (`MetalPostPipeline.bindShadowSampler`, used by gbuffers and fullscreen passes) follows Iris's
`IrisSamplers.addShadowSamplers`: `shadowtex0`/`shadow` (or `watershadow`) = all casters, `shadowtex1` (or `shadow` with
`watershadow`) = opaque casters, `shadowtexNHW`, `shadowcolor`/`shadowcolorN`. During the shadow pass itself
`shadowtex0` is the attachment, so reads get `shadowtex1`.

**Depth-compare sampling.** A `sampler2DShadow` becomes `depth2d<float>` + `sample_compare` in MSL, which takes the
compare function from the sampler state; renderpearl's `GpuDevice.createSampler` cannot express that. The isolated
backend extension `dev.metallium.backend.MetalDepthCompareSamplers` creates a `MetalGpuSampler` with
`MTLSamplerDescriptor.compareFunction` (GL's `GL_TEXTURE_COMPARE_MODE`, Iris uses `LEQUAL`). Which samplers need it is
read from SPIR-V (`GlslProgramTranslator.depthCompareSamplerNames`: combined image samplers whose image is a depth
image), so it works whether or not the pack sets `shadowHardwareFiltering`. Linear/nearest follows `shadowtexNNearest`.

## Results (M3 Max, 2026-10-18)

- Synthetic shadow pack (`src/gametest/resources/metallium-shadowpack`: `shadow` program + composite that projects every
  pixel into the shadow map through a `sampler2DShadow` and paints shadowed pixels magenta): at noon a floating stone
  platform shadows the ground below it - shadowed fraction 1.00 under the platform, 0.00 in the same area without it,
  0.00 on the far ground. The same pack gives the same numbers on Iris/OpenGL (`-Pengine=OPENGL`).
- Complementary Unbound r5.6.1 (local only, `-Pshaderpack=`): `shadow` program runs at 2048 px (shadowcolor0). Game-test
  scene `metallium-iris-external-pack-shadows` (flat world, time 1500, a log + leaf canopy and a stone-brick wall):
  the canopy's leaf-patterned shadow, the trunk and the wall shadows fall in the same places and shapes as with Iris on
  OpenGL (`-Pengine=OPENGL`, same scene). Remaining visible differences are stage-2/3 items (horizon band, no mipmaps).

## Not supported yet

- `shadowcomp` passes (fullscreen on the shadow targets) and shadow compute shaders (stage 5); Complementary's
  `shadowcomp` is a compute shader anyway. Reported at pack load.
- Mipmapped shadow maps (`shadowtexNMipmap`, `shadowcolorNMipmap`): sampled without mipmaps.
- Shadow programs with geometry shaders (voxelization): vanilla shader writes depth only, culling as Iris without
  voxelization. DH shadows (`dh_shadow`).
- `ShadowRenderCallbacks` (Iris API for mods drawing into the shadow map) are not invoked.
- The shadow pass redraws terrain every frame like Iris (no caching).
