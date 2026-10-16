package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.lume.gore.DecalFrame;
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
	private static final float OFFSET = 0.006f;
	private static final int CELL = 64;
	/** Box size in model units; 64 units is four blocks. */
	private static final float UNITS = CELL;
	private static final float BLOCKS = UNITS / 16.0f;
	/**
	 * Side length of {@code GoreClient.BLOOD_ATLAS} in texels. {@code ModelPart.Cube}'s
	 * trailing {@code uScale}/{@code vScale} arguments are the UV divisors (the texture's
	 * dimensions), not multipliers: the baked polygon stores {@code (texU + offset) / uScale}.
	 * Passing 1 left the UVs in texel units, so the quad repeat-wrapped the whole atlas
	 * into a uniform red wash instead of showing one cell.
	 */
	private static final float ATLAS_SIZE = 256.0f;

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
					0.0f, 0.0f, 0.0f, false, ATLAS_SIZE, ATLAS_SIZE, Set.of(Direction.NORTH));
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

	/**
	 * Like {@link #submit} but the orientation comes from the explicit face
	 * basis in {@link DecalFrame}: the quad's tail (model −Y) points along
	 * {@code DecalFrame.tail(face, rot)} and its front (model −Z) along the
	 * face's outward normal, so the tail follows the droplet's travel on every
	 * face. {@code color} is passed to the tinted
	 * {@code submitModelPart(..., UvMapping, int)} overload;
	 * {@code 0xFFFFFFFF} means untinted.
	 */
	public static void submitOnFace(SubmitNodeCollector collector, PoseStack poseStack,
			int variant, float size, float rot, int light, Direction face, int color) {
		init();

		Quaternionf orientation = DecalFrame.orientation(face, rot);
		float scale = size / BLOCKS;

		poseStack.pushPose();
		poseStack.translate(face.getStepX() * OFFSET, face.getStepY() * OFFSET, face.getStepZ() * OFFSET);
		poseStack.rotate(orientation);
		poseStack.scale(scale, scale, scale);
		collector.submitModelPart(VARIANTS[variant & 15], poseStack, renderType,
				light, OverlayTexture.NO_OVERLAY, null, color);
		poseStack.popPose();
	}

	/**
	 * Like {@link #submit} but a drip: the run's top edge stays anchored at the
	 * pose origin while the run grows downward as {@code progress} goes 0..1, the
	 * bulb at its tip. At progress 0 nothing is drawn; at 1 the run is {@code size}
	 * long.
	 */
	public static void submitDrip(SubmitNodeCollector collector, PoseStack poseStack,
			int variant, float size, float rot, int light, Vector3f outward, float progress) {
		init();

		Quaternionf orientation = new Quaternionf().rotateTo(MODEL_FRONT, outward);
		float scale = size / BLOCKS;

		poseStack.pushPose();
		poseStack.translate(outward.x() * OFFSET, outward.y() * OFFSET, outward.z() * OFFSET);
		poseStack.rotate(orientation);
		poseStack.rotateDegrees(Axis.ZP, rot);
		// In cube-block units the quad spans Y in [-2, 2]. Drop it so its top edge
		// (+2) sits on the origin, then grow downward about that edge.
		poseStack.translate(0.0, -BLOCKS / 2.0, 0.0);
		poseStack.scale(1.0f, progress, 1.0f);
		poseStack.scale(scale, scale, scale);
		collector.submitModelPart(VARIANTS[variant & 15], poseStack, renderType,
				light, OverlayTexture.NO_OVERLAY, null);
		poseStack.popPose();
	}
}
