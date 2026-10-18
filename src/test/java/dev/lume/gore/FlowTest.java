package dev.lume.gore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTest {

	@Test
	void length_matchesIntegratedVelocityCurve() {
		float[][] table = {{2,0.0088f},{5,0.0453f},{10,0.134f},{20,0.324f},{30,0.485f},{40,0.612f},{60,0.789f},{80,0.896f},{120,1f}};
		for (float[] row : table) assertEquals(row[1], Flow.length((long) row[0]), 0.01, "t=" + row[0]);
	}

	@Test
	void length_isZeroForNegativeAge() {
		assertEquals(0f, Flow.length(-5), 0f);
		assertEquals(0f, Flow.length(0), 0f);
	}

	@Test
	void length_isMonotonicAndAcceleratesThenSlows() {
		float prev = 0; float maxStep = 0; int argmax = 0;
		for (int t = 1; t <= 120; t++) { float f = Flow.length(t); assertTrue(f >= prev); if (f - prev > maxStep) { maxStep = f - prev; argmax = t; } prev = f; }
		assertTrue(argmax >= 8 && argmax <= 16, "peak velocity tick " + argmax);
	}

	@Test
	void beadScale_conservesMass() {
		assertEquals(1f, Flow.beadScale(0f), 1e-6);
		assertEquals(0.669f, Flow.beadScale(1f), 0.002);
	}

	@Test
	void spread_startsAtZero_andSettlesAtOne() {
		assertEquals(0f, Flow.spread(0), 0f);
		assertEquals(0f, Flow.spread(-3), 0f);
		assertEquals(1f, Flow.spread(Flow.SPREAD_TICKS), 0f);
		assertEquals(1f, Flow.spread(10_000), 0f);
	}

	@Test
	void spread_isFastEarlyAndSlowLate() {
		// A pool floods outward immediately and then creeps: most of its growth
		// is in the first half of the animation.
		assertTrue(Flow.spread(5) > 0.4f, "five ticks in: " + Flow.spread(5));
		assertTrue(Flow.spread(20) > 0.8f, "halfway: " + Flow.spread(20));
		float early = Flow.spread(5);
		float late = Flow.spread(40) - Flow.spread(35);
		assertTrue(early > 5.0f * late, "early growth " + early + " vs late " + late);
	}

	@Test
	void poolSize_growsFromNascentToFull() {
		assertEquals(0.5f * Flow.POOL_NASCENT, Flow.poolSize(0.5f, 0), 1e-6);
		assertEquals(0.5f, Flow.poolSize(0.5f, Flow.SPREAD_TICKS), 1e-6);
		float previous = 0f;
		for (int t = 0; t <= Flow.SPREAD_TICKS; t++) {
			float size = Flow.poolSize(0.5f, t);
			assertTrue(size >= previous, "pool must never shrink: t=" + t);
			previous = size;
		}
	}
}
