package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Button backgrounds in the launcher look (see {@link Restyle}); the label stays vanilla's. */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonRestyleMixin {
	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$restyle(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (!Restyle.active()) return;
		Restyle.button(g, (AbstractWidget) (Object) this);
		ci.cancel();
	}
}
