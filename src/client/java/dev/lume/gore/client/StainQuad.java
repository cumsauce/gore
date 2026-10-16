package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One blood-stain quad: a baked, single-faced model part per atlas cell, oriented
 * from an outward normal, rolled, scaled and submitted through the model feature.
 *
 * <p>Shared by the wall decals, the body stains and the weapon stains so the three
 * callers cannot drift apart. The vanilla custom-geometry submit path is inert on
 * 26.3, so everything goes through {@link SubmitNodeCollector#submitModelPart},
 * the same path entities use.
 */
public final class StainQuad {
	/** Pushes the quad off the surface it rests on, in world units. */
	static final float OFFSET = 0.006f;
	private static final int CELL = 32;
	/** Box size in model units; 32 units is two blocks. */
	private static final float UNITS = CELL;
	private static final float BLOCKS = UNITS / 16.0f;

	private static final ModelPart[] VARIANTS = buildVariants();
	private static final Vector3f MODEL_FRONT = new Vector3f(0.0f, 0.0f, -1.0f);

	private static RenderType renderType;

	private static ModelPart[] buildVariants() {
		ModelPart[] parts = new ModelPart[16];

		for (int cell = 0; cell < 16; cell++) {
			int cellX = cell & 3;
			int cellY = cell >> 2;
			ModelPart.Cube cube = new ModelPart.Cube(cellX * CELL - 1, cellY * CELL - 1,
					-UNITS / 2.0f, -UNITS / 2.0f, 0.0f, UNITS, UNITS, 1.0f,
					0.0f, 0.0f, 0.0f, false, 1.0f, 1.0f, Set.of(Direction.NORTH));
			parts[cell] = new ModelPart(List.of(cube), Map.of());
		}

		return parts;
	}

	private StainQuad() {
	}

	/** Lazily builds the shared render type; idempotent, safe to call repeatedly. */
	public static void init() {
		if (renderType == null) {
			renderType = RenderTypes.entityTranslucent(GoreClient.BLOOD_ATLAS);
		}
	}

	/**
	 * Places one stain quad at the current pose, facing {@code outward}: orients the
	 * quad from {@code outward}, offsets it by {@link #OFFSET} along {@code outward},
	 * rolls it by {@code rot}, scales it so {@code size} is the quad's world size,
	 * and submits the variant's baked part. The pose stack is left exactly as it
	 * was found.
	 */
	public static void submit(SubmitNodeCollector collector, PoseStack poseStack,
			int variant, float size, float rot, int light, Vector3f outward) {
		init();

		Quaternionf orientation = new Quaternionf().rotateTo(MODEL_FRONT, outward);
		float scale = size / BLOCKS;

		poseStack.pushPose();
		poseStack.translate(outward.x() * OFFSET, outward.y() * OFFSET, outward.z() * OFFSET);
		poseStack.rotate(orientation);
		poseStack.rotateDegrees(Axis.ZP, rot);
		poseStack.scale(scale, scale, scale);
		collector.submitModelPart(VARIANTS[variant & 15], poseStack, renderType,
				light, OverlayTexture.NO_OVERLAY, null);
		poseStack.popPose();
	}
}
