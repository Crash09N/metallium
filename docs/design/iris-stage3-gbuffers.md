# Iris adapter stage 3: gbuffers programs on Metal

Status: implemented 2026-10-18 (`metallium-iris`, package `dev.metallium.iris.post`: `GbufferPasses`, `GbufferState`,
`GbufferProgram`; mixins `FrontendCommandEncoderMixin`, `FrontendRenderPassMixin`, `RenderSystemPipelineMixin`).
Blueprint context: `iris-adapter-blueprint.md` §6 stage 3; stage 2: `iris-stage2-post-pipeline.md`.

## The problem

On OpenGL, Iris lets vanilla and Sodium open their render passes on the main framebuffer and then swaps two things per
draw: the GL program (vanilla/Sodium pipeline -> the pack's `gbuffers_*` program, `MixinShaderManager_Overrides`) and the
bound framebuffer (`ExtendedShader.iris$setupState` binds the pack's gbuffer FBO with the program's `DRAWBUFFERS`). A
Metal render pass has its attachments fixed at creation, and the renderpearl frontend validates every
`setPipeline` against the pass's colour attachments (count and formats).

## Decision: retarget whole passes, substitute pipelines in the frontend (no backend changes)

The blueprint proposed per-draw retargeting hooks in the backend (H1-H6). Stage 3 needs none of them:

1. **One fixed gbuffer attachment layout per pack.** The union of the `DRAWBUFFERS` of all `gbuffers_*` programs
   (plus colortex0, sorted, at most 8 - Metal's limit) is the colour attachment list of every captured pass.
   Complementary Unbound: colortex0,3,4,6. Programs that write fewer buffers get an empty write mask on the others;
   their fragment outputs are moved from `DRAWBUFFERS` slot `i` to the attachment index of that buffer by rewriting the
   `Location` decorations in SPIR-V (`GlslProgramTranslator.remapOutputLocations`).
2. **Capture.** Between `beginLevelRendering` and `finalizeLevelRendering` of `MetalPostPipeline`, every render pass
   the frontend creates on the main colour texture (`FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)`) is
   created on the gbuffer attachments instead (main depth stays). Writes go to the main or alt texture of each buffer
   like Iris's `createGbufferFramebuffer`: by the flip state after `prepare` before translucents, after `deferred` from
   `beginTranslucents` on (Iris's own `MixinLevelRenderer` already closes and re-opens the vanilla pass there).
3. **Substitution.** In a captured pass every `FrontendRenderPass#setPipeline` gets a replacement:
   - the source `RenderPipeline` is known from `RenderSystem#getCompiledPipelineNullable` (identity map);
   - Iris's own `IrisPipelines.getPipeline` gives the `ShaderKey` (Sodium terrain is detected by Iris as well), Iris's
     `ProgramFallbackResolver` the program source (e.g. `gbuffers_water` -> `gbuffers_terrain`);
   - Iris's `TransformPatcher.patchVanilla`/`patchSodium` produce the GLSL exactly as on GL; `GbufferProgram` compiles it
     (GLSL -> SPIR-V with the core translator) into a `BackendRenderPipeline.CreateInfo` with the replaced pipeline's
     vertex layout, depth state, cull, topology and push-constant size, and the gbuffer colour targets;
   - pipelines without a pack program (or whose program fails to translate) get a copy of the vanilla pipeline with the
     gbuffer layout (its shader writes colortex0, other attachments masked) - the closest match to GL, where such a draw
     lands in whatever framebuffer Iris left bound.
4. **Binding.** Right after `setPipeline` (`FrontendRenderPass` TAIL) the program's loose uniforms are updated like
   Iris's `ExtendedShader.iris$setupState` (alpha-test reference, `iris_ModelViewMatInverse`, `iris_NormalMat`,
   `iris_ProjMatInverse`, all Iris uniform providers incl. pack custom uniforms) and uploaded as one block per stage;
   the pack's samplers (colortex, depthtex0/1/2, noisetex, normals/specular defaults, shadow placeholders) are bound.
   Vanilla's own resources keep flowing: Iris renames `DynamicTransforms/Projection/Globals/Fog/CloudInfo` to
   `iris_<Name>` and the textures to `gtexture/lightmap/iris_overlay`; the program's binding table maps them back to the
   names vanilla/Sodium set (`Sampler0`, `u_BlockTex`, `u_LightTex`, `u_Globals`, `u_SectionTimeInfo`, ...). Sodium's
   `iris_SodiumPushConstants` block becomes a SPIR-V push-constant block (same layout as Sodium's own `PC`).

## Vertex formats and "pack in use"

The pipeline sets `WorldRenderingSettings` like `IrisRenderingPipeline` (Iris's extended Sodium terrain format
`FormatAnalyzer.createFormat(true,true,true,true)`, block/entity/item ID maps, AO, separate AO...), so Sodium meshes
with `mc_Entity`, `iris_Normal`, `mc_midTexCoord`, `at_midBlock` through Iris's unchanged Sodium mixins.
`Iris.isPackInUseQuick()` (normally `instanceof IrisRenderingPipeline`) is also true for `MetalPostPipeline` while its
gbuffers run (`IrisPackInUseMixin`). That switches on what Iris does on GL for an active pack: extended vanilla vertex
formats (`IrisVertexFormats.ENTITY/TERRAIN/GLYPH`, filled by Iris's `MixinBufferBuilder`), entity/block-entity ID
capture, and the in-level hand (`HandRenderer` opens its passes on the main target, so they are captured and use
`gbuffers_hand`; Iris suppresses the vanilla hand). Passes that are not captured (e.g. the entity outline target) get
their pipeline recompiled for the extended layout (`GbufferState.uncaptured`). Inputs a program declares but the vertex
layout still lacks become constants (`GbufferProgram.defaultMissingInputs`, like an unbound GL attribute).

## Frame structure on Metal

```
beginLevelRendering : resize/clear colortex (colortex0 to fog colour), begin+prepare passes, capture ON (flip: after prepare)
level frame graph   : sky, terrain (Sodium), entities, particles... -> captured passes -> gbuffers programs
beginHand           : copy depth -> depthtex2; Iris's HandRenderer draws the solid hand (captured, gbuffers_hand)
beginTranslucents   : copy depth -> depthtex1, deferred passes, capture continues (flip: after deferred)
level frame graph   : translucent terrain, weather, ...; translucent hand (HandRenderer)
finalizeLevel...    : capture OFF, composite passes, final pass -> main target, swap copies
```

## Not supported yet

- Shadow pass (stage 4): `shadow*` samplers stay white placeholders.
- `gbuffers_*` with geometry/tessellation shaders, explicit pack uniform blocks, images/SSBOs (stage 5), or more than
  16 textures in use (Metal: 16 samplers per stage, the backend numbers textures per pipeline): the vanilla shader is
  used for that pipeline (reported once in the log).
- More than 8 written colortex buffers in gbuffers (Metal limit): writes beyond the 8th are dropped.
- Custom textures (`texture.gbuffers.*`), real PBR normal/specular atlases (defaults are bound).
- Per-draw uniform freshness: loose uniforms are uploaded per `setPipeline`, not per draw (per-entity IDs come from
  the extended vertex formats, as in Iris).
- `prepare` passes run at `beginLevelRendering` (Iris runs them after the shadow pass, which does not exist yet).
