package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Backgrounds of restyled screens: the launcher shader or the blurred world, no dirt panels. */
@Mixin(Screen.class)
public abstract class ScreenRestyleMixin {
	@Shadow
	protected abstract void extractBlurredBackground(GuiGraphicsExtractor g);

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$background(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!Restyle.active() || (Object) this != net.minecraft.client.Minecraft.getInstance().gui.screen()) return;
		Restyle.background(g, () -> extractBlurredBackground(g));
		ci.cancel();
	}

	@Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$menuBackground(GuiGraphicsExtractor g, int x, int y, int w, int h, CallbackInfo ci) {
		if (Restyle.active()) ci.cancel();
	}
}
