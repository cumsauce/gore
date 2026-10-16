# Handoff to Fable: a realism pass on Gore's blood

## What I'm asking of you

Read this, then look at the code and the running mod, then **write a plan** for
making the blood look physically real instead of like random particles.

Three hard rules:

1. **Do not delegate.** No subagents, no agents, no "have someone else check
   this". You do the reading, the thinking and the writing yourself.
2. **Do not implement anything.** No edits to product code, no commits, no
   experiments that change the repo, no running the build to "try things out".
   Reading, and running things read-only, is fine.
3. **The plan is the whole deliverable.** Write it up and save it. That is where
   you stop.

## The deliverable, precisely

- Save your plan to `docs/superpowers/plans/YYYY-MM-DD-<topic>.md` in the repo
  (use today's date; pick a short topic slug like `blood-flow-realism`).
- Write it for **low-intelligence coding agents** — assume the implementer is
  capable but literal, has no context beyond your document, and will not make
  good judgement calls. So:
  - Every task names the exact files, the exact function signatures, and the
    exact constants.
  - Give concrete numbers, not ranges or adjectives. "Decelerate at
    `0.02 * v²` per tick", not "make it more realistic".
  - Every task ends with a verification step that a shell can run and a stated
    expected output.
  - Order the tasks so each one produces something testable on its own.
  - Put the exact test commands in the steps. Do not write "test it".
- Do not write the code itself — signatures, constants and assertions only.
  A plan longer than the code it describes has written the code instead.

## The project in one paragraph

`Gore` is a Fabric mod for Minecraft 26.3 that makes creatures bleed. It is
purely cosmetic — it never deals damage or changes gameplay. Repo:
`/Volumes/My Shared Files/mc/gore`, GitHub `cumsauce/gore`, branch `main`. Java
25, Gradle via `./gradlew`. There is a README at the repo root that describes
the feature set in plain language; read it first.

You are only touching the **blood visuals**. Leave the gameplay plumbing alone.

## The complaint, concretely

Two things, in the owner's words:

> "it looks like random particles and the flowing isnt realistic"

Unpacked, these are the two areas I want your plan to attack:

### 1. "Random particles"

When something is hit, a spray of blood particles comes off the wound. It reads
as a puff of unrelated dots rather than a directed jet of fluid travelling with
the blow. Real impact spatter is a **cone along the force**, with a strong
size distribution (a few big droplets, many tiny ones), ballistic deceleration
from air drag, and a clear separation between the fast fine mist that dies
quickly and the slower heavy droplets that land. Look at how the particles are
spawned and moved and you will see how much of that is missing.

### 2. "The flowing isn't realistic"

Blood that lands on a wall runs down it. That exists (see "Drips" below) but it
is crude: the run grows at a constant linear rate and the bead sits at the
bottom. Real flow accelerates under gravity, thins and decelerates as it
stretches, thickens where it accumulates, breaks off into secondary runnels,
merges with neighbouring runs, and dries from wet bright red to dark brown.
None of that is there.

## What exists today

Read these before planning. They are the whole blood system.

### Server side (`src/main/java/dev/lume/gore/`)

- `Bleeding.java` — the heart. On a damage event it computes `severity` (damage
  over max health), an `impactDirection`, and a wound point; spawns particles;
  casts a cone of rays from the wound that get clipped against block collision
  shapes; and turns each hit into a `Decal` on the block face.
  - `decalAt(...)` currently derives the stain's **elongation band from the
    impact angle** (`sin` of the angle between travel and the face normal) and
    its **density band from severity**, packs both into the atlas `variant`, and
    sets `rot` from the travel direction projected onto the face.
  - `poolDecal(...)` places a ground pool.
  - `dripDecal(...)` places a drip on a vertical face, stamped with the game
    time it was born.
  - `coneDirection(...)` does the directional spray (see the `SPRAY_SPREAD`
    constant).
  - Particles are spawned here with `level.sendParticles(...)`.
- `Decal.java` — the record `(x, y, z, face, u, v, size, rot, variant, born)`.
  `born == 0` is a static stain; `born > 0` is a drip. `axis1`/`axis2` give the
  face's tangent basis.
- `DecalStore.java` — per-level `SavedData`, capped at 1024, oldest evicted.
- `GorePayloads.java` / `Sync.java` — how new decals reach clients, and how a
  joining player gets the full list.
- `Gore.java` — registration: particle types `BLOOD_MIST` and `BLOOD_DROP`, the
  `gore:blood` item component, payload types.
- `StainWounds.java`, `StainStore.java`, `StainPayloads.java`, `StainSync.java`,
  `GoreStain.java` — the body-stain system (blood stuck to a creature's model).
  Separate feature; not your focus, but your changes must not break it.

### Client side (`src/client/java/dev/lume/gore/client/`)

- `DecalRenderer.java` — draws wall decals. **Drips are animated here**: it
  reads `decal.born()`, computes `progress = (gameTime - born) / DRIP_TICKS`
  (`DRIP_TICKS = 80`, i.e. 4 seconds) and calls `StainQuad.submitDrip(...)` with
  that progress. This linear progress is the "flow is not realistic" you are
  being asked to fix.
- `StainQuad.java` — the shared geometry. Holds the baked `ModelPart` variants
  (one per atlas cell), the `OFFSET` that keeps a decal off the block face, and
  `submit(...)` / `submitDrip(...)`.
- `BloodMistParticle.java` / `BloodDropParticle.java` — the two particles. Look
  at their motion: this is where "random particles" lives.
- `DecalManager.java` — client mirror of the decal list.
- `GoreClient.java` — client registration: particle providers, payload
  receivers, `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`.
- `BodyStainLayer.java`, `StainAnchor.java`, `StainManager.java`,
  `StainStateKeys.java` — body stains.
- `BloodItemDecorations.java` — blood on the item icon in the GUI.

### The atlas

`src/main/resources/assets/gore/textures/decal/blood_atlas.png` — 256×256, a 4×4
grid of 64px cells, generated by `tools/gen_blood_atlas.py` (stdlib Python only,
no PIL). Cell layout is a contract between the script and the Java:

- cells 0–11: impact stains, `variant = elongationBand * 3 + densityBand`
  (elongation 0 = round → 3 = long ellipse with a tail; density 0 = clean →
  2 = many satellites)
- cells 12–13: ground pools
- cells 14–15: drips (a thin run with a bead, two lengths)

If your plan changes what cells mean, it must change **both** the script and the
Java, and say so.

## Constraints you must plan within

These are hard-won facts about this Minecraft version. A plan that ignores them
will not build.

1. **There is no shader path for mods on 26.3.** `RenderPipelines.register` is
   private. You cannot add a custom render pipeline. Anything animated must be
   animated by moving/rescaling geometry or by choosing different sprites over
   time — driven by game time, in Java.
2. **`SubmitNodeCollector.submitCustomGeometry` is inert on 26.3.** It silently
   draws nothing (verified at runtime). All custom world geometry goes through
   `submitModelPart(...)`, the model feature — that is what `StainQuad` does.
   Do not plan anything that uses `submitCustomGeometry`.
3. **Decals are textured quads from the atlas.** They are not meshes, not
   particles, not shader effects.
4. **Particles are vanilla-style particles** registered through
   `ParticleProviderRegistry`. They have their own render path and their own
   motion code; you can change both.
5. **Everything is client-rendered from server-sent state.** The server is
   authoritative. If something must animate, the client animates it from the
   `born` timestamp (that is the existing pattern) — do not plan per-frame
   network traffic.
6. **No damage, no gameplay effect, ever.** Cosmetics only.
7. **Do not add a Mixin if you can avoid it.** The mod currently has none.
   Prefer a Fabric event or a registerable hook; if a Mixin is genuinely the
   only way, say so explicitly and justify it.

## Where the realism is weak (my read — verify it yourself, then plan against it)

Treat this as leads to confirm, not as a specification. Look at the code and
decide for yourself.

**Particles**
- Motion is probably near-ballistic with a single gravity constant, so mist and
  droplets behave identically and neither decelerates.
- Sizes are probably picked from a narrow range, so there is no "few large, many
  small" distribution.
- The emission may not be tightly aligned to the blow direction, which is what
  makes it read as a random puff. Check what velocity the particles are given
  relative to `impactDirection`.
- Mist and droplets have no lifecycle difference — real mist hangs, drifts and
  evaporates within a second; real droplets are heavy, fall fast and persist.

**Flow (drips)**
- Growth is linear in time (`progress = age / DRIP_TICKS`). Real flow starts
  slow, accelerates, and then slows as the run thins and drags on the wall.
- The run does not thicken or gain mass over time.
- There is no secondary break-off: a real run sheds a droplet that continues
  down on its own.
- Adjacent runs do not merge.
- Nothing dries. Blood should darken and flatten as it dries, and a wall that
  has been bloody for a while should not look wet.

**Stains generally**
- Every stain is drawn at full opacity forever; there is no aging, no drying, no
  wet/dry distinction.
- Overlapping stains do not accumulate into a mass.

## How to verify anything (read this before writing verification steps)

You are on a headless VM. **`./gradlew runClient` cannot open a window** — the
GLFW/NSGL backend finds no pixel format. The only way to see the mod render is
the client game test:

```sh
cd "/Volumes/My Shared Files/mc/gore"
env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest   # ~45-55s
```

It drives a real client, builds a stone room, has the player cut a cow, and
writes screenshots to `~/gore-run/clientGameTest/screenshots/`. The current
screenshots are:

- `0000_gore-bleeding.png`
- `0001_gore-body.png`
- `0002_gore-body-turned.png`
- `0003_gore-gear.png`
- `0004_gore-splatters.png`

There are two small PNG helpers in
`/private/var/folders/tb/8d_73tkx5hndlyfwjng3srp80000gn/T/opencode/`:

- `dominant.py <png> <r> <g> <b> <tol> [label]` — counts pixels near a colour
  and prints `count=` and a bounding box.
- `pngtool.py crop <in> <x0> <y0> <x1> <y1> <scale> <out>` — crops and rescales.

**The coding agents that implement your plan cannot view images.** They verify
with pixel statistics, not by looking. So your verification steps must be things
a shell can check: a pixel count that must rise or fall, a bounding box that
must move, a file that must exist. Where the only real check is a human
eyeballing the picture, say so explicitly and mark that step "needs a human".

Also useful: the game test is **non-deterministic run to run** because the spray
is driven by a random source that is not frozen. A "pixel-identical" gate is
therefore impossible. Design verification around direction and magnitude, not
exact equality — or make freezing the RNG part of one of your tasks.

## Build and environment

- JDK: `/opt/homebrew/opt/openjdk@25`. Always
  `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew <task>`.
- Use the project's `./gradlew`, never a system Gradle.
- `./gradlew build` takes ~10s warm; `runClientGameTest` ~45-55s. Give long
  commands a generous timeout.
- `javap` is at `/opt/homebrew/opt/openjdk@25/bin/javap`; the decompiled 26.3
  jars are in `~/.gradle/caches/fabric-loom/26.3/`. Check any Minecraft or
  Fabric API signature against those before asserting it — 26.3 renamed a lot
  (`ResourceLocation` → `Identifier`, `Cow` moved package, etc.).

## Existing design docs (read these, do not duplicate them)

- `docs/superpowers/specs/2026-10-16-realistic-blood-design.md` — the
  bloodstain-pattern research, the physics, and the decisions already made.
- `docs/superpowers/plans/2026-10-16-realistic-blood.md` — the existing plan.
  Phase 1 (directional, angle-aware spatter + the new atlas) is **done**. Phase
  2 (drips) is **partly done** — the drips exist but crudely. Phase 3 (pools,
  cast-off, porous wicking) is **not started**.

Your plan should either extend that plan's phases 2 and 3, or supersede them —
say which, and why.

## Two things that are unverified and may be outright bugs

Factor these in; do not assume they work.

1. **The stain tail direction is unconfirmed.** The tail of an elliptical stain
   is supposed to point along the direction of travel. The rotation is computed
   as `degrees(atan2(p·axis2, p·axis1))` in `Bleeding.decalAt`. Nobody has
   confirmed with an eyeball that the tail ends up on the correct side; the sign
   may be flipped. If your plan touches tail orientation, include a step that
   settles this.
2. **The drip animation is unconfirmed.** The transform is verified
   algebraically but no multi-frame evidence exists that the run actually grows
   downward over time rather than appearing full-length. If your plan changes
   the drip, include a way to see the growth (e.g. two screenshots N ticks
   apart).

## How to structure the plan

For each task:

- **Files** — exactly which files are created or modified, by path.
- **Interfaces** — what it consumes from earlier tasks and what it produces for
  later ones, as exact signatures. A task's implementer sees only their own
  task; this is how they learn the neighbouring names and types.
- **Steps** — one action per step, each with a checkable result: write the
  failing test, run it, see it fail, implement, run it, see it pass, commit.
  Include the exact command and the expected output.
- **A commit at the end of each task**, with a concrete commit message.

Also include, near the top:

- The goal in one sentence.
- The architecture in two or three sentences.
- A **Global Constraints** section: the hard limits above, one line each, copied
  verbatim so every task inherits them.
- A **Review Focus** section: the handful of situations most likely to bite
  (e.g. "a drip on a ceiling", "two drips close enough to merge", "a hit with
  zero severity", "an entity in water", "a decal whose `born` is in the future
  after a clock change") — each with the behaviour a reasonable person expects,
  and each pinned by a test in the task that owns that code.

## Finally

Ground it in the physics, not in adjectives. The reason the current blood reads
as fake is that its shapes and motions do not follow from how fluid actually
behaves. Every change you propose should be traceable to a real behaviour —
ballistics, drag, viscosity, surface tension, wetting, drying — and the plan
should say which. That traceability is what will make the result disturbing
rather than decorative.

Save the plan, then stop.
