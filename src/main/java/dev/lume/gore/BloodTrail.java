package dev.lume.gore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server side: a badly wounded creature marks the ground it walks over.
 *
 * <p>A mob does not need to be hit again to bleed. Once it carries enough wounds
 * it loses a little blood every stride, and the marks it leaves are pools that
 * spread as they age ({@link Flow#poolSize}), so the player can read where the
 * thing went and how long ago — which is the part of a fight that vanilla cannot
 * communicate at all. Motion is the trigger, not time: a mob standing still
 * still drips from its model (the body stain layer), but does not paint the
 * floor.
 *
 * <p>Deliberately cheap: only entities the stain store already knows about are
 * considered, at most a few marks are placed per tick, and only within sight of
 * a player, so the cost scales with fights rather than with the world.
 */
public final class BloodTrail {
	/** Ticks between marks from one creature; the stride gate usually binds first. */
	private static final int INTERVAL = 8;
	/** Blocks it must have moved since its last mark, so a trail is spaced. */
	private static final double STRIDE = 1.6;
	/** Fraction of strides that actually leave a mark, so a trail is patchy. */
	private static final float DROP_CHANCE = 0.8f;
	/** Wounds it must carry before it starts leaving a trail. */
	private static final int MIN_STAINS = 2;
	/** Marks placed per server tick across all creatures, a hard cost ceiling. */
	private static final int MAX_MARKS_PER_TICK = 4;
	/** Only creatures this close to a player are worth simulating. */
	private static final double PLAYER_RANGE = 40.0;

	/** Last seen position and tick, per level and entity id. */
	private static final Map<ResourceKey<Level>, Map<java.util.UUID, Mark>> MARKS = new HashMap<>();

	private record Mark(Vec3 at, long tick) {
	}

	private BloodTrail() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(BloodTrail::tick);
	}

	/** Drops an entity's trail bookkeeping, called when it dies or the world stops. */
	public static void forget(ServerLevel level, java.util.UUID entityId) {
		Map<java.util.UUID, Mark> marks = MARKS.get(level.dimension());
		if (marks != null) {
			marks.remove(entityId);
		}
	}

	public static void clear() {
		MARKS.clear();
	}

	private static void tick(MinecraftServer server) {
		int budget = MAX_MARKS_PER_TICK;

		for (ServerLevel level : server.getAllLevels()) {
			StainStore store = StainStore.of(level);
			Map<java.util.UUID, List<GoreStain>> entries = store.entries();

			if (entries.isEmpty()) {
				continue;
			}

			List<ServerPlayer> players = level.players();

			if (players.isEmpty()) {
				continue;
			}

			for (Map.Entry<java.util.UUID, List<GoreStain>> entry : entries.entrySet()) {
				if (budget <= 0) {
					return;
				}

				if (entry.getValue().size() < MIN_STAINS) {
					continue;
				}

				var entity = level.getEntity(entry.getKey());

				if (!(entity instanceof LivingEntity living) || living.isDeadOrDying() || !living.onGround()) {
					continue;
				}

				Vec3 pos = living.position();

				if (!nearAny(players, pos)) {
					continue;
				}

				Map<java.util.UUID, Mark> marks = MARKS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
				Mark last = marks.get(living.getUUID());
				long now = level.getGameTime();

				// A bigger creature sheds bigger drops: the stride is in its own
				// body lengths, so a ravager does not leave a chicken's footprints.
				double stride = STRIDE * Math.max(0.6, Math.min(2.5, living.getBbWidth()));

				if (last != null && (now - last.tick() < INTERVAL || last.at().distanceToSqr(pos) < stride * stride)) {
					continue;
				}

				marks.put(living.getUUID(), new Mark(pos, now));

				if (living.getRandom().nextFloat() < DROP_CHANCE && place(level, living)) {
					budget--;
				}
			}
		}
	}

	private static boolean nearAny(List<ServerPlayer> players, Vec3 pos) {
		double rangeSqr = PLAYER_RANGE * PLAYER_RANGE;

		for (ServerPlayer player : players) {
			if (player.position().distanceToSqr(pos) <= rangeSqr) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Places one mark of blood on the ground under {@code entity}: a small pool
	 * on the block top, plus a droplet that splashes off it. Returns whether a
	 * decal was actually placed.
	 */
	private static boolean place(ServerLevel level, LivingEntity entity) {
		Vec3 feet = entity.position();
		BlockHitResult hit = level.clip(new ClipContext(
				feet.add(0.0, 0.2, 0.0), feet.add(0.0, -1.4, 0.0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));

		if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
			return false;
		}

		Vec3 location = hit.getLocation();
		BlockPos pos = hit.getBlockPos();
		Vec3 rel = location.subtract(Vec3.atCenterOf(pos));
		// Bigger bodies leave bigger pools; also grows with how wounded it is.
		float bulk = Math.max(0.7f, Math.min(2.2f, entity.getBbWidth()));
		int wounds = StainStore.of(level).get(entity.getUUID()).size();
		float size = Math.min(0.45f, (0.12f + entity.getRandom().nextFloat() * 0.10f) * bulk
				+ Math.min(0.10f, wounds * 0.006f));
		float limit = Math.max(0.0f, 0.5f - size * 0.5f);
		Direction up = Direction.UP;
		Decal decal = new Decal(pos.getX(), pos.getY(), pos.getZ(), up.get3DDataValue(),
				(float) Mth.clamp(rel.dot(Decal.axis1(up)), -limit, limit),
				(float) Mth.clamp(rel.dot(Decal.axis2(up)), -limit, limit),
				size, entity.getRandom().nextFloat() * 360.0f, 12, level.getGameTime());

		Decal stored = level.getDataStorage().computeIfAbsent(DecalStore.TYPE).addOrMerge(decal);

		// One drop falls from the creature itself, so the mark has an origin the
		// eye can connect to the body rather than appearing under it.
		level.sendParticles(Gore.BLOOD_DROP, feet.x, feet.y + 0.25, feet.z, 0,
				0.0, -0.05, 0.0, 1.0);

		GorePayloads.DecalsPayload payload = new GorePayloads.DecalsPayload(List.of(stored));
		for (ServerPlayer player : PlayerLookup.around(level, location, 48.0)) {
			ServerPlayNetworking.send(player, payload);
		}

		return true;
	}
}
