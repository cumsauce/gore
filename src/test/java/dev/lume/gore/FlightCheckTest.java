package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
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
}
