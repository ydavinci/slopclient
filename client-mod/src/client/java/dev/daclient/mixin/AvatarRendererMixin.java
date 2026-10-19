package dev.daclient.mixin;

import dev.daclient.DaClient;
import dev.daclient.cosmetics.CapeTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the Cape Studio cape on the local player: the cape and wings layers read the
 * texture from the render state's skin, so swapping it there is all vanilla needs.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void daclient$cape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (!DaClient.ready() || avatar != Minecraft.getInstance().player) return;
		var capes = DaClient.modules().capes;
		if (!capes.active() || !CapeTextures.available()) return;
		PlayerSkin skin = state.skin;
		if (skin.cape() != null && !capes.overMojang.on()) return;
		ClientAsset.Texture cape = CapeTextures.current(capes.animate.on());
		ClientAsset.Texture wings = capes.elytra.on() && CapeTextures.elytra() ? cape : skin.elytra();
		state.skin = new PlayerSkin(skin.body(), cape, wings, skin.model(), skin.secure());
	}
}
