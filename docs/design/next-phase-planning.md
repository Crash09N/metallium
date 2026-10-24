# Next phase: Apple-native features and the NVIDIA path (planning)

Status 2026-10-24: **in planning.** 1.0.0 is released (`docs/RELEASE.md`). The owner named these two tracks as the next
work. This file collects what is already known, the open questions and a proposed order, so the next agent or
contributor can start without re-reading the whole history. Nothing here is implemented; no code for it is in 1.0.

Ground rules for both tracks:
- Each item becomes a design note in `docs/design/` before code, and the owner agrees per item before implementation
  starts. Model choice per `.agents.md` (Fable only with the owner's OK per task).
- Every feature is behind an option or system property (off switch for A/B), gets a game test with screenshots and a
  benchmark row in the format of `docs/benchmarks/release-2026-10-24.md` (G5, 2560x1440 fullscreen, owner away).
- Credits/licences as in `.agents.md`: Nvidium is LGPL-3.0 (ideas so far; adapted code only in an LGPL module), DLSS
  needs NVIDIA's SDK licence terms, MetalRender/Sodium: no code.

## Track A — Apple-native features (Metal)

Research: `docs/research/notes/metalfx-upscaling.md` (2026-10-18; API facts, metal-jvm coverage, design sketches).
Facts learned since then that change that note:
- Minecraft 26.3 uses **reversed-Z depth** (seen in the Voxy/Iris depth work, `VoxyIrisBridge`) → `setDepthReversed(true)`
  for the temporal scaler.
- Nativium requires Metal 4 / macOS 26 already (`docs/RELEASE.md`), so `MTLFXFrameInterpolator` (macOS 26+) and the
  `MTL4FX*` scalers are available on every supported Mac; no version gate beyond the existing one.
- The present path changed: VSync-off present pacing (one present per refresh, up to 3 in flight) lives in the surface
  code; frame interpolation must be built into that scheduler, not beside it.

| # | Item | Effort / risk (from the research note) | Depends on | Open questions |
|---|---|---|---|---|
| A1 | MetalFX **spatial** upscaling (`renderScale` option, presets) | S-M / low | - | World/GUI split so the HUD stays at native resolution (v1 may upscale everything behind the option); texture usage flags (shaderRead/shaderWrite); colour processing mode for MC's gamma-encoded output; how Iris packs' final pass interacts (upscale after `final`?) |
| A2 | MetalFX **temporal** upscaling | L / high | A1 split | Projection jitter (Halton 2,3) without breaking sky/UI; motion vectors from depth + previous view-projection (static terrain exact; entities/particles/hand ghost unless they write MVs); Iris packs out of scope at first (packs have their own TAA) |
| A3 | MetalFX **frame interpolation** | XL / very high | A2, separate UI texture | Two presents per game frame in the pacing scheduler; input latency; quality on entities |
| A4 | **Game Mode** | S / low | - | Needs a `.app` wrapper with the game category (launcher side, not the mod); check what Prism/the official launcher already do on macOS 26 |
| A5 | **Low Power mode** follow-ups | S | - | Already in (mode setting, power/thermal detection, FPS cap). Open: worker threads at lower QoS, watts per mode (powermetrics) in the benchmark |
| A6 | Hardware **ray-traced effects** (M3+) | XL / research | - | Which effect (shadows? AO?), how it coexists with shader packs; research note first |

Proposed order: A1 (full-frame first, then the GUI split) → A4 → A2 (terrain-only motion vectors) → evaluate A3. A5 is
small and can go any time. A6 only after a research note.

## Track B — NVIDIA path

The long-term vision (ROADMAP, "Long-term vision"): one mod that optimises for Apple GPUs (Metal, MetalFX) and NVIDIA
GPUs (Nvidium-style GPU-driven terrain, DLSS upscaling and frame generation). Research: research report §C.5 and
`docs/research/notes/nvidium-embeddium-frapi.md` (Nvidium 0.4.4-beta7, branch 26.3, audited).

What is known:
- Nvidium needs NVIDIA OpenGL extensions (`GL_NV_mesh_shader`, unified memory, representative fragment test, sparse
  buffers, bindless multi-draw-indirect); it never runs on non-GL backends and disables itself whenever an Iris pack is
  active. It replaces Sodium's render/upload path but keeps Sodium's meshing and traversal.
- Nativium today is Metal-only and fully inert on Windows/Linux (`NativiumPlatform.supported()`); the NVIDIA path is a
  separate backend path with its own module, not part of the Metal backend.
- DLSS / frame generation run through Vulkan (vanilla 26.3 ships a Vulkan backend) or DirectX, not OpenGL or Metal.
- Nativium's own terrain occlusion culling (`HiZCuller`/`TerrainOcclusion`, two-phase Hi-Z, own code after
  mcopt/Nvidium ideas) is a starting point for a GPU-driven path on any backend.

| # | Item | Open questions |
|---|---|---|
| B0 | **Permission and naming** | Ask MCRcortex before using an Nvidium-derived name or logo; any adapted Nvidium code goes into an LGPL-3.0 module (like `nativium-iris`) |
| B1 | **Platform gate** | `NativiumPlatform` must learn "Windows/Linux + NVIDIA GPU" without touching the Mac path; keep every other machine inert |
| B2 | **Backend choice** | GL (as Nvidium: NV extensions, but no DLSS) vs vanilla's Vulkan backend (DLSS possible, mesh shaders via `VK_EXT_mesh_shader`); decides everything after it |
| B3 | **GPU-driven terrain** | Port the idea (not the code, unless in an LGPL module) of Nvidium's renderer onto the chosen backend; coexistence with Iris (Nvidium gives up with packs) |
| B4 | **DLSS upscaling / frame generation** | NVIDIA Streamline/DLSS SDK licence terms and redistribution; motion vectors/jitter are the same problem as A2 (share the code) |
| B5 | **Test hardware** | All testing so far is on an M3 Max. The owner has a Windows PC with an RTX 4070 Super for this track; it needs its own test setup and a benchmark matrix like the Mac one before any claim |

Proposed order: B0 and B5 first (they decide whether the track can start), then B2 as a research note, then B1/B3.
B4 after A2 so the motion-vector work is shared.

## Not in these tracks (still open from 1.0)

From `docs/STATUS.md` / `docs/RELEASE.md`: Voxy LOD water seam (upstream Voxy), a dark line in the sky (owner: later),
tessellation shaders, mipmapped shadow maps, broad nativium-gl coverage campaign (tag `parked-gl-coverage`).
