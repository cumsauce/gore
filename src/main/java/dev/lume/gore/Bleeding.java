package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server side: turns a damage event into bleeding. Light hits drip a few drops,
 * heavy hits gush mist and eject splatters that stick to nearby block faces.
 */
public final class Bleeding {
	private static final float SPLATTER_SEVERITY = 0.22f;
	private static final int MAX_DECALS_PER_HIT = 12;
	/** Half-angle of the impact cone, in the tangent the jitter is measured in. */
	private static final float SPRAY_SPREAD = 0.6f;
	/** A heavy hit leaves a run of blood that will later crawl down the wall. */
	private static final float DRIP_SEVERITY = 0.55f;
	private static final float DRIP_CHANCE = 0.5f;

	/**
	 * Counts hits while {@code gore.seed} is set, so a seeded session can replay
	 * each hit's spray: hit {@code n} draws from {@code seed + n}.
	 */
	private static final AtomicLong HIT_COUNTER = new AtomicLong();

	private Bleeding() {
	}

	/**
	 * The RNG a hit's spray is drawn from. With the {@code gore.seed} system
	 * property set (game tests), each hit gets a fresh RandomSource seeded with
	 * the property plus a hit counter, so decal placement is replayable. Without
	 * it — or if the property is not a long — the victim's own RNG is used and
	 * normal gameplay is unchanged.
	 */
	static RandomSource hitRandom(LivingEntity victim) {
		String seed = System.getProperty("gore.seed");

		if (seed != null) {
			try {
				return RandomSource.create(Long.parseLong(seed.trim()) + HIT_COUNTER.getAndIncrement());
			} catch (NumberFormatException ignored) {
				// Unparseable: fall through to the victim's own RNG.
			}
		}

		return victim.getRandom();
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Bleeding::afterDamage);
	}

	private static void afterDamage(LivingEntity victim, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (blocked || damageTaken <= 0.0f || victim instanceof ArmorStand) {
			return;
		}

		if (!(victim.level() instanceof ServerLevel level)) {
			return;
		}

		RandomSource random = hitRandom(victim);
		float severity = Mth.clamp(damageTaken / Math.max(4.0f, victim.getMaxHealth()), 0.08f, 1.0f);
		Vec3 impactDir = impactDirection(victim, source);
		Vec3 wound = new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.62, victim.getZ())
				.add(impactDir.scale(0.25));

		spawnParticles(level, wound, impactDir, severity, random);

		List<Decal> placed = collectDecals(level, victim, wound, impactDir, severity, random);

		if (!placed.isEmpty()) {
			DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
			// Neighbouring marks coalesce in the store; send what was actually
			// stored (the merged decal), not the raw placed list, so clients
			// mirror the same merge and the count never grows for one.
			List<Decal> stored = new ArrayList<>(placed.size());
			for (Decal decal : placed) {
				stored.add(store.addOrMerge(decal));
			}

			GorePayloads.DecalsPayload payload = new GorePayloads.DecalsPayload(List.copyOf(stored));
			for (ServerPlayer player : PlayerLookup.around(level, wound, 96.0)) {
				ServerPlayNetworking.send(player, payload);
			}
		}

		markGear(victim, source, severity, random);

		// Body stain for the hit itself: same clamped severity and impact direction
		// the particles and decals already used, then tell the watching clients.
		GoreStain stain = StainWounds.create(source, impactDir, severity, random);
		StainStore.of(level).add(victim.getId(), stain);
		StainSync.sendAdd(level, victim);
	}

	private static Vec3 impactDirection(LivingEntity victim, DamageSource source) {
		Entity direct = source.getDirectEntity();
		Entity attacker = source.getEntity();

		// A projectile is its own entity, distinct from whoever fired it; its flight
		// direction is the spray direction. A melee hit names the attacker as both the
		// direct and causing entity, and the attacker's own movement (walking, falling)
		// says nothing about where the blood should go, so it is skipped below.
		if (direct != null && direct != victim && direct != attacker) {
			Vec3 velocity = direct.getDeltaMovement();

			if (velocity.lengthSqr() > 1.0e-4) {
				return velocity.normalize();
			}
		}

		if (attacker != null && attacker != victim) {
			Vec3 away = victim.position().subtract(attacker.position());

			if (away.lengthSqr() > 1.0e-4) {
				return away.multiply(1.0, 0.35, 1.0).normalize();
			}
		}

		Vec3 sourcePos = source.getSourcePosition();

		if (sourcePos != null) {
			Vec3 away = victim.position().subtract(sourcePos);

			if (away.lengthSqr() > 1.0e-4) {
				return away.multiply(1.0, 0.35, 1.0).normalize();
			}
		}

		return new Vec3(0.0, -0.6, 0.0);
	}

	/**
	 * Emits the impact spray as a directed cone: every particle starts at the
	 * wound origin with its own velocity from {@link SprayModel}, sent with
	 * {@code count == 0} so the {@code dx,dy,dz} arguments are that exact
	 * per-particle velocity instead of a randomised puff.
	 */
	private static void spawnParticles(ServerLevel level, Vec3 wound, Vec3 dir, float severity, RandomSource random) {
		for (Vec3 drop : SprayModel.dropVelocities(dir, severity, random)) {
			level.sendParticles(Gore.BLOOD_DROP, wound.x, wound.y, wound.z, 0, drop.x, drop.y, drop.z, 1.0);
		}

		// Mist is pushed clear of the victim along the travel direction: at the
		// wound itself the puffs sit inside the model's silhouette and the camera
		// never sees them. 0.55 blocks past the wound is outside a body-sized
		// volume while still reading as coming from the impact.
		Vec3 mistOrigin = wound.add(dir.scale(0.55));
		for (Vec3 mist : SprayModel.mistVelocities(dir, severity, random)) {
			level.sendParticles(Gore.BLOOD_MIST, mistOrigin.x, mistOrigin.y, mistOrigin.z, 0, mist.x, mist.y, mist.z, 1.0);
		}
	}

	private static List<Decal> collectDecals(ServerLevel level, LivingEntity victim, Vec3 wound, Vec3 dir, float severity, RandomSource random) {
		List<Decal> placed = new ArrayList<>();

		if (severity >= SPLATTER_SEVERITY) {
			int rays = 3 + Math.round(severity * 12.0f);

			for (int i = 0; i < rays && placed.size() < MAX_DECALS_PER_HIT; i++) {
				Vec3 rayDir = SprayModel.coneDirection(dir, random, SPRAY_SPREAD);
				Vec3 from = wound.add(rayDir.scale(0.3));
				Vec3 to = from.add(rayDir.scale(1.6 + random.nextDouble() * 2.4));
				BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

				if (hit.getType() != HitResult.Type.BLOCK) {
					continue;
				}

				Decal decal = decalAt(hit, rayDir, severity, level.getGameTime(), random);

				if (decal != null) {
					placed.add(decal);

					// A heavy wound leaves a run that will crawl down the wall.
					if (severity >= DRIP_SEVERITY && random.nextFloat() < DRIP_CHANCE
							&& placed.size() < MAX_DECALS_PER_HIT) {
						Decal drip = dripDecal(hit, severity, level.getGameTime(), random);

						if (drip != null) {
							placed.add(drip);
						}
					}
				}
			}
		}

		if (severity >= 0.45f && random.nextFloat() < 0.55f && placed.size() < MAX_DECALS_PER_HIT) {
			Vec3 from = new Vec3(wound.x, wound.y - 0.2, wound.z);
			Vec3 to = from.add(0.0, -2.5, 0.0);
			BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

			if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
				Decal pool = poolDecal(hit, severity, level.getGameTime(), random);

				if (pool != null) {
					placed.add(pool);
				}
			}
		}

		return placed;
	}

	private static Decal decalAt(BlockHitResult hit, Vec3 travelDir, float severity, long born, RandomSource random) {
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();

		if (face == null) {
			return null;
		}

		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 d = travelDir.normalize();

		// Impact angle: how head-on the droplet hits the wall. A droplet that meets
		// the wall square on leaves a round stain; one that grazes it leaves a long
		// ellipse. The stain's tail always points along the travel, away from the wound.
		float sine = (float) Mth.clamp(Math.abs(d.dot(normal)), 0.0, 1.0);
		int elongation = sine >= 0.85f ? 0 : sine >= 0.6f ? 1 : sine >= 0.35f ? 2 : 3;

		// Align the tail-up sprite with the travel projected onto the face.
		Vec3 p = d.subtract(normal.scale(d.dot(normal)));

		if (p.lengthSqr() < 1.0e-6) {
			p = Decal.axis1(face);
		} else {
			p = p.normalize();
		}

		float rot = (float) Math.toDegrees(Math.atan2(p.dot(Decal.axis2(face)), p.dot(Decal.axis1(face))));

		// Faster impacts shatter into more, smaller droplets.
		int density = severity < 0.45f ? 0 : severity < 0.7f ? 1 : 2;
		int variant = elongation * 3 + density;

		Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(pos));
		float size = Mth.clamp(0.45f - severity * 0.2f + random.nextFloat() * 0.15f, 0.18f, 0.5f);
		// Keep the whole quad on the struck face; otherwise blood floats in the air past the edge.
		float limit = Math.max(0.0f, 0.5f - size * 0.5f);
		float u = (float) Mth.clamp(rel.dot(Decal.axis1(face)), -limit, limit);
		float v = (float) Mth.clamp(rel.dot(Decal.axis2(face)), -limit, limit);
		return new Decal(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue(), u, v, size, rot, variant, born);
	}

	private static Decal poolDecal(BlockHitResult hit, float severity, long born, RandomSource random) {
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();

		if (face == null) {
			return null;
		}

		Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(pos));
		float poolSize = 0.5f + 0.3f * severity;
		float poolLimit = Math.max(0.0f, 0.5f - poolSize * 0.5f);
		return new Decal(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue(),
				(float) Mth.clamp(rel.dot(Decal.axis1(face)), -poolLimit, poolLimit),
				(float) Mth.clamp(rel.dot(Decal.axis2(face)), -poolLimit, poolLimit),
				poolSize, random.nextFloat() * 360.0f, 12 + random.nextInt(2), born);
	}

	/**
	 * A run of blood left on a wall by a heavy wound. It is born at {@code born}
	 * game time and crawls downward as it ages, so {@code born} marks it as a drip
	 * for the client to animate rather than a static splat.
	 */
	static Decal dripDecal(BlockHitResult hit, float severity, long born, RandomSource random) {
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();

		if (face == null) {
			return null;
		}

		// Pools gather on the floor and do not run; only vertical faces drip.
		if (face == Direction.UP || face == Direction.DOWN) {
			return null;
		}

		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		// World-down projected onto the face is straight down for every vertical
		// face, independent of the face's tangent basis (which is inconsistent on
		// the EAST face). The run's sprite-up points this way, the bulb hanging down.
		Vec3 upOnFace = new Vec3(0.0, 1.0, 0.0).subtract(normal.scale(normal.y));

		if (upOnFace.lengthSqr() < 1.0e-6) {
			upOnFace = Decal.axis2(face);
		} else {
			upOnFace = upOnFace.normalize();
		}

		float rot = (float) Math.toDegrees(Math.atan2(upOnFace.dot(Decal.axis2(face)), upOnFace.dot(Decal.axis1(face))));

		Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(pos));
		float size = Mth.clamp(0.35f + severity * 0.25f + random.nextFloat() * 0.15f, 0.3f, 0.7f);
		float limit = Math.max(0.0f, 0.5f - size * 0.5f);
		return new Decal(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue(),
				(float) Mth.clamp(rel.dot(Decal.axis1(face)), -limit, limit),
				(float) Mth.clamp(rel.dot(Decal.axis2(face)), -limit, limit),
				size, rot, 14, born);
	}

	private static void markGear(LivingEntity victim, DamageSource source, float severity, RandomSource random) {
		ItemStack weapon = source.getWeaponItem();

		if (weapon != null && !weapon.isEmpty()) {
			addBlood(weapon, severity * 0.8f, random);
		}

		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack armor = victim.getItemBySlot(slot);

			if (!armor.isEmpty()) {
				float chance = 0.2f + severity * 0.5f;

				if (random.nextFloat() < chance) {
					addBlood(armor, severity, random);
				}
			}
		}
	}

	private static void addBlood(ItemStack stack, float severity, RandomSource random) {
		Integer previous = stack.get(Gore.BLOOD);
		int amount = Math.min(100, (previous == null ? 0 : previous) + 8 + Math.round(45.0f * severity) + random.nextInt(8));
		stack.set(Gore.BLOOD, amount);
	}
}
