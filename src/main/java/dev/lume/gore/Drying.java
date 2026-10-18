package dev.lume.gore;

import net.minecraft.util.ARGB;

/**
 * Ageing of blood: a thin film stays glossy red for a minute, then oxidises
 * and dries to a matte dark brown (haemoglobin → methaemoglobin/haemichrome).
 *
 * <p>Dryness runs 0 (fresh, wet) to 1 (fully dry) over
 * {@code (DRY_TICKS − WET_TICKS) · scale} game ticks, after an initial
 * {@code WET_TICKS · scale} wet period. Decals born before drying existed
 * ({@code born == 0}) are treated as fully dry.
 */
public final class Drying {

	/** Ticks a stain stays fully wet (~60 s at scale 1.0). */
	public static final long WET_TICKS = 1200L;

	/**
	 * Age threshold (ticks since the stain was placed) at which it is fully dry,
	 * multiplied by {@link #SCALE} (5 min at scale 1.0).
	 */
	public static final long DRY_TICKS = 6000L;

	/**
	 * Test hook: multiplies both thresholds so the game test can watch a stain
	 * dry in seconds. Set {@code -Dgore.dryScale=0.02} in the game-test JVM.
	 * Null, blank or unparseable input degrades to {@code 1.0}.
	 */
	public static final double SCALE = scaleOr(System.getProperty("gore.dryScale"), 1.0);

	private Drying() {
	}

	/** The parsed {@code raw}, or {@code fallback} when null, blank or unparseable. */
	private static double scaleOr(String raw, double fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * How dry a stain is: {@code 0f} while wet, rising to {@code 1f} when dry.
	 *
	 * @param born  game time the stain was placed; {@code 0} means legacy, fully dry
	 * @param now   current game time
	 * @param scale multiplies both the wet period and the drying period
	 */
	public static float dryness(long born, long now, double scale) {
		if (born == 0L) {
			return 1f;
		}
		long age = now - born;
		if (age <= 0L) {
			return 0f;
		}
		double wet = WET_TICKS * scale;
		double span = (DRY_TICKS - WET_TICKS) * scale;
		return (float) Math.max(0.0, Math.min(1.0, (age - wet) / span));
	}

	/**
	 * Multiplier tint for a stain at the given dryness: white while wet,
	 * dark brown {@code (133, 217, 153)} when dry.
	 */
	public static int tint(float dryness) {
		int r = Math.round(255.0f * (1.0f - 0.48f * dryness));
		int g = Math.round(255.0f * (1.0f - 0.15f * dryness));
		int b = Math.round(255.0f * (1.0f - 0.40f * dryness));
		return ARGB.color(255, r, g, b);
	}

	/**
	 * Ticks after the stain is fully dry at which it has faded to nothing,
	 * multiplied by {@link #SCALE} (20 min at scale 1.0). A dry stain flakes
	 * off rather than sitting on the wall forever: the budget stops being the
	 * only thing that clears old blood.
	 */
	public static final long FADE_TICKS = 24_000L;

	/**
	 * How much of a stain is still there, {@code 1f} until it finishes drying,
	 * then falling linearly to {@code 0f} at {@link #FADE_TICKS} after birth.
	 * Used as the alpha multiplier, so old blood thins out gradually instead
	 * of blinking away.
	 *
	 * <p>Legacy stains ({@code born == 0}) never fade — their age is unknown.
	 */
	public static float fade(long born, long now, double scale) {
		if (born == 0L) {
			return 1f;
		}

		long age = now - born;

		if (age <= 0L) {
			return 1f;
		}

		double dryAt = DRY_TICKS * scale;
		double goneAt = dryAt + FADE_TICKS * scale;

		if (age <= dryAt) {
			return 1f;
		}

		if (age >= goneAt) {
			return 0f;
		}

		return (float) ((goneAt - age) / (goneAt - dryAt));
	}

	/**
	 * Ticks of rain exposure that wash a stain away, multiplied by
	 * {@link #SCALE} (4 min of rain at scale 1.0). Rain dissolves blood far
	 * faster than it dries, but only while it is actually raining on the
	 * stain — the sweep that calls this checks exposure per decal.
	 */
	public static final long RAIN_WASH_TICKS = 4_800L;

	/**
	 * How much of a stain is left after {@code rainTicks} of accumulated rain
	 * exposure: full until the rain starts to win, then a linear dissolve to
	 * {@code 0f} at {@link #RAIN_WASH_TICKS}. Returned stains are candidates
	 * for removal, not fades — rain runs blood off a wall, it does not thin
	 * it into paint.
	 */
	public static float rainLeft(long rainTicks, double scale) {
		double goneAt = RAIN_WASH_TICKS * scale;

		if (rainTicks <= 0L) {
			return 1f;
		}

		if (rainTicks >= goneAt) {
			return 0f;
		}

		return (float) (1.0 - rainTicks / goneAt);
	}
}
