package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scrollbars as a thin rounded thumb without the track. */
@Mixin(AbstractScrollArea.class)
public abstract class AbstractScrollAreaRestyleMixin {
	@Shadow
	protected abstract int scrollBarX();

	@Shadow
	public abstract int scrollBarY();

	@Shadow
	protected abstract int scrollerHeight();

	@Shadow
	public abstract int scrollbarWidth();

	@Shadow
	protected abstract boolean scrollable();

	@Inject(method = "extractScrollbar", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$restyle(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
		if (!Restyle.active()) return;
		ci.cancel();
		if (!scrollable()) return;
		int x = scrollBarX(), y = scrollBarY(), w = scrollbarWidth(), h = scrollerHeight();
		boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		Restyle.scrollbar(g, this, x, y, w, h, hot);
	}
}
