package dev.daclient.mixin;

import dev.daclient.DaClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom: divides the field of view while the zoom key is held. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void daclient$zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!DaClient.ready()) return;
		double factor = DaClient.modules().zoom.factor();
		if (factor != 1) cir.setReturnValue((float) (cir.getReturnValue() / factor));
	}
}
