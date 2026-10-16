package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.lume.gore.Decal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders blood decals as flat, textured model slabs on block faces.
 *
 * <p>The vanilla custom-geometry submit path is inert on 26.3, so decals go through
 * the model feature instead, which is the same path entities use. Each atlas variant
 * gets one baked {@link ModelPart} holding a single outward-facing quad whose UVs
 * cover exactly that atlas cell.
 */
public final class DecalRenderer {
	private static final float OFFSET = 0.006f;
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

	private DecalRenderer() {
	}

	public static void render(LevelRenderContext context) {
		List<Decal> decals = DecalManager.snapshot();

		if (decals.isEmpty()) {
			return;
		}

		if (renderType == null) {
			renderType = RenderTypes.entityTranslucent(GoreClient.BLOOD_ATLAS);
		}

		PoseStack poseStack = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		ClientLevel level = Minecraft.getInstance().level;

		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);

		for (Decal decal : decals) {
			submit(context, poseStack, decal, level);
		}

		poseStack.popPose();
	}

	private static void submit(LevelRenderContext context, PoseStack poseStack, Decal decal, ClientLevel level) {
		Direction face = Direction.from3DDataValue(decal.face());
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 axis1 = Decal.axis1(face);
		Vec3 axis2 = Decal.axis2(face);
		Vec3 center = new Vec3(decal.x() + 0.5, decal.y() + 0.5, decal.z() + 0.5)
				.add(normal.scale(0.5f + OFFSET))
				.add(axis1.scale(decal.u()))
				.add(axis2.scale(decal.v()));

		int light;

		if (level != null) {
			BlockPos lightPos = new BlockPos(decal.x(), decal.y(), decal.z()).relative(face);
			light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos), level.getBrightness(LightLayer.SKY, lightPos));
		} else {
			light = LightCoordsUtil.FULL_BRIGHT;
		}

		Quaternionf orientation = new Quaternionf().rotateTo(MODEL_FRONT,
				new Vector3f((float) normal.x, (float) normal.y, (float) normal.z));
		float scale = decal.size() / BLOCKS;

		poseStack.pushPose();
		poseStack.translate(center.x, center.y, center.z);
		poseStack.rotate(orientation);
		poseStack.rotateDegrees(Axis.ZP, decal.rot());
		poseStack.scale(scale, scale, scale);
		context.submitNodeCollector().submitModelPart(VARIANTS[decal.variant() & 15], poseStack, renderType,
				light, OverlayTexture.NO_OVERLAY, null);
		poseStack.popPose();
	}
}
