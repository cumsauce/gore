package dev.lume.gore.client;

import dev.lume.gore.Limb;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import dev.lume.gore.Drying;
import dev.lume.gore.GoreStain;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Blood coat: re-submits the entity's model, multiplied towards a dark blood
 * red and blended over the skin, with an opacity that grows with the number of
 * wounds. It is what makes a mob read as bloody at a glance rather than as a
 * clean animal with a few red specks; the wound and spatter layers supply the
 * detail on top of it.
 *
 * <p>{@code submitModel} takes its tint and its outline colour as two separate
 * trailing arguments. They are easy to swap, and swapping them is not a subtle
 * bug: the entity is drawn untinted and the whole silhouette is pushed through
 * the glow-outline pass, which is why attacked mobs used to be outlined in
 * white. The tint goes in the seventh argument, the outline stays zero.
 */
public class BodyDamageLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {

	private final LivingEntityRenderer<?, LivingEntityRenderState, ?> renderer;

	/** Opacity at which one wound starts to read, and the ceiling with many. */
	private static final int MIN_ALPHA = 55;
	private static final int MAX_ALPHA = 195;

	/**
	 * Wounds at which the coat reaches {@link #MAX_ALPHA}. A hit leaves about
	 * five stains, so a mob has to be hit more than twice before it is soaked.
	 */
	private static final float SATURATION = 12.0f;
	/** Fresh stains at which one part's coat is full: a hit leaves about five. */
	private static final float LIMB_SATURATION = 7.0f;
	/** Quantisation step of a stain's hit offset (see StainWounds). */
	private static final float HIT_STEP = 1.0f / 32.0f;

	/** The tint is a multiplier on the skin texture, so it stays dark: blood on skin, not paint. */
	private static final int COAT_R = 116;
	private static final int COAT_G = 24;
	private static final int COAT_B = 18;

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> asParent(
			LivingEntityRenderer<?, ?, ?> r) {
		return (RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>>) r;
	}

	public BodyDamageLayer(LivingEntityRenderer<?, ?, ?> parent) {
		super(asParent(parent));
		this.renderer = (LivingEntityRenderer<?, LivingEntityRenderState, ?>) parent;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
			LivingEntityRenderState state, float limbSwing, float limbSwingAmount) {
		if (!(state instanceof FabricRenderState frs)) {
			return;
		}

		float[] coat = frs.getData(LimbRender.COAT);
		Integer severed = frs.getData(LimbRender.SEVERED);
		if (coat == null && (severed == null || severed == 0)) {
			return;
		}

		EntityModel<LivingEntityRenderState> model = getParentModel();
		Identifier texture = model == null ? null : renderer.getTextureLocation(state);
		if (texture == null) {
			return;
		}

		// Culling keeps the far side of the body out of the blend: with the
		// no-cull entity type the back faces show through the coat and the
		// whole mob turns into a flat red-brown silhouette.
		RenderType renderType = RenderTypes.entityTranslucentCull(texture);

		// One pass per bloodied part, each drawing that part alone (see
		// LimbRender): a slashed arm is a bloody arm, not a pink animal.
		if (coat != null) {
			for (Limb limb : Limb.values()) {
				float intensity = coat[limb.ordinal()];
				if (intensity <= 0.0f) {
					continue;
				}
				int alpha = Math.round(MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * intensity);
				collector.submitModel(model, state, poseStack, renderType, light,
						OverlayTexture.NO_OVERLAY, LimbRender.coatTint(limb, alpha), null, 0);
			}
		}

		// The raw end of every cut-off arm or leg.
		if (severed != null && severed != 0) {
			for (Limb limb : Limb.values()) {
				if (limb != Limb.HEAD && limb != Limb.TORSO && (severed & limb.bit()) != 0) {
					collector.submitModel(model, state, poseStack, renderType, light,
							OverlayTexture.NO_OVERLAY, LimbRender.stumpTint(limb), null, 0);
				}
			}
		}
	}

	/**
	 * The coat for each part, 0..1 by {@link Limb#ordinal()}: the wetness of
	 * the stains whose hit point lands on that part. A part saturates with a
	 * few fresh wounds; stains from before hit points existed count on the
	 * torso.
	 */
	public static float[] limbCoat(List<GoreStain> stains, Entity entity, long now) {
		float[] coat = new float[Limb.values().length];
		if (stains.isEmpty()) {
			return coat;
		}
		float yRot = entity.getYRot();
		float width = entity.getBbWidth();
		float height = entity.getBbHeight();
		float eye = entity.getEyeHeight();
		for (GoreStain stain : stains) {
			Limb limb = Limb.TORSO;
			if (stain.hasHit()) {
				// Stains store the hit in the body frame (Bleeding.inBodyFrame:
				// rotateY(yRot − 180°)); undo that to classify it like the server.
				Vector3f world = new Vector3f(stain.hitX() * HIT_STEP, stain.hitY() * HIT_STEP, stain.hitZ() * HIT_STEP)
						.rotateY((float) -Math.toRadians(yRot - 180.0f));
				limb = Limb.classify(new Vec3(world.x, world.y, world.z), yRot, width, height, eye);
			}
			coat[limb.ordinal()] += 1.0f - Drying.dryness(stain.born(), now, Drying.SCALE);
		}
		for (int i = 0; i < coat.length; i++) {
			coat[i] = Math.min(1.0f, coat[i] / LIMB_SATURATION);
		}
		return coat;
	}

	/**
	 * Coat intensity from the stains' wetness, not just their count: a fresh
	 * stain contributes fully ({@code 1 - dryness == 1}), a fully dry stain
	 * nothing ({@code 1 - dryness == 0}), so an old brown wound stops reading
	 * as a wet red wash.
	 *
	 * @param stains the entity's current stains
	 * @param now    current game time
	 * @return {@code 0f} … {@code 1f}
	 */
	public static float coatIntensity(List<GoreStain> stains, long now) {
		float wetnessSum = 0f;
		for (GoreStain stain : stains) {
			wetnessSum += 1.0f - Drying.dryness(stain.born(), now, Drying.SCALE);
		}
		return Math.min(1.0f, wetnessSum / SATURATION);
	}
}
