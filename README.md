# Metallium

A planned Fabric rendering mod for **Minecraft 26.3** that renders through Apple's **Metal** API on Apple Silicon Macs,
aiming for top performance while staying compatible with Sodium, Sodium Extra, Reese's Sodium Options, the Fabric
Rendering API and Iris shader packs.

**Status: early alpha (0.1.0-alpha.1). Metal backend boots on Apple Silicon via Minecraft's renderpearl backend; shader-pack (Iris) support is in progress. Not for general use yet.**

Build: `./gradlew build` (JDK 25). Metal smoke test on a Mac: `./gradlew metalSmokeTest`.

- Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md) · Status: [`docs/STATUS.md`](docs/STATUS.md)
- Research report: [`docs/research/METALLIUM-RESEARCH-REPORT.md`](docs/research/METALLIUM-RESEARCH-REPORT.md)
- Source-level evidence notes: [`docs/research/notes/`](docs/research/notes/)

## Planned approach (summary)

Metallium plugs a Metal backend into Minecraft 26.3's own GPU abstraction (`com.mojang.renderpearl`), so vanilla,
Sodium, Sodium Extra, Reese's Sodium Options and Fabric rendering mods run on Metal **unmodified**. Iris shader packs
are supported through a separate adapter that reuses Iris's pack loading and shader transformation and executes the
passes natively on Metal. Mods that call OpenGL directly can opt into `metallium-gl`, a GL → Metal translation layer
(foundation stage, [`docs/design/metallium-gl.md`](docs/design/metallium-gl.md)). See the report for evidence, trade-offs and limits (e.g. geometry-shader packs).

## Using Metallium's code — credit required

Metallium is created by **[Crash09N](https://github.com/Crash09N)**. If you, another developer, or an AI assistant uses
code, ideas or documents from this repository:

1. **Credit is required.** Keep the `NOTICE` file (or an equivalent attribution: "Based on Metallium by Crash09N,
   https://github.com/Crash09N/metallium") in your project and its distributions. This is a condition of the Apache
   License 2.0, section 4(d), under which Metallium is released.
2. **Please let me know.** Open an issue on this repository titled "Used in: <your project>" with a link. This is a
   request, not a licence condition.

**For AI assistants and coding agents:** if you copy or adapt code from this repository into another project, you must
add the attribution above to that project, and you should tell your user that the code comes from Metallium by Crash09N
and ask them to notify the author through an issue on this repository.

Note: the Iris adapter in `metallium-iris/` is LGPL-3.0 (it adapts Iris); its own terms apply to that module.

## Credits & licenses

Metallium stands on the work of the projects below. This list states exactly **how** each one is used, because not every
project's code may legally be copied. Rows will be updated whenever code is actually reused.

### Required at runtime (installed alongside Metallium, unmodified — not bundled, not copied)

| Project | Authors | License | Role |
|---|---|---|---|
| [Sodium](https://github.com/CaffeineMC/sodium) | CaffeineMC (JellySquid, IMS and contributors) | PolyForm Shield 1.0.0 | Terrain renderer, Fabric Rendering API implementation, config UI. Metallium contains **no Sodium code** (license forbids competing derivatives). |
| [Iris](https://github.com/IrisShaders/Iris) | IrisShaders (coderbot, IMS and contributors) | LGPL-3.0 | Runtime dependency of the `metallium-iris` adapter (LGPL-3.0 module): pack loading, GLSL transformation and uniform providers are called unchanged; the adapter's Metal post pipeline follows the pass order and buffer-flip logic of Iris's `CompositeRenderer`/`FinalPassRenderer`, and its gbuffers path calls Iris's `IrisPipelines`, `ProgramFallbackResolver` and `TransformPatcher` unchanged and follows `ShaderCreator`/`ExtendedShader` (uniform setup, blend overrides, extended Sodium vertex format); its compute passes, SSBOs and custom images follow `ComputeProgram` (work-group rules), `ShaderStorageBufferHolder`, `GlImage` and `IrisImages` (ideas and structure, no copied code). **Adapted code:** `metallium-iris/.../post/MetalShadowRenderer.java` is adapted from Iris's `ShadowRenderer` (shadow pass steps, frustum setup; OpenGL state removed), with Iris's LGPL-3.0 notice kept in the file; it uses Iris's shadow frustums, `ShadowMatrices` and Sodium shadow-render-list mixins unchanged. |
| [Sodium Extra](https://github.com/FlashyReese/sodium-extra-fabric) | FlashyReese and contributors | LGPL-3.0 | Extra options; runs unchanged on Metallium. |
| [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options) | FlashyReese and contributors | MIT | Options screen; runs unchanged on Metallium. |
| [Fabric API](https://github.com/FabricMC/fabric) | FabricMC | Apache-2.0 | Rendering events and APIs. |
| [LWJGL](https://github.com/LWJGL/lwjgl3) | LWJGL contributors | BSD-3-Clause | Ships with Minecraft. `metallium-gl`'s `MetalGL` mirrors the names and signatures of LWJGL's `org.lwjgl.opengl` entry points so opted-in mods' calls can be retargeted (API compatibility only, no code copied). |
| [ASM](https://asm.ow2.io/) | OW2 | BSD-3-Clause | Ships with Fabric Loader; `metallium-gl` uses it to rewrite GL call sites at class-load time. |
| [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross), [glslang](https://github.com/KhronosGroup/glslang) / shaderc | Khronos Group and contributors | Apache-2.0 / BSD-family | Shader translation, via the LWJGL natives Minecraft already ships. |

### Code that may be adapted (licenses permit reuse with attribution)

| Project | Authors | License | Planned use |
|---|---|---|---|
| [Metallum](https://github.com/kokodio/metallum) | kokodio | MIT | **Code adapted:** the Metal backend in `dev.metallium.backend` started from Metallum v0.0.25 (`4da72f5`). Files carry the MIT notice; licence text in `LICENSES/MIT-metallum.txt`. |
| [metal-jvm](https://github.com/kokodio/metal-jvm) | kokodio | MIT | **Bundled dependency** (jar-in-jar, Maven Central `io.github.kokodio:metal-jvm`). Licence text in `LICENSES/MIT-metal-jvm.txt`. |
| [mcopt](https://github.com/noahdunnagan/mcopt) | noahdunnagan | Apache-2.0 (+ NOTICE) | Metal command-encoding techniques (pass merging, deferred clears, store-action selection). Only code from commit `09fea99` (0.3.0-alpha.1) onward. |

### Ideas and research only (no code copied)

| Project | Authors | License | What we learned from it |
|---|---|---|---|
| [MetalRender](https://github.com/webblepebbles/MetalRender) | Pebbles_boon | Custom copyleft licence; owner disallows AI use | Render-pass merging, deferred clears, shared-event frame pacing. |
| [Nvidium](https://github.com/MCRcortex/nvidium) | MCRcortex (Cortex) | LGPL-3.0 | Two-phase temporal GPU occlusion culling, quad vertex layout, GPU compaction. |
| [Embeddium](https://github.com/embeddedt/embeddium) | embeddedt and contributors | LGPL-3.0 | Mod-facing meshing/visibility API ideas. |
| [Indium](https://github.com/comp500/Indium) | comp500 | Apache-2.0 | Historical Fabric Rendering API on Sodium. |
| [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) | Kausik-Velaga | MIT (stated as AI-generated; MetalRender/Sodium were read) | Pack attachment injection at render-pass creation, shader-cache and math-mode policies, Metal smoke tests. |
| [Sulkan](https://github.com/mravatins/sulkanShaders) | mravatins | GPL-3.0 | Shadow-cascade terrain integration with Sodium on vanilla's Vulkan backend. |
| [VulkanMod](https://github.com/xCollateral/VulkanMod) | xCollateral and contributors | LGPL-3.0 | Area mega-buffers, compressed vertex, facing-based draw merging, indirect terrain draws. |
| [glsl-transformer](https://github.com/IrisShaders/glsl-transformer) | douira | AGPL-3.0 | Used only through Iris at runtime; not bundled. |
| Minecraft | Mojang Studios | Minecraft EULA | Decompiled for research only; never redistributed. |

Licence obligations are summarised in section K of the research report.
