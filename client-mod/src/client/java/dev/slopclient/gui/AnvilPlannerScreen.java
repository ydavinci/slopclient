package dev.slopclient.gui;

import dev.slopclient.anvil.AnvilPlanner;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Anvil planner: pick an item (from the inventory or any enchantable item), tick the
 * enchantments it should get, and see the cheapest order to combine the books in an
 * anvil ({@link AnvilPlanner}). Only enchantments that fit the item, and fit together,
 * can be chosen.
 */
public final class AnvilPlannerScreen extends Screen {
	private static final int TOP = 40, CELL = 20, ROW = 14, STEP = 22, GAP = 8;

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private List<Holder<Enchantment>> enchantments = List.of();
	private List<ItemStack> inventory = List.of();
	private List<ItemStack> all;
	private boolean showAll;
	private String query = "";
	private int itemScroll, enchantScroll, stepScroll;

	private ItemStack item;
	/** Chosen enchantments with their level, in the order they were ticked. */
	private final Map<Holder<Enchantment>, Integer> chosen = new LinkedHashMap<>();
	private AnvilPlanner.Plan plan;
	private String hint = "";

	public AnvilPlannerScreen(Screen parent) {
		super(Component.literal("Amboss-Planer"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (minecraft.level == null) return;
		enchantments = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
			.<Holder<Enchantment>>map(h -> h)
			.sorted(Comparator.comparing(h -> h.value().description().getString()))
			.toList();
		List<ItemStack> inv = new ArrayList<>();
		if (minecraft.player != null) {
			var items = minecraft.player.getInventory();
			for (int i = 0; i < items.getContainerSize(); i++) {
				ItemStack stack = items.getItem(i);
				if (!stack.isEmpty() && enchantable(stack)) inv.add(stack.copy());
			}
		}
		inventory = inv;
		if (inventory.isEmpty()) showAll = true;
	}

	/** Every item some enchantment fits on (books aside), built when the "Alle" tab is first shown. */
	private List<ItemStack> all() {
		if (all == null) {
			List<ItemStack> list = new ArrayList<>();
			for (var item : BuiltInRegistries.ITEM) {
				if (item == Items.BOOK || item == Items.ENCHANTED_BOOK) continue;
				ItemStack stack = new ItemStack(item);
				if (enchantable(stack)) list.add(stack);
			}
			all = list;
		}
		return all;
	}

	private boolean enchantable(ItemStack stack) {
		return enchantments.stream().anyMatch(e -> e.value().canEnchant(stack));
	}

	private List<ItemStack> items() {
		String q = query.toLowerCase();
		return (showAll ? all() : inventory).stream()
			.filter(s -> q.isEmpty() || s.getHoverName().getString().toLowerCase().contains(q))
			.toList();
	}

	/** Enchantments that fit the chosen item. */
	private List<Holder<Enchantment>> fitting() {
		if (item == null) return List.of();
		return enchantments.stream().filter(e -> e.value().canEnchant(item)).toList();
	}

	private int existing(Holder<Enchantment> e) {
		return item == null ? 0 : item.getEnchantments().getLevel(e);
	}

	/** What rules `e` out: a chosen enchantment or one the item already has; null when it fits. */
	private Holder<Enchantment> conflict(Holder<Enchantment> e) {
		for (var other : chosen.keySet()) if (!other.equals(e) && !Enchantment.areCompatible(e, other)) return other;
		for (var other : item.getEnchantments().keySet()) if (!other.equals(e) && !Enchantment.areCompatible(e, other)) return other;
		return null;
	}

	private void select(ItemStack stack) {
		item = stack;
		chosen.clear();
		enchantScroll = 0;
		replan();
	}

	private void replan() {
		stepScroll = 0;
		List<AnvilPlanner.Book> books = new ArrayList<>();
		chosen.forEach((e, level) -> {
			if (existing(e) >= level) return;
			// As the right input every book's enchantment costs half its anvil cost, at least 1.
			int value = Math.max(1, e.value().getAnvilCost() / 2) * level;
			books.add(new AnvilPlanner.Book(Enchantment.getFullname(e, level).getString(), value));
		});
		plan = item == null || books.isEmpty() ? null
			: AnvilPlanner.plan(item.getHoverName().getString(), item.getOrDefault(DataComponents.REPAIR_COST, 0), books);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, 0x7009080E, 0xA009080E);
	}

	// ---------- layout ----------

	private int x0() {
		return (width - totalWidth()) / 2;
	}

	private int totalWidth() {
		return Math.min(width - 20, 640);
	}

	private int bottom() {
		return height - 26;
	}

	private int leftW() {
		return 6 + Math.max(4, Math.min(7, (totalWidth() / 4 - 12) / CELL)) * CELL + 6;
	}

	private int midX() {
		return x0() + leftW() + GAP;
	}

	private int midW() {
		return (totalWidth() - leftW() - 2 * GAP) * 9 / 20;
	}

	private int rightX() {
		return midX() + midW() + GAP;
	}

	private int rightW() {
		return x0() + totalWidth() - rightX();
	}

	private int columns() {
		return (leftW() - 12) / CELL;
	}

	private int gridTop() {
		return TOP + 44;
	}

	// ---------- drawing ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		double t = (System.currentTimeMillis() - openedAt) / 1000.0;
		Chrome.titleBar(g, minecraft, Theme.ease(t / 0.5), "Amboss-Planer");
		hint = "";
		drawItems(g, mouseX, mouseY);
		drawEnchantments(g, mouseX, mouseY);
		drawPlan(g, mouseX, mouseY);
		String line = !query.isEmpty() ? "Suche: " + query + "_" : !hint.isEmpty() ? hint
			: "Gegenstand wählen · Verzauberungen anklicken · Rechtsklick: Stufe ändern · Tippen: suchen";
		Draw.text(g, ellipsize(line, width - 20), 10, height - 18, query.isEmpty() && hint.isEmpty() ? Theme.DIM : Theme.ACCENT_2);
	}

	private void panel(GuiGraphicsExtractor g, int x, int w, String title, int index) {
		int h = bottom() - TOP;
		Draw.glow(g, x, TOP, w, h, 5, 0xFF000000, 0.35);
		Draw.round(g, x, TOP, w, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.86));
		Draw.roundOutline(g, x, TOP, w, h, 7, Theme.LINE_2);
		g.fill(x + 7, TOP, x + w - 7, TOP + 2, Theme.chroma(-index * 0.15));
		Draw.shadowText(g, ellipsize(title, w - 14), x + 7, TOP + 8, Theme.TEXT);
	}

	private void drawItems(GuiGraphicsExtractor g, int mx, int my) {
		int x = x0(), w = leftW();
		panel(g, x, w, "Gegenstand", 0);
		int tabW = (w - 18) / 2;
		tab(g, x + 6, TOP + 22, tabW, "Inventar", !showAll, mx, my);
		tab(g, x + 12 + tabW, TOP + 22, tabW, "Alle", showAll, mx, my);

		List<ItemStack> list = items();
		int cols = columns(), top = gridTop(), rows = (bottom() - 6 - top) / CELL;
		itemScroll = Math.max(0, Math.min(itemScroll, Math.max(0, (list.size() + cols - 1) / cols - rows)));
		if (list.isEmpty()) {
			String empty = showAll ? "Nichts gefunden" : "Nichts Verzauberbares";
			Draw.scaledText(g, empty, x + 7, top + 4, 0.85f, Theme.DIM, false);
		}
		for (int i = itemScroll * cols; i < list.size() && i < (itemScroll + rows) * cols; i++) {
			int cx = x + 6 + (i % cols) * CELL, cy = top + (i / cols - itemScroll) * CELL;
			ItemStack stack = list.get(i);
			boolean hover = Draw.inside(mx, my, cx, cy, CELL, CELL);
			boolean selected = item != null && ItemStack.isSameItemSameComponents(stack, item);
			if (selected) Draw.chromaRing(g, cx, cy, CELL, CELL, 1);
			Draw.round(g, cx + 1, cy + 1, CELL - 2, CELL - 2, 4, selected ? Theme.alpha(Theme.ACCENT, 0.3) : hover ? 0x22FFFFFF : 0x10FFFFFF);
			g.item(stack, cx + 2, cy + 2);
			if (hover) hint = stack.getHoverName().getString() + (stack.isEnchanted() ? " (schon verzaubert)" : "");
		}
	}

	private void tab(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean on, int mx, int my) {
		boolean hover = Draw.inside(mx, my, x, y, w, 16);
		if (on) Draw.chromaFill(g, x, y, w, 16, 5, 0.9, 0);
		else Draw.round(g, x, y, w, 16, 5, hover ? 0x24FFFFFF : 0x12FFFFFF);
		Draw.text(g, label, x + (w - Draw.width(label)) / 2, y + 4, on ? 0xFFFFFFFF : Theme.MUTED);
	}

	private void drawEnchantments(GuiGraphicsExtractor g, int mx, int my) {
		int x = midX(), w = midW();
		panel(g, x, w, item == null ? "Verzauberungen" : item.getHoverName().getString(), 1);
		if (item == null) {
			Draw.scaledText(g, "Wähle links einen Gegenstand.", x + 7, TOP + 26, 0.85f, Theme.DIM, false);
			return;
		}
		List<Holder<Enchantment>> list = fitting();
		int top = TOP + 24, rows = (bottom() - 6 - top) / ROW;
		enchantScroll = Math.max(0, Math.min(enchantScroll, Math.max(0, list.size() - rows)));
		for (int i = enchantScroll; i < list.size() && i < enchantScroll + rows; i++) {
			var e = list.get(i);
			int ry = top + (i - enchantScroll) * ROW;
			boolean hover = Draw.inside(mx, my, x + 3, ry, w - 6, ROW);
			Integer level = chosen.get(e);
			int has = existing(e), max = e.value().getMaxLevel();
			Holder<Enchantment> conflict = level == null ? conflict(e) : null;
			boolean done = has >= max;
			boolean blocked = conflict != null || done;
			if (level != null) Draw.round(g, x + 3, ry, w - 6, ROW, 4, Theme.alpha(Theme.ACCENT, hover ? 0.26 : 0.16));
			else if (hover && !blocked) Draw.round(g, x + 3, ry, w - 6, ROW, 4, 0x0CFFFFFF);
			Chrome.toggle(g, x + 7, ry + 3, level != null, blocked ? 0.35 : 1);
			String name = e.value().description().getString();
			int nameColor = level != null ? Theme.TEXT : blocked ? Theme.alpha(Theme.DIM, 0.8) : Theme.MUTED;
			String badge = level != null ? roman(level) : has > 0 ? "hat " + roman(has) : roman(max);
			int bw = Math.round(Draw.width(badge) * 0.85f);
			Draw.text(g, ellipsize(name, w - 40 - bw), x + 28, ry + 3, nameColor);
			Draw.scaledText(g, badge, x + w - 8 - bw, ry + 4, 0.85f, level != null ? Theme.ACCENT_2 : Theme.DIM, false);
			if (hover) {
				if (done) hint = name + " ist schon auf der höchsten Stufe.";
				else if (conflict != null) hint = name + " passt nicht zu " + conflict.value().description().getString() + ".";
				else if (level != null && max > 1) hint = "Rechtsklick: Stufe ändern (" + roman(level) + " von " + roman(max) + ")";
				else hint = "Anklicken, um " + name + " " + roman(max) + " hinzuzufügen";
			}
		}
		if (list.isEmpty()) Draw.scaledText(g, "Keine Verzauberung passt.", x + 7, top + 2, 0.85f, Theme.DIM, false);
	}

	private void drawPlan(GuiGraphicsExtractor g, int mx, int my) {
		int x = rightX(), w = rightW();
		panel(g, x, w, "Reihenfolge im Amboss", 2);
		if (plan == null) {
			String text = item == null ? "Erst einen Gegenstand wählen." : "Wähle die Verzauberungen, die drauf sollen.";
			Draw.scaledText(g, text, x + 7, TOP + 26, 0.85f, Theme.DIM, false);
			return;
		}
		int footer = 30, top = TOP + 24, rows = (bottom() - footer - top) / STEP;
		stepScroll = Math.max(0, Math.min(stepScroll, Math.max(0, plan.steps().size() - rows)));
		for (int i = stepScroll; i < plan.steps().size() && i < stepScroll + rows; i++) {
			var step = plan.steps().get(i);
			int ry = top + (i - stepScroll) * STEP;
			boolean tooMuch = step.levels() >= AnvilPlanner.TOO_EXPENSIVE;
			String cost = step.levels() + " Level";
			int cw = Draw.width(cost) + 10;
			Draw.round(g, x + 4, ry, w - 8, STEP - 3, 5, step.item() ? Theme.alpha(Theme.ACCENT, 0.14) : 0x12FFFFFF);
			Draw.text(g, (i + 1) + ".", x + 9, ry + 6, Theme.DIM);
			int tx = x + 24, tw = w - 24 - cw - 10;
			Draw.text(g, ellipsize(step.left(), tw), tx, ry + 2, Theme.TEXT);
			String right = "+ " + step.right() + (step.item() ? "" : "  → " + step.result());
			Draw.scaledText(g, ellipsize(right, Math.round(tw / 0.85f)), tx, ry + 11, 0.85f, Theme.ACCENT_2, false);
			Draw.round(g, x + w - 8 - cw, ry + 3, cw, 13, 4, tooMuch ? Theme.alpha(Theme.ERR, 0.3) : 0x18FFFFFF);
			Draw.text(g, cost, x + w - 8 - cw + 5, ry + 6, tooMuch ? Theme.ERR : Theme.TEXT);
			if (Draw.inside(mx, my, x + 4, ry, w - 8, STEP - 3)) {
				hint = "Links: " + step.left() + " · Rechts: " + step.right();
			}
		}
		int fy = bottom() - footer + 4;
		g.fill(x + 7, fy - 4, x + w - 7, fy - 3, Theme.LINE_2);
		String total = "Gesamt: " + plan.levels() + " Level · " + plan.xp() + " XP";
		Draw.chromaText(g, total, x + 7, fy + 2, 0, false);
		String note = plan.possible() ? "Jeder Schritt unter 40 Level" : "Zu teuer: ein Schritt kostet 40+ Level";
		Draw.scaledText(g, note, x + 7, fy + 13, 0.8f, plan.possible() ? Theme.DIM : Theme.ERR, false);
		int below = plan.steps().size() - stepScroll - rows;
		if (below > 0) {
			String more = below + " weitere (Mausrad)";
			Draw.scaledText(g, more, x + w - 8 - Draw.width(more) * 0.8f, fy + 13, 0.8f, Theme.ACCENT_2, false);
		}
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int raw = event.button();
		// As in the ClickGUI: 26.3's SDL3 backend reports 1 or 0 for left, 3 for right.
		int button = raw == 3 ? 1 : raw == 0 || raw == 1 ? 0 : -1;
		if (button < 0) return super.mouseClicked(event, doubleClick);

		int x = x0(), w = leftW(), tabW = (w - 18) / 2;
		if (Draw.inside(mx, my, x + 6, TOP + 22, tabW, 16)) return tab(false);
		if (Draw.inside(mx, my, x + 12 + tabW, TOP + 22, tabW, 16)) return tab(true);
		List<ItemStack> list = items();
		int cols = columns(), top = gridTop();
		if (Draw.inside(mx, my, x + 6, top, cols * CELL, bottom() - 6 - top)) {
			int i = (itemScroll + (int) (my - top) / CELL) * cols + (int) (mx - x - 6) / CELL;
			if (i < list.size()) select(list.get(i));
			return true;
		}

		if (item != null && Draw.inside(mx, my, midX() + 3, TOP + 24, midW() - 6, bottom() - 6 - TOP - 24)) {
			List<Holder<Enchantment>> fit = fitting();
			int i = enchantScroll + (int) (my - TOP - 24) / ROW;
			if (i < fit.size()) clickEnchantment(fit.get(i), button);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private boolean tab(boolean all) {
		showAll = all;
		itemScroll = 0;
		return true;
	}

	private void clickEnchantment(Holder<Enchantment> e, int button) {
		int max = e.value().getMaxLevel(), has = existing(e);
		Integer level = chosen.get(e);
		if (level == null) {
			if (conflict(e) != null || has >= max || chosen.size() >= AnvilPlanner.MAX_BOOKS) return;
			chosen.put(e, max);
		} else if (button == 1 && max > 1) {
			// Right click steps the level down, wrapping round to the highest; never below what the item has.
			int next = level - 1 <= has ? max : level - 1;
			chosen.put(e, next);
		} else {
			chosen.remove(e);
		}
		replan();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int d = scrollY > 0 ? -1 : 1;
		if (mx < midX()) itemScroll = Math.max(0, itemScroll + d);
		else if (mx < rightX()) enchantScroll = Math.max(0, enchantScroll + d);
		else stepScroll = Math.max(0, stepScroll + d);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
			query = query.substring(0, query.length() - 1);
			itemScroll = 0;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (event.isAllowedChatCharacter() && query.length() < 24) {
			query += event.codepointAsString();
			itemScroll = 0;
			return true;
		}
		return false;
	}

	// ---------- helpers ----------

	private static String roman(int level) {
		return Component.translatable("enchantment.level." + level).getString();
	}

	private static String ellipsize(String s, int maxWidth) {
		if (Draw.width(s) <= maxWidth) return s;
		String dots = "…";
		int end = s.length();
		while (end > 0 && Draw.width(s.substring(0, end) + dots) > maxWidth) end--;
		return s.substring(0, end) + dots;
	}

	/** Self-test: picks an item and ticks enchantments by id path, e.g. "sharpness". */
	public void preset(ItemStack stack, String... ids) {
		showAll = true;
		select(stack);
		for (String id : ids) {
			fitting().stream().filter(e -> e.unwrapKey().map(k -> k.identifier().getPath().equals(id)).orElse(false))
				.findFirst().ifPresent(e -> clickEnchantment(e, 0));
		}
	}
}
