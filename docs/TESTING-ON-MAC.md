# Testing Nativium on your Mac

Requirements: Apple Silicon Mac, macOS 26 or newer (Metal 4), Java 25 (e.g. `brew install --cask temurin@25`), Git.

Just want to play? Use the release jar instead: `nativium-1.0.0.jar` in your Fabric 26.3 instance's `mods/`
(install notes: [`RELEASE.md`](RELEASE.md)). This page is for running Nativium from source.

## 1. Get the code

```bash
git clone https://github.com/Crash09N/nativium.git
cd nativium
git checkout claude/lucid-heisenberg-f4n1hp
```

## 2. Quick checks (2 minutes)

```bash
./gradlew metalSmokeTest   # creates a Metal device, renders offscreen, prints "SMOKE: PASS"
./gradlew test             # shader translator tests, also compiled with your Mac's Metal compiler
```

## 3. Run the game from the source tree

```bash
./gradlew runClient                     # vanilla + Nativium
./gradlew runClientGameTest             # automated: boots, creates two worlds, screenshots, report
./gradlew runClientGameTest -PwithSodium
./gradlew runClientGameTest -PwithIris -Pshaderpack=/path/to/pack.zip   # Sodium + Iris + Nativium Iris adapter
./gradlew runClientGameTest -PreleaseJarTest -PreleaseJarIris          # the release jar as a mod (production-style)
```

Game-test results land in `build/run/clientGameTest/`:
`nativium-gametest-report.txt`, `screenshots/`, `logs/latest.log`, `crash-reports/`.

The report's first line must say `backend: Metal | Apple M3 Max ...`.

Your own modpack (copy in git-ignored `run-instance/`, your instance is never modified):

```bash
tools/make-run-instance.sh /path/to/your/instance   # mods, shader/resource packs, config, options.txt
./gradlew runClientGameTest -Pinstance               # fresh world, every shader pack; add -PinstancePacks=a.zip,b.zip or =-
```

Results in `run-instance/` (report, `screenshots/`, `logs/`). See `docs/compat/real-instance-2026-10-18.md`.

## 4. Install into your normal launcher instead

```bash
./gradlew releaseJar     # -> build/release/nativium-<version>.jar (contains nativium-gl, nativium-iris, the Voxy natives)
```

Copy that one jar into the `mods` folder of a **Fabric 26.3** profile (Fabric Loader ≥ 0.19.5) with Fabric API;
Sodium 0.9.x recommended, Iris 1.11.6+ optional (the Iris adapter is inside the jar). Details: [`RELEASE.md`](RELEASE.md).

F3 shows a "Nativium ..." line when Metal is active. To compare against OpenGL, set the Graphics Engine to OpenGL
(Sodium's "Nativium" tab or Video Settings → Graphics API; restart needed) or start with `-Dnativium.backend=off`.

## 5. What to send back

- `nativium-gametest-report.txt` and the screenshots (or your own screenshots), with and without Sodium.
- `logs/latest.log` and any `crash-reports/*.txt`.
- FPS with VSync off in the same world/place: Nativium vs `-Dnativium.backend=off` (OpenGL).

## Known issues

Current list: [`RELEASE.md`](RELEASE.md) "Known limits" and the newest entries of [`STATUS.md`](STATUS.md).
