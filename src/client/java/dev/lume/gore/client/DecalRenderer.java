package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.Decal;
import dev.lume.gore.Gore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Renders blood decals as flat, textured model slabs on block faces.
 *
 * <p>This class owns the world-space maths — which face, where on the face, and at
 * what light level. The quad itself (baked atlas variants, orientation, roll,
 * scale, submit) lives in {@link StainQuad}, shared with the body and weapon
 * stains. The vanilla custom-geometry submit path is inert on 26.3, so the quad
 * goes through the model feature instead, which is the same path entities use.
 */
public final class DecalRenderer {
	/** Ticks a drip takes to run its full length (4 seconds). */
	private static final int DRIP_TICKS = 80;
	/** Birth times of drips whose falling drop has already been spawned. */
	private static final Set<Long> DRIPPED = new HashSet<>();

	private DecalRenderer() {
	}

	/** Forgets completed drips; called on disconnect. */
	public static void clearDrips() {
		DRIPPED.clear();
	}

	public static void render(LevelRenderContext context) {
		List<Decal> decals = DecalManager.snapshot();

		if (decals.isEmpty()) {
			return;
		}

		StainQuad.init();

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
		// Face centre of the block; StainQuad pushes the quad OFFSET off the face along `normal`.
		Vec3 center = new Vec3(decal.x() + 0.5, decal.y() + 0.5, decal.z() + 0.5)
				.add(normal.scale(0.5f))
				.add(axis1.scale(decal.u()))
				.add(axis2.scale(decal.v()));

		int light;

		if (level != null) {
			BlockPos lightPos = new BlockPos(decal.x(), decal.y(), decal.z()).relative(face);
			light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos), level.getBrightness(LightLayer.SKY, lightPos));
		} else {
			light = LightCoordsUtil.FULL_BRIGHT;
		}

		poseStack.pushPose();
		poseStack.translate(center.x, center.y, center.z);

		if (decal.born() > 0L && level != null) {
			// A drip: animate the run downward, and drop a droplet when it bottoms out.
			float progress = Mth.clamp((level.getGameTime() - decal.born()) / (float) DRIP_TICKS, 0.0f, 1.0f);
			StainQuad.submitDrip(context.submitNodeCollector(), poseStack, decal.variant(), decal.size(),
					decal.rot(), light, new Vector3f((float) normal.x, (float) normal.y, (float) normal.z), progress);

			if (progress >= 1.0f && DRIPPED.add(decal.born())) {
				level.addParticle(Gore.BLOOD_DROP, center.x, center.y - decal.size(), center.z, 0.0, -0.08, 0.0);
			}
		} else {
			StainQuad.submitOnFace(context.submitNodeCollector(), poseStack, decal.variant(), decal.size(),
					decal.rot(), light, face, 0xFFFFFFFF);
		}

		poseStack.popPose();
	}
}
