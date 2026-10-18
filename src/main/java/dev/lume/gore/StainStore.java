package dev.lume.gore;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-level map from a creature's UUID to that creature's body stains, with a
 * fixed budget per entity (oldest evicted first) and across the whole level.
 *
 * <p>Keyed by UUID and stored as level {@link SavedData}, so a mob keeps its
 * wounds across a chunk unload and a restart. Entity ids will not do for that:
 * they are per-session numbers, and a stain saved under one id would attach
 * itself to whatever unrelated entity inherits that id next time. The network
 * still addresses entities by id (clients only know ids), so callers that send
 * payloads resolve the UUID through the entity itself.
 *
 * <p>Stains of entities that die or wander into unloaded chunks and never come
 * back are reclaimed by the level-wide budget, oldest first — there is no
 * discard event to hook, and a corpse's wounds are not worth leaking for.
 */
public class StainStore extends SavedData {
	/** Cap per entity; enough for several hits' worth of gash and spatter. */
	public static final int MAX_STAINS = 16;

	/** Atlas cell of the first wound laceration; cells below it are impact spatter. */
	private static final int WOUND_BASE = 16;

	/** Cap on bloodied entities remembered per level, oldest evicted first. */
	private static final int MAX_ENTITIES = 512;

	/**
	 * One creature's saved wounds. A list of these rather than a keyed map: a
	 * UUID is an int-stream in the game's own codec, which cannot be a map key
	 * in every serialisation format, and a stable list format survives a future
	 * change of key just as well.
	 */
	private record Entry(UUID id, List<GoreStain> stains) {
		static final Codec<Entry> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
				GoreStain.CODEC.listOf().fieldOf("stains").forGetter(Entry::stains)
		).apply(instance, Entry::new));
	}

	public static final Codec<StainStore> CODEC = Entry.CODEC.listOf().xmap(
			entries -> {
				Map<UUID, List<GoreStain>> byUuid = new LinkedHashMap<>();
				for (Entry entry : entries) {
					byUuid.put(entry.id(), new ArrayList<>(entry.stains()));
				}
				return new StainStore(byUuid);
			},
			store -> {
				List<Entry> entries = new ArrayList<>(store.byUuid.size());
				store.byUuid.forEach((id, stains) -> entries.add(new Entry(id, List.copyOf(stains))));
				return entries;
			});

	public static final SavedDataType<StainStore> TYPE = new SavedDataType<>(
			// Built without Gore.id: this class is unit-tested, and loading the
			// mod initializer would touch the registry and need a bootstrapped game.
			net.minecraft.resources.Identifier.fromNamespaceAndPath(Gore.MOD_ID, "stains"),
			StainStore::new,
			CODEC,
			null
	);

	private final Map<UUID, List<GoreStain>> byUuid;

	/** Returns the store for the given level, creating it on first use. */
	public static StainStore of(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public StainStore() {
		this(new LinkedHashMap<>());
	}

	private StainStore(Map<UUID, List<GoreStain>> initial) {
		// LinkedHashMap so eviction order and iteration order are insertion
		// order: re-adding an entity refreshes its wounds as "recent".
		byUuid = new LinkedHashMap<>(initial);
	}

	/** Adds a stain to the entity, evicting its oldest once the cap is reached. */
	public void add(UUID entityId, GoreStain stain) {
		Objects.requireNonNull(stain, "stain");
		List<GoreStain> list = byUuid.computeIfAbsent(entityId, id -> new ArrayList<>());

		// Gash accumulation: hitting the same spot again deepens the wound into a
		// bigger one instead of stacking two gashes in exactly the same place.
		//
		// Only wound cells merge. The spatter a hit throws has its own, slightly
		// different direction per fleck; merging on direction alone would collapse
		// that spray straight back into the single blob it exists to avoid.
		int mergeIdx = -1;
		for (int i = 0; i < list.size(); i++) {
			GoreStain existing = list.get(i);
			if (existing.sourceType() != stain.sourceType()) continue;
			if (existing.variant() < WOUND_BASE || stain.variant() < WOUND_BASE) continue;
			int ddx = Math.abs(existing.dirX() - stain.dirX());
			int ddy = Math.abs(existing.dirY() - stain.dirY());
			int ddz = Math.abs(existing.dirZ() - stain.dirZ());
			if (ddx + ddy + ddz < 60) {
				mergeIdx = i;
				break;
			}
		}

		if (mergeIdx >= 0) {
			GoreStain existing = list.get(mergeIdx);
			// The merged wound keeps the older birth stamp — a reopened cut does
			// not reset to wet, it just has more blood in it now.
			GoreStain merged = new GoreStain(
					existing.sourceType(),
					existing.dirX(), existing.dirY(), existing.dirZ(),
					stain.hasHit() ? stain.hitX() : existing.hitX(),
					stain.hasHit() ? stain.hitY() : existing.hitY(),
					stain.hasHit() ? stain.hitZ() : existing.hitZ(),
					existing.born(),
					Math.min(0.7f, existing.size() * 1.15f),
					existing.rot(),
					existing.variant());
			list.set(mergeIdx, merged);
		} else {
			while (list.size() >= MAX_STAINS) {
				list.remove(0);
			}
			list.add(stain);
		}

		// Refresh recency and enforce the level-wide budget.
		List<GoreStain> recent = byUuid.remove(entityId);
		if (recent != null) {
			byUuid.put(entityId, recent);
		}

		while (byUuid.size() > MAX_ENTITIES) {
			UUID oldest = byUuid.keySet().iterator().next();
			byUuid.remove(oldest);
		}

		setDirty();
	}

	/** Adds a stain to the entity the way every caller has it: by entity. */
	public void add(Entity entity, GoreStain stain) {
		add(entity.getUUID(), stain);
	}

	/** Returns an immutable copy of the entity's stains, or an empty list if unknown. */
	public List<GoreStain> get(UUID entityId) {
		return List.copyOf(byUuid.getOrDefault(entityId, List.of()));
	}

	/** Forgets an entity's stains; a no-op for an unknown id. */
	public void remove(UUID entityId) {
		if (byUuid.remove(entityId) != null) {
			setDirty();
		}
	}

	/**
	 * Returns an immutable copy of every entity's stain list. Both the map and
	 * each value list are deep copies, so callers cannot mutate store state.
	 */
	/** Returns an immutable copy of every entity's stain list. Both the map and
	 * each value list are deep copies, so callers cannot mutate store state.
	 */
	public Map<UUID, List<GoreStain>> entries() {
		Map<UUID, List<GoreStain>> copy = new HashMap<>();
		byUuid.forEach((entityId, list) -> copy.put(entityId, List.copyOf(list)));
		return Map.copyOf(copy);
	}

	/** The bloodied UUIDs, without copying their stain lists. */
	public java.util.Set<UUID> ids() {
		return java.util.Set.copyOf(byUuid.keySet());
	}

	/** True when no entity is bloody in this level. */
	public boolean isEmpty() {
		return byUuid.isEmpty();
	}
}
