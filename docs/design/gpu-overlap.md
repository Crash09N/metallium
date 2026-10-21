# GPU overlap: hazard tracking instead of the frame-fence chain

Status: experiment behind `-Dmetallium.gpu.overlap=true` (game test: `-PgpuOverlap`), default off. Results: see
"Measurements" below.

## Problem

Every Metallium encoder waits for one `MTLFence` when it starts and updates it when it ends (inherited from
Metallum; resources are `MTLHazardTrackingModeUntracked`). External encoders (Iris compute and mip blits,
metallium-gl) do the same through `MetalliumInterop.frameFence()`. Fences work across command buffers, so the chain
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

1. **Per-resource fences / events managed by Metallium** (last-writer fence and reader set per texture/buffer, each
   encoder waits only for the fences of what it reads and writes). Most control, but every binding path would have
   to report its resources before the encoder is opened: renderpearl bindings, `setDrawBindings` binders (Iris
   images/SSBOs), Iris compute, metallium-gl (argument buffers, ICBs), terrain. Java-side bookkeeping per bind on a
   CPU-bound renderer, and a missed resource is a silent race. Not chosen as the first step.
2. **Hazard-tracked resources (chosen).** Create the GPU-written resources with `MTLHazardTrackingModeTracked` and
   drop the frame-fence chain between Metallium's encoders. Metal then inserts dependencies only between encoders
   that touch the same resource (across command buffers of the queue too), so frame N+1's shadow/compute work may
   start while frame N's composite chain runs. All binding paths are covered automatically (`useResource` already
   declares the indirectly referenced resources of metallium-gl's argument buffers / ICBs). Cost: driver CPU time
   per bound tracked resource, and dependencies are per whole resource (a buffer arena is one resource).
3. **Tracked heaps** would collapse all resources of a heap into one dependency; Metallium does not use heaps.

## Design (option 2)

- `MetalliumInterop.GPU_OVERLAP` (system property `metallium.gpu.overlap`, read once). `hazardTrackingMode()` /
  `hazardTrackingOption()` return Tracked in overlap mode, Untracked otherwise. Used by `MetalGpuTexture`,
  `MetalGpuBuffer` (incl. orphaned backings, same options), texture-buffer views (copy the buffer's mode), present
  copies, Iris `CustomImages` / `StorageBuffers`, Physics Mod's 3D textures. Buffers that the GPU only reads
  (Iris compute uniform blocks, the zero SSBO) stay untracked: no GPU write, no hazard.
- Metallium's render and blit encoders no longer update a fence. They still *wait* for a second fence, the
  **external-encoder fence**, which `MetalliumInterop.frameFence()` returns in overlap mode: Iris compute encoders and
  metallium-gl encoders wait for and update it (their code is unchanged). Reason: metallium-gl found that tracking
  alone did not reliably order a compute pass with texture atomics against the blits around it
  (`MetalGlDevice.ownFence`), so compute results stay fence-ordered before anything that follows them. Metallium
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

## Measurements

(filled in below)
