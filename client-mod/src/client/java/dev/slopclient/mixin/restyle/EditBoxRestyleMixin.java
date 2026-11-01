package dev.slopclient.mixin.restyle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.ui.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The frame of bordered text fields in the launcher look. */
@Mixin(EditBox.class)
public abstract class EditBoxRestyleMixin {
	@WrapOperation(method = "extractWidgetRenderState", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void slopclient$restyle(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
		if (!Restyle.active()) {
			original.call(g, pipeline, sprite, x, y, w, h);
			return;
		}
		Restyle.textField(g, (AbstractWidget) (Object) this, x, y, w, h);
	}
}
