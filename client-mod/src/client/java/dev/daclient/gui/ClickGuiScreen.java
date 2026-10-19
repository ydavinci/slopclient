package dev.daclient.gui;

import dev.daclient.DaClient;
import dev.daclient.module.Category;
import dev.daclient.module.Module;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import dev.daclient.setting.BoolSetting;
import dev.daclient.setting.ModeSetting;
import dev.daclient.setting.NumberSetting;
import dev.daclient.setting.Setting;
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
		super(Component.literal("DaClient"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Category[] cats = Category.values();
		int total = cats.length * PANEL_W + (cats.length - 1) * GAP;
		int x0 = Math.max(8, (width - total) / 2);
		for (int i = 0; i < cats.length; i++) {
			int[] saved = DaClient.config().panels.get(cats[i].name());
			int[] pos = saved != null ? saved.clone() : new int[] {x0 + i * (PANEL_W + GAP), 44};
			pos[0] = Math.max(0, Math.min(width - PANEL_W, pos[0]));
			pos[1] = Math.max(0, Math.min(height - HEADER, pos[1]));
			panels.put(cats[i], pos);
		}
	}

	/** Opens the settings of a module (used by the self-test). */
	public void expand(String module) {
		expanded.add(module);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		panels.forEach((c, pos) -> DaClient.config().panels.put(c.name(), pos.clone()));
		DaClient.save();
		minecraft.gui.setScreen(parent);
	}

	private List<Module> visible(Category c) {
		String q = query.toLowerCase();
		return DaClient.modules().in(c).stream().filter(m -> q.isEmpty() || m.name.toLowerCase().contains(q)).toList();
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
		if (parent == null) g.fill(0, 0, width, height, 0x66000000);
		else g.fillGradient(0, 0, width, height, 0xF0130F1E, 0xF009080E);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		// Footer first, so panels dragged over it stay on top.
		drawFooter(g, mouseX, mouseY);
		hint = "";
		double t = (System.currentTimeMillis() - openedAt) / 1000.0;
		int i = 0;
		for (Map.Entry<Category, int[]> e : panels.entrySet()) {
			// Panels drop in one after another.
			double k = Theme.ease((t - i * 0.05) / 0.4);
			int offset = (int) Math.round((1 - k) * -14);
			if (k > 0) drawPanel(g, e.getKey(), e.getValue()[0], e.getValue()[1] + offset, mouseX, mouseY, i);
			i++;
		}
	}

	private void drawPanel(GuiGraphicsExtractor g, Category c, int x, int y, int mx, int my, int index) {
		int h = panelHeight(c);
		Draw.round(g, x, y, PANEL_W, h, 4, Theme.alpha(Theme.PANEL_SOLID, 0.92));
		Draw.roundOutline(g, x, y, PANEL_W, h, 4, Theme.LINE_2);
		// Header with a chroma bar, like the launcher's panels.
		g.fill(x + 4, y + 1, x + PANEL_W - 4, y + 3, Theme.chroma(-index * 0.15));
		List<Module> mods = DaClient.modules().in(c);
		long on = mods.stream().filter(Module::enabled).count();
		Draw.shadowText(g, c.label, x + 7, y + 8, Theme.TEXT);
		String count = on + "/" + mods.size();
		Draw.text(g, count, x + PANEL_W - 7 - Draw.width(count), y + 8, Theme.DIM);

		int ry = y + HEADER;
		int row = 0;
		for (Module m : visible(c)) {
			boolean hover = Draw.inside(mx, my, x, ry, PANEL_W, ROW);
			if (m.enabled()) g.fill(x + 2, ry, x + PANEL_W - 2, ry + ROW, Theme.alpha(Theme.ACCENT, hover ? 0.28 : 0.18));
			else if (hover) g.fill(x + 2, ry, x + PANEL_W - 2, ry + ROW, 0x0AFFFFFF);
			if (m.enabled()) Draw.chromaText(g, m.name, x + 8, ry + 4, -index * 0.15 - row * 0.05, false);
			else Draw.text(g, m.name, x + 8, ry + 4, hover ? Theme.TEXT : Theme.MUTED);
			if (!m.settings().isEmpty()) {
				String marker = expanded.contains(m.name) ? "-" : "+";
				Draw.text(g, marker, x + PANEL_W - 12, ry + 4, Theme.DIM);
			}
			if (hover) hint = m.description;
			ry += ROW;
			if (expanded.contains(m.name)) {
				g.fill(x + 2, ry, x + PANEL_W - 2, ry + m.settings().size() * SETTING + 3, 0x33000000);
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
			case BoolSetting b -> {
				int sx = x + w - 16, sy = y + 3;
				Draw.round(g, sx, sy, 16, 8, 4, b.on() ? Theme.chroma(0) : 0x33FFFFFF);
				Draw.round(g, b.on() ? sx + 9 : sx + 1, sy + 1, 6, 6, 3, 0xFFFFFFFF);
			}
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

	private int[] hudButton() {
		int w = 92, h = 18;
		return new int[] {width - w - 10, height - h - 8, w, h};
	}

	private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
		int[] b = hudButton();
		boolean hover = Draw.inside(mx, my, b[0], b[1], b[2], b[3]);
		Draw.round(g, b[0], b[1], b[2], b[3], 4, hover ? Theme.chroma(0) : Theme.alpha(Theme.PANEL_SOLID, 0.92));
		if (!hover) Draw.roundOutline(g, b[0], b[1], b[2], b[3], 4, Theme.LINE_2);
		String label = "HUD bearbeiten";
		Draw.text(g, label, b[0] + (b[2] - Draw.width(label)) / 2, b[1] + 5, hover ? 0xFFFFFFFF : Theme.TEXT);

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
		int button = event.button();
		int[] b = hudButton();
		if (button == 0 && Draw.inside(mx, my, b[0], b[1], b[2], b[3])) {
			minecraft.gui.setScreen(new HudEditorScreen(this));
			return true;
		}
		// Topmost panel first: the last one drawn wins.
		List<Map.Entry<Category, int[]>> order = List.copyOf(panels.entrySet()).reversed();
		for (Map.Entry<Category, int[]> e : order) {
			Category c = e.getKey();
			int x = e.getValue()[0], y = e.getValue()[1];
			if (!Draw.inside(mx, my, x, y, PANEL_W, panelHeight(c))) continue;
			bringToFront(c);
			if (my < y + HEADER) {
				dragging = c;
				dragDx = (int) mx - x;
				dragDy = (int) my - y;
				return true;
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
		if (DaClient.clickGuiKey().matches(event)) {
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
