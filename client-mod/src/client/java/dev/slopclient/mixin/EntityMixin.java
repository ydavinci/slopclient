package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
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
	private void slopclient$freelook(double dx, double dy, CallbackInfo ci) {
		if (!SlopClient.ready() || (Object) this != Minecraft.getInstance().player) return;
		var freelook = SlopClient.modules().freelook;
		if (freelook.looking()) {
			freelook.turn(dx, dy);
			ci.cancel();
		}
	}
}
