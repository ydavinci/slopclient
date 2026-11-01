package dev.slopclient.mixin.restyle;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Header and footer separators (tab bars, "Welt erstellen", mod screens): a hairline
 * instead of the two-pixel texture. Caught at the blit so every screen that draws them
 * by hand is covered, not only the lists.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class SeparatorRestyleMixin {
	@Inject(method = "blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$separator(RenderPipeline pipeline, Identifier id, int x, int y, float u, float v, int w, int h, int tw, int th, CallbackInfo ci) {
		if (id != Screen.HEADER_SEPARATOR && id != Screen.FOOTER_SEPARATOR && id != Screen.INWORLD_HEADER_SEPARATOR
			&& id != Screen.INWORLD_FOOTER_SEPARATOR) return;
		if (!Restyle.active()) return;
		Restyle.separator((GuiGraphicsExtractor) (Object) this, x, y, w, h);
		ci.cancel();
	}
}
