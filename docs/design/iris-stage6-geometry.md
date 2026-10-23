# Iris stage 6: geometry shaders on Metal

Metal has no geometry stage. Programs with a `.gsh` used the vanilla shader before stage 6; the one pack in the local
corpus that has one is Rethinking Voxels (`shadow.gsh`: voxelizes every shadow-pass triangle into `voxelCols` /
`occupancyVolume` with image atomics and adds block lights to `globalLightHashMap` with SSBO atomics, and emits the
shadow-map triangle plus a voxel-map triangle). Without it the voxel volume stayed empty: terrain near the camera was
dark in daylight and block lights (torches) did not light anything.

## Approach: GS emulated in the vertex stage

`GeometryEmulation` (nativium-iris) merges the Iris-patched VS and GS into one vertex shader. The pipeline is drawn
non-indexed as a triangle list with `slots = 3 * (max_vertices - 2)` vertices per input triangle (the most triangles a
`triangle_strip` of `max_vertices` vertices can produce). When every `EmitVertex()` of the GS `main` sits in a
`for (int i = 0; i < 3; i++)` loop directly followed by `EndPrimitive()`, each strip is one triangle and
`3 * (max_vertices / 3)` slots suffice (Rethinking Voxels: 6 instead of 12). Invocation `i` handles triangle
`i / slots`, output slot `i % slots`:

1. **Vertex pulling.** It reads the three corner indices from the index buffer (16/32 bit, `firstIndex`,
   `baseVertex`) and decodes the VS inputs from the vertex buffer in GLSL, from the replaced pipeline's
   `VertexFormat` (UNORM/SNORM/UINT/SINT/FLOAT, 8/16/32 bit, with Metal's vertex-fetch conversions). The VS `in`
   declarations become globals; `gl_VertexID` becomes the pulled vertex index.
2. **VS per corner.** The VS `main` is renamed and runs three times; its outputs (globals now) are copied into the
   GS input arrays (`gs_in_<name>[3]`), `gl_Position` into `gl_in[i].gl_Position`. VS global initializers
   (Iris: `iris_Entity = f(mc_Entity)`) move into the VS function, since GL runs them after the inputs are set.
   VS outputs the GS does not read (`iris_FogFragCoord`) stay outputs (value of the last corner).
3. **GS per slot.** `EmitVertex()` keeps the last three vertices of the current strip in a ring; when the strip
   completes output triangle `slot / 3`, corner `slot % 3` (GL strip winding: odd triangles swap corners 0/1) is copied
   to the real outputs. `EndPrimitive()` restarts the strip. Slots whose triangle is never emitted collapse to one
   point outside the clip volume (no rasterization).
4. **Side effects once per primitive.** Slot 0 runs the GS as written. All other slots run a copy in which
   `imageAtomic*`, `atomic*` and `imageStore` are macros returning 0 / doing nothing, so voxelization and light-hash
   writes happen exactly once per input triangle; the Metal compiler also drops the work that only fed them
   (Rethinking Voxels' 19 texture samples for the voxel colour).

`MetalRenderPass` got one hook for this: `NativiumInterop.setDrawInterceptor(pass, interceptor)`, set by
`GbufferState.bind` after `setPipeline` and cleared by every `setPipeline`. All indexed/non-indexed (multi-)draws of
that pipeline go to the interceptor, which binds vertex buffer slot 0, the index buffer and the draw parameters at
Metal buffer indices 22-24 and issues `drawPrimitives(Triangle, 0, triangles * slots)`.

Mesa's geometry-shader lowering for Apple GPUs (run the GS per output vertex, count primitives) was the idea source;
no Mesa code is used.

## Switches

- `-Dnativium.iris.geometryShaders=false` (game test: `-PnoGeometryShaders`): off, programs with a `.gsh` use the
  vanilla shader as before. Default on.
- `-Dnativium.iris.dumpGeometry=true`: writes the merged sources to `<game dir>/nativium-iris-debug/`.

## Verified (M3 Max, `-PwithIris -Pshaderpack=rethinking-voxels_r0.1-beta9.zip`)

The shadow program translates for Sodium solid/cutout/translucent terrain and entities. Screenshots before/after:
daylight terrain near the camera lit instead of dark, tree shadow and shaded leaf undersides, torch light on players
and ground at night (was absent). Complementary Reimagined, vanilla and Sodium game tests unchanged.

## Limits

- `layout(triangles) in` with `triangle_strip` output only; no `points`/`line_strip` output, adjacency,
  `invocations` > 1, `EmitStreamVertex`, interface blocks or array outputs (such programs fall back to the vanilla
  shader as before). Only TRIANGLES/QUADS pipelines; indirect draws and triangle fans with a GS pipeline are skipped.
- Side effects in VS code, or in functions the GS `main` calls, run once per slot (12x for Rethinking Voxels) rather
  than once; idempotent stores are unaffected, atomic adds there would be counted several times.
- Cost: each input triangle runs the VS 3 x `slots` times and the GS `slots` times (6 and 18 for Rethinking
  Voxels' shadow pass). Benchmark (`-PbenchScene=shaders`, 2560x1440 fullscreen on the 144 Hz display, rd 12,
  `--exclusive`) with the first version (12 slots): 23.8 fps emulated vs 51.3 fps with the vanilla shadow fallback
  (`-PnoGeometryShaders`). The 6-slot version was not benchmarked yet (Mac in use); about half the extra cost is
  expected. A compute pre-pass (VS+GS once per triangle into an output buffer) would be cheaper per
  triangle but needs a compute encoder between render encoders mid-pass.
