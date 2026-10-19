package dev.daclient.mixin;

import dev.daclient.DaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: mouse movement turns the camera, not the player. */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void daclient$freelook(double dx, double dy, CallbackInfo ci) {
		if (!DaClient.ready() || (Object) this != Minecraft.getInstance().player) return;
		var freelook = DaClient.modules().freelook;
		if (freelook.looking()) {
			freelook.turn(dx, dy);
			ci.cancel();
		}
	}
}
