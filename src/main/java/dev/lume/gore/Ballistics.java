package dev.lume.gore;

import net.minecraft.world.phys.Vec3;

/**
 * Air-drag integration for cosmetic blood particles.
 *
 * <p>A droplet of radius {@code r} in air feels quadratic drag
 * {@code a = (3/8)·Cd·(ρ_air/ρ_blood)·v²/r} (Cd 0.5, ρ_air 1.2, ρ_blood 1060 kg/m³),
 * which in blocks/tick units becomes {@code Δv = −k|v|v} with {@code k = QUAD_DRAG / r_m}
 * per tick. For micrometre mist the Stokes term dominates: linear decay
 * {@code c = STOKES / r_m²} per tick.</p>
 *
 * <p>The implicit form {@code v / (1 + k|v|)} is unconditionally stable for any speed,
 * unlike an explicit {@code v − k|v|v} update.</p>
 */
public final class Ballistics {
	/** Downward acceleration in blocks/tick². */
	public static final double GRAVITY = 0.0245;
	/** Quadratic drag coefficient: multiplied by {@code 1/r_m} to get k per tick. */
	public static final double QUAD_DRAG = 2.123e-4;
	/** Stokes drag coefficient: multiplied by {@code 1/r_m²} to get c per tick. */
	public static final double STOKES = 3.82e-9;

	private Ballistics() {
	}

	/**
	 * Advances one tick: gravity, then quadratic drag, then Stokes drag, in that order.
	 *
	 * @param v       velocity in blocks/tick
	 * @param radiusM droplet radius in metres
	 * @return the new velocity in blocks/tick
	 */
	public static Vec3 step(Vec3 v, double radiusM) {
		v = v.add(0, -GRAVITY, 0);
		double k = QUAD_DRAG / radiusM;
		v = v.scale(1.0 / (1.0 + k * v.length()));
		double c = STOKES / (radiusM * radiusM);
		return v.scale(Math.exp(-c));
	}

	/**
	 * Launch velocity of the exact gravity arc (no drag) that leaves {@code from}
	 * and is at {@code to} after {@code ticks} ticks. Stain-carrying droplets fly
	 * this arc so they land on the tick their stain is born.
	 */
	public static Vec3 arcVelocity(Vec3 from, Vec3 to, int ticks) {
		return to.subtract(from).add(0.0, 0.5 * GRAVITY * ticks * ticks, 0.0).scale(1.0 / ticks);
	}

	/** Position on the arc launched from {@code from} at {@code v0}, {@code t} ticks later. */
	public static Vec3 arcPoint(Vec3 from, Vec3 v0, double t) {
		return from.add(v0.scale(t)).add(0.0, -0.5 * GRAVITY * t * t, 0.0);
	}
}
