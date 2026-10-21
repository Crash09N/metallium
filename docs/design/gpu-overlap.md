# GPU overlap: hazard tracking instead of the frame-fence chain

Status: opt-in toggle `-Dnativium.gpu.overlap=true` (game test: `-PgpuOverlap`), default off (not faster for every pack). Results: see
"Measurements" below.

## Problem

Every Nativium encoder waits for one `MTLFence` when it starts and updates it when it ends (inherited from
Metallum; resources are `MTLHazardTrackingModeUntracked`). External encoders (Iris compute and mip blits,
nativium-gl) do the same through `NativiumInterop.frameFence()`. Fences work across command buffers, so the chain
also runs from frame N's last encoder (present copy) into frame N+1's first one: the GPU executes the whole stream of
up to three frames in flight strictly one encoder after the other. Not even the vertex stage of a pass overlaps the
fragment stage of the previous one.

For heavy shader packs that leaves GPU time on the table. Complementary Unbound with colored lighting
(`iris-stage5-compute.md`, Performance): frame N+1 starts with the `voxel_img` clear, the shadow pass (voxelization)
and the floodfill, ~5 ms together, while frame N still runs composite1..7 and final, ~3.3 ms, none of which touch
what the start of N+1 writes.

## Dependencies that must hold

| Resource class | Hazard | Who orders it |
|---|---|---|
| Render targets, colortex ping-pong, history buffers, shadow maps, depth | RAW / WAR / WAW between passes, also across frames (frame N+1's shadow pass writes `shadowtex` that frame N's composite still samples) | Metal hazard tracking (tracked textures) |
| Custom images / SSBOs written by compute or fragment shaders (incl. image atomics) | RAW / WAR / WAW | tracking + the external-encoder fence (below) |
| GPU buffers written by blits (uploads from staging, `copyToBuffer`), Sodium/terrain arenas | WAR against earlier draws, RAW for later draws | tracking (tracked buffers) |
| CPU-written shared memory (transient ring, dynamic buffers, staging) | CPU write while the GPU still reads | unchanged: `MAX_SUBMITS_IN_FLIGHT` ring, orphaning of dynamic buffers, destroy queue. Hazard tracking never covers CPU writes |
| Present ring (VSync off: frame copy -> separate present command buffer) | RAW copy -> present, WAR when a copy slot is reused 3 presents later | tracking (the copies are tracked); ring size check unchanged |
| Blits inside one encoder | Metal does not order commands within an encoder | unchanged: `blitFor` opens a new blit encoder on a hazard |
| Readbacks (`GpuFence`, `commitAndWait`) | CPU waits for command buffer completion | unchanged |

## Options considered

1. **Per-resource fences / events managed by Nativium** (last-writer fence and reader set per texture/buffer, each
   encoder waits only for the fences of what it reads and writes). Most control, but every binding path would have
   to report its resources before the encoder is opened: renderpearl bindings, `setDrawBindings` binders (Iris
   images/SSBOs), Iris compute, nativium-gl (argument buffers, ICBs), terrain. Java-side bookkeeping per bind on a
   CPU-bound renderer, and a missed resource is a silent race. Not chosen as the first step.
2. **Hazard-tracked resources (chosen).** Create the GPU-written resources with `MTLHazardTrackingModeTracked` and
   drop the frame-fence chain between Nativium's encoders. Metal then inserts dependencies only between encoders
   that touch the same resource (across command buffers of the queue too), so frame N+1's shadow/compute work may
   start while frame N's composite chain runs. All binding paths are covered automatically (`useResource` already
   declares the indirectly referenced resources of nativium-gl's argument buffers / ICBs). Cost: driver CPU time
   per bound tracked resource, and dependencies are per whole resource (a buffer arena is one resource).
3. **Tracked heaps** would collapse all resources of a heap into one dependency; Nativium does not use heaps.

## Design (option 2)

- `NativiumInterop.GPU_OVERLAP` (system property `nativium.gpu.overlap`, read once). `hazardTrackingMode()` /
  `hazardTrackingOption()` return Tracked in overlap mode, Untracked otherwise. Used by `MetalGpuTexture`,
  `MetalGpuBuffer` (incl. orphaned backings, same options), texture-buffer views (copy the buffer's mode), present
  copies, Iris `CustomImages` / `StorageBuffers`, Physics Mod's 3D textures. Buffers that the GPU only reads
  (Iris compute uniform blocks, the zero SSBO) stay untracked: no GPU write, no hazard.
- Nativium's render and blit encoders no longer update a fence. They still *wait* for a second fence, the
  **external-encoder fence**, which `NativiumInterop.frameFence()` returns in overlap mode: Iris compute encoders and
  nativium-gl encoders wait for and update it (their code is unchanged). Reason: nativium-gl found that tracking
  alone did not reliably order a compute pass with texture atomics against the blits around it
  (`MetalGlDevice.ownFence`), so compute results stay fence-ordered before anything that follows them. Nativium
  encoders waiting for it only wait for the last compute encoder submitted before them.
- Iris mip blits (`MetalCompute.blitEncoder`) wait for the external fence but do not update it in overlap mode: they
  run in the composite chain, and updating it would make frame N+1's first encoders wait for frame N's mip chain.
- Present path: unchanged code. The present encoders keep waiting for / (VSync on) updating the original frame fence,
  which nothing else updates in overlap mode; the frame copy and the main target are tracked, so the present reads
  complete data.
- Default off: with the property unset nothing changes (same fences, same untracked resources).

Remaining overlap limits by construction: compute encoders still form a chain (frame N+1's first compute waits for
frame N's last compute); whole-resource dependencies (one terrain buffer arena, one transient ring that is only read).

## Validation plan

- Pixel identity: game test screenshots with overlap on vs off; all Iris test packs (incl. compute/history packs) 1.00.
- `MTL_DEBUG_LAYER=1` run with overlap on.
- Bench (exclusive GPU, `bench-env: VALID`, 2560x1440 fullscreen on the 144 Hz Odyssey G5): shaders off,
  Complementary Unbound (`COLORED_LIGHTING=512`, `shadowDistance=256`), Complementary Reimagined, Rethinking Voxels;
  rendered fps and the `gpu=` perf counter, overlap on vs off.
- Metal System Trace (`xctrace` + `tools/metal-trace-encoders.py`) to show encoders of consecutive command buffers
  overlapping.

## Measurements (2026-10-21, M3 Max, Odyssey G5 144 Hz, 2560x1440 fullscreen, VSync off)

Exclusive GPU lock, every row `bench-env: VALID` (fullscreen, focused, display mode / main target / drawable all
2560x1440), lock-step fix on, `-PperfCounters`. One run per cell (off and on back to back).

| scene | overlap off: avg fps / p50 ms | overlap on: avg fps / p50 ms | change |
|---|---|---|---|
| Complementary Unbound r5.6.1, `COLORED_LIGHTING=512`, `shadowDistance=256` | 74.5 / 13.37 | 81.6 / 12.22 | **+9.5 %** |
| Complementary Reimagined r5.9.3 (defaults) | 129.8 / 7.62 | 158.2 / 6.27 | **+21.9 %** |
| Rethinking Voxels r0.1-beta9 (defaults) | 84.2 / 11.82 | 75.5 / 13.13 | **-10.3 %** |
| shaders off (Sodium, `light` scene) | 2637.6 / 0.37 | 2736.9 / 0.36 | +3.8 % (noise level) |

Displayed fps followed (Unbound 69.7 -> 76.0, Reimagined 112.1 -> 129.0, Rethinking Voxels 78.4 -> 70.0).

Metal System Trace (3 s during the measurement, `tools/metal-trace-encoders.py`, new `overlap:` lines):

| run | frame start-to-start | overlap with previous frame | GPU busy per frame | channel time per frame | concurrency |
|---|---|---|---|---|---|
| Unbound, off | 13.42 ms | 0.00 ms (0/273 frames) | 13.40 ms | 13.40 ms | 1.00 |
| Unbound, on | 12.34 ms | 1.19 ms (296/296) | 12.33 ms | 17.67 ms | 1.43 |
| Rethinking Voxels, on | 13.91 ms | 1.84 ms (265/265) | 14.13 ms | 16.00 ms | 1.13 |

(The Rethinking Voxels "off" trace was not recorded; its bench result is in the table above.) With overlap off the
GPU executes strictly serially (concurrency 1.00, no frame overlaps the previous one). With overlap on, every frame
starts ~1.2-1.8 ms before the previous one ends and encoders run concurrently. In Unbound that shortens the frame;
in Rethinking Voxels the concurrent work slows down more than it gains (channel time grows with little extra
concurrency) - consistent with its bandwidth-heavy voxel volumes competing with the previous frame's passes.

Correctness: Iris game test with overlap on: all test packs 1.00 (inverted, gbuffers, block-selected, shadow,
compute incl. image atomics/SSBO/3D reads/mip band, custom textures, history: previous-frame buffers/uniforms),
`result: PASS`; screenshots vs overlap off identical except moving entities/particles (spinning item, particle
positions; timing, not rendering). `MTL_DEBUG_LAYER=1` with overlap on: PASS, no validation messages (after fixing a
pre-existing zero-height mip clear copy that aborted the debug run with overlap off too). metallium-gl game test
(`-PwithGl -PwithSodium -PgpuOverlap`): PASS, pixel checks unchanged.

### Decision

Not enabled by default: it is not faster for every pack (Rethinking Voxels -10 %). The toggle stays (off = the
old fence chain and untracked resources, bit-for-bit the previous behaviour) so heavy compute-light packs can use
it (`-Dnativium.gpu.overlap=true`). Next steps if pursued: per-pack measurements with more runs; finding what in
Rethinking Voxels loses (trace per encoder with `-PperfTrace` mapping; candidates: shared-storage 3D custom images
tracked, the compute chain through the external fence); possibly limit cross-frame overlap (e.g. let the first
encoder of a frame wait for the previous frame's last fence update unless the pack is known to benefit).
