# Realistic blood — design

Date: 2026-10-16
Status: for review (supersedes the splat look of v0.1.x, not the entity work)

## Why

The blood that ships reads as "MS spraypaint" and as "a bullet entry + exit
wound". Both come from the same cause: every splat is a symmetric starburst —
a dense central blob with spikes radiating in all directions — stamped with a
random roll. Real blood is the opposite of that: it is directional and
anisotropic. A wound throws blood *away from itself* along a cone, each droplet
hits at an angle, and every stain is stretched into an ellipse whose tail points
in the direction the droplet was travelling. Symmetric starbursts do not occur;
that shape is what our eyes read as a through-and-through hole.

## The physics (grounding)

Forensic bloodstain pattern analysis classifies stains into three groups, and
the numbers behind them are well measured.

- **Impact angle → shape.** A droplet that hits straight on (90°) leaves a round
  stain; one that hits at a shallow angle leaves an ellipse stretched along the
  travel direction, with `width / length ≈ sin(angle)`. The ellipse has a
  **tail/spine** on its trailing edge that points along the direction of travel,
  and smaller **satellite** droplets cast *forward* past it.
- **Directionality.** Every elongated stain in an impact pattern points *away*
  from the wound. Impact spatter is a **cone** centred on the direction the force
  was applied — denser near the wound and along the force axis, sparser to the
  sides — never a uniform sphere. This is the single fact that fixes the
  "entry + exit" look: align the tails radially outward and the brain stops
  seeing a hole and starts seeing a wound that threw blood.
- **Velocity → droplet size and satellites.** Faster droplets (a hard blow, a
  projectile) break into many small droplets with lots of satellites and thin
  edges; slower, larger droplets (drips, a soft impact) stay as one big blob with
  few or no satellites. The relevant quantity is the Weber number (inertia vs
  surface tension), but the monotone rule — more speed, more and smaller — is
  what matters for a game.
- **Cast-off.** A swinging weapon flings blood in long, thin, elongated streaks
  that point along the arc of the swing.
- **Flow and drips.** When enough blood sits on a vertical surface, gravity pulls
  it down into a **flow pattern**: a narrow vertical run that wets a trail behind
  it and ends in a **teardrop bulb** where the drop pauses before detaching. A
  drip starts when the drop's weight beats the surface's adhesion; porous
  surfaces wick blood away and drip less.

## What changes

Three phases, biggest impact first. Phase 1 alone kills both complaints; Phases 2
and 3 are what make it "disturbing".

### Phase 1 — directional, angle-aware impact spatter

The two visible bugs are fixed together because they share the same cause.

- **A regenerated atlas.** The splat sprites are replaced with *directional*
  shapes: an ellipse with a spine and forward satellites, each drawn with its
  tail pointing a fixed direction in the sprite (up). A stdlib-Python script
  rasterises them from the physics above, so the atlas is reproducible and
  tunable rather than hand-painted, and it can hold many more variants than the
  twelve impact cells it has today. A per-cell elongation parameter maps each
  sprite to a band of impact angles.
- **Directional placement.** `decalAt` no longer rolls a random rotation. It
  computes the droplet's velocity projected onto the struck face, and rotates the
  decal so the sprite's tail points along it. Every tail now points away from the
  wound.
- **Angle and velocity drive size.** The impact angle picks the elongation band;
  the droplet's speed picks the size and the satellite count, via the velocity
  the ray already carries.
- **A cone, not a sphere.** The jittered rays that cast droplets are biased
  toward the impact direction and fall off to the sides, so splatter concentrates
  along the force axis instead of spraying isotropically.

### Phase 2 — drips and flow

The "animated, drips down walls" ask.

- **Run + bulb.** A heavy stain on a vertical face can produce a **run**: a thin,
  persistent vertical trail below the stain that grows downward over time and
  ends in a teardrop bulb. Rendered as a new thin decal type whose length the
  client animates from its age, so it persists like every other decal and needs
  no shader.
- **Falling drops.** When a bulb grows past a threshold it detaches as a falling
  droplet particle, reusing the existing particle path.
- **Accumulation.** Whether a stain drips depends on how much blood landed there
  (its severity), so a light graze stays put and a deep wound runs.

### Phase 3 — polish

- Pools keep their own floor-only look (already the case) but gain a proper
  meniscus edge rather than a crisp sprite.
- Cast-off streaks for weapon swings.
- Wicking on porous blocks (sand, wood) reduces drip, matching real absorption.

## My calls (visual decisions to flag)

These are mine, not yours; each is changeable without reshaping the work.

1. **Keep the sprite-atlas architecture** rather than building fully procedural
   meshes. The atlas fits the existing model-feature render path, and — because I
   cannot see images in this environment and verify by pixel statistics — a
   mathematically-rasterised sprite is far more predictable than per-droplet
   vertex geometry.
2. **The atlas is generated by a committed script**, not hand-authored, so the
   physics constants are auditable and the look can be re-tuned by rerunning it.
3. **Tail-up convention.** Every directional sprite points its tail "up" in the
   atlas; placement rotates to the travel direction. One convention, no
   per-cell guessing.
4. **Drips are persistent thin decals animated by age**, not a shader (26.3 has
   no mod-facing custom pipeline) and not a transient particle (a drip that
   vanishes breaks the "stays where it lands" rule the rest of the mod already
   promises).
5. **Phase 1 ships first and is verified before Phase 2 starts**, because Phase 2
   builds on the new atlas and the new placement.

## Non-goals

- No gameplay effect. Still purely cosmetic.
- No fluid simulation (SPH/FLIP). The physics are captured as placement rules,
  not a solver.
- No change to the body-stain or weapon work already in flight; those re-derive
  their look from this atlas once it lands.

## Anti-goals

- A splat that looks symmetric (the whole point is to remove that).
- Tails pointing toward the wound (breaks directionality).
- Drips that make every stain run (a light graze should stay put).
- Any new dependency or a custom shader/pipeline.
