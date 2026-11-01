package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTabType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advancement tabs as glass tabs, the selected one with an accent edge. */
@Mixin(AdvancementTabType.class)
public abstract class AdvancementTabTypeRestyleMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$restyle(GuiGraphicsExtractor g, int x, int y, boolean selected, int index, CallbackInfo ci) {
		if (!Restyle.active()) return;
		AdvancementTabType type = (AdvancementTabType) (Object) this;
		Restyle.advancementTab(g, x, y, type.getWidth(), type.getHeight(), selected);
		ci.cancel();
	}
}
