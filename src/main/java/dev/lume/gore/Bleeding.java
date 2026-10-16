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
				Vec3 rayDir = dir.add(random.nextGaussian() * 0.35, random.nextGaussian() * 0.2 - 0.15, random.nextGaussian() * 0.35);

				if (rayDir.lengthSqr() < 1.0e-6) {
					rayDir = dir;
				}

				rayDir = rayDir.normalize();
				Vec3 from = wound.add(rayDir.scale(0.3));
				Vec3 to = from.add(rayDir.scale(1.6 + random.nextDouble() * 2.4));
				BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

				if (hit.getType() != HitResult.Type.BLOCK) {
					continue;
				}

				Decal decal = decalAt(hit, severity, random, random.nextInt(12));

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
				Decal pool = decalAt(hit, severity, random, 12 + random.nextInt(2));

				if (pool != null) {
					float poolSize = 0.5f + 0.3f * severity;
					float poolLimit = Math.max(0.0f, 0.5f - poolSize * 0.5f);
					placed.add(new Decal(pool.x(), pool.y(), pool.z(), pool.face(),
							(float) Mth.clamp(pool.u(), -poolLimit, poolLimit),
							(float) Mth.clamp(pool.v(), -poolLimit, poolLimit),
							poolSize, pool.rot(), pool.variant()));
				}
			}
		}

		return placed;
	}

	private static Decal decalAt(BlockHitResult hit, float severity, RandomSource random, int variant) {
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();

		if (face == null) {
			return null;
		}

		Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(pos));
		float size = 0.3f + 0.35f * random.nextFloat() + severity * 0.15f;
		// Keep the whole quad on the struck face; otherwise blood floats in the air past the edge.
		float limit = Math.max(0.0f, 0.5f - size * 0.5f);
		float u = (float) Mth.clamp(rel.dot(Decal.axis1(face)), -limit, limit);
		float v = (float) Mth.clamp(rel.dot(Decal.axis2(face)), -limit, limit);
		return new Decal(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue(), u, v, size, random.nextFloat() * 360.0f, variant);
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
