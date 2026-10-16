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
import org.joml.Matrix4f;
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

		// A sleeping entity still wears its wounds, so sleeping is deliberately not
		// special-cased here. Only the entity's *pose* is unusual, and the caller
		// has already applied that through setupRotations, which the world-to-model
		// conversion below accounts for.

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

		// Everything below happens in the root's frame, which is the frame the
		// stain's direction was converted into. `supportAlong` walks the part with
		// ModelPart.visit, which folds the part's own animated transform in, so the
		// reach it returns is the part's outward face in *this* frame — the same
		// frame the quad is placed in, which is what keeps the two consistent.
		float reach = StainAnchor.supportAlong(poseStack, part, outward);

		if (!Float.isFinite(reach)) {
			// No usable part: draw nothing rather than park blood in mid-air.
			poseStack.popPose();
			return;
		}

		// The quad rides the part's animation by facing the part's *animated*
		// outward direction: a head wound turns with the head, a limb wound swings
		// with the limb. That direction is the part's own transform applied to the
		// wound direction, taken from the part rather than assumed.
		Vector3f animated = animatedDirection(poseStack, part, outward);

		if (animated == null) {
			poseStack.popPose();
			return;
		}

		poseStack.translate(outward.x() * reach, outward.y() * reach, outward.z() * reach);
		StainQuad.submit(collector, poseStack, stain.variant(), stain.size(), stain.rot(), light, animated);
		poseStack.popPose();
	}

	/**
	 * Returns the part's own animated transform applied to {@code direction},
	 * expressed in the space the pose stack currently defines. That is the
	 * direction the wound faces once the part (a head, an arm) has been posed, so
	 * a quad submitted with it turns and swings with the part.
	 *
	 * <p>The transform is read by visiting the part with an untouched stack and
	 * comparing where the visit puts it against where the stack already is — i.e.
	 * the part's contribution isolated from its ancestors'. Returns null when the
	 * part contributes no usable rotation.
	 */
	private static Vector3f animatedDirection(PoseStack poseStack, ModelPart part, Vector3f direction) {
		Matrix4f outer = new Matrix4f(poseStack.last().pose());
		Matrix4f[] first = new Matrix4f[1];

		// The first cube the part reports carries the part's own transform; a part
		// with no cubes of its own has nothing to pose, so keep the raw direction.
		part.visit(new PoseStack(), (pose, name, index, cube) -> {
			if (first[0] == null) {
				first[0] = pose.pose();
			}
		});

		if (first[0] == null) {
			return new Vector3f(direction);
		}

		// part = outer⁻¹ · (visit matrix). Only its orientation matters.
		Matrix4f local = new Matrix4f(outer).invert().mul(first[0], new Matrix4f());
		Vector3f result = local.transformDirection(new Vector3f(direction));

		if (result.lengthSquared() < 1.0E-8F) {
			return null;
		}

		return result.normalize();
	}
}
