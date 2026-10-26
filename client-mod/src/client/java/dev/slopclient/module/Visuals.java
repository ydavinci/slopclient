package dev.slopclient.module;

import dev.slopclient.mixin.OverlayTextureAccessor;
import dev.slopclient.render.Theme;
import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import dev.slopclient.setting.NumberSetting;
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

	/**
	 * A soft outline around other players, but only around those you can actually see:
	 * players behind walls stay as they are, so it shows nothing the screen does not.
	 */
	public static final class Glow extends Module {
		public final ModeSetting color = add(new ModeSetting("Farbe", "Chroma", "Chroma", "Lila", "Teamfarbe", "Weiß", "Rot", "Grün", "Blau", "Gelb"));
		public final BoolSetting friendsOnly = add(new BoolSetting("Nur Teammitglieder", false));
		private final java.util.Set<Integer> visible = new java.util.HashSet<>();

		public Glow() {
			super("Glow", "Sichtbare Spieler leuchten in deiner Farbe", Category.RENDER, false);
		}

		@Override
		public void tick(Minecraft mc) {
			visible.clear();
			if (mc.level == null || mc.player == null) return;
			net.minecraft.world.phys.Vec3 eye = mc.gameRenderer.mainCamera().position();
			for (var player : mc.level.players()) {
				if (player == mc.player || player.isInvisible() || player.isSpectator()) continue;
				if (friendsOnly.on() && !mc.player.isAlliedTo(player)) continue;
				if (player.distanceToSqr(eye) > 128 * 128) continue;
				if (seen(mc, eye, player)) visible.add(player.getId());
			}
		}

		/** Line of sight to the head, the middle or the feet. */
		private static boolean seen(Minecraft mc, net.minecraft.world.phys.Vec3 eye, net.minecraft.world.entity.player.Player p) {
			var box = p.getBoundingBox();
			double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
			for (double y : new double[] {p.getEyeY(), (box.minY + box.maxY) / 2, box.minY + 0.1}) {
				var target = new net.minecraft.world.phys.Vec3(cx, y, cz);
				var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, target,
					net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
			}
			return false;
		}

		public boolean shouldGlow(net.minecraft.world.entity.Entity entity) {
			return visible.contains(entity.getId());
		}

		public int color(net.minecraft.world.entity.Entity entity) {
			return switch (color.get()) {
				case "Teamfarbe" -> entity.getTeamColor();
				// Each player a little further along the rainbow.
				case "Chroma" -> Theme.chroma(entity.getId() * 0.13) & 0xFFFFFF;
				default -> rgb(color, 0);
			};
		}
	}

	/**
	 * Glittering sparks behind you, like a cosmetic trail: a stream of tiny glowing stars
	 * at body height that drift and fade. Only you see it.
	 */
	public static final class Trail extends Module {
		public final ModeSetting color = add(new ModeSetting("Farbe", "Chroma", "Chroma", "Gold", "Lila", "Weiß", "Rot", "Grün", "Blau"));
		public final NumberSetting density = add(new NumberSetting("Dichte", 1, 0.25, 2, 0.25, v -> String.format(Locale.GERMAN, "%.2fx", v)));
		public final BoolSetting firstPerson = add(new BoolSetting("In der Ich-Ansicht", true));
		private net.minecraft.world.phys.Vec3 last;
		private double owed;

		public Trail() {
			super("Trail", "Funkelnde Spur hinter dir", Category.COSMETICS, false);
		}

		@Override
		protected void onDisable() {
			last = null;
		}

		@Override
		public void tick(Minecraft mc) {
			var player = mc.player;
			if (player == null || mc.level == null) {
				last = null;
				return;
			}
			var now = player.position();
			var from = last == null ? now : last;
			last = now;
			if (player.isInvisible() || player.isSpectator()) return;
			if (!firstPerson.on() && mc.options.getCameraType().isFirstPerson()) return;
			// A few sparks while standing, a stream while running.
			double moved = Math.min(1.5, Math.hypot(now.x - from.x, now.z - from.z) + Math.abs(now.y - from.y) * 0.5);
			owed += (1.2 + moved * 42) * density.get();
			var random = mc.level.getRandom();
			double width = player.getBbWidth() * 0.6;
			while (owed >= 1) {
				owed -= 1;
				double t = random.nextDouble();
				// Mostly around the legs, thinning out towards the shoulders.
				double y = from.y + (now.y - from.y) * t + Math.pow(random.nextDouble(), 1.6) * player.getBbHeight() * 0.85;
				double x = from.x + (now.x - from.x) * t + (random.nextDouble() - 0.5) * width;
				double z = from.z + (now.z - from.z) * t + (random.nextDouble() - 0.5) * width;
				double vx = (random.nextDouble() - 0.5) * 0.02 - (now.x - from.x) * 0.04;
				double vz = (random.nextDouble() - 0.5) * 0.02 - (now.z - from.z) * 0.04;
				double vy = random.nextDouble() * 0.015;
				dev.slopclient.render.TrailParticle.spawn(mc, x, y, z, vx, vy, vz, sparkColor(random), random.nextInt(4) == 0);
			}
		}

		private int sparkColor(net.minecraft.util.RandomSource random) {
			return switch (color.get()) {
				case "Chroma" -> Theme.chroma(random.nextDouble()) & 0xFFFFFF;
				// Warm gold from deep amber to pale champagne, as in the reference.
				case "Gold" -> Theme.lerp(0xFFE0A22E, 0xFFFFF0B8, random.nextDouble()) & 0xFFFFFF;
				default -> Theme.lerp(0xFF000000 | rgb(color, 0), 0xFFFFFFFF, random.nextDouble() * 0.35) & 0xFFFFFF;
			};
		}
	}

	/** The launcher's flowing shader as the sky, with fog to match. */
	public static final class Skybox extends Module {
		public final BoolSetting end = add(new BoolSetting("Auch im End", false));
		public final BoolSetting fog = add(new BoolSetting("Nebel anpassen", true));
		public final BoolSetting hideClouds = add(new BoolSetting("Wolken ausblenden", true));

		public Skybox() {
			super("Skybox", "Der Shader des Launchers als Himmel", Category.RENDER, false);
		}

		/** Overworld skies, and the End's when chosen; the Nether has no sky. */
		public boolean replaces(net.minecraft.world.level.dimension.DimensionType.Skybox sky) {
			return active() && (sky == net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD
				|| sky == net.minecraft.world.level.dimension.DimensionType.Skybox.END && end.on());
		}

		public org.joml.Vector3fc fogColor() {
			int c = Theme.SKY_FOG;
			return new org.joml.Vector3f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f);
		}
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
			var texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).slopclient$texture();
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
