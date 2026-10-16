# Gore

Blood that behaves like blood: creatures bleed when they are cut, hard impacts throw it, and it stays where it lands. A Fabric mod for Minecraft 26.3.

Status: **0.1.1**, on the [releases page](https://github.com/cumsauce/gore/releases).

## The idea

Hit something and it bleeds. A glancing blow leaves it dripping; a solid one makes it gush. Hit something hard enough — an arrow, a sprint crit, an axe — and the blood leaves the body with force: a mist sprays in the direction the blow was travelling, and whatever it meets on the way becomes a splatter. Blood that finds a wall, a ceiling or a floor sticks there and stays. Blood that doesn't falls to the ground as droplets.

It is all cosmetic. Bleeding never costs health or damages anything; the wound is what the hit already did. Nothing is added to the game that a vanilla client would need to understand.

## How it works

- **Severity** comes from the damage actually taken, measured against the creature's maximum health, so a hit that nearly kills a chicken is not the same as one that barely dents a ravager.
- **Direction** is the projectile's own velocity when there is a projectile, otherwise the line from attacker to victim, otherwise the damage source's position. A blow from below throws blood upward into the ceiling; a hit from the side paints the wall behind.
- **Splatter** is a set of jittered rays cast from the wound, clipped against block collision shapes. Each ray that lands stores a decal: which block, which face, where on the face, how big, and which cell of the decal texture atlas to use. Twelve of the sixteen atlas cells are impact splats, biased by direction; the last four are the pools a heavy wound leaves on the ground.
- **Decals persist.** They live in a per-level `SavedData` capped at 1024, oldest evicted first, written to `dimensions/<dim>/data/gore/decals.dat`. They survive restarts, and they are per-dimension.
- **Everyone sees them.** The server is authoritative: new decals go to every player within 96 blocks as they are made, and a joining player — or one changing dimension — receives the full list for the level they are entering.
- **Gear gets bloody.** The weapon that drew the blood, and the victim's armor, each carry a `gore:blood` component they accumulate over hits. Player gear rinses clean in water; the component is per-item, so the blood is on the item wherever it goes.
- **Rendering** is done through the level render collector: decals are drawn as lit, textured quads offset a few millimetres off the block face so they never z-fight with it, and each is lit by the block behind it. Particles and item-icon splats are drawn by the client's own paths.

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

## Releasing

Pushing a `v<version>` tag that matches `version` in `gradle.properties` makes CI build the jar and publish a GitHub release:

```sh
# bump version= in gradle.properties, commit, then:
git tag v0.1.0 && git push origin main v0.1.0
```

## Known gaps

- The decals are the whole of the persistence: there is no fading, no drying, no washing blood off a wall. They stay until the budget evicts them.
- Mobs do not rinse their own gear in water; only players do.
- Rain does not disturb decals.
- A wounded creature does not leave a trail as it walks.

## License

CC0-1.0, as declared in `fabric.mod.json`.
