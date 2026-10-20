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

Requirements: Apple Silicon Mac, macOS 14+, Minecraft 26.3, Fabric Loader >= 0.19.5, Java 25, Fabric API.

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
- **Not Apple Silicon macOS**: Metallium keeps OpenGL/Vulkan (backend selection).

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
