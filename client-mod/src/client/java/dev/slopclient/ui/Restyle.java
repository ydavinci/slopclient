package dev.slopclient.ui;

import dev.slopclient.SlopClient;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;

/**
 * Vanilla and mod screens in the launcher look ("Klassisch"): the layout stays theirs,
 * only the drawing changes. Buttons, sliders, checkboxes, text fields, lists,
 * scrollbars and backgrounds are swapped by mixins (dev.slopclient.mixin.restyle) that
 * call in here, and text switches to the menu font while such a screen is open.
 *
 * <p>Left alone: the video settings and what Sodium or Iris show there, SlopClient's
 * own screens (they draw themselves), and everything when the setting is off.
 */
public final class Restyle {
	private Restyle() {
	}

	/** Whether the screen open right now gets the launcher look. */
	public static boolean active() {
		if (!SlopClient.ready()) return false;
		var module = SlopClient.modules().titleScreen;
		if (!module.enabled() || !module.restyle.on()) return false;
		Screen screen = Minecraft.getInstance().gui.screen();
		return screen != null && !excluded(screen);
	}

	private static boolean excluded(Screen screen) {
		if (screen instanceof VideoSettingsScreen) return true;
		String name = screen.getClass().getName();
		return name.startsWith("dev.slopclient.") || name.startsWith("net.caffeinemc.") || name.startsWith("net.irisshaders.")
			|| name.startsWith("net.coderbot.iris.");
	}

	private static double hover(Object widget, boolean on) {
		return Ui.anim("restyle." + System.identityHashCode(widget), on);
	}

	private static float radius(int h) {
		return Math.min(8, h / 2.5f);
	}

	/** Background of a button: glass with a hairline, accent on hover, dim when inactive. */
	public static void button(GuiGraphicsExtractor g, AbstractWidget w) {
		double hv = hover(w, w.isActive() && w.isHoveredOrFocused());
		double a = w.isActive() ? 1 : 0.45;
		float x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight(), r = radius(w.getHeight());
		Ui.rect(g, x, y, bw, bh, r, Theme.alpha(Theme.RAISED, (0.72 + 0.23 * hv) * a));
		Ui.outline(g, x, y, bw, bh, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, hv), (0.09 + 0.3 * hv) * a));
	}

	/** Slider track with the filled part in chroma up to the handle at `value` (0..1). */
	public static void sliderTrack(GuiGraphicsExtractor g, AbstractWidget w, double value) {
		double hv = hover(w, w.isActive() && w.isHoveredOrFocused());
		double a = w.isActive() ? 1 : 0.45;
		float x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight(), r = radius(w.getHeight());
		Ui.rect(g, x, y, bw, bh, r, Theme.alpha(Theme.RAISED, (0.72 + 0.23 * hv) * a));
		float fill = (float) (4 + value * (bw - 8));
		Ui.chroma(g, x, y, Math.max(2 * r, fill), bh, r, (0.32 + 0.15 * hv) * a, 0);
		Ui.outline(g, x, y, bw, bh, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, hv), (0.09 + 0.3 * hv) * a));
	}

	/** Slider handle: a white pill inside the track. */
	public static void sliderHandle(GuiGraphicsExtractor g, AbstractWidget w, int x, int y, int hw, int hh) {
		double hv = hover(w, w.isActive() && w.isHoveredOrFocused());
		Ui.rect(g, x + 1, y + 3, hw - 2, hh - 6, (hw - 2) / 2f, Theme.alpha(0xFFFFFF, (0.75 + 0.25 * hv) * (w.isActive() ? 1 : 0.45)));
	}

	/** Check box: chroma with a check mark when selected, glass otherwise. */
	public static void checkbox(GuiGraphicsExtractor g, AbstractWidget w, boolean selected, int x, int y, int size) {
		double hv = hover(w, w.isActive() && w.isHoveredOrFocused());
		float r = Math.min(5, size / 3f);
		if (selected) {
			Ui.chroma(g, x, y, size, size, r, w.isActive() ? 1 : 0.45, 0.1 * hv);
			float s = size * 0.7f;
			Ui.icon(g, Icon.CHECK, x + (size - s) / 2, y + (size - s) / 2, s, 0xFFFFFFFF);
		} else {
			Ui.rect(g, x, y, size, size, r, Theme.alpha(Theme.RAISED, 0.72 + 0.23 * hv));
			Ui.outline(g, x, y, size, size, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, hv), 0.14 + 0.3 * hv));
		}
	}

	/** Text field frame: dark glass, accent outline while focused. */
	public static void textField(GuiGraphicsExtractor g, AbstractWidget w, int x, int y, int fw, int fh) {
		float r = radius(fh);
		Ui.rect(g, x, y, fw, fh, r, Theme.alpha(Theme.PANEL_SOLID, 0.85));
		Ui.outline(g, x, y, fw, fh, r, w.isFocused() ? Theme.alpha(Theme.ACCENT_2, 0.7) : Theme.alpha(0xFFFFFF, 0.12));
	}

	/** Selected list row: accent wash with a hairline, rounded. */
	public static void selection(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Ui.rect(g, x, y, w, h, 6, Theme.alpha(Theme.ACCENT, 0.2));
		Ui.outline(g, x, y, w, h, 6, Theme.alpha(Theme.ACCENT_2, 0.45));
	}

	/** List area: a faint dark band with hairlines instead of the dirt texture and separators. */
	public static void listBackground(GuiGraphicsExtractor g, AbstractWidget list) {
		g.fill(list.getX(), list.getY(), list.getRight(), list.getBottom(), 0x38000000);
		float hl = Ui.hairline(g);
		Ui.rect(g, list.getX(), list.getY(), list.getWidth(), hl, 0, 0x1FFFFFFF);
		Ui.rect(g, list.getX(), list.getBottom() - hl, list.getWidth(), hl, 0, 0x1FFFFFFF);
	}

	/** Scrollbar: a thin rounded thumb, brighter while hovered or dragged. */
	public static void scrollbar(GuiGraphicsExtractor g, Object area, int x, int y, int w, int h, boolean hot) {
		double hv = hover(area, hot);
		float tw = 3 + (float) hv;
		Ui.rect(g, x + (w - tw) / 2f, y + 1, tw, h - 2, tw / 2, Theme.alpha(0xFFFFFF, 0.28 + 0.3 * hv));
	}

	/** Header or footer separator: one hairline where the texture's light edge was. */
	public static void separator(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		float hl = Ui.hairline(g);
		Ui.rect(g, x, y + (h - hl) / 2f, w, hl, 0, 0x24FFFFFF);
	}

	/** Tab of a tab bar ("Welt erstellen"): a pill, accent wash and chroma underline when selected. */
	public static void tab(GuiGraphicsExtractor g, AbstractWidget w, boolean selected) {
		double hv = hover(w, w.isActive() && w.isHoveredOrFocused());
		double sel = Ui.anim("restyle.tab." + System.identityHashCode(w), selected);
		double a = w.isActive() ? 1 : 0.45;
		float x = w.getX() + 2, y = w.getY() + 3, tw = w.getWidth() - 4, th = w.getHeight() - 6, r = th / 2f;
		Ui.rect(g, x, y, tw, th, r, Theme.alpha(Theme.RAISED, (0.35 + 0.3 * hv + 0.3 * sel) * a));
		if (sel > 0.01) Ui.rect(g, x, y, tw, th, r, Theme.alpha(Theme.ACCENT, 0.18 * sel * a));
		Ui.outline(g, x, y, tw, th, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, Math.max(hv, sel)), (0.08 + 0.25 * hv + 0.3 * sel) * a));
		if (sel > 0.01) {
			float uw = (tw - 2 * r) * (float) sel;
			Ui.chroma(g, x + (tw - uw) / 2f, y + th - 2, uw, 1.5f, 0.75f, sel * a, 0);
		}
		int color = w.isActive() ? (selected ? 0xFFFFFFFF : 0xFFC9CBD6) : 0xFF8A8C96;
		String label = Ui.fit(w.getMessage().getString(), 8, tw - 8);
		Ui.text(g, label, x + (tw - Ui.width(label, 8)) / 2f, y + (th - 10) / 2f, 8, color);
	}

	/** Advancements: the glass panel behind the tree, drawn before the tree so it stays under it. */
	public static void advancementPanel(GuiGraphicsExtractor g, int x, int y) {
		Ui.glass(g, x, y, 252, 140, 10, 1);
		g.fill(x + 9, y + 18, x + 9 + 234, y + 18 + 113, 0xFF000000);
	}

	/** Advancements: hairline around the tree and the title, on top of the tree. */
	public static void advancementFrame(GuiGraphicsExtractor g, int x, int y, String title) {
		Ui.outline(g, x + 9, y + 18, 234, 113, 0, 0x29FFFFFF);
		Ui.text(g, Ui.fit(title, 8.5f, 234), x + 10, y + 5.5f, 8.5f, 0xFFFFFFFF);
	}

	/** Advancement tab: glass, the selected one brighter with an accent edge. */
	public static void advancementTab(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean selected) {
		float r = 7;
		Ui.rect(g, x + 1, y + 1, w - 2, h - 2, r, Theme.alpha(Theme.PANEL_SOLID, selected ? 0.92 : 0.7));
		if (selected) Ui.rect(g, x + 1, y + 1, w - 2, h - 2, r, Theme.alpha(Theme.ACCENT, 0.16));
		Ui.outline(g, x + 1, y + 1, w - 2, h - 2, r, selected ? Theme.alpha(Theme.ACCENT_2, 0.6) : Theme.alpha(0xFFFFFF, 0.12));
	}

	/** Screen background: the launcher shader on the title side, the blurred world in game. */
	public static boolean background(GuiGraphicsExtractor g, Runnable blurWorld) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			MenuShader.draw(g, 1);
			g.fillGradient(0, 0, g.guiWidth(), g.guiHeight(), 0x3009080E, 0x9009080E);
		} else {
			blurWorld.run();
			g.fillGradient(0, 0, g.guiWidth(), g.guiHeight(), 0x7009080E, 0xC009080E);
		}
		return true;
	}
}
