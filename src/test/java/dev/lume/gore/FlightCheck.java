package dev.lume.gore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Flight check: flies every stain-carrying droplet of a spray scene the way
 * {@code BloodDropParticle.tickFlight} does — {@link Ballistics#arcVelocity} over
 * the ticks the client is given, {@code born − launch} — and asserts it ends on
 * its stain's {@link Decal#surfacePoint} on the stain's born tick. Draws side
 * (z–y) and top (x–z) views of every path.
 *
 * <pre>./gradlew flightCheck -Pseed=7 -Pseverity=0.9</pre>
 *
 * Exits non-zero if any droplet misses; {@code FlightCheckTest} runs the same
 * check in the unit tests.
 */
public final class FlightCheck {
	private static final double EPS = 1.0e-6;

	private FlightCheck() {
	}

	/** One simulated flight: the path per tick and what went wrong, if anything. */
	record Flight(Decal decal, Vec3 from, Vec3 to, long launch, int ticks, List<Vec3> path, List<String> problems,
			List<String> notes) {
	}

	public static void main(String[] args) throws IOException {
		long seed = Long.getLong("seed", 20261016L);
		float severity = Float.parseFloat(System.getProperty("severity", "0.85"));
		Path out = Path.of(System.getProperty("out", "build/inspect")).resolve("flight");
		List<Flight> flights = fly(PreviewScenes.spray(severity, seed));
		int bad = 0;

		System.out.printf(Locale.ROOT, "flight check: seed %d severity %.2f, %d droplets%n", seed, severity, flights.size());
		System.out.println("  #  face ticks  launch born  arrive  miss       notes");
		for (int i = 0; i < flights.size(); i++) {
			Flight f = flights.get(i);
			Vec3 end = f.path().getLast();
			System.out.printf(Locale.ROOT, "  %2d %5s %5d %7d %4d %7d  %.2e  %s%s%n", i, f.decal().face() == PreviewScenes.UP ? "floor" : "wall",
					f.ticks(), f.launch(), f.decal().born(), f.launch() + f.path().size() - 1, end.distanceTo(f.to()),
					String.join("; ", f.notes()), f.problems().isEmpty() ? "" : "  FAIL: " + String.join("; ", f.problems()));
			if (!f.problems().isEmpty()) {
				bad++;
			}
		}

		Files.createDirectories(out);
		Path png = out.resolve("flight-" + seed + ".png");
		draw(flights).write(png);
		System.out.printf(Locale.ROOT, "flight check: %d of %d droplets wrong -> %s%n", bad, flights.size(), png.toAbsolutePath());
		if (bad > 0) {
			System.exit(1);
		}
	}

	/** Flies every placement that has a wound, as the client flies the payload's flights. */
	static List<Flight> fly(List<PreviewScenes.Placement> scene) {
		List<Flight> out = new ArrayList<>();
		for (PreviewScenes.Placement p : scene) {
			if (p.origin() == null || DecalMerge.isDrip(p.decal())) {
				continue; // GoreClient.launchFlights skips drips the same way
			}
			out.add(flyOne(p));
		}
		return out;
	}

	static Flight flyOne(PreviewScenes.Placement p) {
		Decal d = p.decal();
		Vec3 from = p.origin();
		Vec3 to = Decal.surfacePoint(d);
		List<String> problems = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		// GoreClient: ticks = born − now; BloodDropParticle.fly clamps to ≥ 1.
		int ticks = Math.max(1, (int) (d.born() - p.launch()));
		Vec3 v0 = Ballistics.arcVelocity(from, to, ticks);
		List<Vec3> path = new ArrayList<>();
		path.add(from);
		// tickFlight: age goes 1, 2, …; the droplet is removed on the tick age reaches ticks.
		for (int age = 1; age <= ticks; age++) {
			Vec3 pos = Ballistics.arcPoint(from, v0, Math.min(age, ticks));
			path.add(pos);
			if (age < ticks && (pos.z < -EPS || pos.y < -EPS)) {
				notes.add("inside a block at age " + age);
			}
		}

		if (d.born() <= p.launch()) {
			problems.add("born at " + d.born() + ", no later than its launch at " + p.launch());
		}
		long arrive = p.launch() + ticks;
		if (arrive != d.born()) {
			problems.add("lands at " + arrive + ", stain born " + d.born());
		}
		double miss = path.getLast().distanceTo(to);
		if (miss > EPS) {
			problems.add(String.format(Locale.ROOT, "lands %.4f from its stain", miss));
		}
		if (ticks != SprayModel.flightTicks(from.distanceTo(to))) {
			// Born from the ray's hit, flown to the clamped stain point: usually equal.
			notes.add("flightTicks(to stain) = " + SprayModel.flightTicks(from.distanceTo(to)));
		}
		return new Flight(d, from, to, p.launch(), ticks, path, problems, notes);
	}

	/** Side view (z across, y up) beside top view (x across, z down). */
	static PreviewImage draw(List<Flight> flights) {
		int s = 110;
		int pad = 20;
		int panel = 5 * s;
		PreviewImage img = new PreviewImage(2 * panel + 3 * pad, panel + 2 * pad, 0x1c1c1c);
		int sx = pad;
		int tx = 2 * pad + panel;
		// Side: z from 4.5 (left, the wound side) to -0.5; y from 3.5 (top) to -0.5.
		// Top: x from -0.5 to 4.5; z from -0.5 (top, the wall) to 4.5.
		java.util.function.DoubleUnaryOperator sideX = z -> sx + (4.5 - z) * s;
		java.util.function.DoubleUnaryOperator sideY = y -> pad + (3.5 - y) * s;
		java.util.function.DoubleUnaryOperator topX = x -> tx + (x + 0.5) * s;
		java.util.function.DoubleUnaryOperator topY = z -> pad + (z + 0.5) * s;

		int stone = 0xff6a6a6a;
		img.fillRect((int) sideX.applyAsDouble(0), (int) sideY.applyAsDouble(PreviewScenes.WALL_H),
				(int) sideX.applyAsDouble(-0.3), (int) sideY.applyAsDouble(0), stone);
		img.fillRect((int) sideX.applyAsDouble(PreviewScenes.FLOOR_D), (int) sideY.applyAsDouble(0),
				(int) sideX.applyAsDouble(-0.3), (int) sideY.applyAsDouble(-0.3), stone);
		img.fillRect((int) topX.applyAsDouble(0), (int) topY.applyAsDouble(-0.3),
				(int) topX.applyAsDouble(PreviewScenes.WALL_W), (int) topY.applyAsDouble(0), stone);
		img.fillRect((int) topX.applyAsDouble(0), (int) topY.applyAsDouble(0),
				(int) topX.applyAsDouble(PreviewScenes.WALL_W), (int) topY.applyAsDouble(PreviewScenes.FLOOR_D), 0xff2e2e2e);

		for (Flight f : flights) {
			int col = f.problems().isEmpty() ? 0xffd02020 : 0xffffe000;
			for (int i = 1; i < f.path().size(); i++) {
				Vec3 a = f.path().get(i - 1);
				Vec3 b = f.path().get(i);
				img.line(sideX.applyAsDouble(a.z), sideY.applyAsDouble(a.y), sideX.applyAsDouble(b.z), sideY.applyAsDouble(b.y), col);
				img.line(topX.applyAsDouble(a.x), topY.applyAsDouble(a.z), topX.applyAsDouble(b.x), topY.applyAsDouble(b.z), col);
				img.dot(sideX.applyAsDouble(b.z), sideY.applyAsDouble(b.y), 1, 0xffff9090);
				img.dot(topX.applyAsDouble(b.x), topY.applyAsDouble(b.z), 1, 0xffff9090);
			}
			// The stain's own point, ringed: a path that ends inside the ring landed.
			img.circle(sideX.applyAsDouble(f.to().z), sideY.applyAsDouble(f.to().y), 4, 0xff40ff40);
			img.circle(topX.applyAsDouble(f.to().x), topY.applyAsDouble(f.to().z), 4, 0xff40ff40);
		}

		Vec3 w = PreviewScenes.WOUND;
		img.dot(sideX.applyAsDouble(w.z), sideY.applyAsDouble(w.y), 3, 0xffffffff);
		img.dot(topX.applyAsDouble(w.x), topY.applyAsDouble(w.z), 3, 0xffffffff);
		return img;
	}
}
