package dev.daclient.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small 2D helpers on top of GuiGraphicsExtractor (rounded boxes, chroma text, scaled text). */
public final class Draw {
	private Draw() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	/** Filled rectangle with corners cut to an approximate radius (0 to 4 px). */
	public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** 1 px border that follows the corners of {@link #round}. */
	public static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		r = Math.min(r, Math.min(w, h) / 2);
		g.fill(x + r, y, x + w - r, y + 1, color);
		g.fill(x + r, y + h - 1, x + w - r, y + h, color);
		g.fill(x, y + r, x + 1, y + h - r, color);
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);
		int prev = r;
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			int end = Math.max(inset + 1, prev);
			g.fill(x + inset, y + i, x + end, y + i + 1, color);
			g.fill(x + w - end, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + end, y + h - i, color);
			g.fill(x + w - end, y + h - i - 1, x + w - inset, y + h - i, color);
			prev = inset;
		}
	}

	/** Glass panel: translucent fill plus hairline border. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		round(g, x, y, w, h, r, Theme.PANEL);
		roundOutline(g, x, y, w, h, r, Theme.LINE);
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), s, x, y, color, false);
	}

	public static void shadowText(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), s, x, y, color, true);
	}

	public static int width(String s) {
		return font().width(s);
	}

	/** Text where every letter carries its own chroma phase, so the colour rolls through. */
	public static int chromaText(GuiGraphicsExtractor g, String s, int x, int y, double phase, boolean shadow) {
		Font font = font();
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			String ch = String.valueOf(s.charAt(i));
			g.text(font, ch, cx, y, Theme.chroma(phase - i * 0.07), shadow);
			cx += font.width(ch);
		}
		return cx - x;
	}

	public static void scaledText(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font(), s, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	public static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Dashed rectangle outline, used for HUD editor frames. */
	public static void dashed(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		for (int i = 0; i < w; i += 4) {
			int e = Math.min(i + 2, w);
			g.fill(x + i, y, x + e, y + 1, color);
			g.fill(x + i, y + h - 1, x + e, y + h, color);
		}
		for (int i = 0; i < h; i += 4) {
			int e = Math.min(i + 2, h);
			g.fill(x, y + i, x + 1, y + e, color);
			g.fill(x + w - 1, y + i, x + w, y + e, color);
		}
	}
}
