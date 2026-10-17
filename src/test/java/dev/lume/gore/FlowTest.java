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
}
