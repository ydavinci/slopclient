package dev.daclient.mixin;

import dev.daclient.DaClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Block outline module: colour and line width of the hit outline. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** The high-contrast option draws a black 7 px line underneath; that one stays. */
	private static boolean daclient$secondary(int color, float width) {
		return color == 0xFF000000 && width == 7.0f;
	}

	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int daclient$outlineColor(int color, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) float width) {
		if (!DaClient.ready() || !DaClient.modules().blockOutline.active() || daclient$secondary(color, width)) return color;
		return DaClient.modules().blockOutline.argb();
	}

	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float daclient$outlineWidth(float width, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) int color) {
		if (!DaClient.ready() || !DaClient.modules().blockOutline.active() || daclient$secondary(color, width)) return width;
		return DaClient.modules().blockOutline.width.get().floatValue();
	}
}
