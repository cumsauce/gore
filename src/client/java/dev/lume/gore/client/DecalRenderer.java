package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.Decal;
import dev.lume.gore.DecalFrame;
import dev.lume.gore.DecalMerge;
import dev.lume.gore.BloodAppearance;
import dev.lume.gore.Drying;
import dev.lume.gore.Flow;
import dev.lume.gore.Gore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Renders blood decals as flat, textured model slabs on block faces.
 *
 * <p>This class owns the world-space maths — which face, where on the face, and at
 * what light level. The quad itself (baked atlas variants, orientation, roll,
 * scale, submit) lives in {@link StainQuad}, shared with the body and weapon
 * stains. The vanilla custom-geometry submit path is inert on 26.3, so the quad
 * goes through the model feature instead, which is the same path entities use.
 *
 * <p>It must be registered on {@code LevelRenderEvents.COLLECT_SUBMITS}, which
 * fires at the tail of {@code LevelRenderer.submitFeatures}: submits made there
 * are baked and drawn in the same frame. Submitting from a draw event (for
 * example {@code AFTER_TRANSLUCENT_TERRAIN}) is a frame late — the decals then
 * ride the previous frame's camera, so they swim over the world while the
 * player moves, z-fight into the block face and pop in and out of view — and
 * they are not submitted at all on frames where that draw call does not run.
 */
public final class DecalRenderer {
	/** Drips whose falling drop has already been shed; one break-off per drip. */
	private static final Set<Decal> DRIPPED = new HashSet<>();

	private DecalRenderer() {
	}

	/** True for wall drips (atlas cell 14), which flow as they age. */
	public static boolean isDrip(Decal d) {
		return DecalMerge.isDrip(d);
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

		long now = level != null ? level.getGameTime() : 0L;
		int color = BloodAppearance.tint(
				BloodAppearance.seedFor(decal.x(), decal.y(), decal.z(), decal.face(),
						decal.u(), decal.v(), decal.variant(), decal.born()),
				Drying.dryness(decal.born(), now, Drying.SCALE));

		// Old blood thins out instead of sitting at full strength until the
		// server's sweep deletes it; the two clocks are the same (Drying.fade),
		// so the client and the server never disagree about when it is gone.
		float fade = Drying.fade(decal.born(), now, Drying.SCALE);

		if (fade <= 0.0f) {
			return;
		}

		if (fade < 1.0f) {
			color = ARGB.color(Math.round(ARGB.alpha(color) * fade),
					ARGB.red(color), ARGB.green(color), ARGB.blue(color));
		}

		poseStack.pushPose();
		poseStack.translate(center.x, center.y, center.z);

		if (isDrip(decal) && level != null) {
			// A drip: the run grows, thins and slows with age, the bead shrinks as
			// it sheds volume, and past BREAK_OFF it pinches off a falling droplet.
			long age = level.getGameTime() - decal.born();
			float f = Flow.length(age);
			StainQuad.submitDrip(context.submitNodeCollector(), poseStack, decal.size(),
					decal.rot(), light, face, f, color);

			if (f >= Flow.BREAK_OFF && DRIPPED.add(decal)) {
				Vec3 bead = center.subtract(DecalFrame.tail(face, decal.rot()).scale(f * decal.size()));
				level.addParticle(Gore.BLOOD_DROP, bead.x, bead.y, bead.z, 0.0, -0.02, 0.0);
			}
		} else if (DecalMerge.isPool(decal)) {
			// A pool floods outward from where the blood landed instead of
			// appearing at full size. The born stamp is its spreading clock;
			// legacy pools (born == 0) are already settled.
			long age = decal.born() == 0L || level == null
					? Long.MAX_VALUE
					: level.getGameTime() - decal.born();
			StainQuad.submitOnFace(context.submitNodeCollector(), poseStack, decal.variant(),
					Flow.poolSize(decal.size(), age), decal.rot(), light, face, color);
		} else {
			StainQuad.submitOnFace(context.submitNodeCollector(), poseStack, decal.variant(), decal.size(),
					decal.rot(), light, face, color);
		}

		poseStack.popPose();
	}
}
