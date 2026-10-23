# Shader pack compatibility

`tools/pack-sweep.sh` is the Iris pack regression test: it runs the Iris client game test
(`./gradlew :runClientGameTest -PwithSodium -PwithIris -Pshaderpack=<zip>`) once per local pack on Metal, parses the game
test report and `latest.log`, and writes `docs/compat/shaderpacks-<date>.md`.

Packs are local only (`/Users/noah/Desktop/Nativium/nativium-research/shaderpacks/`, see its `MANIFEST.md`). Never copy them into
the repository; most licences forbid redistribution. Screenshots and logs are written outside the repo to
`/Users/noah/Desktop/Nativium/nativium-research/sweep-results/<date>/` and referenced there by absolute path.

## Run

1. Quit other Minecraft instances (the script waits for dev clients, not for foreign launchers). One pack takes ~1.5-3 min.
2. Full sweep: `tools/pack-sweep.sh`. Subset: `PACKS="BSL_v10.1.8.zip MakeUp-UltraFast-9.5g.zip" tools/pack-sweep.sh`.
   Names containing spaces: `PACKS_FILE=list.txt` (one zip name per line).
3. `REFERENCE=1` also runs every pack on `-Pengine=OPENGL` and records the reference screenshot path.
4. Other overrides: `PACK_DIR`, `RESULTS_DIR`, `OUT`, `SMOKE=1` (marks the output as a smoke run; do not commit it).
5. Look at the screenshots (`<tag>-metal-natural.png`, `<tag>-metal-flat.png`) before trusting a row.

## Columns

- Status: **works** = pack loaded, no crash, no unsupported-feature report; **partial** = renders, but Nativium logged
  unsupported features, skipped programs or failed translations (or terrain coverage < 0.30); **fails** = not loaded or
  the game crashed.
- Missing features: distinct `[nativium-iris] Unsupported shader pack feature` log entries with counts (shadow/DH
  programs, compute, mipmapped buffers, translation failures with the translator's reason, ...).
- Notes: pipeline class, crash, game test result, terrain coverage (share of non-sky pixels in the lower screen band of
  the natural-world pack screenshot; only detects an empty frame, not wrong colours), translated/failed gbuffers program
  counts, programs not executed, screenshot and log paths.

# Real modpack (mod compatibility)

`tools/make-run-instance.sh` + `./gradlew runClientGameTest -Pinstance` run a copy of a real instance (all its mods,
resource and shader packs) on the dev Nativium; see [real-instance-2026-10-18.md](real-instance-2026-10-18.md) for the
first run with the owner's 145-mod pack and the findings.

## Conformance (Metal vs OpenGL)

`./gradlew :runClientGameTest -PconformanceTest` (and again with `-PbackendOff`), then
`tools/conformance-compare.sh <metal-shots> <opengl-shots> <out>`: deterministic scenes for every vanilla render
pipeline, compared shot by shot. Latest: `conformance-2026-10-23.md`.
