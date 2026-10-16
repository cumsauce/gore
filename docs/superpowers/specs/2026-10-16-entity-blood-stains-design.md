# Blood on bodies and the held weapon — design

Date: 2026-10-16
Status: for review
Supersedes: nothing (extends the shipped decal work)

## Why

Gore today spills blood into the world and onto gear. Hit something and the wall
behind it gets painted, and the sword you did it with keeps a bloody icon. The
creature you cut, though, walks away clean, and the sword in your own hand looks
spotless until you open the inventory. The blood is most convincing exactly where
it is missing: on the thing that got cut, and on the weapon that did the cutting.

This design adds blood to two more surfaces:

1. **Bodies.** Any living thing that takes damage wears a stain at the wound. It
   is stuck to the body — it moves, animates and turns with it — and further hits
   add more stains on top.
2. **The held weapon, in the player's hand.** A bloodied item already carries a
   bloodied icon; in first person it should look bloodied too.

Scope is all entities: the player's own body (both the arms seen in first person
and the full body in third person), every mob, and other players.

## What the user asked for, in their words

- "i also want blood decals on the world - so the player skin, items, view model
  weapons, walls etc"
- "stuck stain like decal" — blood is a stain welded to the surface, not a blob
  left behind at the thing's feet.
- "where it struck would be great" — placement at the actual strike site.
- "all" — the player's own body too, not only mobs.
- "idk keep damaging and adding more" — hits accumulate stains.
- On the view-model weapon: always visible once bloodied.

## Goals

- A stain appears on the body at the wound, and stays stuck to that spot on the
  model as the entity moves, walks and animates.
- Repeated hits add more stains, up to a per-entity cap, then the oldest are
  recycled.
- Blood persists until water washes it off, matching the existing gear rule.
- The first-person held item shows blood whenever the item is bloodied.
- The stain uses the same atlas and the same look as the existing wall decals, so
  it reads as one substance.

## Non-goals

- No gameplay effect whatsoever. No damage, no slowness, no stat changes.
- No wound model geometry, no added body parts, no changes to vanilla models or
  textures.
- No blood on non-living entities (items, boats, projectiles, armor stands).
- No change to the shipped wall decals, particles or GUI item icons beyond what
  is needed to share code.
- Not attempting to locate the wound on the model more precisely than the damage
  source allows (see Constraints).

## Anti-goals

These would each mean the feature failed even if it technically works:

- Blood floating in the air beside a moving mob instead of sitting on it.
- Blood that slides around or ends up on the wrong limb after animation.
- A visual explosion of stains on a mob that has been hit once.
- Geometry that z-fights, flickers or swims against the skin.
- Building splats up without bound until a long-lived mob is a solid red shape
  and the frame cost is unbounded.

## Constraints

**The damage system does not say where on the model the hit landed.** Minecraft
tells us what caused the damage, who caused it, and how much was taken. It does
not give a point on the victim's collision or render geometry. So the wound site
cannot be read off the event; it has to be derived from the damage source and
mapped onto whatever the victim's model actually has there. This is the single
biggest source of imprecision in the feature, and it is inherent, not a shortcut.

**Bodies have no per-entity client store today.** The existing decals are stored
in the world by block position. A stain on a body belongs to an entity and must
survive the entity being unloaded and reloaded, so it needs its own store, keyed
by entity, and its own sync.

**Rendering custom geometry from a level-render event does not work on 26.3.**
The vanilla custom-geometry submit path draws nothing (verified: an untextured
control quad at the same spot as a shape outline that rendered 684 pixels drew
zero), and the documented self-owned-`StagedVertexBuffer` recipe cannot be used
from any level event because they all fire inside the framegraph's level pass.
The working technique, already used for wall decals, is the **model feature**:
bake a `ModelPart` and submit it with `submitModelPart`, which draws in whatever
pose is current when it is submitted. Attaching to an entity means submitting
inside that entity's render pass.

**Render state data is available without a mixin.** Fabric injects a
`FabricRenderState` interface into `EntityRenderState` (and
`FirstPersonHandsAndItemsRenderState`) by class tweaker, so
`state.getData(KEY)/setData(KEY, value)` works on any render state. That is how
per-entity blood reaches the renderer, and the client can invalidate those states
when a sync arrives.

## What the user decided

- Blood is a stain stuck to the surface, riding along with it.
- Placement at the strike site.
- Scope is all entities, including the player's own body.
- Hits accumulate.
- The first-person weapon shows blood whenever it is bloodied, until washed.

## My calls

These are decisions I made rather than the user's; each can be changed without
altering the shape of the work.

1. **Where a stain lands.** The client derives a local-space anchor from the
   damage source rather than the server picking a random spot. Melee and generic
   hits go to the body's front; projectiles to the chest or head, aiming back
   along their flight; explosion to the side facing the blast; fire, lava and
   magma to the legs; falling-damage causes to the legs and feet. The anchor is
   resolved against the entity's actual model parts, so a hit aimed at a head a
   creature does not have falls back to its body.

2. **Cap and eviction.** A per-entity ceiling (starting point: 12 stains). Past
   it, the oldest is dropped as a new one lands. This bounds both the look and
   the cost.

3. **Nobody can be cleaned except by water**, as agreed. Decay over time is
   deliberately not included; the README already lists drying and fading as a
   known gap, and adding decay here would be inconsistent.

4. **Stains are per-entity, not per-part.** A stain knows which part it attached
   to; nothing else about the model is stored.

5. **Client-side placement gives deterministic results.** Because the anchor is a
   pure function of the damage source, every client that sees the hit produces
   the same placements, and they line up if the stain is ever shown in a GUI.

6. **Mob stains are lost when the mob's chunk unloads and reloads.** Stains are
   keyed by entity, so a reloaded mob is a new entity and comes back clean.
   Player stains survive, like their gear. Persisting mob stains across reload
   would mean keying them to something that outlives the entity and writing them
   into the world save, which is more machinery than the request justifies.
   Flagging it because it is a real behavioural limit, not a bug.

7. **First-person weapon blood reuses the model feature**, not the GUI decoration
   path, since the first-person item model is submitted during
   `FirstPersonHandsAndItemsRenderer` rather than through the GUI. The hand render
   already goes through a `SubmitNodeCollector`, which is what makes this
   possible.

8. **Body stains use the impact cells, not the four ground-pool cells.** The pool
   cells are painted top-down for a flat floor and look wrong stuck to a limb.

9. **Water washes body stains on any entity, not only players.** "Water is the
   only cleaner" is only true if it applies to mobs too; leaving mob stains
   permanent while player stains wash would be indefensible. This deliberately
   goes one step past the shipped gear rule, which washes players only.

10. **Removing the cap, persisting mob stains, or storing absolute positions
    rather than anchors are all possible later** — noted so the builder does not
    over-engineer for them.

## The shape of the work

### Part 1 — bodies

**Server, at the damage event** (`Bleeding.afterDamage`, which already computes
severity, impact direction and the wound point): additionally record a body stain
for the victim. The record is the damage source's *type* (for the client's
region table) plus the *impact direction* the server already computed, quantised
so the client reproduces the exact same anchor, plus a variant and a rotation.

**Where stains live, and why not a data component.** Stains are a client-side,
keyed store synced over the mod's own network channel, not a vanilla data
component. A component on a non-player mob is not reliable: the mod's existing
`gore:blood` component on a mob's armour is unguaranteed, and a component would
also be visible to vanilla clients, which the mod deliberately avoids. A mod-side
store keyed by entity id gives exactly what is needed: a list, a cap, eviction,
the ability to sync to just the clients that can see the entity, and a place to
hang join-time replay.

**Sync:** two payloads, mirroring the wall-decal design. One carries the stains
added by a hit to players near the victim. The other replays the full stain list
for entities in view when a player joins or changes dimension, so someone who
arrives mid-fight sees a creature that is already bloodied. Because a stain is
reconstructed from `(type, direction, variant, rotation)` rather than from a
wound point, the replay carries the record itself and no client ever has to have
witnessed the hit.

**Client, extraction:** during entity render state extraction the stains for that
entity are read from the store into the render state under a `RenderStateDataKey`
(plain `getData`/`setData`, no mixin needed — Fabric injects the interface). The
stains are stored in a fixed order and rendered in that order, so placement does
not depend on when the client happened to see each hit.

**Client, render:** a vanilla **`RenderLayer`** attached to every living entity
renderer via `LivingEntityRenderLayerRegistrationCallback`. It reads the stains
from the render state, and for each one resolves the anchor to a `ModelPart` on
the parent model and submits the stain quad in that part's pose, using the
submission technique the wall decals already use.

**Client, invalidation:** `InvalidateRenderStateCallback` is subscribed so that
arriving stains are reflected in render states rather than waiting for whatever
caches them to be rebuilt.

**Box growth:** the layer pre-grows the entity's culling box when the entity
carries stains, so a stain that is only enabled while submitting is never culled
away.

**Washing:** water clears an entity's stains server-side and the clearing is
synced, the same shape as the existing gear wash.

**Persistence across restart:** mob stains do not survive a chunk unload and
reload, because the store is keyed by entity id and a reloaded mob is a new
entity. Player stains do survive, via the same path the gear component already
uses. This is a deliberate acceptance rather than an oversight; the alternative
is persisting stains into the world save keyed by something that survives
reload, which is a larger piece of work and not what was asked for.

### Part 2 — the first-person weapon

**Client, render:** the bloodied item's stains are submitted during the
first-person hand/item pass (`FirstPersonHandsAndItemsRenderer`), in the pose the
item is already rendered in, gated on `Gore.BLOOD` being present on the held
stack. Because the blood only has to look attached, its placement is chosen for
looks rather than reproduced from a wound; keeping it simple is the intent.

**Already covered, not repeated here:** the GUI item icon is shipped
(`BloodItemDecorations`), and the third-person held item needs no new work,
because Fabric injects `FabricRenderState` into `ItemStackRenderState` and its
layer states — the same item render state the GUI icon and the third-person hand
already draw from — so the icon and third person are one surface, not two.

### Shared pieces

- The stainless quad geometry, atlas cell UVs and orientation code that
  `DecalRenderer` already has move into something both callers use, so the blood
  looks identical on a wall, a body and a weapon.
- A damage-source-to-region mapping, shared with nothing else today, lives on the
  client.

## What is decided vs. what is left to the builder

**Decided:** surfaces (bodies of all living entities, plus the first-person
weapon); stain is surface-attached and animation-following; accumulation until a
cap; water is the only cleaner; rendering rides the model feature via a render
layer for bodies and the hand pass for the weapon; the server records the damage
source identity and the client derives the anchor; the stain reuses the existing
atlas.

**Left to the builder:** exact numbers (the cap, stain size range, the per-feature
bounds of the anchor offsets, the quantisation of direction); the precise splits
of the damage-source-to-region table; how the anchor resolves when the ideal part
is missing; where the shared quad/submission helper lives and what it is called;
how the stain list is encoded in the payloads; whether the stored record is
trimmed of anything the client can derive.

**A stain is a best effort, not a guarantee.** Every living entity gets the render
layer, but a creature whose model has no usable part for the chosen region is
allowed to show nothing rather than something wrong. The layer must degrade
quietly, never throw, and never park a stain in mid-air. The known hard cases are
models with a single body part, entities that are not built from a `ModelPart`
hierarchy at all, and very small creatures where a humanoid-sized anchor would
hang off the silhouette; the per-feature clamps and the fallback chain exist for
those, and "nothing" is an acceptable outcome for them.

## Risks

- **Attaching to the wrong part** if a model has an unusual part layout, so the
  resolution has to be defensive and fall back rather than throw.
- **Draw bounding box.** A stain that starts invisible and is enabled during
  submission can be culled away, because the box is computed before submission.
  Pre-growing the box when the entity carries blood is the safe move.
- **Cost.** Every living entity gets a layer; it must do nothing measurable when
  the entity has no blood.
- **Anchors that hang off the silhouette** for models much smaller or larger than
  a humanoid, which is what the per-feature clamps are for.

## Known gaps this does not close

The README's existing gaps stay open: decals do not fade, dry, or wash off walls;
mobs do not rinse their own gear; rain does not disturb decals; a wounded creature
leaves no trail as it walks.
