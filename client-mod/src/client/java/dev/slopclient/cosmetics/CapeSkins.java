package dev.slopclient.cosmetics;

import dev.slopclient.SlopClient;
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
		if (skin == null || !SlopClient.ready()) return skin;
		var capes = SlopClient.modules().capes;
		if (!capes.active()) return skin;
		boolean self = player == Minecraft.getInstance().player;
		CapeAnimation anim = self && CapeTextures.local() != null ? CapeTextures.local()
			: self || capes.others.on() ? RemoteCapes.get(player.getUUID()) : null;
		if (anim == null) return skin;
		if (skin.cape() != null && !capes.overMojang.on()) return skin;
		ClientAsset.Texture cape = anim.current(capes.animate.on());
		ClientAsset.Texture wings = capes.elytra.on() && anim.elytra ? cape : skin.elytra();
		return new PlayerSkin(skin.body(), cape, wings, skin.model(), skin.secure());
	}
}
