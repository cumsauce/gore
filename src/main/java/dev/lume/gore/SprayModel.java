package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Server side: the physics of impact spray. Blood leaves a wound as a cone
 * centred on the force direction — not an isotropic puff — with droplet
 * diameters drawn from a log-normal distribution: a few millimetre-sized
 * drops, many sub-millimetre ones, and a mist of 40–120 µm droplets that
 * stop almost immediately.
 *
 * <p>All velocities are in blocks per tick, ready to be handed to
 * {@code ServerLevel.sendParticles} as a per-particle velocity ({@code count == 0}).
 */
public final class SprayModel {
	/** Tan of the drop cone half-angle → 19.3°, the impact spatter spread. */
	public static final float DROP_SPREAD = 0.35f;
	/** Mist fans out slightly wider than the drops. */
	public static final float MIST_SPREAD = 0.30f;
	/** Severity at or above which a hit throws a visible mist. */
	public static final float MIST_SEVERITY = 0.35f;
	/** Severity at or above which the wound pumps a jet for several ticks. */
	public static final float JET_SEVERITY = 0.45f;

	/** Log-normal parameters for droplet radius: a few mm drops, many sub-mm. */
	public static final double DROP_RADIUS_MEDIAN_M = 0.8e-3;
	public static final double DROP_RADIUS_SIGMA = 0.6;
	public static final double DROP_RADIUS_MIN_M = 0.15e-3;
	public static final double DROP_RADIUS_MAX_M = 2.5e-3;
	/** Mist droplets are 40–120 µm across. */
	public static final double MIST_RADIUS_MIN_M = 2e-5;
	public static final double MIST_RADIUS_MAX_M = 6e-5;

	private SprayModel() {
	}

	/**
	 * Speed of a droplet that carries a stain to a wall, in blocks per tick —
	 * about 8 m/s, a flicked paintbrush rather than a gunshot, so the eye can
	 * follow each drop from the wound to the mark it leaves.
	 */
	public static final double FLIGHT_SPEED = 0.4;

	/**
	 * Ticks a stain-carrying droplet takes to cover {@code distance} blocks.
	 * The stain is born at the blow plus this, and the client flies the droplet
	 * over exactly this time, so the mark appears when the droplet arrives.
	 */
	public static int flightTicks(double distance) {
		return (int) Math.max(2, Math.min(16, Math.round(distance / FLIGHT_SPEED)));
	}

	/**
	 * Number of free drops a hit of {@code severity} (0–1) ejects, on top of the
	 * stain-carrying ones: the few that miss every surface and just fall.
	 */
	public static int dropCount(float severity) {
		return Math.round(2 + 5 * severity);
	}

	/** Number of mist puffs; light hits below {@link #MIST_SEVERITY} throw none. */
	public static int mistCount(float severity) {
		return severity < MIST_SEVERITY ? 0 : Math.round(2 + 4 * severity);
	}

	/**
	 * Number of arterial jets a hit opens; light hits below {@link #JET_SEVERITY}
	 * throw none. A jet is not a droplet: it keeps pumping for a fraction of a
	 * second, which is what makes a hard hit look like a wound under pressure
	 * rather than a single puff of spray.
	 */
	public static int jetCount(float severity) {
		if (severity < JET_SEVERITY) {
			return 0;
		}

		return severity >= 0.8f ? 2 : 1;
	}

	/**
	 * One velocity per drop: each leaves the wound along its own direction
	 * inside the impact cone, at roughly the impact speed scaled by severity.
	 */
	public static List<Vec3> dropVelocities(Vec3 dir, float severity, RandomSource random) {
		int count = dropCount(severity);
		List<Vec3> velocities = new ArrayList<>(count);
		double baseSpeed = 0.18 + 0.2 * severity;

		for (int i = 0; i < count; i++) {
			double speed = baseSpeed * (0.7 + 0.3 * random.nextFloat());
			velocities.add(coneDirection(dir, random, DROP_SPREAD).scale(speed));
		}

		return velocities;
	}

	/**
	 * One velocity per mist droplet: slower than the drops and spread a little
	 * wider, so the mist hangs near the wound instead of flying with the spatter.
	 */
	public static List<Vec3> mistVelocities(Vec3 dir, float severity, RandomSource random) {
		int count = mistCount(severity);
		List<Vec3> velocities = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			double speed = 0.20 + 0.12 * random.nextFloat();
			velocities.add(coneDirection(dir, random, MIST_SPREAD).scale(speed));
		}

		return velocities;
	}

	/** Drop radius in metres: log-normal, clamped to physical bounds. */
	public static double dropRadiusM(RandomSource random) {
		double radius = Math.exp(Math.log(DROP_RADIUS_MEDIAN_M) + DROP_RADIUS_SIGMA * random.nextGaussian());
		return Mth.clamp(radius, DROP_RADIUS_MIN_M, DROP_RADIUS_MAX_M);
	}

	/** Mist radius in metres: uniform within the 40–120 µm band. */
	public static double mistRadiusM(RandomSource random) {
		return MIST_RADIUS_MIN_M + random.nextDouble() * (MIST_RADIUS_MAX_M - MIST_RADIUS_MIN_M);
	}

	/**
	 * A direction inside a cone of tangent width {@code spread} around {@code dir},
	 * biased toward the axis so the spray is densest along the impact direction.
	 */
	public static Vec3 coneDirection(Vec3 dir, RandomSource random, float spread) {
		Vec3 u = Math.abs(dir.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0).cross(dir) : new Vec3(1.0, 0.0, 0.0).cross(dir);
		u = u.normalize();
		Vec3 v = dir.cross(u);
		double theta = random.nextDouble() * Math.PI * 2.0;
		// Bias toward the axis so the spray is densest along the impact direction.
		double radius = spread * random.nextFloat() * random.nextFloat();
		return dir.add(u.scale(Math.cos(theta) * radius)).add(v.scale(Math.sin(theta) * radius)).normalize();
	}
}
