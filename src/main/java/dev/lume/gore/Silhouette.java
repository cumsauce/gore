package dev.lume.gore;

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
}
