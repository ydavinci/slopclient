package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nametags: vanilla never shows the camera player's name; this does in third person. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void slopclient$ownName(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() || !SlopClient.ready()) return;
		var tags = SlopClient.modules().nametags;
		Minecraft mc = Minecraft.getInstance();
		if (tags.active() && tags.own.on() && entity == mc.player && !mc.options.getCameraType().isFirstPerson()) {
			cir.setReturnValue(true);
		}
	}
}
