package dev.lume.gore;

import net.minecraft.util.ARGB;

/**
 * The pure look policy for blood particles: how big a droplet is for its physical
 * radius, how dark and opaque it reads, how long mist hangs and how fast it fades.
 *
 * <p>Everything here is a static function of its argument, with no Minecraft state,
 * so it can be unit-tested. The particle classes live in the client source set, which
 * is not on any unit-test classpath under Loom's split source sets — so any decision
 * a test must pin belongs here, and the particles just call in.
 */
public final class ParticleLook {

	/** Tiny droplet radius, matching {@code SprayModel.dropRadiusM}'s lower clamp. */
	public static final float MIN_DROP_RADIUS_M = 0.15e-3f;

	/** Huge droplet radius, matching {@code SprayModel.dropRadiusM}'s upper clamp. */
	public static final float MAX_DROP_RADIUS_M = 2.5e-3f;

	/** Mist droplet radius band, matching {@code SprayModel.mistRadiusM}. */
	public static final float MIN_MIST_RADIUS_M = 2e-5f;
	public static final float MAX_MIST_RADIUS_M = 6e-5f;

	/**
	 * Mist has to read as a hanging cloud, not as dots that blink out. Anything below
	 * this and the spray loses its volume between two frames.
	 */
	public static final int MIST_LIFETIME_FLOOR = 40;

	/**
	 * Peak opacity of a mist puff at the moment it is thrown. Mist is a
	 * translucent cloud, not a red disc: several puffs overlap to build the
	 * density, so each one stays well under opaque on its own.
	 */
	public static final float MIST_PEAK_ALPHA = 0.5f;

	/** Evaporation rate: {@code alpha = MIST_PEAK_ALPHA * exp(-MIST_FADE * age/lifetime)}. */
	public static final float MIST_FADE = 1.2f;

	/**
	 * Speed in blocks/tick below which a droplet keeps its round silhouette. A drop
	 * crawling out of a wound is a bead; only flight draws it out.
	 */
	public static final float STRETCH_MIN_SPEED = 0.05f;

	/** Speed in blocks/tick at which a droplet is drawn at maximum elongation. */
	public static final float STRETCH_MAX_SPEED = 0.6f;

	/** Cap on the elongation: a streak is never more than three times as long as it is wide. */
	public static final float STRETCH_MAX = 3.0f;

	/** Wet blood base (165, 10, 10) normalised to 0..1: fresh arterial red, close to the walls' wet blood. */
	private static final float BASE_R = 165f / 255f;
	private static final float BASE_G = 10f / 255f;
	private static final float BASE_B = 10f / 255f;

	/** Small droplets are thin and translucent; big ones are optically thick. */
	private static final float MIN_ALPHA = 0.55f;

	/** How much a droplet darkens as it grows: shade runs 1.0 (fleck) .. 0.58 (slab). */
	private static final float SHADE_SWING = 0.42f;

	private ParticleLook() {
	}

	/**
	 * The ARGB a droplet of this physical radius should be tinted with. Bigger droplets
	 * absorb more light along the view ray, so they read darker and more opaque;
	 * smaller ones scatter and read paler and translucent. The base colour comes from
	 * {@link BloodAppearance} so a droplet's hue belongs to the same family as the stain
	 * it will leave behind, jittered deterministically per radius.
	 */
	public static int look(float radiusM) {
		float t = clamp01((radiusM - MIN_DROP_RADIUS_M) / (MAX_DROP_RADIUS_M - MIN_DROP_RADIUS_M));
		float shade = 1.0f - SHADE_SWING * t;
		int tint = BloodAppearance.tint(mix(Float.floatToRawIntBits(radiusM)), 0.0f);
		int r = clampChannel(Math.round(ARGB.red(tint) * BASE_R * shade));
		int g = clampChannel(Math.round(ARGB.green(tint) * BASE_G * shade));
		int b = clampChannel(Math.round(ARGB.blue(tint) * BASE_B * shade));
		int a = clampChannel(Math.round(255f * (MIN_ALPHA + (1.0f - MIN_ALPHA) * t)));
		return ARGB.color(a, r, g, b);
	}

	/**
	 * Quad edge length in blocks for a mist droplet of this radius. Scaled so the mist
	 * reads as overlapping soft volume rather than as separate specks — a puff is
	 * deliberately larger than a droplet, because a cloud is measured in the volume it
	 * covers, not the size of the droplets that make it up.
	 */
	public static float quadFor(float radiusM) {
		float t = clamp01((radiusM - MIN_MIST_RADIUS_M) / (MAX_MIST_RADIUS_M - MIN_MIST_RADIUS_M));
		// A puff starts small and is grown by dispersion each tick
		// (BloodMistParticle), so its *birth* size stays well under a block:
		// a hit throws a dozen overlapping puffs, and a half-block quad each
		// turns the whole frame into a red screen wash.
		return 0.10f + 0.10f * t;
	}

	/**
	 * Opacity of a mist puff at {@code lifeFraction} of the way through its life
	 * (0 at birth, 1 at the tick it is removed). Still legible at mid-life so the cloud
	 * lingers, but effectively gone before the lifetime cap.
	 */
	public static float alphaAt(float lifeFraction) {
		float f = clamp01(lifeFraction);
		return MIST_PEAK_ALPHA * (float) Math.exp(-MIST_FADE * f);
	}

	/**
	 * How far to draw out a droplet along its own velocity, as a multiple of its round
	 * width. Motion blur in a single frame: a droplet moving a fraction of a block per
	 * tick crosses its own width while the frame is exposed, so the eye reads a streak,
	 * not a dot. Flat below {@link #STRETCH_MIN_SPEED} (a bead is a bead) and capped at
	 * {@link #STRETCH_MAX} so nothing degenerates into a line.
	 */
	public static float stretch(float speed) {
		float t = clamp01((speed - STRETCH_MIN_SPEED) / (STRETCH_MAX_SPEED - STRETCH_MIN_SPEED));
		return 1.0f + (STRETCH_MAX - 1.0f) * t;
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	private static int clampChannel(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	/** SplitMix64-style avalanche so neighbouring radii do not share a jitter. */
	private static long mix(long h) {
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}
}
