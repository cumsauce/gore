package dev.lume.gore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * In-memory, per-level map from entity id to that entity's body stains, with a
 * fixed budget (oldest evicted first). Deliberately not save data: stains on a
 * mob do not survive the mob's chunk unloading, so a dead or unloaded entity's
 * entry is only reclaimed by an explicit {@link #remove(int)}.
 */
public class StainStore {
	public static final int MAX_STAINS = 12;

	/** One store per level, keyed by the level's dimension key. */
	private static final Map<ResourceKey<Level>, StainStore> BY_LEVEL = new HashMap<>();

	private final Map<Integer, List<GoreStain>> byEntity = new HashMap<>();

	/** Returns the store for the given level, creating it on first use. */
	public static StainStore of(ServerLevel level) {
		return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new StainStore());
	}

	/** Adds a stain, evicting the entity's oldest stain once the cap is reached. */
	public void add(int entityId, GoreStain stain) {
		List<GoreStain> list = byEntity.computeIfAbsent(entityId, id -> new ArrayList<>());
		while (list.size() >= MAX_STAINS) {
			list.remove(0);
		}
		list.add(stain);
	}

	/** Returns an immutable copy of the entity's stains, or an empty list if unknown. */
	public List<GoreStain> get(int entityId) {
		return List.copyOf(byEntity.getOrDefault(entityId, List.of()));
	}

	/** Forgets an entity's stains; a no-op for an unknown id. */
	public void remove(int entityId) {
		byEntity.remove(entityId);
	}

	/** Returns an immutable copy of every entity's stain list. */
	public Map<Integer, List<GoreStain>> entries() {
		return Map.copyOf(byEntity);
	}
}
