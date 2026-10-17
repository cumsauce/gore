# Blood Visual Overhaul — Design

**Date:** 2026-10-17
**Status:** Accepted (research phase complete; user approved full scope with injury depth staged as later milestones)
**Supersedes:** nothing. Extends `2026-10-16-realistic-blood-design.md` (physics/placement) with a **visual fidelity** layer. Placement physics from that spec still binds.

## Problem

Five visual failures reported on 0.3.0, each traced to a mechanism:

| Complaint | Mechanism |
|---|---|
| "splatter is an obvious circle with a line" | `tools/gen_blood_atlas.py` composes each splat from 3 primitives: a hard `fill_ellipse`, a **3px-wide straight `fill_rect` spine**, and 1–9 `fill_circle` dots. No noise, no ragged edge, no alpha falloff. It is a glyph, not blood. |
| "blood being a single color looks fake" | Three flat colours total (`BASE`/`DARK`/`EDGE`), plus one global `Drying.tint` multiplier per decal. No per-splat variation, no rim/core depth, no sheen. |
| "particles don't look good" | Camera-facing single quads, flat tinted sprites (2 drop / 3 mist), constant colour per class, 0.026–0.12 blocks, no motion stretch, no splash, no secondary droplets. |
| "blood on weapons doesn't look like blood and isn't aware where the icon actually exists" | `BloodItemDecorations` scatters splats uniformly across the **16×16 icon slot** and never reads the item's sprite, so blood lands in transparent corners. It also reuses the *impact* cell on an item icon. |
| "I don't see blood or injuries on NPCs" | `StainAnchor.resolve` name-matches model parts; non-humanoid multi-part models (cow, pig, …) miss every name and `singlePart` returns empty → `BodyStainLayer` draws **nothing**. There is no wound concept at all. |

## Hard constraints (26.3)

- **No shader path.** `RenderPipelines.register` is private, `submitCustomGeometry` is inert. Everything renders through
  `submitModelPart(ModelPart, PoseStack, RenderType, int light, int overlay, UvMapping[, int color])`.
  The trailing `int color` **is** per-submit ARGB tint — that plus `PoseStack` (translate/scale/rotate) is the whole toolkit.
- `SimpleParticleType` carries **no data**. All per-particle variation is client-side.
- No new dependencies. Atlas and particle sprites stay **script-generated** from `tools/` (stdlib-only Python).
- **No network protocol change.** Per-decal appearance must be derivable deterministically from the decal's own identity.

## Decisions

### D1 — Appearance is deterministic, not synced
`BloodAppearance` derives tint jitter and sheen from a hash of the decal identity `(x,y,z,face,u,v,variant,born)`. Client and any future consumer agree without a payload change. Jitter is small (±8 % R, ±5 % G/B) so no two stains look identical but nothing reads as a different fluid.

### D2 — Art is rebuilt as SDF splats, not primitives
Each impact cell becomes a signed-distance-field blob: radius modulated by FBM/worley noise → ragged organic boundary; tail is a **tapered noisy curve** (variable width along a Bézier) instead of a rect; satellites get micro-tails. Critically: **alpha falloff** — thick opaque core → thin semi-transparent rim. That single change removes the "sticker" read.

### D3 — Colour lives in the atlas, variation lives in `BloodAppearance`
Rim darkening (edge pooling), core brightening, and a baked wet sheen are painted into the texture so every consumer gets them. Per-instance variation is the jitter in D1. Sheen fades via the existing `Drying.tint` multiplier.

### D4 — Particles become streaks
Screen-space `roll` aligned to the velocity vector plus elongated sprites produces motion-stretched droplets with no shader. Size and colour couple to the already-computed `SprayModel.radiusM`. Mist becomes a large soft cloud, not tiny dots. Ground impacts spawn micro-droplets.

### D5 — Item blood is masked by the item's real silhouette
Read the item sprite's alpha channel, derive an `ItemSilhouette` (alpha map + edge map + dominant axis), and place **oriented smear** cells along the silhouette's dominant axis, weighted toward its edge. Blood stays on the metal.

### D6 — Body stains project onto actual model surfaces
Replace name matching with **cube-surface projection**: walk the model via `ModelPart.visit`, take each `Cube`'s box (`x,y,z,dx,dy,dz`), pick the face nearest the impact direction, place the stain on that surface. Model-agnostic. `model.root()` is the fallback so something always draws.

### D7 — Injuries are staged (Milestone 5)
Wound texture, body drips, stain accumulation, and a damage-tint overlay are additive layers on `BodyStainLayer`. They land after placement works everywhere.

## Roadmap

| Milestone | Delivers | Fixes |
|---|---|---|
| **M0** `BloodAppearance` + wiring | deterministic per-decal look | (foundation) |
| **M1** SDF art + material pass | organic splats, colour depth, sheen | "circle with a line", "single color" |
| **M2** Particles | streaks, splash, cloud mist, colour/size coupling | "particles don't look good" |
| **M3** Item blood | silhouette-masked oriented smears | "weapon blood" |
| **M4** Body placement | cube-surface projection, draws on all mobs | "no blood on NPCs" |
| **M5** Injuries | wound, body drips, accumulation, damage overlay | "no injuries on NPCs" |

Each milestone is self-contained and testable on its own; M5 is explicitly deferred to later milestones per the user.

## Anti-goals (inherited, still binding)

No symmetric splats, no tails pointing toward the wound, no new dependencies, no custom shaders, no network protocol change.

## Verification model

Headless VM — no live window. Visual checks are `env JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runClientGameTest` (600 s) → screenshots in `~/gore-run/clientGameTest/screenshots/`, measured with `tools/pixelstat.py`. Deterministic logic is unit-tested in `src/test/java/dev/lume/gore/` (JUnit 5).
