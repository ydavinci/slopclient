package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Glow module: an outline in the chosen colour around players you can see. Glass module: your figure's glow. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
	private void slopclient$glow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (!SlopClient.ready()) return;
		var glow = SlopClient.modules().glow;
		if (glow.active() && glow.shouldGlow(entity)) state.outlineColor = glow.color(entity);
		else if (SlopClient.modules().glass.glows(entity)) state.outlineColor = SlopClient.modules().glass.glowColor();
	}
}
