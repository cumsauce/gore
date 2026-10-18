package dev.lume.gore.client;

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
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2fStack;

/**
 * Damage overlay: re-submits the entity's model with a red ARGB tint whose
 * intensity scales with the accumulated stain count, so a heavily bloodied
 * mob reads as wounded even at a distance. Registered alongside
 * {@link BodyStainLayer}.
 */
public class BodyDamageLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {

	private final LivingEntityRenderer<?, LivingEntityRenderState, ?> renderer;

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

		float intensity = Math.min(1.0f, stains.size() * 0.2f);
		if (intensity <= 0.0f) {
			return;
		}

		EntityModel<LivingEntityRenderState> model = getParentModel();
		if (model == null) {
			return;
		}
		model.setupAnim(state);

		Identifier texture = renderer.getTextureLocation(state);
		if (texture == null) {
			return;
		}
		RenderType renderType = RenderTypes.entityTranslucent(texture);

		int r = 255;
		int g = (int) (255 * (1.0f - intensity * 0.35f));
		int b = (int) (255 * (1.0f - intensity * 0.35f));
		int color = ARGB.color((int) (180 * intensity), r, g, b);

		poseStack.pushPose();
		collector.submitModel(model, state, poseStack, renderType, light,
				OverlayTexture.NO_OVERLAY, 0, null, color);
		poseStack.popPose();
	}
}