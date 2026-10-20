# Metallium alpha release (0.1.0-alpha)

Metallium ships as **one plain Fabric jar**: `metallium-0.1.0-alpha.jar`. No launcher, no installer.

## Build

```
export JAVA_HOME=<JDK 25>
./gradlew releaseJar        # -> build/release/metallium-<version>.jar  (./gradlew build also leaves it in build/libs/)
```

The jar contains (jar-in-jar, `META-INF/jars/`):

| Nested jar | Licence | Role |
|---|---|---|
| `metal-jvm-0.1.0.jar` | MIT (`LICENSES/MIT-metal-jvm.txt`) | Metal bindings |
| `metallium-gl-<v>.jar` | MIT | OpenGL to Metal translation for mods that call GL directly; only routes mods that need it |
| `metallium-iris-<v>.jar` | **LGPL-3.0** (own `LICENSE` + `SOURCE.txt` inside) | Iris shader-pack adapter; stays a separate jar so it can be replaced |
| `metallium-voxy-natives-<v>.jar` | MIT (own packaging) + Apache-2.0 / BSD-3-Clause / OLDAP-2.8 (texts in its `META-INF/licenses/`) | Native files only (4.0 MB): the macOS arm64 RocksDB 10.2.1 and LWJGL 3.4.1 zstd/LMDB natives that Voxy's bundled libraries lack. Inert on other systems and without Voxy |

The core (outer jar) is MIT. The LGPL adapter is never merged into the core jar; its corresponding source is the
`metallium-iris/` directory of this repository (see `NOTICE`, README "Credits & licenses", and `SOURCE.txt` in the nested jar).

## Install

Requirements: Apple Silicon Mac with Metal 4 (macOS 26+; the backend compiles MSL 4.0), Minecraft 26.3, Fabric Loader >= 0.19.5, Java 25, Fabric API.

1. Copy `metallium-0.1.0-alpha.jar` into the instance's `mods/` folder (Prism, official launcher, anything that runs Fabric).
2. Optional: Sodium 0.9.x (>= 0.9.2, < 0.10) for Sodium on Metal; Iris >= 1.11.6, < 1.12 (needs Sodium) for shader packs.
3. Start the game. F3 shows the "Metallium ..." line; the Metallium status screen is reachable from the Sodium "Metallium" tab or Video Settings.

Do not install the separate `metallium-gl` / `metallium-iris` jars next to this one (duplicate mod ids).

`./gradlew releaseJar` also writes the standalone `metallium-voxy-natives-<v>.jar` (for Voxy on a Mac without Metallium).
With Metallium installed it is not needed (Fabric keeps the newer copy if both are present). Without these natives Voxy
crashes the game on world join on a Mac ("librocksdbjni-osx-arm64.jnilib was not found inside JAR"); if they are missing,
Metallium disables Voxy for the session and says so before the title screen and on its status screen.

## What is inert when

- **No Iris** (or Iris outside 1.11.6 - 1.11.x, or no Sodium): the nested adapter loads but applies no mixins and does nothing
  (`IrisSupport`); log line `[metallium-iris] inactive: ...`. With Iris present but unsupported, Metallium falls back to OpenGL so Iris keeps working.
- **No mod that calls OpenGL directly**: metallium-gl installs its class-loading hook but retargets nothing.
- **Not Apple Silicon macOS, or Metal not used this session**: see "Platforms" below.

## Platforms

The jar is safe in any modpack. One predicate, `MetalliumPlatform.supported()`, decides whether Metallium does anything:
macOS **and** aarch64 (Apple Silicon) **and** a Metal device the backend can use (`MTLCreateSystemDefaultDevice` non-null,
GPU family Apple7+ and Metal 4). It is evaluated once, at the first Metallium mixin config load; the Metal probe (metal-jvm,
FFM into Metal.framework) only runs on macOS aarch64, so no native code is touched elsewhere.

| Machine / session | What Metallium does |
|---|---|
| Windows, Linux, any non-macOS | Nothing. One log line `Metallium: not a Mac — inactive (game runs on its normal renderer)`. No mixin applies (every config is gated by `MetalliumMixinPlugin` / its own plugin), entrypoints return at once, metallium-gl does not hook class loading, the Iris adapter applies nothing, no Sodium page, no screens, no F3 line, no config or session file is written. |
| Intel Mac, Mac without a usable Metal device | The same, with the log line `Metallium: no supported Metal device — inactive`. |
| Apple Silicon Mac, Metal not used this session (Graphics Engine / vanilla Graphics API = OpenGL or Vulkan, crash recovery, Iris without the adapter, a mod that needs OpenGL) | Rendering and other mods are untouched: no Metal backend, Iris runs its own OpenGL pipeline (the adapter's mixins are not applied), metallium-gl routes nothing (also not explicitly opted-in mods such as Voxy), no Sodium/Distant Horizons/Physics Mod/BetterF3 behaviour changes, no power-mode FPS cap. What stays is what lets the player see the state and switch back to Metal: the "Metal (via Metallium)" entry of the Graphics API option (vanilla and Sodium), the Metallium page / status screen / F3 line, crash-recovery and needs-OpenGL screens, and the Voxy natives check (Voxy would crash on any renderer without its macOS arm64 natives). The exact list is `MetalliumMixinPlugin.ENGINE_SELECTOR`. |
| Apple Silicon Mac, Metal offered | Everything. If Metal device creation still fails and vanilla falls back to OpenGL, the behaviour-changing mixins check `MetalliumRuntime.isMetalActive()` and do nothing. |

The Metal-or-not decision is made before vanilla reads `options.txt` (mixins and metallium-gl need it at class-load time),
so `BackendSelection` reads vanilla's `preferredGraphicsBackend` from `options.txt` itself with the same rule as
`EngineSync` (a value the player changed in vanilla's Graphics API option wins).

`metallium-voxy-natives` (nested) contains only macOS arm64 native files under platform-specific names; it has no code
and is inert on every other system.

Checks: unit tests `MetalliumPlatformTest` (Windows x64/arm64, Linux, FreeBSD, Intel Mac, Apple Silicon without device:
no mixin of any config applies, no native probe; the early engine rule), `MetalliumGlBootstrapTest`, `GlRoutingTest`
(`nothingIsRoutedWithoutMetal`). Game test on a Mac: `-PsimulatePlatform=windows|linux|intel-mac|no-metal`
(`-Dmetallium.platform.simulate`) or `-Pengine=OPENGL`; the report's `metallium inactive:` lines list applied
Metallium mixins and metallium-gl routing, and the run fails on any mixin outside the allowed set or any routed mod.

## Verified (local, M3 Max)

See "Release jar verification" at the end of this file.

## Known limits (alpha, from `docs/STATUS.md`)

- Iris adapter: custom textures (`texture.*`, incl. 3D) are missing (blocks e.g. Photon), `size.buffer.*`, mipmapped shadow maps,
  programs with more than 16 textures / 9 draw buffers, geometry/tessellation shaders (`.gsh`). Not every pack works; see STATUS.md per pack.
- Packs see `MC_OS_MAC` unless `-Dmetallium.iris.hideMacOs=true`; some packs disable compute features on it.
- Voxy runs only through metallium-gl (opt-in); Distant Horizons works, closing a world with DH loaded can deadlock only in the test harness.
- metallium-gl implements a subset of OpenGL; mods using unimplemented functions get a crash-recovery screen and OpenGL on the next start.
- Apple-native extras (MetalFX, Game Mode, ...) are not part of the alpha.
- Closed-source mods are never bundled; Physics Mod and similar work only as separate installs.

## Release jar verification

Local game tests run against `build/release/metallium-0.1.0-alpha.jar` installed as a mod (no dev classes of the root
project or subprojects on the classpath), M3 Max, macOS 27.0.1:

- `./gradlew :runClientGameTest -PreleaseJarTest` (jar alone, no Sodium, no Iris): Metal active, flat/natural world render
  (screenshots checked), status screen reports metallium-gl "installed, class loading hooked", the raw-GL test class is routed
  through metallium-gl; the nested adapter logs `[metallium-iris] inactive: Iris is not installed`. Fabric lists
  `metallium 0.1.0-alpha` with nested `metallium-gl` and `metallium-iris`. Only message: info that Sodium (recommended) is missing.
- `-PreleaseJarTest -PreleaseJarIris` (jar + Sodium 0.9.2 + Iris 1.11.6): Iris "on Metal via the Metallium Iris adapter",
  post pipeline, gbuffers, shadow and compute test packs pass as in the dev run (compute pack checks all 1.00).

Reproduce: `./gradlew :runClientGameTest -PreleaseJarTest [-PreleaseJarIris]` (the flag builds the release jar first).
