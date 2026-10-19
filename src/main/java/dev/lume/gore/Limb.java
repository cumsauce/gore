package dev.lume.gore;

import net.minecraft.world.phys.Vec3;

/**
 * The body part a blow landed on, decided on the server from the hit point
 * alone, so every client agrees on which part was hurt or cut off.
 *
 * <p>The server knows no models, only the bounding box. Upright bodies (taller
 * than wide: zombies, skeletons, players, villagers) are read as a head on top,
 * legs below the hips and arms out at the sides; long bodies (cows, pigs,
 * sheep, wolves) as a head out front, front legs and hind legs. A quadruped's
 * front legs are stored as its arms: the client maps each limb to whichever
 * part names the model actually has (see {@code LimbParts}).
 */
public enum Limb {
	HEAD,
	LEFT_ARM,
	RIGHT_ARM,
	LEFT_LEG,
	RIGHT_LEG,
	TORSO;

	/** The limbs that can come off: everything but the torso. */
	public static final int SEVERABLE = 5;

	public int bit() {
		return 1 << ordinal();
	}

	public boolean isArm() {
		return this == LEFT_ARM || this == RIGHT_ARM;
	}

	public boolean isLeg() {
		return this == LEFT_LEG || this == RIGHT_LEG;
	}

	/** The body's left, in world axes, for a creature facing {@code yRot} degrees. */
	public static Vec3 left(float yRot) {
		double yaw = Math.toRadians(yRot);
		return new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
	}

	/** The body's forward, in world axes, for a creature facing {@code yRot} degrees. */
	public static Vec3 forward(float yRot) {
		double yaw = Math.toRadians(yRot);
		return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
	}

	/**
	 * The limb a blow landing at {@code hit} (offset from the creature's feet,
	 * world axes) struck, for a body of this size facing {@code yRot}.
	 */
	public static Limb classify(Vec3 hit, float yRot, float width, float height, float eyeHeight) {
		double side = hit.dot(left(yRot));
		double ahead = hit.dot(forward(yRot));
		double h = hit.y / Math.max(0.1, height);
		boolean upright = height >= width * 1.6f;

		if (upright) {
			if (hit.y >= eyeHeight - 0.22) {
				return HEAD;
			}
			if (h < 0.42) {
				return side >= 0.0 ? LEFT_LEG : RIGHT_LEG;
			}
			if (Math.abs(side) > width * 0.3) {
				return side >= 0.0 ? LEFT_ARM : RIGHT_ARM;
			}
			return TORSO;
		}

		// Long body: the head sticks out in front, high up.
		if (ahead > width * 0.3 && h > 0.5) {
			return HEAD;
		}
		if (h < 0.45) {
			if (ahead >= 0.0) {
				return side >= 0.0 ? LEFT_ARM : RIGHT_ARM;
			}
			return side >= 0.0 ? LEFT_LEG : RIGHT_LEG;
		}
		return TORSO;
	}
}
