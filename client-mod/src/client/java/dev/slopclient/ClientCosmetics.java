package dev.slopclient;

import dev.slopclient.cosmetics.CosmeticsSettings;
import dev.slopclient.render.Theme;

/**
 * Hands the bundled cosmetics mod the client's settings: the Capes and Hüte & Begleiter
 * modules, the launcher's choices and server, and the accent colour of the theme.
 */
final class ClientCosmetics implements CosmeticsSettings {
	private static final String SERVER = System.getProperty("slopclient.cosmetics", "").trim().replaceAll("/+$", "");

	public String server() {
		return SERVER;
	}

	public boolean capes() {
		return SlopClient.modules().capes.active();
	}

	public boolean capesOfOthers() {
		return SlopClient.modules().capes.others.on();
	}

	public boolean capesOverMojang() {
		return SlopClient.modules().capes.overMojang.on();
	}

	public boolean animateCapes() {
		return SlopClient.modules().capes.animate.on();
	}

	public boolean capesAsElytra() {
		return SlopClient.modules().capes.elytra.on();
	}

	public boolean extras() {
		return SlopClient.modules().extras.active();
	}

	public boolean extrasOfOthers() {
		return SlopClient.modules().extras.others.on();
	}

	public boolean hideHatWithHelmet() {
		return SlopClient.modules().extras.hideWithHelmet.on();
	}

	public String ownHat() {
		return System.getProperty("slopclient.hat", "");
	}

	public String ownPet() {
		return System.getProperty("slopclient.pet", "");
	}

	public String ownAccessory() {
		return System.getProperty("slopclient.accessory", "");
	}

	public int accent() {
		return Theme.ACCENT;
	}

	public int accent2() {
		return Theme.ACCENT_2;
	}

	public int chroma(double phase) {
		return Theme.chroma(phase);
	}
}
