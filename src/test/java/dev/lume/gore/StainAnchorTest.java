package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import dev.lume.gore.client.StainAnchor;

/**
 * Catches the unit-correctness bugs in mapping a recorded hit point onto a
 * model part: blocks vs model units (the 16x factor), and root frame vs part
 * frame. If either is wrong the wound lands beside the body instead of on it.
 *
 * <p>{@link StainAnchor#restingPosition} inverts the part's resting transform
 * (built by {@link ModelPart#translateAndRotate}, which divides position by 16
 * to convert model units to blocks), so it maps root-frame blocks to part-local
 * blocks. The caller multiplies by 16 to get model units for {@code
 * CubeProjection}.
 */
class StainAnchorTest {
	private static ModelPart partAt(float x, float y, float z) {
		// Set the initial pose (in model units) so resetPose() restores it;
		// translateAndRotate then divides by 16 to place the part at (x/16,
		// y/16, z/16) blocks in the root frame.
		ModelPart part = new ModelPart(List.of(), Map.of());
		part.setInitialPose(PartPose.offset(x, y, z));
		return part;
	}

	@Test
	void restingPositionMapsRootFrameToPartLocal() {
		// Part sits at (16, 32, 48) model units = (1, 2, 3) blocks. A root
		// point at (5, 6, 7) blocks maps to (4, 4, 4) blocks in part-local.
		ModelPart part = partAt(16.0f, 32.0f, 48.0f);

		Vector3f local = StainAnchor.restingPosition(part, new Vector3f(5.0f, 6.0f, 7.0f));

		assertEquals(4.0f, local.x(), 1.0E-4F);
		assertEquals(4.0f, local.y(), 1.0E-4F);
		assertEquals(4.0f, local.z(), 1.0E-4F);
	}

	@Test
	void restingPositionAtPartPositionReturnsOrigin() {
		// A point at the part's own position maps to the part's origin.
		ModelPart part = partAt(24.0f, -32.0f, 12.0f);  // (1.5, -2, 0.75) blocks

		Vector3f local = StainAnchor.restingPosition(part,
				new Vector3f(1.5f, -2.0f, 0.75f));

		assertEquals(0.0f, local.x(), 1.0E-4F);
		assertEquals(0.0f, local.y(), 1.0E-4F);
		assertEquals(0.0f, local.z(), 1.0E-4F);
	}

	@Test
	void restingPositionDoesNotMutateThePart() {
		// restingPosition stores and restores the live pose; the part's
		// position fields must be unchanged afterwards.
		ModelPart part = partAt(16.0f, 32.0f, 48.0f);

		StainAnchor.restingPosition(part, new Vector3f(0.0f, 0.0f, 0.0f));

		assertEquals(0.0f, part.x, 1.0E-4F);
		assertEquals(0.0f, part.y, 1.0E-4F);
		assertEquals(0.0f, part.z, 1.0E-4F);
	}

	@Test
	void restingPositionHandlesZeroPosition() {
		// A part at the origin maps a point to itself.
		ModelPart part = partAt(0.0f, 0.0f, 0.0f);

		Vector3f local = StainAnchor.restingPosition(part, new Vector3f(3.0f, -1.0f, 2.0f));

		assertEquals(3.0f, local.x(), 1.0E-4F);
		assertEquals(-1.0f, local.y(), 1.0E-4F);
		assertEquals(2.0f, local.z(), 1.0E-4F);
	}
}
