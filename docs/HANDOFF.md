# Handoff — Gore 0.5.0

Written 2026-10-18. Read this with `README.md`, which describes the mod as it now
behaves. Repo: `cumsauce/gore`, branch `main`, version `0.5.0`, tag `v0.5.0`.

## TL;DR

The mod now renders blood correctly and fluidly: hit mobs are no longer outlined,
wall decals no longer swim or pop, nothing z-fights, bodies are clearly bloody,
and blood pools, drips, jets and trails behave like liquid. Body stains now dry
and persist across restarts; wall and body blood fade out and rain washes it off;
water rinses bodies.

One caveat is deliberately unfinished: stains are still placed on an inferred
face rather than at the recorded hit point. The hit point is recorded and used
for the spray origin, and section "Hit-point placement" below contains everything
measured so far and a concrete plan.

## Build and verify

`gradlew` fails with "Unable to locate a Java Runtime" unless JAVA_HOME is set:

```sh
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./gradlew build --offline                     # compile, unit tests, jar
python3 tools/test_blood_atlas.py             # 10 atlas checks
./gradlew runClientGameTest --offline         # renders the mod, ~2 min
```

- Screenshots: `~/gore-run/clientGameTest/screenshots/`.
- The game-test JVM sets `-Dgore.seed=20261016` (replayable spray) and
  `-Dgore.dryScale=0.02` (drying/fading in seconds instead of minutes).
- The game test needs a real GL context; on this machine `runClient` cannot open
  a window, so the client game test is the only way to see the mod.
- `-Pgore.runDir=<local dir>` moves the run dir (the default is `~/gore-run`
  because Minecraft's world lock needs a filesystem that supports F_FULLFSYNC).

Last verified state: build green, all unit tests pass, atlas 10/10, game test
exits 0 with no mod exceptions, 21 screenshots.

## What changed in this cycle

### Render correctness (the original bug report)

1. **Mob outline.** `BodyDamageLayer` had the trailing `submitModel` arguments
   swapped. The signature is
   `(model, state, poseStack, renderType, light, overlay, tintedColor, uvMapping, outlineColor)`.
   It passed `0` as the tint and its red colour as the outline, so every hit mob
   went through the glow-outline pass and was never tinted. Fixed in
   `src/client/java/dev/lume/gore/client/BodyDamageLayer.java`.
2. **Swimming / disappearing decals.** Decals were submitted from
   `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`, which fires during a draw pass,
   after the frame's geometry was baked. They are now registered on
   `LevelRenderEvents.COLLECT_SUBMITS` (RETURN of `LevelRenderer.submitFeatures`),
   the point where the current frame is baked. This also fixed decals being
   skipped entirely on frames with no translucent terrain draw.
3. **Z-fighting.** `StainQuad` now offsets wall decals by 2 cm (`WORLD_OFFSET`)
   and body stains by 0.25 model units (`MODEL_OFFSET`), with a small extra
   separation between coplanar body stains. The old 6 mm sat inside depth-buffer
   precision.
4. **Missing wound atlas cells.** `StainWounds` picked cells 16–19 but the
   generator only drew cell 16, so 75% of hits rendered no wound. The generator
   now draws four tilted gashes; `tools/test_blood_atlas.py` checks all of them
   (`ART_CELLS = 20`).

### Body blood

- `BodyDamageLayer` re-tints the model toward dark blood with opacity from 55/255
  (one wound) to 195/255 (12+), using `entityTranslucentCull`.
- `StainWounds.create` returns a list: one gash (0.30–0.55 blocks) plus 2–6
  spatter flecks (0.08–0.18). `StainStore` merges only gash cells; spatter never
  merges.
- `BodyStainLayer` places each stain on a stable hashed point of the face, clamps
  size/offset to the model box, biases gashes up, and clamps body drips to the
  face's downward room. No drip on a horizontal face.
- Wounds dry from their `born` stamp (`Drying.dryness`) and drips age from it.

### Fluid feel

- Pools (atlas 12/13) are born small and spread over 40 ticks (`Flow.spread`,
  `Flow.poolSize`); floor hits in `Bleeding.decalAt` become pools.
- Arterial jets: `Gore.BLOOD_JET` + `client/BloodJetParticle`, emitting droplets
  for 9–14 ticks; `SprayModel.jetCount` opens them at severity 0.45+, two on a
  kill.
- `Bleeding.afterDeath`: large spread-under-corpse pool, ring of spatter, burst,
  two jets, store cleanup.
- `BloodTrail`: wounded entities on the ground leave small spreading pools while
  they walk; stride and mark size scale with `getBbWidth()`.
- Mist was one large opaque red disc; now many small translucent puffs.

### Persistence and ageing

- `GoreStain` grew `hitX/hitY/hitZ` (bytes, 1/32-block steps, encoded in the
  body frame as `rotateY(yRot - 180)`) and `born` (VAR_LONG). This is a network
  format change: client and server must be the same version.
- `StainStore` is now per-level `SavedData` keyed by **UUID** (entity ids are
  per-session and would attach old blood to the wrong mob). It is a list of
  `Entry(id, stains)`, not a keyed map, because `UUIDUtil.CODEC` is an int-stream
  and cannot be a JSON map key.
- `StainBinding` sweeps stained UUIDs every 10 ticks and re-sends a returning
  entity's stains under its current id. There is no entity-load event in this
  Fabric version.
- `Drying.fade` takes a dry stain to zero over 20 further minutes; the renderer
  applies it as alpha. `DecalAging` sweeps 6 decals/tick, removes faded ones and
  sends `GorePayloads.DecalsGonePayload`.
- Rain washes: `DecalAging` accumulates per-decal exposure in wall-clock ticks
  between samples (not one per visit); ~4 minutes of rain dissolves a stain.
- `Wash` removes a drowned creature's stains outright; a player in rain loses
  them at 5%/tick. Gear rinses as before.

## Gotchas that will bite

- **Do not move decal submission back to a draw event.** See item 2 above.
- **`LevelRenderEvents.COLLECT_SUBMITS` runs at the tail of `submitFeatures`**,
  where `context.poseStack()` is the fresh feature stack (camera-relative, world
  axes, blocks).
- **Model part frames are not world frames.** A cow's body cube is rotated 90°
  about X; `boxesOf` returns cube coordinates in model pixels; the root submit
  matrix is in blocks. Mixing them is easy and produces plausible-looking but
  wrong positions.
- **`StainStore` must not touch `Gore.id(...)` in static init** — the class is
  unit-tested and loading `Gore` initialises registries. `TYPE` builds its
  `Identifier` from `Gore.MOD_ID` directly.
- **`GoreStain` is a wire format.** Any field change breaks compatibility with
  older clients/servers; bump the version with it.
- `BloodTrail`, `StainBinding` and `Wash` iterate the stain store every tick (or
  every 10). At the current caps (16 stains/entity, 512 entities/level) it is
  cheap, but slice them the way `DecalAging` does if the caps grow.

## Hit-point placement — what was learned

The goal: put the wound where the blow landed instead of at the inferred face
centre. `GoreStain` already carries the point; `Bleeding.hitPoint()` reconstructs
it (projectile position, attacker's gaze clipped against the victim's AABB, or a
source position inside the body) and the spray origin now uses it.

Measured facts from diagnostics on a cow (`bodyRot = 0`, cow at z = -2.5, camera
at z = +0.5):

- The submitted root matrix has scale `(1, -1, -1)` and translation
  `(0, -0.119, -3.0)` — camera-relative, **blocks**, with the renderer's flip and
  the 1.501-block origin shift already in it.
- A chest hit at 0.844 blocks above the feet decodes to a root-space point of
  `y = 1.501 - 0.844 = 0.657`, which is correct (root frame is Y-down, origin
  1.5 blocks above the feet).
- `StainAnchor.boxesOf(part)` for a cow's body is e.g.
  `Box[x=-6, y=-10, z=-7, dx=12, dy=18, dz=10]` — **model pixels**, and the part
  is rotated 90° about X, so local ±Y maps to world front/back.
- Inverting the part matrix on a camera-space point returned **blocks**, while
  the boxes are **pixels**: a 16× factor. Multiplying by 16 put the point inside
  the box as expected.

What failed: after the ×16 conversion the face chosen by nearest-surface was
still the wrong side on cows and husks (stains appeared near the withers or hips
rather than at the hit). The face must be chosen by the *world-facing* direction,
not by local plane distance, and the resting-pose matrix rebuild used for the
point may not match the live stack.

Concrete plan:

1. Capture `poseStack.last().pose()` **after** `root.translateAndRotate` and
   `part.translateAndRotate` (the live stack the renderer draws with), invert it,
   map the camera-relative hit, and multiply by 16.
2. Verify with a temporary particle at the computed world point; it must coincide
   with the server's hit point within a few centimetres.
3. If the live matrix works, weld by caching the part-local point once per stain
   (keyed on the stain's identity) instead of using a resting-pose rebuild.
4. Choose the face by `dot(faceNormal, outward)` in the part frame, and only use
   distance as a tie-breaker.
5. Pin the mapping in a unit test: known `PartPose`, known root matrix, known
   world offset → asserted local point. The 16× bug would have failed it.

## Open items, in priority order

1. Commit/push/release (this handoff accompanies that).
2. Hit-point placement, using the plan above.
3. The blood coat does not dry: `BodyDamageLayer` opacity derives from the stain
   count only, so an old, brown wound still sits under a wet red wash. Weight it
   by `Drying.dryness` per stain.
4. `Wash` is instant: one tick in water erases a fight's wounds. Consider
   requiring a second or two of submersion.
5. Persistence and rain have unit coverage but no in-game proof. A save/quit/
   rejoin scene would prove `StainBinding`; `weather rain` plus
   `-Dgore.dryScale` would prove `DecalAging` in ~96 ticks.
6. On a quadruped the inferred face centre is between the front legs, so some
   spatter is hidden; this is exactly what hit-point placement fixes.

## Test and scene inventory

Unit tests: `DryingTest` (dryness, fade, rain clocks), `StainStoreTest`
(budget, merge rules, codec round-trip, legacy hit detection), `FlowTest` (drip
length, bead, spread, pool size), `SprayModelTest` (counts, jets, cone, radius),
`BloodAppearanceTest` (tint, sheen, `unitDisk` spread), `CubeProjectionTest`,
`DecalMergeTest`, `DecalStoreTest`, `BleedingDripTest`, `BallisticsTest`,
`SilhouetteTest`, `ParticleLookTest`.

Client game test scenes, in order: `gore-bleeding`, `gore-body`,
`gore-body-turned`, `gore-gear`, `gore-splatters`, `gore-tail-up`,
`gore-tail-down`, `gore-spray`, `gore-spray-settled`, `gore-drip-05/30/120`,
`gore-dry-wet/dry`, `gore-pool-03/45`, `gore-body-close`,
`gore-body-humanoid`, `gore-body-humanoid-dry`, `gore-death`,
`gore-death-settled`.
