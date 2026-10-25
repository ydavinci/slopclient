package dev.slopclient.cosmetics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SlopClient cosmetics (capes, hats, pets) as a mod of their own. The SlopClient mod
 * bundles it and hands over its settings with {@link #use}; installed alone it reads
 * its own config file and shows the cosmetics of everyone on the cosmetics server.
 */
public final class Cosmetics implements ClientModInitializer {
	/** Resource namespace, shared with the SlopClient mod so texture paths stay the same. */
	public static final String NS = "slopclient";
	public static final Logger LOG = LoggerFactory.getLogger("SlopClient Cosmetics");

	private static volatile CosmeticsSettings settings;
	private static boolean tickFailed;

	/** The SlopClient mod calls this from its entrypoint. */
	public static void use(CosmeticsSettings host) {
		settings = host;
	}

	public static CosmeticsSettings settings() {
		CosmeticsSettings s = settings;
		if (s != null) return s;
		// Inside the SlopClient mod its settings arrive with its entrypoint; until then
		// (mixins can fire earlier) nothing is shown.
		if (FabricLoader.getInstance().isModLoaded("slopclient")) return CosmeticsSettings.OFF;
		synchronized (Cosmetics.class) {
			if (settings == null) settings = StandaloneSettings.load();
			return settings;
		}
	}

	@Override
	public void onInitializeClient() {
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> avatar) helper.register(new ExtrasLayer(avatar));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> RemoteCapes.clear());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			// Cosmetics are extras: a bug or a bad server answer here must never end the game.
			try {
				Catalogue.tick(mc);
				RemoteCapes.tick(mc);
				PetMotion.tick(mc);
			} catch (RuntimeException e) {
				if (!tickFailed) LOG.error("Cosmetics stopped updating after an error", e);
				tickFailed = true;
			}
		});
	}
}
