package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skybox module: distant terrain fades into the sky's dark violet instead of vanilla's blue. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {
	@Inject(method = "getBaseColor", at = @At("HEAD"), cancellable = true)
	private void slopclient$fogColor(ClientLevel level, Camera camera, int renderDistance, float partialTick, CallbackInfoReturnable<Vector3fc> cir) {
		if (!SlopClient.ready()) return;
		var sky = SlopClient.modules().skybox;
		if (sky.fog.on() && sky.replaces(level.dimensionType().skybox())) cir.setReturnValue(sky.fogColor());
	}
}
