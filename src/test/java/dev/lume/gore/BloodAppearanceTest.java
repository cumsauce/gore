package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * {@link BloodAppearance} must be a pure function of a stain's identity: the
 * same stain always looks the same, on every client, without a payload.
 */
class BloodAppearanceTest {

	private static final float EPS = 1.0e-6f;

	@Test
	void seedFor_isStableAndOrderSensitive() {
		long a = BloodAppearance.seedFor(1, 2, 3, 4, 0.25f, 0.5f, 7, 1234L);
		long b = BloodAppearance.seedFor(1, 2, 3, 4, 0.25f, 0.5f, 7, 1234L);

		assertEquals(a, b, "identical decals must hash identically");
		assertNotEquals(a, BloodAppearance.seedFor(1, 2, 3, 4, 0.25f, 0.5f, 7, 1235L),
				"born must change the seed");
		assertNotEquals(a, BloodAppearance.seedFor(1, 2, 3, 4, 0.25f, 0.5f, 8, 1234L),
				"variant must change the seed");
	}

	@Test
	void tint_jittersRgbWithinBounds() {
		for (float d : new float[] { 0.0f, 0.5f, 1.0f }) {
			int base = Drying.tint(d);
			int baseR = ARGB.red(base);
			int baseG = ARGB.green(base);
			int baseB = ARGB.blue(base);

			for (int i = 0; i < 200; i++) {
				long seed = BloodAppearance.seedFor(i, i * 3, i * 7, i % 6, i * 0.125f, i * 0.0625f, i % 12, i * 977L);
				int tint = BloodAppearance.tint(seed, d);

				assertEquals(255, ARGB.alpha(tint), "alpha stays opaque");
				assertWithin(ARGB.red(tint), baseR, 0.08, "red");
				assertWithin(ARGB.green(tint), baseG, 0.05, "green");
				assertWithin(ARGB.blue(tint), baseB, 0.05, "blue");
			}
		}
	}

	@Test
	void tint_interpolatesDrynessMonotonically() {
		long seed = BloodAppearance.seedFor(9, 8, 7, 2, 0.1f, 0.9f, 5, 4242L);
		int previous = Integer.MAX_VALUE;

		for (int step = 0; step <= 20; step++) {
			int red = ARGB.red(BloodAppearance.tint(seed, step / 20.0f));
			assertTrue(red <= previous, "red must not rise as the stain dries (step " + step + ")");
			previous = red;
		}

		assertWithin(ARGB.red(BloodAppearance.tint(seed, 0.0f)), 255, 0.08, "wet red");
		assertWithin(ARGB.red(BloodAppearance.tint(seed, 1.0f)), 133, 0.08, "dry red");
	}

	@Test
	void sheen_fadesToZero() {
		for (int i = 0; i < 50; i++) {
			long seed = BloodAppearance.seedFor(i, 0, 0, 0, 0.0f, 0.0f, 0, i);
			assertTrue(BloodAppearance.sheen(seed, 0.0f) > 0.3f, "fresh blood is glossy");
			assertEquals(0.0f, BloodAppearance.sheen(seed, 1.0f), EPS, "dry blood is matte");
		}
	}

	@Test
	void legacyBornZeroRendersUntintedJitter() {
		long now = 999_999L;
		long seed = BloodAppearance.seedFor(4, 5, 6, 1, 0.3f, 0.7f, 3, 0L);

		// born == 0 is the pre-drying legacy stamp: Drying treats it as fully
		// dry, so it must never read as fresh red and never as a foreign fluid.
		assertEquals(1.0f, Drying.dryness(0L, now, 1.0), EPS);

		int tint = BloodAppearance.tint(seed, Drying.dryness(0L, now, 1.0));
		int dry = Drying.tint(1.0f);

		assertEquals(255, ARGB.alpha(tint));
		assertWithin(ARGB.red(tint), ARGB.red(dry), 0.08, "legacy red");
		assertWithin(ARGB.green(tint), ARGB.green(dry), 0.05, "legacy green");
		assertWithin(ARGB.blue(tint), ARGB.blue(dry), 0.05, "legacy blue");
	}

	@Test
	void doesNotConsumeRandomSource() {
		RandomSource reference = RandomSource.create(7L);
		RandomSource observed = RandomSource.create(7L);

		for (int i = 0; i < 100; i++) {
			long seed = BloodAppearance.seedFor(i, i, i, 0, 0.5f, 0.5f, 1, i);
			BloodAppearance.tint(seed, 0.25f);
			BloodAppearance.sheen(seed, 0.25f);
		}

		for (int i = 0; i < 16; i++) {
			assertEquals(reference.nextLong(), observed.nextLong(),
					"appearance must never draw from the shared RNG stream");
		}
	}

	@Test
	void unitDisk_staysInsideAndFillsTheDisk() {
		double sumRadius = 0.0;

		for (int i = 0; i < 1000; i++) {
			long seed = BloodAppearance.seedFor(i, i * 5, 3, i % 6, 0.1f * (i % 7), 0.2f, i % 4, i);
			float[] p = BloodAppearance.unitDisk(seed, 7);

			double radius = Math.sqrt(p[0] * p[0] + p[1] * p[1]);
			assertTrue(radius <= 1.0 + 1.0e-5, "point outside the disk: " + radius);
			assertEquals(radius, p[2], 1.0e-5, "radius component must match the point");
			sumRadius += radius;

			float[] again = BloodAppearance.unitDisk(seed, 7);
			assertEquals(p[0], again[0], 0f);
			assertEquals(p[1], again[1], 0f);
		}

		// sqrt(u) area weighting puts the mean radius at 2/3; sampling u directly
		// (a centre-biased disk) would sit near 1/2.
		double mean = sumRadius / 1000.0;
		assertTrue(mean > 0.58 && mean < 0.72, "mean radius " + mean);
	}

	private static void assertWithin(int actual, int expected, double band, String channel) {
		// + 1.0 absorbs the Math.round in tint(): a jittered channel can land
		// half a unit outside the nominal band before rounding.
		double tolerance = Math.max(1.0, expected * band) + 1.0;
		assertTrue(Math.abs(actual - expected) <= tolerance,
				channel + " " + actual + " outside ±" + band + " of " + expected);
	}
}
