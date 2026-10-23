# Far terrain under shader packs on Metal (Voxy, Distant Horizons)

Status: Voxy implemented (2026-10-21), Distant Horizons see §4. Iris-side code lives in `nativium-iris` (LGPL-3.0),
GL-name plumbing in `nativium-gl` (MIT), the safety net in core `compat/voxy`.

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
nativium-gl) and over translating `dh_*` programs for Voxy geometry (packs write Voxy-specific code in
`voxy_*.glsl`, not `dh_*`).

1. `VoxyPipelineFactoryMixin` (`@Pseudo`, `RenderPipelineFactory.createIrisPipeline` HEAD): if the active Iris
   pipeline is a `MetalPostPipeline` with gbuffers and Voxy is routed through nativium-gl, `VoxyIrisBridge` builds
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
2. **nativium-gl names** (`MetalGLInterop`): sampled textures use `glId(GpuTexture)` (looked up per bind, follows
   re-creation); draw buffers use `stableTextureName(key, texture)` — one name per (buffer, main/alt) that is re-pointed
   to the new Metal texture after a resize (`GlExternalTextures.stableName`, like GL storage re-specification;
   attachments of it in Voxy's framebuffers follow). Samplers: nativium-gl sampler objects (nearest / linear /
   linear-repeat / depth-compare) wrapped as Iris `GlSampler(int)`. `metalTexture(name)` gives the `MTLTexture` of
   Voxy's depth buffers.
3. `MetalPostPipeline`: `beginLevelRendering` re-points the stable names and applies Voxy's captured viewport (as Voxy's
   Iris mixin does); `vxDepthTexOpaque/Trans` in gbuffers/deferred/composite passes are Voxy's two depth textures
   (wrapped once per Metal texture as renderpearl textures), white (= depth 1, "no LOD") without Voxy; `dhDepthTex*`
   white as well.
4. Ordering: Voxy draws inside Sodium's opaque pass via nativium-gl (Nativium's encoder is ended and resumed, as
   without shaders); the deferred passes run at `beginTranslucents`, after that, and read the LOD gbuffers + depth.

## 4. Distant Horizons (implemented 2026-10-22: `DhIrisBridge`)

On Metal DH runs its Blaze3D (renderpearl) renderer (`DhIrisEngineMixin`); Iris's DH integration only exists for DH's
OpenGL renderer (DH API program/framebuffer overrides with GL ids). DH's GL renderer cannot run on Metal (DH checks that
Minecraft itself renders with OpenGL and reads Minecraft's targets as GL textures), so the Blaze3D passes are
redirected instead, with the same machinery as the vanilla/Sodium passes (`GbufferPasses`):

1. **Terrain pass** (label `distantHorizons:TerrainRenderer`, DH's `DhColorTexture` + `DhDepthTexture`): captured;
   its colour attachments become the pack's gbuffer attachments (no clear: the vanilla scene is already there), the
   depth stays DH's own depth texture. That texture is `dhDepthTex`/`dhDepthTex0`/`dhDepthTex1` in the pack's passes
   (DH's opaque and transparent terrain share the pass, so `dhDepthTex1` = `dhDepthTex0` for now).
2. **Programs**: DH's pipelines `distanthorizons:opaque_terrain` / `transparent_terrain` are replaced by the pack's
   `dh_terrain` / `dh_water` (Iris's fallback chain), patched by Iris's own `TransformPatcher.patchDHTerrain` and then
   adapted (`DhIrisBridge.adaptVertex`): Iris's DH patch expects DH's OpenGL layout (`uvec4 vPosition` = position +
   meta, `vec4 iris_color`, `uvec4 irisExtra` = material, normal, texture tile) and loose `modelOffset`/`mircoOffset`;
   the Blaze3D pipeline delivers the same data as six attributes (`vPosition`, `meta`, `vColor`, `irisMaterial`,
   `irisNormal`, `textureTile`) and the uniform blocks `vertUniqueUniformBlock` (`uModelOffset`) /
   `vertSharedUniformBlock` (`uCameraPos`, `uWorldYOffset`, `uMircoOffset`, ...), which DH binds per draw by name.
   Macros map one onto the other (`modelOffset = uModelOffset - uCameraPos + (0, uWorldYOffset, 0)`). Samplers:
   `dhBlockAtlas` = DH's `uBlockAtlas`, `lightmap` = `uLightMap`. Per draw (like Iris's `IrisLodRenderProgram`):
   `iris_ModelViewMatrix(Inverse)` = camera model-view, `iris_ProjectionMatrix(Inverse)` = `DHCompat.getProjection()`,
   `iris_NormalMatrix`, `clipDistance` = DH's near clip distance (DH API).
3. **DH's copy/apply passes** (DH's colour onto the main target) draw into a scratch texture: the pack composites the
   LODs in its deferred/composite passes (`dhDepthTex*`, `dh*` uniforms from Iris's own uniform code,
   `DISTANT_HORIZONS` from Iris's macros).

Verified: Complementary Reimagined r5.9.3 (game test `-PwithIris -PwithDH -Pshaderpack=...`: shaded LODs to the
horizon).

4. **Generic objects (`dh_generic`, 2026-10-23)**: DH's clouds and beacon beams (pass
   `distantHorizons:GenericObjectRenderer`, pipeline `distanthorizons:generic_objects`) used to land in DH's colour
   texture, which nothing shows while a pack is on. The pass is now captured like the terrain pass (gbuffer
   attachments, DH's depth, so they are part of `dhDepthTex`) and its pipeline becomes the pack's `dh_generic` (Iris's
   fallback: `dh_terrain`, which is what Complementary/Photon/BSL get), patched by Iris's
   `TransformPatcher.patchDHGeneric` and adapted (`DhIrisBridge.adaptGeneric`): Iris expects DH's OpenGL instanced
   boxes (per-instance `aScale`/`aTranslateChunk`/`aTranslateSubChunk`, `iris_color`, `int aMaterial`, loose box-group
   uniforms); the Blaze3D pipeline draws plain 24-vertex boxes (`vPosition`, `aColor`, `uint aMaterial`) with the group
   data in `vertUniformBlock`, so the instance attributes become constants, `gl_VertexID` is taken modulo 24 (face
   normal by vertex index, like DH's own shader) and the uniforms come from the block. Matrices like Iris's
   `IrisGenericRenderProgram` (camera model-view, DH projection). Off switch: `-Dnativium.iris.noDhGeneric=true`
   (game test `-PnoDhGeneric`).
5. **LOD shadows (`dh_shadow`, 2026-10-23)**: only packs that ship `dh_shadow` and do not set `dhShadow.enabled=false`
   (like Iris: of the test packs only Bliss with its `DISTANT_HORIZONS_SHADOWMAP` option; Complementary, Photon, BSL
   disable it). DH can render a second view cheaply: its core already supports an Iris shadow pass (`ClientApi`
   skips camera updates, `RenderBufferHandler` culls with an `IDhApiShadowCullingFrustum` override). Three things were
   in the way on Metal:
   - DH's render hook (`SodiumChunkSection.renderGroup` HEAD) does not fire for `MetalShadowRenderer`'s own
     `renderGroup` call, so `DhIrisBridge.renderShadowLods` calls `ClientApi.renderLods()` after the shadow pass's opaque
     terrain (skipped while `DhRenderState.unableToRenderBecause()` is non-empty, like DH's `canRenderOrThrow`);
   - Iris's DH events (`LodRendererEvents.setupTransparentRendererEventCancling`) cancel DH's render during any Iris
     shadow pass unless Iris's OpenGL DH pipeline has a shadow program; on Metal that pipeline never exists. The two
     anonymous handlers are found by their enclosing method and replaced by equivalents that also let DH through when
     `dh_shadow` runs on Metal (`DhShadowHooks`, hooked lazily, Metal only, kept out of `DhIrisBridge` so that loads
     without DH; if the handlers are not found, dh_shadow stays off);
   - culling: a `DhApiBeforeRenderSetupEvent` handler bound after Iris's binds Iris's shadow frustum
     (`ShadowRenderer.FRUSTUM`, which Iris's DH mixins make an `IDhApiShadowCullingFrustum`) for the shadow pass, as
     Iris does for its own pipeline; otherwise DH would draw every LOD into the shadow map.
   DH's terrain pass is then retargeted to the shadow attachments (`GbufferState.descriptor` in the shadow pass) and its
   pipelines become `dh_shadow` (patchDHTerrain + `adaptVertex`, shadow attachment layout; `dh_shadow`'s draw buffers
   count for the shadowcolor attachments). Matrices: `ShadowRenderer.MODELVIEW` and `undoRevZ(ShadowRenderer.PROJECTION)`
   — the projection the pack's other shadow programs see on Metal (Iris's GL path uses `toMinusOneToOne`, which here
   puts the LODs at the wrong shadow depth: they lit instead of shadowed). DH's other passes of that frame (generic
   objects, SSAO/fog on DH's own textures, copy/apply) go to the scratch texture or DH's own textures. If `dh_shadow`
   does not compile, LOD shadows switch off (DH's own shader would draw the camera view into the shadow map). Off
   switch: `-Dnativium.iris.noDhShadow=true` (game test `-PnoDhShadow`). Cost: DH renders its terrain a second time
   (only LODs inside the shadow frustum).

Verified 2026-10-23: `dh_generic` with Complementary Reimagined, Photon and BSL (DH's cloud boxes appear in the sky,
lit/fogged by the pack; with `-PnoDhGeneric` they are missing, nothing else changes). `dh_shadow` with Bliss 2.1.2 +
`DISTANT_HORIZONS_SHADOWMAP` (game test `-PshaderpackOptions=DISTANT_HORIZONS_SHADOWMAP=true`, afternoon-sun screenshot
`nativium-dh-low-sun` against `-PnoDhShadow`): far LOD hills now shadow Bliss's volumetric light and LOD terrain, no
artifacts; a pixel-exact check of the shadow map was not done.
