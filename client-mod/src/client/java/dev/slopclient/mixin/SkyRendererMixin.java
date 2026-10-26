package dev.slopclient.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.slopclient.SlopClient;
import dev.slopclient.render.SlopSky;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skybox module: the launcher's shader instead of vanilla's sky, sun, moon and stars. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	@Shadow
	@Final
	private RenderTarget renderTarget;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void slopclient$sky(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
		if (!SlopClient.ready() || !SlopClient.modules().skybox.replaces(state.skybox)) return;
		if (SlopSky.render(renderTarget, fog)) ci.cancel();
	}
}
