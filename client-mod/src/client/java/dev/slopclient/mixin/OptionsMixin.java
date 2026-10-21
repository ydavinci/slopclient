package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the boosted gamma of "Helligkeit" out of options.txt. */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "save", at = @At("HEAD"))
	private void slopclient$restoreGamma(CallbackInfo ci) {
		if (SlopClient.ready()) SlopClient.modules().brightness.restore();
	}
}
