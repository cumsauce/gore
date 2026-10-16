package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Sprays leave the wound as a directed cone with log-normal droplet sizes —
 * the physics the impact particles and Task 4's ballistics both rely on.
 */
class SprayModelTest {
	private final RandomSource r = RandomSource.create(20261016L);

	@Test
	void dropVelocities_lieInsideCone_andHaveImpactSpeed() {
		Vec3 dir = new Vec3(1, 0, 0);
		List<Vec3> v = SprayModel.dropVelocities(dir, 0.6f, r);
		assertEquals(17, v.size());
		double meanSpeed = 0;
		for (Vec3 each : v) {
			double angle = Math.toDegrees(Math.acos(each.normalize().dot(dir)));
			assertTrue(angle <= 20.0, "angle " + angle);
			meanSpeed += each.length();
		}
		meanSpeed /= v.size();
		assertTrue(meanSpeed > 0.45 && meanSpeed < 0.60, "mean speed " + meanSpeed);
	}

	@Test
	void counts_atMinimumSeverity() {
		assertEquals(7, SprayModel.dropCount(0.08f));
		assertEquals(0, SprayModel.mistCount(0.08f));
		assertEquals(10, SprayModel.mistCount(0.6f));
	}

	@Test
	void dropRadius_isLogNormalWithFewLargeDrops() {
		double[] s = new double[2000];
		int large = 0;
		for (int i = 0; i < s.length; i++) {
			s[i] = SprayModel.dropRadiusM(r);
			if (s[i] > 2.0e-3) large++;
			assertTrue(s[i] >= 0.15e-3 && s[i] <= 2.5e-3);
		}
		Arrays.sort(s);
		assertTrue(s[1000] > 0.65e-3 && s[1000] < 0.95e-3, "median " + s[1000]);
		assertTrue(large >= 40 && large <= 240, "large " + large);   // 2 %–12 %
	}

	@Test
	void mistRadius_isWithinBounds() {
		for (int i = 0; i < 500; i++) {
			double m = SprayModel.mistRadiusM(r);
			assertTrue(m >= 2e-5 && m <= 6e-5);
		}
	}
}
