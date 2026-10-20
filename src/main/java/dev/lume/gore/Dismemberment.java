package dev.lume.gore;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Server side: which part a blow hurt, and when a part comes off.
 *
 * <p>Every blow with a known hit point adds its damage, as a fraction of max
 * health, to the limb it struck ({@link Limb#classify}). Purely cosmetic: a
 * creature that loses a limb keeps its AI, health and attacks.
 *
 * <ul>
 * <li>An arm comes off a living creature once it has taken {@link #ARM_SEVER}
 * of the creature's health and the blow that pushes it over is a hard one
 * (severity {@link #ARM_SEVERITY}); a leg needs more ({@link #LEG_SEVER},
 * {@link #LEG_SEVERITY}). Only blows from something (a weapon, a
 * projectile, a claw) or an explosion cut; fire, falls and drowning do not.</li>
 * <li>The head only comes off on the killing blow.</li>
 * <li>The killing blow takes off whatever part it struck; an explosion
 * that kills takes off one or two parts at random.</li>
 * <li>Living players keep their limbs (a missing arm in first person would
 * be confusing); a killing blow still cuts.</li>
 * </ul>
 */
public final class Dismemberment {
	static final float ARM_SEVER = 0.3f;
	static final float ARM_SEVERITY = 0.45f;
	static final float LEG_SEVER = 0.45f;
	static final float LEG_SEVERITY = 0.6f;

	/**
	 * A blade cuts far sooner. Tuned on a 20-health zombie: an iron sword hit
	 * is 6 damage, severity 0.3, so two or three blows on one arm take it off
	 * and a leg needs a few more; an axe (9) does it in one or two.
	 */
	static final float BLADE_ARM_SEVER = 0.25f;
	static final float BLADE_ARM_SEVERITY = 0.2f;
	static final float BLADE_LEG_SEVER = 0.4f;
	static final float BLADE_LEG_SEVERITY = 0.25f;
	/** Chance that a bladed killing blow to the body takes the head instead of nothing. */
	static final float BLADE_KILL_HEAD = 0.5f;

	/** Tells clients a limb just came off, so they can throw it and bleed the stump. */
	public record SeverPayload(int entityId, int limb, Vec3 dir) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SeverPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("sever"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final StreamCodec<ByteBuf, SeverPayload> SEVER_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SeverPayload::entityId,
			ByteBufCodecs.VAR_INT, SeverPayload::limb,
			StreamCodec.composite(ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y,
					ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new), SeverPayload::dir,
			SeverPayload::new);

	private Dismemberment() {
	}

	public static LimbState state(LivingEntity entity) {
		LimbState state = entity.getAttached(Gore.LIMBS);
		return state == null ? LimbState.NONE : state;
	}

	/** The limb a hit at {@code hit} (offset from the feet) struck on this body. */
	public static Limb limbAt(LivingEntity victim, Vec3 hit) {
		if (hit == null) {
			return Limb.TORSO;
		}
		return Limb.classify(hit, victim.yBodyRot, victim.getBbWidth(), victim.getBbHeight(), victim.getEyeHeight());
	}

	/** True when this damage can cut: something struck the body, or it blew up. */
	public static boolean cuts(DamageSource source) {
		if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_FALL)
				|| source.is(DamageTypeTags.IS_DROWNING) || source.is(DamageTypeTags.IS_FREEZING)) {
			return false;
		}
		return source.getEntity() != null || source.getDirectEntity() != null
				|| source.is(DamageTypeTags.IS_EXPLOSION);
	}

	/** True when the blow was dealt with a sword or an axe. */
	public static boolean bladed(DamageSource source) {
		ItemStack weapon = source.getWeaponItem();
		return weapon != null && (weapon.is(ItemTags.SWORDS) || weapon.is(ItemTags.AXES));
	}

	/**
	 * Records a blow and cuts the limb off when it has had enough. Returns the
	 * limb the blow struck (a limb already gone counts as the torso).
	 */
	static Limb afterHit(ServerLevel level, LivingEntity victim, DamageSource source, float damageTaken,
			float severity, Vec3 hit, Vec3 impactDir) {
		LimbState state = state(victim);
		Limb limb = limbAt(victim, hit);

		if (state.isSevered(limb)) {
			limb = Limb.TORSO;
		}

		state = state.hit(limb, damageTaken / Math.max(1.0f, victim.getMaxHealth()));

		if (!(victim instanceof Player) && cuts(source)
				&& shouldSever(limb, state.damage(limb), severity, bladed(source))) {
			state = state.sever(limb);
			victim.setAttached(Gore.LIMBS, state);
			announce(level, victim, limb, impactDir);
			return limb;
		}

		victim.setAttached(Gore.LIMBS, state);
		return limb;
	}

	/** The thresholds above, as a pure function for the tests. */
	public static boolean shouldSever(Limb limb, float accumulated, float severity) {
		return shouldSever(limb, accumulated, severity, false);
	}

	/** As above; a sword or axe blow uses the much lower blade thresholds. */
	public static boolean shouldSever(Limb limb, float accumulated, float severity, boolean bladed) {
		if (bladed) {
			if (limb.isArm()) {
				return accumulated >= BLADE_ARM_SEVER && severity >= BLADE_ARM_SEVERITY;
			}
			if (limb.isLeg()) {
				return accumulated >= BLADE_LEG_SEVER && severity >= BLADE_LEG_SEVERITY;
			}
			return false;
		}
		if (limb.isArm()) {
			return accumulated >= ARM_SEVER && severity >= ARM_SEVERITY;
		}
		if (limb.isLeg()) {
			return accumulated >= LEG_SEVER && severity >= LEG_SEVERITY;
		}
		return false;
	}

	/** The killing blow cuts what it struck; a fatal explosion tears off one or two parts. */
	static void afterDeath(ServerLevel level, LivingEntity victim, DamageSource source, Vec3 hit, Vec3 impactDir,
			RandomSource random) {
		if (!cuts(source)) {
			return;
		}

		LimbState state = state(victim);
		// The killing blow never reaches afterHit (Fabric skips AFTER_DAMAGE for
		// a fatal hit), so classify it here; without a hit point fall back to
		// the part the previous blow struck.
		Limb limb = hit != null ? limbAt(victim, hit) : state.lastLimb();

		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			int count = 1 + random.nextInt(2);
			for (int i = 0; i < count; i++) {
				Limb pick = Limb.values()[random.nextInt(Limb.SEVERABLE)];
				if (!state.isSevered(pick)) {
					state = state.sever(pick);
					announce(level, victim, pick, impactDir);
				}
			}
		} else {
			Limb cut = killCut(limb, bladed(source), random);
			if (cut != null && !state.isSevered(cut)) {
				state = state.sever(cut);
				announce(level, victim, cut, impactDir);
			}
		}

		victim.setAttached(Gore.LIMBS, state);
	}

	/**
	 * What a killing blow takes off. A blow that struck a limb takes it. A
	 * blade swung at the body — where a player normally aims — takes the head
	 * half the time; anything else to the body takes nothing.
	 */
	public static Limb killCut(Limb struck, boolean bladed, RandomSource random) {
		if (struck != Limb.TORSO) {
			return struck;
		}
		return bladed && random.nextFloat() < BLADE_KILL_HEAD ? Limb.HEAD : null;
	}

	/**
	 * The world point the limb joins the body: shoulders and hips out at the
	 * sides, the neck at eye height, front and hind legs fore and aft on a long
	 * body. Where the stump bleeds from.
	 */
	public static Vec3 joint(LivingEntity body, Limb limb) {
		float w = body.getBbWidth();
		float h = body.getBbHeight();
		boolean upright = h >= w * 1.6f;
		Vec3 left = Limb.left(body.yBodyRot);
		Vec3 ahead = Limb.forward(body.yBodyRot);
		Vec3 at = switch (limb) {
			case HEAD -> new Vec3(0.0, body.getEyeHeight() - (upright ? 0.25 : 0.1), 0.0)
					.add(upright ? Vec3.ZERO : ahead.scale(w * 0.55));
			case LEFT_ARM, RIGHT_ARM -> upright
					? left.scale(w * (limb == Limb.LEFT_ARM ? 0.55 : -0.55)).add(0.0, h * 0.72, 0.0)
					: left.scale(w * (limb == Limb.LEFT_ARM ? 0.3 : -0.3)).add(ahead.scale(w * 0.4)).add(0.0, h * 0.45, 0.0);
			case LEFT_LEG, RIGHT_LEG -> upright
					? left.scale(w * (limb == Limb.LEFT_LEG ? 0.2 : -0.2)).add(0.0, h * 0.4, 0.0)
					: left.scale(w * (limb == Limb.LEFT_LEG ? 0.3 : -0.3)).add(ahead.scale(-w * 0.4)).add(0.0, h * 0.45, 0.0);
			case TORSO -> new Vec3(0.0, h * 0.5, 0.0);
		};
		return body.position().add(at);
	}

	private static void announce(ServerLevel level, LivingEntity victim, Limb limb, Vec3 impactDir) {
		SeverPayload payload = new SeverPayload(victim.getId(), limb.ordinal(), impactDir);
		for (ServerPlayer player : PlayerLookup.tracking(victim)) {
			ServerPlayNetworking.send(player, payload);
		}
		if (victim instanceof ServerPlayer self) {
			ServerPlayNetworking.send(self, payload);
		}

		// The stump empties: jets out of the cut, along the way the limb went.
		Vec3 at = joint(victim, limb);
		Vec3 out = at.subtract(victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0));
		Vec3 dir = out.lengthSqr() < 1.0e-4 ? impactDir : out.normalize().add(impactDir.scale(0.5)).normalize();
		for (int i = 0; i < 2; i++) {
			level.sendParticles(Gore.BLOOD_JET, at.x, at.y, at.z, 0, dir.x * 0.45, dir.y * 0.45 + 0.1, dir.z * 0.45, 1.0);
		}
	}
}
