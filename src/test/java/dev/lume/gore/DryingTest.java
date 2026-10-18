package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	void fade_holdsUntilDry_thenFallsToNothing() {
		long born = 1000;
		long dryAt = born + Drying.DRY_TICKS;
		long goneAt = dryAt + Drying.FADE_TICKS;

		assertEquals(1f, Drying.fade(born, born + 1, 1.0), 1e-6, "fresh blood is fully there");
		assertEquals(1f, Drying.fade(born, dryAt, 1.0), 1e-6, "it only starts to fade once dry");
		assertEquals(0.5f, Drying.fade(born, dryAt + Drying.FADE_TICKS / 2, 1.0), 0.01,
				"the fade is linear");
		assertEquals(0f, Drying.fade(born, goneAt, 1.0), 1e-6);
		assertEquals(0f, Drying.fade(born, goneAt + 10_000, 1.0), 1e-6);
	}

	@Test
	void fade_neverRemovesLegacyStains() {
		assertEquals(1f, Drying.fade(0L, 999_999L, 1.0), 0f);
	}

	@Test
	void rain_washesBeforeBloodHasTimeToDry() {
		assertEquals(1f, Drying.rainLeft(0, 1.0), 0f);
		assertEquals(0.5f, Drying.rainLeft(Drying.RAIN_WASH_TICKS / 2, 1.0), 0.01);
		assertEquals(0f, Drying.rainLeft(Drying.RAIN_WASH_TICKS, 1.0), 0f);
		assertTrue(Drying.RAIN_WASH_TICKS < Drying.DRY_TICKS);
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
