package dev.slopclient.cosmetics;

/**
 * What the cosmetics need to know from their host: the SlopClient mod passes its module
 * settings and theme, the standalone mod reads {@code config/slopclient-cosmetics.json}.
 */
public interface CosmeticsSettings {
	/** Cosmetics server base URL; empty means no capes, hats or pets of other players. */
	String server();

	boolean capes();

	boolean capesOfOthers();

	/** Show the SlopClient cape instead of a Mojang cape. */
	boolean capesOverMojang();

	boolean animateCapes();

	boolean capesAsElytra();

	boolean extras();

	boolean extrasOfOthers();

	boolean hideHatWithHelmet();

	/** Your own hat chosen in the launcher; null takes it from the cosmetics server. */
	default String ownHat() {
		return null;
	}

	/** Your own pet chosen in the launcher; null takes it from the cosmetics server. */
	default String ownPet() {
		return null;
	}

	/** Accent colours (ARGB) for hats and pets painted "accent" and "accent2". */
	default int accent() {
		return 0xFF8B5CF6;
	}

	default int accent2() {
		return 0xFFC084FC;
	}

	/** Animated chroma colour (ARGB), like the SlopClient theme with its default violet. */
	default int chroma(double phase) {
		int[] stops = {0xFF8B5CF6, 0xFFC084FC, 0xFFE879F9, 0xFFC084FC, 0xFF8B5CF6};
		double t = (System.currentTimeMillis() / 6000.0 + phase) % 1.0;
		if (t < 0) t += 1;
		double pos = t * (stops.length - 1);
		int i = (int) Math.floor(pos);
		int a = stops[i], b = stops[Math.min(i + 1, stops.length - 1)];
		double f = pos - i;
		int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * f);
		int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * f);
		int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * f);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	/** Everything off: the SlopClient mod is installed but has not started yet. */
	CosmeticsSettings OFF = new CosmeticsSettings() {
		public String server() {
			return "";
		}

		public boolean capes() {
			return false;
		}

		public boolean capesOfOthers() {
			return false;
		}

		public boolean capesOverMojang() {
			return false;
		}

		public boolean animateCapes() {
			return false;
		}

		public boolean capesAsElytra() {
			return false;
		}

		public boolean extras() {
			return false;
		}

		public boolean extrasOfOthers() {
			return false;
		}

		public boolean hideHatWithHelmet() {
			return false;
		}
	};
}
