package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Scenes for the offline inspection tools ({@link BloodPreview}, {@link FlightCheck}).
 *
 * <p>Every scene is set in the same unfolded room: a wall of {@link #WALL_W}×{@link #WALL_H}
 * blocks whose south faces are the plane {@code z = 0}, over a floor of
 * {@link #WALL_W}×{@link #FLOOR_D} blocks whose tops are the plane {@code y = 0}.
 * A scene is a list of placements, each applied through {@link DecalMerge} at
 * its launch tick, as the server stores a hit's decals at the blow.
 */
final class PreviewScenes {
	static final int WALL_W = 4;
	static final int WALL_H = 3;
	static final int FLOOR_D = 4;
	static final int SOUTH = Direction.SOUTH.get3DDataValue();
	static final int UP = Direction.UP.get3DDataValue();
	/** The wound the spray scenes bleed from, and the tick of the blow. */
	static final Vec3 WOUND = new Vec3(2.0, 1.3, 2.2);
	static final long BLOW = 4;

	/**
	 * One decal placed at game time {@code launch}. {@code origin} is the wound a
	 * droplet flies from to make it, or null for a mark placed by hand.
	 */
	record Placement(long launch, Decal decal, Vec3 origin) {
	}

	static final List<String> NAMES = List.of("spray", "cluster", "drips", "pool", "merge");

	private PreviewScenes() {
	}

	static List<Placement> scene(String name, long seed) {
		return switch (name) {
			case "spray" -> spray(0.85f, seed);
			case "cluster" -> cluster(seed);
			case "drips" -> drips();
			case "pool" -> pools(seed);
			case "merge" -> merge();
			default -> throw new IllegalArgumentException("unknown scene " + name + "; one of " + NAMES);
		};
	}

	/** The decals stored by game time {@code now}, merged as the server merges them. */
	static List<Decal> stored(List<Placement> scene, long now) {
		List<Decal> decals = new ArrayList<>();
		for (Placement p : scene) {
			if (p.launch() <= now) {
				DecalMerge.applyTo(decals, p.decal());
			}
		}
		return decals;
	}

	/** A wall mark at plane coordinates {@code (pu, pv)} = world {@code (x, y)}. */
	static Decal wall(double pu, double pv, float size, float rot, int variant, long born) {
		int x = (int) Math.floor(pu);
		int y = (int) Math.floor(pv);
		return new Decal(x, y, -1, SOUTH, (float) (pu - x - 0.5), (float) (pv - y - 0.5), size, rot, variant, born);
	}

	/** A floor mark at world {@code (x, z)}. */
	static Decal floor(double wx, double wz, float size, float rot, int variant, long born) {
		int x = (int) Math.floor(wx);
		int z = (int) Math.floor(wz);
		// UP's axis2 is -z, so v grows toward the wall.
		return new Decal(x, -1, z, UP, (float) (wx - x - 0.5), (float) (z + 0.5 - wz), size, rot, variant, born);
	}

	private static Placement byHand(Decal d) {
		return new Placement(Math.max(0, d.born() - 2), d, null);
	}

	/**
	 * A hit of {@code severity} at {@link #BLOW}: the same rays, decals, drips and
	 * pool as {@code Bleeding.collectDecals}, cast against the room's two planes
	 * instead of a level.
	 */
	static List<Placement> spray(float severity, long seed) {
		RandomSource random = RandomSource.create(seed);
		List<Placement> out = new ArrayList<>();
		Vec3 dir = new Vec3(0.15, -0.25, -1.0).normalize();
		int rays = 4 + Math.round(severity * 16.0f);

		for (int i = 0; i < rays && out.size() < Bleeding.MAX_DECALS_PER_HIT; i++) {
			Vec3 rayDir = SprayModel.coneDirection(dir, random, Bleeding.SPRAY_SPREAD);
			Vec3 from = WOUND.add(rayDir.scale(0.3));
			Vec3 to = from.add(rayDir.scale(1.6 + random.nextDouble() * 2.4));
			BlockHitResult hit = clip(from, to);

			if (hit == null) {
				continue;
			}

			long arrives = BLOW + SprayModel.flightTicks(WOUND.distanceTo(hit.getLocation()));
			Decal decal = Bleeding.decalAt(hit, rayDir, severity, arrives, random);

			if (decal != null) {
				out.add(new Placement(BLOW, decal, WOUND));

				if (severity >= Bleeding.DRIP_SEVERITY && random.nextFloat() < Bleeding.DRIP_CHANCE
						&& out.size() < Bleeding.MAX_DECALS_PER_HIT) {
					Decal drip = Bleeding.dripDecal(hit, severity, arrives + 1, random);
					if (drip != null) {
						out.add(new Placement(BLOW, drip, null));
					}
				}
			}
		}

		Vec3 below = new Vec3(WOUND.x, WOUND.y - 0.2, WOUND.z);
		BlockHitResult ground = clip(below, below.add(0.0, -2.5, 0.0));
		if (ground != null && ground.getDirection() == Direction.UP) {
			float poolSize = Math.clamp(0.45f + 0.35f * severity + random.nextFloat() * 0.15f, 0.4f, 0.9f);
			long arrives = BLOW + SprayModel.flightTicks(WOUND.distanceTo(ground.getLocation()));
			out.add(new Placement(BLOW, Bleeding.poolDecal(ground, poolSize, arrives, random), WOUND));
		}

		return out;
	}

	/** The first of the wall and floor planes the segment meets, or null. */
	static BlockHitResult clip(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		double best = Double.MAX_VALUE;
		BlockHitResult hit = null;

		if (d.z < 0 && from.z >= 0) {
			double t = -from.z / d.z;
			Vec3 p = from.add(d.scale(t));
			if (t <= 1 && p.x >= 0 && p.x < WALL_W && p.y >= 0 && p.y < WALL_H && t < best) {
				best = t;
				hit = new BlockHitResult(p, Direction.SOUTH, new BlockPos((int) Math.floor(p.x), (int) Math.floor(p.y), -1), false);
			}
		}

		if (d.y < 0 && from.y >= 0) {
			double t = -from.y / d.y;
			Vec3 p = from.add(d.scale(t));
			if (t <= 1 && p.x >= 0 && p.x < WALL_W && p.z >= 0 && p.z < FLOOR_D && t < best) {
				hit = new BlockHitResult(p, Direction.UP, new BlockPos((int) Math.floor(p.x), -1, (int) Math.floor(p.z)), false);
			}
		}

		return hit;
	}

	private static List<Placement> cluster(long seed) {
		RandomSource random = RandomSource.create(seed);
		List<Placement> out = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			double a = random.nextDouble() * Math.PI * 2;
			double r = 0.15 + random.nextDouble() * 0.9;
			out.add(byHand(wall(2.0 + Math.cos(a) * r, 1.5 + Math.sin(a) * r * 0.7,
					0.2f + random.nextFloat() * 0.3f, random.nextFloat() * 360f, random.nextInt(12), 2 + i * 2)));
		}
		return out;
	}

	private static List<Placement> drips() {
		List<Placement> out = new ArrayList<>();
		double[] us = {0.7, 1.95, 3.2};
		for (int i = 0; i < us.length; i++) {
			long born = 2 + i * 15;
			// A splat with the run that crawls down out of it a tick later.
			out.add(byHand(wall(us[i], 2.3, 0.35f, 270f, 4, born)));
			out.add(byHand(wall(us[i], 2.3, 0.4f + 0.12f * i, 90f, 14, born + 1)));
		}
		return out;
	}

	private static List<Placement> pools(long seed) {
		RandomSource random = RandomSource.create(seed);
		List<Placement> out = new ArrayList<>();
		out.add(byHand(floor(1.4, 1.6, 0.8f, 30f, 12, 2)));
		out.add(byHand(floor(2.5, 2.2, 0.6f, 200f, 13, 10)));
		out.add(byHand(floor(1.9, 0.7, 0.45f, 100f, 12, 30)));
		for (int i = 0; i < 10; i++) {
			out.add(byHand(floor(0.5 + random.nextDouble() * 3, 0.3 + random.nextDouble() * 3,
					0.22f + random.nextFloat() * 0.15f, random.nextFloat() * 360f, 12 + random.nextInt(2), 4 + i * 3)));
		}
		return out;
	}

	/** Droplets landing on one spot in turn: the stain grows and darkens, the drip lengthens. */
	private static List<Placement> merge() {
		List<Placement> out = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			out.add(byHand(wall(1.3 + 0.02 * i, 1.6 - 0.02 * i, 0.25f, 300f, 3 * (i % 2), 2 + i * 12)));
		}
		for (int i = 0; i < 3; i++) {
			out.add(byHand(wall(2.7 + 0.03 * i, 2.2, 0.35f, 90f, 14, 5 + i * 25)));
		}
		// Two neighbours close enough for their fields to fuse without merging.
		out.add(byHand(wall(1.0, 0.7, 0.3f, 0f, 1, 6)));
		out.add(byHand(wall(1.33, 0.75, 0.3f, 0f, 1, 6)));
		return out;
	}
}
