package dev.daclient.mixin;

import dev.daclient.DaClient;
import dev.daclient.hud.Cps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts clicks for the CPS display and lets the mouse wheel change the zoom level. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void daclient$countClick(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		// GLFW_PRESS, only while playing (not in menus).
		if (action == 1 && Minecraft.getInstance().gui.screen() == null) Cps.click(info.button());
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void daclient$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (DaClient.ready() && DaClient.modules().zoom.onScroll(yOffset)) ci.cancel();
	}
}
