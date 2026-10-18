package dev.lume.gore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Server side: gives a returning creature its wounds back.
 *
 * <p>{@link StainStore} keeps stains by UUID for the life of the world, but the
 * wire addresses entities by their per-session id, and a client that joins (or
 * comes back within range of) an entity needs the stains sent under the id it
 * currently has. This class notices entities the store knows about and sends
 * their stain list to the players near them, once per entity load.
 *
 * <p>There is no entity-load event in this API version, so the check is a sweep
 * over the stained UUIDs the store already holds — a few dozen at most — every
 * {@link #SWEEP_TICKS}. A UUID that is not currently loaded is skipped and
 * tried again next sweep; one that has been seen is remembered by
 * {@code (level, uuid) → entity id} so a still-loaded entity is not spammed
 * every sweep. The map is dropped when the entity's id changes (a reload) or
 * the entity is gone.
 */
public final class StainBinding {
	/** How often the sweep runs. Twice a second is invisible to a player. */
	private static final int SWEEP_TICKS = 10;

	/** Radius within which a player is told about an entity's wounds. */
	private static final double RANGE = 96.0;

	/** Last entity id each (level, uuid) was announced under. */
	private static final Map<ResourceKey<Level>, Map<UUID, Integer>> ANNOUNCED = new HashMap<>();

	private StainBinding() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(StainBinding::tick);
	}

	/** Forgets an entity's announcement, called when it dies. */
	public static void forget(ServerLevel level, UUID entityId) {
		Map<UUID, Integer> announced = ANNOUNCED.get(level.dimension());

		if (announced != null) {
			announced.remove(entityId);
		}
	}

	public static void clear() {
		ANNOUNCED.clear();
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % SWEEP_TICKS != 0) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			StainStore store = StainStore.of(level);
			Map<UUID, List<GoreStain>> entries = store.entries();

			if (entries.isEmpty()) {
				continue;
			}

			Map<UUID, Integer> announced = ANNOUNCED.computeIfAbsent(level.dimension(), key -> new HashMap<>());

			// Drop announcements for entities that are gone or whose id changed.
			announced.entrySet().removeIf(entry -> {
				Entity entity = level.getEntity(entry.getKey());
				return entity == null || entity.getId() != entry.getValue();
			});

			for (Map.Entry<UUID, List<GoreStain>> entry : entries.entrySet()) {
				if (entry.getValue().isEmpty()) {
					continue;
				}

				Entity entity = level.getEntity(entry.getKey());

				if (!(entity instanceof LivingEntity living) || living.isDeadOrDying()) {
					continue;
				}

				if (announced.containsKey(entry.getKey()) && announced.get(entry.getKey()) == living.getId()) {
					continue;
				}

				announced.put(entry.getKey(), living.getId());

				StainPayloads.AddPayload payload = new StainPayloads.AddPayload(living.getId(), entry.getValue());
				for (ServerPlayer player : PlayerLookup.around(level, living.position(), RANGE)) {
					ServerPlayNetworking.send(player, payload);
				}
			}
		}
	}
}
