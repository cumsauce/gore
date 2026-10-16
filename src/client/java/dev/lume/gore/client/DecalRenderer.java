package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lume.gore.Decal;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/** Submits blood decal quads as custom geometry into the level render collector. */
public final class DecalRenderer {
	private static final float CELL = 0.25f;
	private static final float MARGIN = 0.015f;
	private static final float OFFSET = 0.006f;
	private static final float[] CORNER_A = {-1.0f, 1.0f, 1.0f, -1.0f};
	private static final float[] CORNER_B = {-1.0f, -1.0f, 1.0f, 1.0f};
	private static RenderType renderType;

	private DecalRenderer() {
	}

	public static void collect(LevelRenderContext context) {
		List<Decal> decals = DecalManager.snapshot();

		if (decals.isEmpty()) {
			return;
		}

		if (renderType == null) {
			renderType = RenderTypes.entityTranslucent(GoreClient.BLOOD_ATLAS);
		}

		PoseStack poseStack = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);
		context.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, buffer) -> draw(pose, buffer, decals));
		poseStack.popPose();
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, List<Decal> decals) {
		ClientLevel level = Minecraft.getInstance().level;

		for (Decal decal : decals) {
			Direction face = Direction.from3DDataValue(decal.face());
			Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
			Vec3 axis1 = Decal.axis1(face);
			Vec3 axis2 = Decal.axis2(face);
			Vec3 center = new Vec3(decal.x() + 0.5, decal.y() + 0.5, decal.z() + 0.5)
					.add(normal.scale(OFFSET))
					.add(axis1.scale(decal.u()))
					.add(axis2.scale(decal.v()));

			int cell = decal.variant() & 15;
			int cellX = cell & 3;
			int cellY = cell >> 2;
			float u0 = cellX * CELL + MARGIN;
			float u1 = (cellX + 1) * CELL - MARGIN;
			float v0 = cellY * CELL + MARGIN;
			float v1 = (cellY + 1) * CELL - MARGIN;

			float half = decal.size() * 0.5f;
			double angle = Math.toRadians(decal.rot());
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);

			int light;

			if (level != null) {
				BlockPos lightPos = new BlockPos(decal.x(), decal.y(), decal.z()).relative(face);
				light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos), level.getBrightness(LightLayer.SKY, lightPos));
			} else {
				light = LightCoordsUtil.FULL_BRIGHT;
			}

			for (int i = 0; i < 4; i++) {
				vertex(buffer, pose, center, axis1, axis2, normal, cos, sin, half, CORNER_A[i], CORNER_B[i], u0, u1, v0, v1, light);
			}
		}
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 center, Vec3 axis1, Vec3 axis2, Vec3 normal,
			float cos, float sin, float half, float cornerA, float cornerB,
			float u0, float u1, float v0, float v1, int light) {
		float rotatedA = cornerA * cos - cornerB * sin;
		float rotatedB = cornerA * sin + cornerB * cos;
		Vec3 pos = center.add(axis1.scale(rotatedA * half)).add(axis2.scale(rotatedB * half));
		float u = u0 + (cornerA + 1.0f) * 0.5f * (u1 - u0);
		float v = v0 + (1.0f - (cornerB + 1.0f) * 0.5f) * (v1 - v0);
		buffer.addVertex(pose, (float) pos.x, (float) pos.y, (float) pos.z)
				.setColor(1.0f, 1.0f, 1.0f, 1.0f)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
	}
}
