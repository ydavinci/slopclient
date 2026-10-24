package dev.slopclient.cosmetics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Puts SlopClient capes into a player's skin: your own from the Cape Studio (or, without
 * one, what the cosmetics server has for you), other players' from the server.
 *
 * <p>Applied where the skin comes from ({@code AbstractClientPlayer.getSkin}), so the
 * vanilla cape and elytra layers and cape mods such as WaveyCapes all see the same cape.
 */
public final class CapeSkins {
	private CapeSkins() {
	}

	public static PlayerSkin apply(AbstractClientPlayer player, PlayerSkin skin) {
		if (skin == null) return skin;
		CosmeticsSettings s = Cosmetics.settings();
		if (!s.capes()) return skin;
		boolean self = player == Minecraft.getInstance().player;
		CapeAnimation anim = self && CapeTextures.local() != null ? CapeTextures.local()
			: self || s.capesOfOthers() ? RemoteCapes.get(player.getUUID()) : null;
		if (anim == null) return skin;
		if (skin.cape() != null && !s.capesOverMojang()) return skin;
		ClientAsset.Texture cape = anim.current(s.animateCapes());
		ClientAsset.Texture wings = s.capesAsElytra() && anim.elytra ? cape : skin.elytra();
		return new PlayerSkin(skin.body(), cape, wings, skin.model(), skin.secure());
	}
}
