package dev.lume.gore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for {@link Silhouette}, the alpha mask the item-blood path uses to
 * keep stains on the icon instead of on its transparent corners.
 */
class SilhouetteTest {

	private static final int N = 16;

	/** A solid square covering pixels {@code [lo, hi]} on both axes of a 16x16 map. */
	private static Silhouette square(int lo, int hi) {
		byte[] alpha = new byte[N * N];
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (x >= lo && x <= hi && y >= lo && y <= hi) {
					alpha[y * N + x] = (byte) 255;
				}
			}
		}
		return new Silhouette(alpha, N, N);
	}

	@Test
	void opaqueFollowsAlphaMap() {
		byte[] alpha = { 0, 100, 127, (byte) 128, (byte) 255 };
		Silhouette s = new Silhouette(alpha, 5, 1);

		assertFalse(s.opaque(0, 0), "alpha 0 must not be opaque");
		assertFalse(s.opaque(1, 0), "alpha 100 must not be opaque");
		assertFalse(s.opaque(2, 0), "alpha 127 must not be opaque");
		assertTrue(s.opaque(3, 0), "alpha 128 must be opaque");
		assertTrue(s.opaque(4, 0), "alpha 255 must be opaque");

		// Out-of-bounds reads the same way the gradient does: transparent, not a crash.
		assertFalse(s.opaque(-1, 0), "out of bounds must not be opaque");
		assertFalse(s.opaque(5, 0), "out of bounds must not be opaque");
	}

	@Test
	void edgeAtDetectsBoundary() {
		Silhouette s = square(5, 10);

		assertTrue(s.edgeAt(5, 7) > 100f, "left border should have a strong gradient");
		assertTrue(s.edgeAt(10, 7) > 100f, "right border should have a strong gradient");
		assertTrue(s.edgeAt(7, 5) > 100f, "top border should have a strong gradient");
		assertTrue(s.edgeAt(7, 10) > 100f, "bottom border should have a strong gradient");

		assertEquals(0f, s.edgeAt(8, 8), 1e-3f, "deep inside the square there is no gradient");
		assertEquals(0f, s.edgeAt(0, 0), 1e-3f, "far corner is transparent on every side");
		assertEquals(0f, s.edgeAt(15, 15), 1e-3f, "far corner is transparent on every side");
	}

	@Test
	void degenerateCovarianceFallsBackToAxis() {
		// A disc: the covariance is isotropic, so no direction is preferred. The result
		// must still be a unit vector with no NaN and no division by zero.
		byte[] alpha = new byte[N * N];
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				double dx = x - 7.5;
				double dy = y - 7.5;
				if (dx * dx + dy * dy <= 36.0) {
					alpha[y * N + x] = (byte) 255;
				}
			}
		}
		Silhouette disc = new Silhouette(alpha, N, N);

		float[] axis = disc.dominantAxis();
		assertEquals(2, axis.length);
		assertTrue(Float.isFinite(axis[0]) && Float.isFinite(axis[1]), "axis must be finite, got " + axis[0] + "," + axis[1]);
		float length = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1]);
		assertEquals(1f, length, 1e-3f, "axis must be unit length");
		assertEquals(1f, axis[0], 1e-2f, "an isotropic blob has no preferred direction; expected the (1, 0) fallback, got " + axis[0] + "," + axis[1]);
		assertEquals(0f, axis[1], 1e-2f, "an isotropic blob has no preferred direction; expected the (1, 0) fallback, got " + axis[0] + "," + axis[1]);

		// A single opaque pixel is a genuinely degenerate covariance (det == 0).
		Silhouette dot = new Silhouette(new byte[] { (byte) 255 }, 1, 1);
		float[] fallback = dot.dominantAxis();
		assertEquals(1f, fallback[0], 0f);
		assertEquals(0f, fallback[1], 0f);
	}

	@Test
	void dominantAxisAlignsWithElongation() {
		// 4 wide x 14 tall: the long axis is vertical.
		byte[] alpha = new byte[N * N];
		for (int y = 1; y <= 14; y++) {
			for (int x = 6; x <= 9; x++) {
				alpha[y * N + x] = (byte) 255;
			}
		}
		Silhouette tall = new Silhouette(alpha, N, N);

		float[] axis = tall.dominantAxis();
		assertEquals(2, axis.length);
		assertEquals(1f, Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1]), 1e-3f, "axis must be unit length");
		assertEquals(0f, axis[0], 0.1f, "a tall rectangle is dominated by its vertical axis, got " + axis[0] + "," + axis[1]);
		assertEquals(1f, Math.abs(axis[1]), 0.1f, "a tall rectangle is dominated by its vertical axis, got " + axis[0] + "," + axis[1]);
	}
}
