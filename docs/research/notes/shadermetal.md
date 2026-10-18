# ShaderMetal (LingXiFox) audit

Cloned 2026-10-18 to /Users/noah/Downloads/metallium-research/ShaderMetal (6 commits, all 2026-07-14/15, single author LingXiFox).
Paths below are relative to `shader-metal-template-1.21.4/` unless noted. Labels: V = Verified in code, SI = Strongly inferred, H = Hypothesis.

## Summary
ShaderMetal is NOT an Iris/shader-pack runtime. It is a Radiance (Vulkan path-tracing mod) port to Metal: Fabric mod, MC 1.21.4,
Java mixins forward the GL render calls over JNI to a native ObjC++ Metal renderer, plus an experimental hardware ray-traced lighting
pass (BLAS/TLAS + MetalFX). It reads no shader packs. Almost nothing here helps Stage 3-5; the one useful artefact is a compact
GLSL->SPIR-V->MSL compiler config, which we already do equivalently.

## Facts
- MC/loader (V): MC 1.21.4, Fabric Loader >=0.19.2, Yarn `1.21.4+build.8` (`gradle.properties`, `src/main/resources/fabric.mod.json`). Not 26.x / not renderpearl.
- Licence (V): GPL-3.0-only (`LICENSE`, `fabric.mod.json` "license"). Submodules glslang / SPIRV-Cross keep their own (BSD/Apache-2.0). Derives architecture from Radiance + MCVR (GPL-3) per `PROVENANCE.md`, `THIRD_PARTY_NOTICES.md`.
  Reuse: NOT compatible with our Apache-2.0 core, and GPL-3 is not LGPL-3 compatible for inbound code in the adapter either (GPL code would force the whole combined work to GPL). Treat as ideas-only.
- What it is (V): `src/client/java/.../mixin/*` (RenderSystem, BufferRenderer, GameRenderer, ShaderProgram, CompiledShader, Window mixins) + `proxy/*` JNI classes -> `native/src/jni/*.mm`. It replaces GL rendering of vanilla 1.21.4 calls; a vanilla-style "pipeline state tracker" (`native/src/render/PipelineStateTracker.cpp`) and `ChunkProxy` feed geometry to both raster and RT acceleration structures.
- Iris/Sodium/OptiFine/gbuffer/shadow-map: grep for iris|shaderpack|optifine|gbuffer|shadowmap|composite finds nothing in code (V). No pack parsing, no `#include`/`#define` preprocessing, no `shaders.properties`, no `/*DRAWBUFFERS*/`, no MRT gbuffers, no shadow pass, no composite chain. Its "shadows" are ray-traced in `native/shaders/RayTrace.metal` (1698 lines).
- Compute (V): only its own RT/temporal/spatial kernels (`native/src/raytracing/RayTracePass.hpp:130-132`, `RayTracePass.mm:337-344`). GLSL compute is not supported: `ShaderStage` has only Vertex and Fragment (`native/src/core/ShaderCompiler.cpp:126-136`). No SSBO/image support.
- Mipmaps (V): standard texture upload with `mipmapLevelCount` (`native/src/resource/TextureManager.mm:179-180`) and per-level `replaceRegion` (`:412`); sampler min/mag/mip modes cached (`SamplerCache.mm`). No per-frame mip generation for render targets (`FrameContext.mm:596,622` use `mipmapped:NO`).
- Render targets (V): only a Depth32Float_Stencil8 and one BGRA8Unorm colour target (`native/src/core/FrameContext.mm:593-625`); no MRT / float formats.
- Packs claimed/tested (V): none. Tests: one C++ file, `native/tests/ShaderCompilerTests.cpp` (304 lines; trivial vertex+fragment, thread-safety). README states testing only on an M5 Mac / macOS 27 beta. CI: `.github/workflows/build.yml` on macos-26.
- Maturity (V): 0.3.0-alpha.1, 6 commits in 2 days, README lists visible noise/artefacts/frame spikes. ~13.7k native LOC, of which AccelStructManager.mm is 3106.
- AI-generated (SI, near-certain): `文档/SHADERMETAL_PROJECT_BRIEF.md` is explicitly "a build contract for the executing agent (Codex / multi-subagent orchestration)" with parallel subagent work packages (lines 3, 186-209, 253-255); ~13k lines in 2 days from a single committer; Claude-style defensive comment/diagnostic code. Also note: our CLAUDE.md forbids copying MetalRender (AI disallowed); ShaderMetal has no such stated rule, but GPL still blocks reuse.

## Translation path (V) -- `native/src/core/ShaderCompiler.cpp`
- glslang (not shaderc) in-process: `setEnvInput(Glsl, stage, Vulkan, 100)`, client Vulkan 1.2, target SPIR-V 1.5, `setAutoMapBindings/Locations(true)`, parse as `#version 460` with `EShMsgSpvRules|EShMsgVulkanRules` (L202-218), optimizer disabled, debug stripped (L293-296). Global mutex around glslang (L189).
- SPIRV-Cross `CompilerMSL`: macOS, MSL 3.0, `argument_buffers=true`, Tier2, `force_active_argument_buffer_resources`, `fixup_clipspace` on vertex (L333-346). Set 0 = bindless texture/sampler argument buffer (`set_argument_buffer_device_address_space(0,true)`, 4096-entry table, `RasterPass.mm:301`), set 1 binding 0 = std140 uniform block forced to a direct `[[buffer(1)]]` via `add_discrete_descriptor_set(1)` + `add_msl_resource_binding` (L347-359).
- Takes already-Vulkan-flavoured GLSL: its Java side supplies sources for vanilla 1.21.4 core shaders; it does not rewrite OpenGL-style pack GLSL.

## Ideas for Metallium Stage 3-5, with licence status
| Idea | Evidence | Stage | Reuse | Label |
|---|---|---|---|---|
| SPIRV-Cross Tier2 argument buffers for textures (set 0 bindless table) with discrete set for UBO. Could avoid the 31-texture slot limit for packs with many samplers (colortex0-15, shadowtex, noisetex, etc.) | ShaderCompiler.cpp:341-359, RasterPass.mm:245-327 | 3 | Idea only (GPL). The SPIRV-Cross options are public API, so re-deriving is clean | V that it works for them; H that it helps us (needs `useResource` for each texture, RasterPass.mm:759) |
| Single global compile mutex + diagnostic phases (parse/link/SPIR-V/MSL) for pack-shader error reporting | ShaderCompiler.cpp:189, ShaderDiagnosticPhase | 3 | Idea only | V |
| Compute stage and images/SSBO | Not present (only Vertex/Fragment) | 4/5 | Nothing to adopt | V (absence) |
| Shadow pass / MRT gbuffers / mip generation for composite | Not present (single BGRA8 + D32S8) | 3-5 | Nothing to adopt | V (absence) |
| Hardware RT (acceleration structures from chunk meshes) as a possible later extension, not needed for Iris | AccelStructManager.mm, RayTracePass.mm | n/a | Idea only | V |

## Conclusion
Do not spend more time on it for Stage 3-5; our Stage 2 compiler path already matches or exceeds it (shaderc with Vulkan-relaxed GLSL, MSL via SPIRV-Cross). Radiance/MCVR (GPL) are likewise irrelevant to Iris. If we ever want a reference for a GL-flavoured-GLSL pack runtime on a non-GL backend, look at Iris itself (LGPL), Vulkan mods (VulkanMod, GPL), not this.
