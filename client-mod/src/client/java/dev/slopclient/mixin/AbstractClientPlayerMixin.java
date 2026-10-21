package dev.slopclient.mixin;

import dev.slopclient.cosmetics.CapeSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SlopClient capes at the source of the skin, so every renderer and cape mod sees them. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void slopclient$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = cir.getReturnValue();
		PlayerSkin withCape = CapeSkins.apply((AbstractClientPlayer) (Object) this, skin);
		if (withCape != skin) cir.setReturnValue(withCape);
	}
}
