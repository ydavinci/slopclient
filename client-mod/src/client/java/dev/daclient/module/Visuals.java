package dev.daclient.module;

import dev.daclient.mixin.OverlayTextureAccessor;
import dev.daclient.render.Theme;
import dev.daclient.setting.BoolSetting;
import dev.daclient.setting.ModeSetting;
import dev.daclient.setting.NumberSetting;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/** Visual tweaks of M12: outline, hurt colour, fire, nametags, chat, scoreboard. */
public final class Visuals {
	private Visuals() {
	}

	/** Colours offered by the colour settings, ARGB without alpha. */
	static final String[] COLORS = {"Lila", "Chroma", "Weiß", "Schwarz", "Rot", "Grün", "Blau", "Gelb"};

	static int rgb(ModeSetting mode, double phase) {
		return switch (mode.get()) {
			case "Chroma" -> Theme.chroma(phase) & 0xFFFFFF;
			case "Weiß" -> 0xFFFFFF;
			case "Schwarz" -> 0x000000;
			case "Rot" -> 0xFF4D5E;
			case "Grün" -> 0x4ADE80;
			case "Blau" -> 0x60A5FA;
			case "Gelb" -> 0xFACC15;
			default -> Theme.ACCENT_2 & 0xFFFFFF;
		};
	}

	static String percent(double v) {
		return Math.round(v * 100) + " %";
	}

	/** Colour and thickness of the outline around the targeted block. */
	public static final class BlockOutline extends Module {
		public final ModeSetting color = add(new ModeSetting("Farbe", "Lila", COLORS));
		public final NumberSetting opacity = add(new NumberSetting("Deckkraft", 0.8, 0.1, 1, 0.05, Visuals::percent));
		public final NumberSetting width = add(new NumberSetting("Linienstärke", 3, 1, 8, 0.5, v -> String.format(Locale.GERMAN, "%.1f", v)));

		public BlockOutline() {
			super("Block-Umriss", "Farbe und Dicke des Block-Umrisses", Category.RENDER, false);
		}

		public int argb() {
			int a = (int) Math.round(opacity.get() * 255);
			return a << 24 | rgb(color, System.currentTimeMillis() / 3000.0);
		}
	}

	/** Colour entities flash in when hurt (vanilla: red). */
	public static final class HitColor extends Module {
		private static final int VANILLA = 0xB2FF0000;
		public final ModeSetting color = add(new ModeSetting("Farbe", "Lila", COLORS));
		public final NumberSetting opacity = add(new NumberSetting("Deckkraft", 0.7, 0.1, 1, 0.05, Visuals::percent));
		private int applied = VANILLA;

		public HitColor() {
			super("Hit-Color", "Eigene Farbe für getroffene Gegner", Category.RENDER, false);
		}

		@Override
		public void tick(Minecraft mc) {
			int a = (int) Math.round(opacity.get() * 255);
			apply(mc, a << 24 | rgb(color, 0));
		}

		@Override
		protected void onDisable() {
			apply(Minecraft.getInstance(), VANILLA);
		}

		/** Rewrites the hurt half of vanilla's overlay texture. */
		private void apply(Minecraft mc, int argb) {
			if (argb == applied || mc.gameRenderer == null) return;
			applied = argb;
			var texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).daclient$texture();
			var pixels = texture.getPixels();
			if (pixels == null) return;
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 16; x++) pixels.setPixel(x, y, argb);
			}
			texture.upload();
		}
	}

	/** Moves the burning overlay down so it covers less of the screen. */
	public static final class LowFire extends Module {
		public final NumberSetting offset = add(new NumberSetting("Absenkung", 0.3, 0.05, 0.6, 0.05, Visuals::percent));

		public LowFire() {
			super("Low Fire", "Feuer am Bildschirmrand tiefer", Category.RENDER, false);
		}
	}

	/** Shows your own nametag in third person. */
	public static final class Nametags extends Module {
		public final BoolSetting own = add(new BoolSetting("Eigener Nametag", true));

		public Nametags() {
			super("Nametags", "Eigenen Namen in der Außenansicht zeigen", Category.RENDER, false);
		}
	}

	/** Timestamps, stacked repeats and a longer chat history. */
	public static final class ChatPlus extends Module {
		public final BoolSetting timestamps = add(new BoolSetting("Uhrzeit", true));
		public final BoolSetting stack = add(new BoolSetting("Wiederholungen bündeln", true));
		public final NumberSetting history = add(new NumberSetting("Verlauf", 500, 100, 2000, 100, v -> Math.round(v) + " Zeilen"));

		public ChatPlus() {
			super("Chat", "Uhrzeit, gebündelte Wiederholungen, längerer Verlauf", Category.COMFORT, false);
		}
	}

	/** Size and numbers of the sidebar scoreboard. */
	public static final class Scoreboard extends Module {
		public final NumberSetting size = add(new NumberSetting("Größe", 1, 0.5, 1.5, 0.05, v -> String.format(Locale.GERMAN, "%.2fx", v)));
		public final BoolSetting hideNumbers = add(new BoolSetting("Rote Zahlen ausblenden", true));
		public final BoolSetting hide = add(new BoolSetting("Ganz ausblenden", false));

		public Scoreboard() {
			super("Scoreboard", "Größe und Zahlen der Seitenleiste", Category.HUD, false);
		}
	}
}
