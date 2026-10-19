package dev.daclient.render;

import dev.daclient.DaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Small 2D helpers on top of GuiGraphicsExtractor (rounded boxes, chroma text, scaled text).
 * All DaClient text goes through here, so the Sora font can be switched in one place.
 */
public final class Draw {
	/** Sora SemiBold for UI text, see assets/daclient/font/sora.json. */
	public static final FontDescription SORA = new FontDescription.Resource(Identifier.fromNamespaceAndPath(DaClient.ID, "sora"));
	/** Sora ExtraBold for the wordmark. */
	public static final FontDescription DISPLAY = new FontDescription.Resource(Identifier.fromNamespaceAndPath(DaClient.ID, "sora_display"));

	private Draw() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	private static boolean sora() {
		return DaClient.ready() && DaClient.modules().uiFont.active();
	}

	/** Text as a component in the current UI font. */
	public static Component styled(String s) {
		return styled(s, SORA);
	}

	private static Component styled(String s, FontDescription font) {
		Component c = Component.literal(s);
		return sora() ? c.copy().withStyle(st -> st.withFont(font)) : c;
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

	/**
	 * Rotating chroma border, like the launcher's conic "chroma-ring": the gradient
	 * runs once around the perimeter and turns over time.
	 */
	public static void chromaRing(GuiGraphicsExtractor g, int x, int y, int w, int h, int thickness) {
		int perimeter = 2 * (w + h);
		double turn = System.currentTimeMillis() / 4000.0;
		for (int i = 0; i < perimeter; i += 2) {
			int color = Theme.chroma(i / (double) perimeter - turn);
			int px, py;
			if (i < w) {
				px = x + i;
				py = y;
				g.fill(px, py, Math.min(px + 2, x + w), py + thickness, color);
			} else if (i < w + h) {
				px = x + w - thickness;
				py = y + (i - w);
				g.fill(px, py, px + thickness, Math.min(py + 2, y + h), color);
			} else if (i < 2 * w + h) {
				px = x + w - (i - w - h) - 2;
				py = y + h - thickness;
				g.fill(Math.max(px, x), py, px + 2, py + thickness, color);
			} else {
				px = x;
				py = y + h - (i - 2 * w - h) - 2;
				g.fill(px, Math.max(py, y), px + thickness, py + 2, color);
			}
		}
	}

	/** Soft glow around a box (stacked translucent rounds), like a CSS box-shadow. */
	public static void glow(GuiGraphicsExtractor g, int x, int y, int w, int h, int spread, int color, double strength) {
		for (int i = spread; i >= 1; i--) {
			double a = strength * (1 - i / (double) (spread + 1)) / spread * 2.2;
			round(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, 6 + i, Theme.alpha(color, a));
		}
	}

	/** Pill with a hairline border and muted text, the launcher's Chip. Returns its width. */
	public static int chip(GuiGraphicsExtractor g, int x, int y, String text, int dot) {
		int pad = 7, dotW = dot != 0 ? 7 : 0;
		int w = width(text) + pad * 2 + dotW;
		round(g, x, y, w, 14, 7, 0x0FFFFFFF);
		roundOutline(g, x, y, w, 14, 7, Theme.LINE_2);
		if (dot != 0) round(g, x + pad, y + 5, 4, 4, 2, dot);
		text(g, text, x + pad + dotW, y + 3, Theme.MUTED);
		return w;
	}

	/** Glass button: translucent fill, hairline border, brighter on hover. */
	public static void glassButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean hover, double alpha) {
		round(g, x, y, w, h, 6, Theme.alpha(hover ? Theme.RAISED : Theme.PANEL_SOLID, 0.78 * alpha));
		roundOutline(g, x, y, w, h, 6, Theme.alpha(0xFFFFFF, (hover ? 0.16 : 0.08) * alpha));
		text(g, label, x + (w - width(label)) / 2, y + (h - 8) / 2, Theme.alpha(hover ? 0xFFFFFF : Theme.TEXT, alpha));
	}

	/** Glass panel: translucent fill plus hairline border. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		round(g, x, y, w, h, r, Theme.PANEL);
		roundOutline(g, x, y, w, h, r, Theme.LINE);
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), styled(s), x, y, color, false);
	}

	public static void shadowText(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), styled(s), x, y, color, true);
	}

	public static int width(String s) {
		return font().width(styled(s));
	}

	/** Text where every letter carries its own chroma phase, so the colour rolls through. */
	public static int chromaText(GuiGraphicsExtractor g, String s, int x, int y, double phase, boolean shadow) {
		Font font = font();
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			Component ch = styled(String.valueOf(s.charAt(i)));
			g.text(font, ch, cx, y, Theme.chroma(phase - i * 0.07), shadow);
			cx += font.width(ch);
		}
		return cx - x;
	}

	public static void scaledText(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color, boolean shadow) {
		scaled(g, styled(s), x, y, scale, color, shadow);
	}

	/** Wordmark text (Sora ExtraBold when the Sora font is on). */
	public static void displayText(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color) {
		scaled(g, styled(s, DISPLAY), x, y, scale, color, false);
	}

	public static int displayWidth(String s) {
		return font().width(styled(s, DISPLAY));
	}

	private static void scaled(GuiGraphicsExtractor g, Component c, float x, float y, float scale, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font(), c, 0, 0, color, shadow);
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
