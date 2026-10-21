# Far terrain under shader packs on Metal (Voxy, Distant Horizons)

Status: Voxy implemented (2026-10-21), Distant Horizons see §4. Iris-side code lives in `metallium-iris` (LGPL-3.0),
GL-name plumbing in `metallium-gl` (MIT), the safety net in core `compat/voxy`.

## 1. How it works on OpenGL (verified in the sources / bytecode)

- **Voxy** does *not* use the packs' `dh_*` programs. Packs that support Voxy ship `voxy.json` plus
  `voxy_opaque.glsl` / `voxy_translucent.glsl` (Complementary Reimagined r5.9.3, Photon 1.3b, BSL 10.1.8; Complementary
  Unbound r5.6.1 has none). Voxy's own Iris mixins (read-only interop, Voxy is "All rights reserved"):
  - `ProgramSet.<init>`: parses the pack's `voxy.json` into an `IrisShaderPatch` (kept on the ProgramSet);
  - `StandardMacros`: defines `VOXY`, so the pack's deferred/composite programs read `vxDepthTexOpaque/Trans`;
  - `CommonUniforms.addNonDynamicUniforms`: adds `vxRenderDistance`, `vxNear/FarPlane`, `vxModelView(Inv|Prev)`,
    `vxProj(Inv|Prev)`, `vxViewProj(Inv|Prev)` to every program;
  - `IrisRenderingPipeline.<init>`: builds `IrisVoxyRenderPipelineData` from the GL pipeline (uniform struct from the
    pack's custom uniforms, sampler set via `addGbufferOrShadowSamplers`, draw buffers = the pack's colortex GL names);
  - `IrisSamplers.addRenderTargetSamplers`: adds `vxDepthTexOpaque/Trans` = Voxy's own depth textures;
  - `IrisRenderingPipeline.beginLevelRendering`: applies the camera captured at `LevelRenderer.render` to Voxy's viewport.
  Voxy's `RenderPipelineFactory` then creates `IrisVoxyRenderPipeline` if the Iris pipeline implements Voxy's
  `IGetIrisVoxyPipelineData`; it draws the LODs, with the pack's patch compiled into Voxy's shaders, into the pack's
  gbuffers (e.g. colortex0/6) with its own depth buffer (excluded from vanilla depth), inside Sodium's opaque pass.
- **Distant Horizons**: Iris's `compat/dh` hooks DH's *OpenGL* renderer through the DH API (framebuffer + shader
  program overrides running `dh_terrain` / `dh_water` / `dh_shadow`, `dhDepthTex0/1`, `dh*` uniforms).

## 2. Why it broke on Metal

`MetalPostPipeline` replaces `IrisRenderingPipeline`, so Voxy's pipeline-data mixin never runs: Voxy falls back to its
`NormalRenderPipeline` (vanilla-shaded LODs into the main colour target, which the pack does not read), while the pack
is still compiled with `VOXY` and samples `vxDepthTexOpaque/Trans`, which had no binding (colortex0 / white bound
instead) — the stripes and the fire-textured band. Hence the safety net (pause Voxy while a pack renders on Metal).

## 3. Voxy design: let Voxy's own Iris pipeline run, with GL names for the Metal targets

Chosen over re-implementing Voxy's Iris pipeline (licence: no copying; and Voxy's code already does all of it through
metallium-gl) and over translating `dh_*` programs for Voxy geometry (packs write Voxy-specific code in
`voxy_*.glsl`, not `dh_*`).

1. `VoxyPipelineFactoryMixin` (`@Pseudo`, `RenderPipelineFactory.createIrisPipeline` HEAD): if the active Iris
   pipeline is a `MetalPostPipeline` with gbuffers and Voxy is routed through metallium-gl, `VoxyIrisBridge` builds
   Voxy's pipeline data by calling Voxy's own `IrisVoxyRenderPipelineData.buildPipeline` (reflection) with
   - a **facade** `IrisRenderingPipeline` (allocated without constructor; never registered with Iris) whose
     `getFlippedAfterPrepare()` is the pack's opaque flip state, whose `addGbufferOrShadowSamplers` adds the Iris
     gbuffer sampler names (colortex4+, depthtex0-2, noisetex, shadowtex/shadowcolor, custom textures, `vxDepthTex*`,
     external `tex`/`lightmap` units) with GL names of the Metal textures, and whose `renderTargets` field is a fake
     `RenderTargets` holding the pack's draw buffers as **stable GL names**;
   - the pipeline's `CustomUniforms` (so the pack's custom uniforms and Voxy's `vx*` uniforms are written by Voxy);
   - no SSBO holder (none of the tested packs declare SSBOs for Voxy; a pack that does fails → fallback).
   It returns `new IrisVoxyRenderPipeline(...)`; on any failure it returns nothing and Voxy uses its normal pipeline
   (then the safety net pauses Voxy as before).
2. **metallium-gl names** (`MetalGLInterop`): sampled textures use `glId(GpuTexture)` (looked up per bind, follows
   re-creation); draw buffers use `stableTextureName(key, texture)` — one name per (buffer, main/alt) that is re-pointed
   to the new Metal texture after a resize (`GlExternalTextures.stableName`, like GL storage re-specification;
   attachments of it in Voxy's framebuffers follow). Samplers: metallium-gl sampler objects (nearest / linear /
   linear-repeat / depth-compare) wrapped as Iris `GlSampler(int)`. `metalTexture(name)` gives the `MTLTexture` of
   Voxy's depth buffers.
3. `MetalPostPipeline`: `beginLevelRendering` re-points the stable names and applies Voxy's captured viewport (as Voxy's
   Iris mixin does); `vxDepthTexOpaque/Trans` in gbuffers/deferred/composite passes are Voxy's two depth textures
   (wrapped once per Metal texture as renderpearl textures), white (= depth 1, "no LOD") without Voxy; `dhDepthTex*`
   white as well.
4. Ordering: Voxy draws inside Sodium's opaque pass via metallium-gl (Metallium's encoder is ended and resumed, as
   without shaders); the deferred passes run at `beginTranslucents`, after that, and read the LOD gbuffers + depth.

## 4. Distant Horizons

On Metal DH runs its Blaze3D (renderpearl) renderer (`DhIrisEngineMixin`); Iris's DH integration only exists for DH's
OpenGL renderer (DH API program/framebuffer overrides with GL program and framebuffer ids). Shading DH's LODs with
`dh_terrain` would mean substituting DH's Blaze3D terrain pipeline with a translated `dh_terrain` program in DH's
vertex format and redirecting DH's render target to the pack's gbuffers (similar to `GbufferPasses`), plus
`dhDepthTex0/1` from DH's depth and the DH uniforms. Not done in this step (see ROADMAP); `dhDepthTex*` are bound to
white so packs that sample them do not read garbage.
