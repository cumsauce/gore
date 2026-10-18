package dev.lume.gore.client;

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
		List<GoreStain> stains = null;
		if (state instanceof FabricRenderState frs) {
			stains = frs.getData(StainStateKeys.STAINS);
		}
		if (stains == null || stains.isEmpty()) {
			return;
		}

		long now = Minecraft.getInstance().level != null
				? Minecraft.getInstance().level.getGameTime()
				: 0L;
		float intensity = coatIntensity(stains, now);
		if (intensity <= 0.0f) {
			return;
		}

		EntityModel<LivingEntityRenderState> model = getParentModel();
		if (model == null) {
			return;
		}

		Identifier texture = renderer.getTextureLocation(state);
		if (texture == null) {
			return;
		}

		// Culling keeps the far side of the body out of the blend: with the
		// no-cull entity type the back faces show through the coat and the
		// whole mob turns into a flat red-brown silhouette.
		RenderType renderType = RenderTypes.entityTranslucentCull(texture);

		int alpha = Math.round(MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * intensity);
		int tint = ARGB.color(alpha, COAT_R, COAT_G, COAT_B);

		poseStack.pushPose();
		collector.submitModel(model, state, poseStack, renderType, light,
				OverlayTexture.NO_OVERLAY, tint, null, 0);
		poseStack.popPose();
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
