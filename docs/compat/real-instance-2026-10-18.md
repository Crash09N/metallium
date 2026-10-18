# Real-instance stress test, 2026-10-18

The owner's everyday modpack (Fabric, Minecraft 26.3, 145 enabled mods, 16 resource packs, 7 shader packs) on
Metallium 0.1.0-alpha.1. The test ran on an Apple M3 Max with macOS 27.0.1, in an 854x480 window at render distance 5
(the instance's own setting).
This file has no private data: only mod and pack names and versions. Screenshots and logs are kept outside the
repository in `/Users/noah/Downloads/metallium-research/sweep-results/real-instance-2026-10-18/`, with one folder per
run: `full2/` (all packs on Metal), `fps-metal/` and `fps-opengl/`, `solas-opengl/`, and `full1/` (before the fixes).

## How it was run

```bash
tools/make-run-instance.sh /path/to/instance        # -> run-instance/ (git-ignored), instance itself untouched
./gradlew runClientGameTest -Pinstance               # all shader packs; -PinstancePacks=a.zip,b.zip | -
./gradlew runClientGameTest -Pinstance -PbackendOff  # same on OpenGL
./gradlew runClientGameTest -Pinstance -PinstanceCrashRecovery   # real crash recovery across runs
```

- **Copied:** the enabled `mods/*.jar`, `shaderpacks/`, `resourcepacks/`, `config/` and `options.txt`.
- **Not copied:** saves, server lists, Essential account data, logs, crash reports and screenshots.
- **Changes to the copy:**
  - Windowed mode, all sound volumes at 0, no pause when the window loses focus.
  - Dynamic FPS and FPS Reducer turned off. When the window has no focus, Dynamic FPS renders at 1 fps, which makes
    every FPS number meaningless. `KEEP_FPS_LIMITERS=1` keeps them on.
- **Mods left out:**
  - Fabric API: the dev classpath already provides 0.163.0.
  - Essential 1.5.1: its loader re-maps Essential to "dev mappings" whenever `fabric.development=true`. 26.x ships
    no `mappings.tiny`, so it cannot start in a Loom dev launch ("Failed to find tiny mappings file"). This is a
    limit of the dev launch, not a Metallium issue. `KEEP_ESSENTIAL=1` keeps it in.
  - Physics Mod: left out of the shader runs only (see below).
- **Loading:** the instance's own Sodium 0.9.3-beta.1, Iris 1.11.7 and all other mods load from `run-instance/mods`.
  Metallium, metallium-iris and metallium-gl come from the dev build.
- **Scenario:** a fresh natural world (seed `metallium-forest`, creative mode), with a cow, a villager, a zombie, a
  chest and a torch in view.
  - Without shaders: screenshots of the title screen, the world, F3, rain and night, plus 10 s of FPS samples.
  - Then every shader pack: a day and a night screenshot plus FPS samples.
- **Safety checks:**
  - Mod screens that pop up (onboarding, login, updates) are logged with the mod that owns them and then closed.
    None appeared, because the copied configs already skip them.
  - A 30-minute watchdog writes the report and stops the JVM if the run hangs.
  - No mod needed window focus.

## Result overview

| Area | Result |
|---|---|
| Start-up, 352 mod containers, Metal backend | OK. No mixin conflict with Metallium or the adapters; the only mixin warnings are refmaps in dev and optional targets (VoxelMap, OptiFine, Vivecraft and other absent mods) |
| World rendering (Sodium 0.9.3-beta.1 on Metal), entities, ETF/EMF, Fresh Animations, Continuity, Better Leaves, 3D ladders/rails, rain, night | OK (screenshots `full2/0002`–`0005`) |
| HUD mods (Xaero minimap via metallium-gl, Jade, BetterF3, AppleSkin, inventory HUD, status-effect bars, FPS Reducer HUD, ReGlass) | OK |
| Physics Mod 3.2.5 | **Aborts the JVM on Metal at start-up.** This is a mod incompatibility. Metallium now recovers from it (see below) |
| Iris 1.11.7 + Sodium 0.9.3-beta.1 with the adapter | Loads (dependency widened to `>=1.11.6 <1.12`) |
| Shader packs | 5 of 7 render on Metal; Photon and Bliss render black (see the table) |
| FPS without shaders, exclusive GPU | Metal **639** avg (min 497) vs OpenGL **166** avg (min 128), same scene |

## Shader packs (Metal, after the fixes; `full2/`)

FPS is the vanilla FPS counter, sampled once per second for about 10 s. The scene looks over a dark-oak forest; the
window is 854x480 and render distance 5. The OpenGL reference is the same instance with `-PbackendOff`.

| Pack | Result on Metal | FPS | Remaining issues (adapter work) |
|---|---|---|---|
| Complementary Reimagined r5.6.1 | Renders well | 303 | DH programs not executed; some mipmapped buffers sampled without mips |
| Complementary Unbound r5.6.1 | Renders. Shows the pack's own "Colored Lighting is not supported on Apple devices" message | 173 | The pack detects Apple and asks to turn off Advanced Color Tracing. The owner's pack settings have it on; this is not a Metallium issue |
| BSL v10.0 | Renders well | 315 | Before the fix: `deferred` failed (`bool new`), giving a dark scene |
| Solas V3.1b | Renders | 273 | (in `full1` Iris itself failed to load it, see below) |
| Rethinking Voxels r0.1-beta9 | Renders | 140 (min 37 while loading) | (same intermittent Iris load failure in `full1`) |
| Photon v1.2a | **Black** | 376 | `deferred`, `deferred1`, `deferred4` and `composite` sample `customtex0`/`customtex3`, which are 3D textures ("only 2D textures are supported yet"). One compute shader skipped |
| Bliss v2.1.1 (Chocapic13 edit) | **Black** | 511 | See the list below this table |

Bliss has four separate problems:
- `deferred` and `composite2` fail in glslang: "'highp' : overloaded functions must have the same parameter precision
  qualifiers". The translator probably needs to strip precision qualifiers or make them match.
- `composite4` binds more than 16 samplers ("'sampler' attribute parameter is out of bounds: must be between 0 and
  15"). Metal allows 16 sampler states per stage, so samplers need to be shared or deduplicated.
- The gbuffers write 9 buffers (colortex up to 15), but a Metal pass has at most 8 colour attachments. This breaks
  `gbuffers_terrain`, `gbuffers_entities` and `gbuffers_hand` ("'color' attribute ... between 0 and 7").
- DH programs are not executed.

These belong to the Iris adapter (stage 5 or later); details are in `full2/gradle-run.log`.

## Fixed in Metallium (this branch)

1. **metallium-iris: NPE on the first frame of the first pack of a session.** Iris's `CustomUniforms.update()`
   updates `currentSelectedBlockId` for every pack. That reads `WorldRenderingSettings.getBlockStateIds()`, which was
   still null because `GbufferState.beginFrame` set up the ID maps only after the uniform update. With the crosshair
   on a block (creative or survival, i.e. normal play), the result was "gbuffers setup failed" and the pack never
   rendered. The ID maps are now set up before the uniforms, in the same order as Iris's own pipeline. A game test
   loads a pack with a block selected and no ID map.
2. **metallium-iris: garbled terrain after any gbuffers failure.** After a failure the chunks stayed in Iris's
   extended vertex format while vanilla shaders drew them, which exploded the geometry. The fallback now restores
   vanilla world settings and rebuilds the chunks. A game test simulates the failure
   (`-Dmetallium.iris.test.failGbuffers`); its screenshot match went from 0.72 to 1.00.
3. **Shader translator: identifiers that later stages reject.**
   - `sampler` and `samplerShadow`: Photon and Bliss use `sampler2D sampler`, but Vulkan GLSL reserves these words
     ("unexpected SAMPLER").
   - C++ keywords: BSL has `bool new`, which MSL rejects ("expected unqualified-id").
   - Merged into the single `VULKAN_ONLY_KEYWORDS` rename pass from stage 5 and extended with the remaining C++
     keywords. Unit tests cover it.
4. **Crash recovery for silent start-up deaths.** Physics Mod makes the JVM abort with no crash report and no
   hs_err. Before this fix CrashGuard found no evidence, so every start crashed again. The session marker now records
   "Metal offered" and "start-up finished". A Metal session that died during start-up without a crash file gets
   OpenGL safe mode and the recovery screen. The likely causes are listed with "Disable" buttons: routed mods that
   also use Minecraft's OpenGL backend classes, here Physics Mod, Polytone, XaeroLib and Axiom. Unit tests cover
   this, and it was verified twice in the instance: abort, then OpenGL plus the recovery screen.
5. **Mod scan false alarm: ImmediatelyFast** was shown as "calls OpenGL directly: will not render on Metal". The
   reason was its mixins into `GlCommandEncoder`, `GlSurface` and `GlDebug`. That code only runs inside Minecraft's
   OpenGL backend, which Metal does not use. Mixins whose targets are all in that backend no longer count, and the
   scan cache version was bumped.
6. **metallium-iris accepts Iris 1.11.7.** In the 1.11.6 → 1.11.7 jar diff, none of the adapter's mixin targets or
   signatures changed. `IrisRenderSystem.getVRAM` now reads `GL.getCapabilities`, which was already redirected to the
   all-false fake capabilities and gives the 4 GiB default. The other changes are in ShadowRenderer, IrisPipelines,
   MixinPipelineBuilder, MixinCamera, MixinGameRenderer, MixinEquipmentLayerRenderer and VanillaCoreTransformer.

## Mod incompatibilities (not Metallium's fault) and proposals

- **Physics Mod 3.2.5** (all rights reserved, closed source; studied only by bytecode inspection, nothing copied).
  - **Symptom:** a JVM abort during start-up: `FATAL ERROR in native method: [LWJGL] No context is current`, at
    `GL33C.glGenSamplers` ← `com.mojang.renderpearl.backend.opengl.GlSampler.<init>` ←
    `PhysicsRenderUtil$GlSampler3D.<init>` ← `MixinSamplerCache` (in `SamplerCache.initialize`).
  - **Root cause:** the mod handles two backends, `GlDevice` (OpenGL) and `VulkanDevice`, and treats every other
    device as OpenGL. It subclasses and constructs Minecraft's OpenGL backend objects in 25 classes (GlProgram,
    GlRenderPipeline, GlTexture, GlSampler, GlDevice casts). metallium-gl can only retarget the mod's own direct GL
    calls, not code inside Minecraft's backend classes.
  - **Workaround:** with this branch the second start runs on OpenGL and offers to disable the mod.
  - **Proposal:** ask the author to treat unknown backends as "GPU effects off" (no 3D sampler, no ocean FFT), as
    for Vulkan. A Metallium-side option would be a scan rule: a mod that constructs or subclasses
    `com/mojang/renderpearl/backend/opengl/*` → "needs OpenGL", proposed before the crash rather than after it.
    That rule is a policy decision for the owner.
  - **Update (same day, owner-approved):** both are done.
    - Pre-start detection: `OpenGlRequirements` (data list `assets/metallium/compat/needs-opengl.json` plus the scan
      rule "constructs or subclasses `com/mojang/renderpearl/backend/opengl/*`", which flags only Physics Mod in this
      pack). An unanswered mod starts the session on OpenGL and `NeedsOpenGlScreen` asks before the title screen:
      run on OpenGL (remembered in `openGlChoices`) / this session only / disable the mod / try Metal anyway.
    - Metal support for Physics Mod 3.2.5 (`dev.metallium.compat.physicsmod`, applied only to that verified version):
      its 3D sampler and 3D textures become Metal objects; GPU liquids and volumetric smoke report "unsupported" (as on
      macOS OpenGL 4.1, which has no compute); the FFT ocean already falls back by itself. Block debris, ragdolls and
      explosion debris render on Metal like on OpenGL (`./gradlew :runClientGameTest -PwithPhysics=/abs/physics-mod.jar`,
      also with `-PwithSodium -PwithGl`). Other versions get the question instead of a crash.
- **Axiom 6.1.3**: routed through metallium-gl. `Annotations` uses 5 GL functions metallium-gl does not implement
  yet (the retargeter logs 5 distinct functions without implementation). It also uses Minecraft's GL backend in 7 classes, so the editor
  overlays may fail on Metal. Not exercised in the test (it needs the Axiom editor). Status: partial.
- **Polytone 6.8.6**: `ExpressionUniformBuffers` uses `glBindBufferBase`, `glGetUniformBlockIndex` and
  `glUniformBlockBinding`, which metallium-gl does not implement. It only matters for resource packs with Polytone
  custom shader uniforms; the owner's packs did not trigger it.
- **XaeroLib, Xaero's Minimap and World Map, Crash Assistant, lsdc, FPS Reducer**: routed through metallium-gl with
  every function implemented. The minimap and the FPS Reducer HUD render correctly.
- **ImmediatelyFast 1.17.1**: native on Metal (its GL mixins are inactive there). No conflict seen.
- **Iris (also on OpenGL)**: Solas V3.1b and Rethinking Voxels failed to load in `full1` and in the OpenGL run with
  `java.nio.file.ClosedFileSystemException` in `IdMap.readProperties`. The log shows a nested Iris reload ("Shaders
  are disabled because enableShaders is set to false") while the zip pack was still loading, which closed the
  pack's zip file system. It is intermittent (both loaded in `full2`), happens on OpenGL too, and is not caused by
  Metallium. The test's quick pack switching may make it more likely.
- **Title screen labels missing in the first screenshot**: this is the vanilla widget fade-in (same on OpenGL); the
  screenshot taken 3 s later is correct (`fps-metal/0002_instance-title.png`).
- **Harmless log noise**: Sodium Extra Information telemetry error, JER missing config, ViaFabricPlus packet
  exception on disconnect, a missing-registry (`trade_set`) error from a recipe-viewer add-on, and "Illegal option value 0 for Anisotropic Filtering"
  (options.txt).

## Not covered

- Owner saves, multiplayer servers, Essential (it cannot run in dev), voice chat, the Axiom editor, WorldEdit CUI
  selections, Distant Horizons and Voxy (disabled in the instance).
- Fullscreen and higher render distance: the FPS numbers are only for comparing Metal with OpenGL in this scene.
