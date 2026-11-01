package dev.slopclient.mixin.restyle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The check box itself in the launcher look; the label stays vanilla's. */
@Mixin(Checkbox.class)
public abstract class CheckboxRestyleMixin {
	@WrapOperation(method = "extractContents", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
	private void slopclient$restyle(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, Operation<Void> original) {
		if (!Restyle.active()) {
			original.call(g, pipeline, sprite, x, y, w, h, color);
			return;
		}
		Checkbox self = (Checkbox) (Object) this;
		Restyle.checkbox(g, self, self.selected(), x, y, Math.min(w, h));
	}
}
