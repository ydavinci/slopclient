package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tabs of "Welt erstellen" and other tabbed screens as launcher pills instead of stone tabs. */
@Mixin(MenuTabBar.MenuTabButton.class)
public abstract class MenuTabButtonRestyleMixin extends TabButton {
	private MenuTabButtonRestyleMixin(TabManager manager, Tab tab, int width, int height) {
		super(manager, tab, width, height);
	}

	@Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$restyle(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!Restyle.active()) return;
		Restyle.tab(g, this, this.isSelected());
		this.handleCursor(g);
		ci.cancel();
	}
}
