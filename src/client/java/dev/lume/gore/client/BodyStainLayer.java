package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.BloodAppearance;
import dev.lume.gore.CubeProjection;
import dev.lume.gore.Flow;
import dev.lume.gore.GoreStain;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.Minecraft;
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

		// A sleeping entity still wears its wounds, so sleeping is deliberately not
		// special-cased here. Only the entity's *pose* is unusual, and the caller
		// has already applied that through setupRotations, which the world-to-model
		// conversion below accounts for.

		EntityModel<LivingEntityRenderState> model = getParentModel();

		for (int i = 0; i < stains.size(); i++) {
			submitStain(poseStack, collector, light, state, model, stains.get(i), i);
		}
	}

	private static void submitStain(PoseStack poseStack, SubmitNodeCollector collector, int light,
			LivingEntityRenderState state, EntityModel<LivingEntityRenderState> model, GoreStain stain, int index) {
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

		ModelPart root = model.root();
		ModelPart part = anchor.get();

		// The stain is welded: its placement is a fixed direction *in the part's own
		// frame*, chosen once from the wound. The part's live animation then carries
		// it — a head yawing swings the stain round with it, a limb swinging takes
		// its stain along — because the direction never has the animation divided
		// back out of it. Deriving it per frame from the world direction instead
		// would cancel exactly the motion we want.
		//
		// `outward` is in the entity's root frame, so it is carried into the part's
		// frame through the part's *resting* pose (its initial pose, before
		// animation). That is the same frame `translateAndRotate` below builds on
		// top of, so a part at rest lands the stain exactly where the wound was.
		Vector3f local = StainAnchor.restingDirection(part, outward);

		if (local == null) {
			return;
		}

		poseStack.pushPose();

		if (part != root) {
			root.translateAndRotate(poseStack);
		}

		part.translateAndRotate(poseStack);

		// The face of the part the wound landed on. This is the cube surface
		// itself, not the part's far extent along the wound direction, so the
		// stain sits flat on the body instead of on a bounding volume around it.
		// `projectOnto` measures in the part's own frame without pushing anything,
		// so the point it returns is in exactly the frame the next translate uses.
		Optional<CubeProjection.Result> face = StainAnchor.projectOnto(part, local);

		if (face.isPresent()) {
			float[] point = face.get().point();
			float[] normal = face.get().normal();

			poseStack.translate(point[0], point[1], point[2]);

			// GoreStain has no born stamp and the payload is frozen, so body
			// stains cannot dry yet — they always render wet. The seed comes
			// from the stain's own fields plus its index, so two identical
			// rolls on one entity still read as two different stains.
			long seed = BloodAppearance.seedFor(stain.sourceType(), stain.dirX(), stain.dirY(), stain.dirZ(),
					stain.size(), stain.rot(), stain.variant(), index);
			int color = BloodAppearance.tint(seed, 0.0f);
			Vector3f normalVec = new Vector3f(normal[0], normal[1], normal[2]);
			StainQuad.submit(collector, poseStack, stain.variant(), stain.size(), stain.rot(), light, normalVec, color);

			// M5: wound lacerations (variant >= 16) grow a body drip downward
			// from the wound, driven by Flow like wall drips.
			if (stain.variant() >= 16) {
				long received = 0L;
				if (state instanceof FabricRenderState frs) {
					Long rt = frs.getData(StainStateKeys.RECEIVED_TICK);
					if (rt != null) received = rt;
				}
				Minecraft mc = Minecraft.getInstance();
				long now = (mc.level != null) ? mc.level.getGameTime() : 0L;
				long age = now - received;
				float f = Flow.length(age);
				if (f > 0.0f) {
					Vector3f gravity = new Vector3f(0.0f, -1.0f, 0.0f);
					float gdot = gravity.dot(normalVec);
					Vector3f downward = new Vector3f(
							gravity.x() - normalVec.x() * gdot,
							gravity.y() - normalVec.y() * gdot,
							gravity.z() - normalVec.z() * gdot);
					if (downward.lengthSquared() < 1.0e-8f) {
						downward.set(1.0f, 0.0f, 0.0f);
					} else {
						downward.normalize();
					}
					StainQuad.submitBodyDrip(collector, poseStack, stain.size(), light,
							normalVec, downward, f, color);
				}
			}
		}

		poseStack.popPose();
	}
}
