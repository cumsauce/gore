package dev.lume.gore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BleedingDripTest {

	@Test
	void dripDecal_returnsNullForUpAndDown() {
		RandomSource r = RandomSource.create(1);
		for (Direction d : new Direction[]{Direction.UP, Direction.DOWN})
			assertNull(Bleeding.dripDecal(new BlockHitResult(new Vec3(0.5, 1.0, 0.5), d, BlockPos.ZERO, false), 0.6f, 100L, r));
	}

	@Test
	void dripDecal_onWall_isVariant14_withBornAndTailUp() {
		Decal d = Bleeding.dripDecal(new BlockHitResult(new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, BlockPos.ZERO, false), 0.6f, 100L, RandomSource.create(1));
		assertEquals(14, d.variant());
		assertEquals(100L, d.born());
		Vec3 tail = DecalFrame.tail(Direction.SOUTH, d.rot());
		assertEquals(1.0, tail.y, 1e-3);
	}
}
