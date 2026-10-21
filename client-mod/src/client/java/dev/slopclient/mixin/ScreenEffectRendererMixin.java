package dev.slopclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.slopclient.SlopClient;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low Fire: lowers the burning overlay while it is drawn. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"))
	private static void slopclient$lowerFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.pushPose();
		if (SlopClient.ready() && SlopClient.modules().lowFire.active()) {
			pose.translate(0, -SlopClient.modules().lowFire.offset.get().floatValue(), 0);
		}
	}

	@Inject(method = "submitFire", at = @At("RETURN"))
	private static void slopclient$restoreFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.popPose();
	}
}
