package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** {@link BloodField}: blood on one face is one liquid surface, not stacked stickers. */
class BloodFieldTest {
	private static final int RES = BloodField.RES;
	private static final int SOUTH = Direction.SOUTH.get3DDataValue();

	private static boolean[] coverage(List<Decal> decals, long now) {
		List<BloodField.Kernel> kernels = new ArrayList<>();
		for (Decal d : decals) {
			kernels.addAll(BloodField.kernels(d, now));
		}
		int[] px = new int[RES * RES];
		BloodField.bake(kernels, 0, 0, px);
		boolean[] on = new boolean[px.length];
		for (int i = 0; i < px.length; i++) {
			on[i] = (px[i] >>> 24) != 0;
		}
		return on;
	}

	@Test
	void neighbouringBlobs_fuseWithALiquidNeck() {
		// Two blobs whose visible discs (radius 0.18) leave a gap at the
		// midpoint: alone it is bare, together the summed field bridges it.
		int mid = (RES / 2) * RES + RES / 2;
		BloodField.Kernel left = blob(-0.22);
		BloodField.Kernel right = blob(0.22);
		assertFalse(bake(List.of(left))[mid]);
		assertFalse(bake(List.of(right))[mid]);
		assertTrue(bake(List.of(left, right))[mid]);
	}

	private static BloodField.Kernel blob(double u) {
		double support = 0.18 / Math.sqrt(1.0 - Math.cbrt(0.35));
		return new BloodField.Kernel(u, 0, u, 0, support, 1f, 0xFF960A0C, 1f, 0f);
	}

	private static boolean[] bake(List<BloodField.Kernel> kernels) {
		int[] px = new int[RES * RES];
		BloodField.bake(kernels, 0, 0, px);
		boolean[] on = new boolean[px.length];
		for (int i = 0; i < px.length; i++) {
			on[i] = (px[i] >>> 24) != 0;
		}
		return on;
	}

	@Test
	void dripTop_isRounded_notACutEdge() {
		// A settled drip hanging from the face centre (rot 90 = tail up, so it runs down).
		Decal drip = new Decal(0, 0, 0, SOUTH, 0f, 0.3f, 0.7f, 90f, 14, 0L);
		boolean[] on = coverage(List.of(drip), 0);
		int top = -1;
		for (int j = 0; j < RES && top < 0; j++) {
			for (int i = 0; i < RES; i++) {
				if (on[j * RES + i]) {
					top = j;
					break;
				}
			}
		}
		assertTrue(top >= 0, "drip visible");
		// The topmost row of a rounded cap is narrower than the run a few rows below.
		assertTrue(width(on, top) < width(on, top + 3), "cap narrows to the top");
	}

	@Test
	void fullyFadedStain_bakesNothing() {
		Decal old = new Decal(0, 0, 0, SOUTH, 0f, 0f, 0.4f, 0f, 0, 1L);
		long now = 1L + 10 * (long) (Drying.FADE_TICKS * Drying.SCALE);
		assertEquals(0, BloodField.kernels(old, now).size());
	}

	@Test
	void withinOwnFace_onlyForStainsThatDoNotSpill() {
		// A small round stain in the middle of its face: safe to drop with its block.
		assertTrue(BloodField.withinOwnFace(new Decal(0, 0, 0, SOUTH, 0f, 0f, 0.25f, 0f, 0, 1L)));
		// The same stain at the face's edge reaches the next block: it must stay.
		assertFalse(BloodField.withinOwnFace(new Decal(0, 0, 0, SOUTH, 0.45f, 0f, 0.25f, 0f, 0, 1L)));
		// A long drip runs down into the block below.
		assertFalse(BloodField.withinOwnFace(new Decal(0, 0, 0, SOUTH, 0f, -0.2f, 0.7f, 90f, 14, 1L)));
	}

	private static int width(boolean[] on, int row) {
		int n = 0;
		for (int i = 0; i < RES; i++) {
			if (on[row * RES + i]) {
				n++;
			}
		}
		return n;
	}
}
