package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
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
	private static final float MIST_SEVERITY = 0.35f;
	private static final int MAX_DECALS_PER_HIT = 12;
	/** Half-angle of the impact cone, in the tangent the jitter is measured in. */
	private static final float SPRAY_SPREAD = 0.6f;

	private Bleeding() {
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

		RandomSource random = victim.getRandom();
		float severity = Mth.clamp(damageTaken / Math.max(4.0f, victim.getMaxHealth()), 0.08f, 1.0f);
		Vec3 impactDir = impactDirection(victim, source);
		Vec3 wound = new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.62, victim.getZ())
				.add(impactDir.scale(0.25));

		spawnParticles(level, wound, impactDir, severity, random);

		List<Decal> placed = collectDecals(level, victim, wound, impactDir, severity, random);

		if (!placed.isEmpty()) {
			DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
			for (Decal decal : placed) {
				store.add(decal);
			}

			GorePayloads.DecalsPayload payload = new GorePayloads.DecalsPayload(List.copyOf(placed));
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

	private static void spawnParticles(ServerLevel level, Vec3 wound, Vec3 dir, float severity, RandomSource random) {
		int drops = 3 + Math.round(severity * 12.0f);
		level.sendParticles(Gore.BLOOD_DROP, wound.x, wound.y, wound.z, drops, 0.3, 0.2, 0.3, 0.05);

		if (severity < MIST_SEVERITY) {
			return;
		}

		int mist = 1 + Math.round(severity * 5.0f);
		level.sendParticles(Gore.BLOOD_MIST,
				wound.x + dir.x * 0.2, wound.y + dir.y * 0.2, wound.z + dir.z * 0.2,
				mist, dir.x * 0.25 + 0.06, dir.y * 0.25 + 0.06, dir.z * 0.25 + 0.06, 0.25);

		int darts = 1 + Math.round(severity * 3.0f);

		for (int i = 0; i < darts; i++) {
			Vec3 dart = dir.add(random.nextGaussian() * 0.12, random.nextGaussian() * 0.12 - 0.05, random.nextGaussian() * 0.12)
					.normalize()
					.scale(0.35 + 0.4 * severity);
			level.sendParticles(Gore.BLOOD_MIST, wound.x, wound.y, wound.z, 0, dart.x, dart.y, dart.z, 1.0);
		}
	}

	private static List<Decal> collectDecals(ServerLevel level, LivingEntity victim, Vec3 wound, Vec3 dir, float severity, RandomSource random) {
		List<Decal> placed = new ArrayList<>();

		if (severity >= SPLATTER_SEVERITY) {
			int rays = 3 + Math.round(severity * 12.0f);

			for (int i = 0; i < rays && placed.size() < MAX_DECALS_PER_HIT; i++) {
				Vec3 rayDir = coneDirection(dir, random, SPRAY_SPREAD);
				Vec3 from = wound.add(rayDir.scale(0.3));
				Vec3 to = from.add(rayDir.scale(1.6 + random.nextDouble() * 2.4));
				BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

				if (hit.getType() != HitResult.Type.BLOCK) {
					continue;
				}

				Decal decal = decalAt(hit, rayDir, severity, random);

				if (decal != null) {
					placed.add(decal);
				}
			}
		}

		if (severity >= 0.45f && random.nextFloat() < 0.55f && placed.size() < MAX_DECALS_PER_HIT) {
			Vec3 from = new Vec3(wound.x, wound.y - 0.2, wound.z);
			Vec3 to = from.add(0.0, -2.5, 0.0);
			BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

			if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
				Decal pool = poolDecal(hit, severity, random);

				if (pool != null) {
					placed.add(pool);
				}
			}
		}

		return placed;
	}

	private static Decal decalAt(BlockHitResult hit, Vec3 travelDir, float severity, RandomSource random) {
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
		return new Decal(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue(), u, v, size, rot, variant);
	}

	private static Decal poolDecal(BlockHitResult hit, float severity, RandomSource random) {
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
				poolSize, random.nextFloat() * 360.0f, 12 + random.nextInt(2));
	}

	private static Vec3 coneDirection(Vec3 dir, RandomSource random, float spread) {
		Vec3 u = Math.abs(dir.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0).cross(dir) : new Vec3(1.0, 0.0, 0.0).cross(dir);
		u = u.normalize();
		Vec3 v = dir.cross(u);
		double theta = random.nextDouble() * Math.PI * 2.0;
		// Bias toward the axis so the spray is densest along the impact direction.
		double radius = spread * random.nextFloat() * random.nextFloat();
		return dir.add(u.scale(Math.cos(theta) * radius)).add(v.scale(Math.sin(theta) * radius)).normalize();
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
