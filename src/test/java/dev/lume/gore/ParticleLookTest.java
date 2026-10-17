package dev.lume.gore;

import net.minecraft.util.ARGB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParticleLook} — the pure size/colour/lifetime policy that the
 * blood particle classes apply. These live in the main source set on purpose: the
 * particles themselves are client-only (Loom split source sets put {@code src/client}
 * on no unit-test classpath), so every decision that a test must pin is factored out
 * here rather than buried in a tick() method.
 */
class ParticleLookTest {

	@Test
	void largerDropletsAreDarkerAndMoreOpaque() {
		int big = ParticleLook.look(2.0e-3f);
		int small = ParticleLook.look(0.2e-3f);
		assertTrue(ARGB.red(big) <= ARGB.red(small),
				"a bigger droplet absorbs more light and must read darker: red " + ARGB.red(big) + " vs " + ARGB.red(small));
		assertTrue(ARGB.alpha(big) >= ARGB.alpha(small),
				"a bigger droplet is optically thicker and must read more opaque: alpha " + ARGB.alpha(big) + " vs " + ARGB.alpha(small));
	}

	@Test
	void mistQuadGrowsWithRadius() {
		assertTrue(ParticleLook.quadFor(6e-5f) > ParticleLook.quadFor(2e-5f),
				"larger mist droplets must occupy a larger quad: " + ParticleLook.quadFor(6e-5f) + " vs " + ParticleLook.quadFor(2e-5f));
	}

	@Test
	void mistLingersAsCloud() {
		assertTrue(ParticleLook.MIST_LIFETIME_FLOOR >= 40,
				"mist must hang as a cloud, not blink out: floor " + ParticleLook.MIST_LIFETIME_FLOOR);
		assertTrue(ParticleLook.alphaAt(0.5f) >= 0.25f,
				"mist must still be visible at mid-life: alpha " + ParticleLook.alphaAt(0.5f));
	}
}
