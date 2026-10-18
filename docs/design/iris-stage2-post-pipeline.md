# Iris adapter stage 2: shader-pack fullscreen passes on Metal

Status: implemented 2026-10-18 (`metallium-iris`, package `dev.metallium.iris.post`). Blueprint context:
`iris-adapter-blueprint.md` §6 stage 2.

## Decision: targeted replacement, not the GL-name shim (yet)

The blueprint's long-term seam is a "GL-name shim" (`MetalGL` sink behind `GlStateManager`/`IrisRenderSystem`) that
lets `IrisRenderingPipeline`, `CompositeRenderer` and `FinalPassRenderer` run unchanged. For stage 2 we chose a
targeted replacement instead, because it produces correct pixels with a fraction of the surface:

- `IrisRenderingPipeline`'s constructor touches every GL subsystem at once (render targets, shadow targets, ~80
  gbuffers programs, custom textures, SSBOs). Running it on Metal requires most of the ~110-function shim before the
  first pixel.
- A post-processing pass only needs: a fullscreen draw, colour targets with ping-pong, samplers and loose uniforms.
  All of that already exists in Minecraft's public renderpearl API (`RenderPass`, `GpuTexture`, `GpuSampler`,
  `TransientMemory`), which the Metal backend implements. **No backend changes were needed.**

What is reused from Iris unchanged: pack loading and options (`ShaderPack`, `ProgramSet`, `PackDirectives`), the GLSL
transformer (`TransformPatcher.patchComposite`), buffer flipping (`BufferFlipper`), and all uniform providers
(`CommonUniforms`, `CustomUniforms` incl. pack-defined `uniform.*` expressions). What is ours: the pass runner
(`MetalPostPipeline`, structured like `CompositeRenderer`/`FinalPassRenderer`), render targets (`PostTargets`) and
program compilation (`PostProgram`).

Stage 3+ (gbuffers, shadow) will need the attachment-retarget hooks (H1-H6) and probably parts of the shim; the
stage-2 classes stay useful there (program compilation, uniform blocks, targets).

## Data flow

```
Iris.createPipeline (mixin, Metal only) -> MetalPostPipeline(ProgramSet)
  per pass: TransformPatcher.patchComposite -> GlslProgramTranslator.compileToSpirv (shaderc, Vulkan-relaxed)
            -> varyings matched by name in SPIR-V, default-block layout reflected
            -> SPIRVModule + descriptor bindings -> BackendRenderPipeline.CreateInfo
            -> backend.compilePipeline (Metal: SPIR-V -> MSL, as for vanilla) -> FrontendRenderPipeline
  per frame (finalizeLevelRendering, after the frame graph executed):
     clear buffers -> begin/prepare passes -> copy main colour into colortex0 -> deferred/composite passes
     -> final pass into the main target (or copy colortex0) -> copy flipped buffers back (Iris swap passes)
```

- **Uniforms.** Loose uniforms are folded by glslang into one default block per stage. `PostUniforms.Holder` is an Iris
  `DynamicLocationalUniformHolder`: `location(name, type)` returns a synthetic global location bound to the reflected
  member(s); Iris's uniform objects keep calling `IrisRenderSystem.uniform*` / `glUniform*`, which the adapter's
  mixins route to `PostUniforms.set*` on Metal. Blocks are uploaded per draw through `TransientMemory`.
- **Samplers.** `colortexN` / legacy names -> `PostTargets` (main or alt by flip state), `depthtex0/1/2` -> main depth,
  `noisetex` -> generated noise, `shadow*` -> shadow maps (stage 4; cleared = fully lit without a shadow pass), anything else -> colortex0 (GL's texture-unit-0
  behaviour that Iris relies on in fullscreen passes). Fragment samplers get the low Metal indices (16 per stage).
- **No GL on Metal.** Every static `GlStateManager` method is prefixed (bytecode, `GlStateManagerGuard` via the mixin
  plugin) with `if (GlGuard.active()) return <answer>;`, so Iris paths that still call it (pipeline reset, macro
  setup) never reach LWJGL, which would abort the JVM without a context.

## Not supported yet (reported in log + chat, never a crash)

shadow/dh programs (gbuffers: stage 3, `iris-stage3-gbuffers.md`; packs without gbuffers programs keep the vanilla scene), shadowcomp passes, compute shaders, geometry/tessellation
(pass skipped), mipmapped buffers, viewport scale, blend overrides, `size.buffer.*`, custom textures, explicit uniform
blocks, images/SSBOs. Without gbuffers programs depthtex1/2 are the same as depthtex0 (with them, stage 3 copies the depth before hand/translucents).

## Results (M3 Max, 2026-10-18)

- Test pack (`src/gametest/resources/metallium-testpack`, inverts colours via colortex0 + `viewWidth`): inverted-pixel
  fraction 1.00 in the game test.
- Complementary Unbound r5.6.1 (local only, never in the repo): loads; 8 fullscreen programs + final translate and
  run (its deferred clouds and tonemapping show over the vanilla scene); 20 gbuffers/shadow programs reported as not
  executed; mipmap requests in deferred1/composite4/composite6 ignored. Samplers without a source on Metal yet:
  shadowtex0/1, shadowcolor0/1 (placeholders), normals/specular/tex (colortex0).
