package dev.lume.gore;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticsTest {
	@Test
	void millimetreDrop_reachesTerminalSpeedNearPointThreeFour() {
		Vec3 v = Vec3.ZERO;
		for (int i = 0; i < 200; i++) v = Ballistics.step(v, 1e-3);
		assertTrue(-v.y > 0.30 && -v.y < 0.40, "terminal " + v.y);
	}

	@Test
	void millimetreDrop_deceleratesHorizontallyButKeepsFlying() {
		Vec3 v = new Vec3(0.6, 0, 0);
		for (int i = 0; i < 10; i++) v = Ballistics.step(v, 1e-3);
		assertTrue(v.x > 0.22 && v.x < 0.30, "vx after 10 ticks " + v.x);
	}

	@Test
	void mist_stopsWithinTwoTicksAndHangs() {
		Vec3 v = new Vec3(0.4, 0, 0);
		double travelled = 0;
		for (int i = 0; i < 20; i++) {
			v = Ballistics.step(v, 4e-5);
			travelled += v.x;
		}
		assertTrue(travelled < 0.5, "mist travelled " + travelled);
		assertTrue(v.length() < 0.02, "mist speed after 20 ticks " + v.length());
	}

	@Test
	void step_isStableForAbsurdSpeeds() {
		Vec3 v = Ballistics.step(new Vec3(1000, 1000, 1000), 1.5e-4);
		assertTrue(Double.isFinite(v.length()) && v.length() < 1000, "len " + v.length());
	}
}
