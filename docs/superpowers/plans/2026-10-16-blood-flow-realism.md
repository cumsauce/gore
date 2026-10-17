# Blood Flow Realism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Gore's blood follow fluid physics — a directed, drag-decelerated spray with a real droplet size distribution; wall runs that accelerate, thin, shed a droplet and merge; and stains that dry from wet red to dark brown — so it stops reading as random particles and linear smears.

**Architecture:** The server stays authoritative and keeps emitting `Decal` records and particle spawns; all animation stays client-side and is derived from `Decal.born` game time (existing pattern, no new network traffic). All physics lives in small, pure, unit-testable classes in the `main` source set (`SprayModel`, `Ballistics`, `Flow`, `Drying`, `DecalFrame`, `DecalMerge`) that both server code and client renderers/particles call. Rendering stays on the `submitModelPart` atlas-quad path; drying uses the `submitModelPart(..., int color)` tint overload; drip growth is done by pose-stack scaling of two atlas cells (run strip + bead).

**Tech Stack:** Java 25, Fabric Loader 0.19.5 / Fabric API 0.162.0+26.3, Minecraft 26.3, Loom 1.18.2, JUnit 5 (`./gradlew test`), Fabric client game test (`./gradlew runClientGameTest`), stdlib-only Python 3 for the atlas generator and pixel checks.

**Spec:** `docs/superpowers/specs/2026-10-16-realistic-blood-design.md`. This plan **supersedes Phases 2 and 3 of `docs/superpowers/plans/2026-10-16-realistic-blood.md`** (Phase 1 of that plan is done and untouched). Reason: Phase 2 as written produced a linear, bead-at-bottom drip and no drying; Phase 3 (pool meniscus, cast-off, porous wicking) is deferred — see "Deferred" at the end — because the user's complaint is spray and flow, and those are where the physics is absent. Task 8 marks the old plan as superseded.

## Global Constraints

- There is no shader path for mods on 26.3. `RenderPipelines.register` is private. Anything animated must be animated by moving/rescaling geometry or by choosing different sprites over time — driven by game time, in Java.
- `SubmitNodeCollector.submitCustomGeometry` is inert on 26.3. All custom world geometry goes through `submitModelPart(...)`. Do not use `submitCustomGeometry`.
- Decals are textured quads from the atlas `src/main/resources/assets/gore/textures/decal/blood_atlas.png` (256×256, 4×4 grid of 64 px cells). They are not meshes, not particles, not shader effects.
- Particles are vanilla-style particles registered through `ParticleProviderRegistry`.
- Everything is client-rendered from server-sent state. The server is authoritative. The client animates from the `born` timestamp. No per-frame network traffic.
- No damage, no gameplay effect, ever. Cosmetics only.
- Do not add a Mixin. The mod has none. (No task in this plan needs one.)
- If a task changes what an atlas cell means it changes **both** `tools/gen_blood_atlas.py` and the Java that reads the cell (Task 5 does this for cells 14–15).
- Build: `cd "/Volumes/My Shared Files/mc/gore"` then `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew <task>`. Never a system Gradle. `build` ≈ 10 s warm; `runClientGameTest` ≈ 45–55 s (use a 600000 ms timeout).
- Headless VM: `runClient` cannot open a window. Visual verification is only via `runClientGameTest` screenshots in `~/gore-run/clientGameTest/screenshots/`, checked with pixel statistics. Steps that only a human can judge are marked **needs a human**.
- Check every Minecraft/Fabric signature against `~/.gradle/caches/fabric-loom/26.3/` with `/opt/homebrew/opt/openjdk@25/bin/javap -cp <jar> <class>` before relying on it. Known 26.3 facts used below: `Identifier` (not `ResourceLocation`); `OrderedSubmitNodeCollector.submitModelPart(ModelPart, PoseStack, RenderType, int light, int overlay, UvMapping)` and overload `(..., UvMapping, int color)`; `ARGB.color(int a,int r,int g,int b)`; `PoseStack.rotate(Quaternionfc)`, `translate(float,float,float)`, `scale(float,float,float)`; `RandomSource.create(long)`.
- Body stains (`BodyStainLayer`, `StainWounds`, `StainStore`, `GoreStain`) use atlas cells 0–11 and `StainQuad.submit(..., Vector3f outward)`; every task must leave that call and those cells working. `./gradlew build` must pass at the end of every task.
- Units: 1 block = 1 m, 1 tick = 1/20 s. Gravity `9.81 m/s²` = `0.0245 blocks/tick²`.

## Review Focus

Each line names an input the spec does not discuss, the behaviour a reasonable person expects, and the task whose test pins it.

1. **A ray hits a floor or ceiling and tries to start a drip** — no drip is created (blood cannot run along a horizontal face; pools cover floors). Pinned by `BleedingDripTest.dripDecal_returnsNullForUpAndDown` in Task 5.
2. **Two drips land within a few centimetres on the same face** — they merge into one heavier run instead of two parallel threads; the decal count does not grow. Pinned by `DecalMergeTest.drips_withinTolerance_merge` and `DecalStoreTest.addOrMerge_doesNotGrowCountWhenMerging` in Task 7.
3. **A hit with minimum severity (0.08: a scratch)** — a few droplets, no mist, no wall stains. Pinned by `SprayModelTest.counts_atMinimumSeverity` in Task 3 (stains already gated by `SPLATTER_SEVERITY = 0.22f`).
4. **A decal whose `born` is in the future (clock skew, world reload)** — it renders as a fresh, full-size wet stain and a zero-length drip; nothing throws or goes negative. Pinned by `FlowTest.length_isZeroForNegativeAge` (Task 5) and `DryingTest.dryness_isZeroForNegativeAge` (Task 6).
5. **A decal saved before this plan (`born == 0`)** — treated as old, fully dried blood, still a static stain, never a drip. Pinned by `DryingTest.dryness_isOneForLegacyBornZero` (Task 6) and the `variant == 14` drip detection test `DecalRendererKindTest.isDrip_dependsOnVariantNotBorn` (Task 5).
6. **A victim standing in water** — spray particles vanish within a couple of ticks (blood disperses); stains still land on nearby blocks because rays already use `ClipContext.Fluid.NONE`. Particle-in-fluid removal is implemented in Task 4; it cannot be unit tested (needs `ClientLevel`) — **needs a human**, noted in Task 4 Step 9.

---

### Task 1: Deterministic game-test harness and pixel tooling

**Files:**
- Modify: `src/main/java/dev/lume/gore/Bleeding.java` (the `RandomSource random = victim.getRandom();` line in `afterDamage`)
- Modify: `build.gradle` (the existing `afterEvaluate { runs.configureEach { ... } }` block)
- Create: `tools/pixelstat.py`
- Modify: `src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java` (no scene changes yet; only confirm it still runs)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - JVM system property `gore.seed` (long). When set, `Bleeding.afterDamage` uses `RandomSource.create(seed + HIT_COUNTER.getAndIncrement())` where `HIT_COUNTER` is `private static final AtomicLong HIT_COUNTER = new AtomicLong()`; when absent it uses `victim.getRandom()` as today. Exposed as `static RandomSource hitRandom(LivingEntity victim)` in `Bleeding`.
  - `tools/pixelstat.py <png> <r> <g> <b> <tol> [x0 y0 x1 y1]` — stdlib only; counts pixels whose |R−r|,|G−g|,|B−b| are all ≤ tol inside the optional region; prints exactly one line: `size=WxH count=N bbox=x0,y0,x1,y1 centroid=cx.x,cy.y` (`bbox=none centroid=none` when N = 0; centroid printed with one decimal). Copy `load_png` from `/private/var/folders/tb/8d_73tkx5hndlyfwjng3srp80000gn/T/opencode/pngtool.py` into the script (zlib + struct PNG reader; no PIL).
  - `tools/pixelstat.py --blood <png> [x0 y0 x1 y1]` — the same output format, but counts pixels with `R ≥ 45`, `R ≥ 1.8·G`, `R ≥ 1.8·B`. This matches blood from wet bright red `(120,14,14)` through dried dark brown `(63,12,8)` while rejecting grey stone and shadows, and is the mode **every blood-presence/geometry gate below uses**. (Fixed-RGB probes are only for the drying gate, which deliberately measures the colour shift.)
  - Game test JVM args (clientGameTest run config only): `-Dgore.seed=20261016 -Dgore.dryScale=0.02` (the second is read by Task 6; harmless until then).

- [ ] **Step 1: Add `hitRandom` to `Bleeding`**

`static RandomSource hitRandom(LivingEntity victim)` reading `System.getProperty("gore.seed")`; replace the `victim.getRandom()` call in `afterDamage` with `hitRandom(victim)`.

- [ ] **Step 2: Add the VM args to the clientGameTest run**

Inside the existing `afterEvaluate { runs.configureEach { ... } }` block in `build.gradle`, add `if (name == "clientGameTest") { vmArg "-Dgore.seed=20261016"; vmArg "-Dgore.dryScale=0.02" }`.

- [ ] **Step 3: Create `tools/pixelstat.py`**

Run: `python3 tools/pixelstat.py src/main/resources/assets/gore/textures/decal/blood_atlas.png 120 14 14 40 128 192 191 255`
Expected: exactly `size=256x256 count=468 bbox=152,196,167,252 centroid=159.5,229.4` (the current, pre-change cell‑14 drip strip plus its bulb; these are the measured values of the checked-in atlas — if they differ, `pixelstat.py` decodes PNGs differently from `gen_blood_atlas.py`'s writer and that must be fixed before continuing).

- [ ] **Step 4: Build**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the game test twice and compare the splatter screenshot**

Run (twice, 600000 ms timeout each): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
After each run: `python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0004_gore-splatters.png` (at this point in the plan the blood is still wet, so a fixed red probe would also work; `--blood` keeps one probe valid for every later task, where drying changes the colour).
Expected: both runs `BUILD SUCCESSFUL`; the two `count=` values differ by less than 5 % of the larger (decal placement is now seeded; residual difference is lighting/particles). Record both numbers in the commit message body.

- [ ] **Step 6: Commit**

```bash
git add build.gradle tools/pixelstat.py src/main/java/dev/lume/gore/Bleeding.java
git commit -m "test: seed spray RNG via gore.seed and add pixelstat tool"
```

---

### Task 2: Make stain tails point along travel (fix decal orientation)

Physics: the elongated end of an impact stain (the "tail") lies along the droplet's travel direction, away from the wound (spec §tails). The server encodes this as `rot = degrees(atan2(p·axis2, p·axis1))` where `p` is travel projected onto the face. The client currently orients the quad with `Quaternionf.rotateTo(MODEL_FRONT, outward)` then `rotateDegrees(ZP, rot)`; `rotateTo` yields a different in-plane basis per face (verified by hand against `ModelPart$Vertex` and `Decal.axis1/axis2`), so the tail direction is face-dependent and wrong. Fix: build the orientation explicitly from the face basis.

**Files:**
- Create: `src/main/java/dev/lume/gore/DecalFrame.java`
- Create: `src/test/java/dev/lume/gore/DecalFrameTest.java`
- Modify: `src/client/java/dev/lume/gore/client/StainQuad.java`
- Modify: `src/client/java/dev/lume/gore/client/DecalRenderer.java`
- Modify: `src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java`

**Interfaces:**
- Consumes: `Decal.axis1(Direction)`, `Decal.axis2(Direction)` (existing; `axis1 × axis2` = outward normal).
- Produces:
  - `public final class DecalFrame` with
    - `public static Vec3 tail(Direction face, float rotDeg)` = `axis1·cos(rot) + axis2·sin(rot)`.
    - `public static Quaternionf orientation(Direction face, float rotDeg)` — rotation whose columns map model X → `tail × normal`, model Y → `−tail`, model Z → `−normal` (determinant +1; model −Y is the atlas "tail-up" direction, model −Z is the textured front of the `ModelPart.Cube`). Build with `new Quaternionf().setFromNormalized(new Matrix3f(xCol, yCol, zCol))`.
  - `StainQuad.submitOnFace(SubmitNodeCollector collector, PoseStack poseStack, int variant, float size, float rot, int light, Direction face, int color)` — same as `submit` but uses `DecalFrame.orientation(face, rot)` instead of `rotateTo` + `rotateDegrees`, and passes `color` to the `submitModelPart(..., UvMapping, int color)` overload. `color = 0xFFFFFFFF` means untinted (Task 6 supplies real tints).
  - The existing `StainQuad.submit(..., Vector3f outward)` is **left unchanged** (body stains).

- [ ] **Step 1: Write the failing test `DecalFrameTest`**

```java
@ParameterizedTest @EnumSource(Direction.class)
void orientation_mapsModelTailToTailVector(Direction face) {
    for (float rot : new float[]{0f, 37f, 90f, 180f, -90f, 270f}) {
        Quaternionf q = DecalFrame.orientation(face, rot);
        Vector3f tail = q.transform(new Vector3f(0, -1, 0));
        Vec3 expected = DecalFrame.tail(face, rot);
        assertEquals(expected.x, tail.x, 1e-4); assertEquals(expected.y, tail.y, 1e-4); assertEquals(expected.z, tail.z, 1e-4);
        Vector3f front = q.transform(new Vector3f(0, 0, -1));
        Vec3 n = Decal.axis1(face).cross(Decal.axis2(face));
        assertEquals(n.x, front.x, 1e-4); assertEquals(n.y, front.y, 1e-4); assertEquals(n.z, front.z, 1e-4);
    }
}
@Test void tail_rot90_onSouthFace_pointsUp() {
    Vec3 t = DecalFrame.tail(Direction.SOUTH, 90f);
    assertEquals(0.0, t.x, 1e-6); assertEquals(1.0, t.y, 1e-6); assertEquals(0.0, t.z, 1e-6);
}
```

- [ ] **Step 2: Run it, see it fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.DecalFrameTest'`
Expected: `BUILD FAILED` with compilation error `cannot find symbol ... DecalFrame`.

- [ ] **Step 3: Implement `DecalFrame`**

- [ ] **Step 4: Run it, see it pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Add `StainQuad.submitOnFace` and switch `DecalRenderer.submit` to it**

In `DecalRenderer.submit`, the non-drip branch calls `StainQuad.submitOnFace(collector, poseStack, decal.variant(), decal.size(), decal.rot(), light, face, 0xFFFFFFFF)`. Leave the drip branch as-is for now (Task 5 rewrites it).

- [ ] **Step 6: Add the tail-orientation scenes to the game test**

Append to the end of `runTest`, after the `gore-splatters` screenshot:
1. `fill fx-4 fy fz+4 fx+4 fy+5 fz+4 stone` (a clean second wall behind the player; the cow was teleported to `fz+6`, now hidden behind it).
2. Look at `(fx+0.5, fy+1.5, fz+4)` (use `context.getInput().lookAt(Vec3)`; if that overload does not exist, call `mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, vec)` inside `runOnClient`). `waitTicks(5)`.
3. `runOnClient`: `DecalManager.clear(); DecalRenderer.clearDrips(); DecalManager.addDecals(List.of(new Decal(fx, fy+1, fz+4, Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 90f, 9, 0L)))` — variant 9 = elongation band 3, density 0 (long ellipse with tail, no satellites). **Face is NORTH**, not SOUTH: the player stands at `fz` and looks toward `fz+4`, so the wall surface facing them, and the only surface a decal can be visible on, is the block's `-Z` (NORTH) face. A `SOUTH`-face decal at `fz+4` is buried inside the wall and renders nothing. `waitTicks(5)`; `takeScreenshot("gore-tail-up")`.
4. Same with `rot = -90f`; `takeScreenshot("gore-tail-down")`.
5. `runOnClient`: `DecalManager.clear()` (so later scenes start clean).

If the gametest source set cannot see `dev.lume.gore.client.*`, add `dependencies { gametestImplementation sourceSets.client.output }` (name per Loom's generated configuration; check with `./gradlew dependencies --configuration gametestCompileClasspath`).

- [ ] **Step 7: Run the game test and check the centroid moves**

Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
Then:
`python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0005_gore-tail-up.png`
`python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0006_gore-tail-down.png`
Expected: both `count=` > 200; both bboxes taller than wide (`y1−y0 > 1.3·(x1−x0)`); centroid **y of tail-up is greater** than centroid y of tail-down by at least 4 px (screen y grows downward; the atlas bulb sits below the cell centre when the tail is up, so the mass centroid is below the anchor when the tail points up and above it when it points down). Reference run (after Tasks 4–7 landed): both count 214; bboxes `422,227,431,262` and `422,217,431,252` (9 wide × 35 tall); centroids y 246.1 vs 232.9, Δ 13.2. If the inequality is reversed, `DecalFrame.orientation` has the tail sign flipped — fix it, do not change the test.

- [ ] **Step 8: Build and commit**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build` → `BUILD SUCCESSFUL`.

```bash
git add src/main/java/dev/lume/gore/DecalFrame.java src/test/java/dev/lume/gore/DecalFrameTest.java src/client/java/dev/lume/gore/client/StainQuad.java src/client/java/dev/lume/gore/client/DecalRenderer.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java build.gradle
git commit -m "fix: orient stain tails along travel using an explicit face basis"
```

---

### Task 3: Spray model — directed cone, droplet size distribution, server emission

Physics: impact spatter leaves the wound as a cone centred on the force direction (half-angle ≈ 20°); droplet diameters are log-normal (a few millimetre-sized drops, many sub-millimetre ones; mist is 40–120 µm); all leave at roughly the same speed (8–12 m/s for a blunt/edged impact). Today `sendParticles(count>0)` randomises position **and velocity** isotropically — that is the "random puff".

**Files:**
- Create: `src/main/java/dev/lume/gore/SprayModel.java`
- Create: `src/test/java/dev/lume/gore/SprayModelTest.java`
- Modify: `src/main/java/dev/lume/gore/Bleeding.java` (`spawnParticles`, and make `coneDirection` package-private `static`)

**Interfaces:**
- Consumes: `Bleeding.coneDirection(Vec3 dir, RandomSource random, float spread)` (existing; make it package-private so `SprayModel` can call it, or move it into `SprayModel` and have `Bleeding.collectDecals` call `SprayModel.coneDirection` — choose the move).
- Produces: `public final class SprayModel` with constants and methods
  - `public static final float DROP_SPREAD = 0.35f;` (tan of cone half-angle → 19.3°)
  - `public static final float MIST_SPREAD = 0.30f;`
  - `public static final float MIST_SEVERITY = 0.35f;`
  - `public static final double DROP_RADIUS_MEDIAN_M = 0.8e-3;`, `DROP_RADIUS_SIGMA = 0.6`, `DROP_RADIUS_MIN_M = 0.15e-3`, `DROP_RADIUS_MAX_M = 2.5e-3`
  - `public static final double MIST_RADIUS_MIN_M = 2e-5;`, `MIST_RADIUS_MAX_M = 6e-5`
  - `public static int dropCount(float severity)` = `Math.round(6 + 18 * severity)`
  - `public static int mistCount(float severity)` = `severity < MIST_SEVERITY ? 0 : Math.round(4 + 10 * severity)`
  - `public static List<Vec3> dropVelocities(Vec3 dir, float severity, RandomSource random)` — `dropCount` vectors, each `coneDirection(dir, random, DROP_SPREAD)` scaled by `(0.35 + 0.45*severity) * (0.7 + 0.3*random.nextFloat())` blocks/tick
  - `public static List<Vec3> mistVelocities(Vec3 dir, float severity, RandomSource random)` — `mistCount` vectors, `coneDirection(dir, random, MIST_SPREAD)` scaled by `0.30 + 0.20*random.nextFloat()`
  - `public static double dropRadiusM(RandomSource random)` = `clamp(exp(ln(MEDIAN) + SIGMA * random.nextGaussian()), MIN, MAX)`
  - `public static double mistRadiusM(RandomSource random)` = uniform in `[MIST_RADIUS_MIN_M, MIST_RADIUS_MAX_M]`
  - `public static Vec3 coneDirection(Vec3 dir, RandomSource random, float spread)` (moved from `Bleeding`, body unchanged)

- [ ] **Step 1: Write the failing test `SprayModelTest`**

```java
RandomSource r = RandomSource.create(20261016L);
@Test void dropVelocities_lieInsideCone_andHaveImpactSpeed() {
    Vec3 dir = new Vec3(1, 0, 0);
    List<Vec3> v = SprayModel.dropVelocities(dir, 0.6f, r);
    assertEquals(17, v.size());
    double meanSpeed = 0;
    for (Vec3 each : v) {
        double angle = Math.toDegrees(Math.acos(each.normalize().dot(dir)));
        assertTrue(angle <= 20.0, "angle " + angle);
        meanSpeed += each.length();
    }
    meanSpeed /= v.size();
    assertTrue(meanSpeed > 0.45 && meanSpeed < 0.60, "mean speed " + meanSpeed);
}
@Test void counts_atMinimumSeverity() {
    assertEquals(7, SprayModel.dropCount(0.08f));
    assertEquals(0, SprayModel.mistCount(0.08f));
    assertEquals(10, SprayModel.mistCount(0.6f));
}
@Test void dropRadius_isLogNormalWithFewLargeDrops() {
    double[] s = new double[2000]; int large = 0;
    for (int i = 0; i < s.length; i++) { s[i] = SprayModel.dropRadiusM(r); if (s[i] > 2.0e-3) large++; assertTrue(s[i] >= 0.15e-3 && s[i] <= 2.5e-3); }
    Arrays.sort(s);
    assertTrue(s[1000] > 0.65e-3 && s[1000] < 0.95e-3, "median " + s[1000]);
    assertTrue(large >= 40 && large <= 240, "large " + large);   // 2 %–12 %
}
@Test void mistRadius_isWithinBounds() {
    for (int i = 0; i < 500; i++) { double m = SprayModel.mistRadiusM(r); assertTrue(m >= 2e-5 && m <= 6e-5); }
}
```

- [ ] **Step 2: Run it, see it fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.SprayModelTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... SprayModel`.

- [ ] **Step 3: Implement `SprayModel`; move `coneDirection` out of `Bleeding`**

- [ ] **Step 4: Run it, see it pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Rewrite `Bleeding.spawnParticles(ServerLevel level, Vec3 wound, Vec3 dir, float severity, RandomSource random)`**

Delete the `drops`/`mist`/`darts` blocks. New body: for each `v` in `SprayModel.dropVelocities(dir, severity, random)` call `level.sendParticles(Gore.BLOOD_DROP, wound.x, wound.y, wound.z, 0, v.x, v.y, v.z, 1.0)`; for each `v` in `SprayModel.mistVelocities(dir, severity, random)` call `level.sendParticles(Gore.BLOOD_MIST, wound.x, wound.y, wound.z, 0, v.x, v.y, v.z, 1.0)`. (`count == 0` makes the `dx,dy,dz × speed` arguments the exact velocity — the existing dart path already relies on this.) Remove the now-unused `MIST_SEVERITY` constant from `Bleeding` if nothing else references it.

- [ ] **Step 6: Build and commit**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build` → `BUILD SUCCESSFUL`.

```bash
git add src/main/java/dev/lume/gore/SprayModel.java src/test/java/dev/lume/gore/SprayModelTest.java src/main/java/dev/lume/gore/Bleeding.java
git commit -m "feat: emit spray as a directed cone with log-normal droplet sizes"
```

---

### Task 4: Ballistics — drag-decelerated droplets, hanging mist, lifecycle split

Physics: a droplet of radius *r* in air feels quadratic drag `a = (3/8)·Cd·(ρ_air/ρ_blood)·v²/r` (Cd 0.5, ρ_air 1.2, ρ_blood 1060 kg/m³) → in blocks/tick units `Δv = −k|v|v` with `k = 2.123e-4 / r_m` per tick. For mist (r ≈ 40 µm) the Stokes term dominates: linear decay rate `9μ/(2ρr²) = 7.64e-8 / r²` per second (μ = 1.8e-5 Pa·s) → per tick rate `c = 3.82e-9 / r²`. A 1 mm droplet has terminal speed ≈ 0.34 blocks/tick (7 m/s) and flies metres; a 40 µm mist droplet stops within ~0.3 blocks in two ticks, hangs, and evaporates in under 1.5 s. The vanilla `Particle.tick()` (`yd −= 0.04·gravity; v *= friction`) cannot express this, so both particles override `tick()` fully.

**Files:**
- Create: `src/main/java/dev/lume/gore/Ballistics.java`
- Create: `src/test/java/dev/lume/gore/BallisticsTest.java`
- Modify: `src/client/java/dev/lume/gore/client/BloodDropParticle.java`
- Modify: `src/client/java/dev/lume/gore/client/BloodMistParticle.java`
- Modify: `src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java`

**Interfaces:**
- Consumes: `SprayModel.dropRadiusM(RandomSource)`, `SprayModel.mistRadiusM(RandomSource)` (Task 3).
- Produces: `public final class Ballistics` with
  - `public static final double GRAVITY = 0.0245;` (blocks/tick²)
  - `public static final double QUAD_DRAG = 2.123e-4;` (× 1/r_m → k per tick)
  - `public static final double STOKES = 3.82e-9;` (× 1/r_m² → c per tick)
  - `public static Vec3 step(Vec3 v, double radiusM)` — in this order: `v = v.add(0, -GRAVITY, 0)`; `k = QUAD_DRAG / radiusM`; `v = v.scale(1.0 / (1.0 + k * v.length()))`; `c = STOKES / (radiusM*radiusM)`; `v = v.scale(Math.exp(-c))`. (The implicit form `v/(1+k|v|)` is unconditionally stable for any speed.)
  - Particle behaviour (client, not unit-tested):
    - `BloodDropParticle`: field `double radiusM = SprayModel.dropRadiusM(random)`; `quadSize = (float)(0.02 + 0.04 * radiusM / 1e-3)`; `lifetime = 60`; `hasPhysics = true`; `tick()` does: `age++`, `if (age >= lifetime || onGround && age > landedAt + 1) remove()` (record `landedAt` on first `onGround`), else `Vec3 v = Ballistics.step(new Vec3(xd, yd, zd), radiusM)`; assign back; `move(xd, yd, zd)`; if `level.getFluidState(BlockPos.containing(x, y, z)).isEmpty()` is false → `remove()`. No `super.tick()`. Alpha stays 1.0 until landing, then the particle is removed (the server decal is the lasting mark).
    - `BloodMistParticle`: `radiusM = SprayModel.mistRadiusM(random)`; `quadSize = (float)(0.08 + 0.08 * (radiusM - 2e-5) / 4e-5)`; `lifetime = 12 + random.nextInt(13)` (0.6–1.2 s); `hasPhysics = false`; `tick()`: `age++`, remove at lifetime; `Ballistics.step(v, radiusM)` then add a random walk `(gauss*0.002, gauss*0.002, gauss*0.002)`; `move`; `quadSize *= 1.03f` (dispersion); `alpha = 0.9f * (float)Math.exp(-3.0 * age / lifetime)` (evaporation); `setSpriteFromAge(sprites)`. No `super.tick()`.

- [ ] **Step 1: Write the failing test `BallisticsTest`**

```java
@Test void millimetreDrop_reachesTerminalSpeedNearPointThreeFour() {
    Vec3 v = Vec3.ZERO;
    for (int i = 0; i < 200; i++) v = Ballistics.step(v, 1e-3);
    assertTrue(-v.y > 0.30 && -v.y < 0.40, "terminal " + v.y);
}
@Test void millimetreDrop_deceleratesHorizontallyButKeepsFlying() {
    Vec3 v = new Vec3(0.6, 0, 0);
    for (int i = 0; i < 10; i++) v = Ballistics.step(v, 1e-3);
    assertTrue(v.x > 0.22 && v.x < 0.30, "vx after 10 ticks " + v.x);
}
@Test void mist_stopsWithinTwoTicksAndHangs() {
    Vec3 v = new Vec3(0.4, 0, 0); double travelled = 0;
    for (int i = 0; i < 20; i++) { v = Ballistics.step(v, 4e-5); travelled += v.x; }
    assertTrue(travelled < 0.5, "mist travelled " + travelled);
    assertTrue(v.length() < 0.02, "mist speed after 20 ticks " + v.length());
}
@Test void step_isStableForAbsurdSpeeds() {
    Vec3 v = Ballistics.step(new Vec3(1000, 1000, 1000), 1.5e-4);
    assertTrue(Double.isFinite(v.length()) && v.length() < 1000);
}
```

- [ ] **Step 2: Run it, see it fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.BallisticsTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... Ballistics`.

- [ ] **Step 3: Implement `Ballistics.step`**

- [ ] **Step 4: Run it, see it pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Rewrite `BloodDropParticle` and `BloodMistParticle` per the Interfaces block**

Keep the `Provider` classes and `getLayer()` as they are; keep colours `(0.55, 0.06, 0.055)` for drops and `(0.62, 0.075, 0.07)` for mist. Remove `gravity`/`friction` assignments (unused once `super.tick()` is not called).

- [ ] **Step 6: Add the spray scene to the game test**

Append after the Task 2 scenes:
1. `kill @e[type=minecraft:cow]`; `summon minecraft:cow fx+0.5 fy fz-2.5 {NoAI:1b,Health:200f,attributes:[{id:"minecraft:max_health",base:200}]}` (copy the exact NBT the existing summon uses); `waitTicks(5)`; look at the cow; `waitTicks(5)`.
2. `damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack at fx-3 fy+1 fz-2.5` — `impactDirection` falls back to the damage-source position, so the blow travels **+X**, which is screen-right for a player facing −Z.
3. `waitTicks(2)`; `takeScreenshot("gore-spray")`.
4. `waitTicks(40)`; `takeScreenshot("gore-spray-settled")`.

- [ ] **Step 7: Run the game test and check the spray is directed**

Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
`python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0007_gore-spray.png`
`python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0008_gore-spray-settled.png`
Expected: `count(0007) − count(0008) ≥ 2000` (the airborne spray cloud was rendered at +2 ticks and has landed or been removed by +42 ticks). Reference run (after the HUD change): 14629 − 194 = 14435.
**Ruling (this gate replaces the earlier `x1 ≥ 0.72·W` / `x0 ≥ 0.25·W` band assertions, which are not measurable in this scene):** the room is already covered by the previous scene's wall stains, and at +2 ticks the ±19° cone is only ~1 block wide, so the airborne pixels fill the frame and no band or centroid separates the jet. The spray's *direction* is proven where it belongs — `SprayModelTest.dropVelocities_lieInsideCone` asserts every drop velocity is within 20° of `impactDirection`, and `BallisticsTest` proves the motion — so this pixel gate only needs to show the cloud exists and dissipates. The visual "does it read as a directed jet" judgement is a human check in Task 8.

- [ ] **Step 8: Build and commit**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build` → `BUILD SUCCESSFUL`.

```bash
git add src/main/java/dev/lume/gore/Ballistics.java src/test/java/dev/lume/gore/BallisticsTest.java src/client/java/dev/lume/gore/client/BloodDropParticle.java src/client/java/dev/lume/gore/client/BloodMistParticle.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java
git commit -m "feat: drag-based droplet ballistics and short-lived hanging mist"
```

- [ ] **Step 9: needs a human** — in a windowed session, hit a mob standing in water and confirm the droplets vanish within two ticks and no particles float on the surface. Not shell-checkable.

---

### Task 5: Flow — accelerating, thinning, bead-shedding drips with top-anchored growth

Physics: a run of blood on a vertical wall starts from rest and accelerates under gravity (time constant ≈ 0.3 s), then slows as the finite volume behind it depletes and the film thins (time constant ≈ 2 s). Its length follows the integral of `v(t) = (1 − e^{−t/A}) · e^{−t/D}` with `A = 6` ticks, `D = 40` ticks, over `T = 120` ticks (6 s). As it stretches it thins; the leading bead keeps the volume that has not been left behind as film (bead scale `∛(1 − 0.7·f)`, mass conservation) and when it has stretched past 60 % it pinches off a droplet that falls freely. Current defects: linear growth, `translate(0, −BLOCKS/2, 0)` applied before scaling (moves the quad two world blocks), bead scaled with the strip, `DRIPPED` keyed by `born` (one droplet per hit, not per drip).

**Files:**
- Create: `src/main/java/dev/lume/gore/Flow.java`
- Create: `src/test/java/dev/lume/gore/FlowTest.java`
- Create: `src/test/java/dev/lume/gore/BleedingDripTest.java`
- Modify: `tools/gen_blood_atlas.py` (cells 14 and 15), regenerate `src/main/resources/assets/gore/textures/decal/blood_atlas.png`
- Modify: `src/client/java/dev/lume/gore/client/StainQuad.java` (`submitDrip`)
- Modify: `src/client/java/dev/lume/gore/client/DecalRenderer.java`
- Modify: `src/client/java/dev/lume/gore/client/BloodItemDecorations.java:29` (`rng.nextInt(16)` → `rng.nextInt(12)`: drip/pool cells never belong on an item icon)
- Modify: `src/main/java/dev/lume/gore/Bleeding.java` (`dripDecal` variant, visibility)
- Modify: `src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java`

**Interfaces:**
- Consumes: `DecalFrame.orientation(Direction, float)`, `DecalFrame.tail(Direction, float)`, `StainQuad.submitOnFace(...)` (Task 2).
- Produces:
  - Atlas contract change: **cell 14 = run strip** (6 px wide, x 29–34, full cell height y 0–63, colour BASE with DARK 1‑px edges), **cell 15 = bead** (ellipse a = 9, b = 8 centred at (31.5, 31.5)). The old "two drip lengths" meaning is gone. `Bleeding.dripDecal` always emits `variant = 14`.
  - `public final class Flow`: `public static final int A_TICKS = 6, D_TICKS = 40, T_TICKS = 120;` `public static final float BREAK_OFF = 0.6f;` `public static final float THIN = 0.35f;`
    - `public static float length(long ageTicks)` — 0 for `age ≤ 0`, 1 for `age ≥ T_TICKS`, else `F(age)/F(T)` with `F(t) = D(1 − e^{−t/D}) − (AD/(A+D))(1 − e^{−t(A+D)/(AD)})`.
    - `public static float beadScale(float f)` = `(float)Math.cbrt(1 − 0.7·f)`.
    - `public static float widthScale(float f)` = `1 − THIN·f`.
  - `DecalRenderer`: `public static boolean isDrip(Decal d)` = `d.variant() == 14` (replaces `born() > 0`); `private static final Set<Decal> DRIPPED` (record equality) — `clearDrips()` unchanged.
  - `StainQuad.submitDrip(SubmitNodeCollector collector, PoseStack poseStack, float size, float rot, int light, Direction face, float f, int color)` — pose order for the run (model units, `s = size / BLOCKS`): `translate(normal·OFFSET)`, `rotate(DecalFrame.orientation(face, rot))`, `scale(s, s, s)`, `translate(0, 2f, 0)`, `scale(Flow.widthScale(f), f, 1)`, submit `VARIANTS[14]`; the quad then spans model y ∈ [0, 4f], i.e. it hangs from the anchor **downward** (with `rot` pointing the tail up, model +Y is world down). Then pop/push and for the bead: `translate(normal·OFFSET)`, `rotate(...)`, `scale(s,s,s)`, `translate(0, 4f + 2·Flow.beadScale(f), 0)`, `scale(Flow.beadScale(f))` uniformly, submit `VARIANTS[15]`. Both with the `color` overload. (The `2·beadScale(f)` term puts the bead's **top edge** at the run's bottom, so the bead always hangs at or below the anchor and never bulges above the wound; the run's top edge is pinned at world offset 0 = the anchor, and its length is `size·f`.)
  - Break-off droplet: in `DecalRenderer.submit`, when `isDrip` and `f ≥ Flow.BREAK_OFF` and `DRIPPED.add(decal)` → `level.addParticle(Gore.BLOOD_DROP, bead.x, bead.y, bead.z, 0, -0.02, 0)` with `bead = center − DecalFrame.tail(face, rot) · (f · size)`.

- [ ] **Step 1: Write the failing tests**

`FlowTest`:
```java
@Test void length_matchesIntegratedVelocityCurve() {
    float[][] table = {{2,0.0088f},{5,0.0453f},{10,0.134f},{20,0.324f},{30,0.485f},{40,0.612f},{60,0.789f},{80,0.896f},{120,1f}};
    for (float[] row : table) assertEquals(row[1], Flow.length((long) row[0]), 0.01, "t=" + row[0]);
}
@Test void length_isZeroForNegativeAge() { assertEquals(0f, Flow.length(-5), 0f); assertEquals(0f, Flow.length(0), 0f); }
@Test void length_isMonotonicAndAcceleratesThenSlows() {
    float prev = 0; float maxStep = 0; int argmax = 0;
    for (int t = 1; t <= 120; t++) { float f = Flow.length(t); assertTrue(f >= prev); if (f - prev > maxStep) { maxStep = f - prev; argmax = t; } prev = f; }
    assertTrue(argmax >= 8 && argmax <= 16, "peak velocity tick " + argmax);
}
@Test void beadScale_conservesMass() { assertEquals(1f, Flow.beadScale(0f), 1e-6); assertEquals(0.669f, Flow.beadScale(1f), 0.002); }
```
`BleedingDripTest`:
```java
@Test void dripDecal_returnsNullForUpAndDown() {
    RandomSource r = RandomSource.create(1);
    for (Direction d : new Direction[]{Direction.UP, Direction.DOWN})
        assertNull(Bleeding.dripDecal(new BlockHitResult(new Vec3(0.5, 1.0, 0.5), d, BlockPos.ZERO, false), 0.6f, 100L, r));
}
@Test void dripDecal_onWall_isVariant14_withBornAndTailUp() {
    Decal d = Bleeding.dripDecal(new BlockHitResult(new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, BlockPos.ZERO, false), 0.6f, 100L, RandomSource.create(1));
    assertEquals(14, d.variant()); assertEquals(100L, d.born());
    Vec3 tail = DecalFrame.tail(Direction.SOUTH, d.rot()); assertEquals(1.0, tail.y, 1e-3);
}
```
Make `Bleeding.dripDecal` package-private `static` for the test.

- [ ] **Step 2: Run them, see them fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.FlowTest' --tests 'dev.lume.gore.BleedingDripTest'`
Expected: `BUILD FAILED` (`Flow` missing; `dripDecal` not accessible / variant 15 possible).

- [ ] **Step 3: Implement `Flow`; set `dripDecal` variant to 14 and make it package-private**

- [ ] **Step 4: Run them, see them pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Change the atlas generator**

Replace `draw_drip(buf, cell_x, cell_y, top)` with `draw_run(buf, cell_x, cell_y)` (fill_rect x 29..34, y 0..63, BASE; columns 29 and 34 DARK) and `draw_bead(buf, cell_x, cell_y)` (fill_ellipse centre (31.5, 31.5), a 9, b 8, BASE, with EDGE on the lower leading edge as `draw_impact` does). Call `draw_run` for cell 14 (grid 2,3) and `draw_bead` for cell 15 (grid 3,3). Update the cell-layout comment at the top of the script.

Run: `python3 tools/gen_blood_atlas.py`
Expected: `wrote src/main/resources/assets/gore/textures/decal/blood_atlas.png (256x256 RGBA, N opaque px)`.
Run: `python3 tools/pixelstat.py src/main/resources/assets/gore/textures/decal/blood_atlas.png 120 14 14 40 128 192 191 255`
Expected: `count=384 bbox=157,192,162,255`.
Run: `python3 tools/pixelstat.py src/main/resources/assets/gore/textures/decal/blood_atlas.png 120 14 14 40 192 192 255 255`
Expected: `count` between 200 and 250; `bbox=216,215,231,232` ± 1 px per edge (a 9 px (vertical) × 8 px (horizontal) ellipse centred at cell-local (31.5, 31.5)).

- [ ] **Step 6: Rewrite `StainQuad.submitDrip` and the drip branch of `DecalRenderer.submit`; add `isDrip`; change `BloodItemDecorations` to `nextInt(12)`**

Compute `long age = level.getGameTime() - decal.born(); float f = Flow.length(age);`. Pass `color = 0xFFFFFFFF` (Task 6 replaces it).

- [ ] **Step 7: Add the drip growth scene to the game test**

Append after the Task 4 scenes: look at `(fx+0.5, fy+2.5, fz+4)` (the clean wall; top area, above any later stains); `runOnClient`: `DecalManager.clear(); DecalRenderer.clearDrips(); long born = mc.level.getGameTime(); DecalManager.addDecals(List.of(new Decal(fx, fy+2, fz+4, Direction.NORTH.get3DDataValue(), 0f, 0.3f, 0.6f, 90f, 14, born)))` (NORTH face, same reason as Task 2: the player looks toward `fz+4` from `fz`). Then `waitTicks(5)` → `takeScreenshot("gore-drip-05")`; `waitTicks(25)` → `"gore-drip-30"`; `waitTicks(90)` → `"gore-drip-120"`. Then `runOnClient`: `DecalManager.clear()`.

- [ ] **Step 8: Run the game test and check the run grows downward**

Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
For each of `0009_gore-drip-05.png`, `0010_gore-drip-30.png`, `0011_gore-drip-120.png`: `python3 tools/pixelstat.py --blood <png>`
Expected: **geometry, not pixel count** — `bbox.y0` within 3 px across all three (top anchored); `bbox.y1(05) < bbox.y1(30) < bbox.y1(120)` (grows downward: screen y increases); `bbox.y1(120) − bbox.y0(120)` at least 1.5 × `bbox.y1(05) − bbox.y0(05)`; and `count ≥ 100` in every frame (the run is visible). Reference run (after the HUD and drying changes): y0 213/213/213; y1 249 < 271 < 292; height 36 → 79 (2.19×); counts 186 / 244 / 198.
**Ruling (count monotonicity and the earlier `count(120) ≥ 2 × count(05)` clause are both dropped):** pixel counts are not a sound growth metric here, for two independent reasons. (1) `beadScale(0) = 1` starts the leading bead near full size and shrinks it to 0.669 (area ×0.45) as the run grows, so area grows sub-linearly (measured 1.75× before Task 6). (2) Once Task 6 adds drying, the run's colour changes with age, so the set of pixels any probe matches changes too — the measured counts are 186 / 244 / 198 even though the run strictly lengthens. The claims that matter — top-anchored, descending, ≥1.5× height — are all geometry and pass with margin. If `y0` moves instead of `y1`, the sign of `translate(0, 2f, 0)` / the orientation is wrong — fix `submitDrip`, not the assertion.

- [ ] **Step 9: Build and commit**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build` → `BUILD SUCCESSFUL`.

```bash
git add tools/gen_blood_atlas.py src/main/resources/assets/gore/textures/decal/blood_atlas.png src/main/java/dev/lume/gore/Flow.java src/main/java/dev/lume/gore/Bleeding.java src/test/java/dev/lume/gore/FlowTest.java src/test/java/dev/lume/gore/BleedingDripTest.java src/client/java/dev/lume/gore/client/StainQuad.java src/client/java/dev/lume/gore/client/DecalRenderer.java src/client/java/dev/lume/gore/client/BloodItemDecorations.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java
git commit -m "feat: physically-timed drip flow with thinning run, shrinking bead and break-off droplet"
```

---

### Task 6: Drying — every stain ages from wet red to dark brown

Physics: a thin blood film stays glossy red for about a minute, then oxidises and dries over several minutes to a matte dark brown (haemoglobin → methaemoglobin/haemichrome). Model: `dryness(age) = clamp((age − WET)/(DRY − WET), 0, 1)` with `WET = 1200` ticks (60 s), `DRY = 6000` ticks (5 min). Rendering: multiply the atlas colour by `(1 − 0.48·d, 1 − 0.15·d, 1 − 0.40·d)` via the `submitModelPart` colour overload → BASE `(120,14,14)` becomes `(62,12,8)` when dry. All decals now carry `born`; legacy `born == 0` is treated as fully dry. The game test cannot wait five minutes, so `gore.dryScale` (system property, default `1.0`, test uses `0.02` from Task 1) multiplies both thresholds.

**Files:**
- Create: `src/main/java/dev/lume/gore/Drying.java`
- Create: `src/test/java/dev/lume/gore/DryingTest.java`
- Modify: `src/main/java/dev/lume/gore/Bleeding.java` (`decalAt`, `poolDecal` gain a `long born` parameter; `collectDecals` passes `level.getGameTime()`)
- Modify: `src/client/java/dev/lume/gore/client/DecalRenderer.java`
- Modify: `src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java`

**Interfaces:**
- Consumes: `StainQuad.submitOnFace(..., int color)` and `submitDrip(..., int color)` (Tasks 2, 5); `DecalRenderer.isDrip` (Task 5).
- Produces: `public final class Drying`:
  - `public static final long WET_TICKS = 1200L, DRY_TICKS = 6000L;`
  - `public static final double SCALE = Double.parseDouble(System.getProperty("gore.dryScale", "1.0"));`
  - `public static float dryness(long born, long now, double scale)` — `born == 0 → 1f`; `age = now − born`; `age ≤ 0 → 0f`; else `clamp((age − WET·scale)/((DRY − WET)·scale), 0, 1)`.
  - `public static int tint(float dryness)` = `ARGB.color(255, round(255·(1 − 0.48·d)), round(255·(1 − 0.15·d)), round(255·(1 − 0.40·d)))`.
  - `Bleeding.decalAt(BlockHitResult hit, Vec3 travelDir, float severity, long born, RandomSource random)` and `Bleeding.poolDecal(BlockHitResult hit, float severity, long born, RandomSource random)` now set `born` on the record.
  - `DecalRenderer.submit` computes `int color = Drying.tint(Drying.dryness(decal.born(), level.getGameTime(), Drying.SCALE))` and passes it to both `submitOnFace` and `submitDrip`.

- [ ] **Step 1: Write the failing test `DryingTest`**

```java
@Test void dryness_isZeroWhileWet_andOneWhenDry() {
    assertEquals(0f, Drying.dryness(100, 100 + 1199, 1.0), 1e-6);
    assertEquals(0.5f, Drying.dryness(100, 100 + 3600, 1.0), 1e-3);
    assertEquals(1f, Drying.dryness(100, 100 + 6000, 1.0), 1e-6);
}
@Test void dryness_isZeroForNegativeAge() { assertEquals(0f, Drying.dryness(500, 400, 1.0), 0f); }
@Test void dryness_isOneForLegacyBornZero() { assertEquals(1f, Drying.dryness(0, 5, 1.0), 0f); }
@Test void dryness_scaleShortensTheClock() { assertEquals(1f, Drying.dryness(100, 100 + 120, 0.02), 1e-6); assertEquals(0f, Drying.dryness(100, 100 + 23, 0.02), 1e-6); }
@Test void tint_isWhiteWhenWet_andDarkBrownMultiplierWhenDry() {
    assertEquals(0xFFFFFFFF, Drying.tint(0f));
    int dry = Drying.tint(1f);
    assertEquals(133, ARGB.red(dry)); assertEquals(217, ARGB.green(dry)); assertEquals(153, ARGB.blue(dry)); assertEquals(255, ARGB.alpha(dry));
}
```
(If `ARGB` needs Minecraft bootstrap in unit tests — it should not; it is static integer maths — fall back to bit-shifting in the assertions.)

- [ ] **Step 2: Run it, see it fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.DryingTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... Drying`.

- [ ] **Step 3: Implement `Drying`; thread `born` through `decalAt`/`poolDecal`; apply the tint in `DecalRenderer`**

- [ ] **Step 4: Run it, see it pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 5 tests passed. Also run `--tests 'dev.lume.gore.BleedingDripTest'` → still passes.

- [ ] **Step 5: Add the drying scene to the game test**

Append after the Task 5 scenes: look at `(fx+0.5, fy+1.5, fz+4)`; `runOnClient`: `DecalManager.clear(); DecalRenderer.clearDrips(); DecalManager.addDecals(List.of(new Decal(fx, fy+1, fz+4, Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 0, mc.level.getGameTime())))` (variant 0: round, clean; NORTH face, same reason as Task 2). `waitTicks(5)` → `takeScreenshot("gore-dry-wet")`; `waitTicks(130)` → `takeScreenshot("gore-dry-dry")` (with `dryScale = 0.02`: wet until tick 24, fully dry at tick 120).

- [ ] **Step 6: Run the game test and check the colour shift**

Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
Bright probe: `python3 tools/pixelstat.py <png> 110 14 14 40`; dark probe: `python3 tools/pixelstat.py <png> 63 12 8 20`, each on `0012_gore-dry-wet.png` and `0013_gore-dry-dry.png`.
Expected: `bright(wet) ≥ 2 × bright(dry)` and `dark(dry) ≥ 2 × dark(wet)`; `bright(wet) ≥ 150` and `dark(dry) ≥ 150` (the stain is visible wet and dry). Reference run (after the Task 7 HUD change): bright 994 → 0; dark 75 → 994. **Needs a human** to confirm the dry stain reads as old blood rather than mud; shell checks only the direction of the shift.
**Ruling (the dark probe is `63 12 8 20`, not the earlier `60 12 8 30`):** the dried-blood tint is the texture `(120,14,14)` multiplied by `tint(1)` = `(133,217,153)/255`, i.e. `(62.6, 11.9, 8.4)`. A wide probe at `(60,12,8)` tol 30 also matches the stone room's ambient shadows right across the frame (~1900 px in *both* frames), so `dark(dry) ≥ 2 × dark(wet)` read as a false 1976 vs 3714 failure. The site-specific `(63,12,8)` tol 20 probe returns 57 (wet) vs 944 (dry): the wet stain is bright red and does not match, the dry stain does. Cost if wrong: a looser colour match; the bright probe's 944 → 0 already pins the wet→dry transition.

- [ ] **Step 7: Build and commit**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build` → `BUILD SUCCESSFUL`.

```bash
git add src/main/java/dev/lume/gore/Drying.java src/test/java/dev/lume/gore/DryingTest.java src/main/java/dev/lume/gore/Bleeding.java src/client/java/dev/lume/gore/client/DecalRenderer.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java
git commit -m "feat: stains and drips dry from wet red to dark brown over five minutes"
```

---

### Task 7: Accumulation — merge neighbouring drips and overlapping stains

Physics: liquid films on a wall coalesce when they touch (surface tension); two runs a few centimetres apart become one heavier run; repeated spatter on the same spot builds a thicker, more saturated patch. Data model: merging happens where decals are **added** (server `DecalStore`) and is mirrored on the client when the merged decal arrives, so the count never grows for a merge.

**Files:**
- Create: `src/main/java/dev/lume/gore/DecalMerge.java`
- Create: `src/test/java/dev/lume/gore/DecalMergeTest.java`
- Modify: `src/main/java/dev/lume/gore/DecalStore.java`
- Create or extend: `src/test/java/dev/lume/gore/DecalStoreTest.java`
- Modify: `src/main/java/dev/lume/gore/Bleeding.java` (`afterDamage` store/send loop)
- Modify: `src/client/java/dev/lume/gore/client/DecalManager.java` (`addDecals`)

**Interfaces:**
- Consumes: `Decal` record; `DecalRenderer.isDrip` semantics (`variant == 14`).
- Produces: `public final class DecalMerge`:
  - `public static final float DRIP_DU = 0.08f, DRIP_DV = 0.25f;` (same face; |Δu| ≤ 0.08 blocks, |Δv| ≤ 0.25)
  - `public static final float STAIN_DIST = 0.06f;` (same face; Euclidean (u,v) distance)
  - `public static final float DRIP_MAX_SIZE = 0.9f, STAIN_MAX_SIZE = 0.5f;`
  - `public static Optional<Decal> findTarget(Collection<Decal> existing, Decal candidate)` — first decal with equal `x,y,z,face` that is (a) drip↔drip within `DRIP_DU/DRIP_DV`, or (b) impact stain (variant < 12) ↔ impact stain within `STAIN_DIST`. Pools (12–13) never merge.
  - `public static Decal merged(Decal target, Decal candidate)` — keeps `target`'s `x,y,z,face,u,v,rot`; drips: `size = min(DRIP_MAX_SIZE, sqrt(t.size² + c.size²))`, `variant 14`, `born = candidate.born` (new supply restarts the flow); stains: `size = min(STAIN_MAX_SIZE, t.size · 1.15f)`, `variant = (t.variant/3)·3 + min(2, t.variant%3 + 1)` (one more density band), `born = candidate.born`.
  - `DecalMerge.applyTo(List<Decal> decals, Decal candidate) -> Decal` — the pure, list-level merge: if `findTarget` hits, remove the target, add `merged`, return it; else add `candidate` and return it. (Pure — touches no registries, so it is unit-testable without bootstrapping Minecraft.)
  - `DecalStore.addOrMerge(Decal candidate) -> Decal` — delegates to `DecalMerge.applyTo(this.decals, candidate)`, then `setDirty()`, and returns the result. Eviction rule unchanged.
  - `Bleeding.afterDamage` sends the list of `addOrMerge` return values (not the raw placed list).
  - `DecalManager.addDecals(List<Decal>)` — for each incoming decal, remove any existing decal returned by `DecalMerge.findTarget(existing, incoming)` before adding (the merged decal from the server shares the target's `x,y,z,face,u,v`, so it matches its own predecessor).

- [ ] **Step 1: Write the failing tests**

`DecalMergeTest`:
```java
Decal drip(float u, float v, float size, long born) { return new Decal(1,2,3, Direction.NORTH.get3DDataValue(), u, v, size, 90f, 14, born); }
Decal stain(float u, float v, float size, int variant) { return new Decal(1,2,3, Direction.NORTH.get3DDataValue(), u, v, size, 0f, variant, 50L); }
@Test void drips_withinTolerance_merge() {
    Decal a = drip(0.10f, 0.0f, 0.4f, 100); Decal b = drip(0.15f, 0.2f, 0.3f, 140);
    Decal m = DecalMerge.merged(DecalMerge.findTarget(List.of(a), b).orElseThrow(), b);
    assertEquals(0.10f, m.u(), 1e-6); assertEquals(0.5f, m.size(), 1e-6); assertEquals(140L, m.born()); assertEquals(14, m.variant());
}
@Test void drips_tooFarApart_doNotMerge() { assertTrue(DecalMerge.findTarget(List.of(drip(0f,0f,0.4f,1)), drip(0.2f,0f,0.4f,2)).isEmpty()); }
@Test void stains_overlapping_merge_withDenserVariant_andCappedSize() {
    Decal a = stain(0f,0f,0.48f, 7 /* elong 2, dens 1 */); Decal b = stain(0.03f,0.03f,0.3f, 0);
    Decal m = DecalMerge.merged(DecalMerge.findTarget(List.of(a), b).orElseThrow(), b);
    assertEquals(8, m.variant()); assertEquals(0.5f, m.size(), 1e-6);
}
@Test void pools_neverMerge() { Decal p = new Decal(1,2,3, Direction.UP.get3DDataValue(), 0,0,0.6f,0,12,5L); assertTrue(DecalMerge.findTarget(List.of(p), p).isEmpty()); }
@Test void differentFaces_doNotMerge() { Decal s = new Decal(1,2,3, Direction.SOUTH.get3DDataValue(), 0,0,0.3f,0,0,5L); assertTrue(DecalMerge.findTarget(List.of(stain(0,0,0.3f,0)), s).isEmpty()); }
```
`DecalStoreTest.addOrMerge_doesNotGrowCountWhenMerging`: a plain `ArrayList<Decal> list`; `DecalMerge.applyTo(list, drip(0,0,0.4,1))` then `DecalMerge.applyTo(list, drip(0.05f,0.1f,0.3f,2))` → `list.size() == 1` and the single element has `size ≈ 0.5`. **Do NOT construct `new DecalStore()`**: its class initializer touches `Gore.id` → `Gore.<clinit>` → data-component registration, which needs Minecraft's registries bootstrapped — a plain unit test must not bootstrap. `DecalMerge.applyTo` is the pure entry point precisely so this test needs nothing from the game.

- [ ] **Step 2: Run them, see them fail**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test --tests 'dev.lume.gore.DecalMergeTest' --tests 'dev.lume.gore.DecalStoreTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... DecalMerge` / `addOrMerge`.

- [ ] **Step 3: Implement `DecalMerge`, `DecalStore.addOrMerge`, the `Bleeding` send loop and `DecalManager.addDecals`**

- [ ] **Step 4: Run them, see them pass**

Run: same command. Expected: `BUILD SUCCESSFUL`, 6 tests passed. Then run the whole unit suite: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Game-test regression**

Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL`; `python3 tools/pixelstat.py --blood ~/gore-run/clientGameTest/screenshots/0004_gore-splatters.png` prints `count ≥ 100` (the wall is not blanked) — reference 1064. The absolute count is deliberately **not** compared to Task 1: merging collapses a coincident cluster by design, and the Task 7 measured drop (pre-HUD 3992 → 330 with a red probe) is the merge working, not a regression.

- [ ] **Step 6: Build and commit**

```bash
git add src/main/java/dev/lume/gore/DecalMerge.java src/main/java/dev/lume/gore/DecalStore.java src/main/java/dev/lume/gore/Bleeding.java src/client/java/dev/lume/gore/client/DecalManager.java src/test/java/dev/lume/gore/DecalMergeTest.java src/test/java/dev/lume/gore/DecalStoreTest.java
git commit -m "feat: merge neighbouring drips and overlapping stains into heavier decals"
```

---

### Task 8: Documentation, supersession note, full verification

**Files:**
- Modify: `README.md` (feature list and "Known gaps")
- Modify: `docs/superpowers/plans/2026-10-16-realistic-blood.md` (add a one-line note at the top of the Phase 2/3 section)
- Modify: `tools/gen_blood_atlas.py` header comment if not already updated in Task 5

**Interfaces:** none.

- [ ] **Step 1: Update `README.md`**

Add to the feature list: "Spray is a directed cone with a log-normal droplet size distribution and air-drag ballistics; mist hangs and evaporates within a second." / "Drips accelerate, thin, shed a falling droplet and merge with neighbours." / "Blood dries from wet red to dark brown over five minutes (`-Dgore.dryScale` scales the clock)." Remove "no fading/drying" from Known gaps; add "Known gaps: no secondary runnel branching, no pool meniscus, no cast-off trails, no porous wicking (see plan 2026-10-16-blood-flow-realism, Deferred)". Document `-Dgore.seed=<long>` as a test-only switch.

- [ ] **Step 2: Mark the old plan superseded**

At the top of the Phase 2/3 section of `docs/superpowers/plans/2026-10-16-realistic-blood.md` add: `> Superseded by docs/superpowers/plans/2026-10-16-blood-flow-realism.md (Phases 2–3).`

- [ ] **Step 3: Full verification**

Run: `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew clean build test` → `BUILD SUCCESSFUL`.
Run (600000 ms): `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest` → `BUILD SUCCESSFUL`.
Run: `ls ~/gore-run/clientGameTest/screenshots/`
Expected: 14 files `0000_gore-bleeding.png` … `0013_gore-dry-dry.png` in the order: bleeding, body, body-turned, gear, splatters, tail-up, tail-down, spray, spray-settled, drip-05, drip-30, drip-120, dry-wet, dry-dry. Re-run every pixel assertion from Tasks 2, 4, 5 and 6 and record the numbers in the commit body.

- [ ] **Step 4: needs a human** — view `0001_gore-body.png` (the body-stain system must still read as blood after the Task 2 UV-divisor fix changed all stain rendering from a whole-atlas wash to single atlas cells — this is the only check that body stains survived), `0007_gore-spray.png` (jet should read as a cone with a few large drops leading and fine mist near the wound), `0011_gore-drip-120.png` (thin run with a small bead at the bottom, not a smear) and `0013_gore-dry-dry.png` (dried blood, matte dark red-brown).

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/plans/2026-10-16-realistic-blood.md tools/gen_blood_atlas.py
git commit -m "docs: describe spray ballistics, drip flow, drying; supersede old phase 2/3"
```

---

## Deferred (explicitly not in this plan)

- **Secondary runnel branching** (a run splitting into two threads). The break-off droplet in Task 5 provides the "sheds a droplet that continues down" behaviour; a true branch needs a third drip cell and per-drip RNG and is left for a follow-up.
- **Pool meniscus / pool growth over time**, **cast-off trails** from a swinging weapon, **porous wicking** into wool/sponge — Phase 3 of the old plan; unchanged scope, still not started.
- **Body-stain drying** — `BodyStainLayer` keeps the untinted `StainQuad.submit`; applying `Drying.tint` there needs a `born` on `GoreStain` and is a separate small plan.

## Self-review notes

- Spec coverage: directional tails (Task 2), spray cone + sizes (3), ballistics (4), age-animated run + bulb + falling drop (5), drying (6, spec's "no drying" anti-gap), accumulation (7). Pool meniscus/cast-off/wicking deferred and said so.
- Type consistency: `DecalFrame.orientation(Direction, float)`, `StainQuad.submitOnFace(..., Direction face, int color)`, `StainQuad.submitDrip(collector, poseStack, size, rot, light, face, f, color)`, `Flow.length(long)`, `Drying.dryness(long born, long now, double scale)`, `DecalMerge.findTarget/merged`, `DecalStore.addOrMerge` are used with the same names in every task that references them.
- Review Focus: every line names its pinning test and task; item 6 is the single **needs a human** check with no shell equivalent.
- Proportion: bodies are given only for the drag step, the flow integral and the pose-stack order, where the signature alone would leave a choice that breaks the test.
