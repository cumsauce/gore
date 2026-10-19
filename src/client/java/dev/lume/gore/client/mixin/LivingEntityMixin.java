package dev.lume.gore.client.mixin;

import dev.lume.gore.client.ClientBleeding;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's view of a hit: the server's damage event, with the source but
 * not the amount. Only used on servers without the mod (see ClientBleeding).
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void gore$onDamageEvent(DamageSource source, CallbackInfo ci) {
		ClientBleeding.onDamageEvent((LivingEntity) (Object) this, source);
	}
}
