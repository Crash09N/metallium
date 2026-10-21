# Making a mod Metal-ready (for mod authors)

Nativium runs Minecraft 26.3 on Apple's Metal. You do **not** need to write Metal code: if your mod draws through
Minecraft's own graphics layer (*renderpearl*: `GpuDevice`, `RenderPipeline`, `RenderPass`, `GpuBuffer`, `GpuTexture`),
it runs on Metal, Vulkan and OpenGL alike, because Nativium implements that layer on Metal.

What breaks is code that bypasses the layer:

| Your code uses | On Nativium |
|---|---|
| Vanilla/Fabric rendering APIs (`GuiGraphicsExtractor`, `RenderPipelines`, render types, `LevelRenderEvents`, HUD elements) | native on Metal |
| renderpearl directly (`RenderSystem.getDevice()`, `RenderPass`, `GpuBuffer`, …) | native on Metal |
| LWJGL OpenGL (`GL11.glEnable`, `GL30.glBindFramebuffer`, `GL.getCapabilities()`, …) | no OpenGL context exists: runs only through **nativium-gl** (a translation layer; partial coverage) |
| Minecraft's OpenGL backend classes (`com.mojang.renderpearl.backend.opengl.*`: `GlStateManager`, casts to `GlDevice`/`GlTexture`) | breaks; cannot be translated |

## Raw GL → renderpearl

```java
// Before: raw OpenGL (fails on Metal and Vulkan)
GL11.glEnable(GL11.GL_BLEND);
GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
```

```java
// After: a pipeline declares the state once (blend, depth, cull, vertex format, shaders) ...
public static final RenderPipeline MY_PIPELINE = RenderPipeline.builder(/* vanilla snippets, e.g. matrices/fog */)
        .withLocation(Identifier.fromNamespaceAndPath("mymod", "pipeline/overlay"))
        .withVertexShader(Identifier.fromNamespaceAndPath("mymod", "core/overlay"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("mymod", "core/overlay"))
        .withColorTargetState(/* ColorTargetState with your blend function */)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withCull(false)
        .build();

// ... and a render pass draws with it into a texture (the main target or your own GpuTexture).
GpuDevice device = RenderSystem.getDevice();
GpuBuffer vertices = device.createBuffer(() -> "mymod overlay", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data);
try (RenderPass pass = device.createCommandEncoder()
        .createRenderPass(() -> "mymod overlay", colorTargetView, Optional.empty())) {
    pass.setPipeline(RenderSystem.getCompiledPipeline(MY_PIPELINE));
    pass.setVertexBuffer(0, vertices.slice());
    pass.draw(0, vertexCount, 0, 1);
}
```

Mapping of common GL habits:

- `glEnable(GL_BLEND)`, `glBlendFunc`, `glDepthFunc`, `glCullFace` → fields of the `RenderPipeline` (immutable state).
- `glGenBuffers` + `glBufferData` → `device.createBuffer(...)`; updates → `commandEncoder.writeToBuffer(slice, data)`.
- `glGenTextures` + `glTexImage2D` → `device.createTexture(...)` + `createTextureView(...)`; bind with
  `pass.setUniform("Sampler0", view, sampler)`.
- FBOs → a `GpuTexture` with render-attachment usage as the pass's colour/depth target.
- GLSL: write shaders as Minecraft core shaders (resource pack `assets/<mod>/shaders/`). Nativium translates them to
  Metal Shading Language; keep to the GLSL subset vanilla's shaders use.
- Prefer the high-level APIs where they fit: `GuiGraphicsExtractor.fill/blit/text` for HUDs and screens, render types /
  `LevelRenderEvents` for world overlays. They are already backend-independent.

Do not cast `RenderSystem.getDevice()` (or textures/buffers) to OpenGL classes, and do not call `GL.getCapabilities()`
to detect features: on Metal there is no GL context. Check `RenderSystem.getDevice().getDeviceInfo().backendName()` if
you really need to know the backend ("Metal", "OpenGL", "Vulkan").

## nativium-gl: automatic translation, opt-out and opt-in

At start-up Nativium scans every mod's bytecode (cached by jar contents) for calls to `org.lwjgl.opengl.*`. If Metal is
in use and the optional **nativium-gl** module is installed, those classes are retargeted to nativium-gl
automatically: no opt-in is needed. Coverage is partial (2D textures, buffers, FBOs, GLSL 330+ programs, VAOs, draws,
blend/depth/cull state); every unimplemented GL function is logged once per mod and listed on the status screen, and
the bridge falls back to real OpenGL whenever the game runs on OpenGL.

Declare in `fabric.mod.json` if you want to steer this:

```json
"custom": {
  "nativium-gl": false
}
```
opts out (e.g. you already have a Metal-safe path and only call GL on the OpenGL backend).

```json
"custom": {
  "nativium-gl": { "packages": ["com.example.mymod.render"] }
}
```
opts in explicitly for those packages (also when the scan finds nothing, e.g. GL calls through reflection);
`"nativium-gl": true` opts in the whole mod.

Users can override per mod in **Video Settings → Nativium → Nativium status…** (Auto / Native / nativium-gl), or with
`-Dnativium.gl.mods=modid` / `mods=modid` in `config/nativium-gl.properties`.

Before the rename (Nativium was called **Metallium** until 0.1.0-alpha) the key was `"metallium-gl"`; it is still read
when `"nativium-gl"` is absent, for one release. Likewise the old runtime API `dev.metallium.api.MetalliumRuntime` is a
deprecated shim that delegates to `de.nprmedia.nativium.api.NativiumRuntime` (`isMetalActive()`, `registerStatusLine()`,
`statusLines()`, `metalDescription()`); switch to the new class, the shim will be removed after this release.

If a translated mod crashes the game, Nativium does not crash-loop: the next start runs on OpenGL and asks the user
whether to disable the mod, keep using OpenGL, or try Metal again.

## Testing your mod with Nativium

1. macOS on Apple Silicon, Java 25. Put the Nativium jar (and Sodium, if you support it) next to your mod in a dev run
   (`modLocalRuntime files("libs/nativium-<version>.jar")` in Loom, or the `mods` folder).
2. Start the game and open **Nativium status…** (Sodium: Video Settings → Nativium tab → General; without Sodium: end
   of vanilla's Video Settings). Your mod should be listed as *native on Metal*. If it shows *calls OpenGL directly*, the
   listed functions tell you which classes to port.
3. The log has one line per mod with direct GL use: `[nativium] mod scan: ...`, followed by the classes/functions.
   `F3` shows `Nativium …: Metal active` plus adapter/nativium-gl lines.
4. Compare with vanilla's Vulkan backend (Video Settings → Graphics API → Vulkan): code that works on Vulkan almost
   always works on Nativium too.
5. Apple's Metal HUD (frame times, GPU memory): start the game with the environment variable `MTL_HUD_ENABLED=1`.

Problems or questions: open an issue at https://github.com/Crash09N/metallium.
