package dev.slopclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.slopclient.Config;
import dev.slopclient.SlopClient;
import dev.slopclient.module.Category;
import dev.slopclient.module.Module;
import dev.slopclient.render.Draw;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import dev.slopclient.setting.NumberSetting;
import dev.slopclient.setting.Setting;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Cheat-client style module menu: one draggable panel per category, rows toggle on
 * left click and expand their settings on right click. Typing goes into the search
 * field at the top, which filters the panels to matching modules (by name,
 * description, category or setting) and highlights the hits.
 *
 * <p>Panels and footer are drawn in a scaled space (the "Größe" button, saved as
 * {@link Config#guiScale}); the title bar and the search field stay at Minecraft's
 * GUI scale. Module and setting names too long for their row are shrunk to fit.
 */
public final class ClickGuiScreen extends Screen {
	private static final int PANEL_W = 124, HEADER = 20, ROW = 15, SETTING = 14, GAP = 10;
	/** Smallest share of its normal size a long name is shrunk to before it gets an ellipsis. */
	private static final float MIN_TEXT_SCALE = 0.6f;
	private static final int SEARCH_H = 20, QUERY_MAX = 32;

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private final Map<Category, int[]> panels = new LinkedHashMap<>();
	private final Set<String> expanded = new HashSet<>();
	/** Modules the search opened and the user closed again; forgotten when the query changes. */
	private final Set<String> collapsed = new HashSet<>();
	private String query = "";
	private EditBox search;
	/** Folded words of the query; every one must match somewhere in a module. */
	private List<String> words = List.of();

	private Category dragging;
	private int dragDx, dragDy;
	private NumberSetting slider;
	private int sliderX, sliderW;
	private String hint = "";
	private String buttonHint = "";
	/** Right edge of the title bar, so the search field moves below it when the window is narrow. */
	private int titleRight = 300;

	/** `parent` is shown again on close (the title screen), or null in game. */
	public ClickGuiScreen(Screen parent) {
		super(Component.literal("SlopClient"));
		this.parent = parent;
	}

	// ---------- scale and layout ----------

	private static float scale() {
		return SlopClient.config().guiScale;
	}

	/** Width and height of the scaled space the panels and the footer live in. */
	private int vw() {
		return (int) (width / scale());
	}

	private int vh() {
		return (int) (height / scale());
	}

	/** Search field in screen coordinates: [x, y, w, h]. */
	private int[] searchBox() {
		int w = Math.min(190, width / 3);
		int x = width - 10 - w;
		// Under the title bar when both don't fit in one row.
		int y = x < titleRight + 8 ? 34 : 10;
		return new int[] {x, y, w, SEARCH_H};
	}

	/** Top of the first panel row (scaled space), below the title bar and the search field. */
	private int panelTop() {
		int[] s = searchBox();
		return (int) Math.ceil((s[1] + s[3] + 14) / scale());
	}

	@Override
	protected void init() {
		search = new EditBox(font, 0, 0, 100, 10, Component.literal("Module suchen"));
		search.setBordered(false);
		search.setMaxLength(QUERY_MAX);
		search.setTextColor(Theme.TEXT);
		search.setHint(Component.literal("Module suchen …"));
		// Always focused: typing anywhere goes into the search.
		search.setCanLoseFocus(false);
		search.setValue(query);
		search.setResponder(this::setQuery);
		addWidget(search);
		setInitialFocus(search);
		Category[] cats = Category.values();
		int vw = vw();
		int cols = Math.max(1, Math.min(cats.length, (vw - 16 + GAP) / (PANEL_W + GAP)));
		int total = cols * PANEL_W + (cols - 1) * GAP;
		int x0 = Math.max(8, (vw - total) / 2);
		// Panels that don't fit in one row go under the currently shortest column.
		int[] bottoms = new int[cols];
		java.util.Arrays.fill(bottoms, panelTop());
		for (int i = 0; i < cats.length; i++) {
			int[] saved = SlopClient.config().panels.get(cats[i].name());
			int col = i < cols ? i : shortest(bottoms);
			int[] pos = saved != null ? saved.clone() : new int[] {x0 + col * (PANEL_W + GAP), bottoms[col]};
			bottoms[col] += panelHeight(cats[i]) + GAP;
			panels.put(cats[i], pos);
		}
		clampPanels();
	}

	/** Keeps every panel header on screen (after a resize or a size change). */
	private void clampPanels() {
		for (int[] pos : panels.values()) {
			pos[0] = Math.max(0, Math.min(vw() - PANEL_W, pos[0]));
			pos[1] = Math.max(0, Math.min(vh() - HEADER, pos[1]));
		}
	}

	private void changeScale(int steps) {
		var config = SlopClient.config();
		float before = config.guiScale;
		config.guiScale = Config.clampGuiScale(before + steps * 0.1f);
		if (config.guiScale == before) return;
		// Panels stay where they were on screen.
		float k = before / config.guiScale;
		for (int[] pos : panels.values()) {
			pos[0] = Math.round(pos[0] * k);
			pos[1] = Math.round(pos[1] * k);
		}
		clampPanels();
		SlopClient.save();
	}

	/** Opens the settings of a module (used by the self-test). */
	public void expand(String module) {
		expanded.add(module);
	}

	private static int shortest(int[] bottoms) {
		int best = 0;
		for (int i = 1; i < bottoms.length; i++) if (bottoms[i] < bottoms[best]) best = i;
		return best;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		panels.forEach((c, pos) -> SlopClient.config().panels.put(c.name(), pos.clone()));
		SlopClient.save();
		minecraft.gui.setScreen(parent);
	}

	// ---------- search ----------

	/** Lower case with umlauts spelled out, so "groesse" finds "Größe". */
	private static String fold(String s) {
		return s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
	}

	/** `word` in `folded`, also when typed without the umlaut's e ("hute" finds "Hüte"). */
	private static boolean loose(String folded, String word) {
		return folded.contains(word) || folded.replace("ae", "a").replace("oe", "o").replace("ue", "u").contains(word);
	}

	/** Empties the search; the edit box's responder resets the filter. */
	private void clearSearch() {
		search.setValue("");
	}

	private void setQuery(String q) {
		query = q;
		collapsed.clear();
		words = java.util.Arrays.stream(fold(q).trim().split("\\s+")).filter(w -> !w.isEmpty()).toList();
	}

	private boolean searching() {
		return !words.isEmpty();
	}

	private boolean settingHit(Setting<?> s) {
		if (!searching()) return false;
		String n = fold(s.name());
		return words.stream().anyMatch(w -> loose(n, w));
	}

	private boolean matches(Module m) {
		if (!searching()) return true;
		String all = fold(m.name + " " + m.description + " " + m.category.label + " "
			+ String.join(" ", m.settings().stream().map(Setting::name).toList()));
		return words.stream().allMatch(w -> loose(all, w));
	}

	private List<Module> visible(Category c) {
		return SlopClient.modules().in(c).stream().filter(this::matches).toList();
	}

	/** While searching, panels without a hit are hidden. */
	private boolean shown(Category c) {
		return !searching() || !visible(c).isEmpty();
	}

	private List<Module> hits() {
		return SlopClient.modules().all().stream().filter(this::matches).toList();
	}

	/** Settings are open when expanded by hand, or while the search hits one of them. */
	private boolean open(Module m) {
		if (m.settings().isEmpty()) return false;
		return expanded.contains(m.name) || !collapsed.contains(m.name) && m.settings().stream().anyMatch(this::settingHit);
	}

	/** [start, end) of the first query word found in `name`, or null. */
	private int[] highlight(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (String w : words) {
			int i = lower.indexOf(w);
			if (i >= 0) return new int[] {i, i + w.length()};
		}
		return null;
	}

	private int panelHeight(Category c) {
		int h = HEADER;
		for (Module m : visible(c)) {
			h += ROW;
			if (open(m)) h += m.settings().size() * SETTING + 3;
		}
		return h + 3;
	}

	// ---------- drawing ----------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level == null) {
			// From the title screen: the launcher's shader backdrop.
			MenuShader.draw(g, 1);
		} else {
			// In game: the world stays visible, blurred and dimmed like the launcher's glass.
			extractBlurredBackground(g);
			g.fillGradient(0, 0, width, height, 0x7009080E, 0xA009080E);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		double t = (System.currentTimeMillis() - openedAt) / 1000.0;
		long active = SlopClient.modules().all().stream().filter(Module::active).count();
		titleRight = Chrome.titleBar(g, minecraft, Theme.ease(t / 0.5), active + " Module aktiv");
		drawSearch(g, mouseX, mouseY, Theme.ease(t / 0.5));

		float s = scale();
		int mx = (int) (mouseX / s), my = (int) (mouseY / s);
		g.pose().pushMatrix();
		g.pose().scale(s, s);
		// Footer first, so panels dragged over it stay on top.
		drawFooter(g, mx, my);
		hint = "";
		int i = 0;
		for (Map.Entry<Category, int[]> e : panels.entrySet()) {
			double k = Theme.ease((t - i * 0.05) / 0.4);
			if (k > 0 && shown(e.getKey())) drawPanel(g, e.getKey(), e.getValue()[0], e.getValue()[1], mx, my, i);
			i++;
		}
		drawHintLine(g);
		g.pose().popMatrix();
	}

	private void drawSearch(GuiGraphicsExtractor g, int mx, int my, double k) {
		int[] b = searchBox();
		int x = b[0], y = b[1], w = b[2], h = b[3];
		boolean active = !query.isEmpty();
		Draw.round(g, x, y, w, h, 6, Theme.alpha(Theme.PANEL_SOLID, 0.78 * k));
		if (active) Draw.chromaRing(g, x, y, w, h, 1);
		else Draw.roundOutline(g, x, y, w, h, 6, Theme.alpha(0xFFFFFF, 0.1 * k));
		// Magnifier: ring plus handle.
		int ic = active ? Theme.ACCENT_2 : Theme.DIM;
		Draw.roundOutline(g, x + 7, y + 5, 8, 8, 4, ic);
		g.fill(x + 14, y + 12, x + 16, y + 14, ic);
		g.fill(x + 15, y + 13, x + 17, y + 15, ic);

		int tx = x + 21;
		int clearX = x + w - 13;
		int textEnd = x + w - 6;
		if (active) {
			int n = hits().size();
			String count = n == 0 ? "keine Treffer" : n == 1 ? "1 Treffer" : n + " Treffer";
			// Clear button at the far right, the hit count before it.
			boolean overClear = Draw.inside(mx, my, clearX - 4, y + 2, 14, 16);
			Draw.text(g, "x", clearX, y + 6, overClear ? Theme.TEXT : Theme.MUTED);
			int cw = Math.round(Draw.width(count) * 0.75f);
			Draw.scaledText(g, count, clearX - 6 - cw, y + 7.5f, 0.75f, n == 0 ? Theme.ERR : Theme.DIM, false);
			textEnd = clearX - 12 - cw;
		}
		// The vanilla edit box does the typing (SDL3 only sends text to a focused text field), we draw around it.
		search.setRectangle(Math.max(20, textEnd - tx), 10, tx, y + 6);
		search.extractRenderState(g, mx, my, 0);
	}

	private void drawPanel(GuiGraphicsExtractor g, Category c, int x, int y, int mx, int my, int index) {
		int h = panelHeight(c);
		// Glass panel like the launcher's: translucent, hairline border, chroma bar on top.
		Draw.glow(g, x, y, PANEL_W, h, 5, 0xFF000000, 0.35);
		Draw.round(g, x, y, PANEL_W, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.86));
		Draw.roundOutline(g, x, y, PANEL_W, h, 7, Theme.LINE_2);
		g.fill(x + 7, y, x + PANEL_W - 7, y + 2, Theme.chroma(-index * 0.15));
		List<Module> mods = SlopClient.modules().in(c);
		String count = searching()
			? visible(c).size() + " von " + mods.size()
			: mods.stream().filter(Module::enabled).count() + "/" + mods.size();
		int countW = Draw.width(count);
		Draw.text(g, count, x + PANEL_W - 7 - countW, y + 8, Theme.DIM);
		fitText(g, c.label, x + 7, y + 8, PANEL_W - 14 - countW - 4, 1f, Theme.TEXT, true);

		int ry = y + HEADER;
		int row = 0;
		for (Module m : visible(c)) {
			boolean hover = Draw.inside(mx, my, x, ry, PANEL_W, ROW);
			// Rows like the launcher's module list: enabled ones tinted with a chroma name.
			if (m.enabled()) Draw.round(g, x + 3, ry, PANEL_W - 6, ROW, 4, Theme.alpha(Theme.ACCENT, hover ? 0.26 : 0.16));
			else if (hover) Draw.round(g, x + 3, ry, PANEL_W - 6, ROW, 4, 0x0CFFFFFF);

			// The name gets the room up to the "+" marker and the toggle (or the "Server" tag).
			int nameEnd;
			if (m.serverLocked()) {
				// Switched off by the server for this session; the choice is kept.
				String tag = "Server";
				int tagX = x + PANEL_W - 9 - Math.round(Draw.width(tag) * 0.75f);
				Draw.scaledText(g, tag, tagX, ry + 5, 0.75f, Theme.ERR, false);
				nameEnd = tagX - 3;
			} else {
				Chrome.toggle(g, x + PANEL_W - 25, ry + 3, m.enabled(), 1);
				nameEnd = x + PANEL_W - 28;
				if (!m.settings().isEmpty()) {
					String marker = open(m) ? "-" : "+";
					Draw.text(g, marker, x + PANEL_W - 35, ry + 4, hover ? Theme.MUTED : Theme.DIM);
					nameEnd = x + PANEL_W - 38;
				}
			}
			drawName(g, m.name, x + 9, ry, nameEnd - (x + 9), m.enabled(), hover, -index * 0.15 - row * 0.05);

			if (hover) hint = m.serverLocked() ? m.name + " ist auf diesem Server deaktiviert." : m.description;
			ry += ROW;
			if (open(m)) {
				Draw.round(g, x + 3, ry, PANEL_W - 6, m.settings().size() * SETTING + 3, 4, 0x2E000000);
				for (Setting<?> s : m.settings()) {
					if (settingHit(s)) Draw.round(g, x + 5, ry, PANEL_W - 10, SETTING, 3, Theme.alpha(Theme.ACCENT_2, 0.14));
					drawSetting(g, s, x + 8, ry + 1, PANEL_W - 16, mx, my);
					ry += SETTING;
				}
				ry += 3;
			}
			row++;
		}
	}

	/** A module name shrunk to fit `room`, with the search hit underlaid. */
	private void drawName(GuiGraphicsExtractor g, String name, int x, int rowY, int room, boolean on, boolean hover, double phase) {
		float k = fit(name, room, 1f);
		if (k < MIN_TEXT_SCALE) {
			k = MIN_TEXT_SCALE;
			name = ellipsize(name, (int) (room / k));
		}
		// Smaller text stays vertically centred in the row.
		float y = rowY + 4 + (1 - k) * 4.5f;
		int[] hit = searching() ? highlight(name) : null;
		if (hit != null) {
			float hx = x + Draw.width(name.substring(0, hit[0])) * k;
			float hw = Draw.width(name.substring(hit[0], hit[1])) * k;
			Draw.round(g, Math.round(hx) - 1, rowY + 2, Math.round(hw) + 2, ROW - 4, 2, Theme.alpha(Theme.ACCENT_2, 0.32));
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(k, k);
		if (on) Draw.chromaText(g, name, 0, 0, phase, false);
		else Draw.text(g, name, 0, 0, hover ? Theme.TEXT : Theme.alpha(Theme.MUTED, 0.85));
		g.pose().popMatrix();
	}

	/** Scale at which `s` fits `room`, at most `base`. */
	private static float fit(String s, int room, float base) {
		return Math.min(base, room / (float) Math.max(1, Draw.width(s)));
	}

	/** Text at `base` scale, shrunk to at most 60 % of it if it doesn't fit `room`, then cut with "…". */
	private static void fitText(GuiGraphicsExtractor g, String s, int x, float y, int room, float base, int color, boolean shadow) {
		float k = fit(s, room, base);
		if (k < base * MIN_TEXT_SCALE) {
			k = base * MIN_TEXT_SCALE;
			s = ellipsize(s, (int) (room / k));
		}
		Draw.scaledText(g, s, x, y + (base - k) * 4.5f, k, color, shadow);
	}

	private void drawSetting(GuiGraphicsExtractor g, Setting<?> s, int x, int y, int w, int mx, int my) {
		boolean hover = Draw.inside(mx, my, x, y, w, SETTING);
		int nameColor = settingHit(s) ? Theme.ACCENT_2 : hover ? Theme.TEXT : Theme.MUTED;
		switch (s) {
			case BoolSetting b -> {
				fitText(g, s.name(), x, y + 3, w - 20, 0.85f, nameColor, false);
				Chrome.toggle(g, x + w - 16, y + 2, b.on(), 1);
			}
			case NumberSetting n -> {
				String v = n.display();
				int vw = Math.round(Draw.width(v) * 0.85f);
				Draw.scaledText(g, v, x + w - vw, y + 3, 0.85f, Theme.TEXT, false);
				int[] bar = sliderBar(n, x, w);
				fitText(g, s.name(), x, y + 3, bar[0] - x - 6, 0.85f, nameColor, false);
				g.fill(bar[0], y + 6, bar[0] + bar[1], y + 8, 0x33FFFFFF);
				g.fill(bar[0], y + 6, bar[0] + (int) Math.round(bar[1] * n.fraction()), y + 8, Theme.chroma(0));
			}
			case ModeSetting m -> {
				String v = "< " + m.display() + " >";
				int vw = Math.round(Draw.width(v) * 0.85f);
				Draw.scaledText(g, v, x + w - vw, y + 3, 0.85f, Theme.ACCENT_2, false);
				fitText(g, s.name(), x, y + 3, w - vw - 4, 0.85f, nameColor, false);
			}
		}
	}

	/**
	 * Slider track of a number setting: [x, width]. The name takes at most half of the
	 * row next to the value, so a long name no longer squeezes the track away.
	 */
	private static int[] sliderBar(NumberSetting n, int x, int w) {
		int vw = Math.round(Draw.width(n.display()) * 0.85f);
		int free = w - vw - 6;
		int nameW = Math.min(Math.round(Draw.width(n.name()) * 0.85f), free / 2);
		int bx = x + nameW + 6;
		return new int[] {bx, Math.max(10, x + free - bx)};
	}

	/** A footer button; `action` gets the mouse button. */
	private record FooterButton(String label, String hint, int x, int y, int w, int h, java.util.function.IntConsumer action) {
		boolean hit(double mx, double my) {
			return Draw.inside(mx, my, x, y, w, h);
		}
	}

	/** Right-aligned row: profile switcher, new, delete, size, HUD editor (scaled space). */
	private List<FooterButton> footerButtons() {
		var config = SlopClient.config();
		var modules = SlopClient.modules();
		List<String> profiles = config.profiles();
		int h = 18, y = vh() - h - 8, x = vw() - 10;
		List<FooterButton> buttons = new java.util.ArrayList<>();
		String hud = "HUD bearbeiten";
		int w = Draw.width(hud) + 16;
		x -= w;
		buttons.add(new FooterButton(hud, "HUD-Elemente verschieben und skalieren", x, y, w, h,
			b -> minecraft.gui.setScreen(new HudEditorScreen(this))));
		String shots = "Screenshots";
		w = Draw.width(shots) + 16;
		x -= w + 4;
		buttons.add(new FooterButton(shots, "Deine Screenshots dieser Instanz ansehen", x, y, w, h,
			b -> minecraft.gui.setScreen(new ScreenshotGalleryScreen(this))));
		String size = "Größe " + Math.round(config.guiScale * 100) + " %";
		w = Draw.width(size) + 16;
		x -= w + 4;
		buttons.add(new FooterButton(size, "Größe des Menüs · Klick: größer · Rechtsklick: kleiner", x, y, w, h,
			b -> changeScale(b == 1 ? -1 : 1)));
		if (profiles.size() > 1) {
			x -= h + 4;
			buttons.add(new FooterButton("x", "Profil „" + config.profile() + "“ löschen", x, y, h, h, b -> config.deleteCurrent(modules)));
		}
		x -= h + 4;
		buttons.add(new FooterButton("+", "Neues Profil mit den aktuellen Einstellungen", x, y, h, h, b -> config.create(modules)));
		String label = "Profil: " + config.profile();
		w = Draw.width(label) + 16;
		x -= w + 4;
		buttons.add(new FooterButton(label, profiles.size() > 1 ? "Klick: nächstes Profil · Rechtsklick: vorheriges" : "Nur ein Profil. Mit + legst du weitere an.", x, y, w, h, b -> {
			if (profiles.size() < 2) return;
			int i = profiles.indexOf(config.profile());
			config.switchTo(modules, profiles.get(Math.floorMod(i + (b == 1 ? -1 : 1), profiles.size())));
		}));
		return buttons;
	}

	private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
		buttonHint = "";
		for (FooterButton b : footerButtons()) {
			boolean hover = b.hit(mx, my);
			if (hover) buttonHint = b.hint();
			Draw.glassButton(g, b.x(), b.y(), b.w(), b.h(), b.label(), hover, 1);
		}
	}

	/** Bottom-left line: what the hovered thing does, or how to use the menu. Drawn after the panels set `hint`. */
	private void drawHintLine(GuiGraphicsExtractor g) {
		String line = !hint.isEmpty() ? hint : !buttonHint.isEmpty() ? buttonHint
			: searching() ? (hits().size() == 1 ? "Enter: Treffer an/aus · Esc: Suche leeren" : "Esc: Suche leeren")
			: "Linksklick: an/aus · Rechtsklick: Einstellungen · Tippen: suchen";
		// The line hides when a panel covers it.
		int lineW = Draw.width(line), lineY = vh() - 20;
		boolean covered = panels.entrySet().stream().anyMatch(e -> {
			int[] p = e.getValue();
			return shown(e.getKey()) && p[0] < 10 + lineW && p[0] + PANEL_W > 10 && p[1] < lineY + 9 && p[1] + panelHeight(e.getKey()) > lineY;
		});
		if (!covered) Draw.text(g, line, 10, lineY, Theme.DIM);
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int rawButton = event.button();
		// In Minecraft 26.3 (SDL3 backend): 1 = left, 2 = middle, 3 = right.
		// Normalize: 0 = left click, 1 = right click.
		int button = (rawButton == 3) ? 1 : (rawButton == 0 || rawButton == 1 ? 0 : -1);
		if (button == -1) return super.mouseClicked(event, doubleClick);

		// Search field (screen coordinates): the "x" clears it, the rest just takes the click.
		int[] sb = searchBox();
		if (Draw.inside(event.x(), event.y(), sb[0], sb[1], sb[2], sb[3])) {
			if (!query.isEmpty() && event.x() >= sb[0] + sb[2] - 18) clearSearch();
			else super.mouseClicked(event, doubleClick);
			setFocused(search);
			return true;
		}

		double mx = event.x() / scale(), my = event.y() / scale();
		for (FooterButton b : footerButtons()) {
			if (b.hit(mx, my)) {
				b.action().accept(button);
				return true;
			}
		}
		// Topmost panel first: the last one drawn wins.
		List<Map.Entry<Category, int[]>> order = List.copyOf(panels.entrySet()).reversed();
		for (Map.Entry<Category, int[]> e : order) {
			Category c = e.getKey();
			if (!shown(c)) continue;
			int x = e.getValue()[0], y = e.getValue()[1];
			if (!Draw.inside(mx, my, x, y, PANEL_W, panelHeight(c))) continue;
			bringToFront(c);
			if (my < y + HEADER) {
				if (button == 0) {
					dragging = c;
					dragDx = (int) mx - x;
					dragDy = (int) my - y;
					return true;
				}
			}
			int ry = y + HEADER;
			for (Module m : visible(c)) {
				if (my >= ry && my < ry + ROW) {
					if (button == 0) m.toggle();
					else if (button == 1 && !m.settings().isEmpty()) {
						if (open(m)) {
							expanded.remove(m.name);
							collapsed.add(m.name);
						} else {
							expanded.add(m.name);
							collapsed.remove(m.name);
						}
					}
					return true;
				}
				ry += ROW;
				if (open(m)) {
					for (Setting<?> s : m.settings()) {
						if (my >= ry && my < ry + SETTING) {
							clickSetting(s, x + 8, PANEL_W - 16, mx, button);
							return true;
						}
						ry += SETTING;
					}
					ry += 3;
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void clickSetting(Setting<?> s, int x, int w, double mx, int button) {
		switch (s) {
			case BoolSetting b -> b.toggle();
			case ModeSetting m -> m.cycle(button == 1 ? -1 : 1);
			case NumberSetting n -> {
				int[] bar = sliderBar(n, x, w);
				sliderX = bar[0];
				sliderW = bar[1];
				slider = n;
				n.setFraction((mx - sliderX) / sliderW);
			}
		}
	}

	private void bringToFront(Category c) {
		int[] pos = panels.remove(c);
		panels.put(c, pos);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		double mx = event.x() / scale(), my = event.y() / scale();
		if (dragging != null) {
			int[] pos = panels.get(dragging);
			pos[0] = Math.max(0, Math.min(vw() - PANEL_W, (int) mx - dragDx));
			pos[1] = Math.max(0, Math.min(vh() - HEADER, (int) my - dragDy));
			return true;
		}
		if (slider != null) {
			slider.setFraction((mx - sliderX) / sliderW);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		slider = null;
		return super.mouseReleased(event);
	}

	/** The wheel scrolls all panels, so long ones fit on small windows. */
	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int dy = (int) Math.signum(scrollY) * 16;
		for (int[] pos : panels.values()) pos[1] += dy;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (!query.isEmpty()) {
			if (key == InputConstants.KEY_ESCAPE) {
				clearSearch();
				return true;
			}
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				List<Module> found = hits();
				if (found.size() == 1 && !found.get(0).serverLocked()) found.get(0).toggle();
				return true;
			}
		}
		// The menu key closes, but not while a query is typed (it may be a letter key).
		if (SlopClient.clickGuiKey().matches(event) && query.isEmpty()) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	// ---------- text ----------

	private static String ellipsize(String s, int maxWidth) {
		if (Draw.width(s) <= maxWidth) return s;
		int end = s.length();
		while (end > 0 && Draw.width(s.substring(0, end) + "…") > maxWidth) end--;
		return s.substring(0, end) + "…";
	}

}
