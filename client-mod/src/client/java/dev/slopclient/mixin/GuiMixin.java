package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import dev.slopclient.gui.SlopTitleScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps the vanilla title screen for the SlopClient one, on both ways it can appear:
 * someone calls {@code setScreen(new TitleScreen())}, or {@code setScreen(null)} without a
 * world, where vanilla builds the title screen itself (e.g. "Abbrechen" in "Neue Welt
 * erstellen" opened straight from Einzelspieler without any worlds, issue #11).
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen slopclient$titleScreen(Screen screen) {
		return replace(screen);
	}

	/**
	 * Right after vanilla stored its own `new TitleScreen()` into the argument (the first store
	 * to it). `require = 0`: should a future version move it, the game still starts.
	 */
	@ModifyVariable(method = "setScreen", at = @At(value = "STORE", ordinal = 0), argsOnly = true, require = 0)
	private Screen slopclient$builtTitleScreen(Screen screen) {
		return replace(screen);
	}

	private static Screen replace(Screen screen) {
		if (screen != null && screen.getClass() == TitleScreen.class && SlopClient.ready() && SlopClient.modules().titleScreen.enabled()) {
			return new SlopTitleScreen();
		}
		return screen;
	}
}
