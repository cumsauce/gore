package dev.lume.gore;

import net.minecraft.util.ARGB;

/**
 * Deterministic per-stain appearance.
 *
 * <p>Two hits never leave identical blood. Every stain carries a little colour
 * variation and a wet sheen, but the world has no room for it in the network
 * payload: {@link Decal} and {@link GoreStain} shapes are frozen, and a stain
 * re-rendered next frame must not shimmer. So the variation is not stored or
 * synced at all — it is a pure function of the stain's own identity, and every
 * client derives the same answer for free.
 *
 * <p>Everything here is a hash of integers and floats. Nothing touches
 * {@link net.minecraft.util.RandomSource}, so the seeded spray RNG the game
 * tests rely on is never perturbed by rendering a stain.
 *
 * <p>{@link #tint} returns a <em>multiplier</em> on {@link Drying#tint}'s
 * contract (white when wet, dark brown when dry) — it is handed to
 * {@code submitModelPart(..., UvMapping, int)} and scaled against the blood
 * atlas, never used as a composited colour.
 */
public final class BloodAppearance {

	/** Red channel jitter, ±8 % of the drying ramp. */
	private static final float RED_JITTER = 0.08f;

	/** Green and blue jitter tighter than red so stains stay in the blood palette. */
	private static final float GREEN_JITTER = 0.05f;

	private static final float BLUE_JITTER = 0.05f;

	private BloodAppearance() {
	}

	/**
	 * Stable 64-bit identity hash for one stain, derived from everything that
	 * makes it unique: where it sits, which face, its offset on that face, which
	 * atlas cell it draws, and when it was placed.
	 */
	public static long seedFor(int x, int y, int z, int face, float u, float v, int variant, long born) {
		long h = 0x9E3779B97F4A7C15L;
		h = mix(h, x);
		h = mix(h, y);
		h = mix(h, z);
		h = mix(h, face);
		h = mix(h, Float.floatToRawIntBits(u));
		h = mix(h, Float.floatToRawIntBits(v));
		h = mix(h, variant);
		h = mix(h, born);
		return h;
	}

	/**
	 * Multiplier tint for a stain: {@link Drying#tint} with each channel nudged
	 * by a stable per-stain factor, so no two stains read as the same flat red.
	 *
	 * @param seed     from {@link #seedFor}
	 * @param dryness  {@code 0f} wet … {@code 1f} dry, normally from
	 *                 {@link Drying#dryness}
	 */
	public static int tint(long seed, float dryness) {
		int base = Drying.tint(dryness);
		int r = ARGB.red(base);
		int g = ARGB.green(base);
		int b = ARGB.blue(base);

		int rr = clamp(Math.round(r * (1.0f + bipolar(seed, 1) * RED_JITTER)));
		int gg = clamp(Math.round(g * (1.0f + bipolar(seed, 2) * GREEN_JITTER)));
		int bb = clamp(Math.round(b * (1.0f + bipolar(seed, 3) * BLUE_JITTER)));

		return ARGB.color(255, rr, gg, bb);
	}

	/**
	 * How strongly a stain's baked specular highlight shows, {@code 0f..1f}.
	 * Fresh blood is wet and glossy; dry blood is matte. The atlas paints the
	 * highlight's shape, this fades it out.
	 */
	public static float sheen(long seed, float dryness) {
		return (1.0f - dryness) * (0.5f + 0.5f * unit01(seed, 4));
	}

	/** SplitMix64-style avalanche step, so adjacent stains hash far apart. */
	private static long mix(long h, long v) {
		long z = h + v * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** A stable value in {@code [0, 1)} for one channel of one stain. */
	private static float unit01(long seed, int salt) {
		long z = mix(seed, 0xC6BC279692B5C323L * (salt + 1));
		return ((z >>> 40) & 0xFFFFFFL) / (float) (1 << 24);
	}

	/** A stable value in {@code [-1, 1]} for one channel of one stain. */
	private static float bipolar(long seed, int salt) {
		return unit01(seed, salt) * 2.0f - 1.0f;
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
