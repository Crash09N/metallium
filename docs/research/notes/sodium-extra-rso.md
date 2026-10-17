# Sodium Extra (26.3/dev, v0.9.4) + Reese's Sodium Options (26.3/dev) — source audit for Metallium

Refs: `sodium-extra` @ b0bbae5 ("change: Align glowing outlines with Panini projection"); `reeses-sodium-options` @ b6261b1 ("Bump 2.2.6-beta.1"); `sodium` @ 03198cb (mc26.3-0.9.2), `sodium` origin/dev (0.9.3-beta.1) consulted via `git grep`/`git show`; `metallum` @ 4da72f5 consulted for backend facts.
Paths below: `SE/` = `sodium-extra/common/src/main/java/me/flashyreese/mods/sodiumextra/`, `RSO/` = `reeses-sodium-options/common/src/main/java/me/flashyreese/mods/reeses_sodium_options/`, `SOD/` = `sodium/common/src/main/java/net/caffeinemc/mods/sodium/`.

Labels: **[V]** Verified in source, **[SI]** Strongly inferred, **[H]** Hypothesis, **[U]** Unverified.

---

## 0. Headline findings (decision-relevant)

1. **[V] Sodium Extra issues zero raw `org.lwjgl.opengl` calls.** Only GL-specific code: (a) `adaptive_sync/MixinGlSurface` mixes into `com.mojang.renderpearl.backend.opengl.GlSurface` (vanilla's OpenGL backend class) and wraps `SDL_GL_SetSwapInterval`; (b) `SodiumExtraGameOptions.VerticalSyncOption.isAdaptiveSyncSupported()` calls `SDL_GL_GetCurrentContext/GetSwapInterval/SetSwapInterval` **only if** `device.getDeviceInfo().backendName()` equals `"OpenGL"`, and checks `GpuSurface.PresentMode.FIFO_RELAXED` if `"Vulkan"`; anything else → `false` (`SE/client/config/SodiumExtraGameOptions.java:190-229`). Metallum reports `backendName = "Metal"` (`metallum/src/main/java/com/metallum/render/MetalDevice.java:246-251`) so Adaptive VSync is cleanly hidden, nothing crashes. Everything else goes through Blaze3D/"renderpearl" abstractions, vanilla render-state/submit APIs, or Sodium internals.
2. **[V] MC 26.3's GPU abstraction lives in `com.mojang.renderpearl.*`** (`api.device.GpuDevice`, `api.device.GpuSurface`, `api.device.GpuBackend#createWindow(String,int,int,long)`, `api.buffers.GpuBuffer/GpuBufferSlice`, `api.commands.RenderPass`, `api.pipeline.RenderPipeline`, `backend.opengl.GlSurface`) with `GpuSurface.PresentMode.{FIFO, FIFO_RELAXED, MAILBOX…}` and both "OpenGL" and "Vulkan" backend names recognised by Sodium Extra. Windowing is SDL3 (`org.lwjgl.sdl.SDLVideo`). (Inferred purely from mod imports/mixin targets — vanilla source not available.) [SI]
3. **[V] Sodium Extra depends on Sodium internals in 3 areas**: (a) fog-shape cull expansion mixins into `RenderSectionManager`, `OcclusionCuller`, `TraversableTree` (terrain renderer internals); (b) GLSL source patch of Sodium's terrain shader `sodium:blocks/block_layer_opaque` through vanilla `ShaderManager.Configs#getShader`; (c) config-UI internals (`client.config.ConfigManager`, `client.config.structure.{Config,IntegerOption,Option}`, `client.gui.options.TextProvider`, `client.gui.options.control.ControlValueFormatterImpls`, `client.services.PlatformRuntimeInformation`). Everything else option-related uses the public `net.caffeinemc.mods.sodium.api.config.*` API.
4. **[V] Under S1 (Sodium unchanged, Metal swapped under Blaze3D) essentially every feature works unchanged**, provided Metallum's backend (i) compiles GLSL arriving via `ShaderManager.Configs#getShader` (Sodium terrain + Sodium Extra's injected varyings at locations 4/5, and the Panini post shader `#version 330` + `GL_ARB_separate_shader_objects` with std140 UBOs), (ii) honours `USAGE_COPY_DST` uniform buffers + `CommandEncoder#writeToBuffer`, (iii) passes SDL window flags through (Metallum does: `SDL_WINDOW_METAL | flags`, `metallum/.../MetalBackend.java:33-35`). Only Adaptive VSync is lost (needs a native Metal analogue if wanted).
5. **[V] Under S2 (Metallum replaces Sodium terrain)** the at-risk features are: **fog shape (cylindrical/radial/planar)** — Sodium Extra *encodes* the shape as a +1,048,576/+2,097,152/+3,145,728 offset into vanilla `FogData.renderDistanceStart/End` and only decodes it for vanilla shaders (`MixinFogRenderer#updateBuffer` ModifyArgs). A non-Sodium terrain renderer reading FogData/Sodium `FogParameters` will see garbage distances (≈ 1–3 million blocks → effectively no terrain fog) unless it decodes (adapter required). The cull-expansion mixins become inert. Fog **distance/start/off**, protected gameplay fog, cloud fog are plain `FogData` edits and work if Metallum's terrain consumes vanilla FogData.
6. **[V] RSO** replaces Sodium's `VideoSettingsScreen` by mixin `@Inject(init, TAIL)` → `setScreen(new SodiumVideoOptionsScreen(prevScreen))` (`RSO/mixin/sodium/MixinSodiumOptionsGUI.java:27-34`), and renders **all** `ConfigManager.CONFIG.getModOptions()` pages (i.e. every mod that registers through `sodium:config_api_user`), so third-party options (Sodium Extra, Iris, Metallum's own Sodium page) appear. It is pure GUI (GuiGraphicsExtractor, scissor) — no GPU/GL code. **Gotchas:** Fabric metadata pins `"sodium": "=${sodium_version}"` with `SODIUM_VERSION = 0.9.3-beta.1+mc26.3` → at these refs RSO will **not** load alongside Sodium 0.9.2 (Sodium Extra builds vs 0.9.2). And Metallum's `SodiumPreferredGraphicsApiMixin` (redirect inside Sodium's `CyclingControl$CyclingControlElement`) will **not** apply under RSO, because RSO's `EnumOptionRow` calls `EnumOption#getElementName` directly.
7. **Recommendation:** S1 → preserve Sodium Extra and RSO unchanged; add (optional) a Metal-native adaptive-sync story and verify the two GLSL paths in Metallum's shader translator. S2 → keep Sodium loaded as "UI/config + API host" if at all possible; implement a decode adapter for the fog-shape encoding (or reimplement fog shapes natively), and re-target the cull-expansion behaviour into Metallum's own culler. Do not reimplement RSO; fix Metallum's graphics-API label to work at the `EnumOption` level (e.g. element name provider) so it shows under both UIs.

---

## 1. Build / packaging facts (Sodium Extra)

- [V] Versions: `sodium-extra/build.gradle.kts:9-17` → MC 26.3, NeoForge 26.3.0.1-beta, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, `GREENLIGHT_VERSION = "0.1.0+mc26.3"`, `MOD_VERSION = "0.9.4"`, `SODIUM_VERSION = "0.9.2+mc26.3"`. Java toolchain 25 (`build.gradle.kts` subprojects block), mixin `compatibilityLevel: JAVA_25`.
- [V] Common deps compileOnly: Sodium (`net.caffeinemc:sodium-fabric`), `me.flashyreese.mods:greenlight-api`, Fabric API modules `fabric-api-base`, `fabric-block-getter-api-v2`, `fabric-rendering-v1`, MixinExtras 0.5.5 (`common/build.gradle.kts:39-57`). Fabric module JiJ-includes `greenlight-api` (`fabric/build.gradle.kts` `include("me.flashyreese.mods:greenlight-api:…")`); NeoForge likewise.
- [V] Single mixin config, common-only: `common/src/main/resources/sodium-extra.mixins.json` (45 entries, `defaultRequire: 1`, plugin `SodiumExtraMixinConfigPlugin`). **No fabric- or neoforge-specific mixins exist.** Fabric declares it via `fabric.mod.json "mixins"`, NeoForge via `neoforge.mods.toml [[mixins]]`.
- [V] Access widener: only `mutable field net/minecraft/client/gui/components/debug/DebugScreenEntries PROFILES` (`common/src/main/resources/sodium-extra.accesswidener`), used by Fabric init (`fabric/.../SodiumExtraFabricClientModInitializer.java:19-34`) to add the light-updates warning to DEFAULT/PERFORMANCE debug profiles. NeoForge uses `RegisterDebugEntriesEvent#includeInProfile` instead (`neoforge/.../SodiumExtraNeoForgeClientMod.java:19-25`).
- [V] Fabric metadata (`fabric/src/main/resources/fabric.mod.json`): `depends sodium >=0.9.2+mc26.3`, `breaks iris <1.7.0`, `breaks reeses-sodium-options <1.8.4`, `suggests greenlight >=0.1.0`, `suggests reeses-sodium-options >=2.2.0`; entrypoints `client` → `SodiumExtraFabricClientModInitializer`, `sodium:config_api_user` → `SodiumExtraConfig`; `license: LGPLv3`. NeoForge: modId `sodium_extra`, greenlight optional, `modproperties "sodium:config_api_user"`.
- [V] Doc discrepancy: README badge/build section says Java 17 (`README.md:2`, "Java Development Kit 17 or above"), build actually requires Java 25.

### 1.1 Mixin gating (CaffeineConfig)
- [V] `SE/client/SodiumExtraClientMod.java:43-77` builds `CaffeineConfig` "Sodium Extra" with settings key `sodium-extra:options`, file `<config>/sodium-extra.properties`; options (all default true): `core`(not user-overridable), `adaptive_sync, animation, biome_colors, cloud, compat`(not overridable)`, fog, fps, gui, instant_sneak, light_updates, optimizations, panini_projection, particle, prevent_shaders, reduce_resolution_on_mac, render, render.block, render.block.entity, render.entity, sky, sky_colors, stars, steady_debug_hud, sun_moon, toasts`.
- [V] Vestigial options with **no mixin package**: `core`, `compat`, `optimizations`, `render.block` (only `render.block.entity` has classes), `stars`, `sun_moon` (stars/sun/moon are in `sky/MixinSkyRenderer`). Not harmful.
- [V] Mixins are disabled by package prefix (`net/caffeinemc/caffeineconfig/AbstractCaffeineConfigMixinPlugin.java:33-63`). Other Fabric mods can force-disable via `custom."sodium-extra:options"` boolean map in their `fabric.mod.json` (`fabric/src/main/java/net/caffeinemc/caffeineconfig/CaffeineConfigFabric.java:12-33`). **→ Metallum can declare e.g. `"mixin.adaptive_sync": false` or `"mixin.fog": false` in its fabric.mod.json for S2 without touching Sodium Extra.** [V mechanism; usage SI]
- [V] Every UI option calls `.setEnabled(mixinConfig().getOptions().get("mixin.X").isEnabled())` so a disabled package greys out its controls (e.g. `SodiumExtraConfig.java:240,306,382,437,448,462,668,677,702,761,806,839,880,888,898,919`).

### 1.2 Greenlight (GREENLIGHT_VERSION)
- [V] Used only in `SE/client/fog/FogDistanceHelper.java:4-5,47-50`: `Greenlight.feature(Identifier "sodium-extra:protected_gameplay_fog").decoder(1, ProtectedGameplayFogPolicy::fromJson).register()` → `ClientFeature<ProtectedGameplayFogPolicy>`; `PROTECTED_GAMEPLAY_FOG.policy()` (Optional) consulted in `shouldModifyProtectedGameplayFog()` (`:321-326`) and `getProtectedGameplayFogDistance()` (`:328-342`).
- [SI from docs + JSON schema in code] Greenlight is a FlashyReese client mod/API that grants "client features" from **server-provided resource packs** at `assets/<ns>/client_features/v1/<feature>.json` (`docs/server-policies.md:1-30`, example `docs/examples/protected_gameplay_fog.json`). Policy JSON per type: `{enabled, max_distance_blocks (0..256), allow_off}` (`FogDistanceHelper.java:408-446`). Without Greenlight runtime (API is JiJ'd, but the mod itself is optional) the policy is absent → protected fog stays vanilla on multiplayer ("fails closed"). Local/singleplayer always allowed; LAN-opened integrated server allowed only if `IntegratedServer#getGuestCommandAccess()` (`:380-392`, accessors `mixin/fog/AccessorMinecraft`, `AccessorIntegratedServer`).
- [V] Greenlight has **no rendering involvement**; irrelevant to Metal. Greenlight jar not present locally; its internals [U].

### 1.3 Iris / RSO dependence
- [V] Iris: reflection only, `SE/compat/IrisCompat.java:18-34` (`net.irisshaders.iris.api.v0.IrisApi#isShaderPackInUse/isRenderingShadowPass`, `IrisVertexFormats.TERRAIN`). Only `isShaderPackInUse()` is actually called — by Panini (`SE/client/render/PaniniProjection.java:70`). `isRenderingShadowPass`/`getTerrainFormat` are dead code. [V]
- [V] RSO: no code dependency; only metadata (`breaks <1.8.4`, `suggests >=2.2.0`).
- [V] Dead code: `SE/client/render/vertex/formats/{TextureVertex,TextureColorVertex,WeatherVertex}.java` (use Sodium API `api.vertex.attributes.*`, `api.math.MatrixHelper`) and `SE/common/util/Utils.java` — no references from any mixin. Leftovers from older custom weather/sky renderers.

---

## 2. Complete config option inventory (Sodium Extra)

Storage: `<config>/sodium-extra-options.json` (`SodiumExtraConfigKeys.FILE_NAME`), Gson LOWER_CASE_WITH_UNDERSCORES, atomic write (`ConfigFileIO`), corrupt file moved aside (`SodiumExtraGameOptions.java:50-131`). `SodiumExtraGameOptions implements api.config.StorageEventHandler` → `afterSave()` writes file.
Registration: `SodiumExtraConfig implements api.config.ConfigEntryPoint#registerConfigLate` (`SodiumExtraConfig.java:990-1000`): `registerOwnModOptions().setIcon(...)` + 5 pages + `registerOptionReplacement(sodium:general.vsync, …)`.

Page **Animations** (`createAnimationsPage`, `:230-293`), all Boolean, default true, gated `mixin.animation`:
| Option id | Field | Line |
|---|---|---|
| sodium-extra:animations_all | animationSettings.animation | 234 |
| animate_water | .water | 244 |
| animate_lava | .lava | 252 |
| animate_fire | .fire | 260 |
| animate_portal | .portal | 268 |
| block_animations | .blockAnimations | 276 |
| animate_sculk_sensor | .sculkSensor | 284 |

Page **Particles** (`:295-366`), gated `mixin.particle`: `particles_all` (300), `rain_splash_particles` (311), `block_break_particles` (319), `block_breaking_particles` (327), plus **one Boolean per registered particle type** (all namespaces, grouped per namespace, `BuiltInRegistries.PARTICLE_TYPE.keySet()`), id `particle.<options.particles.ns.path>` → `particleSettings.otherMap` (`:337-363`). Modded particles therefore show up automatically.

Page **Details** (`:368-451`): `sky` (373), `stars` (384), `sun` (395), `moon` (406) gated `mixin.sky`; `rain_snow` (417) gated **`mixin.particle`** (weather mixin lives in the particle package); `biome_colors` (428) gated `mixin.biome_colors`; `sky_colors` (439) gated `mixin.sky_colors`.

Page **Render** (`createRenderPage`, `:453-753`):
- Fog (all gated `mixin.fog`): `advanced_fog_settings` (461, default false); global `single_fog` int range `[-1 .. max(render distance options, Sodium render_distance validator max)]` with `-1=Off, 0=Vanilla` (475-482, range provider depends on `sodium:general.render_distance` with `ConfigState.UPDATE_ON_REBUILD`); `fog_start` 0–100 % (484); `fog_shape` enum VANILLA/CYLINDRICAL/RADIAL/PLANAR (490, additionally gated `FogShaderTransformer.isShapeSupported()`); `cloud_fog` 0–100 % (495); `multi_dimension_fog` (504); per-dimension `fog.<dim>`, `fog_start.<dim>`, `fog_shape.<dim>`, `cloud_fog.<dim>` (522-555) for overworld/nether/end + current level's dimension type + any saved overrides; `protected_gameplay_fog` (562) + `protected_gameplay_fog.{blindness,darkness,lava,powder_snow,water}` int −1..256 blocks (574-657).
- `light_updates` (667, gated `mixin.light_updates`).
- Entities (gated `mixin.render.entity`): `item_frame` (676), `armor_stands` (684), `paintings` (692), `item_frame_name_tag` (734), `player_name_tag` (742).
- Block entities (gated `mixin.render.block.entity`): `beacon_beam` (701), `limit_beacon_beam_height` (709, default false), `enchanting_table_book` (717), `piston` (725).

Page **Extras** (`createExtraPage`, `:755-936`):
- `reduce_resolution_on_mac` (760; enabled only if mixin enabled **and** `os.name` contains "mac"; impact HIGH).
- `overlay_corner` enum (770), `text_contrast` enum NONE/BACKGROUND/SHADOW (777), `show_fps` (784), `show_fps_extended` (791), `show_coordinates` (798).
- `cloud_height_override` (805, gated `mixin.cloud`), `cloud_height` int −64..319 (813-820).
- `advanced_item_tooltips` (825) → binds vanilla `Options.advancedItemTooltips`, storage `options.save()`.
- `more_hud_overlays` external button (832) → `setScreenAndShow(new DebugOptionsScreen())`.
- Toasts (gated `mixin.toasts`): `toasts`, `advancement_toast`, `recipe_toast`, `system_toast`, `tutorial_toast` (838-876).
- `instant_sneak` (879, gated `mixin.instant_sneak`).
- `prevent_shaders` (887, gated `mixin.prevent_shaders`, flag `OptionFlag.REQUIRES_RENDERER_RELOAD`).
- `panini_projection` (897, impact MEDIUM, gated `mixin.panini_projection`), `panini_projection_strength` 0–100 % (906).
- `steady_debug_hud` (918), `steady_debug_hud_refresh_interval` 1–20 ticks (926).

**Option replacement** `sodium:general.vsync` (`createVerticalSyncOption`, `:938-988`): replaces Sodium's boolean VSync (`SOD/client/gui/SodiumConfigBuilder.java:277`) by enum OFF/ON/ADAPTIVE; ADAPTIVE allowed only if `mixin.adaptive_sync` && `isAdaptiveSyncSupported()`. Binding sets `extraSettings.useAdaptiveSync` + vanilla `enableVsync`, then `Minecraft#invalidateSurfaceConfiguration()`.

Config-class fields without a UI option [V]: `extraSettings.useAdaptiveSync` (set via the vsync replacement), `fogSettings.dimensionOverrides[*].distanceChunks` etc. (via per-dim UI). All `SodiumExtraGameOptions` fields have UI.

Features NOT present in this version (asked about) [V by exhaustive file list]: reduced light flicker, entity/block-entity culling-distance options, render-distance extension, "light sky"/void-fog options, separate stars/sun_moon mixins, cloud *distance* (only cloud height + cloud fog %), FPS limiter, fullscreen/macOS menu options other than reduce-resolution.

---

## 3. Mixin inventory (all 45, `sodium-extra.mixins.json`)

Target classification: **MC** = Minecraft/Blaze3D/renderpearl class; **MC-GL** = vanilla OpenGL backend class; **SOD-int** = Sodium internal (non-API) class.

| Mixin (SE/mixin/…) | Target | Kind | Injection (line) | Feature |
|---|---|---|---|---|
| adaptive_sync/MixinGlSurface | `com.mojang.renderpearl.backend.opengl.GlSurface` | MC-GL | `@Inject supportedPresentModes RETURN` add FIFO_RELAXED (26); `@WrapOperation configure → SDLVideo.SDL_GL_SetSwapInterval` use −1, fallback 1 (37) | Adaptive VSync |
| animation/MixinSpriteContentsAnimationState | `SpriteContents.AnimationState` | MC | interface impl, stores sprite | Animations |
| animation/MixinTextureAtlas | `TextureAtlas` | MC | `@WrapWithCondition cycleAnimationFrames → AnimationState#tick` (84); `@WrapOperation upload → TextureAtlasSprite#createAnimationState(GpuBufferSlice,int)` (89) | Animations |
| biome_colors/MixinBiomeColors | `BiomeColors` | MC | `getAverageGrass/Water/FoliageColor` RETURN (12,19,26) | Biome colors |
| cloud/MixinLevelRenderer | `LevelRenderer` | MC | `@WrapOperation prepareTranslucents → CloudRenderer#prepare(I,CloudStatus,F,I,Vec3,J,F)` replace height `cloudHeight+0.33` (15) | Cloud height |
| fog/AccessorIntegratedServer | `IntegratedServer` | MC | `@Invoker getGuestCommandAccess` | Protected fog permission |
| fog/AccessorMinecraft | `Minecraft` | MC | `@Accessor singleplayerServer` | ditto |
| fog/MixinAtmosphericFogEnvironment | `AtmosphericFogEnvironment` | MC | `setupFog TAIL` env start/end, skyEnd, cloudEnd (17) | Fog distance/start/off, cloud fog |
| fog/MixinBlindness/Darkness/Lava/PowderedSnow/WaterFogEnvironment | `*FogEnvironment` | MC | `setupFog TAIL` (16) → `applyProtectedGameplayFog` | Protected gameplay fog |
| fog/MixinFogRenderer (priority 1300) | `FogRenderer` | MC | `setupFog HEAD` reset (30); `@WrapOperation FogEnvironment#setupFog` capture atmospheric (35); `@Inject PUTFIELD FogData.renderDistanceEnd AFTER` set render-distance fog start/end + shape offset (41-68); `@ModifyArgs updateBuffer(FogData) → updateBuffer(ByteBuffer,I,Vector4f,FFFFFF)` decode args 5/6 (70-82) | Terrain fog distance/start/shape |
| fog/MixinShaderManager | `ShaderManager.Configs` | MC | `@ModifyReturnValue getShader` → if id == `sodium:blocks/block_layer_opaque` patch GLSL (`FogShaderTransformer`) (`MixinShaderManager.java:17-24`) | Fog shape (shader patch of a **Sodium** shader) |
| fog/MixinRenderSectionManager | `SOD …render.chunk.RenderSectionManager` | SOD-int | `@ModifyReturnValue getEffectiveRenderDistance / getSearchDistanceForCullType / getSearchDistance` (19,25,31) | Cylindrical fog cull expansion |
| fog/MixinOcclusionCuller | `SOD …render.chunk.occlusion.OcclusionCuller` | SOD-int | `@ModifyArgs processQueue → visitNeighbors(...)` add up/down directions (30-47); `@Inject testDistance HEAD` (70) | ditto |
| fog/MixinTraversableTree | `SOD …render.chunk.tree.TraversableTree` | SOD-int | `@Inject cylindricalDistanceTest HEAD` (12) | ditto |
| fps/MixinGameRenderer | `GameRenderer` | MC | `render HEAD` → `FrameCounter#onFrame` (12) | FPS overlay |
| gui/MixinDebugOptionsScreen | `DebugOptionsScreen$OptionEntry` | MC | `extractContent HEAD` custom label for `sodium-extra:` entries (38) | Debug-screen entries UI |
| gui/MixinGui | `Gui` | MC | `extractRenderState TAIL` → `SodiumExtraHud#onHudRender` (15) | FPS/coords overlay |
| gui/MixinMinecraftClient | `Minecraft` | MC | `runTick HEAD` → HUD text rebuild (12) | FPS/coords overlay |
| instant_sneak/MixinCamera | `Camera` | MC | `tick HEAD` set `eyeHeight = entity.getEyeHeight()` (21) | Instant sneak |
| light_updates/MixinLevelLightEngine | `LevelLightEngine` | MC | `checkBlock HEAD cancel` (14); `runLightUpdates RETURN → 0` (20) | Light updates toggle |
| panini_projection/AccessorLevelRenderer | `LevelRenderer` | MC | `entityOutlineTarget`, `currentFrameRendersEntityOutline` | Panini |
| panini_projection/AccessorPostChain | `PostChain` | MC | `passes` | Panini |
| panini_projection/AccessorPostPass | `PostPass` | MC | `customUniforms Map<String,GpuBuffer>` | Panini |
| panini_projection/MixinGameRenderer | `GameRenderer` | MC | `render3dHud HEAD` → `PaniniProjection.process` (38) | Panini |
| particle/MixinClientLevel | `ClientLevel` | MC | `addDestroyBlockEffect`/`addBreakingParticles HEAD` (17,24); `@WrapWithCondition tickWeatherEffects → addParticle` (31) | Particles |
| particle/MixinFireworkParticle | `FireworkParticles.Starter` | MC | `createParticle HEAD` (18) | Particles (firework) |
| particle/MixinLevelRenderer | `WeatherEffectRenderer` | MC | `render(WeatherRenderState, RenderPass, RenderPipeline) HEAD cancel` (12) | Rain/snow toggle |
| particle/MixinParticleEngine | `ParticleEngine` | MC | `createParticle HEAD → null` (16) | Per-type particles |
| prevent_shaders/MixinGameRenderer | `GameRenderer` | MC | `toggleSpectatorPostEffect`, `setSpectatedEntityPostEffect HEAD cancel` (14,21) | Prevent (vanilla spectator/entity) post shaders |
| reduce_resolution_on_mac/MixinWindow | `com.mojang.blaze3d.platform.Window` | MC | `@ModifyArg createWindow → GpuBackend#createWindow(String,I,I,J) index 3` clear `SDL_WINDOW_HIGH_PIXEL_DENSITY` if `Util.getPlatform()==OSX` (22-29) | macOS reduce resolution |
| render/block/entity/MixinBeaconRenderer (prio 999) | `BeaconRenderer` | MC | `submit HEAD cancel` (27); `@WrapOperation submitBeaconBeam` clamp maxY (35) | Beacon beam / height limit |
| render/block/entity/MixinEnchantingTableBlockEntityRenderer | `EnchantTableRenderer` | MC | `submit HEAD` (16) | Enchanting book |
| render/block/entity/MixinPistonBlockEntityRenderer | `PistonHeadRenderer` | MC | `submit HEAD` (16) | Moving pistons |
| render/entity/MixinItemFrameEntityRenderer | `ItemFrameRenderer` | MC | `submit` after super call (19); `shouldShowName HEAD` (26) | Item frames / name tag |
| render/entity/MixinLivingEntityRenderer | `LivingEntityRenderer` | MC | `submit HEAD` armor stands (29); `shouldShowName` players (39) | Armor stands / player name tags |
| render/entity/MixinPaintingEntityRenderer | `PaintingRenderer` | MC | `submit HEAD` (16) | Paintings |
| sky/MixinSkyRenderer | `SkyRenderer` | MC | `renderSkyDisc`, `renderEndSky`, `renderSun`, `renderMoon`, `renderStars`, `renderSunriseAndSunset` HEAD cancel (12-55) | Sky/sun/moon/stars |
| sky_colors/MixinAtmosphericFogEnvironment | `AtmosphericFogEnvironment` | MC | `@ModifyArg getBaseColor → applyWeatherDarken(Vector3fc,F,F) index 0` → constant 0x78A7FF (13) | Sky colors |
| steady_debug_hud/MixinDebugScreenOverlay | `DebugScreenOverlay` | MC | `extractRenderState HEAD` (29), `@WrapOperation extractLines` ordinal 0/1 caching (44,53) | Steady debug HUD |
| toasts/MixinToastInstance | `ToastManager$ToastInstance` | MC | `update HEAD` (21) | Toasts |
| toasts/MixinToastManager | `ToastManager` | MC | `addToast HEAD` (14); `lambda$update$1 HEAD` (21) — synthetic lambda name, fragile | Toasts |

[V] All three SOD-int targets/methods exist at 0.9.2 (`SOD/client/render/chunk/RenderSectionManager.java:356,364,1013,1026`; `…/occlusion/OcclusionCuller.java:39-40,275,371,464`; `…/tree/TraversableTree.java:284`) and on Sodium origin/dev (0.9.3-beta.1) (`git grep`). Because `defaultRequire: 1`, if Sodium removed/renamed these, the game **crashes at mixin apply** (not silent) — relevant if Metallum ever stubs/replaces Sodium classes.

---

## 4. Feature deep-dives relevant to Metal

### 4.1 Fog (distance / start / off / shape / cloud fog / per-dimension / protected)
Data path [V]:
1. Vanilla `FogRenderer#setupFog` → per-environment `FogEnvironment#setupFog(FogData,…)`. Sodium Extra edits `FogData.environmentalStart/End, skyEnd, cloudEnd` at TAIL of `AtmosphericFogEnvironment#setupFog` (`fog/MixinAtmosphericFogEnvironment.java:17-44`) and protected env types (blindness etc.).
2. After vanilla writes `FogData.renderDistanceEnd`, `MixinFogRenderer#postFogSetup` overrides `renderDistanceStart/End` (`MixinFogRenderer.java:41-68`); for shapes ≠ VANILLA adds an offset band (`FogDistanceHelper.applyRenderDistanceShape`, `:201-219`; offsets `:40-43`). Boss fog (`BossOverlay#shouldCreateWorldFog`) suppresses changes (`:316-319`).
3. Sodium captures **encoded** values into `FogParameters` at `FogRenderer#setupFog RETURN` (`SOD/mixin/core/render/world/FogRendererMixin.java:28-44`) → terrain UBO (`SOD/client/render/chunk/UniformBufferManager.java:118`) → patched terrain shader decodes (`FogShaderTransformer.java:26-54`).
4. Vanilla shaders get **decoded** values via `@ModifyArgs` on `FogRenderer#updateBuffer` (`MixinFogRenderer.java:70-82`).
5. Shader patch: string replace on GLSL anchors `#include <sodium:fog.glsl>`, `layout(location = 1) out/in vec2 v_TexCoord;`, `gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);`, `return _linearFog(` (`FogShaderTransformer.java:14-23,72-98`); adds varyings `layout(location=4) v_PlanarDistance`, `layout(location=5) v_SodiumExtraCylindricalDistance`. [V] anchors present in Sodium 0.9.2 (`sodium/common/src/main/resources/assets/sodium/shaders/blocks/block_layer_opaque.{vsh:5,9,62; fsh:4,9,33}`) and origin/dev; Sodium uses locations 0–3 only, so 4/5 are free. On anchor drift → `shapeSupported=false`, UI hides shape option, Java stops encoding (graceful).
6. Sodium 0.9.2 builds its terrain pipeline via vanilla `RenderPipeline.builder().withVertexShader(Identifier "sodium:blocks/block_layer_opaque")…withPushConstantSize(...)` (`SOD/client/render/chunk/ShaderChunkRenderer.java:66-98`) → the source is resolved by vanilla `ShaderManager` → Sodium Extra's `getShader` hook applies → backend compiles. [V for Sodium side; SI that Metallum's backend receives the post-hook source, because the hook is on the source resolver, not on GL compile.]
7. Cylindrical cull expansion: `RenderSectionManager` distance getters return `ceil(max(rd, rd*16)) + 0.75` marker; `OcclusionCuller#testDistance` and `TraversableTree#cylindricalDistanceTest` recognise the marker by raw float bits and test horizontal ≤ rd, |vertical| ≤ 16·rd (`FogDistanceHelper.java:229-265,307-314`).
- [H] Side effect: for RADIAL/PLANAR shapes Sodium's `FogParameters.cullDistance = min(renderEnd(encoded ≈1M+), environmentalEnd)` (`SOD/client/util/FogParameters.java:8-10`) so fog-occlusion culling falls back to environmentalEnd; not a Metal issue.
- Iris: when a shader pack is active Sodium's `block_layer_opaque` isn't used; whether Iris consumes encoded `renderDistanceStart/End` (e.g. via Sodium FogParameters or vanilla fog UBO) [U] — see open questions.

GL/Metal: no raw GL. Metal impact = the GLSL patch must survive Metallum's GLSL→MSL translation (plain GLSL 3.3-style varyings — [SI] fine with glslang/SPIRV-Cross).

### 4.2 Adaptive VSync
[V] UI: `SodiumExtraGameOptions.java:173-239`, `SodiumExtraConfig.java:938-988`. Runtime: `MixinGlSurface` (GL backend only). For Vulkan backend the code merely checks `FIFO_RELAXED` in `minecraft.windowSurface().supportedPresentModes()` — i.e. vanilla's Vulkan backend natively supports FIFO_RELAXED and Sodium Extra only needs to set `enableVsync` + `useAdaptiveSync`; how `useAdaptiveSync` maps to `PresentMode.FIFO_RELAXED` on Vulkan is not in Sodium Extra code (only GlSurface is mixed) → [H] vanilla picks FIFO_RELAXED itself when supported? Not determinable without MC source [U].
Metal: Metallum `MetalSurface.SUPPORTED_PRESENT_MODES = {FIFO, MAILBOX}`, `displaySyncEnabled = presentMode != MAILBOX` (`metallum/.../MetalSurface.java:26,61`). With backendName "Metal", ADAPTIVE is filtered out; harmless. A native analogue (CAMetalLayer `displaySyncEnabled` + ProMotion/VRR, or `presentAfterMinimumDuration`) would require either Metallum reporting FIFO_RELAXED and Sodium Extra's check recognising "Metal" (adapter mixin into `VerticalSyncOption.isAdaptiveSyncSupported`) — low value.

### 4.3 Reduce resolution on macOS
[V] Clears `SDL_WINDOW_HIGH_PIXEL_DENSITY` in the flags passed to `GpuBackend#createWindow` (`reduce_resolution_on_mac/MixinWindow.java:22-29`), only when `Util.getPlatform()==OSX`. Metallum's `MetalBackend#createWindow` ORs incoming flags with `SDL_WINDOW_METAL` (`MetalBackend.java:33-35`) → flag removal is honoured; drawable size is set from `GpuSurface.Configuration.width/height` (`MetalSurface.java:52-58`). [SI] Works unchanged on Metal in both scenarios (window is vanilla-owned). Note option takes effect only at window creation (needs restart). Attribution comment: approach from RetiNo (MIT) via Canvas (Apache-2.0) (`MixinWindow.java:11-19`).

### 4.4 Animations
[V] Gates `SpriteContents.AnimationState#tick` inside `TextureAtlas#cycleAnimationFrames` per sprite name list (water/lava/portal/fire/"block animations"/sculk) (`MixinTextureAtlas.java:28-81,84-87`), and tags each animation state with its sprite in `TextureAtlas#upload` via `createAnimationState(GpuBufferSlice,int)` (`:89-94`). CPU-side, before any GPU upload; the actual atlas update is vanilla's (renderpearl buffers/commands). [SI] Unchanged on Metal (S1 & S2) as long as the atlas animation upload path is vanilla's; in S2 if Metallum has its own atlas animation upload (e.g. compute), it must still call vanilla `cycleAnimationFrames`/`AnimationState.tick` or the toggles are ignored.

### 4.5 Sky / sun / moon / stars / sunrise / end sky; sky colors; biome colors
[V] Cancel vanilla `SkyRenderer` draw methods (`sky/MixinSkyRenderer.java`). Sky color: replaces input to `applyWeatherDarken` with constant (`sky_colors/…:13-19`). Biome colors: returns constants from `BiomeColors#getAverage*Color` (`biome_colors/…`). Biome colors affect **Sodium chunk meshing** (Sodium calls vanilla/Fabric color providers) [SI] → in S2 Metallum's mesher must use `BiomeColors`/`BlockColors` (vanilla) for this to apply; changing it requires chunk rebuild (no `OptionFlag.REQUIRES_RENDERER_RELOAD` set on biome_colors → [V] Sodium Extra doesn't request a reload; existing chunks keep old colours until rebuilt).

### 4.6 Clouds
[V] Height override wraps `CloudRenderer#prepare(...)` argument (`cloud/MixinLevelRenderer.java:15-21`); cloud fog % edits `FogData.cloudEnd` (`fog/MixinAtmosphericFogEnvironment.java:46-51`). Both vanilla-level; Metal-agnostic. Works in S1/S2 if Metallum keeps vanilla `CloudRenderer`.

### 4.7 Weather (rain/snow) & particles
[V] Weather: cancels `WeatherEffectRenderer#render(WeatherRenderState, RenderPass, RenderPipeline)` (renderpearl RenderPass → backend-agnostic). Particles: cancels creation in `ParticleEngine#createParticle`, `ClientLevel#addDestroyBlockEffect/addBreakingParticles`, rain splash in `ClientLevel#tickWeatherEffects`, firework starter. All CPU-side; Metal-agnostic.

### 4.8 Entities / block entities
[V] Cancels `submit(...)` of vanilla renderers (`SubmitNodeCollector` API) and name-tag decisions. Metal-agnostic; works as long as entity/BE rendering still goes through vanilla renderers (true in both S1 and S2 unless Metallum replaces entity rendering — not planned per task).

### 4.9 Panini projection
[V] `PaniniProjection.process` at `GameRenderer#render3dHud HEAD` (before hand): gets `ShaderManager#getPostChain("sodium-extra:panini", LevelTargetBundle.MAIN_TARGETS)`, finds `PaniniConfig` custom uniform `GpuBuffer` in a `PostPass`, replaces it with a `USAGE_UNIFORM|USAGE_COPY_DST` buffer if needed (`RenderSystem.getDevice().createBuffer`), writes std140 vec4 via `createCommandEncoder().writeToBuffer(slice, …)`, runs `postChain.process(mainTarget, pool)` and again on `entityOutlineTarget` if outlines this frame (`PaniniProjection.java:34-148`). Disabled when `preventShaders`, scoping, panoramic, frustum captured, or **Iris shader pack in use** (`:59-71`). Post shader: `assets/sodium-extra/shaders/post/panini.fsh` (`#version 330`, `#extension GL_ARB_separate_shader_objects : require`, std140 `SamplerInfo`, `PaniniConfig`, `sampler2D InSampler`) + vanilla `minecraft:core/screenquad` / `minecraft:post/blit`; chain JSON `assets/sodium-extra/post_effect/panini.json`.
Metal: no raw GL; requires Metallum to (a) compile mod-supplied GLSL post shaders (same path as vanilla post effects — [SI] already needed for vanilla), (b) support `writeToBuffer` on uniform buffers and outline target reprocessing. Works in S1 and S2.

### 4.10 Prevent shaders
[V] Cancels vanilla `GameRenderer#toggleSpectatorPostEffect` / `setSpectatedEntityPostEffect` (creeper/spider/enderman post effects). Not related to Iris. Metal-agnostic. (Flagged `REQUIRES_RENDERER_RELOAD`.)

### 4.11 HUD: FPS/coords overlay, debug entries, steady debug HUD, toasts, instant sneak, light updates
[V] All vanilla GUI (`GuiGraphicsExtractor#text/fill`), vanilla `DebugScreenEntry` registration, CPU. `FrameCounter` hooks `GameRenderer#render HEAD`. Instant sneak edits `Camera.eyeHeight`. Light updates cancels client light engine work (`LevelLightEngine#checkBlock`/`runLightUpdates`) — **changes chunk light data that Sodium/Metallum meshers read**; warning debug entry `SodiumExtraDebugEntryLightUpdates`. Metal-agnostic.

---

## 5. Required table (S1 = Metallum keeps Sodium, swaps Blaze3D backend to Metal; S2 = Metallum replaces Sodium's terrain renderer)

Legend in cells: "S1 / S2". "Raw GL?" column semantics folded into Dependencies.

| Feature | Original implementation | Dependencies | Works unchanged? | Adapter needed? | Native implementation needed? | Recommended strategy |
|---|---|---|---|---|---|---|
| Option pages / config storage | `SodiumExtraConfig` (`ConfigEntryPoint#registerConfigLate`, `:990-1000`), `SodiumExtraGameOptions` JSON | Sodium public config API `api.config.*`; Sodium internals `client.config.ConfigManager/structure.*` (`FogDistanceHelper.java:13-16,123-170`), `gui.options.TextProvider`, `ControlValueFormatterImpls`, `services.PlatformRuntimeInformation`; no GPU | Yes / Yes if Sodium still loaded; **No** if Sodium absent (hard dep `sodium >=0.9.2`) | No / No (Sodium loaded) | No | Keep Sodium loaded as config/UI host in both scenarios |
| VSync enum replacement + Adaptive VSync | `SodiumExtraConfig.java:938-988`; `VerticalSyncOption.isAdaptiveSyncSupported` (`SodiumExtraGameOptions.java:190-229`); `adaptive_sync/MixinGlSurface` | **MC-GL class `renderpearl.backend.opengl.GlSurface`**, SDL3 `SDL_GL_*` (only when backendName=="OpenGL"); Vulkan path via `GpuSurface.PresentMode.FIFO_RELAXED`; Sodium option id `sodium:general.vsync` | OFF/ON: Yes / Yes. ADAPTIVE: hidden on Metal (backendName "Metal" → false) — degrades gracefully, no crash | Optional: mixin making `isAdaptiveSyncSupported` true for "Metal" + Metallum surface supporting FIFO_RELAXED | Optional: Metal analogue (CAMetalLayer displaySync + VRR) | Accept loss initially; optionally add Metallum-side FIFO_RELAXED + small compat mixin later. Can disable `mixin.adaptive_sync` via Metallum fabric.mod.json custom value |
| Reduce resolution on macOS | `reduce_resolution_on_mac/MixinWindow` clears `SDL_WINDOW_HIGH_PIXEL_DENSITY` arg of `GpuBackend#createWindow` | vanilla `Window`, SDL3; OS check `Util.getPlatform()==OSX` + UI check `os.name` contains "mac" | Yes / Yes (Metallum ORs flags, `MetalBackend.java:33-35`) | No | No | Preserve; verify Metal drawable/contentsScale at 1× on Retina [H: SDL_Metal_CreateView derives scale from flag] |
| Fog distance / start % / Off (atmospheric), per-dimension | `fog/MixinAtmosphericFogEnvironment`, `fog/MixinFogRenderer` (`:41-68,70-82`), `FogDistanceHelper` | vanilla `FogData`/`FogRenderer`/fog UBO; Sodium reads FogData via its own `FogRendererMixin` | Yes / Yes **if** Metallum terrain consumes vanilla `FogData` (or Sodium `FogParameters`) | No / No (for VANILLA shape); see shape row | No | Preserve; in S2, source terrain fog from `FogData`/`FogStorage` like Sodium does |
| Fog shape (cylindrical/radial/planar) | Offset-band encoding in `FogData.renderDistanceStart/End` (`FogDistanceHelper.java:201-227`), GLSL patch of `sodium:blocks/block_layer_opaque` (`MixinShaderManager`, `FogShaderTransformer`), decode for vanilla in `updateBuffer` | **Sodium terrain shader source + anchors**, vanilla `ShaderManager.Configs#getShader`; GLSL varyings loc 4/5 | S1: Yes, provided Metallum compiles the post-hook GLSL (no raw GL). S2: **No** — Metallum terrain would read encoded ≈1–3M-block distances (no terrain fog) and patch never applies | S2: **Yes** — Metallum must decode the bands (`renderStart - offset`) and implement shape math; or force-disable shapes (`FogShaderTransformer.isShapeSupported` false ⇒ requires the transform to observe drift; alternative: Metallum mixin) | S2: Yes (shape math in Metallum's MSL terrain shader) | S1 preserve. S2: implement band-decoding + cylindrical/planar/radial distance in Metallum terrain shader (spec in `FogShaderTransformer.java:26-54`), mirroring Sodium's contract |
| Cylindrical fog cull expansion | `fog/MixinRenderSectionManager`, `MixinOcclusionCuller`, `MixinTraversableTree` | **Sodium internals** (non-API) | S1: Yes. S2: inert (Sodium culler not run) — mixins still apply if Sodium loaded; crash if Sodium classes absent (`defaultRequire 1`) | S2: Yes — Metallum culler must honour `FogDistanceHelper.isExpandedCylindricalCullDistance`/`testExpandedCylindricalCullDistance` or equivalent | S2: Yes (in Metallum culler) | S2: re-implement in Metallum's section culler (expand vertical limit 16× when cylindrical) |
| Cloud fog % | `fog/MixinAtmosphericFogEnvironment#applyCloudFog` → `FogData.cloudEnd` | vanilla | Yes / Yes | No | No | Preserve |
| Protected gameplay fog (blindness/darkness/lava/powder snow/water) | `fog/Mixin*FogEnvironment` TAIL → `FogDistanceHelper.applyProtectedGameplayFog`; Greenlight policy | vanilla FogEnvironment; **Greenlight API** (JiJ, runtime mod optional) | Yes / Yes | No | No | Preserve; Greenlight irrelevant to Metal |
| Cloud height override | `cloud/MixinLevelRenderer` wraps `CloudRenderer#prepare` | vanilla `CloudRenderer` | Yes / Yes (if vanilla clouds kept) | No | No | Preserve |
| Sky / sun / moon / stars / sunrise / end sky toggles | `sky/MixinSkyRenderer` HEAD cancels | vanilla `SkyRenderer` | Yes / Yes | No | No | Preserve |
| Sky colors | `sky_colors/MixinAtmosphericFogEnvironment` ModifyArg | vanilla | Yes / Yes | No | No | Preserve |
| Biome colors | `biome_colors/MixinBiomeColors` RETURN constants | vanilla `BiomeColors` (used by Sodium mesher via color providers) | Yes / Yes if Metallum mesher uses vanilla `BlockColors`/`BiomeColors` | S2: only if mesher bypasses vanilla color providers | No | Preserve; S2 mesher must use vanilla tint providers (also needed for Fabric compat) |
| Rain/snow toggle | `particle/MixinLevelRenderer` cancels `WeatherEffectRenderer#render(…, RenderPass, RenderPipeline)` | vanilla, renderpearl RenderPass | Yes / Yes | No | No | Preserve |
| Particles (all, rain splash, block break/breaking, per-type, firework) | `particle/MixinParticleEngine`, `MixinClientLevel`, `MixinFireworkParticle` | vanilla, CPU-side | Yes / Yes | No | No | Preserve |
| Texture animation toggles (water/lava/fire/portal/block/sculk) | `animation/MixinTextureAtlas`, `MixinSpriteContentsAnimationState` | vanilla `TextureAtlas`, renderpearl `GpuBufferSlice` signature | Yes / Yes (if atlas animation stays vanilla) | S2: only if Metallum replaces atlas animation ticking | No | Preserve; keep vanilla `cycleAnimationFrames` |
| Light updates toggle | `light_updates/MixinLevelLightEngine` | vanilla light engine (CPU) | Yes / Yes | No | No | Preserve |
| Item frames / armor stands / paintings / name tags | `render/entity/*` cancel `submit`/`shouldShowName` | vanilla entity renderers | Yes / Yes | No | No | Preserve |
| Beacon beam / height limit / enchanting book / pistons | `render/block/entity/*` | vanilla BE renderers (`SubmitNodeCollector`) | Yes / Yes | No | No | Preserve |
| FPS / extended FPS / coords overlay, corner, contrast | `gui/MixinGui`, `gui/MixinMinecraftClient`, `fps/MixinGameRenderer`, `SodiumExtraHud`, `FrameCounter` | vanilla GUI | Yes / Yes | No | No | Preserve |
| Debug-screen entries (fps, coords, fps ext., light-updates warning) | `SodiumExtraClientMod.registerAll`, Fabric AW hack on `DebugScreenEntries.PROFILES`, `gui/MixinDebugOptionsScreen` | vanilla debug API | Yes / Yes | No | No | Preserve |
| Steady debug HUD | `steady_debug_hud/MixinDebugScreenOverlay` | vanilla | Yes / Yes | No | No | Preserve |
| Toasts (all / advancement / recipe / system / tutorial) | `toasts/*` (synthetic `lambda$update$1` target) | vanilla | Yes / Yes | No | No | Preserve |
| Instant sneak | `instant_sneak/MixinCamera` | vanilla `Camera` | Yes / Yes | No | No | Preserve |
| Prevent (vanilla spectator) post shaders | `prevent_shaders/MixinGameRenderer` | vanilla | Yes / Yes | No | No | Preserve |
| Panini projection | `panini_projection/*`, `PaniniProjection`, `panini.fsh` + `post_effect/panini.json` | vanilla PostChain/PostPass, renderpearl `GpuBuffer`, `CommandEncoder#writeToBuffer`, `Std140Builder`; Iris API (skip when pack active) | S1: Yes if Metallum compiles mod GLSL post shaders (#version 330 + GL_ARB_separate_shader_objects) & supports COPY_DST uniform writes. S2: same | No (backend capability, not adapter) | No | Preserve; add Panini to Metallum's post-shader compile test suite |
| Advanced item tooltips / "More HUD overlays" button | vanilla options / `DebugOptionsScreen` | vanilla | Yes / Yes | No | No | Preserve |

GL interaction summary [V]: the only code that would *break* on a non-GL backend is code that is already backend-conditional (GlSurface mixin is applied but its methods simply never run when the GL backend isn't instantiated; `SDL_GL_*` calls are behind `backendName=="OpenGL"`). Note the `MixinGlSurface` class transformation still requires `GlSurface` to be **present** on the classpath (it ships with vanilla 26.3) — [SI] no issue.

---

## 6. Reese's Sodium Options (RSO) audit

### 6.1 Identity / build
- [V] Version `2.2.6-beta.1`, `SODIUM_VERSION = "0.9.3-beta.1+mc26.3"`, `CONTROLIFY_VERSION = "3.5.2+mc26.3"`, `CONTROLIFY_ENABLED` default **true** (`rso.enableControlify` gradle property) (`reeses-sodium-options/build.gradle.kts:8-22`).
- [V] Fabric `fabric.mod.json`: `depends minecraft ~26.3`, **`sodium "=${sodium_version}"` (exact pin)**, `suggests controlify >=3.0.2`, `breaks iris <1.1.4, sodium-extra <0.5.3, sodium <0.5.8`, `recommends sodium-extra >=0.4.2`; entrypoints `controlify` and `sodium:config_api_user` (`ReeseSodiumOptionsConfigEntryPoint`); license MIT. NeoForge toml: sodium `versionRange "[${sodium_version}]"` (exact), controlify optional. When Controlify disabled, build strips the controlify entrypoint/suggests via regex (`fabric/build.gradle.kts:127-135`) and excludes `client/controlify/**` (`common/build.gradle.kts:37-42`).
- [V] Implication: at the checked-out refs RSO (built for Sodium 0.9.3-beta.1, exact pin) and Sodium Extra 0.9.4 (built for 0.9.2, `>=`) + local Sodium tag 0.9.2 are not a loadable trio; Metallium must align on Sodium 0.9.3-beta.1+ (Sodium Extra's `>=` permits it) [SI].
- [V] Mixin config `reeses-sodium-options.mixins.json`: `compatibilityLevel JAVA_17`, refmap declared, 3 client mixins.

### 6.2 How it replaces Sodium's screen
- [V] `RSO/mixin/sodium/MixinSodiumOptionsGUI.java:16-34`: `@Mixin(net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen)`, `@Inject(method="init", at=TAIL)` → if `ReeseSodiumOptionsConfig.config().isEnabled()`, `minecraft.gui.setScreen(new SodiumVideoOptionsScreen(this.prevScreen))`. Shadows `prevScreen` (exists: `SOD/client/gui/VideoSettingsScreen.java:42`, `init` at `:144`). Shift+P in RSO screen opens Sodium's original screen (`SodiumVideoOptionsScreen.java:433-434`).
- [V] `MixinOption` (`@Mixin(client.config.structure.Option)`) shadows package-private `id` and `state` (Config) (`SOD/client/config/structure/Option.java:16,22`) to expose ids/parent config.
- [V] `MixinAbstractWidget` (`@Mixin(client.gui.widgets.AbstractWidget, remap=false)`) makes `dim` (`Dim2i`) mutable for re-layout of Sodium fallback controls.

### 6.3 Which Sodium classes / API it consumes
- [V] Public API: `api.config.ConfigEntryPoint`, `ConfigState`, `StorageEventHandler`, `option.OptionImpact`, `option.SteppedValidator`, `structure.{ConfigBuilder, OptionBuilder, OptionGroupBuilder, OptionPageBuilder}` (for its own settings page).
- [V] **Internals (heavy):** `client.config.ConfigManager.CONFIG` (`getModOptions()`, `applyAllOptions()`, `resetAllOptionsFromBindings()`, `anyOptionChanged()`, `invalidateGlobalRebuildDependents()`; `SodiumVideoOptionsScreen.java:99,200,253-254,322-326,342,463-464`), `client.config.structure.{ModOptions, Page, ExternalPage, OptionGroup, Option, StatefulOption, BooleanOption, IntegerOption, EnumOption, ExternalButtonOption, Config}`, `client.gui.{VideoSettingsScreen, SodiumOptions, ColorTheme}`, `client.gui.options.control.{Control, ControlElement, AbstractOptionList, CyclingControl, SliderControl, TickBoxControl, ExternalButtonControl}`, `client.gui.prompt.{ScreenPrompt, ScreenPromptable}`, `client.gui.widgets.AbstractWidget`, `client.util.Dim2i`, `client.data.fingerprint.HashedFingerprint`, `client.SodiumClientMod`, `client.services.PlatformRuntimeInformation` (import counts via grep). → RSO is tightly version-coupled to Sodium (hence the exact pin).

### 6.4 Third-party options
- [V] Tabs are built from **every** `ModOptions` in `ConfigManager.CONFIG.getModOptions()` (sorted so RSO's own config last) → all pages of all mods registered through `sodium:config_api_user` (Sodium Extra, Iris, Metallum's `MetallumSodiumConfig`, etc.) (`SodiumVideoOptionsScreen.java:307-326`). Search indexes all pages too (`RSO/client/gui/search/OptionSearch.java:33`).
- [V] Row mapping: `BooleanOption+TickBoxControl`, `IntegerOption+SliderControl`, `EnumOption+CyclingControl`, `ExternalButtonOption+ExternalButtonControl` → RSO-native rows; anything else → `SodiumControlElementOptionRow` that hosts Sodium's own `Control#createElement(...)` (`OptionRowFactory.java:61-80`, `SodiumControlElementOptionRow.java:136-138`); failure → `UnsupportedOptionRow` (label only, logs once). `ExternalPage` tabs open the external screen (`TabSelectionState.java:61`, `SodiumVideoOptionsScreen.java:670`). Option replacements (e.g. Sodium Extra's VSync enum) are resolved by Sodium before RSO sees `ModOptions` [SI].
- [SI] Consequence for Metallum: any **mixin into Sodium's control element rendering** (e.g. `metallum/src/main/java/com/metallum/compat/sodium/mixin/SodiumPreferredGraphicsApiMixin.java`, which `@Redirect`s `EnumOption#getElementName` inside `CyclingControl$CyclingControlElement#extractRenderState`) is bypassed by RSO, whose `EnumOptionRow` calls `option.getElementName(...)` directly (`RSO/client/gui/frame/option/EnumOptionRow.java:106,113,127`). Metallum should instead customise at the API level (element name provider on a replaced option, or a mixin on `EnumOption#getElementName`).

### 6.5 Rendering / platform
- [V] No GPU code: all drawing via vanilla `GuiGraphicsExtractor` (+ `enableScissor`, `AbstractFrame.java:144`). Uses SDL3 for input (`SDLMouse.SDL_GetMouseState`, `AbstractOptionRow.java:404`; `SDLKeycode`), `Blaze3D.openUri` for links. Metal-agnostic in both scenarios.

### 6.6 Controlify
- [V] Optional: `ReeseSodiumOptionsControlifyEntrypoint` registers `ScreenProcessorProvider` for `SodiumVideoOptionsScreen` and a component processor for `SearchTextFieldWidget` (`RSO/client/controlify/*`). Compiled in by default; entrypoint only invoked if Controlify present. Irrelevant to Metal.

### 6.7 Recommendation (RSO)
**Preserve** (do not replace or fork). It is a pure-UI consumer of Sodium's config model, works for third-party options, and is backend-agnostic. Requirements for Metallium: (1) keep Sodium's config subsystem (`ConfigManager`, `structure.*`, `VideoSettingsScreen`) intact — true in S1; in S2 keep Sodium loaded; (2) align Sodium version to RSO's exact pin; (3) make Metallum's own option customisations RSO-safe (API-level, not control-element mixins). If Sodium were removed entirely (S2 without Sodium), RSO cannot function at all and should be dropped rather than shimmed.

---

## 7. Licenses and headers
- [V] Sodium Extra: `LICENSE.txt` = GNU LGPL v3 (29 June 2007) text; `fabric.mod.json "license": "LGPLv3"`, `neoforge.mods.toml license = "LGPLv3"`. **No per-file copyright headers** in Java sources (grep for `Copyright|SPDX|License` matched only `MixinWindow.java`, whose comment credits RetiNo by Julian Dunskus (MIT) and Canvas by grondag (Apache-2.0), `MixinWindow.java:11-19`). Bundled `net.caffeinemc.caffeineconfig.*` (CaffeineMC's config lib) also has no header [V]; its upstream license [U] (CaffeineConfig is commonly LGPL-3.0 from Lithium/Sodium lineage — unverified). Translations CC0 per README.
- [V] RSO: `LICENSE.txt` MIT, "Copyright (c) 2021 FlashyReese"; metadata `license: MIT`. No per-file headers found.
- Implication [SI]: Metallum can freely copy RSO code with the MIT notice. Copying Sodium Extra code (e.g. the fog-shape GLSL/encoding logic) into Metallum triggers LGPL-3.0 obligations; re-implementing the *contract* (band offsets 1,048,576/2,097,152/3,145,728, band size 1,048,576, cylindrical vertical scale 16) from scratch to interoperate is the cleaner path.

---

## 8. Strategy summary

S1 (Sodium unchanged, Metal backend):
- Preserve Sodium Extra + RSO unchanged. Metallum test checklist: Sodium terrain GLSL *after* Sodium Extra's `getShader` patch compiles to MSL (varyings loc 4/5, helper function); Panini post chain (GLSL 330 + `GL_ARB_separate_shader_objects`, std140 UBO, `writeToBuffer` into COPY_DST uniform buffer, processing `entityOutlineTarget`); `reduce_resolution_on_mac` gives 1× drawable; VSync OFF/ON map to MAILBOX/FIFO; Adaptive hidden.
- Optional: Metal FIFO_RELAXED-equivalent + compat mixin for "Metal" backend name.

S2 (Metallum terrain replaces Sodium's):
- Keep Sodium loaded (config API, UI, RSO, Sodium Extra hard dependency). Sodium-internal mixins (culler) remain applied but inert.
- Must implement in Metallum: (1) terrain fog from vanilla `FogData`/Sodium `FogStorage`, **decoding Sodium Extra's shape bands** and computing cylindrical/radial/planar distance in the MSL terrain shader; (2) cylindrical cull expansion in Metallum's culler (or call `FogDistanceHelper.isExpandedCylindricalCullDistance/testExpandedCylindricalCullDistance` reflectively/softly); (3) mesher uses vanilla tint providers (biome-colors toggle) and vanilla light data (light-updates toggle); (4) keep vanilla atlas animation ticking (animation toggles).
- Alternative minimal path: force `mixin.fog`'s shape support off and only support distance/start/off — but there is no public switch for shape alone; disabling `mixin.fog` via fabric.mod.json custom value kills *all* fog options (too broad). [V mechanism]

---

## Open questions / not verified
1. [U] How vanilla 26.3's Vulkan backend chooses `PresentMode.FIFO_RELAXED` when Sodium Extra's "ADAPTIVE" is set (Sodium Extra only mixes `GlSurface`); MC source unavailable.
2. [U] Whether Metallum's shader pipeline obtains GLSL via `ShaderManager.Configs#getShader` (so Sodium Extra's patch reaches it) or via another resource path — needs reading Metallum's shader compile path (not in scope here; SI yes since Sodium uses `RenderPipeline.withVertexShader(Identifier)`).
3. [U] Iris interplay with the encoded render-distance fog bands (does Iris read `FogData.renderDistanceStart/End` or Sodium `FogParameters` raw, thereby seeing +1M offsets when a non-vanilla shape is selected with a shader pack active?).
4. [U] Greenlight internals (resource pack trust model) — jar not available; described from Sodium Extra docs and call sites only.
5. [U] Upstream license of the bundled `net.caffeinemc.caffeineconfig` package.
6. [H] SDL3 `SDL_Metal_CreateView` sets `CAMetalLayer.contentsScale` according to `SDL_WINDOW_HIGH_PIXEL_DENSITY`, so reduce-resolution yields a 1× drawable with Metallum — not runtime-tested.
7. [H] RADIAL/PLANAR encoded `renderEnd` makes Sodium's `FogParameters.cullDistance` collapse to `environmentalEnd` (fog-occlusion culling side effect) — behavioural, not tested.
8. [U] Whether `MixinToastManager`'s synthetic target `lambda$update$1` matches the 26.3 bytecode (would crash at startup if not, given `defaultRequire 1`); assumed correct since the branch targets 26.3.
9. Not checked: Sodium Extra lang files / Crowdin content; NeoForge-specific runtime differences beyond entrypoints.
