package dev.lume.gore;

/**
 * The physics of a wall drip, as pure functions of the drip's age in ticks.
 *
 * <p>A run of blood on a vertical wall starts from rest and accelerates under
 * gravity (time constant {@link #A_TICKS}), then slows as the finite volume
 * behind it depletes and the film thins (time constant {@link #D_TICKS}). The
 * length follows the integral of {@code v(t) = (1 − e^{−t/A}) · e^{−t/D}} over
 * {@link #T_TICKS}, normalised to 0..1.
 *
 * <p>As it stretches it thins ({@link #widthScale}) and the leading bead keeps
 * only the volume not yet left behind as film ({@link #beadScale}, mass
 * conservation); past {@link #BREAK_OFF} of its length the bead pinches off a
 * falling droplet.
 */
public final class Flow {
	/** Acceleration time constant, in ticks (≈ 0.3 s): the run starts from rest. */
	public static final int A_TICKS = 6;
	/** Depletion time constant, in ticks (≈ 2 s): how fast the film runs dry. */
	public static final int D_TICKS = 40;
	/** Age, in ticks (6 s), at which the run has stretched its full length. */
	public static final int T_TICKS = 120;
	/** Stretch fraction at which the bead pinches off a droplet. */
	public static final float BREAK_OFF = 0.6f;
	/** Fraction of width lost when the run is fully stretched. */
	public static final float THIN = 0.35f;

	private Flow() {
	}

	/**
	 * How far the run has stretched, 0 at birth to 1 at {@link #T_TICKS} ticks:
	 * {@code F(age)/F(T)}, where {@code F} is the integral of the accelerating-
	 * then-depleting velocity {@code v(t) = (1 − e^{−t/A})·e^{−t/D}}.
	 */
	public static float length(long ageTicks) {
		if (ageTicks <= 0L) {
			return 0.0f;
		}

		if (ageTicks >= T_TICKS) {
			return 1.0f;
		}

		return (float) (f(ageTicks) / f(T_TICKS));
	}

	/**
	 * Mass conservation for the leading bead: as the run stretches to fraction
	 * {@code f} it leaves 70 % of its volume behind as film, so the bead's linear
	 * scale is the cube root of what remains.
	 */
	public static float beadScale(float f) {
		return (float) Math.cbrt(1.0 - 0.7 * f);
	}

	/** The film thins as it stretches: width at stretch fraction {@code f}. */
	public static float widthScale(float f) {
		return 1.0f - THIN * f;
	}

	/**
	 * The velocity integral {@code ∫₀ᵗ (1 − e^{−τ/A})·e^{−τ/D} dτ}
	 * = {@code D(1 − e^{−t/D}) − (AD/(A+D))(1 − e^{−t(A+D)/(AD)})}.
	 */
	private static double f(double t) {
		double a = A_TICKS;
		double d = D_TICKS;
		return d * (1.0 - Math.exp(-t / d))
				- (a * d / (a + d)) * (1.0 - Math.exp(-t * (a + d) / (a * d)));
	}
}
