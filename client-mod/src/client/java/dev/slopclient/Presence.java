package dev.slopclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Tells the launcher where the player is, for its Discord status. The launcher
 * reads these "[SlopClient] presence:" lines from the game log; nothing leaves the PC.
 */
public final class Presence {
	private static String last;

	private Presence() {
	}

	public static void tick(Minecraft mc) {
		String place;
		if (mc.level == null) {
			place = "menu";
		} else if (mc.hasSingleplayerServer()) {
			place = "singleplayer";
		} else {
			ServerData server = mc.getCurrentServer();
			if (server == null) place = "menu";
			else if (server.isRealm()) place = "server Realms";
			else if (server.isLan()) place = "server LAN";
			else place = "server " + server.ip.trim();
		}
		if (place.equals(last)) return;
		last = place;
		SlopClient.LOG.info("[SlopClient] presence: {}", place);
	}
}
