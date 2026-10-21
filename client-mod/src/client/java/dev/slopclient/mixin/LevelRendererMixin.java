package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Block outline module: colour and line width of the hit outline. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** The high-contrast option draws a black 7 px line underneath; that one stays. */
	private static boolean slopclient$secondary(int color, float width) {
		return color == 0xFF000000 && width == 7.0f;
	}

	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int slopclient$outlineColor(int color, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) float width) {
		if (!SlopClient.ready() || !SlopClient.modules().blockOutline.active() || slopclient$secondary(color, width)) return color;
		return SlopClient.modules().blockOutline.argb();
	}

	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float slopclient$outlineWidth(float width, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) int color) {
		if (!SlopClient.ready() || !SlopClient.modules().blockOutline.active() || slopclient$secondary(color, width)) return width;
		return SlopClient.modules().blockOutline.width.get().floatValue();
	}
}
