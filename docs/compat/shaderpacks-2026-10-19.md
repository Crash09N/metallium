# Shader pack compatibility 2026-10-19 (Metal, baseline before the encoder optimisation)

- Commit 8c5c9ea + bench tooling; Apple M3 Max, macOS 27.0.1; `REFERENCE=1 tools/pack-sweep.sh` (each pack on Metal and on OpenGL), all runs under `gpu-lock.sh` (shared, not exclusive).
- Screenshots/logs (outside the repo): `/Users/noah/Desktop/Nativium/nativium-research/sweep-results/2026-10-19-baseline/<pack>-{metal,opengl}-{natural,flat}.png`, `-report.txt`, `-latest.log`.
- All 11 packs in the local pack directory: no crash, game test PASS for 10, FAIL for BSL (assertion below). Pipeline is `MetalPostPipeline` for all.

## Classification (I looked at the Metal and OpenGL screenshots)

Caveat: the game test does not place the camera identically between runs (mob/terrain position differs between the Metal and OpenGL screenshot of the same pack), so visuals
were compared by look, not pixel-for-pixel. In 4 packs the OpenGL "reference" looks like unshaded vanilla (Nostalgia, photon, rethinking-voxels, Solas), so it is not a usable reference there (cause not investigated; Iris loaded the pack pipeline on GL).

| Pack | Class | Notes |
|---|---|---|
| ComplementaryReimagined r5.9.3 | renders correctly | Shaded grass/trees, soft lighting; 41 gbuffers programs translated, 0 failed. Only chat message: DH programs. |
| ComplementaryUnbound r5.6.1 | renders correctly | Shadows, fog, sky look like the OpenGL reference. |
| MakeUp-UltraFast 9.5g | renders correctly | Looks like the GL reference (bright, light fog). Not tested for DH. |
| Sildur's Vibrant Extreme 2.02 | renders correctly | Colour grading/sky match the GL reference. |
| superDuperVanilla 1.3.8 | renders, probably correct | Plausible sunset-ish shaded scene on Metal; composite1 failed to translate (MSL compile error, vanilla shader used) and 2 programs failed, so some effect is missing; the view differs from the GL one so not comparable. |
| BSL 10.1.8 | renders with visible issue / test FAIL | Image looks right (shadows, sky, fog) but the game test fails: "player model looks white/untextured or see-through with the external pack (f5 0.00 near-white, 0.28 sky; hand 0.00 near-white)" - the third-person player model is see-through (sky shows through its area). Needs follow-up. |
| Nostalgia 5.1 | renders with visible differences | Metal shows heavy blue-grey fog and strong haze. Metal reports "blend override not supported yet (blending off)" for `prepare` and `deferred`, which likely causes the fog/haze to be wrong. GL reference is unshaded, so not comparable. |
| rethinking-voxels r0.1-beta9 | renders, dark, unverifiable | Very dark scene with visible torch/light; `shadow` program uses a geometry shader (not supported on Metal, vanilla shader used), so shadows/voxel lighting are not working. GL reference unshaded. |
| Solas 3.8 | renders, unverifiable | Dark, moody forest; no failed translations. GL reference unshaded. |
| photon v1.3b | black / broken | Frame is entirely black except the HUD. 8 programs failed translation: `customtex0`/`customtex3` samplers have dimension 3 or similar ("sampler 'customtex0' has dimension 2 (only 2D textures are supported yet)": deferred, deferred1, deferred4, composite). |
| Bliss v2.1.2 (Chocapic13 edit) | black / broken | Frame is entirely black except the HUD. 19 programs failed to translate: `gbuffers_terrain/hand/entities` (MSL compile error), `deferred` ("'highp' : overloaded functions must have the same parameter precision"), `composite2`, `composite4`; also 9 gbuffers buffers written but Metal has 8 attachments (writes to colortex[15] dropped). |

Summary: works well 4 (Complementary x2, MakeUp, Sildur) + superDuperVanilla probably; BSL image fine but test FAIL; 3 render with notable doubt/differences (Nostalgia, rethinking-voxels, Solas); 2 black (photon, Bliss); 0 crashes.

## In-chat "[Metallium] Not supported yet" messages (from each Metal `latest.log`; each is posted twice per run)

| Pack | Chat message |
|---|---|
| Bliss | `Not supported yet: 1 DH programs are not executed yet on Metal (+4 more, see log)` |
| BSL | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| ComplementaryReimagined | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| ComplementaryUnbound | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| MakeUp-UltraFast | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| Nostalgia | `Not supported yet: prepare: blend override not supported yet (blending off) (+1 more, see log)` |
| photon | `Not supported yet: 2 DH programs are not executed yet on Metal (+4 more, see log)` |
| rethinking-voxels | `Not supported yet: 2 DH programs are not executed yet on Metal` (log also: shadow geometry/tessellation shaders unsupported) |
| Sildur | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| Solas | `Not supported yet: 2 DH programs are not executed yet on Metal` |
| superDuperVanilla | `Not supported yet: 3 DH programs are not executed yet on Metal (+1 more, see log)` |

The chat message shows only the first entry; the full list per pack is in the generated table below. The DH (Distant Horizons) message is benign here (DH is not installed).

## Generated table (tools/pack-sweep.sh output, status column is the script's heuristic: everything is "partial" because of the DH message)

| Pack | Script status | Missing features (log) | Translated / failed gbuffers programs |
|---|---|---|---|
| Bliss | partial | 19 failed translations (see above), 9 buffers vs 8 attachments, DH | 27 / 19 |
| BSL | partial (test FAIL) | DH | 42 / 0 |
| ComplementaryReimagined | partial | DH | 41 / 0 |
| ComplementaryUnbound | partial | DH | 41 / 0 |
| MakeUp-UltraFast | partial | DH | 44 / 0 |
| Nostalgia | partial | blend override (prepare, deferred) | 42 / 0 |
| photon | partial | 8 failed: customtex 3D sampler, DH | 43 / 8 |
| rethinking-voxels | partial | shadow geometry shader, DH | 31 / 0 |
| Sildur | partial | DH | 43 / 0 |
| Solas | partial | DH | 39 / 0 |
| superDuperVanilla | partial | composite1 MSL compile error, DH | 41 / 2 |

Terrain coverage is 1.00 for all packs, including the black Bliss/photon frames, so the coverage heuristic does not detect black-frame failures (bug in the sweep heuristic).

## Follow-up (same day, after the sweep)

- **BSL "player model see-through" was a false positive of the check**, not a renderer bug: the colour check counted the
  default skin's light-blue shirt as sky (BSL gave the same 0.28 on OpenGL). The check now compares the F5 view with the
  same view with the player invisible (morning sun behind the camera, the owner's report). BSL, Complementary Unbound,
  superDuperVanilla, Nostalgia and Bliss on Metal: body area matches the background 0.00, screenshots inspected; Unbound
  F5 on Metal and OpenGL look identical. The owner's report is not reproduced by this scene.
- **OpenGL references for Nostalgia/photon/Solas/rethinking-voxels are unshaded because Iris cannot load them on macOS
  OpenGL 4.1** (Nostalgia: `#version 430`, photon: compute `deferred4_a`, Solas: `glClearTexImage` (GL 4.4) NPE,
  rethinking-voxels: Iris `ClosedFileSystemException` reading the id map); not a harness bug. `tools/pack-sweep.sh` now
  marks such references INVALID with the reason.
- `tools/pack-sweep.sh`: new status `broken` from `tools/FrameStats.java` (luminance mean/stddev/dark fraction of the
  world area): flags exactly Bliss and photon on this baseline. The DH message is info, not `partial`. `PACKS` accepts
  names with spaces (`'|'`-separated names or globs).
- Nostalgia: `blend override not supported yet` is gone (fullscreen-pass blend overrides implemented); Nostalgia only sets
  `blend.prepare=off` / `blend.deferred=off`, so its look does not change.
- superDuperVanilla: composite1 now translates (fragment output without `layout` got location 0 next to an explicit
  location 0). Its TAA/previous-frame history (colortex5) is now active; the Metal screenshots show coloured smears that
  still need an OpenGL comparison of the same scene.
- **superDuperVanilla smears and Bliss night sky / rain / exposure: one cause, fixed.** Not TAA, previous-frame uniforms
  or flips (all verified against Iris and by the new history test pack, which passes on Metal and OpenGL). Iris ignores a
  `texture.<stage>.colortexN` override in passes after one of the same stage wrote colortexN (`flippedAtLeastOnce` in
  `CustomTextureSamplerInterceptor`); Metal applied it everywhere. superDuperVanilla (`texture.composite.colortex0 =
  clouds.png`) bloomed its cloud map (coloured blotches); Bliss (`texture.composite.colortex6 = blueNoise.png`) bloomed
  blue noise (grass-like night sky) and fed it into its exposure (washed-out fog, strong rain streaks). Night and rain
  screenshots of both packs now match the OpenGL reference. Found with `-PirisDump=<dir>` (colortex buffer dumps).
- Bliss weather: a draw buffer without any fragment output (gl_FragData[0] never referenced) no longer gets an undefined
  value on Metal (empty write mask, as GL keeps the content). No visible change in the test scene.
