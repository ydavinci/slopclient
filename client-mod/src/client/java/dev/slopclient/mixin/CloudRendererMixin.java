package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skybox module: vanilla's white clouds would sit in front of the shader sky. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At("HEAD"), cancellable = true)
	private void slopclient$hideClouds(CallbackInfo ci) {
		if (!SlopClient.ready()) return;
		var sky = SlopClient.modules().skybox;
		var level = Minecraft.getInstance().level;
		if (sky.hideClouds.on() && level != null && sky.replaces(level.dimensionType().skybox())) ci.cancel();
	}
}
