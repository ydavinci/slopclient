package dev.slopclient.render;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Small 2D helpers on top of GuiGraphicsExtractor (rounded boxes, chroma text, scaled text).
 * All SlopClient text goes through here, so the UI font can be switched in one place.
 */
public final class Draw {
	/**
	 * Fonts for the "Schrift" module: name in the menu, then the asset id of its UI
	 * (SemiBold) and display (ExtraBold) definitions in assets/slopclient/font. All are
	 * OFL fonts; missing glyphs fall back to the Minecraft font.
	 */
	public static final String[][] FONTS = {
		{"Sora", "sora", "8.5", "9"},
		{"Inter", "inter", "8", "8.5"},
		{"JetBrains Mono", "jetbrains_mono", "8", "8.5"},
		{"Space Grotesk", "space_grotesk", "8.5", "9"},
		{"Outfit", "outfit", "9", "9.5"},
		{"Poppins", "poppins", "8.5", "9"},
		{"Minecraft", null, null, null},
	};
	private static final java.util.Map<String, FontDescription[]> DESCRIPTIONS = new java.util.HashMap<>();

	/** The chosen font for SlopClient's own text renderer (see CrispFont). */
	private record Choice(CrispFont ui, CrispFont display, float uiSize, float displaySize) {
	}

	private static final java.util.Map<String, Choice> CHOICES = new java.util.HashMap<>();

	/** Minecraft's line height: text is centred in it, like the vanilla font. */
	private static final float LINE = 9;

	private static Choice choice() {
		if (!SlopClient.ready() || !SlopClient.modules().uiFont.active()) return null;
		String name = SlopClient.modules().uiFont.font.get();
		for (String[] f : FONTS) {
			if (!f[0].equals(name) || f[1] == null) continue;
			return CHOICES.computeIfAbsent(f[1], id -> {
				String file = id.replace('_', '-');
				return new Choice(CrispFont.of(file + "-semibold"), CrispFont.of(file + "-extrabold"), Float.parseFloat(f[2]), Float.parseFloat(f[3]));
			});
		}
		return null;
	}

	private Draw() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	/** UI and display font of the current choice; null keeps Minecraft's own font. */
	private static FontDescription[] current() {
		if (!SlopClient.ready() || !SlopClient.modules().uiFont.active()) return null;
		String name = SlopClient.modules().uiFont.font.get();
		for (String[] f : FONTS) {
			if (!f[0].equals(name)) continue;
			if (f[1] == null) return null;
			return DESCRIPTIONS.computeIfAbsent(f[1], id -> new FontDescription[] {
				new FontDescription.Resource(Identifier.fromNamespaceAndPath(SlopClient.ID, id)),
				new FontDescription.Resource(Identifier.fromNamespaceAndPath(SlopClient.ID, id + "_display")),
			});
		}
		return null;
	}

	/** The font that replaces Minecraft's default everywhere, or null (module off, "Minecraft" chosen, or only menus). */
	public static FontDescription gameFont() {
		if (!SlopClient.ready() || !SlopClient.modules().uiFont.everywhere.on()) return null;
		FontDescription[] font = current();
		return font == null ? null : font[0];
	}

	/** Text as a component in the current UI font. */
	public static Component styled(String s) {
		return styled(s, false);
	}

	private static Component styled(String s, boolean display) {
		Component c = Component.literal(s);
		FontDescription[] font = current();
		return font == null ? c : c.copy().withStyle(st -> st.withFont(font[display ? 1 : 0]));
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
	/** Width {@link #chip} will take. */
	public static int chipWidth(String text, boolean dot) {
		return width(text) + 14 + (dot ? 7 : 0);
	}

	public static int chip(GuiGraphicsExtractor g, int x, int y, String text, int dot) {
		int pad = 7, dotW = dot != 0 ? 7 : 0;
		int w = width(text) + pad * 2 + dotW;
		round(g, x, y, w, 14, 7, 0x0FFFFFFF);
		roundOutline(g, x, y, w, 14, 7, Theme.LINE_2);
		if (dot != 0) round(g, x + pad, y + 5, 4, 4, 2, dot);
		text(g, text, x + pad + dotW, y + 3, Theme.MUTED);
		return w;
	}

	/** Secondary button like the launcher's: raised glass, hairline border, brighter on hover. */
	public static void glassButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean hover, double alpha) {
		round(g, x, y, w, h, 8, Theme.alpha(Theme.RAISED, (hover ? 0.95 : 0.72) * alpha));
		roundOutline(g, x, y, w, h, 8, Theme.alpha(0xFFFFFF, (hover ? 0.16 : 0.07) * alpha));
		text(g, label, x + (w - width(label)) / 2, y + (h - 8) / 2, Theme.alpha(hover ? 0xFFFFFF : Theme.TEXT, alpha));
	}

	/**
	 * Rounded rectangle filled with the travelling chroma gradient, column by column
	 * (the launcher's primary button fill).
	 */
	public static void chromaFill(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, double alpha, double brighten) {
		r = Math.min(r, Math.min(w, h) / 2);
		for (int dx = 0; dx < w; dx++) {
			// Corner inset of this column.
			double edge = dx < r ? r - dx - 0.5 : dx >= w - r ? dx - (w - r) + 0.5 : 0;
			int inset = edge > 0 ? (int) Math.round(r - Math.sqrt(Math.max(0, r * r - edge * edge))) : 0;
			int c = Theme.chroma(-dx / (double) w * 0.5);
			if (brighten > 0) c = Theme.lerp(c, 0xFFFFFFFF, brighten);
			g.fill(x + dx, y + inset, x + dx + 1, y + h - inset, Theme.alpha(c, alpha));
		}
	}

	/** Glass panel: translucent fill plus hairline border. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		round(g, x, y, w, h, r, Theme.PANEL);
		roundOutline(g, x, y, w, h, r, Theme.LINE);
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		Choice c = choice();
		if (c != null && c.ui.covers(s)) c.ui.draw(g, s, x, y, c.uiSize, LINE, color, null, false);
		else g.text(font(), styled(s), x, y, color, false);
	}

	public static void shadowText(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		Choice c = choice();
		if (c != null && c.ui.covers(s)) c.ui.draw(g, s, x, y, c.uiSize, LINE, color, null, true);
		else g.text(font(), styled(s), x, y, color, true);
	}

	public static int width(String s) {
		Choice c = choice();
		if (c != null && c.ui.covers(s)) return (int) Math.ceil(c.ui.width(s, c.uiSize));
		return font().width(styled(s));
	}

	/** Text where every letter carries its own chroma phase, so the colour rolls through. */
	public static int chromaText(GuiGraphicsExtractor g, String s, int x, int y, double phase, boolean shadow) {
		Choice choice = choice();
		if (choice != null && choice.ui.covers(s)) {
			int[] colors = new int[s.codePointCount(0, s.length())];
			for (int i = 0; i < colors.length; i++) colors[i] = Theme.chroma(phase - i * 0.07);
			return (int) Math.ceil(choice.ui.draw(g, s, x, y, choice.uiSize, LINE, 0, colors, shadow));
		}
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
		Choice c = choice();
		if (c != null && c.ui.covers(s)) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			c.ui.draw(g, s, 0, 0, c.uiSize, LINE, color, null, shadow);
			g.pose().popMatrix();
			return;
		}
		scaled(g, styled(s), x, y, scale, color, shadow);
	}

	/** Wordmark text, in the ExtraBold cut of the chosen font. */
	public static void displayText(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color) {
		Choice c = choice();
		if (c != null && c.display.covers(s)) {
			// Drawn at its real size, not scaled up from a small bitmap.
			c.display.draw(g, s, x, y, c.displaySize * scale, LINE * scale, color, null, false);
			return;
		}
		scaled(g, styled(s, true), x, y, scale, color, false);
	}

	public static float displayWidth(String s) {
		Choice c = choice();
		if (c != null && c.display.covers(s)) return c.display.width(s, c.displaySize);
		return font().width(styled(s, true));
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
