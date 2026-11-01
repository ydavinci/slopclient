package dev.slopclient.ui;

import dev.slopclient.SlopClient;
import dev.slopclient.render.Theme;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Building blocks of the launcher look, shared by the SlopClient menus: the glass
 * sidebar, buttons, chips and the player pill. Each clickable part adds a {@link Hit}
 * to the list the screen hands in, so drawing and clicking use the same layout.
 */
public final class Kit {
	public static final float RAIL_X = 10, RAIL_W = 40, ITEM = 28, BTN_H = 34;
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SlopClient.ID, "textures/gui/logo.png");

	private Kit() {
	}

	/** Something clickable laid out this frame. */
	public record Hit(float x, float y, float w, float h, Runnable action) {
		public boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** A sidebar entry; disabled ones are dimmed and only show their tooltip. */
	public record Entry(String label, String icon, boolean active, boolean enabled, Runnable action) {
	}

	public static boolean over(float x, float y, float w, float h, int mx, int my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Left edge of the content next to the sidebar. */
	public static float contentLeft() {
		return RAIL_X + RAIL_W + 24;
	}

	// ---------- sidebar ----------

	/**
	 * The liquid-glass sidebar: logo, `entries` from the top, `bottom` (quit or leave) at
	 * the bottom in red on hover. Returns {label, centre y} of the hovered entry for
	 * {@link #tooltip}, or null.
	 */
	public static String[] rail(GuiGraphicsExtractor g, String key, List<Entry> entries, Entry bottom, int height, int mx, int my, double k, List<Hit> hits) {
		float x = RAIL_X - (float) (1 - k) * 12, y = 10, h = height - 20;
		Ui.glass(g, x, y, RAIL_W, h, 14, k);
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO, Math.round(x + 11), Math.round(y + 10), 0, 0, 18, 18, 18, 18, Theme.alpha(0xFFFFFF, k));
		Ui.rect(g, x + 10, y + 35, RAIL_W - 20, Ui.hairline(g), 0, Theme.alpha(0xFFFFFF, 0.1 * k));

		String[] tooltip = null;
		float ix = x + (RAIL_W - ITEM) / 2, iy = y + 42;
		for (Entry e : entries) {
			boolean hover = over(ix, iy, ITEM, ITEM, mx, my);
			double hv = Ui.anim(key + ".nav." + e.label(), hover && e.enabled());
			if (e.active()) {
				Ui.rect(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(Theme.ACCENT, 0.24 * k));
				Ui.outline(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(Theme.ACCENT_2, 0.32 * k));
				Ui.chroma(g, x + 1.5f, iy + 8, 2.5f, ITEM - 16, 1.25f, k, 0);
			} else if (hv > 0) {
				Ui.rect(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(0xFFFFFF, 0.08 * hv * k));
			}
			int color = e.active() ? 0xFFFFFFFF : Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
			Ui.icon(g, e.icon(), ix + 7, iy + 7, 14, Theme.alpha(color, (e.enabled() ? 1 : 0.3) * k));
			if (e.enabled() && e.action() != null) hits.add(new Hit(ix, iy, ITEM, ITEM, e.action()));
			if (hover) tooltip = new String[] {e.label(), Float.toString(iy + ITEM / 2)};
			iy += ITEM + 6;
		}

		if (bottom != null) {
			float qy = y + h - ITEM - 6;
			boolean hover = over(ix, qy, ITEM, ITEM, mx, my);
			double hv = Ui.anim(key + ".nav.bottom", hover);
			if (hv > 0) Ui.rect(g, ix, qy, ITEM, ITEM, 9, Theme.alpha(Theme.ERR, 0.16 * hv * k));
			Ui.icon(g, bottom.icon(), ix + 7, qy + 7, 14, Theme.alpha(Theme.lerp(Theme.MUTED, Theme.ERR, hv), k));
			hits.add(new Hit(ix, qy, ITEM, ITEM, bottom.action()));
			if (hover) tooltip = new String[] {bottom.label(), Float.toString(qy + ITEM / 2)};
		}
		return tooltip;
	}

	/** Tooltip right of the sidebar, vertically centred on `centreY`. */
	public static void tooltip(GuiGraphicsExtractor g, String[] tooltip) {
		if (tooltip == null) return;
		String label = tooltip[0];
		float centreY = Float.parseFloat(tooltip[1]);
		float size = 8, w = Ui.width(label, size) + 16, h = 18;
		float x = RAIL_X + RAIL_W + 8, y = centreY - h / 2;
		Ui.shadow(g, x, y, w, h, 7, 8, 3, 0x66000000);
		Ui.rect(g, x, y, w, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.96));
		Ui.outline(g, x, y, w, h, 7, Theme.LINE_2);
		Ui.text(g, label, x + 8, y + (h - size * 1.25f) / 2, size, Theme.TEXT);
	}

	/** Player name and clock in a glass pill at the top right; returns its bottom edge. */
	public static float playerPill(GuiGraphicsExtractor g, int width, double k) {
		Minecraft mc = Minecraft.getInstance();
		String name = mc.getUser().getName(), time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
		float size = 8, h = 22;
		float w = 10 + 17 + Ui.width(name, size) + 13 + Ui.width(time, size) + 10;
		float x = width - 12 - w, y = 10;
		if (k <= 0) return y + h;
		Ui.rect(g, x, y, w, h, 11, Theme.alpha(Theme.PANEL_SOLID, 0.55 * k));
		Ui.outline(g, x, y, w, h, 11, Theme.alpha(0xFFFFFF, 0.09 * k));
		float cx = x + 10;
		Ui.icon(g, Icon.USER, cx, y + 5, 12, Theme.alpha(Theme.ACCENT_2, k));
		cx += 17;
		cx += Ui.text(g, name, cx, y + (h - size * 1.25f) / 2, size, Theme.alpha(Theme.TEXT, k));
		Ui.rect(g, cx + 6, y + 7, Ui.hairline(g), h - 14, 0, Theme.alpha(0xFFFFFF, 0.14 * k));
		Ui.text(g, time, cx + 13, y + (h - size * 1.25f) / 2, size, Theme.alpha(Theme.MUTED, k));
		return y + h;
	}

	// ---------- buttons ----------

	/** Width of a primary button for `label` with an optional second line `sub`. */
	public static float primaryWidth(String label, String sub) {
		float text = Math.max(Ui.boldWidth(label, 10), sub == null ? 0 : Ui.width(sub, 7));
		return Math.min(190, Math.max(118, text + 46));
	}

	/** The launcher's play button: chroma fill, glow and a ring just outside it. */
	public static void primary(GuiGraphicsExtractor g, String key, String label, String sub, String icon, float x, float y, float w, int mx, int my, double k, List<Hit> hits, Runnable action) {
		boolean hover = over(x, y, w, BTN_H, mx, my);
		double hv = Ui.anim(key, hover);
		Ui.shadow(g, x, y, w, BTN_H, 12, 14, 4, Theme.alpha(Theme.ACCENT, (0.45 + 0.25 * hv) * k));
		Ui.outline(g, x - 3, y - 3, w + 6, BTN_H + 6, 15, Theme.alpha(Theme.ACCENT_2, (0.45 + 0.4 * hv) * k));
		Ui.chroma(g, x, y, w, BTN_H, 12, k, 0.1 * hv);
		Ui.icon(g, icon, x + 12, y + (BTN_H - 14) / 2, 14, Theme.alpha(0xFFFFFF, k));
		if (sub == null) {
			Ui.bold(g, label, x + 32, y + (BTN_H - 12.5f) / 2, 10, Theme.alpha(0xFFFFFF, k));
		} else {
			Ui.bold(g, label, x + 32, y + 5, 10, Theme.alpha(0xFFFFFF, k));
			Ui.text(g, Ui.fit(sub, 7, w - 42), x + 32, y + 19, 7, Theme.alpha(0xFFFFFF, 0.78 * k));
		}
		hits.add(new Hit(x, y, w, BTN_H, action));
	}

	public static float glassWidth(String label) {
		return Ui.width(label, 9) + 42;
	}

	/** Secondary button: raised glass with an icon. `danger` turns it red on hover. */
	public static void glass(GuiGraphicsExtractor g, String key, String label, String icon, float x, float y, float w, boolean danger, int mx, int my, double k, List<Hit> hits, Runnable action) {
		boolean hover = over(x, y, w, BTN_H, mx, my);
		double hv = Ui.anim(key, hover);
		int tint = danger ? Theme.lerp(Theme.RAISED, 0xFF3A1820, hv) : Theme.RAISED;
		Ui.rect(g, x, y, w, BTN_H, 12, Theme.alpha(tint, (0.68 + 0.27 * hv) * k));
		Ui.outline(g, x, y, w, BTN_H, 12, Theme.alpha(danger ? Theme.lerp(0xFFFFFFFF, Theme.ERR, hv) : 0xFFFFFFFF, (0.08 + (danger ? 0.3 : 0.1) * hv) * k));
		int iconColor = danger ? Theme.lerp(Theme.MUTED, Theme.ERR, hv) : Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
		Ui.icon(g, icon, x + 12, y + (BTN_H - 13) / 2, 13, Theme.alpha(iconColor, k));
		Ui.text(g, Ui.fit(label, 9, w - 38), x + 30, y + (BTN_H - 9 * 1.25f) / 2, 9, Theme.alpha(Theme.TEXT, k));
		hits.add(new Hit(x, y, w, BTN_H, action));
	}

	/** Small pill button (secondary actions, mod buttons); returns its width. */
	public static float pill(GuiGraphicsExtractor g, String key, String label, float x, float y, boolean enabled, int mx, int my, double k, List<Hit> hits, Runnable action) {
		float size = 7.5f, w = Ui.width(label, size) + 18, h = 18;
		boolean hover = enabled && over(x, y, w, h, mx, my);
		double hv = Ui.anim(key, hover);
		Ui.rect(g, x, y, w, h, 9, Theme.alpha(0xFFFFFF, (0.05 + 0.07 * hv) * k));
		Ui.outline(g, x, y, w, h, 9, Theme.alpha(0xFFFFFF, (0.1 + 0.12 * hv) * k));
		Ui.text(g, label, x + 9, y + (h - size * 1.25f) / 2, size, Theme.alpha(enabled ? Theme.lerp(Theme.MUTED, Theme.TEXT, hv) : Theme.DIM, k));
		if (enabled) hits.add(new Hit(x, y, w, h, action));
		return w;
	}

	public static float pillWidth(String label) {
		return Ui.width(label, 7.5f) + 18;
	}

	// ---------- chips ----------

	public static float chipWidth(String text, boolean dot) {
		return Ui.width(text, 7.5f) + 16 + (dot ? 8 : 0);
	}

	/** Status chip like the launcher's; `dot` 0 for none. Returns its width. */
	public static float chip(GuiGraphicsExtractor g, String text, float x, float y, int dot, double k) {
		float size = 7.5f, w = chipWidth(text, dot != 0);
		Ui.rect(g, x, y, w, 16, 8, Theme.alpha(0xFFFFFF, 0.06 * k));
		Ui.outline(g, x, y, w, 16, 8, Theme.alpha(0xFFFFFF, 0.12 * k));
		float tx = x + 8;
		if (dot != 0) {
			Ui.rect(g, tx, y + 6, 4, 4, 2, Theme.alpha(dot, k));
			tx += 8;
		}
		Ui.text(g, text, tx, y + (16 - size * 1.25f) / 2, size, Theme.alpha(dot != 0 ? Theme.TEXT : Theme.MUTED, k));
		return w;
	}
}
