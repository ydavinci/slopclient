package dev.daclient.mixin;

import dev.daclient.DaClient;
import dev.daclient.gui.DaTitleScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the vanilla title screen for the DaClient one. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen daclient$titleScreen(Screen screen) {
		if (screen != null && screen.getClass() == TitleScreen.class && DaClient.ready() && DaClient.modules().titleScreen.enabled()) {
			return new DaTitleScreen();
		}
		return screen;
	}
}
