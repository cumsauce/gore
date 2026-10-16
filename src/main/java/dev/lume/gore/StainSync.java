package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
		StainPayloads.AddPayload payload = new StainPayloads.AddPayload(victim.getId(), store.get(victim.getId()));

		for (ServerPlayer player : PlayerLookup.around(level, victim.position(), 96.0)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Sends the full stain map of the player's current level. */
	public static void sendTo(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		StainStore store = StainStore.of(level);
		List<StainPayloads.Entry> entries = new ArrayList<>();
		store.entries().forEach((entityId, stains) -> entries.add(new StainPayloads.Entry(entityId, stains)));
		ServerPlayNetworking.send(player, new StainPayloads.SyncPayload(level.dimension().identifier(), List.copyOf(entries)));
	}

	/**
	 * Tells clients in the level that the entity is clean again, by sending an
	 * {@link StainPayloads.AddPayload} with an empty list. The client reads an
	 * empty list as "this entity is clean". Sent to the whole level rather than
	 * around a position because by wash time the entity may be gone, and the
	 * payload is tiny.
	 */
	public static void sendRemove(ServerLevel level, int entityId) {
		StainPayloads.AddPayload payload = new StainPayloads.AddPayload(entityId, List.of());

		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
