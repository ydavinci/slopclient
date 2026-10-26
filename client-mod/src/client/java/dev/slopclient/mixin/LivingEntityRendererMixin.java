package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nametags: vanilla never shows the camera player's name; this does in third person.
 * Glass module: players drawn translucent with the glass skin.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
	private void slopclient$glass(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, boolean bodyVisible, boolean translucent, boolean glowing,
		CallbackInfoReturnable<net.minecraft.client.renderer.rendertype.RenderType> cir) {
		if (!bodyVisible || !SlopClient.ready() || !(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar)) return;
		if (!SlopClient.modules().glass.applies(avatar.id)) return;
		boolean slim = avatar.skin != null && avatar.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		// Emissive: glass catches light, it does not sit in the shade.
		cir.setReturnValue(net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucentEmissive(
			slim ? dev.slopclient.module.Visuals.Glass.SLIM : dev.slopclient.module.Visuals.Glass.WIDE));
	}

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
