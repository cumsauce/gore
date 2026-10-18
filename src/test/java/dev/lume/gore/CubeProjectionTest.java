package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pure core of body-stain placement: given a set of model boxes and the
 * direction a wound faces, which face of which box carries the stain.
 *
 * <p>These are the properties a mob's blood depends on. If any of them is
 * wrong the stain floats off the body, sinks into it, or lands on a surface
 * the camera cannot see — which reads as "this mob does not bleed".
 */
class CubeProjectionTest {
	private static CubeProjection.Box box(float x, float y, float z, float dx, float dy, float dz, String name) {
		return new CubeProjection.Box(x, y, z, dx, dy, dz, name);
	}

	@Test
	void zeroBoxesReturnsEmpty() {
		assertTrue(CubeProjection.project(List.of(), new float[] {1.0F, 0.0F, 0.0F}).isEmpty());
	}

	@Test
	void singleBoxPicksFaceNearestImpact() {
		Optional<CubeProjection.Result> hit = CubeProjection.project(
				List.of(box(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, "body")),
				new float[] {1.0F, 0.0F, 0.0F});

		assertTrue(hit.isPresent());
		float[] normal = hit.get().normal();
		assertEquals(1.0F, normal[0], 1.0E-4F);
		assertEquals(0.0F, normal[1], 1.0E-4F);
		assertEquals(0.0F, normal[2], 1.0E-4F);

		// The point must lie on the +X face, not at the box centre or on the far
		// side: a stain at the centre would be buried inside the model.
		float[] point = hit.get().point();
		assertEquals(1.0F, point[0], 1.0E-4F);
		assertEquals("body", hit.get().partName());
	}

	@Test
	void multiBoxPicksNearestSurface() {
		// Two slabs at different depths along +Z. Impact arrives from +Z, so the
		// nearer surface is the one on the far slab; picking the near slab would
		// put the stain behind the body.
		List<CubeProjection.Box> boxes = List.of(
				box(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 1.0F, "far"),
				box(-1.0F, -1.0F, 5.0F, 2.0F, 2.0F, 1.0F, "near"));

		Optional<CubeProjection.Result> hit = CubeProjection.project(boxes, new float[] {0.0F, 0.0F, 1.0F});

		assertTrue(hit.isPresent());
		assertEquals("near", hit.get().partName());
		assertEquals(6.0F, hit.get().point()[2], 1.0E-4F);
	}

	@Test
	void impactAlongAxisDoesNotDivideByZero() {
		List<CubeProjection.Box> boxes = List.of(box(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, "body"));
		float[][] directions = {{0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F}};

		for (float[] direction : directions) {
			Optional<CubeProjection.Result> hit = CubeProjection.project(boxes, direction);

			assertTrue(hit.isPresent());
			float[] normal = hit.get().normal();
			assertEquals(direction[1], normal[1], 1.0E-4F);

			for (float value : hit.get().point()) {
				assertTrue(Float.isFinite(value));
			}

			for (float value : normal) {
				assertTrue(Float.isFinite(value));
			}
		}
	}

	@Test
	void projectAtWithoutPointMatchesProject() {
		Optional<CubeProjection.Result> without = CubeProjection.projectAt(
				List.of(box(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, "body")),
				new float[] {1.0F, 0.0F, 0.0F}, null);
		Optional<CubeProjection.Result> with = CubeProjection.projectAt(
				List.of(box(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, "body")),
				new float[] {1.0F, 0.0F, 0.0F}, new float[] {0.0F, 0.0F, 0.0F});

		assertTrue(without.isPresent());
		assertTrue(with.isPresent());
		assertEquals(without.get().normal()[0], with.get().normal()[0], 1.0E-4F);
		assertEquals(without.get().point()[0], with.get().point()[0], 1.0E-4F);
	}

	@Test
	void projectAtTiebreakPicksFaceNearestHitPoint() {
		// Two coplanar +X-facing slabs side by side along X. Impact comes from +X,
		// so both faces face the wound equally; the hit point breaks the tie toward
		// the slab the blow actually met.
		List<CubeProjection.Box> boxes = List.of(
				box(-2.0F, -1.0F, -1.0F, 1.0F, 2.0F, 2.0F, "left"),
				box( 1.0F, -1.0F, -1.0F, 1.0F, 2.0F, 2.0F, "right"));

		// Hit point sits on the right slab.
		Optional<CubeProjection.Result> hit = CubeProjection.projectAt(
				boxes, new float[] {1.0F, 0.0F, 0.0F}, new float[] {1.5F, 0.0F, 0.0F});

		assertTrue(hit.isPresent());
		assertEquals("right", hit.get().partName());
		assertEquals(2.0F, hit.get().point()[0], 1.0E-4F);

		// Hit point sits on the left slab.
		hit = CubeProjection.projectAt(
				boxes, new float[] {1.0F, 0.0F, 0.0F}, new float[] {-1.5F, 0.0F, 0.0F});

		assertTrue(hit.isPresent());
		assertEquals("left", hit.get().partName());
		assertEquals(-1.0F, hit.get().point()[0], 1.0E-4F);
	}

	@Test
	void projectAtWithKnownHitPointStillPicksFacingFace() {
		// A single +X-facing slab. Impact comes from +X; the hit point is on the
		// slab's surface, so the face is chosen by facing and the point is the
		// recorded hit.
		List<CubeProjection.Box> boxes = List.of(
				box(-2.0F, -1.0F, -1.0F, 1.0F, 2.0F, 2.0F, "front"));

		Optional<CubeProjection.Result> hit = CubeProjection.projectAt(
				boxes, new float[] {1.0F, 0.0F, 0.0F}, new float[] {-1.0F, 0.5F, 0.0F});

		assertTrue(hit.isPresent());
		assertEquals("front", hit.get().partName());
		assertEquals(1.0F, hit.get().normal()[0], 1.0E-4F);
		assertEquals(-1.0F, hit.get().point()[0], 1.0E-4F);
	}
}
