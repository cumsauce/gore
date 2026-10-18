package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.BloodAppearance;
import dev.lume.gore.CubeProjection;
import dev.lume.gore.Drying;
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
 *
 * <p>A wound cell (atlas 16–19) sits at the face centre. Impact spatter
 * (cells 0–11) is scattered around it on a stable per-stain point of the face,
 * derived from the stain's own identity, so one hit reads as a gash plus the
 * spray that came off it rather than as several stains stacked in the same
 * spot. Each stain's point is a pure function of its fields, so it never
 * moves between frames or as more stains are added.
 */
public class BodyStainLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	/**
	 * How far spatter tries to land from the face centre, in model units (16 per
	 * block). It is only a wish: the per-face clamp below keeps every mark on the
	 * part regardless of how small the creature is.
	 */
	private static final float SPATTER_SPREAD = 6.0f;

	/**
	 * How far the wound itself wanders from the face centre, in model units.
	 * Smaller than the spatter: the gash should stay recognisably where the hit
	 * landed, while repeated hits must not paint over the same few pixels.
	 */
	private static final float WOUND_SPREAD = 2.4f;

	/** A wound cell is the direct hit; cells below this are spatter. */
	private static final int WOUND_BASE = 16;

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
			submitStain(poseStack, collector, light, state, model, stains.get(i));
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

		// The recorded hit point, mapped from the root frame into this part's
		// own frame (model units, 16 per block). When the stain has no hit
		// point (older stains, or a degenerate hit dead-centre on the origin)
		// this stays null and the renderer falls back to inferring the face.
		Vector3f hitLocal = null;
		if (stain.hasHit()) {
			float step = 1.0f / 32.0f;
			Vector3f hitRoot = new Vector3f(
					stain.hitX() * step, stain.hitY() * step, stain.hitZ() * step);
			hitLocal = StainAnchor.restingPosition(part, hitRoot);
			hitLocal.mul(16.0f);
		}

		poseStack.pushPose();

		if (part != root) {
			root.translateAndRotate(poseStack);
		}

		part.translateAndRotate(poseStack);

		// Choose the face the wound landed on by the outward direction the
		// wound faces, using the hit point only to break ties between coplanar
		// surfaces. This keeps a stain on the skin instead of on a bounding
		// volume, and lands it at the hit point when one is recorded.
		List<CubeProjection.Box> boxes = StainAnchor.boxesOf(part);
		Optional<CubeProjection.Result> face = CubeProjection.projectAt(
				boxes,
				new float[] {local.x(), local.y(), local.z()},
				hitLocal == null ? null : new float[] {hitLocal.x(), hitLocal.y(), hitLocal.z()});

		if (face.isEmpty()) {
			poseStack.popPose();
			return;
		}

		float[] point = hitLocal == null
				? face.get().point()
				: new float[] {hitLocal.x(), hitLocal.y(), hitLocal.z()};
		float[] normal = face.get().normal();
		Vector3f normalVec = new Vector3f(normal[0], normal[1], normal[2]);
		CubeProjection.Box box = face.get().box();
		Vector3f[] tangent = faceTangent(normalVec);
		float halfU = CubeProjection.extentAlong(box, tangent[0].x(), tangent[0].y(), tangent[0].z());
		float halfV = CubeProjection.extentAlong(box, tangent[1].x(), tangent[1].y(), tangent[1].z());
		float minHalf = Math.min(halfU, halfV);

		// The stain's quad is a square of `size` blocks; keeping its half
		// diagonal inside the face is what stops a wrong-sized mark from
		// hanging over the silhouette. Sizes are blocks, extents model units
		// (16 per block); a truly oversized wound is capped rather than
		// allowed to cover more body than the part has.
		float size = Math.min(stain.size(), 2.0f * minHalf / 16.0f);
		float halfSize = size * 8.0f;
		// A square quad rotated by the stain's roll reaches halfSize·√2 at its
		// corners, so that is how far its centre must stay from the edge.
		float room = Math.max(0.0f, minHalf - halfSize * 1.4142135f);

		Vector3f centre;
		if (hitLocal != null) {
			centre = new Vector3f(hitLocal);
		} else {
			// The face centre, in the part's own frame: the box centre pushed out
			// along the normal to the face plane.
			float halfN = CubeProjection.extentAlong(box, normalVec.x(), normalVec.y(), normalVec.z());
			centre = new Vector3f(
					(box.minX() + box.maxX()) * 0.5f + normalVec.x() * halfN,
					(box.minY() + box.maxY()) * 0.5f + normalVec.y() * halfN,
					(box.minZ() + box.maxZ()) * 0.5f + normalVec.z() * halfN);
		}

		float du = 0.0f;
		float dv = 0.0f;

		// GoreStain stamps the hit, so the seed stays a pure function of the
		// stain's identity — every client derives the same look, and it never
		// shimmers between frames.
		long seed = BloodAppearance.seedFor(stain.sourceType(), stain.dirX(), stain.dirY(), stain.dirZ(),
				stain.size(), stain.rot(), stain.variant(), stain.born());
		Minecraft mc = Minecraft.getInstance();
		long now = (mc.level != null) ? mc.level.getGameTime() : 0L;
		int color = BloodAppearance.tint(seed, Drying.dryness(stain.born(), now, Drying.SCALE));

		// Spatter is scattered around the wound on a stable point of its own.
		// The gash itself does not move when its point is exact; when it is
		// inferred it drifts within the face and sits a little above centre,
		// because the centre of a quadruped's body box is the gap between its
		// front legs and a chest-high cut is what the eye reads as a wound.
		if (stain.variant() < WOUND_BASE) {
			float[] disk = BloodAppearance.unitDisk(seed, 7);
			float spread = Math.min(SPATTER_SPREAD, room) * (0.35f + 0.65f * disk[2]);
			du += disk[0] * spread;
			dv += disk[1] * spread;
		} else {
			dv += Math.min(WOUND_SPREAD, room);
		}

		du = Math.max(-room, Math.min(room, du));
		dv = Math.max(-room, Math.min(room, dv));

		Vector3f shift = new Vector3f(centre)
				.add(new Vector3f(tangent[0]).mul(du))
				.add(new Vector3f(tangent[1]).mul(dv));

		poseStack.translate(shift.x, shift.y, shift.z);

		// A hair of depth separation between coplanar stains, so a spatter
		// blob and the gash under it do not swap draw order with the camera.
		// Model units: 0.06 is under four millimetres.
		float bias = 0.06f * (stain.variant() & 3);
		poseStack.translate(normalVec.x() * bias, normalVec.y() * bias, normalVec.z() * bias);

		StainQuad.submit(collector, poseStack, stain.variant(), size, stain.rot(), light, normalVec, color);

		// Wound lacerations grow a body drip downward from the wound, driven by
		// Flow like wall drips. A wound keeps feeding the run, so it travels
		// BODY_SPEED times faster than a wall run left by a single splat.
		// A face pointing straight up (the top of a head or a back) does not
		// run at all: blood pools there.
		if (stain.variant() >= WOUND_BASE && Math.abs(normalVec.y()) < 0.9f) {
			long age = stain.born() == 0L ? Flow.T_TICKS : (now - stain.born()) * Flow.BODY_SPEED;
			float f = Flow.length(age);

			if (f > 0.0f) {
				Vector3f gravity = new Vector3f(0.0f, -1.0f, 0.0f);
				float gdot = gravity.dot(normalVec);
				Vector3f downward = new Vector3f(
						gravity.x() - normalVec.x() * gdot,
						gravity.y() - normalVec.y() * gdot,
						gravity.z() - normalVec.z() * gdot);
				if (downward.lengthSquared() < 1.0E-8f) {
					downward.set(1.0f, 0.0f, 0.0f);
				} else {
					downward.normalize();
				}

				// Keep the run on the part: a run longer than the distance
				// from the stain to the face's lower edge, or its bead,
				// dangles in the air off the body. The room left is measured
				// along gravity, from where the stain actually sits.
				float downExtent = CubeProjection.extentAlong(box, downward.x(), downward.y(), downward.z());
				float usedDown = shift.dot(downward);
				float available = downExtent - Math.max(0.0f, usedDown) - halfSize - 1.5f;
				float maxRun = available / Math.max(1.0e-4f, size * 16.0f);
				f = Math.min(f, maxRun);

				if (f > 0.02f) {
					StainQuad.submitBodyDrip(collector, poseStack, size, light,
							normalVec, downward, f, color);
				}
			}
		}

		poseStack.popPose();
	}

	/**
	 * Two unit vectors spanning the face plane, from an axis-aligned model-space
	 * normal. The fallback axis keeps a horizontal (floor/ceiling) face from
	 * degenerating into a zero cross product.
	 */
	private static Vector3f[] faceTangent(Vector3f normal) {
		Vector3f helper = Math.abs(normal.y()) > 0.9f
				? new Vector3f(1.0f, 0.0f, 0.0f)
				: new Vector3f(0.0f, 1.0f, 0.0f);
		Vector3f t1 = new Vector3f(normal).cross(helper).normalize();
		Vector3f t2 = new Vector3f(normal).cross(t1).normalize();
		return new Vector3f[] {t1, t2};
	}
}
