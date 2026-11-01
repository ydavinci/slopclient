package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lists: a faint band instead of the dirt background and separators, rounded selection. */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListRestyleMixin {
	@Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$background(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (!Restyle.active()) return;
		Restyle.listBackground(g, (AbstractWidget) (Object) this);
		ci.cancel();
	}

	@Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$separators(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (Restyle.active()) ci.cancel();
	}

	@Inject(method = "extractSelection", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$selection(GuiGraphicsExtractor g, @Coerce LayoutElement entry, int color, CallbackInfo ci) {
		if (!Restyle.active()) return;
		Restyle.selection(g, entry.getX(), entry.getY(), entry.getWidth(), entry.getHeight());
		ci.cancel();
	}
}
