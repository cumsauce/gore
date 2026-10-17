package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests for the list-level merge in {@link DecalMerge}:
 * {@code applyTo} coalesces a new decal into a neighbour so the list never
 * grows for a merge. (No Minecraft bootstrap: the test mutates a plain
 * {@code ArrayList}, not {@code DecalStore}, whose static init touches
 * registries.)
 */
class DecalStoreTest {
	private static Decal drip(float u, float v, float size, long born) {
		return new Decal(1, 2, 3, Direction.NORTH.get3DDataValue(), u, v, size, 90f, 14, born);
	}

	@Test
	void addOrMerge_doesNotGrowCountWhenMerging() {
		List<Decal> decals = new ArrayList<>();
		DecalMerge.applyTo(decals, drip(0f, 0f, 0.4f, 1));
		Decal merged = DecalMerge.applyTo(decals, drip(0.05f, 0.1f, 0.3f, 2));

		assertEquals(1, decals.size());
		assertEquals(0.5f, decals.get(0).size(), 1e-6);
		assertEquals(merged, decals.get(0));
	}
}
