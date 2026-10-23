# Conformance scenes: Metal vs OpenGL (2026-10-23)

Phase 1 roadmap item: every vanilla render pipeline compared against OpenGL screenshots (orientation, blending, depth,
OIT). Result: **no Metal conformance bug found.** Every scene matches OpenGL within the noise that two Metal runs of
the same scene show among themselves.

## How it is run

```
./gradlew :runClientGameTest -PconformanceTest                 # Metal
./gradlew :runClientGameTest -PconformanceTest -PbackendOff    # OpenGL reference (vanilla's GL backend)
tools/conformance-compare.sh <metal screenshots> <opengl screenshots> <out dir>
```

Options: `-PconformanceScenes=terrain,water` (subset), `-PconformanceFabulous=false` (skip the fabulous pass),
`-PwithSodium` (Sodium terrain instead of vanilla's). Screenshots: `build/run/clientGameTest/screenshots/NNNN_conformance-<mode>-<scene>.png`;
copy them away before the next run (the run directory is recreated). The compare script prints the table below and
writes `<shot>-diff.png` (ImageMagick compare, fuzz 10%) and `<shot>-side.png` (Metal | OpenGL | |difference| x4).

Scenario: `src/gametest/java/de/nprmedia/nativium/gametest/ConformanceScenario.java`. Window 854x480 (game-test
default), M3 Max, macOS 27.0.1, Minecraft 26.3.

Determinism: flat world; `advance_time`/`advance_weather`/mob spawning off; mobs `NoAI`; the server tick clock is
frozen (`/tick freeze`) for each shot, which also stops client entity ticks, particles and ambient block particles;
enchantment glint speed 0; chunk fade-in 0; view bobbing off; particles set to minimal while a scene is built; scenes
are cleared with `fill ... strict` and entity discard (no drops, no death particles); toasts cleared before each shot.
The client runs in lock step with the test. Each world scene is shot in **fancy** and again in **fabulous** (improved
transparency: the transparency post chain, vanilla's OIT path); HUD and inventory only once.

## Scenes

| scene | pipelines covered |
|---|---|
| terrain | solid, cutout (leaves, plants, cobweb, iron bars, panes), translucent (stained glass, ice, slime, honey, tinted glass) in front of a coloured wool wall (orientation: brown on the left, white on the right) |
| water | translucent water over coloured floor, kelp, sea pickles, ice, lily pad, boat, stained-glass wall behind, translucent smoke / effect particles above the water |
| underwater | water fog, underside of the water surface, kelp |
| entities | cow, sheep, husk with a name tag (see-through text), creeper with **Glowing** (entity outline post pass), translucent slime, enderman (emissive eyes), armor stand (armor, glint, shield), item frames and glow item frame (glint item, block item, block-entity item), painting, minecart, entity shadows |
| block-entities | single/double chests, ender/trapped/copper chest, signs with text, hanging sign, banner with patterns, bed, shulker box, skulls/heads, decorated pot, lectern with book, bell, enchanting table, spawner with pig, conduit |
| portals-beacon | end portal and end gateway (screen-space portal shader), gateway beam, beacon beam through stained glass, nether portal (translucent, animated) |
| particles | opaque, emissive and translucent particle types at fixed positions (flame, heart, note, crit, end rod, glow, villager, dust, block, item, smoke, totem, dripping water, ...) |
| sky-day / sky-sunset / sky-night | sky, sun with glow, sunrise colours, horizon fog, fancy clouds, moon, stars |
| weather | rain (plains) and snow (snowy plains) side by side in front of a wall |
| outline-breaking | block outline (crosshair on a log), crumbling overlay at three stages, hand, hotbar |
| hand-glint | first-person sword with enchantment glint, off-hand item, hotbar glint |
| leash-fishing-map | leash (cow to fence), fishing line (rod to bobber), filled map in the off hand and in an item frame |
| hud | hotbar items (3D blocks, block-entity items, glint, durability bar, counts), hearts, food, XP bar and level, title/subtitle, boss bar, scoreboard sidebar, formatted chat |
| gui-inventory | survival inventory: player model, armor slots, item models, glint, tooltip with formatted text |

Lightning is excluded (random bolt geometry).

## Results

Values: % of pixels differing by more than 10% / by more than 25% (fuzz). "Metal vs Metal" is two Metal runs of the
same build (the noise floor). Sodium column: `-PwithSodium` Metal vs `-PwithSodium -PbackendOff`, fancy ; fabulous.

| scene | Metal vs GL fancy | Metal vs GL fabulous | Metal vs Metal fancy | Metal vs Metal fabulous | Sodium Metal vs GL | verdict |
|---|---|---|---|---|---|---|
| terrain | 0.01 / 0.00 | 0.01 / 0.00 | 0.01 / 0.00 | 0.01 / 0.00 | 0.01 / 0.00 ; 0.01 / 0.00 | match |
| water | 0.03 / 0.00 | 0.02 / 0.00 | 0.05 / 0.00 | 0.05 / 0.00 | 0.02 / 0.00 ; 0.04 / 0.00 | match (water animation phase, random smoke size) |
| underwater | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| entities | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match (glowing outline present on both) |
| block-entities | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| portals-beacon | 0.03 / 0.02 | 0.00 / 0.00 | 0.09 / 0.05 | 0.12 / 0.07 | 0.00 / 0.00 ; 0.02 / 0.01 | match (animation phase of nether portal, beams, portal starfield) |
| particles | 0.37 / 0.31 | 0.29 / 0.23 | 0.24 / 0.17 | 0.31 / 0.24 | 0.44 / 0.37 ; 0.27 / 0.22 | match (random particle size/sprite/offset) |
| sky-day | 0.00 / 0.00 | 0.00 / 0.00 | 0.01 / 0.00 | 0.01 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| sky-sunset | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| sky-night | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| weather | 1.31 / 0.29 | 1.31 / 0.31 | 1.60 / 0.36 | 1.61 / 0.39 | 1.07 / 0.22 ; 1.07 / 0.23 | match (random rain/snow columns) |
| outline-breaking | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| hand-glint | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match |
| leash-fishing-map | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 ; 0.00 / 0.00 | match (a few fishing-line edge pixels) |
| hud | 0.00 / 0.00 | – | 0.00 / 0.00 | – | 0.00 / 0.00 | match (AE fuzz 0: ~2 pixels) |
| gui-inventory | 0.00 / 0.00 | – | 0.01 / 0.00 | – | 0.00 / 0.00 | match |

Reviewed by eye (side-by-side and difference images): no orientation flips (wool order, text, map, HUD layout), no
blending differences (stained glass, water, slime, fabulous composite, particles over water), no depth errors (outline,
breaking overlay, see-through name tag, entity outline, portal shader), nothing missing. The remaining differences are
all time- or random-driven content that differs just as much between two runs on the same backend:

- texture animation phase (water, nether portal) and beam scroll / end-portal starfield, which follow the frame's
  partial tick;
- particles: per-particle random size, sprite and initial motion (spawned one tick before the freeze);
- weather: random precipitation columns.

Scenario notes (vanilla behaviour, the same on OpenGL): a `fillbiome` back to plains did not re-tint the grass of the
shared scene area in the first version (it stayed snowy-plains teal for the rest of the run on both backends), so the
weather scene now has its own area (x=26..54); a husk replaces the zombie, which could catch fire in daylight during
the unfrozen setup tick (seen in one OpenGL run).

Not covered: lightning, the end sky / nether fog (other dimensions), the dragon and wither beams, Iris shader packs
(see `shaderpacks-2026-10-19.md`).
