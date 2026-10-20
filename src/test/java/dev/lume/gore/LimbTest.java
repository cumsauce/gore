package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Which part a hit point lands on, and when a part comes off. */
class LimbTest {
	// A zombie: 0.6 wide, 1.95 tall, eyes at 1.74, facing south (yaw 0, left = +x).
	private static Limb zombie(double x, double y, double z) {
		return Limb.classify(new Vec3(x, y, z), 0.0f, 0.6f, 1.95f, 1.74f);
	}

	// A cow: 0.9 wide, 1.4 tall, facing south (forward = +z).
	private static Limb cow(double x, double y, double z) {
		return Limb.classify(new Vec3(x, y, z), 0.0f, 0.9f, 1.4f, 1.3f);
	}

	@Test
	void upright_body_hasHeadArmsLegsAndTorso() {
		assertEquals(Limb.HEAD, zombie(0.0, 1.8, 0.3));
		assertEquals(Limb.LEFT_ARM, zombie(0.3, 1.3, 0.0));
		assertEquals(Limb.RIGHT_ARM, zombie(-0.3, 1.3, 0.0));
		assertEquals(Limb.LEFT_LEG, zombie(0.1, 0.4, 0.3));
		assertEquals(Limb.RIGHT_LEG, zombie(-0.1, 0.4, 0.3));
		assertEquals(Limb.TORSO, zombie(0.0, 1.2, 0.3));
	}

	@Test
	void leftAndRight_followTheBodyNotTheWorld() {
		// Facing north (yaw 180) the body's left is −x.
		assertEquals(Limb.LEFT_ARM, Limb.classify(new Vec3(-0.3, 1.3, 0.0), 180.0f, 0.6f, 1.95f, 1.74f));
	}

	@Test
	void long_body_hasHeadInFront_frontAndHindLegs() {
		assertEquals(Limb.HEAD, cow(0.0, 1.1, 0.45));
		assertEquals(Limb.LEFT_ARM, cow(0.3, 0.3, 0.3));
		assertEquals(Limb.RIGHT_LEG, cow(-0.3, 0.3, -0.3));
		assertEquals(Limb.TORSO, cow(0.0, 0.9, 0.0));
	}

	@Test
	void arms_comeOffSoonerThanLegs_andNeverOnALightHit() {
		assertTrue(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.35f, 0.5f));
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.35f, 0.3f), "light hit");
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.2f, 0.9f), "not enough damage yet");
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_LEG, 0.35f, 0.5f));
		assertTrue(Dismemberment.shouldSever(Limb.LEFT_LEG, 0.5f, 0.7f));
		assertFalse(Dismemberment.shouldSever(Limb.HEAD, 5.0f, 1.0f), "heads only come off on a kill");
		assertFalse(Dismemberment.shouldSever(Limb.TORSO, 5.0f, 1.0f));
	}

	@Test
	void limbState_accumulatesDamage_andRemembersCuts() {
		LimbState s = LimbState.NONE.hit(Limb.RIGHT_ARM, 0.2f).hit(Limb.RIGHT_ARM, 0.15f);
		assertEquals(0.35f, s.damage(Limb.RIGHT_ARM), 1e-6);
		assertEquals(Limb.RIGHT_ARM, s.lastLimb());
		s = s.sever(Limb.RIGHT_ARM);
		assertTrue(s.isSevered(Limb.RIGHT_ARM));
		assertFalse(s.isSevered(Limb.LEFT_ARM));
		assertEquals(Limb.TORSO, s.hit(Limb.TORSO, 0.1f).lastLimb());
	}

	@Test
	void blades_severInANormalFight() {
		// Iron sword on a 20-health zombie: 6 damage, severity 0.3 per blow.
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.3f, 0.3f), "fists and clubs still need a huge blow");
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.15f, 0.3f, true), "one sword blow is not enough");
		assertTrue(Dismemberment.shouldSever(Limb.LEFT_ARM, 0.3f, 0.3f, true), "the second one is");
		assertFalse(Dismemberment.shouldSever(Limb.LEFT_LEG, 0.3f, 0.3f, true));
		assertTrue(Dismemberment.shouldSever(Limb.LEFT_LEG, 0.45f, 0.3f, true));
		assertFalse(Dismemberment.shouldSever(Limb.HEAD, 5.0f, 1.0f, true), "heads only come off on a kill");
	}

	@Test
	void killCut_bladeToTheBody_sometimesTakesTheHead() {
		net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(1L);
		assertEquals(Limb.LEFT_ARM, Dismemberment.killCut(Limb.LEFT_ARM, false, r));
		int heads = 0;
		for (int i = 0; i < 1000; i++) {
			if (Dismemberment.killCut(Limb.TORSO, true, r) == Limb.HEAD) {
				heads++;
			}
			assertEquals(null, Dismemberment.killCut(Limb.TORSO, false, r));
		}
		assertTrue(heads > 400 && heads < 600, "heads " + heads);
	}
}
