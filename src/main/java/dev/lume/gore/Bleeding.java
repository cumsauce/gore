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
 *
 * <p>Blood is placed in four ways, in order of how far it travels: an arterial
 * jet that keeps pumping from the wound for a few ticks, the immediate spray,
 * the wall and floor decals that spray leaves behind, and the body stains on the
 * victim itself (see {@link StainWounds}). Floor hits become pools that spread
 * as they age, so a fight progressively soaks the ground around it.
 */
public final class Bleeding {
	private static final float SPLATTER_SEVERITY = 0.22f;
	private static final int MAX_DECALS_PER_HIT = 18;
	/** Half-angle of the impact cone, in the tangent the jitter is measured in. */
	private static final float SPRAY_SPREAD = 0.6f;
	/** A heavy hit leaves a run of blood that will later crawl down the wall. */
	private static final float DRIP_SEVERITY = 0.45f;
	private static final float DRIP_CHANCE = 0.55f;
	/** Severity at or above which a hit also tries to pool blood at the feet. */
	private static final float POOL_SEVERITY = 0.25f;

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
		ServerLivingEntityEvents.AFTER_DEATH.register(Bleeding::afterDeath);
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
		Vec3 hit = hitPoint(victim, source, impactDir);
		Vec3 bodyHit = hit == null ? null : inBodyFrame(hit, victim.getYRot());
		// With a real hit point the wound is exactly where the blow landed;
		// the old 62%-of-height guess is only the fallback now.
		Vec3 wound = hit != null
				? victim.position().add(hit)
				: new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.62, victim.getZ())
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

			storedDecals(level, wound, stored);
		}

		markGear(victim, source, severity, random);

		// Body stains for the hit itself: the gash plus the spatter the blow threw,
		// using the same clamped severity and impact direction the particles and
		// decals already used. Then tell the watching clients.
		StainStore store = StainStore.of(level);
		for (GoreStain stain : StainWounds.create(source, impactDir, severity, bodyHit, level.getGameTime(), random)) {
			store.add(victim, stain);
		}
		StainSync.sendAdd(level, victim);
	}

	/**
	 * Rotates a world-space offset into the victim's body frame, the frame the
	 * client undoes with the entity's current body rotation. Storing it in the
	 * body frame is what keeps a wound on the same side of a creature when it
	 * turns: a world-space offset would stay pointing the same compass way while
	 * the body walked away from it.
	 */
	private static Vec3 inBodyFrame(Vec3 offset, float yRot) {
		org.joml.Vector3f v = new org.joml.Vector3f((float) offset.x, (float) offset.y, (float) offset.z)
				.rotateY((float) Math.toRadians(yRot - 180.0f));
		return new Vec3(v.x, v.y, v.z);
	}

	/**
	 * Where the blow actually landed, as an offset from the victim's position
	 * (world axes, blocks), or {@code null} when nothing better than the old
	 * inference is known.
	 *
	 * <p>Minecraft's damage event reports no hit point, but it names the
	 * entities involved, and that is enough to reconstruct one: a projectile
	 * knows exactly where it struck (it is still at the impact), a melee
	 * attacker was looking at the victim from its own eye height, and a
	 * positioned source (cactus, campfire) is itself the origin. Each is cast
	 * at the victim's bounding box; the first cast that lands wins. The
	 * fallback of nothing found returns null, and the stains then carry a zero
	 * hit point the client resolves by inference, as it always did.
	 */
	private static Vec3 hitPoint(LivingEntity victim, DamageSource source, Vec3 impactDir) {
		Entity direct = source.getDirectEntity();
		Entity attacker = source.getEntity();

		Vec3 from = null;

		if (direct != null && direct != victim && direct != attacker) {
			// A projectile that is still alive sits at the impact; one that
			// already broke (arrow stuck in) still carries its last position.
			from = direct.position();
		} else if (attacker != null && attacker != victim) {
			Vec3 eye = attacker.getEyePosition();
			Vec3 look = attacker.getViewVector(1.0f);

			// Only trust the attacker's gaze when it is pointed at the victim —
			// sweeping a sword while running away should not put wounds where
			// the attacker happens to be staring.
			if (victim.getBoundingBox().clip(eye, eye.add(look.scale(6.0))).isPresent()) {
				from = eye;
			}
		}

		if (from == null) {
			Vec3 sourcePos = source.getSourcePosition();

			if (sourcePos != null && victim.getBoundingBox().inflate(1.0).contains(sourcePos)) {
				// A source position inside or beside the body: contact damage
				// (cactus, sweet berry bush) struck right where it stands.
				from = sourcePos;
			}
		}

		if (from == null) {
			return null;
		}

		// Cast from the origin toward the body along the line to the victim's
		// centre; the entry point of that ray is where the blow met the skin.
		Vec3 center = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
		Vec3 toCenter = center.subtract(from);

		if (toCenter.lengthSqr() < 1.0e-6) {
			return null;
		}

		return victim.getBoundingBox().clip(from, from.add(toCenter.normalize().scale(4.0)))
				.map(hit -> hit.subtract(victim.position()))
				.orElse(null);
	}

	/**
	 * The killing blow: the body lets go of everything at once. A large pool
	 * spreads under the corpse, a ring of spatter lands around it, the mist
	 * billows and a couple of jets empty the last of the wound.
	 *
	 * <p>This is the one event where it is safe to be generous: it happens once
	 * per creature, and it is the image the player remembers.
	 */
	private static void afterDeath(LivingEntity victim, DamageSource source) {
		if (victim instanceof ArmorStand) {
			return;
		}

		if (!(victim.level() instanceof ServerLevel level)) {
			return;
		}

		RandomSource random = victim.getRandom();
		Vec3 feet = victim.position();
		long now = level.getGameTime();

		// The corpse's own pool: a direct probe straight down, wider than the
		// splatter probe and independent of which way the killing blow pointed.
		BlockHitResult ground = level.clip(new ClipContext(
				feet.add(0.0, 0.1, 0.0), feet.add(0.0, -2.5, 0.0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

		if (ground.getType() == HitResult.Type.BLOCK && ground.getDirection() == Direction.UP) {
			DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
			Vec3 rel = ground.getLocation().subtract(Vec3.atCenterOf(ground.getBlockPos()));
			float poolSize = 0.95f + random.nextFloat() * 0.35f;
			float limit = Math.max(0.0f, 0.5f - poolSize * 0.5f);
			Decal pool = new Decal(ground.getBlockPos().getX(), ground.getBlockPos().getY(), ground.getBlockPos().getZ(),
					Direction.UP.get3DDataValue(),
					(float) Mth.clamp(rel.dot(Decal.axis1(Direction.UP)), -limit, limit),
					(float) Mth.clamp(rel.dot(Decal.axis2(Direction.UP)), -limit, limit),
					poolSize, random.nextFloat() * 360.0f, 13, now);
			storedDecals(level, ground.getLocation(), List.of(store.addOrMerge(pool)));
		}

		// The last spatter, thrown outward and down from the body. Reusing the
		// normal cone machinery means every landing face gets the right treatment:
		// walls get tails and drips, floors get spreading pools.
		Vec3 burst = new Vec3(feet.x, feet.y + victim.getBbHeight() * 0.45, feet.z);
		List<Decal> placed = collectDecals(level, victim, burst, new Vec3(0.0, -1.0, 0.0), 1.0f, random);

		if (!placed.isEmpty()) {
			DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
			List<Decal> stored = new ArrayList<>(placed.size());
			for (Decal decal : placed) {
				stored.add(store.addOrMerge(decal));
			}
			storedDecals(level, burst, stored);
		}

		// A full burst of droplets and a pair of jets in opposite directions, so
		// the death reads from any side the player happens to be watching from.
		spawnParticles(level, burst, new Vec3(0.0, -0.35, 0.0), 1.0f, random);

		for (int i = 0; i < 2; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			Vec3 dir = new Vec3(Math.cos(angle), 0.25 + random.nextDouble() * 0.35, Math.sin(angle)).normalize();
			level.sendParticles(Gore.BLOOD_JET, burst.x, burst.y, burst.z, 0,
					dir.x * 0.5, dir.y * 0.5, dir.z * 0.5, 1.0);
		}

		// The entity is gone: its stains have no body left to render on, and its
		// trail bookkeeping would leak.
		StainStore store = StainStore.of(level);
		boolean wasStained = !store.get(victim.getUUID()).isEmpty();
		store.remove(victim.getUUID());
		BloodTrail.forget(level, victim.getUUID());

		// A dead player's client still renders the body for a moment, and a
		// respawn reuses the id, so explicitly clear the client-side list. Only
		// worth a packet if the creature was actually bloody.
		if (wasStained) {
			StainSync.sendRemove(level, victim.getId());
		}
	}

	/** Sends a batch of stored decals to everyone who can see this level's blood. */
	private static void storedDecals(ServerLevel level, Vec3 around, List<Decal> decals) {
		if (decals.isEmpty()) {
			return;
		}

		GorePayloads.DecalsPayload payload = new GorePayloads.DecalsPayload(List.copyOf(decals));
		for (ServerPlayer player : PlayerLookup.around(level, around, 96.0)) {
			ServerPlayNetworking.send(player, payload);
		}
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

		// Arterial jets: a few emitters that keep throwing droplets for a fraction
		// of a second after the hit. They start a little outside the body so the
		// stream does not spawn inside the mob's own model.
		Vec3 jetOrigin = wound.add(dir.scale(0.35));
		for (int i = 0; i < SprayModel.jetCount(severity); i++) {
			Vec3 jetDir = SprayModel.coneDirection(dir, random, SPRAY_SPREAD * 0.5f);
			double speed = 0.35 + 0.25 * severity;
			level.sendParticles(Gore.BLOOD_JET, jetOrigin.x, jetOrigin.y, jetOrigin.z, 0,
					jetDir.x * speed, jetDir.y * speed, jetDir.z * speed, 1.0);
		}
	}

	private static List<Decal> collectDecals(ServerLevel level, LivingEntity victim, Vec3 wound, Vec3 dir, float severity, RandomSource random) {
		List<Decal> placed = new ArrayList<>();

		if (severity >= SPLATTER_SEVERITY) {
			int rays = 4 + Math.round(severity * 16.0f);

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

		// Blood that runs off the body and reaches the floor pools there. This is
		// separate from the ray spatter: the rays fly outward, and on a flat floor
		// they leave small marks in a ring, while this is the puddle directly
		// under the wound.
		if (severity >= POOL_SEVERITY && random.nextFloat() < 0.6f && placed.size() < MAX_DECALS_PER_HIT) {
			Vec3 from = new Vec3(wound.x, wound.y - 0.2, wound.z);
			Vec3 to = from.add(0.0, -2.5, 0.0);
			BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

			if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
				float poolSize = Mth.clamp(0.45f + 0.35f * severity + random.nextFloat() * 0.15f, 0.4f, 0.9f);
				Decal pool = poolDecal(hit, poolSize, level.getGameTime(), random);

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

		// A flat floor does not take an elongated impact stain: the droplet
		// flattens and creeps outward under gravity. Floor hits become pools that
		// spread as they age (see DecalRenderer), which is what makes a fight
		// look like it is soaking into the ground.
		if (face == Direction.UP) {
			float poolSize = Mth.clamp(0.28f + severity * 0.22f + random.nextFloat() * 0.12f, 0.22f, 0.6f);
			return poolDecal(hit, poolSize, born, random);
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

	private static Decal poolDecal(BlockHitResult hit, float poolSize, long born, RandomSource random) {
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();

		if (face == null) {
			return null;
		}

		Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(pos));
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
