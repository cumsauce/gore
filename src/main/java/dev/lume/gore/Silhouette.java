package dev.lume.gore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The alpha mask of an item icon, used to keep blood on the icon instead of on its
 * transparent corners.
 *
 * <p>The mask is a row-major {@code byte[]} of alpha values, one per pixel. All lookups
 * treat pixels outside the map as transparent, so callers never need to clamp.
 *
 * <p>Everything here is pure geometry: no Minecraft types, so it is unit-testable from the
 * main source set. {@code ItemSilhouette} is what turns a real {@code ItemStack} into one.
 */
public record Silhouette(byte[] alpha, int w, int h) {

	/** Alpha at or above this counts as part of the icon. Matches the atlas tooling's threshold. */
	public static final int OPAQUE = 128;

	public Silhouette {
		if (alpha == null) {
			throw new IllegalArgumentException("alpha map must not be null");
		}
		if (w <= 0 || h <= 0) {
			throw new IllegalArgumentException("dimensions must be positive, got " + w + "x" + h);
		}
		if (alpha.length != w * h) {
			throw new IllegalArgumentException("alpha map is " + alpha.length + " bytes, expected " + (w * h));
		}
	}

	/** Alpha at {@code (x, y)}, or {@code 0} outside the map. */
	private int alphaAt(int x, int y) {
		if (x < 0 || y < 0 || x >= w || y >= h) {
			return 0;
		}
		return alpha[y * w + x] & 0xFF;
	}

	/** Whether the pixel belongs to the icon. Out of bounds is transparent, not an error. */
	public boolean opaque(int x, int y) {
		return alphaAt(x, y) >= OPAQUE;
	}

	/**
	 * Magnitude of the central-difference alpha gradient at {@code (x, y)}, in alpha units
	 * ({@code 0} for a flat region, up to ~{@code 360} across a hard edge).
	 *
	 * <p>Large values mark the boundary between icon and background, so the item-blood path
	 * can keep smears away from the silhouette edge.
	 */
	public float edgeAt(int x, int y) {
		int gx = alphaAt(x + 1, y) - alphaAt(x - 1, y);
		int gy = alphaAt(x, y + 1) - alphaAt(x, y - 1);
		return (float) Math.sqrt((double) gx * gx + (double) gy * gy);
	}

	/**
	 * Centre of mass of the opaque pixels, or {@code (0, 0)} when the map has none.
	 */
	public float[] centroid() {
		double sx = 0.0;
		double sy = 0.0;
		int n = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (opaque(x, y)) {
					sx += x;
					sy += y;
					n++;
				}
			}
		}
		if (n == 0) {
			return new float[] { 0f, 0f };
		}
		return new float[] { (float) (sx / n), (float) (sy / n) };
	}

	/**
	 * Unit vector along the icon's longest direction, from a PCA of the opaque pixels.
	 *
	 * <p>The sign is arbitrary (a smear is symmetric along its axis), but the direction is
	 * stable. An isotropic or single-pixel mask has no preferred direction and returns the
	 * {@code (1, 0)} fallback rather than a NaN from an unnormalisable eigenvector.
	 */
	public float[] dominantAxis() {
		double sx = 0.0;
		double sy = 0.0;
		int n = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (opaque(x, y)) {
					sx += x;
					sy += y;
					n++;
				}
			}
		}
		if (n == 0) {
			return new float[] { 1f, 0f };
		}
		double mx = sx / n;
		double my = sy / n;

		double cxx = 0.0;
		double cxy = 0.0;
		double cyy = 0.0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (opaque(x, y)) {
					double dx = x - mx;
					double dy = y - my;
					cxx += dx * dx;
					cxy += dx * dy;
					cyy += dy * dy;
				}
			}
		}
		// Work with the covariance per pixel so the guard below is scale-free.
		cxx /= n;
		cxy /= n;
		cyy /= n;

		// A single pixel (or a map of identical points) has a zero covariance matrix; the
		// determinant guard catches it. An isotropic disc has a large determinant but no
		// preferred direction, and is caught by the near-zero off-diagonal branch below.
		double det = cxx * cyy - cxy * cxy;
		if (!(det > 1e-6)) {
			return new float[] { 1f, 0f };
		}

		// Dominant eigenvector of [[cxx, cxy], [cxy, cyy]].
		double scale = Math.max(cxx, cyy);
		double vx;
		double vy;
		if (Math.abs(cxy) <= 1e-6 * scale) {
			// Axis-aligned: the eigenvectors are the coordinate axes.
			if (cxx >= cyy) {
				vx = 1.0;
				vy = 0.0;
			} else {
				vx = 0.0;
				vy = 1.0;
			}
		} else {
			double half = (cxx - cyy) / 2.0;
			double lambda = (cxx + cyy) / 2.0 + Math.sqrt(half * half + cxy * cxy);
			vx = cxy;
			vy = lambda - cxx;
		}

		double length = Math.sqrt(vx * vx + vy * vy);
		if (!(length > 1e-9)) {
			return new float[] { 1f, 0f };
		}
		return new float[] { (float) (vx / length), (float) (vy / length) };
	}

	/**
	 * Picks {@code count} places to put a blood smear on this icon, in icon pixel
	 * coordinates (pixel centres, so {@code (0.5, 0.5)} is the top-left pixel).
	 *
	 * <p>Three properties matter, and the tests pin all three. Every point lands on an
	 * opaque pixel, so blood never floats in the transparent corner of a sword or a bow.
	 * Points prefer the silhouette's rim, where blood clings to an edge and catches the
	 * light, over the flat middle. And they are spread along {@link #dominantAxis()}, so
	 * several smears on one item read as a streak down the blade rather than a cluster.
	 *
	 * <p>{@code seed} only jitters where inside a quantile band the point lands, so the
	 * same item always gets the same blood: the decoration must not flicker.
	 */
	public static List<float[]> place(byte[] alpha, int w, int h, int count, long seed) {
		List<float[]> points = new ArrayList<>(Math.max(0, count));

		if (count <= 0) {
			return points;
		}

		Silhouette shape = new Silhouette(alpha, w, h);
		List<Integer> opaque = new ArrayList<>();

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (shape.opaque(x, y)) {
					opaque.add(y * w + x);
				}
			}
		}

		if (opaque.isEmpty()) {
			return points;
		}

		double[] edges = new double[opaque.size()];
		double[] sortedEdges = new double[opaque.size()];

		for (int i = 0; i < opaque.size(); i++) {
			edges[i] = shape.edgeAt(opaque.get(i) % w, opaque.get(i) / w);
			sortedEdges[i] = edges[i];
		}

		Arrays.sort(sortedEdges);
		double median = sortedEdges[sortedEdges.length / 2];

		List<Integer> rim = new ArrayList<>();

		for (int i = 0; i < opaque.size(); i++) {
			if (edges[i] > median) {
				rim.add(opaque.get(i));
			}
		}

		// A small or soft icon can have no pixel above its own median. Falling back to
		// the whole shape still respects the one rule that must never break: stay on the
		// icon.
		if (rim.size() < count) {
			rim = opaque;
		}

		float[] axis = shape.dominantAxis();
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;

		for (int index : opaque) {
			double projection = (index % w) * axis[0] + (index / w) * axis[1];
			lo = Math.min(lo, projection);
			hi = Math.max(hi, projection);
		}

		double span = hi - lo;
		Random random = new Random(seed);

		for (int i = 0; i < count; i++) {
			double band = count == 1 ? 0.5 : i / (double) (count - 1);
			double target = lo + span * band;

			if (span > 1.0) {
				target += (random.nextDouble() - 0.5) * span / (count * 2.0);
			}

			int best = rim.get(0);
			double bestDistance = Double.MAX_VALUE;

			for (int index : rim) {
				double projection = (index % w) * axis[0] + (index / w) * axis[1];
				double distance = Math.abs(projection - target);

				if (distance < bestDistance) {
					bestDistance = distance;
					best = index;
				}
			}

			points.add(new float[] { best % w + 0.5f, best / w + 0.5f });
		}

		return points;
	}
}
