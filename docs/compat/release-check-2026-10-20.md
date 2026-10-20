# Release-jar check, production-style launch, 2026-10-20

`./gradlew releaseJar` (after merging the Physics Mod shader fix from the main branch), then
`./gradlew runClientProd -PplayProd=<copy of the play instance> -PplayWorld=relcheck1`: Fabric launched with
`fabric.development` unset, Metallium as the release jar (nested metallium-gl, metallium-iris, metallium-voxy-natives),
no dev-only test aids. Instance copy: 146 mods (play instance minus `animated-mojang-logo`, `config/metallium/session.json`
removed, windowed 854x480-ish window, Iris on with Complementary Reimagined r5.6.1). World: copy of the game test world
(`Quick Play` cannot create a world: "Could not find a world with the provided identifier"). Join took ~25-30 s after
the JVM started; the run then idled for 90 s in the world.

## Runs

| Run | Result |
|---|---|
| Metal (default config) | Joined. Log: `Metal backend enabled`, `Graphics Engine: METAL`, `Using shaderpack`, `Metal shader pipeline: shadow 2048px + gbuffers ... + 8 fullscreen passes + final`, Physics Mod on Metal, Voxy render system created, no crash report, no mixin apply failure. Window screenshot: world rendered, minimap + BetterF3 HUD visible. |
| OpenGL (`"engine": "OPENGL"`) | Joined. `Metal backend disabled: Graphics Engine is OPENGL`, `Graphics Engine: OPENGL`, all mods report `OPENGL_ENGINE`, game renders. `Voxy is unsupported on your system` (GL 4.1, expected). No crash report. |
| Session marker | Present during both runs, absent after exit. The exit was a SIGTERM (see limits), which is the "sigterm" case of `tools/check-session-exit.sh`. |

The only log errors in both runs are offline-mode / mod-config noise, identical on Metal and OpenGL: `Failed to refresh session: Unknown account`, Realms 401,
Essential connection manager (offline), `jeresources.json` missing (first start), JER `Missing registry trade_set`,
six `Pack declares support for version newer than 64` (resource pack metadata), Xaero `Setting not found` warnings,
Iris/EnhancedVisuals `Method overwrite conflict for getResourcePool` warning.

## Per mod

| Mod | Loaded | Works | Issue |
|---|---|---|---|
| Metallium release jar (+gl, +iris, +voxy-natives nested) | yes | yes | none |
| Sodium 0.9.3-beta.1 | yes | yes (terrain renders on Metal) | none |
| Iris 1.11.7 + Metallium adapter | yes | yes, pack loaded, Metal pipeline built | none |
| Voxy 0.2.20-beta + metallium-voxy-natives | yes | yes on Metal: world engine created, render system created, RocksDB storage dir (`voxy/<id>/storage`, .sst files) written, no natives crash | OpenGL: "unsupported" (GL 4.1), game continues |
| LOD Server Support 0.19.0 | yes | "Voxy detected - registered raw ingest bridge" | none |
| Physics Mod 3.2.5 | yes | routed through metallium-gl, "Physics Mod on Metal" | not exercised visually (no blocks broken) |
| Essential 1.5.1 + essential-baritone-compat + patched essential/ jar | yes | starts, no crash | cannot connect (offline), expected |
| Skin Layers 3D 1.11.3 | yes | no errors | player model not inspected |
| ReGlass 2.1.1 | yes | no errors | glass not inspected |
| Cull Leaves 4.1.2 | yes | no errors | not inspected |
| BetterF3 20.0.0 | yes | HUD text visible in screenshot (FPS/CPU) | F3 screen itself not opened (see limits) |
| Xaero minimap / world map / XaeroPlus | yes | minimap renders, routed through metallium-gl | `Setting not found` warnings (XaeroPlus config keys, harmless) |
| Axiom, polytone, fpsreducer, lsdc, crash_assistant | yes | routed through metallium-gl, no errors | none |
| Bobby, More Culling, Distant Horizons | not in the play instance | not tested | |
| JER (JustEnoughResources) | yes | game runs | `Error during loading of villager trades: Missing registry trade_set` (JER vs 26.3 data, not Metallium) |

## Limits of this check

- No keyboard input path: osascript keystrokes and CGEvents are refused (no Accessibility permission for the agent
  shell), so F3 and F2 could not be pressed, and `Cmd+Q`/a System Events quit did not stop the game. The in-game
  picture is a desktop screenshot of the game window (`screencapture`). Verified F3 lines for Metallium (engine, Iris,
  Voxy, power) are therefore only covered by the game tests, not by this run. The in-game quit path was replaced by
  SIGTERM; a GUI "Save and Quit" was not driven.
- Quick Play needs an existing save; the shared run used a copy of the game test world.
- Dev-run gap found in Part A: the game test bench with `-PwithVoxy=<jar>` does not load the nested natives addon,
  so Voxy is silently disabled there (the release jar loads it, as shown above).
