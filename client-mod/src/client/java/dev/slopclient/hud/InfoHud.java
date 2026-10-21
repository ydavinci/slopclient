package dev.slopclient.hud;

import dev.slopclient.SlopClient;
import dev.slopclient.mixin.BossHealthOverlayAccessor;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * HUD elements of M13. Like the rest of the HUD they only show what the game already
 * tells the player; the reach display can be switched off by servers (`slopclient:disable`).
 */
public final class InfoHud {
	private InfoHud() {
	}

	// ---------- compass ----------

	/** A strip with the cardinal directions that scrolls with the view, plus the exact heading. */
	public static final class Compass extends HudModule {
		private static final int WIDTH = 140, HEIGHT = 20;
		private static final String[] NAMES = {"S", "SW", "W", "NW", "N", "NO", "O", "SO"};
		private final BoolSetting degrees = add(new BoolSetting("Grad anzeigen", true));

		public Compass() {
			super("Kompass", "Himmelsrichtung als mitlaufende Leiste", false, 0.5, 0, true);
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return mc.player != null;
		}

		@Override
		public int width(Minecraft mc) {
			return WIDTH;
		}

		@Override
		public int height(Minecraft mc) {
			return degrees.on() ? HEIGHT + 9 : HEIGHT;
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, WIDTH, HEIGHT);
			// Minecraft's yaw: 0 = south, 90 = west.
			float yaw = mc.player == null ? 180 : Mth.wrapDegrees(mc.player.getYRot());
			float pxPerDegree = WIDTH / 120f;
			int centre = WIDTH / 2;
			g.enableScissor(0, 0, WIDTH, HEIGHT);
			for (int step = -8; step <= 8; step++) {
				float heading = Math.round(yaw / 15f) * 15f + step * 15f;
				float x = centre + Mth.wrapDegrees(heading - yaw) * pxPerDegree;
				int h = Math.floorMod(Math.round(heading), 360);
				if (h % 45 == 0) {
					String name = NAMES[h / 45];
					boolean main = name.length() == 1;
					int color = name.equals("N") ? Theme.ACCENT_2 : main ? Theme.TEXT : Theme.MUTED;
					Draw.shadowText(g, name, Math.round(x - Draw.width(name) / 2f), 6, color);
				} else {
					g.fill(Math.round(x), 9, Math.round(x) + 1, 12, Theme.alpha(Theme.MUTED, 0.6));
				}
			}
			g.disableScissor();
			// The fixed marker in the middle.
			g.fill(centre, 1, centre + 1, 4, Theme.chroma(0));
			g.fill(centre, HEIGHT - 4, centre + 1, HEIGHT - 1, Theme.chroma(0));
			if (degrees.on()) {
				String d = Math.round(Mth.positiveModulo(yaw + 180, 360)) + "°";
				Draw.shadowText(g, d, centre - Draw.width(d) / 2, HEIGHT + 1, Theme.MUTED);
			}
		}
	}

	// ---------- item counter ----------

	/** How many arrows, totems, pearls … are in the inventory. */
	public static final class ItemCounter extends HudModule {
		private record Kind(String label, Item[] items, BoolSetting on) {
		}

		private final List<Kind> kinds = new ArrayList<>();
		private final ModeSetting layout = add(new ModeSetting("Anordnung", "Spalte", "Spalte", "Zeile"));
		private final BoolSetting hideEmpty = add(new BoolSetting("Leere ausblenden", true));

		public ItemCounter() {
			super("Item-Zähler", "Pfeile, Totems, Perlen und mehr im Inventar", false, 1, 1, true);
			kind("Pfeile", true, Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW);
			kind("Totems", true, Items.TOTEM_OF_UNDYING);
			kind("Enderperlen", true, Items.ENDER_PEARL);
			kind("Goldäpfel", true, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);
			kind("Wurftränke", false, Items.SPLASH_POTION);
			kind("Feuerwerk", false, Items.FIREWORK_ROCKET);
			kind("Spinnweben", false, Items.COBWEB);
			kind("Erfahrungsfläschchen", false, Items.EXPERIENCE_BOTTLE);
		}

		private void kind(String label, boolean on, Item... items) {
			kinds.add(new Kind(label, items, add(new BoolSetting(label, on))));
		}

		/** (icon, count) for every shown kind. */
		private List<Map.Entry<ItemStack, Integer>> rows(Minecraft mc) {
			List<Map.Entry<ItemStack, Integer>> rows = new ArrayList<>();
			for (Kind k : kinds) {
				if (!k.on.on()) continue;
				int count = 0;
				if (mc.player != null) {
					var inv = mc.player.getInventory();
					for (int i = 0; i < inv.getContainerSize(); i++) {
						ItemStack s = inv.getItem(i);
						for (Item item : k.items) if (s.is(item)) count += s.getCount();
					}
				}
				if (count == 0 && hideEmpty.on() && mc.player != null) continue;
				// Item data is only bound inside a world (the HUD editor also opens from the title screen).
				rows.add(Map.entry(mc.level == null ? ItemStack.EMPTY : new ItemStack(k.items[0]), count));
			}
			return rows;
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !rows(mc).isEmpty();
		}

		private int cell(Minecraft mc) {
			int max = Draw.width("0");
			for (var r : rows(mc)) max = Math.max(max, Draw.width(String.valueOf(r.getValue())));
			return 18 + max + 4;
		}

		@Override
		public int width(Minecraft mc) {
			int n = Math.max(1, rows(mc).size());
			return (layout.is("Zeile") ? n * cell(mc) : cell(mc)) + (background.on() ? 4 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			int n = Math.max(1, rows(mc).size());
			return (layout.is("Zeile") ? 17 : n * 17) + (background.on() ? 4 : 0);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int p = background.on() ? 2 : 0, x = p, y = p, cell = cell(mc);
			var rows = rows(mc);
			for (var r : rows) {
				if (!r.getKey().isEmpty()) g.item(r.getKey(), x, y);
				else Draw.round(g, x + 2, y + 2, 12, 12, 3, Theme.alpha(Theme.ACCENT, 0.5));
				int count = r.getValue();
				Draw.shadowText(g, String.valueOf(count), x + 19, y + 4, count == 0 ? Theme.ERR : Theme.TEXT);
				if (layout.is("Zeile")) x += cell;
				else y += 17;
			}
			if (rows.isEmpty() && editing) Draw.shadowText(g, "Items", p + 2, p + 4, Theme.MUTED);
		}
	}

	// ---------- combo and reach ----------

	/**
	 * Watches the player's own hits: a hit counts when the attacked entity shows its
	 * hurt animation shortly after the swing, so misses and blocked hits do not.
	 */
	public static final class Hits {
		private static Entity target;
		private static long swungAt;
		private static double swingReach;
		private static int combo;
		private static long lastHitAt;
		private static double reach = -1;
		private static int ownHurt;

		private Hits() {
		}

		/** From the attack callback (client side). */
		public static InteractionResult attacked(Entity entity) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) return InteractionResult.PASS;
			target = entity;
			swungAt = System.currentTimeMillis();
			swingReach = distance(mc.player.getEyePosition(), entity.getBoundingBox());
			return InteractionResult.PASS;
		}

		private static double distance(Vec3 eye, AABB box) {
			double x = Mth.clamp(eye.x, box.minX, box.maxX), y = Mth.clamp(eye.y, box.minY, box.maxY), z = Mth.clamp(eye.z, box.minZ, box.maxZ);
			return eye.distanceTo(new Vec3(x, y, z));
		}

		public static void tick(Minecraft mc) {
			long now = System.currentTimeMillis();
			if (mc.player == null) {
				combo = 0;
				reach = -1;
				target = null;
				return;
			}
			if (target instanceof LivingEntity living && now - swungAt < 400 && living.hurtTime > 0 && living.hurtTime >= living.hurtDuration - 2) {
				combo = now - lastHitAt < 2000 ? combo + 1 : 1;
				lastHitAt = now;
				reach = swingReach;
				target = null;
			}
			// Getting hit or two seconds without a hit ends the combo.
			int hurt = mc.player.hurtTime;
			if (hurt > ownHurt) combo = 0;
			ownHurt = hurt;
			if (now - lastHitAt > 2000) combo = 0;
		}

		static int combo() {
			return combo;
		}

		static double reach() {
			return System.currentTimeMillis() - lastHitAt < 3000 ? reach : -1;
		}
	}

	public static final class Combo extends HudModule {
		public Combo() {
			super("Combo", "Treffer in Folge, ohne selbst getroffen zu werden", false, 0.5, 0.62, true);
		}

		private String value() {
			return Hits.combo() == 0 ? "–" : String.valueOf(Hits.combo());
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Combo", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Combo", value());
		}
	}

	/** Distance of the last own hit, measured to the target's hitbox. */
	public static final class Reach extends HudModule {
		public Reach() {
			super("Reichweite", "Abstand des letzten eigenen Treffers", false, 0.5, 0.67, true);
		}

		private String value() {
			double r = Hits.reach();
			return r < 0 ? "–" : String.format(Locale.GERMAN, "%.2f", r);
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Blöcke", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Blöcke", value());
		}
	}

	// ---------- cooldowns ----------

	/** Item cooldowns (pearl, chorus fruit, shield …) as bars with the time left. */
	public static final class Cooldowns extends HudModule {
		private record Seen(long at, float percent) {
		}

		private final Map<Identifier, Seen> seen = new HashMap<>();

		public Cooldowns() {
			super("Cooldowns", "Abklingzeiten von Perle, Schild, Chorusfrucht …", false, 0.5, 0.74, true);
		}

		/** Items in hotbar and offhand that are cooling down, one per cooldown group. */
		private Map<Identifier, ItemStack> cooling(Minecraft mc) {
			Map<Identifier, ItemStack> out = new LinkedHashMap<>();
			if (mc.player == null) return out;
			var cooldowns = mc.player.getCooldowns();
			List<ItemStack> stacks = new ArrayList<>();
			for (int i = 0; i < 9; i++) stacks.add(mc.player.getInventory().getItem(i));
			stacks.add(mc.player.getItemBySlot(EquipmentSlot.OFFHAND));
			for (ItemStack s : stacks) {
				if (s.isEmpty() || !cooldowns.isOnCooldown(s)) continue;
				out.putIfAbsent(cooldowns.getCooldownGroup(s), s);
			}
			return out;
		}

		@Override
		public void tick(Minecraft mc) {
			var cooling = cooling(mc);
			seen.keySet().retainAll(cooling.keySet());
			long now = System.currentTimeMillis();
			for (var e : cooling.entrySet()) {
				float p = mc.player.getCooldowns().getCooldownPercent(e.getValue(), 0);
				seen.putIfAbsent(e.getKey(), new Seen(now, p));
			}
		}

		/** Seconds left, extrapolated from how fast the bar went down so far. */
		private String left(Identifier group, float percent) {
			Seen s = seen.get(group);
			if (s == null || s.percent <= percent + 0.001f) return "";
			double elapsed = (System.currentTimeMillis() - s.at) / 1000.0;
			double secs = elapsed * percent / (s.percent - percent);
			return String.format(Locale.GERMAN, "%.1fs", secs);
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !cooling(mc).isEmpty();
		}

		@Override
		public int width(Minecraft mc) {
			return 90 + (background.on() ? 4 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, cooling(mc).size()) * 17 + (background.on() ? 4 : 0);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int p = background.on() ? 2 : 0, y = p;
			var cooling = cooling(mc);
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			for (var e : cooling.entrySet()) {
				float percent = mc.player.getCooldowns().getCooldownPercent(e.getValue(), partial);
				g.item(e.getValue(), p, y);
				int barX = p + 20, barW = 90 - 22 - p;
				Draw.round(g, barX, y + 11, barW, 3, 1, Theme.alpha(Theme.TEXT, 0.12));
				Draw.round(g, barX, y + 11, Math.max(2, Math.round(barW * percent)), 3, 1, Theme.chroma(0));
				Draw.shadowText(g, left(e.getKey(), percent), barX, y + 1, Theme.TEXT);
				y += 17;
			}
			if (cooling.isEmpty() && editing) Draw.shadowText(g, "Cooldowns", p + 2, p + 4, Theme.MUTED);
		}
	}

	// ---------- boss bar ----------

	/** Vanilla's boss bars, but movable, scalable or hidden. */
	public static final class BossBar extends HudModule {
		public final BoolSetting hide = add(new BoolSetting("Ausblenden", false));

		public BossBar() {
			super("Bossleiste", "Bossleiste verschieben, skalieren oder ausblenden", false, 0.5, 0, false);
		}

		private int bars(Minecraft mc) {
			return ((BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).slopclient$events().size();
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !hide.on() && bars(mc) > 0;
		}

		@Override
		public int width(Minecraft mc) {
			return 182;
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, bars(mc)) * 19;
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			if (bars(mc) == 0) {
				if (editing) {
					Draw.round(g, 0, 10, 182, 5, 2, Theme.alpha(Theme.ACCENT, 0.6));
					String s = "Bossleiste";
					Draw.shadowText(g, s, 91 - Draw.width(s) / 2, 0, Theme.MUTED);
				}
				return;
			}
			if (hide.on()) return;
			// Vanilla draws centred at the top starting 3 px down: shift that to (0, 0).
			g.pose().pushMatrix();
			g.pose().translate(-(g.guiWidth() / 2f - 91), -3);
			mc.gui.hud.getBossOverlay().extractRenderState(g);
			g.pose().popMatrix();
		}
	}

	// ---------- small info lines ----------

	public static final class Memory extends HudModule {
		public Memory() {
			super("Arbeitsspeicher", "Belegter und maximaler RAM von Minecraft", false, 0, 0.52, true);
		}

		private String value() {
			Runtime r = Runtime.getRuntime();
			long used = (r.totalMemory() - r.freeMemory()) >> 20, max = r.maxMemory() >> 20;
			return used + " / " + max + " MB";
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("RAM", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "RAM", value());
		}
	}

	public static final class Biome extends HudModule {
		public Biome() {
			super("Biom", "Biom an deiner Position", false, 0, 0.76, true);
		}

		private String value(Minecraft mc) {
			if (mc.player == null || mc.level == null) return "Ebene";
			var key = mc.level.getBiome(mc.player.blockPosition()).unwrapKey();
			if (key.isEmpty()) return "?";
			Identifier id = key.get().identifier();
			return Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString();
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return mc.player != null;
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Biom", value(mc));
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Biom", value(mc));
		}
	}

	public static final class Day extends HudModule {
		public Day() {
			super("Tag", "Tag in der Welt", false, 0, 0.84, true);
		}

		private String value(Minecraft mc) {
			return mc.level == null ? "1" : String.valueOf(mc.level.getOverworldClockTime() / 24000L + 1);
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return mc.level != null;
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Tag", value(mc));
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Tag", value(mc));
		}
	}

	/** Start, stop and reset with one key (unbound by default, see controls). */
	public static final class Stopwatch extends HudModule {
		private KeyMapping key;
		private long startedAt, stoppedAt;
		private boolean running, stopped;

		public Stopwatch() {
			super("Stoppuhr", "Taste: starten, anhalten, zurücksetzen", false, 0, 0.9, true);
		}

		public void bind(KeyMapping key) {
			this.key = key;
		}

		@Override
		public void tick(Minecraft mc) {
			while (key != null && key.consumeClick()) {
				long now = System.currentTimeMillis();
				if (running) {
					running = false;
					stopped = true;
					stoppedAt = now;
				} else {
					// From stopped or idle: start again at zero.
					running = true;
					stopped = false;
					startedAt = now;
				}
			}
		}

		private String value() {
			long ms = running ? System.currentTimeMillis() - startedAt : stopped ? stoppedAt - startedAt : 0;
			return String.format(Locale.GERMAN, "%d:%02d,%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10);
		}

		@Override
		public int width(Minecraft mc) {
			return labelValueWidth("Zeit", value());
		}

		@Override
		public int height(Minecraft mc) {
			return lineHeight();
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			labelValue(g, "Zeit", value());
		}
	}

	// ---------- resource packs ----------

	/** The active resource packs with their icons, top pack first. */
	public static final class Packs extends HudModule {
		private static final Map<String, Identifier> ICONS = new HashMap<>();
		private static final Identifier NO_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");

		public Packs() {
			super("Resourcepacks", "Aktive Resourcepacks mit Icon", false, 1, 0.2, true);
		}

		private List<Pack> packs(Minecraft mc) {
			List<Pack> out = new ArrayList<>();
			for (Pack p : mc.getResourcePackRepository().getSelectedPacks()) {
				// Vanilla and mod resources are always there; only real packs are interesting.
				String id = p.getId();
				if (id.equals("vanilla") || id.equals("fabric") || id.startsWith("fabric-") || p.isRequired()) continue;
				out.add(0, p);
			}
			return out;
		}

		private static Identifier icon(Minecraft mc, Pack pack) {
			return ICONS.computeIfAbsent(pack.getId(), id -> {
				try (var meta = pack.openMetadata()) {
					var supplier = meta.getRootResource("pack.png");
					if (supplier == null) return NO_ICON;
					try (InputStream in = supplier.get()) {
						NativeImage img = NativeImage.read(in);
						Identifier tex = Identifier.fromNamespaceAndPath(SlopClient.ID, "pack/" + Integer.toHexString(id.hashCode()));
						mc.getTextureManager().register(tex, new DynamicTexture(() -> "SlopClient pack icon " + id, img));
						return tex;
					}
				} catch (Exception e) {
					return NO_ICON;
				}
			});
		}

		@Override
		public boolean hasContent(Minecraft mc) {
			return !packs(mc).isEmpty();
		}

		@Override
		public int width(Minecraft mc) {
			int max = Draw.width("Resourcepacks");
			for (Pack p : packs(mc)) max = Math.max(max, Math.min(140, Draw.width(p.getTitle().getString())));
			return max + 16 + (background.on() ? 10 : 0);
		}

		@Override
		public int height(Minecraft mc) {
			return Math.max(1, packs(mc).size()) * 14 + (background.on() ? 6 : 0);
		}

		@Override
		protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
			box(g, width(mc), height(mc));
			int p = background.on() ? 5 : 0, y = background.on() ? 3 : 0;
			List<Pack> packs = packs(mc);
			for (Pack pack : packs) {
				g.blit(RenderPipelines.GUI_TEXTURED, icon(mc, pack), p, y, 0, 0, 12, 12, 12, 12);
				String title = pack.getTitle().getString();
				while (Draw.width(title) > 140 && title.length() > 4) title = title.substring(0, title.length() - 2);
				if (!title.equals(pack.getTitle().getString())) title += "…";
				Draw.shadowText(g, title, p + 16, y + 2, Theme.TEXT);
				y += 14;
			}
			if (packs.isEmpty() && editing) Draw.shadowText(g, "Resourcepacks", p + 16, y + 2, Theme.MUTED);
		}
	}
}
