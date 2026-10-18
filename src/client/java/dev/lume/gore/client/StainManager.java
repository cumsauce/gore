package dev.lume.gore.client;

import dev.lume.gore.GoreStain;
import dev.lume.gore.StainPayloads;
import dev.lume.gore.StainStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Client-side mirror of the server's per-entity stain map for the current
 * level. The map is keyed by entity id and each entity's list is independently
 * capped at {@link StainStore#MAX_STAINS}, evicting oldest first.
 */
public final class StainManager {
	private static final Map<Integer, List<GoreStain>> BY_ENTITY = new HashMap<>();
	private static final Map<Integer, Long> RECEIVED_TICK = new HashMap<>();
	private static Identifier dimension;

	private StainManager() {
	}

	/**
	 * Replaces one entity's stain list with the authoritative list from the
	 * server, capping at {@link StainStore#MAX_STAINS} and evicting oldest first.
	 * An empty list removes the entity's entry entirely — that is how a wash
	 * arrives: the server sends an empty list meaning "this entity is clean".
	 */
	public static void add(int entityId, List<GoreStain> stains) {
		if (stains == null || stains.isEmpty()) {
			BY_ENTITY.remove(entityId);
			RECEIVED_TICK.remove(entityId);
			return;
		}

		List<GoreStain> list = new ArrayList<>(stains);
		while (list.size() > StainStore.MAX_STAINS) {
			list.remove(0);
		}
		BY_ENTITY.put(entityId, list);
		// Track when this stain set was received so age-driven effects (body
		// drips) can animate from a known origin even though GoreStain carries
		// no born field (protocol frozen).
		Minecraft mc = Minecraft.getInstance();
		long now = (mc.level != null) ? mc.level.getGameTime() : 0L;
		RECEIVED_TICK.put(entityId, now);
	}

	/**
	 * Replaces the whole map with an authoritative snapshot for the given
	 * dimension, sent on join and on dimension change. Drops everything when the
	 * dimension changed, then rebuilds from the entries.
	 */
	public static void replace(Identifier dimensionId, List<StainPayloads.Entry> entries) {
		if (dimension == null || !dimension.equals(dimensionId)) {
			BY_ENTITY.clear();
			dimension = dimensionId;
		}

		BY_ENTITY.clear();
		for (StainPayloads.Entry entry : entries) {
			add(entry.entityId(), entry.stains());
		}
	}

	public static void clear() {
		BY_ENTITY.clear();
		RECEIVED_TICK.clear();
		dimension = null;
	}

	/** Client game-time at which the entity's current stain set was received. */
	public static long receivedTick(int entityId) {
		return RECEIVED_TICK.getOrDefault(entityId, 0L);
	}

	/** Returns an immutable copy of the entity's stains, or an empty list if unknown. */
	public static List<GoreStain> snapshot(int entityId) {
		return List.copyOf(BY_ENTITY.getOrDefault(entityId, List.of()));
	}
}
