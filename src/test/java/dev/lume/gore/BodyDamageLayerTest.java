package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import dev.lume.gore.client.BodyDamageLayer;

/**
 * {@link BodyDamageLayer#coatIntensity}: the coat's opacity is weighted by
 * per-stain wetness, so old dry blood stops contributing to the wet red wash.
 */
class BodyDamageLayerTest {

	private static GoreStain fresh(long now) {
		return new GoreStain(0, 0, 0, 0, 0, 0, 0, now, 0.5f, 0f, 16);
	}

	private static GoreStain dry(long now) {
		// 30000 ticks old at scale 1.0: past WET_TICKS(1200) + full span
		return new GoreStain(0, 0, 0, 0, 0, 0, 0, now - 30000, 0.5f, 0f, 16);
	}

	@Test
	void noStainsIsNoCoat() {
		assertEquals(0f, BodyDamageLayer.coatIntensity(List.of(), 1000), 1e-6);
	}

	@Test
	void freshStainsBuildTheCoat() {
		long now = 1000;
		// Six fresh stains: wetness sum = 6, intensity = 6 / 12 = 0.5
		assertEquals(0.5f,
				BodyDamageLayer.coatIntensity(List.of(fresh(now), fresh(now), fresh(now),
						fresh(now), fresh(now), fresh(now)), now), 1e-6);
	}

	@Test
	void enoughFreshStainsSaturate() {
		long now = 1000;
		// Twelve fresh stains: wetness sum = 12, capped at 12 → 1.0
		var stains = java.util.stream.Stream.generate(() -> fresh(now)).limit(12)
				.toList();
		assertEquals(1.0f, BodyDamageLayer.coatIntensity(stains, now), 1e-6);
	}

	@Test
	void dryStainsDoNotContribute() {
		long now = 1000;
		assertEquals(0f, BodyDamageLayer.coatIntensity(List.of(dry(now)), now), 1e-6);
		assertEquals(0f, BodyDamageLayer.coatIntensity(List.of(dry(now), dry(now)), now),
				1e-6);
	}

	@Test
	void mixedStainsWeightByWetness() {
		long now = 1000;
		// One fresh (wetness 1) + one dry (wetness 0) → sum 1 / 12 ≈ 0.0833
		assertEquals(1.0f / 12.0f,
				BodyDamageLayer.coatIntensity(List.of(fresh(now), dry(now)), now), 1e-6);
		// Six fresh (wetness 1 each) + six dry (wetness 0 each) → sum 6 / 12 = 0.5
		List<GoreStain> mixed = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			mixed.add(fresh(now));
			mixed.add(dry(now));
		}
		assertEquals(0.5f, BodyDamageLayer.coatIntensity(mixed, now), 1e-6);
	}

	@Test
	void legacyBornZeroCountsAsFullyDry() {
		long now = 1000;
		assertEquals(0f,
				BodyDamageLayer.coatIntensity(
						List.of(new GoreStain(0, 0, 0, 0, 0, 0, 0, 0L, 0.5f, 0f, 16)),
						now), 1e-6);
	}

	@Test
	void recentlyBornStainIsStillWet() {
		long now = 1000;
		// Born 100 ticks ago at scale 1.0: still within WET_TICKS(1200) → dryness 0
		assertEquals(1.0f / 12.0f,
				BodyDamageLayer.coatIntensity(List.of(new GoreStain(0, 0, 0, 0, 0, 0, 0,
						now - 100, 0.5f, 0f, 16)), now), 1e-6);
	}
}
