package dev.daclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.daclient.DaClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom divides the field of view; freelook replaces the camera angles. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void daclient$zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!DaClient.ready()) return;
		double factor = DaClient.modules().zoom.factor();
		if (factor != 1) cir.setReturnValue((float) (cir.getReturnValue() / factor));
	}

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void daclient$freelook(Camera camera, float yRot, float xRot, Operation<Void> original) {
		var freelook = DaClient.ready() ? DaClient.modules().freelook : null;
		if (freelook != null && freelook.looking()) original.call(camera, freelook.yaw(), freelook.pitch());
		else original.call(camera, yRot, xRot);
	}
}
