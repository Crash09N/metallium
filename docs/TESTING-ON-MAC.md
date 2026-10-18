# Testing Metallium on your Mac

Requirements: Apple Silicon Mac, macOS 14 or newer, Java 25 (e.g. `brew install --cask temurin@25`), Git.

## 1. Get the code

```bash
git clone https://github.com/Crash09N/metallium.git
cd metallium
git checkout claude/lucid-heisenberg-f4n1hp
```

## 2. Quick checks (2 minutes)

```bash
./gradlew metalSmokeTest   # creates a Metal device, renders offscreen, prints "SMOKE: PASS"
./gradlew test             # shader translator tests, also compiled with your Mac's Metal compiler
```

## 3. Run the game from the source tree

```bash
./gradlew runClient                     # vanilla + Metallium
./gradlew runClientGameTest             # automated: boots, creates two worlds, screenshots, report
./gradlew runClientGameTest -PwithSodium
./gradlew runClientGameTest -PwithIris  # Sodium + Iris + Metallium Iris adapter (work in progress)
```

Game-test results land in `build/run/clientGameTest/`:
`metallium-gametest-report.txt`, `screenshots/`, `logs/latest.log`, `crash-reports/`.

The report's first line must say `backend: Metal | Apple M3 Max ...`.

## 4. Install into your normal launcher instead

```bash
./gradlew build
```

Copy `build/libs/metallium-<version>.jar` into the `mods` folder of a **Fabric 26.3** profile (Fabric Loader ≥ 0.19.5).
Optional: Sodium 0.9.2, Fabric API. Do **not** add Iris yet unless you also add
`metallium-iris/build/libs/metallium-iris-<version>.jar` (with only Iris, Metallium deliberately falls back to OpenGL).

Check the log for `Using graphics backend Metal`. To compare against OpenGL, start with `-Dmetallium.backend=off`
or turn off "Metal Backend" in Sodium's video settings (restart needed).

## 5. What to send back

- `metallium-gametest-report.txt` and the screenshots (or your own screenshots), with and without Sodium.
- `logs/latest.log` and any `crash-reports/*.txt`.
- FPS with VSync off in the same world/place: Metallium vs `-Dmetallium.backend=off` (OpenGL).

## Known issues (2026-10-18)

- With **Sodium**, terrain is currently invisible on the CI's virtual Mac GPU (under investigation; may or may not
  reproduce on real hardware — your test answers that).
- **Iris** shader packs do not work on Metal yet; the adapter is at stage 1 (getting Iris to start).
