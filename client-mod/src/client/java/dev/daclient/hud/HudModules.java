package dev.daclient.hud;

import dev.daclient.DaClient;
import dev.daclient.module.Module;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import dev.daclient.setting.BoolSetting;
import dev.daclient.setting.ModeSetting;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** The HUD elements of DaClient v1. All of them only display information the player already has. */
public final class HudModules {
	private HudModules() {
	}

	/** "DaClient 0.1.0" in the top-left corner. */
	public static final class Watermark extends HudModule {
		public Watermark() {
			super("Watermark", "Name und Version oben links", true, 0, 0, false);
		}

		@Override
		public int width(Minecraft mc) {
			return Draw.width("DaClient") + 4 + Draw.width(DaClient.VERSION) + (background.on() ? 10 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int px = background.on() ? 5 : 0, py = background.on() ? 4 : 0;
			int w = Draw.chromaText(g, "DaClient", px, py, 0, true);
			Draw.shadowText(g, DaClient.VERSION, px + w + 4, py, Theme.MUTED);
		}
	}

	/** Active modules, longest first, right-aligned with a rolling chroma phase per row. */
	public static final class ArrayListHud extends HudModule {
		public ArrayListHud() {
			super("ArrayList", "Liste der aktiven Module", true, 1, 0, true);
		}

		/** {name, suffix} per active module. */
		private List<String[]> rows() {
			List<String[]> rows = new ArrayList<>();
			for (Module m : DaClient.modules().all()) {
				if (m.active() && m.listed()) rows.add(new String[] {m.name, m.suffix()});
			}
			rows.sort(Comparator.comparingInt(ArrayListHud::rowWidth).reversed());
			return rows;
		}

		private static int rowWidth(String[] row) {
			return Draw.width(row[0]) + (row[1].isEmpty() ? 0 : 4 + Draw.width(row[1]));
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !rows().isEmpty();
		}

		@Override
		public int width(Minecraft mc) {
			return rows().stream().mapToInt(ArrayListHud::rowWidth).max().orElse(60) + 8;
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, rows().size()) * 11;
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			List<String[]> rows = rows();
			int width = width(mc);
			// Rows align to the side of the screen the element sits on.
			boolean right = fx > 0.5;
			for (int i = 0; i < rows.size(); i++) {
				String[] row = rows.get(i);
				int rw = rowWidth(row) + 8;
				int rx = right ? width - rw : 0;
				int color = Theme.chroma(-i * 0.06);
				if (background.on()) g.fill(rx, i * 11, rx + rw, i * 11 + 11, Theme.alpha(Theme.PANEL_SOLID, 0.6));
				g.fill(right ? width - 1 : 0, i * 11, right ? width : 1, i * 11 + 11, color);
				Draw.shadowText(g, row[0], rx + 4, i * 11 + 2, color);
				if (!row[1].isEmpty()) Draw.shadowText(g, row[1], rx + 8 + Draw.width(row[0]), i * 11 + 2, Theme.MUTED);
			}
			if (rows.isEmpty() && editing) Draw.shadowText(g, "ArrayList", 4, 2, Theme.MUTED);
		}
	}

	public static final class Fps extends HudModule {
		public Fps() {
			super("FPS", "Bilder pro Sekunde", true, 0, 0.085, true);
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("FPS", String.valueOf(mc.getFps()));
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "FPS", String.valueOf(mc.getFps()));
		}
	}

	public static final class CpsHud extends HudModule {
		private final BoolSetting rightButton = add(new BoolSetting("Rechte Taste", true));

		public CpsHud() {
			super("CPS", "Klicks pro Sekunde", true, 0, 0.17, true);
		}

		private String value() {
			return rightButton.on() ? Cps.left() + " | " + Cps.right() : String.valueOf(Cps.left());
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("CPS", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "CPS", value());
		}
	}

	public static final class Ping extends HudModule {
		public Ping() {
			super("Ping", "Latenz zum Server", false, 0, 0.255, true);
		}

		private int latency(Minecraft mc) {
			if (mc.player == null || mc.getConnection() == null) return -1;
			PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			return info == null ? -1 : info.getLatency();
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			// Singleplayer has no real latency.
			return latency(mc) >= 0 && !mc.hasSingleplayerServer();
		}

		private String value(Minecraft mc) {
			int ms = latency(mc);
			return ms < 0 ? "–" : String.valueOf(ms);
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("ms", value(mc));
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "ms", value(mc));
		}
	}

	public static final class Clock extends HudModule {
		private final ModeSetting format = add(new ModeSetting("Format", "24 h", "24 h", "12 h"));

		public Clock() {
			super("Uhrzeit", "Echte Uhrzeit", false, 0, 0.34, true);
		}

		private String value() {
			return LocalTime.now().format(DateTimeFormatter.ofPattern(format.is("24 h") ? "HH:mm" : "h:mm a"));
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Uhr", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Uhr", value());
		}
	}

	public static final class Coordinates extends HudModule {
		private final BoolSetting direction = add(new BoolSetting("Richtung", true));

		public Coordinates() {
			super("Koordinaten", "Position und Blickrichtung", true, 0, 0.43, true);
		}

		private List<String[]> lines(Minecraft mc) {
			List<String[]> lines = new ArrayList<>();
			if (mc.player == null) {
				lines.add(new String[] {"XYZ", "0 64 0"});
				return lines;
			}
			lines.add(new String[] {"XYZ", String.format("%.0f %.0f %.0f", Math.floor(mc.player.getX()), Math.floor(mc.player.getY()), Math.floor(mc.player.getZ()))});
			if (direction.on()) {
				String facing = switch (mc.player.getDirection()) {
					case NORTH -> "Norden (-Z)";
					case SOUTH -> "Süden (+Z)";
					case WEST -> "Westen (-X)";
					case EAST -> "Osten (+X)";
					default -> "";
				};
				lines.add(new String[] {"Blick", facing});
			}
			return lines;
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return mc.player != null;
		}

		@Override
		public int width(Minecraft mc) {
			int max = 0;
			for (String[] l : lines(mc)) max = Math.max(max, Draw.width(l[0]) + 5 + Draw.width(l[1]));
			return max + (background.on() ? 10 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			return lines(mc).size() * 10 + (background.on() ? 6 : -1);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int p = background.on() ? 5 : 0, y = background.on() ? 4 : 0;
			for (String[] l : lines(mc)) {
				Draw.shadowText(g, l[0], p, y, Theme.ACCENT_2);
				Draw.shadowText(g, l[1], p + Draw.width(l[0]) + 5, y, Theme.TEXT);
				y += 10;
			}
		}
	}

	/** WASD, space and both mouse buttons; pressed keys light up in chroma. */
	public static final class Keystrokes extends HudModule {
		private final BoolSetting space = add(new BoolSetting("Leertaste", true));
		private final BoolSetting mouse = add(new BoolSetting("Maus", true));
		private static final int KEY = 20, GAP = 2;

		public Keystrokes() {
			super("Keystrokes", "Gedrückte Tasten anzeigen", true, 0, 1, true);
		}

		@Override
		public int width(Minecraft mc) {
			return KEY * 3 + GAP * 2;
		}

		@Override
		public int height(Minecraft mc) {
			int h = KEY * 2 + GAP;
			if (mouse.on()) h += GAP + 16;
			if (space.on()) h += GAP + 10;
			return h;
		}

		private void key(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean down, double phase) {
			int bg = down ? Theme.chroma(phase) : background.on() ? Theme.alpha(Theme.PANEL_SOLID, 0.72) : 0x00000000;
			Draw.round(g, x, y, w, h, 3, bg);
			// Long labels (the CPS keys) shrink to fit their key.
			float scale = Math.min(1f, (w - 4) / (float) Draw.width(label));
			float tw = Draw.width(label) * scale;
			Draw.scaledText(g, label, x + (w - tw) / 2f, y + (h - 8 * scale) / 2f, scale, down ? 0xFFFFFFFF : Theme.TEXT, true);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			var o = mc.options;
			int step = KEY + GAP;
			key(g, step, 0, KEY, KEY, "W", down(o.keyUp), 0);
			key(g, 0, step, KEY, KEY, "A", down(o.keyLeft), -0.05);
			key(g, step, step, KEY, KEY, "S", down(o.keyDown), -0.1);
			key(g, step * 2, step, KEY, KEY, "D", down(o.keyRight), -0.15);
			int y = step * 2;
			int full = width(mc);
			if (mouse.on()) {
				int half = (full - GAP) / 2;
				key(g, 0, y, half, 16, Cps.left() + " CPS", down(o.keyAttack), -0.2);
				key(g, half + GAP, y, full - half - GAP, 16, Cps.right() + " CPS", down(o.keyUse), -0.25);
				y += 16 + GAP;
			}
			if (space.on()) {
				int bg = down(o.keyJump) ? Theme.chroma(-0.3) : background.on() ? Theme.alpha(Theme.PANEL_SOLID, 0.72) : 0;
				Draw.round(g, 0, y, full, 10, 3, bg);
				g.fill(full / 2 - 10, y + 5, full / 2 + 10, y + 6, down(o.keyJump) ? 0xFFFFFFFF : Theme.TEXT);
			}
		}

		private static boolean down(KeyMapping key) {
			return key.isDown();
		}
	}

	/** Worn armour and the held item with remaining durability. */
	public static final class Armor extends HudModule {
		private final ModeSetting mode = add(new ModeSetting("Anzeige", "Zahl", "Zahl", "Prozent", "Nur Symbol"));
		private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

		public Armor() {
			super("Rüstung", "Rüstung und Haltbarkeit", true, 1, 0.78, false);
		}

		private List<ItemStack> items(Minecraft mc) {
			List<ItemStack> items = new ArrayList<>();
			if (mc.player == null) return items;
			for (EquipmentSlot slot : SLOTS) {
				ItemStack stack = mc.player.getItemBySlot(slot);
				if (!stack.isEmpty()) items.add(stack);
			}
			return items;
		}

		private String label(ItemStack stack) {
			if (mode.is("Nur Symbol")) return "";
			if (!stack.isDamageableItem()) return stack.getCount() > 1 ? String.valueOf(stack.getCount()) : "";
			int left = stack.getMaxDamage() - stack.getDamageValue();
			return mode.is("Prozent") ? Math.round(100.0 * left / stack.getMaxDamage()) + "%" : String.valueOf(left);
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !items(mc).isEmpty();
		}

		@Override
		public int width(Minecraft mc) {
			int max = Draw.width("1561");
			for (ItemStack s : items(mc)) max = Math.max(max, Draw.width(label(s)));
			return 18 + max + (background.on() ? 8 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, items(mc).size()) * 17 + (background.on() ? 4 : 0);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int p = background.on() ? 2 : 0, y = p;
			for (ItemStack stack : items(mc)) {
				g.item(stack, p, y);
				String label = label(stack);
				int color = Theme.TEXT;
				if (stack.isDamageableItem()) {
					double left = 1 - stack.getDamageValue() / (double) stack.getMaxDamage();
					color = left < 0.15 ? Theme.ERR : left < 0.4 ? 0xFFFBBF24 : Theme.TEXT;
				}
				Draw.shadowText(g, label, p + 19, y + 4, color);
				y += 17;
			}
			if (items(mc).isEmpty() && editing) Draw.shadowText(g, "Rüstung", 2, 4, Theme.MUTED);
		}
	}

	/** Active potion effects with level and remaining time. */
	public static final class Effects extends HudModule {
		private final ModeSetting sort = add(new ModeSetting("Sortierung", "Zeit", "Zeit", "Name"));

		public Effects() {
			super("Trankeffekte", "Aktive Effekte mit Restzeit", true, 1, 0.4, true);
		}

		private List<String[]> lines(Minecraft mc) {
			List<String[]> lines = new ArrayList<>();
			if (mc.player == null) return lines;
			List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
			if (sort.is("Zeit")) effects.sort(Comparator.comparingInt(MobEffectInstance::getDuration));
			else effects.sort(Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString()));
			for (MobEffectInstance e : effects) {
				String name = e.getEffect().value().getDisplayName().getString();
				if (e.getAmplifier() > 0) name += " " + roman(e.getAmplifier() + 1);
				String time;
				if (e.isInfiniteDuration()) time = "∞";
				else {
					int secs = e.getDuration() / 20;
					time = String.format("%d:%02d", secs / 60, secs % 60);
				}
				lines.add(new String[] {name, time, String.valueOf(e.getEffect().value().getColor())});
			}
			return lines;
		}

		private static String roman(int n) {
			return switch (n) {
				case 2 -> "II";
				case 3 -> "III";
				case 4 -> "IV";
				case 5 -> "V";
				default -> String.valueOf(n);
			};
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !lines(mc).isEmpty();
		}

		@Override
		public int width(Minecraft mc) {
			int max = Draw.width("Trankeffekte");
			for (String[] l : lines(mc)) max = Math.max(max, Draw.width(l[0]) + 6 + Draw.width(l[1]));
			return max + (background.on() ? 12 : 4);
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, lines(mc).size()) * 10 + (background.on() ? 6 : 0);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			List<String[]> lines = lines(mc);
			int width = width(mc);
			box(g, width, height(mc));
			int y = background.on() ? 4 : 0, p = background.on() ? 5 : 0;
			for (String[] l : lines) {
				g.fill(p - 2 + (background.on() ? 0 : 2), y, p - 1 + (background.on() ? 0 : 2), y + 8, 0xFF000000 | Integer.parseInt(l[2]));
				Draw.shadowText(g, l[0], p + 2, y, Theme.TEXT);
				Draw.shadowText(g, l[1], width - p - Draw.width(l[1]), y, Theme.MUTED);
				y += 10;
			}
			if (lines.isEmpty() && editing) Draw.shadowText(g, "Trankeffekte", p + 2, y, Theme.MUTED);
		}
	}
}
