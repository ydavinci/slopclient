package dev.slopclient.gui;

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
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Cheat-client style module menu: one draggable panel per category, rows toggle on
 * left click and expand their settings on right click. Typing filters all panels.
 */
public final class ClickGuiScreen extends Screen {
	private static final int PANEL_W = 124, HEADER = 20, ROW = 15, SETTING = 14, GAP = 10;

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private final Map<Category, int[]> panels = new LinkedHashMap<>();
	private final Set<String> expanded = new HashSet<>();
	private String query = "";

	private Category dragging;
	private int dragDx, dragDy;
	private NumberSetting slider;
	private int sliderX, sliderW;
	private String hint = "";

	/** `parent` is shown again on close (the title screen), or null in game. */
	public ClickGuiScreen(Screen parent) {
		super(Component.literal("SlopClient"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Category[] cats = Category.values();
		int cols = Math.max(1, Math.min(cats.length, (width - 16 + GAP) / (PANEL_W + GAP)));
		int total = cols * PANEL_W + (cols - 1) * GAP;
		int x0 = Math.max(8, (width - total) / 2);
		// Panels that don't fit in one row go under the currently shortest column.
		int[] bottoms = new int[cols];
		java.util.Arrays.fill(bottoms, 44);
		for (int i = 0; i < cats.length; i++) {
			int[] saved = SlopClient.config().panels.get(cats[i].name());
			int col = i < cols ? i : shortest(bottoms);
			int[] pos = saved != null ? saved.clone() : new int[] {x0 + col * (PANEL_W + GAP), bottoms[col]};
			bottoms[col] += panelHeight(cats[i]) + GAP;
			pos[0] = Math.max(0, Math.min(width - PANEL_W, pos[0]));
			pos[1] = Math.max(0, Math.min(height - HEADER, pos[1]));
			panels.put(cats[i], pos);
		}
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

	private List<Module> visible(Category c) {
		String q = query.toLowerCase();
		return SlopClient.modules().in(c).stream().filter(m -> q.isEmpty() || m.name.toLowerCase().contains(q)).toList();
	}

	private int panelHeight(Category c) {
		int h = HEADER;
		for (Module m : visible(c)) {
			h += ROW;
			if (expanded.contains(m.name)) h += m.settings().size() * SETTING + 3;
		}
		return h + 3;
	}

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
		Chrome.titleBar(g, minecraft, Theme.ease(t / 0.5), active + " Module aktiv");
		// Footer first, so panels dragged over it stay on top.
		drawFooter(g, mouseX, mouseY);
		hint = "";
		int i = 0;
		for (Map.Entry<Category, int[]> e : panels.entrySet()) {
			double k = Theme.ease((t - i * 0.05) / 0.4);
			if (k > 0) drawPanel(g, e.getKey(), e.getValue()[0], e.getValue()[1], mouseX, mouseY, i);
			i++;
		}
	}

	private void drawPanel(GuiGraphicsExtractor g, Category c, int x, int y, int mx, int my, int index) {
		int h = panelHeight(c);
		// Glass panel like the launcher's: translucent, hairline border, chroma bar on top.
		Draw.glow(g, x, y, PANEL_W, h, 5, 0xFF000000, 0.35);
		Draw.round(g, x, y, PANEL_W, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.86));
		Draw.roundOutline(g, x, y, PANEL_W, h, 7, Theme.LINE_2);
		g.fill(x + 7, y, x + PANEL_W - 7, y + 2, Theme.chroma(-index * 0.15));
		List<Module> mods = SlopClient.modules().in(c);
		long on = mods.stream().filter(Module::enabled).count();
		Draw.shadowText(g, c.label, x + 7, y + 8, Theme.TEXT);
		String count = on + "/" + mods.size();
		Draw.text(g, count, x + PANEL_W - 7 - Draw.width(count), y + 8, Theme.DIM);

		int ry = y + HEADER;
		int row = 0;
		for (Module m : visible(c)) {
			boolean hover = Draw.inside(mx, my, x, ry, PANEL_W, ROW);
			// Rows like the launcher's module list: enabled ones tinted with a chroma name.
			if (m.enabled()) Draw.round(g, x + 3, ry, PANEL_W - 6, ROW, 4, Theme.alpha(Theme.ACCENT, hover ? 0.26 : 0.16));
			else if (hover) Draw.round(g, x + 3, ry, PANEL_W - 6, ROW, 4, 0x0CFFFFFF);
			if (m.enabled()) Draw.chromaText(g, m.name, x + 9, ry + 4, -index * 0.15 - row * 0.05, false);
			else Draw.text(g, m.name, x + 9, ry + 4, hover ? Theme.TEXT : Theme.alpha(Theme.MUTED, 0.85));
			if (m.serverLocked()) {
				// Switched off by the server for this session; the choice is kept.
				String tag = "Server";
				Draw.scaledText(g, tag, x + PANEL_W - 9 - Draw.width(tag) * 0.75f, ry + 5, 0.75f, Theme.ERR, false);
			} else {
				Chrome.toggle(g, x + PANEL_W - 25, ry + 3, m.enabled(), 1);
				if (!m.settings().isEmpty()) {
					String marker = expanded.contains(m.name) ? "-" : "+";
					Draw.text(g, marker, x + PANEL_W - 35, ry + 4, hover ? Theme.MUTED : Theme.DIM);
				}
			}
			if (hover) hint = m.serverLocked() ? m.name + " ist auf diesem Server deaktiviert." : m.description;
			ry += ROW;
			if (expanded.contains(m.name)) {
				Draw.round(g, x + 3, ry, PANEL_W - 6, m.settings().size() * SETTING + 3, 4, 0x2E000000);
				for (Setting<?> s : m.settings()) {
					drawSetting(g, s, x + 8, ry + 1, PANEL_W - 16, mx, my);
					ry += SETTING;
				}
				ry += 3;
			}
			row++;
		}
	}

	private void drawSetting(GuiGraphicsExtractor g, Setting<?> s, int x, int y, int w, int mx, int my) {
		boolean hover = Draw.inside(mx, my, x, y, w, SETTING);
		Draw.scaledText(g, s.name(), x, y + 3, 0.85f, hover ? Theme.TEXT : Theme.MUTED, false);
		switch (s) {
			case BoolSetting b -> Chrome.toggle(g, x + w - 16, y + 2, b.on(), 1);
			case NumberSetting n -> {
				String v = n.display();
				int vw = Math.round(Draw.width(v) * 0.85f);
				Draw.scaledText(g, v, x + w - vw, y + 3, 0.85f, Theme.TEXT, false);
				int bx = x + Math.round(Draw.width(s.name()) * 0.85f) + 6, bw = w - (bx - x) - vw - 6;
				if (bw > 10) {
					g.fill(bx, y + 6, bx + bw, y + 8, 0x33FFFFFF);
					g.fill(bx, y + 6, bx + (int) Math.round(bw * n.fraction()), y + 8, Theme.chroma(0));
				}
			}
			case ModeSetting m -> {
				String v = "< " + m.display() + " >";
				int vw = Math.round(Draw.width(v) * 0.85f);
				Draw.scaledText(g, v, x + w - vw, y + 3, 0.85f, Theme.ACCENT_2, false);
			}
		}
	}

	/** A footer button; `action` gets the mouse button. */
	private record FooterButton(String label, String hint, int x, int y, int w, int h, java.util.function.IntConsumer action) {
		boolean hit(double mx, double my) {
			return Draw.inside(mx, my, x, y, w, h);
		}
	}

	/** Right-aligned row: profile switcher, new, delete, HUD editor. */
	private List<FooterButton> footerButtons() {
		var config = SlopClient.config();
		var modules = SlopClient.modules();
		List<String> profiles = config.profiles();
		int h = 18, y = height - h - 8, x = width - 10;
		List<FooterButton> buttons = new java.util.ArrayList<>();
		String hud = "HUD bearbeiten";
		int w = Draw.width(hud) + 16;
		x -= w;
		buttons.add(new FooterButton(hud, "HUD-Elemente verschieben und skalieren", x, y, w, h,
			b -> minecraft.gui.setScreen(new HudEditorScreen(this))));
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
		String buttonHint = "";
		for (FooterButton b : footerButtons()) {
			boolean hover = b.hit(mx, my);
			if (hover) buttonHint = b.hint();
			Draw.glassButton(g, b.x(), b.y(), b.w(), b.h(), b.label(), hover, 1);
		}
		if (hint.isEmpty()) hint = buttonHint;

		String line = !query.isEmpty() ? "Suche: " + query + "_" : !hint.isEmpty() ? hint
			: "Linksklick: an/aus · Rechtsklick: Einstellungen · Tippen: suchen";
		// The hint hides when a panel covers its line; the search query always shows.
		int lineW = Draw.width(line), lineY = height - 20;
		boolean covered = panels.entrySet().stream().anyMatch(e -> {
			int[] p = e.getValue();
			return p[0] < 10 + lineW && p[0] + PANEL_W > 10 && p[1] < lineY + 9 && p[1] + panelHeight(e.getKey()) > lineY;
		});
		if (!covered || !query.isEmpty()) Draw.text(g, line, 10, lineY, query.isEmpty() ? Theme.DIM : Theme.ACCENT_2);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int rawButton = event.button();
		// In Minecraft 26.3 (SDL3 backend): 1 = left, 2 = middle, 3 = right.
		// Normalize: 0 = left click, 1 = right click.
		int button = (rawButton == 3) ? 1 : (rawButton == 0 || rawButton == 1 ? 0 : -1);
		if (button == -1) return super.mouseClicked(event, doubleClick);

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
						if (!expanded.remove(m.name)) expanded.add(m.name);
					}
					return true;
				}
				ry += ROW;
				if (expanded.contains(m.name)) {
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
				String v = n.display();
				int vw = Math.round(Draw.width(v) * 0.85f);
				sliderX = x + Math.round(Draw.width(s.name()) * 0.85f) + 6;
				sliderW = Math.max(10, w - (sliderX - x) - vw - 6);
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
		if (dragging != null) {
			int[] pos = panels.get(dragging);
			pos[0] = Math.max(0, Math.min(width - PANEL_W, (int) event.x() - dragDx));
			pos[1] = Math.max(0, Math.min(height - HEADER, (int) event.y() - dragDy));
			return true;
		}
		if (slider != null) {
			slider.setFraction((event.x() - sliderX) / sliderW);
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
		if (SlopClient.clickGuiKey().matches(event)) {
			onClose();
			return true;
		}
		if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
			query = query.substring(0, query.length() - 1);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (event.isAllowedChatCharacter() && query.length() < 24) {
			query += event.codepointAsString();
			return true;
		}
		return false;
	}
}
