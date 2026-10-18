package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class StainSync {
	private StainSync() {
	}

	/**
	 * Sends the entity's full current stain list to the players who can see it —
	 * the same radius the decals use. An empty list tells the client the entity
	 * is clean, so this doubles as the removal path.
	 */
	public static void sendAdd(ServerLevel level, LivingEntity victim) {
		StainStore store = StainStore.of(level);
		StainPayloads.AddPayload payload = new StainPayloads.AddPayload(victim.getId(), store.get(victim.getUUID()));

		for (ServerPlayer player : PlayerLookup.around(level, victim.position(), 96.0)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * Sends the full stain map of the player's current level. The store is keyed
	 * by UUID (so it survives a restart), but the wire addresses entities by the
	 * id the client knows, so only stains whose entity is currently loaded can be
	 * sent. Entities that load later are re-sent by {@code StainBinding} once
	 * they are added to the world.
	 */
	public static void sendTo(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		StainStore store = StainStore.of(level);
		List<StainPayloads.Entry> entries = new ArrayList<>();

		for (Map.Entry<UUID, List<GoreStain>> entry : store.entries().entrySet()) {
			Entity entity = level.getEntity(entry.getKey());

			if (entity != null && !entry.getValue().isEmpty()) {
				entries.add(new StainPayloads.Entry(entity.getId(), entry.getValue()));
			}
		}

		ServerPlayNetworking.send(player, new StainPayloads.SyncPayload(level.dimension().identifier(), List.copyOf(entries)));
	}

	/**
	 * Tells clients in the level that the entity is clean again, by sending an
	 * empty {@link StainPayloads.AddPayload}. The client reads an empty list as
	 * "this entity is clean". Sent to the whole level rather than around a
	 * position because by wash time the entity may be gone, and the payload is
	 * tiny.
	 */
	public static void sendRemove(ServerLevel level, int entityId) {
		StainPayloads.AddPayload payload = new StainPayloads.AddPayload(entityId, List.of());

		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * The same as {@link #sendRemove(ServerLevel, int)} but only to the players
	 * near a position, for the common case of an entity whose whereabouts are
	 * known (a wash, not a death).
	 */
	public static void sendRemove(ServerLevel level, int entityId, net.minecraft.world.phys.Vec3 around) {
		StainPayloads.AddPayload payload = new StainPayloads.AddPayload(entityId, List.of());

		for (ServerPlayer player : PlayerLookup.around(level, around, 96.0)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
