package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests for {@link DecalMerge}: neighbouring runs coalesce into one
 * heavier decal, overlapping stains get denser, and pools never merge.
 */
class DecalMergeTest {
	private static Decal drip(float u, float v, float size, long born) {
		return new Decal(1, 2, 3, Direction.NORTH.get3DDataValue(), u, v, size, 90f, 14, born);
	}

	private static Decal stain(float u, float v, float size, int variant) {
		return new Decal(1, 2, 3, Direction.NORTH.get3DDataValue(), u, v, size, 0f, variant, 50L);
	}

	@Test
	void drips_withinTolerance_merge() {
		Decal a = drip(0.10f, 0.0f, 0.4f, 100);
		Decal b = drip(0.15f, 0.2f, 0.3f, 140);
		Decal m = DecalMerge.merged(DecalMerge.findTarget(List.of(a), b).orElseThrow(), b);
		assertEquals(0.10f, m.u(), 1e-6);
		assertEquals(0.5f, m.size(), 1e-6);
		assertEquals(140L, m.born());
		assertEquals(14, m.variant());
	}

	@Test
	void drips_tooFarApart_doNotMerge() {
		assertTrue(DecalMerge.findTarget(List.of(drip(0f, 0f, 0.4f, 1)), drip(0.2f, 0f, 0.4f, 2)).isEmpty());
	}

	@Test
	void stains_overlapping_merge_withDenserVariant_andCappedSize() {
		Decal a = stain(0f, 0f, 0.48f, 7 /* elong 2, dens 1 */);
		Decal b = stain(0.03f, 0.03f, 0.3f, 0);
		Decal m = DecalMerge.merged(DecalMerge.findTarget(List.of(a), b).orElseThrow(), b);
		assertEquals(8, m.variant());
		assertEquals(0.5f, m.size(), 1e-6);
	}

	@Test
	void pools_neverMerge() {
		Decal p = new Decal(1, 2, 3, Direction.UP.get3DDataValue(), 0, 0, 0.6f, 0, 12, 5L);
		assertTrue(DecalMerge.findTarget(List.of(p), p).isEmpty());
	}

	@Test
	void differentFaces_doNotMerge() {
		Decal s = new Decal(1, 2, 3, Direction.SOUTH.get3DDataValue(), 0, 0, 0.3f, 0, 0, 5L);
		assertTrue(DecalMerge.findTarget(List.of(stain(0, 0, 0.3f, 0)), s).isEmpty());
	}
}
