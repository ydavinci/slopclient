package dev.daclient.module;

import dev.daclient.mixin.OptionInstanceAccessor;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import dev.daclient.setting.BoolSetting;
import dev.daclient.setting.ModeSetting;
import dev.daclient.setting.NumberSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Render and comfort modules of DaClient v1. */
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
			option().daclient$setValue(gamma.get());
		}

		@Override
		protected void onDisable() {
			restore();
		}

		/** Puts the user's own gamma back (on disable and before saving options). */
		public void restore() {
			if (saved != null) {
				option().daclient$setValue(saved);
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
			Minecraft.getInstance().options.keySprint.setDown(false);
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

	/** Sora as the font of all DaClient menus and HUD elements (off: Minecraft font). */
	public static final class UiFont extends Module {
		public UiFont() {
			super("Sora-Schrift", "Moderne Schrift in Menüs und HUD", Category.COMFORT, true);
		}

		@Override
		public boolean listed() {
			return false;
		}
	}

	/** Replaces the vanilla title screen with the DaClient one. */
	public static final class TitleScreenModule extends Module {
		public TitleScreenModule() {
			super("Titelbildschirm", "DaClient-Startbildschirm statt Vanilla", Category.COMFORT, true);
		}

		@Override
		public boolean listed() {
			return false;
		}
	}
}
