package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import dev.slopclient.gui.SlopTitleScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the vanilla title screen for the SlopClient one. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen slopclient$titleScreen(Screen screen) {
		if (screen != null && screen.getClass() == TitleScreen.class && SlopClient.ready() && SlopClient.modules().titleScreen.enabled()) {
			return new SlopTitleScreen();
		}
		return screen;
	}
}
