package dev.slopclient.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen "Einzelspieler" returns to, so the world page can take its place. */
@Mixin(SelectWorldScreen.class)
public interface SelectWorldScreenAccessor {
	@Accessor("lastScreen")
	Screen slopclient$lastScreen();
}
