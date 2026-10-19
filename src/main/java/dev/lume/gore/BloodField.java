package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;

/**
 * Wall blood as one liquid surface instead of a pile of stickers.
 *
 * <p>Every decal becomes a handful of metaball kernels (capsules) in its face's
 * <em>plane</em> coordinates — {@code (block·axis1 + u, block·axis2 + v)}, so the
 * field is continuous across neighbouring blocks of one wall. A block face is
 * then baked into a small texture by summing the field per texel and
 * thresholding it: two blots that touch fuse with a liquid neck between them,
 * a drip's top is a rounded meniscus that blends into the splat feeding it,
 * and a pool floods outward and swallows the spatter it reaches. There is one
 * quad per face, so nothing can z-fight with anything.
 *
 * <p>Pure functions of the decals and the game time; no randomness, so a face
 * re-baked next tick is identical where nothing moved.
 */
public final class BloodField {
	/** Texels per block face edge. */
	public static final int RES = 32;
	/** Iso-level of the surface. */
	static final float THRESHOLD = 0.35f;
	/** Visible radius / kernel support radius for one isolated circle at {@link #THRESHOLD}. */
	private static final float ISO = (float) Math.sqrt(1.0 - Math.cbrt(THRESHOLD));
	/** Fresh blood, before the per-stain jitter and the drying ramp multiply it. */
	private static final int WET_R = 190;
	private static final int WET_G = 8;
	private static final int WET_B = 10;
	/** Ticks over which a fresh splat wets out to its full footprint. */
	private static final double WET_SPREAD_TICKS = 30.0;

	private BloodField() {
	}

	/**
	 * One metaball: a capsule from {@code (au,av)} to {@code (bu,bv)} whose
	 * field is {@code amp·(1 − (d/support)²)³} inside the support.
	 */
	public record Kernel(double au, double av, double bu, double bv, double support, float amp,
			int rgb, float fade, float dry) {
		public double minU() {
			return Math.min(au, bu) - support;
		}

		public double maxU() {
			return Math.max(au, bu) + support;
		}

		public double minV() {
			return Math.min(av, bv) - support;
		}

		public double maxV() {
			return Math.max(av, bv) + support;
		}
	}

	/** Plane coordinate of a block along {@code axis} (a signed unit axis). */
	public static int planeCoord(int x, int y, int z, net.minecraft.world.phys.Vec3 axis) {
		return (int) Math.round(x * axis.x + y * axis.y + z * axis.z);
	}

	/** The kernels for one decal at game time {@code now}. Empty once it has faded out. */
	public static List<Kernel> kernels(Decal d, long now) {
		List<Kernel> out = new ArrayList<>(8);
		Direction face = Direction.from3DDataValue(d.face());
		double cu = planeCoord(d.x(), d.y(), d.z(), Decal.axis1(face)) + d.u();
		double cv = planeCoord(d.x(), d.y(), d.z(), Decal.axis2(face)) + d.v();
		double rad = Math.toRadians(d.rot());
		double tu = Math.cos(rad);
		double tv = Math.sin(rad);

		if (d.born() != 0L && now < d.born()) {
			// Still in flight: the droplet has not reached the wall yet.
			return out;
		}

		long age = d.born() == 0L ? Long.MAX_VALUE / 4 : now - d.born();
		float fade = Drying.fade(d.born(), now, Drying.SCALE);

		if (fade <= 0.0f) {
			return out;
		}

		float dry = Drying.dryness(d.born(), now, Drying.SCALE);
		long seed = BloodAppearance.seedFor(d.x(), d.y(), d.z(), d.face(), d.u(), d.v(), d.variant(), d.born());
		int tint = BloodAppearance.tint(seed, dry);
		int rgb = ARGB.color(255,
				WET_R * ARGB.red(tint) / 255, WET_G * ARGB.green(tint) / 255, WET_B * ARGB.blue(tint) / 255);
		// A fading stain also thins: its kernels weaken, so it shrinks from the
		// edges and lets go of its neighbours rather than just turning see-through.
		float amp = 0.55f + 0.45f * fade;

		if (DecalMerge.isDrip(d)) {
			// tail points up along the wall; the run flows the other way.
			float f = dripStretch(d, now);
			double len = d.size() * f * 0.95;
			double width = d.size() * 0.075 * Flow.widthScale(f);
			double eu = cu - tu * len;
			double ev = cv - tv * len;
			// The meniscus where the run leaves the splat: a round head, so the
			// top of a drip is never the flat cut edge of a texture strip.
			out.add(circle(cu, cv, d.size() * 0.12, amp, rgb, fade, dry));
			if (len > 1.0e-4) {
				out.add(new Kernel(cu, cv, eu, ev, width / ISO, amp, rgb, fade, dry));
				out.add(circle(eu, ev, d.size() * 0.105 * Flow.beadScale(f), amp, rgb, fade, dry));
			}
			return out;
		}

		if (DecalMerge.isPool(d)) {
			double r = Flow.poolSize(d.size(), age) * 0.42;
			out.add(circle(cu, cv, r * 0.85, amp, rgb, fade, dry));
			// Lobes give the pool an uneven, liquid outline instead of a disc.
			for (int i = 0; i < 4; i++) {
				double a = rad + i * (Math.PI / 2.0) + unit(seed, 10 + i) * 0.9;
				double dist = r * (0.35 + 0.25 * unit(seed, 20 + i));
				out.add(circle(cu + Math.cos(a) * dist, cv + Math.sin(a) * dist,
						r * (0.45 + 0.25 * unit(seed, 30 + i)), amp, rgb, fade, dry));
			}
			return out;
		}

		// Impact stain: elongation 0..3 from the impact angle, density 0..2 from force.
		int elong = Math.min(3, d.variant() / 3);
		int density = d.variant() % 3;
		double wet = 0.85 + 0.15 * (1.0 - Math.exp(-age / WET_SPREAD_TICKS));
		double r0 = d.size() * 0.26 * (1.0 - 0.08 * elong) * wet;
		double half = r0 * 0.45 * elong;
		out.add(new Kernel(cu - tu * half, cv - tv * half, cu + tu * half, cv + tv * half,
				r0 / ISO, amp, rgb, fade, dry));

		if (elong > 0) {
			// The exclamation-mark tail thrown ahead of a glancing drop.
			double td = half + r0 * (1.0 + 0.25 * elong);
			out.add(circle(cu + tu * td, cv + tv * td, Math.max(0.02, r0 * 0.32), amp, rgb, fade, dry));
		}

		int satellites = 2 + 2 * density + elong;
		for (int i = 0; i < satellites; i++) {
			// Thrown mostly forward along the travel, a few to the sides.
			double a = rad + (unit(seed, 40 + i) - 0.5) * (Math.PI * (elong > 0 ? 0.9 : 2.0));
			double dist = r0 * (1.25 + 1.1 * unit(seed, 60 + i));
			out.add(circle(cu + Math.cos(a) * dist, cv + Math.sin(a) * dist,
					Math.max(0.018, r0 * (0.08 + 0.14 * unit(seed, 80 + i))), amp, rgb, fade, dry));
		}

		return out;
	}

	private static Kernel circle(double u, double v, double visibleRadius, float amp, int rgb, float fade, float dry) {
		return new Kernel(u, v, u, v, visibleRadius / ISO, amp, rgb, fade, dry);
	}

	/**
	 * Bakes the face whose block sits at plane coordinates {@code (pu, pv)} into
	 * {@code out} ({@link #RES}² ARGB, row-major). Texel {@code (i, j)} is at
	 * {@code u = pu + (i + .5)/RES − .5} and {@code v = pv + .5 − (j + .5)/RES}
	 * (row 0 is the face's +axis2 edge). Returns true if any texel is visible.
	 */
	public static boolean bake(List<Kernel> kernels, int pu, int pv, int[] out) {
		boolean any = false;
		// Only kernels whose support reaches this face can touch a texel: cull
		// the rest once per face instead of once per texel.
		Kernel[] near = new Kernel[kernels.size()];
		int count = 0;
		for (Kernel k : kernels) {
			if (k.maxU() >= pu - 0.5 && k.minU() <= pu + 0.5 && k.maxV() >= pv - 0.5 && k.minV() <= pv + 0.5) {
				near[count++] = k;
			}
		}

		if (count == 0) {
			java.util.Arrays.fill(out, 0, RES * RES, 0);
			return false;
		}

		for (int j = 0; j < RES; j++) {
			double v = pv + 0.5 - (j + 0.5) / RES;

			for (int i = 0; i < RES; i++) {
				double u = pu + (i + 0.5) / RES - 0.5;
				float field = 0.0f;
				float r = 0.0f;
				float g = 0.0f;
				float b = 0.0f;
				float fade = 0.0f;
				float dry = 0.0f;

				for (int n = 0; n < count; n++) {
					Kernel k = near[n];
					if (u < k.minU() || u > k.maxU() || v < k.minV() || v > k.maxV()) {
						continue;
					}

					double d2 = segmentDist2(u, v, k) / (k.support() * k.support());

					if (d2 >= 1.0) {
						continue;
					}

					double s = 1.0 - d2;
					float w = (float) (s * s * s) * k.amp();
					field += w;
					r += w * ARGB.red(k.rgb());
					g += w * ARGB.green(k.rgb());
					b += w * ARGB.blue(k.rgb());
					fade += w * k.fade();
					dry += w * k.dry();
				}

				// No field can beat the lowest wobbled threshold: skip the noise.
				if (field <= THRESHOLD * 0.8f) {
					out[j * RES + i] = 0;
					continue;
				}

				// A wobble in the iso-level gives the edge the ragged look of
				// blood soaking into a rough surface instead of vector-clean curves.
				float threshold = THRESHOLD * (0.8f + 0.4f * noise(u, v));

				if (field <= threshold) {
					out[j * RES + i] = 0;
					continue;
				}

				any = true;
				r /= field;
				g /= field;
				b /= field;
				fade /= field;
				dry /= field;
				// Thicker blood is darker and more opaque; the thin film at the
				// edge lets the surface show through. Dry blood gets a darker
				// rim where the film contracted as it dried.
				float thick = Math.min(1.0f, (field - threshold) / 1.2f);
				float edge = 1.0f - Math.min(1.0f, (field - threshold) / 0.15f);
				float shade = (1.0f - 0.45f * thick) * (1.0f - 0.35f * dry * edge);
				float alpha = (0.78f + 0.22f * thick) * fade;
				// Wet, thick blood is glossy: a sheen where the film bulges (a
				// noise ridge inside the body, never at the thin edge) that dulls
				// away as it dries.
				float ridge = Math.max(0.0f, noise(u * 1.7 + 11.3, v * 1.7 - 4.1) - 0.62f) / 0.38f;
				float gloss = ridge * thick * (1.0f - dry) * (1.0f - edge);
				out[j * RES + i] = ARGB.color(Math.round(255 * alpha),
						Math.min(255, Math.round(r * shade + 90.0f * gloss)),
						Math.min(255, Math.round(g * shade + 40.0f * gloss)),
						Math.min(255, Math.round(b * shade + 40.0f * gloss)));
			}
		}

		return any;
	}

	private static double segmentDist2(double u, double v, Kernel k) {
		double du = k.bu() - k.au();
		double dv = k.bv() - k.av();
		double len2 = du * du + dv * dv;
		double t = len2 < 1.0e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((u - k.au()) * du + (v - k.av()) * dv) / len2));
		double eu = u - (k.au() + du * t);
		double ev = v - (k.av() + dv * t);
		return eu * eu + ev * ev;
	}

	/** Two-octave value noise in [0, 1], continuous across faces of one plane. */
	static float noise(double u, double v) {
		return 0.65f * value(u * 7.0, v * 7.0) + 0.35f * value(u * 19.0 + 3.1, v * 19.0 + 7.7);
	}

	private static float value(double x, double y) {
		int xi = (int) Math.floor(x);
		int yi = (int) Math.floor(y);
		float fx = (float) (x - xi);
		float fy = (float) (y - yi);
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		float a = hash(xi, yi);
		float b = hash(xi + 1, yi);
		float c = hash(xi, yi + 1);
		float d = hash(xi + 1, yi + 1);
		return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
	}

	private static float hash(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
	}

	/** Stable value in [0, 1) for one property of one stain. */
	private static float unit(long seed, int salt) {
		long z = seed + 0x9E3779B97F4A7C15L * (salt + 1);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return ((z >>> 40) & 0xFFFFFFL) / (float) (1 << 24);
	}

	/**
	 * How far a drip has stretched at {@code now}, 0..1 ({@link Flow#length}).
	 * A drip fed by a merged droplet restarts its run when that droplet lands,
	 * but never retracts: it keeps the length it had reached and the new run
	 * takes over once it is longer, so the merge shows no jump.
	 */
	public static float dripStretch(Decal d, long now) {
		if (d.born() == 0L) {
			return 1.0f;
		}
		float f = Flow.length(now - d.born());
		if (d.fed() != 0L && now >= d.fed()) {
			f = Math.max(Flow.length(d.fed() - d.born()), Flow.length(now - d.fed()));
		}
		return f;
	}

	/**
	 * True while the decal's shape is still moving (a drip running, a pool or
	 * splat spreading), so its faces need re-baking every tick rather than on
	 * the slow drying clock.
	 */
	public static boolean animating(Decal d, long now) {
		if (d.born() == 0L) {
			return false;
		}
		long age = now - d.born();
		if (age < 0) {
			return true; // in flight: bake every tick so it appears on arrival
		}
		if (DecalMerge.isDrip(d)) {
			return age < Flow.T_TICKS || (d.fed() != 0L && now - d.fed() < Flow.T_TICKS);
		}
		if (DecalMerge.isPool(d)) {
			return age < Flow.SPREAD_TICKS;
		}
		return age < WET_SPREAD_TICKS * 4;
	}

	/**
	 * True when every part of the decal, fully grown, stays on its own block
	 * face; false when it spills onto neighbouring blocks of the same wall.
	 * Used to decide whether a decal can be dropped when its block is broken:
	 * one that spills still paints the neighbours, so it has to stay.
	 */
	public static boolean withinOwnFace(Decal d) {
		Direction face = Direction.from3DDataValue(d.face());
		int pu = planeCoord(d.x(), d.y(), d.z(), Decal.axis1(face));
		int pv = planeCoord(d.x(), d.y(), d.z(), Decal.axis2(face));
		Decal settled = d.born() == 0L ? d
				: new Decal(d.x(), d.y(), d.z(), d.face(), d.u(), d.v(), d.size(), d.rot(), d.variant(), 1L);
		long grown = 1L + Flow.T_TICKS + 1;

		for (Kernel k : kernels(settled, grown)) {
			// The visible edge, not the kernel's whole support (which is ~1.8x
			// wider), with room for the iso-level noise and for neighbours'
			// fields summing a little further out.
			double reach = k.support() * ISO * 1.25;
			if (Math.min(k.au(), k.bu()) - reach < pu - 0.5 || Math.max(k.au(), k.bu()) + reach > pu + 0.5
					|| Math.min(k.av(), k.bv()) - reach < pv - 0.5 || Math.max(k.av(), k.bv()) + reach > pv + 0.5) {
				return false;
			}
		}

		return true;
	}
}
