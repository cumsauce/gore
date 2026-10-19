package dev.lume.gore;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Merging rules for decals that are added to a wall: liquid films on one face
 * coalesce when they touch (surface tension), so two runs a few centimetres
 * apart become one heavier run, and repeated spatter on the same spot builds a
 * thicker, denser stain. Merging replaces the target with a single merged
 * decal, so the decal count never grows for a merge and the budget is spent on
 * distinct marks.
 *
 * <p>Pools (variant 12–13) and decals on different faces never merge.
 */
public final class DecalMerge {
	/** Drip tolerance along the face's tangent axes, in block units. */
	public static final float DRIP_DU = 0.08f;
	public static final float DRIP_DV = 0.25f;
	/** Impact stains always merge when their (u,v) positions are this close. */
	public static final float STAIN_DIST = 0.06f;
	/**
	 * Beyond {@link #STAIN_DIST}, impact stains also merge when their discs
	 * overlap this much: centre distance under this fraction of the summed
	 * radii. Like metaballs, two blots that visibly run into each other become
	 * one heavier blot instead of two coplanar quads fighting for the same
	 * pixels.
	 */
	public static final float STAIN_OVERLAP = 0.6f;
	public static final float DRIP_MAX_SIZE = 0.9f;
	public static final float STAIN_MAX_SIZE = 0.5f;

	/** The drip variant (atlas cell 14); the single source of drip-ness. */
	private static final int DRIP_VARIANT = 14;
	/** Impact stains are variants 0–11; 12–13 are pools. */
	private static final int STAIN_VARIANTS = 12;

	private DecalMerge() {
	}

	/**
	 * The first existing decal on the same block face that this candidate would
	 * coalesce with: a drip within {@link #DRIP_DU}/{@link #DRIP_DV} of another
	 * drip, or an impact stain within {@link #STAIN_DIST} of another impact
	 * stain. Pools never merge, and different faces never merge.
	 */
	public static Optional<Decal> findTarget(Collection<Decal> existing, Decal candidate) {
		// Nearest match wins, so the client — which re-runs this with the merged
		// decal, anchored on its target's (u,v) — always finds the same target.
		Decal best = null;
		float bestDist = Float.MAX_VALUE;

		for (Decal decal : existing) {
			if (decal.x() != candidate.x() || decal.y() != candidate.y()
					|| decal.z() != candidate.z() || decal.face() != candidate.face()) {
				continue;
			}

			boolean match = (isDrip(decal) && isDrip(candidate) && withinDripTolerance(decal, candidate))
					|| (isStain(decal) && isStain(candidate) && withinStainDistance(decal, candidate));

			if (match) {
				float du = decal.u() - candidate.u();
				float dv = decal.v() - candidate.v();
				float dist = du * du + dv * dv;

				if (dist < bestDist) {
					best = decal;
					bestDist = dist;
				}
			}
		}

		return Optional.ofNullable(best);
	}

	/**
	 * Adds the candidate to a mutable decal list, coalescing it into an
	 * existing neighbour when {@link #findTarget} hits: the target is replaced
	 * in place by the single merged decal, so a merge never grows the list.
	 * Returns the decal that was actually stored. (No eviction here — see
	 * {@code DecalStore.add}, which owns the budget.)
	 */
	public static Decal applyTo(List<Decal> decals, Decal candidate) {
		Decal result = findTarget(decals, candidate)
				.map(target -> {
					decals.remove(target);
					return merged(target, candidate);
				})
				.orElse(candidate);
		decals.add(result);
		return result;
	}

	/**
	 * The single decal that replaces {@code target} once {@code candidate} has
	 * merged into it. Keeps the target's position, face, (u,v) and rotation;
	 * both grow by quadrature (area adds) towards their max size, and stains
	 * step up one density band. The
	 * merged decal is born when the new supply arrived, restarting the flow.
	 *
	 * <p>Callers must only pass pairs {@link #findTarget} accepted.
	 */
	public static Decal merged(Decal target, Decal candidate) {
		if (isDrip(target)) {
			float size = (float) Math.sqrt(target.size() * target.size() + candidate.size() * candidate.size());
			return new Decal(target.x(), target.y(), target.z(), target.face(), target.u(), target.v(),
					Math.min(DRIP_MAX_SIZE, size), target.rot(), DRIP_VARIANT, candidate.born());
		}

		int variant = (target.variant() / 3) * 3 + Math.min(2, target.variant() % 3 + 1);
		return new Decal(target.x(), target.y(), target.z(), target.face(), target.u(), target.v(),
				Math.min(STAIN_MAX_SIZE, Math.max(target.size() * 1.15f,
						(float) Math.sqrt(target.size() * target.size() + candidate.size() * candidate.size()))),
				// Keep the target's clock: a stain already on the wall must not
				// vanish while the droplet merging into it is still in the air.
				target.rot(), variant, Math.min(target.born(), candidate.born()));
	}

	/**
	 * True for wall drips (atlas cell 14), decided by variant alone — never by
	 * {@code born}. {@code DecalRenderer.isDrip} delegates here so the rule has
	 * one definition in the main source set, where the unit tests can see it.
	 */
	public static boolean isDrip(Decal decal) {
		return decal.variant() == DRIP_VARIANT;
	}

	/** Impact stain: variants 0–11. Pools (12–13) are excluded. */
	private static boolean isStain(Decal decal) {
		return decal.variant() < STAIN_VARIANTS;
	}

	/**
	 * True for the two pool cells (12, 13). Pools spread as they age
	 * ({@link Flow#poolSize}) instead of being fixed stickers, so the renderer
	 * needs to recognise them; the merge rules keep them apart because two
	 * overlapping spreading blobs would visibly jump when they coalesce.
	 */
	public static boolean isPool(Decal decal) {
		return decal.variant() == 12 || decal.variant() == 13;
	}

	private static boolean withinDripTolerance(Decal a, Decal b) {
		float du = Math.abs(a.u() - b.u());
		float dv = Math.abs(a.v() - b.v());
		// DRIP_DV is the vertical (along-the-run) tolerance. On every wall but
		// EAST, axis2 is world up; EAST's basis has axis1 up instead, so swap.
		if (a.face() == net.minecraft.core.Direction.EAST.get3DDataValue()) {
			return dv <= DRIP_DU && du <= DRIP_DV;
		}
		return du <= DRIP_DU && dv <= DRIP_DV;
	}

	private static boolean withinStainDistance(Decal a, Decal b) {
		float du = a.u() - b.u();
		float dv = a.v() - b.v();
		double dist = Math.sqrt(du * du + dv * dv);
		return dist <= STAIN_DIST || dist <= STAIN_OVERLAP * 0.5f * (a.size() + b.size());
	}
}
