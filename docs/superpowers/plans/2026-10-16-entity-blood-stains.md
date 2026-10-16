# Blood on bodies and the held weapon — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every living entity a blood stain welded to the model at the wound when it takes damage, and make the bloodied item in the player's hand look bloodied in first person.

**Architecture:** The server records a small, self-describing stain record on the victim at the damage event and syncs it over the mod's own channel. The client derives the anchor from that record (never from a wound point it would have to have witnessed), stores stains keyed by entity, publishes them into the entity's render state under a Fabric `RenderStateDataKey`, and draws them from a vanilla `RenderLayer` using the model-feature submit that already works on 26.3 for wall decals. The first-person weapon reuses the same stain quad and the same submit, driven from the hand render pass.

**Tech Stack:** Java 25, Fabric Loom 1.18.2, Minecraft 26.3 (unobfuscated), Fabric API 0.162.0+26.3. No Mixins — Fabric's class tweakers already inject everything needed.

**Spec:** `docs/superpowers/specs/2026-10-16-entity-blood-stains-design.md`

## Global Constraints

- Target is Minecraft `26.3` with official (unobfuscated) names; `gradle.properties` says `minecraft_version=26.3`, `loader_version=0.19.5`, `loom_version=1.18.2`, `fabric_api_version=0.162.0+26.3`. Do not change these.
- Java 25. Build with `./gradlew`, never the system `gradle`.
- **No Mixin config may be added.** Everything needed is reachable through Fabric's class tweakers from `fabric-rendering-v1`.
- All drawing is client-side. The server never renders and must never send anything a vanilla client could not ignore.
- **Nothing may damage, slow, or otherwise affect gameplay.** Blood is cosmetic only.
- The mod must keep working with the mod on the server only (then nothing renders) — no client-only class may be loaded from the server entrypoint.
- Rendering uses the **model feature** (`SubmitNodeCollector.submitModelPart`). `submitCustomGeometry` is inert on 26.3 and must not be used.
- Blood must never appear where no part is: a stain that cannot resolve to a real model part is skipped silently, never placed in mid-air and never thrown.
- Existing behaviour must not regress: wall decals, particles, the GUI item icon, and the `gore:blood` gear component all keep working exactly as they do today.
- Every new client class goes in `src/client/java/dev/lume/gore/client/`; every new shared/server class goes in `src/main/java/dev/lume/gore/`.

## Review Focus

1. **An entity takes damage, no client is nearby, then a player joins.** A player arriving after the fight must see a bloodied creature, not a clean one. The stain list has to be replayable from the record alone.
2. **A model with no part matching the wound region** (a slime, a squid, anything built from a single body part). Expected: nothing renders, nothing throws, no geometry appears detached from the body.
3. **An entity dies and is removed while it still has stains.** Its entry must not leak in the keyed store, and the next entity to take that id must not inherit its wounds.
4. **The player takes a hit with no weapon in hand.** Expected: the body stain still appears; nothing tries to stain a null or empty stack.
5. **A mob is hit enough times to pass the per-entity cap.** Expected: the count stops growing, the oldest is recycled, and the count is bounded in what gets stored and sent.

## File Structure

**Server / shared (`src/main/java/dev/lume/gore/`)**

- `GoreStain.java` (create) — the stain record: source type, quantised direction, size, rotation, variant. Plus its `Codec` and `StreamCodec`, so it can travel both over the network and (later) into save data.
- `StainStore.java` (create) — server-side, level-scoped, keyed by entity id: add, cap/evict, get, remove. Owns the cap constant.
- `StainWounds.java` (create) — server-side: turns a `DamageSource` plus the already-computed impact direction into a `GoreStain`. Owns the variant and size rolls.
- `StainPayloads.java` (create) — the two payload types and their codecs: incremental adds, and full replay.
- `StainSync.java` (create) — sending to the players who can see an entity, and the join / dimension-change replay. Mirrors `Sync.java`.
- `Gore.java` (modify) — register the payload types.
- `Bleeding.java` (modify) — at the damage event, record a stain and send it; grow the cap through the store.
- `Wash.java` (modify) — clear an entity's stains in water, any entity, not only players.

**Client (`src/client/java/dev/lume/gore/client/`)**

- `StainManager.java` (create) — the client-side keyed store of stains, with the same cap, plus `snapshot(entityId)`.
- `StainQuad.java` (create) — the shared stain geometry: one baked `ModelPart` per atlas variant, the offset constant, and `submit(collector, poseStack, variant, size, rot, light)`. `DecalRenderer` will be refactored onto this so a wall, a body and a weapon cannot drift apart.
- `BodyStainLayer.java` (create) — the `RenderLayer`: reads stains off the render state, resolves the anchor to a `ModelPart`, submits.
- `StainAnchor.java` (create) — client-side: damage source type to region, and region to `ModelPart` resolution against the parent model, with the per-feature clamps and the fallback chain.
- `StainStateKeys.java` (create) — the `RenderStateDataKey` for the stain list, and the extraction helper.
- `HeldItemStains.java` (create) — first-person weapon stains.
- `GoreClient.java` (modify) — register the layer callback, the receivers, the invalidation callback, the hand-pass hook.

**Tests**

- Client game tests in `src/gametest/java/dev/lume/gore/gametest/` for anything visual, driven by the existing `GoreClientGameTest` harness and checked against screenshots with the PNG tools already in the temp dir.

---

### Task 1: The stain record

Pure data, no behaviour, so it can be built and tested before anything consumes it. This is the contract every later task agrees on.

**Files:**
- Create: `src/main/java/dev/lume/gore/GoreStain.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record GoreStain(int sourceType, int dirX, int dirY, int dirZ, float size, float rot, int variant)` with `static final Codec<GoreStain> CODEC` and `static final StreamCodec<ByteBuf, GoreStain> STREAM_CODEC`.

- [ ] **Step 1: Write the record with its codecs**

Direction is stored as three quantised bytes so the server and the client agree on the anchor to well within a pixel at body scale; the client decodes them back to a unit vector. Size and rotation follow `Decal`'s existing treatment (`FLOAT` and `ROTATION_BYTE`); `variant` is a `VAR_INT` bounded to `0..11` because body stains use impact cells only.

```java
public record GoreStain(int sourceType, int dirX, int dirY, int dirZ, float size, float rot, int variant) {
    public static final Codec<GoreStain> CODEC = ...;      // RecordCodecBuilder, mirror Decal.CODEC
    public static final StreamCodec<ByteBuf, GoreStain> STREAM_CODEC = ...;  // composite, mirror Decal.STREAM_CODEC
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/lume/gore/GoreStain.java
git commit -m "Add the body stain record and its codecs"
```

---

### Task 2: The server-side store, with a bounded cap

The cap is the anti-goal guard from the spec: a long-lived mob must not accumulate without bound.

**Files:**
- Create: `src/main/java/dev/lume/gore/StainStore.java`

**Interfaces:**
- Consumes: `GoreStain` (Task 1).
- Produces: `class StainStore` with `static final int MAX_STAINS`, and instance methods `void add(int entityId, GoreStain stain)`, `List<GoreStain> get(int entityId)`, `void remove(int entityId)`, `Map<Integer, List<GoreStain>> entries()`.

- [ ] **Step 1: Write the store, mirroring `DecalStore`'s eviction shape**

`DecalStore` already establishes the pattern: a list per key, oldest evicted first, cap enforced on add. Follow it. Storing per entity id means a dead entity's entry is only reclaimed by an explicit `remove`.

```java
public class StainStore {
    public static final int MAX_STAINS = 12;
    private final Map<Integer, List<GoreStain>> byEntity = new HashMap<>();
    // add: get-or-create list, while (list.size() >= MAX_STAINS) list.remove(0), then list.add(stain)
    // get: List.copyOf(byEntity.getOrDefault(entityId, List.of()))
    // remove: byEntity.remove(entityId)
    // entries: Map.copyOf(byEntity)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/lume/gore/StainStore.java
git commit -m "Add the per-entity server stain store"
```

---

### Task 3: Turning a damage source into a stain

Keeps the decision of *how big* and *which cell* in one place, and keeps `Bleeding` from growing another branch.

**Files:**
- Create: `src/main/java/dev/lume/gore/StainWounds.java`

**Interfaces:**
- Consumes: `GoreStain` (Task 1).
- Produces: `static GoreStain create(DamageSource source, Vec3 impactDirection, float severity, RandomSource random)`.

- [ ] **Step 1: Write the generator**

The source type is the integer the client's region table will switch on; the direction is the server's already-computed impact direction, quantised to bytes. Size follows `Bleeding.decalAt`'s existing shape so a body stain reads at the same scale as a wall splat. Variant is restricted to the twelve impact cells, never the four pool cells.

```java
public static GoreStain create(DamageSource source, Vec3 dir, float severity, RandomSource random) {
    // sourceType: from the damage type registry, an int id stable for the session
    // dirN: normalise, then scale each component by 127 and round to a byte
    // size: 0.22f + 0.18f * random.nextFloat() + severity * 0.10f, clamped to <= 0.42f
    // rot: random.nextFloat() * 360.0f
    // variant: random.nextInt(12)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/lume/gore/StainWounds.java
git commit -m "Turn a damage source into a body stain record"
```

---

### Task 4: Payloads and sync

Mirrors `GorePayloads` and `Sync` exactly, because those are known to work and the join-replay problem has the same shape.

**Files:**
- Create: `src/main/java/dev/lume/gore/StainPayloads.java`
- Create: `src/main/java/dev/lume/gore/StainSync.java`
- Modify: `src/main/java/dev/lume/gore/Gore.java`

**Interfaces:**
- Consumes: `GoreStain` (Task 1), `StainStore` (Task 2).
- Produces: `StainPayloads.AddPayload(int entityId, List<GoreStain> stains)`, `StainPayloads.SyncPayload(Identifier dimension, List<StainPayloads.Entry> entries)` where `Entry(int entityId, List<GoreStain> stains)`, both with `TYPE` constants and codecs; `StainSync.sendAdd(ServerLevel, LivingEntity)`, `StainSync.sendTo(ServerPlayer)`, `StainSync.sendRemove(ServerLevel, int entityId)`.

- [ ] **Step 1: Write the two payloads and their codecs**

`AddPayload` carries what one hit produced. `SyncPayload` carries the whole map for a level so a joining player sees creatures already bloodied — the Review Focus case. `GorePayloads` shows the exact `StreamCodec.composite` pattern to copy.

- [ ] **Step 2: Write the sync helpers, following `Sync.java`**

`sendAdd` sends `AddPayload` to `PlayerLookup.around(level, victim.position(), 96.0)` — the same radius the decals use. `sendTo` reads the store for the player's level and sends the full `SyncPayload`. `sendRemove` sends an `AddPayload` with an empty list, which the client reads as "this entity is clean".

- [ ] **Step 3: Register the payload types in `Gore.onInitialize`**

Add two `PayloadTypeRegistry.clientboundPlay().register(...)` lines next to the existing two. Nothing else in `Gore` changes in this task.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/lume/gore/StainPayloads.java src/main/java/dev/lume/gore/StainSync.java src/main/java/dev/lume/gore/Gore.java
git commit -m "Add body stain payloads and sync"
```

---

### Task 5: Record and send a stain on every hit

The server half of the feature, finished and provable before any client work.

**Files:**
- Modify: `src/main/java/dev/lume/gore/Bleeding.java`
- Modify: `src/main/java/dev/lume/gore/Gore.java`

**Interfaces:**
- Consumes: `GoreStain`, `StainStore`, `StainWounds.create(...)` (Tasks 1-3), `StainSync.sendAdd(...)` (Task 4).
- Produces: a `StainStore` owned by the level, reachable as `LevelStains.of(ServerLevel)`.

- [ ] **Step 1: Give the server a place to keep the store**

Follow how `DecalStore` is obtained, but a per-entity map is not save data, so a small static held in the mod is the right shape. Add `LevelStains` — a `Map<ResourceKey<Level>, StainStore>` with `static StainStore of(ServerLevel level)` — either as its own file or as the accessor on `StainStore`. Keep it in `StainStore.java` to avoid another file.

- [ ] **Step 2: Add the stain at the damage event**

In `Bleeding.afterDamage`, after the existing `markGear(victim, source, severity, random)` call, create a stain with `StainWounds.create(source, impactDir, severity, random)`, add it to the level's store keyed by `victim.getId()`, and call `StainSync.sendAdd(level, victim)`. Both the stain and the cap are already computed — this step adds no new numbers.

- [ ] **Step 3: Send the join replay**

`Gore` already registers `ServerPlayConnectionEvents.JOIN` and `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL`, both calling `Sync.sendTo`. Add a `StainSync.sendTo(player)` call to both existing lambdas.

- [ ] **Step 4: Write a server game test for the cap and the source type**

Add a server-side test source file `src/test/java/dev/lume/gore/StainStoreTest.java` covering: twelve adds then a thirteenth leaves exactly `MAX_STAINS` entries with the oldest gone; `get` on an unknown id returns empty; `remove` then `get` returns empty. Use plain JUnit 5 (Loom puts it on the test classpath); no Minecraft bootstrap needed, because `StainStore` and `GoreStain` are plain data.

- [ ] **Step 5: Run the tests to verify they fail, then pass**

Run: `./gradlew test --tests '*StainStoreTest*'`
Expected: FAIL first (no test source set configured), then PASS once `test { useJUnitPlatform() }` is added to `build.gradle` and the assertions are written.

- [ ] **Step 6: Verify the whole thing still builds and the client test still renders**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/lume/gore/Bleeding.java src/main/java/dev/lume/gore/Gore.java src/main/java/dev/lume/gore/StainStore.java src/test/java/dev/lume/gore/StainStoreTest.java build.gradle
git commit -m "Record a body stain on every hit and keep the list bounded"
```

---

### Task 6: Sharing the stain quad

Refactor before adding a second caller, so the wall, the body and the weapon cannot drift apart. No behaviour changes; the wall decals must look identical after this.

**Files:**
- Create: `src/client/java/dev/lume/gore/client/StainQuad.java`
- Modify: `src/client/java/dev/lume/gore/client/DecalRenderer.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `static void StainQuad.submit(SubmitNodeCollector collector, PoseStack poseStack, int variant, float size, float rot, int light, Vector3f outward)` which orients, places and submits one stain quad, and `static void StainQuad.init()` which lazily builds the render type.

- [ ] **Step 1: Move the baked variants and the submit into `StainQuad`**

`DecalRenderer` already holds exactly this: `VARIANTS`/`buildVariants()`, the `MODEL_FRONT` vector, the lazy `RenderType`, the `rotateTo`/`rotateDegrees`/`scale` sequence and the `submitModelPart` call. Move them; `DecalRenderer.submit` keeps the world-space maths (face, axes, centre, light) and calls `StainQuad.submit(...)`.

- [ ] **Step 2: Point `DecalRenderer` at it**

`DecalRenderer.render` calls `StainQuad.init()` once, and `DecalRenderer.submit` passes `normal` as `outward`. Delete the moved code from `DecalRenderer`.

- [ ] **Step 3: Verify the wall decals are unchanged**

Run: `./gradlew runClientGameTest` (about 45s)
Expected: `BUILD SUCCESSFUL`; the crop `250 120 620 360` of `~/gore-run/clientGameTest/screenshots/0002_gore-splatters.png` shows the same red splatter on the wall as before this task.

- [ ] **Step 4: Commit**

```bash
git add src/client/java/dev/lume/gore/client/StainQuad.java src/client/java/dev/lume/gore/client/DecalRenderer.java
git commit -m "Share the stain quad between wall decals and body stains"
```

---

### Task 7: The client store and the render-state key

The client half of the data path. Still nothing drawn.

**Files:**
- Create: `src/client/java/dev/lume/gore/client/StainManager.java`
- Create: `src/client/java/dev/lume/gore/client/StainStateKeys.java`

**Interfaces:**
- Consumes: `GoreStain` (Task 1).
- Produces: `static void StainManager.add(int entityId, List<GoreStain> stains)`, `static void StainManager.replace(Identifier dimension, List<StainPayloads.Entry> entries)`, `static void StainManager.clear()`, `static List<GoreStain> StainManager.snapshot(int entityId)`; and `static final RenderStateDataKey<List<GoreStain>> StainStateKeys.STAINS`.

- [ ] **Step 1: Write `StainManager`, mirroring `DecalManager`**

`DecalManager` is the model: a `Deque` with a `MAX` cap, `addDecals`, `sync` keyed by dimension, `clear`, `snapshot`. Here the map is keyed by entity id and each list is independently capped at `StainStore.MAX_STAINS`, evicting oldest first. `add` with an empty list removes the entity's entry (that is how a wash arrives). `replace` drops everything when the dimension changes, then rebuilds.

- [ ] **Step 2: Write the key**

`RenderStateDataKey.create(() -> "gore:stains")` typed `List<GoreStain>`. One key, one list, read by the layer.

- [ ] **Step 3: Write a test for the store**

Add `src/test/java/dev/lume/gore/client/StainManagerTest.java`. Problem: `StainManager` is in the `client` source set, which the plain `test` task does not see. Keep this test out of the plain unit suite and instead cover it in the gametest in Task 8 — a store this small is exercised end to end there. Delete nothing; simply do not add this file.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileClientJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/lume/gore/client/StainManager.java src/client/java/dev/lume/gore/client/StainStateKeys.java
git commit -m "Add the client stain store and render-state key"
```

---

### Task 8: The body stain layer

The core deliverable: a stain that appears at the wound and moves with the body.

**Files:**
- Create: `src/client/java/dev/lume/gore/client/StainAnchor.java`
- Create: `src/client/java/dev/lume/gore/client/BodyStainLayer.java`
- Modify: `src/client/java/dev/lume/gore/client/GoreClient.java`

**Interfaces:**
- Consumes: `StainQuad.submit(...)` (Task 6), `StainManager.snapshot(int)` and `StainStateKeys.STAINS` (Task 7).
- Produces: `class BodyStainLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>>` with `public void submit(PoseStack, SubmitNodeCollector, int, LivingEntityRenderState, float, float)`.

- [ ] **Step 1: Write `StainAnchor`**

Two jobs, both client-side and instance-free.

The region table maps the source type to one of `BODY`, `CHEST`, `HEAD`, `ARM`, `LEG`, `FOOT` — melee and generic to `BODY`, projectile and thrown to `CHEST`, explosion to `BODY` biased toward the blast, fire/lava/magma/hot-floor to `LEG`, fall to `FOOT`. The region is then resolved against the parent model: try the region's own part, then fall back down a fixed chain (e.g. `ARM` to `BODY`, `FOOT` to `LEG`, `LEG` to `BODY`, `BODY` to whatever single part exists), and if nothing resolves, return empty so the caller skips the stain. A small model gets an explicitly reduced offset clamp rather than a humanoid-sized one.

```java
// enum Region { BODY, CHEST, HEAD, ARM, LEG, FOOT }
// static Region regionFor(int sourceType, Vec3 dir)
// static Optional<ModelPart> resolve(GeometryModel model, Region region, Vec3 dir)
//   where GeometryModel is net.minecraft.client.model.Model<?>
```

- [ ] **Step 2: Write the layer**

Read the stain list from the render state with `renderState.getData(StainStateKeys.STAINS)` (the interface is injected into `EntityRenderState` by Fabric; no mixin). Return immediately when it is null or empty, so an unbloodied entity costs nothing. For each stain, decode the direction from its three bytes to a unit vector, resolve the part, and inside `part.translateAndRotate(poseStack)` submit the quad placed just off the part's front face along the decoded direction, using the same `OFFSET` logic as the wall decals and the same light as the entity.

- [ ] **Step 3: Register the layer for every living entity**

In `GoreClient.onInitializeClient`, register `LivingEntityRenderLayerRegistrationCallback.EVENT`, calling `helper.register(new BodyStainLayer(...))` — `registerLayers` receives the `EntityType`, the renderer and the helper, so one registration covers all living entities.

- [ ] **Step 4: Grow the culling box when the entity is bloodied**

Override the render layer's parent-box path — or, if the layer cannot express it, do it through the same `RenderStateDataKey` on the render state — so an entity carrying stains has a box that already contains the stain geometry before submission. This is the Review Focus case where blood can be culled away.

- [ ] **Step 5: Extract the stains into the render state and invalidate on arrival**

Subscribe `ClientPlayNetworking` for both stain payloads, calling `StainManager` then `InvalidateRenderStateCallback`. In `GoreClient`, drive the extraction: hook the point in the client tick or render where entity render states are produced so `setData(StainStateKeys.STAINS, StainManager.snapshot(entity.getId()))` happens for each visible entity.

- [ ] **Step 6: Verify with a client game test**

Extend `GoreClientGameTest` with a third wounding pass, then screenshot the cow: set `time set noon`, damage the cow twice with `player_attack` as before, aim at the cow and `takeScreenshot("gore-body")`. Check the crop with the temp-dir `dominant.py` for red pixels on the cow's silhouette, not only on the wall behind it.

- [ ] **Step 7: Run it and confirm**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL`, and `~/gore-run/clientGameTest/screenshots/0003_gore-body.png` shows red on the cow's body.

- [ ] **Step 8: Commit**

```bash
git add src/client/java/dev/lume/gore/client/StainAnchor.java src/client/java/dev/lume/gore/client/BodyStainLayer.java src/client/java/dev/lume/gore/client/GoreClient.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java
git commit -m "Draw body stains welded to the entity's own model"
```

---

### Task 9: Washing

The last rule of the feature: water is the only cleaner.

**Files:**
- Modify: `src/main/java/dev/lume/gore/Wash.java`

**Interfaces:**
- Consumes: `LevelStains`/`StainStore` (Task 5), `StainSync.sendRemove(...)` (Task 4).
- Produces: nothing new; `Wash.tick` additionally clears stains.

- [ ] **Step 1: Extend the wash to entities and to stains**

`Wash.tick` currently loops players and cleans their item components. Widen it to loop all `LivingEntity`s in water — the players it already visits plus the mobs, which is the deliberate step past the shipped gear rule. For each, clear their entry in the level's stain store and `StainSync.sendRemove(level, entity.getId())`. Leave the existing gear cleaning exactly as it is.

- [ ] **Step 2: Write a test for the removal path**

Add to `src/test/java/dev/lume/gore/StainStoreTest.java`: adding stains and then `remove` leaves `get` empty and `entries` without the id — the same operation `Wash` performs.

- [ ] **Step 3: Run the tests**

Run: `./gradlew test --tests '*StainStoreTest*'`
Expected: PASS.

- [ ] **Step 4: Verify the build and the client test**

Run: `./gradlew build` and `./gradlew runClientGameTest`
Expected: both `BUILD SUCCESSFUL`; the wall and body screenshots from earlier tasks are unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/lume/gore/Wash.java src/test/java/dev/lume/gore/StainStoreTest.java
git commit -m "Wash body stains off any entity that enters water"
```

---

### Task 10: The first-person weapon

The second deliverable, independent of the body work.

**Files:**
- Create: `src/client/java/dev/lume/gore/client/HeldItemStains.java`
- Modify: `src/client/java/dev/lume/gore/client/GoreClient.java`

**Interfaces:**
- Consumes: `StainQuad.submit(...)` (Task 6).
- Produces: `static void HeldItemStains.render(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, long seed)`.

- [ ] **Step 1: Write the holder-item stain renderer**

Gate on `stack.get(Gore.BLOOD)` being present and positive; otherwise return. Derive a stable `Random` from the stack's item hash plus a per-stack salt, so the same bloodied sword always looks the same. Draw a small number of stains scaled to the item's own bounds, submitted in the pose it was handed — since the caller's pose is already the item's, anything submitted here is welded to it.

- [ ] **Step 2: Call it from the first-person hand pass**

Hook the point where the first-person item model is submitted. `FirstPersonHandsAndItemsRenderer` exposes `submitHandsWithItems(float, PoseStack, SubmitNodeCollector, PlayerRenderState, FirstPersonHandsAndItemsRenderState)` and its render state carries `mainHandItem`/`offHandItem` plus the item render states; if no event exposes that pass on this Fabric version, mixin-free fallback is to drive it from the existing level render hook where the camera is the player and `handRenderSelection` says a hand is drawn. Prefer an exposed hook; do not add a Mixin.

- [ ] **Step 3: Verify with a client game test**

The existing `0001_gore-gear.png` inventory screenshot already proves the GUI icon. Add a screenshot in first person with the bloodied sword held, aimed at a plain wall so the hand is clearly visible, and confirm red on the sword's silhouette rather than only on the icon.

- [ ] **Step 4: Run it and confirm**

Run: `./gradlew runClientGameTest`
Expected: `BUILD SUCCESSFUL`, and a `gore-hand` screenshot showing blood on the held sword in first person.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/dev/lume/gore/client/HeldItemStains.java src/client/java/dev/lume/gore/client/GoreClient.java src/gametest/java/dev/lume/gore/gametest/GoreClientGameTest.java
git commit -m "Show blood on the held weapon in first person"
```

---

### Task 11: Documentation, version and release

**Files:**
- Modify: `README.md`
- Modify: `gradle.properties`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code-facing.

- [ ] **Step 1: Bump the version**

`gradle.properties`: `version=0.2.0`. This is a feature, not a patch.

- [ ] **Step 2: Update the README**

Add a bullet under **How it works** describing body stains: placed at the wound from the damage source, welded to the model and animated with it, accumulating to a per-entity cap, washed off in water, and replayed to players who join mid-fight. Note the first-person weapon alongside the existing gear bullet. In **Known gaps**, add that mob stains do not survive a chunk unload and reload, and that the wound region is inferred from the damage source rather than from a true hit point. Keep every existing bullet, including the wall-decal rendering note.

- [ ] **Step 3: Verify the whole project builds clean**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` with no new warnings in the mod's own classes.

- [ ] **Step 4: Commit**

```bash
git add README.md gradle.properties
git commit -m "Document body and held-weapon blood; bump to 0.2.0"
```

- [ ] **Step 5: Release on user confirmation only**

Do not tag or push a release without the user asking. When they do: `git tag v0.2.0 && git push origin main v0.2.0`.

---

## Self-review

**Spec coverage.** Every part of the spec maps to a task: the record (1), the store and its cap (2), the region source (3), sync and join replay (4, 5), the shared quad and its reuse by wall decals (6), the client store and the render-state key (7), the layer, the anchor resolution, the box growth and the invalidation (8), washing on any entity (9), the first-person weapon (10), docs and release (11). The spec's "best effort, never throw" requirement is Task 8 step 1's optional return and step 2's early return. The spec's anti-goals are pinned by Task 2 step 4's cap test, Task 8 step 6's screenshot, and Task 9's wash test.

**Step scan.** Each step names a file, a signature, or a command with an expected result. The two steps that would otherwise decide nothing — how the region table splits and where the first-person hook lives — are given concrete default tables and a stated preference order instead of being left open.

**Type consistency.** `GoreStain`'s field names are used identically in Tasks 2, 3, 4, 7, 8 and 10. `StainManager.snapshot(int)` is called the same way in Tasks 7 and 8. `StainQuad.submit(...)` has one signature used by both Task 6 and Task 10. `StainStore.MAX_STAINS` is the single cap, referenced by Task 2 and restated in Task 7. `StainSync.sendAdd/sendTo/sendRemove` are defined in Task 4 and used unchanged in Tasks 5 and 9.

**Review Focus.** Each of the five lines has a home: join replay is Task 5 step 3 plus Task 8 step 5; no-matching-part is Task 8 step 1's fallback chain; dead entity leaking is Task 9 step 2's removal test; no weapon in hand is Task 10 step 1's early return; the cap is Task 2 step 4 and Task 9 step 2.

**Proportion.** The spec is a design document; this plan is its task breakdown with the decisions the implementer cannot make alone — names, signatures, caps, thresholds and the verification commands. Code appears only where a signature and a test would not determine the body (the store's eviction loop, the payload shape, the anchor chain).
