package dev.daclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.daclient.cosmetics.karambit.KarambitRenderer;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Special renderers only see the item stack; the Karambit also needs the display
 * context to animate in first person only. The model identity keeps the plain
 * argument, so cached GUI renders are unaffected.
 */
@Mixin(SpecialModelWrapper.class)
public abstract class SpecialModelWrapperMixin {
	@ModifyArg(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;setupSpecialModel(Lnet/minecraft/client/renderer/special/SpecialModelRenderer;Ljava/lang/Object;)V"),
		index = 1)
	private Object daclient$karambitContext(Object argument, @Local(argsOnly = true) ItemDisplayContext context) {
		return argument == KarambitRenderer.Arg.UNKNOWN ? KarambitRenderer.Arg.of(context) : argument;
	}
}
