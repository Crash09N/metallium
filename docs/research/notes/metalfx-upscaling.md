# MetalFX upscaling / frame interpolation for Metallium

Date 2026-10-18. Labels: [V] Verified, [S] Strongly inferred, [H] Hypothesis.

## 1. "Metal Upscaler" mod audit

- [V] CurseForge page only (by Metal_Team). Fabric + NeoForge; MC 26.2 and 1.21.1 (v0.5.0, 2026-10-11); 1.21.11/1.20.1 planned v0.6.0. Licence: All Rights Reserved.
- [V] No source link (page links only Instagram/YouTube); not on Modrinth (API search "metal upscaler" returned 0 hits); web search found no GitHub repo.
- [V] Jar download not possible: direct curl to /download/9130962 returns HTTP 403 (needs browser). Nothing cloned, no jar inspected. Empty dir created: /Users/noah/Desktop/Nativium/nativium-research/metal-upscaler-jar. If the owner wants a javap look, they must download the jar manually (but licence is ARR: ideas only; never copy).
- Everything below on how it works is [V as the author's claim, from the page text], not from code:
  - Stays on the OpenGL backend; renders at reduced resolution; upscaler per platform: DLSS (RTX, via D3D12), MetalFX (Apple Silicon), FSR1 (AMD/Intel), Lanczos fallback.
  - Cross-API sharing via IOSurface on macOS (shared NT handles on Windows) -> GL texture and Metal texture alias the same memory.
  - Presents through its own CAMetalLayer (macOS) / DirectComposition (Windows), compositing the HUD itself; "menus/text/inventory never touched" = UI at native res.
  - Motion vectors are NOT produced by MC; reconstructed from depth buffer + camera matrices: exact for static geometry, smeared for entities.
  - Frame generation: up to 2x on Apple Silicon (and RTX 40), 6x on RTX 50; off by default, adds input latency.
  - A search snippet says the MetalFX path is labelled experimental and off by default, Lanczos is usually faster on the OpenGL path [V, secondary].
- [H] Not documented: jitter source, exact MetalFX classes used, native dylib vs FFM, macOS version floor. Frame gen at 2x on Apple Silicon implies MTLFXFrameInterpolator (macOS 26) [S].
- Takeaway: it validates the approach (depth + camera-matrix MVs, UI composited after) but offers no code. Our advantage: we own the Metal device, so no IOSurface interop is needed.

## 2. MetalFX API facts

- [V] Apple docs (via search): MTLFXSpatialScaler and MTLFXTemporalScaler (+descriptors): macOS 13.0+. MTLFXFrameInterpolatorBase / Descriptor, MTL4FXTemporalScaler: macOS 26.0+. [S] MTLFXFrameInterpolator class page itself not seen; macOS 26 inferred from sibling pages + one third-party note. The dev machine runs Darwin 27 so testable locally.
- [V] Requires Apple-silicon-class GPU family support check: `MTLFXSpatialScalerDescriptor.supportsDevice(device)` exists in metal-jvm; Intel Macs likely return false [S].

## 3. metal-jvm 0.1.0 exposure (jar in ~/.gradle/caches/modules-2/files-2.1/io.github.kokodio/metal-jvm/0.1.0)

[V] Package io.github.kokodio.metaljvm.metalfx contains: MTLFXSpatialScaler(+Base,+Descriptor,+ColorProcessingMode), MTLFXTemporalScaler(+Base,+Descriptor), MTLFXTemporalDenoisedScaler(+Base,+Descriptor), MTLFXFrameInterpolator(+Base,+Descriptor), MTLFXFrameInterpolatableScaler, MTL4FX{Spatial,Temporal,TemporalDenoised}Scaler, MTL4FXFrameInterpolator.
- Spatial: `MTLFXSpatialScalerDescriptor.alloc()`, `newSpatialScaler(MTLDevice)` (also MTL4Compiler overload), `supportsDevice`, `supportsMetal4FX`; scaler: setColorTexture/setOutputTexture/setInputContentWidth/Height/setFence; `encodeToCommandBuffer(MTLCommandBuffer)`.
- Temporal: setColorTexture, setDepthTexture, setMotionTexture, setOutputTexture, setExposureTexture, setPreExposure, setJitterOffsetX/Y, setMotionVectorScaleX/Y, setReset, setDepthReversed, content/offset setters, reactive mask; encodeToCommandBuffer.
- FrameInterpolator: content size, depth/motion/ui/distortion offsets, color/depth/motion/output/ui textures; encodeToCommandBuffer.
- Not checked: descriptor property setters (width/height/formats) and the temporal/interpolator descriptor factory names; the Base classes are 8-25 KB so they are almost certainly full. Verify with javap before coding.

## 4. Design for Metallium

Current state [V]: MetalSurface.blitFromTexture -> MetalCommandEncoder.presentTextureToDrawable(drawable, textureView) does the final blit; CAMetalLayer drawable sized to window. MC renders into a main render target (renderpearl GpuTexture) sized to the window.

### 4.1 Spatial upscaling (MTLFXSpatialScaler) - effort S-M (3-5 days), risk low
- Option `renderScale` (0.5-1.0 + presets). Main render target allocated at scale*window size; a native-size output texture (private, usage shaderWrite/RenderTarget, format matched to color) holds the upscaled image.
- Insert in the present path: when scale<1, encode scaler (color = scaled target, output = native texture) on the same command buffer before presentTextureToDrawable, then blit output. Same command buffer -> no fence needed (fence setter optional).
- UI at native res [H, depends on MC]: MC 26.x draws the GUI into the same main target after the world, so UI would be scaled too. Needs a split: world pass -> low-res target, upscale -> native target, then GUI drawn at native res on top. That requires intercepting the point between level render and GUI (mixin in GameRenderer/LevelRenderer boundary, rebinding the main target); this is the real cost. Fallback v1: upscale everything (blurry HUD), ship behind option.
- Also: resize handling, mouse/viewport scaling, screenshots, F3 status line, textures' mip bias (-log2(1/scale)) for temporal later. Compare against a Lanczos/bilinear fallback for Intel Macs.
- Risks: MTLFXSpatialScaler wants color texture usage shaderRead and output usage shaderWrite (storage) + private storage; our textures may lack these usage flags [S]; sRGB vs linear colorProcessingMode (perceptual for gamma-encoded MC output).

### 4.2 Temporal upscaling (MTLFXTemporalScaler) - effort L (2-4 weeks), risk high
- Jitter: Halton(2,3) sequence, phase count per scale; inject sub-pixel offset into the projection matrix used for the world pass (MC's GameRenderer.getProjectionMatrix / the projection UBO), not for GUI. Pass same jitter (in pixels, sign per Apple docs) via setJitterOffsetX/Y each frame.
- Depth: needs the world depth texture readable (sampled) after the pass; depth format depth32f; MC uses reverse-Z? unknown [H], set setDepthReversed accordingly.
- Motion vectors: MC emits none. Plan A (terrain, static): fullscreen pass reconstructing world position from depth + inverse(current viewProj), reprojecting with previous viewProj (unjittered), output RG16F in pixels (setMotionVectorScale). Exact for static terrain. Plan B (entities/block entities, particles, hand): write MVs in-pass requires extra MRT in entity pipelines and prev-model matrices per entity -> invasive; v1 mask them via zero-MV + reactive mask, accept ghosting (this matches what Metal Upscaler admits). Water/clouds/translucent also ghost.
- Teleport/dimension change/respawn: setReset(true). Exposure: constant/ preExposure=1 (MC has no HDR exposure).
- History needs a persistent output texture; frame-in-flight handling for per-frame textures.
- Risks: MC post-processing (transparency/entity-outline chains) writes into the main target after the world pass; scaler must run after them but before GUI; jitter must not break pixel-exact things (sky, UI blits). Shader packs (Iris adapter) would need own path: out of scope.

### 4.3 Frame interpolation (MTLFXFrameInterpolator) - effort XL (4+ weeks), risk very high
- API: macOS 26+ [V-ish, see above]; paired with the temporal scaler (MTLFXFrameInterpolatableScaler -> `frameInterpolator` attached on the temporal scaler descriptor) [S].
- Needs: temporal path done first (depth, MVs, jitter), a separate UI texture (so UI is composited after interpolation - this forces the split from 4.1), and a presentation scheduler that presents the interpolated frame at half the real frame interval (MTLDrawable present-after-minimum-duration / display link pacing). Our MetalSurface present path (prefetcher, immediate/FIFO modes) would need 2 presents per game frame and latency management. Also needs previous-frame buffers and per-frame "delta time".
- Risk: input latency, entity MV quality (artifacts), pacing with vsync modes, needs Metal 4-style APIs maybe (MTL4FXFrameInterpolator exists in metal-jvm) [H]. Recommend: do NOT start until 4.2 ships; spike with a standalone sample to validate availability on target macOS.

### 4.4 Suggested order
1. 4.1 with full-frame upscale (quick win), then the world/GUI split. 2. 4.2 terrain-only MVs. 3. Evaluate 4.3.
