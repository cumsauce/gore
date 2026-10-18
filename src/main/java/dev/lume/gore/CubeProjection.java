package dev.lume.gore;

import java.util.List;
import java.util.Optional;

/**
 * Picks the model surface a wound lands on.
 *
 * <p>Body stains used to be anchored by name — {@code findNamed} probed for
 * {@code "body"}, {@code "torso"}, {@code "leg0"} and so on. That works for a
 * humanoid and for a cow, whose model happens to spell its torso {@code "body"},
 * but a model with different names silently drew nothing, and even when a name
 * matched the stain sat at the part's far extent along the wound direction
 * rather than on a face. The result was blood that looked painted on a
 * bounding volume instead of on the body.
 *
 * <p>This class answers the question the renderer actually needs: given the
 * boxes a model is built from and the direction a wound faces, which face of
 * which box is the one the blood is on? It is deliberately free of Minecraft
 * types so it can be tested without a client.
 */
public final class CubeProjection {
	/** One axis-aligned model box. {@code dx/dy/dz} may be negative, as Minecraft allows. */
	public record Box(float x, float y, float z, float dx, float dy, float dz, String name) {
		public float minX() {
			return Math.min(x, x + dx);
		}

		public float maxX() {
			return Math.max(x, x + dx);
		}

		public float minY() {
			return Math.min(y, y + dy);
		}

		public float maxY() {
			return Math.max(y, y + dy);
		}

		public float minZ() {
			return Math.min(z, z + dz);
		}

		public float maxZ() {
			return Math.max(z, z + dz);
		}
	}

	/**
	 * A face centre, its outward normal, and the box it belongs to, all in one
	 * frame. The box is what lets the caller keep a stain on the face — a quad
	 * wider than the face, centred too far off it, or a drip run longer than the
	 * distance to the face's bottom edge, all hang over the silhouette and read
	 * as blood floating beside the body.
	 */
	public record Result(float[] point, float[] normal, Box box) {
		/** The name of the model part the face belongs to. */
		public String partName() {
			return box.name();
		}
	}

	/** Outward normals of the six faces, in the order {@link #faceCentre} expects. */
	public static final float[][] NORMALS = {
			{1.0F, 0.0F, 0.0F},
			{-1.0F, 0.0F, 0.0F},
			{0.0F, 1.0F, 0.0F},
			{0.0F, -1.0F, 0.0F},
			{0.0F, 0.0F, 1.0F},
			{0.0F, 0.0F, -1.0F}
	};

	private CubeProjection() {
	}

	/**
	 * Returns the box face the wound lands on, or empty when there is nothing to
	 * land on.
	 *
	 * <p>{@code impactDir} points from the body back towards where the blood came
	 * from, so the chosen face is the one whose outward normal best matches it.
	 * Among faces that face the wound equally well, the one furthest along
	 * {@code impactDir} wins — that is the surface a ray from the wound meets
	 * first, so a stain on a limb is not hidden behind the torso.
	 *
	 * @see #projectAt(List, float[], float[])
	 */
	public static Optional<Result> project(List<Box> boxes, float[] impactDir) {
		return projectAt(boxes, impactDir, null);
	}

	/**
	 * Like {@link #project}, but when a hit point is known the wound is placed on
	 * the face the blow actually met.
	 *
	 * <p>The face is chosen by the {@code outward} direction the wound faces: the
	 * side whose outward normal best matches {@code impactDir} wins, exactly as in
	 * {@link #project}. On a tie between coplanar surfaces the face whose centre is
	 * closest to {@code worldPoint} wins, so the stain lands at the hit point
	 * rather than on whichever part the surface scan happened to visit first.
	 *
	 * <p>{@code worldPoint} is the hit point in this {@code Box} frame — model
	 * units (16 per block) expressed relative to the part the boxes came from.
	 * When it is {@code null} this behaves identically to {@link #project}.
	 */
	public static Optional<Result> projectAt(List<Box> boxes, float[] impactDir, float[] worldPoint) {
		if (boxes == null || boxes.isEmpty() || impactDir == null) {
			return Optional.empty();
		}

		float length = (float) Math.sqrt(
				impactDir[0] * impactDir[0] + impactDir[1] * impactDir[1] + impactDir[2] * impactDir[2]);

		if (!(length > 1.0E-8F) || !Float.isFinite(length)) {
			return Optional.empty();
		}

		float ix = impactDir[0] / length;
		float iy = impactDir[1] / length;
		float iz = impactDir[2] / length;

		boolean pointKnown = worldPoint != null
				&& Float.isFinite(worldPoint[0]) && Float.isFinite(worldPoint[1]) && Float.isFinite(worldPoint[2]);
		float px = pointKnown ? worldPoint[0] : 0.0F;
		float py = pointKnown ? worldPoint[1] : 0.0F;
		float pz = pointKnown ? worldPoint[2] : 0.0F;

		Result best = null;
		float bestFacing = -2.0F;
		float bestDepth = -Float.MAX_VALUE;

		for (Box box : boxes) {
			for (int face = 0; face < NORMALS.length; face++) {
				float[] normal = NORMALS[face];
				float facing = normal[0] * ix + normal[1] * iy + normal[2] * iz;

				// Only the side of the box the blood actually reaches can carry it.
				if (facing <= 0.0F) {
					continue;
				}

				float[] centre = faceCentre(box, face);
				float depth;

				if (pointKnown) {
					// Distance from the face centre to the hit point, so a tie in
					// facing is resolved by proximity to where the blow landed
					// rather than by arbitrary box order.
					depth = -(px - centre[0]) * (px - centre[0])
							- (py - centre[1]) * (py - centre[1])
							- (pz - centre[2]) * (pz - centre[2]);
				} else {
					depth = centre[0] * ix + centre[1] * iy + centre[2] * iz;
				}

				// Strictly better facing always wins; on a tie the point-nearest
				// surface does (or the deeper surface when no point is known).
				if (facing > bestFacing + 1.0E-5F
						|| (facing >= bestFacing - 1.0E-5F && depth > bestDepth)) {
					bestFacing = facing;
					bestDepth = depth;
					best = new Result(centre, normal.clone(), box);
				}
			}
		}

		return Optional.ofNullable(best);
	}

	public static float[] faceCentre(Box box, int face) {
		float cx = (box.minX() + box.maxX()) * 0.5F;
		float cy = (box.minY() + box.maxY()) * 0.5F;
		float cz = (box.minZ() + box.maxZ()) * 0.5F;

		return switch (face) {
			case 0 -> new float[] {box.maxX(), cy, cz};
			case 1 -> new float[] {box.minX(), cy, cz};
			case 2 -> new float[] {cx, box.maxY(), cz};
			case 3 -> new float[] {cx, box.minY(), cz};
			case 4 -> new float[] {cx, cy, box.maxZ()};
			default -> new float[] {cx, cy, box.minZ()};
		};
	}

	/**
	 * Half the box's extent along an axis-aligned direction, in the box's own
	 * frame: the box reaches this far from its centre in {@code dir}. Used to
	 * keep a stain (and the run it drips) inside the face it was placed on.
	 */
	public static float extentAlong(Box box, float dirX, float dirY, float dirZ) {
		float hx = (box.maxX() - box.minX()) * 0.5F;
		float hy = (box.maxY() - box.minY()) * 0.5F;
		float hz = (box.maxZ() - box.minZ()) * 0.5F;

		return hx * Math.abs(dirX) + hy * Math.abs(dirY) + hz * Math.abs(dirZ);
	}
}
