package dev.daclient.mixin;

import dev.daclient.DaClient;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Screenshot preview: notices when a screenshot was written. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
	@ModifyVariable(
		method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
		at = @At("HEAD"), argsOnly = true)
	private static Consumer<Component> daclient$preview(Consumer<Component> callback, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) String name) {
		// The self-test's own screenshots stay quiet.
		if (name != null && name.startsWith("daclient-selftest")) return callback;
		return message -> {
			callback.accept(message);
			if (message.getContents() instanceof TranslatableContents t && t.getKey().equals("screenshot.success") && DaClient.ready()) {
				DaClient.modules().screenshots.onSaved(Minecraft.getInstance());
			}
		};
	}
}
