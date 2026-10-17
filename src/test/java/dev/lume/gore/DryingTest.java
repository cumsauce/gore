package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.ARGB;
import org.junit.jupiter.api.Test;

class DryingTest {

	@Test
	void dryness_isZeroWhileWet_andOneWhenDry() {
		assertEquals(0f, Drying.dryness(100, 100 + 1199, 1.0), 1e-6);
		assertEquals(0.5f, Drying.dryness(100, 100 + 3600, 1.0), 1e-3);
		assertEquals(1f, Drying.dryness(100, 100 + 6000, 1.0), 1e-6);
	}

	@Test
	void dryness_isZeroForNegativeAge() {
		assertEquals(0f, Drying.dryness(500, 400, 1.0), 0f);
	}

	@Test
	void dryness_isOneForLegacyBornZero() {
		assertEquals(1f, Drying.dryness(0, 5, 1.0), 0f);
	}

	@Test
	void dryness_scaleShortensTheClock() {
		assertEquals(1f, Drying.dryness(100, 100 + 120, 0.02), 1e-6);
		assertEquals(0f, Drying.dryness(100, 100 + 23, 0.02), 1e-6);
	}

	@Test
	void tint_isWhiteWhenWet_andDarkBrownMultiplierWhenDry() {
		assertEquals(0xFFFFFFFF, Drying.tint(0f));
		int dry = Drying.tint(1f);
		assertEquals(133, ARGB.red(dry));
		assertEquals(217, ARGB.green(dry));
		assertEquals(153, ARGB.blue(dry));
		assertEquals(255, ARGB.alpha(dry));
	}
}
