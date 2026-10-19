# Gore

Blood that behaves like blood: creatures bleed when they are cut, hard impacts throw it, and it stays where it lands. A Fabric mod for Minecraft 26.3.

Status: **0.7.0**, on the [releases page](https://github.com/cumsauce/gore/releases).

## The idea

Hit something and it bleeds. A glancing blow leaves it dripping; a solid one makes it gush. Hit something hard enough — an arrow, a sprint crit, an axe — and the blood leaves the body with force: a mist sprays in a cone along the direction the blow was travelling, and whatever it meets on the way becomes a splatter. Blood that finds a wall, a ceiling or a floor sticks there and stays, and on a wall a heavy splat runs downwards into a drip. Blood that doesn't falls to the ground as droplets.

The splatter is built from bloodstain-pattern physics rather than a starburst. A wound throws blood one way, not outwards in every direction: what lands is a cone of stains whose shape records how hard each one hit — close to round when it struck the face square on, stretched into a long ellipse with a tail when it struck at a glancing angle — with fine satellite droplets cast ahead of the bigger ones.

It is all cosmetic. Bleeding never costs health or damages anything; the wound is what the hit already did. Nothing is added to the game that a vanilla client would need to understand.

## How it works

- **Severity** comes from the damage actually taken, measured against the creature's maximum health, so a hit that nearly kills a chicken is not the same as one that barely dents a ravager.
- **Direction** is the projectile's own velocity when there is a projectile, otherwise the line from attacker to victim, otherwise the damage source's position. A blow from below throws blood upward into the ceiling; a hit from the side paints the wall behind.
- **Splatter** is a set of jittered rays cast from the wound in a cone along the blow, clipped against block collision shapes. Each ray that lands stores a decal: which block, which face, where on the face, how big, which way it is turned, and which cell of the decal texture atlas to use. The angle it struck at picks the stain's elongation — square on gives a round blot, glancing gives a long-tailed ellipse — and the blow's force picks the droplet size and how many satellites it casts, so a harder hit throws more, smaller drops.
- **The spray is the splatter.** Every stain a hit leaves on a wall or floor is carried there by a droplet you can watch: the server stamps the stain's birth at the tick its droplet arrives, and the client flies that droplet from the wound along a real gravity arc (about 8 m/s, a flicked brush rather than a gunshot), so the mark appears exactly where and when it lands. A few free drops miss everything and fall.
- Spray is a directed cone with a log-normal droplet size distribution and air-drag ballistics; mist hangs and evaporates within a second. A hard hit also opens **arterial jets** — short-lived emitters at the wound that keep throwing droplets for a fraction of a second, so the wound reads as being under pressure instead of as a single puff.
- Blood that reaches a **floor** pools there instead of keeping an impact tail. A pool is born small and floods outward over about two seconds, decelerating as it spreads, so a fight visibly soaks the ground around it.
- Drips start wide where the splat fed them, taper as they descend, accelerate, thin, shed a falling droplet and merge with neighbours.
- Blood dries from wet red to dark brown over five minutes, then **fades to nothing** over the next twenty (`-Dgore.dryScale` scales both clocks). A dry stain on a wall or a body thins out gradually rather than sitting at full strength until the budget evicts it.
- **Rain washes blood away.** A stain exposed to the sky dissolves after about four minutes of rain — faster than it dries, and tracked per decal, so a sheltered face keeps its blood while the wall beside it runs clean.
- **Water rinses bodies.** A creature in water loses its wounds outright; a player in rain loses them slowly. Gear still rinses as soon as its owner is in water.
- **Bodies get bloody.** Anything that takes damage wears a dark blood coat whose opacity grows with the number of wounds, plus a gash welded to its own model with spatter scattered around it, so it moves and animates with the creature. The gash grows a run that crawls down the body, and it dries on the same clock as a wall stain. Hits accumulate up to a per-entity cap, oldest recycled. It applies to your own body, every mob, and other players.
- **Body blood persists.** Stains are stored per level as `SavedData` keyed by the creature's UUID, so a mob that wanders out of range, has its chunk unloaded, or watches the server restart comes back wearing the same wounds. Entity ids change per session and would attach old blood to the wrong mob, so the network still addresses by id but the store never does; a returning entity's stains are re-sent to nearby clients when it loads.
- **Wounded creatures leave a trail.** A mob carrying two or more wounds drips pools onto the ground as it walks, at most one a second, so you can read where it went. A **killing blow** empties the body: a large pool spreads under the corpse, a ring of spatter lands around it, and jets finish the wound.
- **Decals persist.** They live in a per-level `SavedData` capped at 1024, oldest evicted first, written to `dimensions/<dim>/data/gore/decals.dat`. They survive restarts, and they are per-dimension.
- **Everyone sees them.** The server is authoritative: new decals go to every player within 96 blocks as they are made, and a joining player — or one changing dimension — receives the full list for the level they are entering.
- **Gear gets bloody.** The weapon that drew the blood, and the victim's armor, each carry a `gore:blood` component they accumulate over hits. Player gear rinses clean in water; the component is per-item, so the blood is on the item wherever it goes.
- **Rendering** happens in the level's own world pass. Wall blood is a liquid surface, not stickers: every decal contributes metaball kernels to the block faces it reaches, and each face is baked into one small texture and drawn as a single lit quad, so overlapping blood fuses instead of z-fighting, pools swallow the spatter they reach, drips have a rounded meniscus, and blood runs across block seams. Decals are submitted on `COLLECT_SUBMITS`, the point where the frame's geometry is baked; submitting from a draw event instead (an earlier version did) leaves them a frame behind the camera, which is what makes blood swim over the world and pop in and out of view as the player moves. 26.3's custom-geometry submit path draws nothing, so decals ride the same model pass the entities do — one baked single-face model per atlas cell. Particles and item-icon splats are drawn by the client's own paths.

## Install

Needs [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5+ and [Fabric API](https://modrinth.com/mod/fabric-api). Drop `gore-<version>.jar` into `mods/`. Minecraft 26.3, Java 25.

It goes on the **server**, and also on the **client** if you want to see any of it — all the drawing is client-side, so with the mod only on the server nothing appears at all.

## Development

You need JDK 25.

```sh
./gradlew build                # compile and produce the jar
./gradlew runClient            # dev client
./gradlew runServer            # dev server
```

Building inside a VM from a folder shared with the host over VirtioFS needs `-Pgore.runDir=<dir on a local disk>` for the game runs, because Minecraft's world session lock needs `F_FULLFSYNC`, which that filesystem does not support. The default already points the run dirs at `~/gore-run`.

`runClient` cannot open a window on a machine whose GLFW/NSGL backend finds no pixel format (a VM). The mod's client game test can still be seen there, because it drives the real client on its own render thread:

```sh
./gradlew runClientGameTest     # renders the mod and writes screenshots
```

It builds a stone room, has the player cut a cow with a sword for a directed spray (which also bloodies the held sword and stains the cow's body), and writes screenshots to `~/gore-run/clientGameTest/screenshots/`. In CI it runs only on a manual dispatch with "Run the client game test" ticked, under Xvfb and Mesa's software Vulkan.

### Fast inspection

Three tools sit between the unit tests and the client game test. The first two need no game, window or GL and finish in seconds.

**Wall previewer.** Bakes a scene of decals with `BloodField` exactly as `DecalRenderer` does, every block face over a range of ticks, and lays out an unfolded room (a 4×3 wall above a 4×4 floor) on stone grey with the block grid:

```sh
./gradlew previewBlood -Pscene=spray -Pto=120 -Pstep=2 -Poverlay
```

Scenes are `spray` (one hard hit through the same `Bleeding` decal code as the game, droplets landing at their flight times), `kill` (a killing blow's all-round burst and gib smears), `cluster`, `drips`, `pool`, `merge` (droplets landing on one spot through `DecalMerge`), or `all`. It writes `build/inspect/<scene>/frames/tNNNN.png`, `<scene>.apng` and `<scene>-sheet.png`, and prints decals, kernels, lit texels and mean alpha per tick. `-Poverlay` marks each kernel's centre and support, `-Pseed`, `-Pfrom`, `-Pscale` (pixels per texel) and `-Pgore.dryScale` change the run. Scenes live in `src/test/java/dev/lume/gore/PreviewScenes.java`.

**Flight check.** Flies every stain-carrying droplet of a spray scene the way `BloodDropParticle` does (`Ballistics.arcVelocity`/`arcPoint` over `born − launch` ticks) and fails if one does not end on its stain's `Decal.surfacePoint` on its born tick. It prints a table and draws side and top views of the paths to `build/inspect/flight/flight-<seed>.png`. `FlightCheckTest` runs the same check for 20 seeds in `./gradlew test`.

```sh
./gradlew flightCheck -Pseed=7 -Pseverity=0.9
```

**Particle trace.** With `-Dgore.trace=<file>`, every `BloodDropParticle` (types `flight`, `drop`, `micro`), `BloodMistParticle`, `BloodJetParticle` and `BloodGibParticle` (type `gib`) appends one JSON line per tick: id, type, game time, age, position, interpolation origin (`xo/yo/zo`), velocity, alpha and size. The client game test always writes `~/gore-run/clientGameTest/gore-trace.jsonl`. Analyse it with:

```sh
python3 tools/trace.py ~/gore-run/clientGameTest/gore-trace.jsonl
```

It prints a per-type summary, flags an interpolation origin that does not follow the previous tick's position, speeds above `--max-speed` (blocks/tick), skipped ticks and bad values, writes `trace-side.png` and `trace-top.png` with flagged points in yellow, and exits non-zero if anything was flagged.

`-Dgore.seed=<long>` is a test-only switch: it seeds the deterministic game-test harness so a run can be reproduced, and has no effect on normal play.

## Releasing

Pushing a `v<version>` tag that matches `version` in `gradle.properties` makes CI build the jar and publish a GitHub release:

```sh
# bump version= in gradle.properties, commit, then:
git tag v0.1.0 && git push origin main v0.1.0
```

## Known gaps

- The decals are the whole of the persistence: there is no fading, no washing blood off a wall. They stay until the budget evicts them.
- Pools spread but do not then run downhill or along a slope, and rain does not erode a pool faster than any other shape.
- A body stain is still placed by inference, not on the exact hit point. `GoreStain` records one (the server reconstructs it from the projectile, the attacker's gaze or the source position, and uses it for the spray origin), but mapping that point onto a model part needs a unit-correct pass through the renderer's transform that is not done yet; the wound is inferred onto the nearest sensible face instead. On a quadruped the centre of that face is the gap between the front legs, so some spatter is hidden by the legs, and on a very dark mob a dark gash reads less well than the bright spatter around it.
- Wash and fade are time-based, not physical: a stain under an overhang still dries, and a dropped bucket does not splash blood off a wall.
- No secondary runnel branching, no pool meniscus, no cast-off trails, no porous wicking (see plan 2026-10-16-blood-flow-realism, Deferred).
- Drips saved by versions before 0.3 (atlas cell 15) now render as static bead stains rather than animated runs; new drips use cell 14 and animate.

## License

CC0-1.0, as declared in `fabric.mod.json`.
