package dev.lume.gore;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecalFrameTest {

	@ParameterizedTest
	@EnumSource(Direction.class)
	void orientation_mapsModelTailToTailVector(Direction face) {
		for (float rot : new float[]{0f, 37f, 90f, 180f, -90f, 270f}) {
			Quaternionf q = DecalFrame.orientation(face, rot);
			Vector3f tail = q.transform(new Vector3f(0, -1, 0));
			Vec3 expected = DecalFrame.tail(face, rot);
			assertEquals(expected.x, tail.x, 1e-4);
			assertEquals(expected.y, tail.y, 1e-4);
			assertEquals(expected.z, tail.z, 1e-4);
			Vector3f front = q.transform(new Vector3f(0, 0, -1));
			Vec3 n = Decal.axis1(face).cross(Decal.axis2(face));
			assertEquals(n.x, front.x, 1e-4);
			assertEquals(n.y, front.y, 1e-4);
			assertEquals(n.z, front.z, 1e-4);
		}
	}

	@Test
	void tail_rot90_onSouthFace_pointsUp() {
		Vec3 t = DecalFrame.tail(Direction.SOUTH, 90f);
		assertEquals(0.0, t.x, 1e-6);
		assertEquals(1.0, t.y, 1e-6);
		assertEquals(0.0, t.z, 1e-6);
	}
}
