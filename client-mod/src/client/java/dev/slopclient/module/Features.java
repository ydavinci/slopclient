package dev.slopclient.module;

import dev.slopclient.mixin.OptionInstanceAccessor;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import dev.slopclient.setting.NumberSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Render and comfort modules of SlopClient v1. */
public final class Features {
	private Features() {
	}

	/**
	 * Hold the zoom key to narrow the field of view (like OptiFine's zoom).
	 * The level eases in and out; the mouse wheel changes it while zooming.
	 */
	public static final class Zoom extends Module {
		public final NumberSetting level = add(new NumberSetting("Stufe", 4, 1.5, 10, 0.5, v -> v % 1 == 0 ? Math.round(v) + "x" : String.format(java.util.Locale.GERMAN, "%.1fx", v)));
		public final BoolSetting scroll = add(new BoolSetting("Mausrad", true));
		public final BoolSetting cinematic = add(new BoolSetting("Weiche Kamera", true));
		private KeyMapping key;
		private double current = 1;
		private double scrollLevel = -1;
		private boolean smoothBefore;
		private boolean zooming;

		public Zoom() {
			super("Zoom", "Taste halten zum Heranzoomen", Category.RENDER, true);
		}

		public void bind(KeyMapping key) {
			this.key = key;
		}

		@Override
		public String suffix() {
			return level.display();
		}

		public boolean zooming() {
			return active() && key != null && key.isDown() && Minecraft.getInstance().gui.screen() == null;
		}

		/** Divides the field of view; 1 means no zoom. */
		public double factor() {
			double target = zooming() ? (scrollLevel > 0 ? scrollLevel : level.get()) : 1;
			// Frame-rate independent easing towards the target.
			current += (target - current) * 0.35;
			if (Math.abs(target - current) < 0.01) current = target;
			return current;
		}

		/** Returns true when the scroll was used for zooming. */
		public boolean onScroll(double amount) {
			if (!zooming() || !scroll.on() || amount == 0) return false;
			double base = scrollLevel > 0 ? scrollLevel : level.get();
			scrollLevel = Math.max(1.5, Math.min(30, base * (amount > 0 ? 1.25 : 0.8)));
			return true;
		}

		@Override
		public void tick(Minecraft mc) {
			boolean now = zooming();
			if (now != zooming) {
				zooming = now;
				if (now) {
					smoothBefore = mc.options.smoothCamera;
					if (cinematic.on()) mc.options.smoothCamera = true;
				} else {
					mc.options.smoothCamera = smoothBefore;
					scrollLevel = -1;
				}
			}
		}
	}

	/**
	 * "Helligkeit" (fullbright): raises the gamma beyond the slider's 100 %.
	 * The real gamma is restored before options are saved (see OptionsMixin).
	 */
	public static final class Brightness extends Module {
		public final NumberSetting gamma = add(new NumberSetting("Gamma", 10, 1, 15, 0.5, v -> Math.round(v * 100) + " %"));
		private Double saved;

		public Brightness() {
			super("Helligkeit", "Sicht in Höhlen und bei Nacht", Category.RENDER, false);
		}

		private static OptionInstanceAccessor option() {
			return (OptionInstanceAccessor) (Object) Minecraft.getInstance().options.gamma();
		}

		@Override
		public void tick(Minecraft mc) {
			if (saved == null) saved = mc.options.gamma().get();
			option().slopclient$setValue(gamma.get());
		}

		@Override
		protected void onDisable() {
			restore();
		}

		/** Puts the user's own gamma back (on disable and before saving options). */
		public void restore() {
			if (saved != null) {
				option().slopclient$setValue(saved);
				saved = null;
			}
		}
	}

	/** Custom crosshair, drawn in place of the vanilla one in first person. */
	public static final class Crosshair extends Module {
		public final ModeSetting shape = add(new ModeSetting("Form", "Kreuz", "Kreuz", "Punkt", "Kreis"));
		public final ModeSetting color = add(new ModeSetting("Farbe", "Weiß", "Weiß", "Chroma", "Grün", "Pink"));
		public final NumberSetting size = add(new NumberSetting("Größe", 5, 2, 10, 1, v -> Math.round(v) + " px"));
		public final NumberSetting gap = add(new NumberSetting("Abstand", 2, 0, 6, 1, v -> Math.round(v) + " px"));

		public Crosshair() {
			super("Fadenkreuz", "Eigenes Fadenkreuz", Category.RENDER, false);
		}

		private int colour() {
			return switch (color.get()) {
				case "Chroma" -> Theme.chroma(0);
				case "Grün" -> Theme.ON;
				case "Pink" -> 0xFFF472B6;
				default -> 0xFFFFFFFF;
			};
		}

		public void draw(GuiGraphicsExtractor g) {
			int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2, c = colour();
			int s = size.get().intValue(), gp = gap.get().intValue();
			switch (shape.get()) {
				case "Punkt" -> Draw.round(g, cx - 1, cy - 1, 3, 3, 1, c);
				case "Kreis" -> {
					int r = s + gp;
					for (int a = 0; a < 360; a += 6) {
						double rad = Math.toRadians(a);
						int px = cx + (int) Math.round(Math.cos(rad) * r), py = cy + (int) Math.round(Math.sin(rad) * r);
						g.fill(px, py, px + 1, py + 1, c);
					}
					g.fill(cx, cy, cx + 1, cy + 1, c);
				}
				default -> {
					g.fill(cx - gp - s, cy, cx - gp, cy + 1, c);
					g.fill(cx + gp + 1, cy, cx + gp + 1 + s, cy + 1, c);
					g.fill(cx, cy - gp - s, cx + 1, cy - gp, c);
					g.fill(cx, cy + gp + 1, cx + 1, cy + gp + 1 + s, c);
				}
			}
		}
	}

	/** Keeps sprinting while walking forward, so the sprint key doesn't have to be held. */
	public static final class ToggleSprint extends Module {
		public ToggleSprint() {
			super("Toggle Sprint", "Automatisch sprinten beim Vorwärtslaufen", Category.COMFORT, true);
		}

		@Override
		public void tick(Minecraft mc) {
			if (mc.player == null || mc.gui.screen() != null) return;
			mc.options.keySprint.setDown(mc.options.keyUp.isDown());
		}

		@Override
		protected void onDisable() {
			var options = Minecraft.getInstance().options;
			if (options != null) options.keySprint.setDown(false);
		}
	}

	/**
	 * Hold (or toggle) the freelook key to look around the player in third person
	 * while walking straight on. Servers can switch it off (see ServerRules).
	 */
	public static final class Freelook extends Module {
		public final ModeSetting mode = add(new ModeSetting("Modus", "Halten", "Halten", "Umschalten"));
		private KeyMapping key;
		private boolean toggled;
		private boolean looking;
		private CameraType before;
		private float yaw;
		private float pitch;

		public Freelook() {
			super("Freelook", "Umsehen, ohne die Laufrichtung zu ändern", Category.COMFORT, true);
		}

		public void bind(KeyMapping key) {
			this.key = key;
		}

		/** Whether the camera currently uses the freelook angles. */
		public boolean looking() {
			return looking;
		}

		public float yaw() {
			return yaw;
		}

		public float pitch() {
			return pitch;
		}

		/** Mouse movement goes to the camera instead of the player while looking. */
		public void turn(double dx, double dy) {
			yaw += (float) dx * 0.15f;
			pitch = Math.max(-90, Math.min(90, pitch + (float) dy * 0.15f));
		}

		@Override
		public void tick(Minecraft mc) {
			if (key == null) return;
			if (mode.is("Umschalten")) {
				while (key.consumeClick()) toggled = !toggled;
			} else {
				toggled = false;
			}
			boolean want = mc.player != null && mc.gui.screen() == null && (mode.is("Halten") ? key.isDown() : toggled);
			if (want && !looking) start(mc);
			else if (!want && looking) stop(mc);
		}

		private void start(Minecraft mc) {
			looking = true;
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			before = mc.options.getCameraType();
			if (before.isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}

		private void stop(Minecraft mc) {
			looking = false;
			toggled = false;
			if (before != null) mc.options.setCameraType(before);
			before = null;
		}

		@Override
		protected void onDisable() {
			if (looking) stop(Minecraft.getInstance());
		}
	}

	/** Sora as the font of all SlopClient menus and HUD elements (off: Minecraft font). */
	/** Font of the SlopClient menus and HUD; see Draw.FONTS. */
	/** Hats and pets chosen in the launcher (Kosmetik → Extras). */
	public static final class Extras extends Module {
		public final dev.slopclient.setting.BoolSetting others = add(new dev.slopclient.setting.BoolSetting("Bei anderen zeigen", true));
		public final dev.slopclient.setting.BoolSetting hideWithHelmet = add(new dev.slopclient.setting.BoolSetting("Hut unter Helm ausblenden", false));

		public Extras() {
			super("Hüte & Begleiter", "Hut und Begleiter aus dem Launcher, auch bei anderen Spielern", Category.RENDER, true);
		}

		@Override
		public boolean listed() {
			return false;
		}
	}

	public static final class UiFont extends Module {
		public final ModeSetting font = add(new ModeSetting("Schriftart", "Sora", fontNames()));
		public final dev.slopclient.setting.BoolSetting everywhere = add(new dev.slopclient.setting.BoolSetting("Überall im Spiel", true));

		public UiFont() {
			super("Schrift", "Schriftart für Menüs, HUD und auf Wunsch das ganze Spiel", Category.COMFORT, true);
		}

		private static String[] fontNames() {
			String[] names = new String[Draw.FONTS.length];
			for (int i = 0; i < names.length; i++) names[i] = Draw.FONTS[i][0];
			return names;
		}

		@Override
		public String suffix() {
			return font.get();
		}

		@Override
		public boolean listed() {
			return false;
		}
	}

	/**
	 * Shows the cape from the launcher's Cape Studio on your player (and as elytra).
	 * Purely visual; servers can switch it off with the id "capes".
	 */
	public static final class Capes extends Module {
		public final BoolSetting animate = add(new BoolSetting("Animation", true));
		public final BoolSetting elytra = add(new BoolSetting("Auch als Elytra", true));
		public final BoolSetting overMojang = add(new BoolSetting("Vor Mojang-Cape", true));
		public final BoolSetting others = add(new BoolSetting("Capes anderer", true));

		public Capes() {
			super("Capes", "Eigene Cape aus dem Cape-Studio des Launchers", Category.COSMETICS, true);
		}

		@Override
		public String suffix() {
			int frames = dev.slopclient.cosmetics.CapeTextures.frameCount();
			return frames > 1 ? frames + " Bilder" : "";
		}
	}

	/**
	 * Shows the netherite sword as a 3D CS:GO Karambit, with draw and inspect
	 * animations in first person. Purely visual: the item stays a netherite sword.
	 */
	public static final class Karambit extends Module {
		public final BoolSetting draw = add(new BoolSetting("Zieh-Animation", true));
		public final BoolSetting inspect = add(new BoolSetting("Inspect-Animation", true));
		private KeyMapping inspectKey;
		private boolean held;
		private int slot = -1;

		public Karambit() {
			super("Karambit", "Netherite-Schwert als 3D-Karambit, Inspect mit V", Category.COSMETICS, true);
		}

		public void bind(KeyMapping key) {
			inspectKey = key;
		}

		@Override
		protected void onDisable() {
			dev.slopclient.cosmetics.karambit.KarambitAnimation.stop();
			held = false;
		}

		@Override
		public void tick(Minecraft mc) {
			if (mc.player == null) {
				held = false;
				return;
			}
			boolean now = mc.player.getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SWORD);
			int selected = mc.player.getInventory().getSelectedSlot();
			if (now && (!held || selected != slot) && draw.on()) dev.slopclient.cosmetics.karambit.KarambitAnimation.startDraw();
			if (!now) dev.slopclient.cosmetics.karambit.KarambitAnimation.stop();
			held = now;
			slot = selected;
			while (inspectKey != null && inspectKey.consumeClick()) {
				if (now && inspect.on() && mc.gui.screen() == null) dev.slopclient.cosmetics.karambit.KarambitAnimation.startInspect();
			}
			// Attacking interrupts the inspect, like in CS.
			if (mc.player.isSwinging()) dev.slopclient.cosmetics.karambit.KarambitAnimation.cancelInspect();
		}
	}

	/**
	 * Anvil planner: the cheapest order to put enchanted books on an item. Opens with its
	 * key or the "Planer" button next to the anvil.
	 */
	public static final class AnvilPlanner extends Module {
		private KeyMapping key;

		public AnvilPlanner() {
			super("Amboss-Planer", "Günstigste Reihenfolge für Verzauberungsbücher", Category.COMFORT, true);
		}

		public void bind(KeyMapping key) {
			this.key = key;
		}

		@Override
		public void tick(Minecraft mc) {
			while (key != null && key.consumeClick()) {
				if (mc.gui.screen() == null && mc.level != null) mc.gui.setScreen(new dev.slopclient.gui.AnvilPlannerScreen(null));
			}
		}
	}

	/** Replaces the vanilla title screen with the SlopClient one. */
	public static final class TitleScreenModule extends Module {
		public TitleScreenModule() {
			super("Titelbildschirm", "SlopClient-Startbildschirm statt Vanilla", Category.COMFORT, true);
		}

		@Override
		public boolean listed() {
			return false;
		}
	}
}
