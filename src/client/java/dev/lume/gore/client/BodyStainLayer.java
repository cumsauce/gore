package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.GoreStain;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Draws each of an entity's blood stains as a quad welded to the part of the
 * entity's own model the wound belongs to, so the stain animates with the body.
 *
 * <p>The stain list arrives through {@link StainStateKeys#STAINS}, filled once
 * per frame during level extraction (see {@code GoreClient}); an unbloodied
 * entity sees a null/empty list and costs nothing. The quad itself is
 * {@link StainQuad}, shared with the wall decals — orientation, z-fight offset,
 * roll, scale and submission all live there.
 */
public class BodyStainLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	public BodyStainLayer(LivingEntityRenderer<?, ?, ?> parent) {
		super(asParent(parent));
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> asParent(
			LivingEntityRenderer<?, ?, ?> renderer) {
		// Every living renderer's own state type is some LivingEntityRenderState
		// subclass, and the layer reads only fields common to all of them, so the
		// narrowing cast is safe at runtime (the same rawness Fabric's own
		// registration helper performs on the other end).
		return (RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>>) renderer;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
			LivingEntityRenderState state, float limbSwing, float limbSwingAmount) {
		List<GoreStain> stains = ((FabricRenderState) state).getData(StainStateKeys.STAINS);

		if (stains == null || stains.isEmpty()) {
			return;
		}

		// setupRotations skips the yaw basis entirely while sleeping, so the
		// world-to-model conversion below would be wrong; skip the few frames.
		// A sleeping entity still wears its wounds. Only the *pose* is special
		// (handled by setupRotations, which the caller has already applied), so
		// sleeping is deliberately not skipped here.

		EntityModel<LivingEntityRenderState> model = getParentModel();

		for (GoreStain stain : stains) {
			submitStain(poseStack, collector, light, state, model, stain);
		}
	}

	private static void submitStain(PoseStack poseStack, SubmitNodeCollector collector, int light,
			LivingEntityRenderState state, EntityModel<LivingEntityRenderState> model, GoreStain stain) {
		Vector3f travel = new Vector3f(stain.dirX(), stain.dirY(), stain.dirZ());

		if (travel.lengthSquared() < 1.0E-8F) {
			return;
		}

		travel.normalize();
		StainAnchor.Region region = StainAnchor.regionFor(stain.sourceType(), new Vec3(travel));

		// The stored bytes are the blood's travel direction (see Bleeding and
		// StainWounds). The wound surface faces back along it — the same
		// convention the wall decals use, whose struck face's normal points at
		// where the blood came from. Ground sources (fire, fall) carry the
		// no-attacker fallback of "down", and their wound faces down along that
		// spray, so LEG and FOOT keep the raw direction instead.
		Vector3f outwardWorld = region == StainAnchor.Region.LEG || region == StainAnchor.Region.FOOT
				? new Vector3f(travel)
				: new Vector3f(travel).negate();

		// LivingEntityRenderer.setupRotations rolls the pose by (180 - bodyRot)
		// about Y before the layers run, so a world direction enters model space
		// through the inverse roll.
		Vector3f outward = new Vector3f(outwardWorld)
				.rotateY((float) Math.toRadians(state.bodyRot - 180.0F));

		Optional<ModelPart> anchor = StainAnchor.resolve(model, region, new Vec3(outward.x(), outward.y(), outward.z()));

		if (anchor.isEmpty()) {
			// No usable part: draw nothing rather than park blood in mid-air.
			return;
		}

		poseStack.pushPose();
		ModelPart root = model.root();
		ModelPart part = anchor.get();

		if (part != root) {
			root.translateAndRotate(poseStack);
		}

		// Recomputed from the part's current (animated) pose each frame, so the
		// quad rides the body's transform without depending on it being rigid.
		float reach = StainAnchor.supportAlong(poseStack, part, outward);

		if (Float.isFinite(reach)) {
			poseStack.translate(outward.x() * reach, outward.y() * reach, outward.z() * reach);
			StainQuad.submit(collector, poseStack, stain.variant(), stain.size(), stain.rot(), light, outward);
		}

		poseStack.popPose();
	}
}
