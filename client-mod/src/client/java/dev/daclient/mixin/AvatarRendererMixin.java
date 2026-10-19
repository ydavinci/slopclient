package dev.daclient.mixin;

import dev.daclient.DaClient;
import dev.daclient.cosmetics.CapeAnimation;
import dev.daclient.cosmetics.CapeTextures;
import dev.daclient.cosmetics.RemoteCapes;
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
 * DaClient capes on players: your own from the Cape Studio (or, without one, what the
 * cosmetics server has for you), other players' from the server. The cape and wings
 * layers read the texture from the render state's skin, so swapping it there is enough.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void daclient$cape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (!DaClient.ready()) return;
		var capes = DaClient.modules().capes;
		if (!capes.active()) return;
		boolean self = avatar == Minecraft.getInstance().player;
		CapeAnimation anim = self && CapeTextures.local() != null ? CapeTextures.local()
			: self || capes.others.on() ? RemoteCapes.get(avatar.getUUID()) : null;
		if (anim == null) return;
		PlayerSkin skin = state.skin;
		if (skin.cape() != null && !capes.overMojang.on()) return;
		ClientAsset.Texture cape = anim.current(capes.animate.on());
		ClientAsset.Texture wings = capes.elytra.on() && anim.elytra ? cape : skin.elytra();
		state.skin = new PlayerSkin(skin.body(), cape, wings, skin.model(), skin.secure());
	}
}
