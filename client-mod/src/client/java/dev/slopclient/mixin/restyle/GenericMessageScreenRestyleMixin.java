package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Short message screens ("Weltdaten werden vorbereitet …" before "Neue Welt erstellen",
 * "Lädt …") override the background with vanilla's panorama, so the Screen mixin never
 * sees them and the panorama flashed between two restyled screens.
 */
@Mixin(GenericMessageScreen.class)
public abstract class GenericMessageScreenRestyleMixin extends Screen {
	private GenericMessageScreenRestyleMixin(Component title) {
		super(title);
	}

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$background(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!Restyle.active()) return;
		Restyle.background(g, () -> extractBlurredBackground(g));
		ci.cancel();
	}
}
