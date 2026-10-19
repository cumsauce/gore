package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** {@link FlightCheck}: every stain-carrying droplet lands on its stain on the tick it is born. */
class FlightCheckTest {
	@Test
	void everyDroplet_landsOnItsStain_whenItIsBorn() {
		for (long seed = 1; seed <= 20; seed++) {
			List<FlightCheck.Flight> flights = FlightCheck.fly(PreviewScenes.spray(0.85f, seed));
			assertFalse(flights.isEmpty(), "seed " + seed + " threw no stain droplets");
			for (FlightCheck.Flight f : flights) {
				assertEquals(List.of(), f.problems(), "seed " + seed + " " + f.decal());
			}
		}
	}

	@Test
	void stainBornAtTheBlow_isReported() {
		Decal d = PreviewScenes.wall(2.0, 1.0, 0.3f, 0f, 0, PreviewScenes.BLOW);
		FlightCheck.Flight f = FlightCheck.flyOne(new PreviewScenes.Placement(PreviewScenes.BLOW, d, PreviewScenes.WOUND));
		assertFalse(f.problems().isEmpty());
	}

	@Test
	void longThrow_atTheFlightTickCap_stillLands() {
		Decal d = PreviewScenes.wall(3.5, 2.5, 0.3f, 0f, 0, 0);
		Vec3 far = new Vec3(0.5, 1.0, 40.0);
		int ticks = SprayModel.flightTicks(far.distanceTo(Decal.surfacePoint(d)));
		assertEquals(16, ticks);
		Decal born = new Decal(d.x(), d.y(), d.z(), d.face(), d.u(), d.v(), d.size(), d.rot(), d.variant(), 100 + ticks);
		assertEquals(List.of(), FlightCheck.flyOne(new PreviewScenes.Placement(100, born, far)).problems());
	}

	@Test
	void sprayArcs_neverPassThroughTheRoomBeforeLanding() {
		for (long seed = 1; seed <= 20; seed++) {
			for (FlightCheck.Flight f : FlightCheck.fly(PreviewScenes.spray(0.85f, seed))) {
				assertTrue(f.notes().stream().noneMatch(n -> n.startsWith("inside")), "seed " + seed + " " + f.notes());
			}
		}
	}
}
