package dev.daclient.mixin;

import dev.daclient.DaClient;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the boosted gamma of "Helligkeit" out of options.txt. */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "save", at = @At("HEAD"))
	private void daclient$restoreGamma(CallbackInfo ci) {
		if (DaClient.ready()) DaClient.modules().brightness.restore();
	}
}
