# Metallium

A planned Fabric rendering mod for **Minecraft 26.3** that renders through Apple's **Metal** API on Apple Silicon Macs,
aiming for top performance while staying compatible with Sodium, Sodium Extra, Reese's Sodium Options, the Fabric
Rendering API and Iris shader packs.

**Status: research and architecture phase. There is no implementation code yet.**

- Research report: [`docs/research/METALLIUM-RESEARCH-REPORT.md`](docs/research/METALLIUM-RESEARCH-REPORT.md)
- Source-level evidence notes: [`docs/research/notes/`](docs/research/notes/)

## Planned approach (summary)

Metallium plugs a Metal backend into Minecraft 26.3's own GPU abstraction (`com.mojang.renderpearl`), so vanilla,
Sodium, Sodium Extra, Reese's Sodium Options and Fabric rendering mods run on Metal **unmodified**. Iris shader packs
are supported through a separate adapter that reuses Iris's pack loading and shader transformation and executes the
passes natively on Metal. See the report for evidence, trade-offs and limits (e.g. geometry-shader packs).

## Credits & licenses

Metallium stands on the work of the projects below. This list states exactly **how** each one is used, because not every
project's code may legally be copied. Rows will be updated whenever code is actually reused.

### Required at runtime (installed alongside Metallium, unmodified — not bundled, not copied)

| Project | Authors | License | Role |
|---|---|---|---|
| [Sodium](https://github.com/CaffeineMC/sodium) | CaffeineMC (JellySquid, IMS and contributors) | PolyForm Shield 1.0.0 | Terrain renderer, Fabric Rendering API implementation, config UI. Metallium contains **no Sodium code** (license forbids competing derivatives). |
| [Iris](https://github.com/IrisShaders/Iris) | IrisShaders (coderbot, IMS and contributors) | LGPL-3.0 | Shader-pack loading and GLSL transformation, used by the planned Iris adapter. |
| [Sodium Extra](https://github.com/FlashyReese/sodium-extra-fabric) | FlashyReese and contributors | LGPL-3.0 | Extra options; runs unchanged on Metallium. |
| [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options) | FlashyReese and contributors | MIT | Options screen; runs unchanged on Metallium. |
| [Fabric API](https://github.com/FabricMC/fabric) | FabricMC | Apache-2.0 | Rendering events and APIs. |
| [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross), [glslang](https://github.com/KhronosGroup/glslang) / shaderc | Khronos Group and contributors | Apache-2.0 / BSD-family | Shader translation, via the LWJGL natives Minecraft already ships. |

### Code that may be adapted (licenses permit reuse with attribution)

| Project | Authors | License | Planned use |
|---|---|---|---|
| [Metallum](https://github.com/kokodio/metallum) | kokodio | MIT | Starting point for the renderpearl Metal backend and SPIR-V→MSL setup. |
| [metal-jvm](https://github.com/kokodio/metal-jvm) | kokodio | MIT | Java FFM bindings to Metal. |
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
