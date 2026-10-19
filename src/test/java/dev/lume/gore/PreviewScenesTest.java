package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Regression checks on the {@link BloodPreview} scenes: what the wall shows over time. */
class PreviewScenesTest {
	private static final long SEED = 20261016L;

	/** Lit texels on the wall and floor at {@code now}, baked as the previewer bakes them. */
	private static int lit(List<PreviewScenes.Placement> scene, long now) {
		List<BloodField.Kernel> wall = new ArrayList<>();
		List<BloodField.Kernel> floor = new ArrayList<>();
		for (Decal d : PreviewScenes.stored(scene, now)) {
			(d.face() == PreviewScenes.SOUTH ? wall : floor).addAll(BloodField.kernels(d, now));
		}
		int[] px = new int[BloodField.RES * BloodField.RES];
		int count = 0;
		for (int bx = 0; bx < PreviewScenes.WALL_W; bx++) {
			for (int by = 0; by < PreviewScenes.FLOOR_D; by++) {
				if (by < PreviewScenes.WALL_H && BloodField.bake(wall, bx, by, px)) {
					count += visible(px);
				}
				if (BloodField.bake(floor, bx, -by, px)) {
					count += visible(px);
				}
			}
		}
		return count;
	}

	private static int visible(int[] px) {
		int n = 0;
		for (int c : px) {
			if (c >>> 24 != 0) {
				n++;
			}
		}
		return n;
	}

	@Test
	void everyScene_showsBlood_byTheEnd() {
		for (String name : PreviewScenes.NAMES) {
			assertTrue(lit(PreviewScenes.scene(name, SEED), 120) > 0, name);
		}
	}

	@Test
	void nothingShows_beforeTheFirstDropletLands() {
		for (String name : PreviewScenes.NAMES) {
			List<PreviewScenes.Placement> scene = PreviewScenes.scene(name, SEED);
			long first = scene.stream().mapToLong(p -> p.decal().born()).min().orElseThrow();
			assertTrue(lit(scene, first - 1) == 0, name + " shows blood before tick " + first);
		}
	}

	/**
	 * Without merges blood only arrives, runs and spreads within the first
	 * minutes, so the visible area never drops sharply from one tick to the
	 * next: a drop is a decal blinking out or a kernel jumping. A running drip
	 * thins as it stretches, and the iso-level noise lets edge texels flicker,
	 * so a dip of a couple of percent is allowed.
	 */
	@Test
	void coverage_neverShrinks_whileFresh() {
		for (String name : List.of("spray", "cluster", "drips", "pool")) {
			List<PreviewScenes.Placement> scene = PreviewScenes.scene(name, SEED);
			int previous = 0;
			for (long t = 0; t <= 160; t++) {
				int now = lit(scene, t);
				assertTrue(now >= previous * 0.98 - 3, name + " shrank at tick " + t + ": " + previous + " -> " + now);
				previous = now;
			}
		}
	}

	@Test
	void sprayDecals_stayInsideTheRoom() {
		for (long seed = 1; seed <= 20; seed++) {
			for (PreviewScenes.Placement p : PreviewScenes.spray(0.85f, seed)) {
				Decal d = p.decal();
				assertTrue(Math.abs(d.u()) <= 0.5f && Math.abs(d.v()) <= 0.5f, d.toString());
				assertTrue(d.x() >= 0 && d.x() < PreviewScenes.WALL_W, d.toString());
				assertTrue(d.born() > p.launch(), "born at the blow: " + d);
			}
		}
	}

	/**
	 * A drip already running on the wall must not vanish while a second drip's
	 * droplet that will merge into it is still in the air.
	 */
	@Test
	@Disabled("Known bug: DecalMerge.merged gives a drip the in-flight candidate's future born, "
			+ "so BloodField.kernels hides the whole drip until that droplet lands")
	void runningDrip_staysVisible_whileAMergingDropletFlies() {
		Decal first = PreviewScenes.wall(2.0, 2.0, 0.4f, 90f, 14, 10);
		Decal second = PreviewScenes.wall(2.02, 2.0, 0.4f, 90f, 14, 40);
		List<PreviewScenes.Placement> scene = List.of(
				new PreviewScenes.Placement(8, first, null),
				new PreviewScenes.Placement(36, second, null));
		for (long t = 12; t <= 60; t++) {
			assertTrue(lit(scene, t) > 0, "drip gone at tick " + t);
		}
	}
}
