package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Punchy (optional) draws held items through its own first-person animations, which
 * would replace the Karambit's grip and inspect. While the Karambit module is on, the
 * netherite sword counts as on Punchy's item blacklist, so Punchy leaves exactly that
 * item to vanilla rendering and keeps animating everything else. Every Punchy check
 * (rendering, inspect, model resolving) goes through this method.
 */
@Pseudo
@Mixin(targets = "punchy.config.PunchyConfig", remap = false)
public abstract class PunchyConfigMixin {
	@Inject(method = "isItemBlacklisted", at = @At("HEAD"), cancellable = true, require = 0)
	private static void slopclient$karambit(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (stack != null && stack.is(Items.NETHERITE_SWORD) && SlopClient.ready() && SlopClient.modules().karambit.active()) {
			cir.setReturnValue(true);
		}
	}
}
