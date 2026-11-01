package dev.slopclient.mixin.restyle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Slider track (filled in chroma up to the value) and handle in the launcher look. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonRestyleMixin {
	@Shadow
	protected double value;

	@WrapOperation(method = "extractWidgetRenderState", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
	private void slopclient$restyle(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, Operation<Void> original) {
		if (!Restyle.active()) {
			original.call(g, pipeline, sprite, x, y, w, h, color);
			return;
		}
		AbstractWidget self = (AbstractWidget) (Object) this;
		// First the track (the whole widget), then the 8 px handle.
		if (w == self.getWidth()) Restyle.sliderTrack(g, self, value);
		else Restyle.sliderHandle(g, self, x, y, w, h);
	}
}
