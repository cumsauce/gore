package dev.lume.gore.client.mixin;

import dev.lume.gore.client.LimbRender;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the one call that draws a submitted model, after its {@code setupAnim}
 * has posed it: {@link LimbRender} shortens or hides severed limbs and, for the
 * mod's own per-limb passes, draws only that limb, then puts every part back so
 * the next creature sharing the model is whole.
 */
@Mixin(ModelFeatureRenderer.class)
abstract class ModelFeatureRendererMixin {
	private static final String RENDER = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V";

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = RENDER))
	private void gore$beforeRender(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		LimbRender.before(submit.model(), submit.state(), submit.tintedColor());
	}

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = RENDER, shift = At.Shift.AFTER))
	private void gore$afterRender(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		LimbRender.after();
	}
}
