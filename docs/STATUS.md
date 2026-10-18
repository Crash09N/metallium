# Metallium status

## 2026-10-18 (first implementation night)

Verified on GitHub's macOS arm64 runners (virtual M1, "Apple Paravirtual device", macOS 15.7.9) by the CI game test:

| Item | State |
|---|---|
| Metal backend (renderpearl SPI, from Metallum) | Boots Minecraft 26.3; title screen, flat and natural worlds render correctly (screenshots in branch `ci-artifacts-vanilla`) |
| Backend selection | Metal first only for "Default" on Apple Silicon; OpenGL/Vulkan fallbacks kept; Iris without adapter → OpenGL |
| Shader-pack translator (`dev.metallium.shaders.GlslProgramTranslator`) | Iris-style GLSL → SPIR-V (glslang, Vulkan-relaxed) → MSL with uniform-block and binding reflection; unit tests pass, MSL compiles with the Metal compiler on macOS |
| Sodium on Metal | Loads, meshes and uploads, but **terrain renders garbled** on the virtual GPU — see `TESTING-ON-MAC.md` |
| Iris adapter stage 1 (`metallium-iris`) | Game starts on Metal with Sodium + Iris + adapter (no crash); shader packs not yet executed |
| CI | Linux build + unit tests; macOS: Metal smoke test, translator tests compiled with Metal, game tests (vanilla / Sodium / Iris) with screenshot-based terrain check; results pushed to `ci-artifacts-*` branches |

Next steps:
1. Owner test on M3 Max (`docs/TESTING-ON-MAC.md`): decides whether the Sodium bug is backend or virtual-GPU specific.
2. Fix Sodium terrain on Metal.
3. Iris adapter stage 2 (composite/final passes) per `docs/design/iris-adapter-blueprint.md`, using `GlslProgramTranslator`.
4. Benchmark baseline: vanilla 26.3's own Vulkan backend on macOS through MoltenVK (and Mesa KosmicKrisp if available),
   next to OpenGL and Metallium, same world/camera path (see report §L.4). Comparison only; it cannot run Iris.
5. Later stage, geometry/tessellation shaders for packs: study Mesa's compute-based geometry/tessellation emulation for
   Apple GPUs (Asahi/Honeykrisp; Mesa is MIT, credit in README if code is adapted) as the route to packs that need `.gsh`.
   Rejected: Zink (OpenGL→Vulkan) + MoltenVK under unmodified Iris — three translation layers, missing Vulkan features
   on MoltenVK, expected slower than Apple's OpenGL.
