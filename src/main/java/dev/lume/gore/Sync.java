package dev.lume.gore;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class Sync {
	private Sync() {
	}

	/** Sends the full decal list of the player's current level. */
	public static void sendTo(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
		ServerPlayNetworking.send(player, new GorePayloads.DecalsSyncPayload(level.dimension().identifier(), store.getAll()));
	}
}
