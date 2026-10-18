package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server side: lets old blood go away.
 *
 * <p>Decals used to stay until the 1024-entry budget evicted them, so a wall
 * could keep every splat for the life of the world. Now a stain is removed once
 * it has faded to nothing ({@link Drying#fade}) or has been rained on long
 * enough to wash off ({@link Drying#rainLeft}). The client fades the remaining
 * ones by the same clock, so the server only has to send the removals.
 *
 * <p>The sweep is incremental: {@link #SLICE} entries are checked per tick,
 * walking the store in order, so a full 1024-entry store is scanned about once
 * every 3.5 seconds for a few microseconds per tick. Rain exposure is counted
 * per decal in a side map, because a stain has no field for it and the payload
 * must not grow for something only the server tracks.
 */
public final class DecalAging {
	/** Entries checked per server tick. */
	private static final int SLICE = 6;

	/** Ticks of rain exposure accumulated per decal, keyed by the decal itself. */
	private static final java.util.Map<Decal, Long> RAIN = new java.util.HashMap<>();

	/** Tick each decal was last sampled, so exposure is wall-clock, not per visit. */
	private static final java.util.Map<Decal, Long> SEEN = new java.util.HashMap<>();

	/** Index into the store for the next slice, per level. */
	private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Integer> CURSOR =
			new java.util.HashMap<>();

	private DecalAging() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(DecalAging::tick);
	}

	public static void clear() {
		RAIN.clear();
		SEEN.clear();
		CURSOR.clear();
	}

	private static void tick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
			List<Decal> decals = store.getAll();

			if (decals.isEmpty()) {
				continue;
			}

			int cursor = CURSOR.getOrDefault(level.dimension(), 0) % decals.size();
			int end = Math.min(decals.size(), cursor + SLICE);
			long now = level.getGameTime();
			List<Decal> gone = new ArrayList<>();

			for (int i = cursor; i < end; i++) {
				Decal decal = decals.get(i);

				if (decal.born() == 0L) {
					// Legacy stamp: age unknown, so no fading or washing.
					continue;
				}

				if (Drying.fade(decal.born(), now, Drying.SCALE) <= 0.0f) {
					gone.add(decal);
					RAIN.remove(decal);
					SEEN.remove(decal);
					continue;
				}

				if (rainedOn(level, decal)) {
					// Exposure is the time since this decal was last sampled, so
					// a decal visited once every few hundred ticks still washes
					// after minutes of rain rather than hours of sampling.
					long since = now - SEEN.getOrDefault(decal, now);
					long ticks = RAIN.getOrDefault(decal, 0L) + Math.max(1L, since);
					RAIN.put(decal, ticks);

					if (Drying.rainLeft(ticks, Drying.SCALE) <= 0.0f) {
						gone.add(decal);
						RAIN.remove(decal);
						SEEN.remove(decal);
					}
				}

				SEEN.put(decal, now);
			}

			CURSOR.put(level.dimension(), (end >= decals.size()) ? 0 : end);

			if (gone.isEmpty()) {
				continue;
			}

			store.removeAll(gone);
			broadcast(level, gone);
		}
	}

	/**
	 * Whether rain is currently falling on the decal's block face: it must be
	 * raining at the block, the face must point up or sideways (a face under an
	 * overhang is sheltered by its own block), and the sky must be visible.
	 */
	private static boolean rainedOn(ServerLevel level, Decal decal) {
		BlockPos pos = new BlockPos(decal.x(), decal.y(), decal.z());

		if (!level.isRainingAt(pos.above())) {
			return false;
		}

		return level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above()) >= 15;
	}

	private static void broadcast(ServerLevel level, List<Decal> gone) {
		GorePayloads.DecalsGonePayload payload = new GorePayloads.DecalsGonePayload(List.copyOf(gone));

		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
