package dev.slopclient.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.CrispFont;
import dev.slopclient.render.Theme;
import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Launcher-style drawing in physical pixels: smooth rounded boxes, hairline borders,
 * soft shadows, glass, Sora text and Lucide icons.
 *
 * <p>Callers pass GUI coordinates (floats) like everywhere else. Each shape is drawn
 * with the pose scaled back to physical pixels, so corners are anti-aliased, borders
 * can be one real pixel thin and radii are exact at every GUI scale, instead of
 * stepping in GUI pixels (2 to 4 real pixels each).
 *
 * <p>Corners come from small coverage masks (signed distance, anti-aliased), cached per
 * radius and drawn as nine slices: four corners, stretched one-pixel edges, a filled
 * centre. The masks are white with alpha, so any colour tints them.
 */
public final class Ui {
	private static final CrispFont SANS = CrispFont.of("sora-semibold");
	private static final CrispFont SANS_BOLD = CrispFont.of("sora-extrabold");
	private static final CrispFont ICONS = CrispFont.of("lucide");

	private record Mask(Identifier id, int c, int size) {
	}

	private static final Map<String, Mask> MASKS = new HashMap<>();
	private static int nextMask;

	private Ui() {
	}

	// ---------- pixels ----------

	/** Physical pixels per GUI unit at the current pose (GUI scale times any pose scale). */
	public static float px(GuiGraphicsExtractor g) {
		Matrix3x2f m = new Matrix3x2f(g.pose());
		float poseScale = (float) Math.hypot(m.m00(), m.m01());
		return (float) Minecraft.getInstance().getWindow().getGuiScale() * poseScale;
	}

	/** One physical pixel in GUI units, for hairlines placed by hand. */
	public static float hairline(GuiGraphicsExtractor g) {
		return 1f / px(g);
	}

	private interface PixelDraw {
		void draw(int x, int y, int w, int h, int r);
	}

	/** Runs `draw` in physical pixels with the box and radius converted and snapped. */
	private static void inPixels(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, PixelDraw draw) {
		float p = px(g);
		int x0 = Math.round(x * p), y0 = Math.round(y * p), x1 = Math.round((x + w) * p), y1 = Math.round((y + h) * p);
		int pw = x1 - x0, ph = y1 - y0;
		if (pw <= 0 || ph <= 0) return;
		int pr = Math.max(0, Math.min(Math.round(r * p), Math.min(pw, ph) / 2));
		g.pose().pushMatrix();
		g.pose().scale(1f / p, 1f / p);
		draw.draw(x0, y0, pw, ph, pr);
		g.pose().popMatrix();
	}

	// ---------- masks ----------

	/** Signed distance from a pixel centre to a rounded box filling an n x n mask with an inset. */
	private static double sdf(int i, int j, int n, double inset, double radius) {
		double half = n / 2.0 - inset;
		double px = Math.abs(i + 0.5 - n / 2.0) - (half - radius);
		double py = Math.abs(j + 0.5 - n / 2.0) - (half - radius);
		double outside = Math.hypot(Math.max(px, 0), Math.max(py, 0));
		return outside + Math.min(Math.max(px, py), 0) - radius;
	}

	/**
	 * Mask of `kind` (fill, ring of `param` px, or shadow with `param` px blur) for radius
	 * `r` px. Size 2c+1: the corners are c x c, the middle row and column are straight edges.
	 */
	private static Mask mask(String kind, int r, int param) {
		return MASKS.computeIfAbsent(kind + r + "/" + param, key -> {
			int c = kind.equals("shadow") ? r + 2 * param : r;
			int n = 2 * c + 1;
			NativeImage image = new NativeImage(n, n, false);
			for (int j = 0; j < n; j++) {
				for (int i = 0; i < n; i++) {
					double a = switch (kind) {
						case "fill" -> coverage(sdf(i, j, n, 0, r));
						case "ring" -> coverage(sdf(i, j, n, 0, r)) - coverage(sdf(i, j, n, 0, r) + param);
						default -> {
							// Box shadow: a smooth step over twice the blur, close to a Gaussian.
							double d = sdf(i, j, n, 2 * param, r);
							double t = Math.max(0, Math.min(1, (d + param) / (2.0 * param)));
							yield 1 - t * t * (3 - 2 * t);
						}
					};
					int alpha = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
					image.setPixel(i, j, alpha << 24 | 0xFFFFFF);
				}
			}
			Identifier id = Identifier.fromNamespaceAndPath(SlopClient.ID, "ui/mask/" + (nextMask++));
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "SlopClient UI " + key, image));
			return new Mask(id, c, n);
		});
	}

	/** Area of a pixel inside the shape for a signed distance at its centre. */
	private static double coverage(double d) {
		return Math.max(0, Math.min(1, 0.5 - d));
	}

	private static void blit(GuiGraphicsExtractor g, Mask m, int x, int y, int u, int v, int w, int h, int srcW, int srcH, int color) {
		if (w <= 0 || h <= 0) return;
		g.blit(RenderPipelines.GUI_TEXTURED, m.id(), x, y, u, v, w, h, srcW, srcH, m.size(), m.size(), color);
	}

	/** Nine slices of `m` over the pixel box; `centre` fills the middle with the colour. */
	private static void nine(GuiGraphicsExtractor g, Mask m, int x, int y, int w, int h, int color, boolean centre) {
		int c = m.c();
		int midW = w - 2 * c, midH = h - 2 * c;
		blit(g, m, x, y, 0, 0, c, c, c, c, color);
		blit(g, m, x + w - c, y, c + 1, 0, c, c, c, c, color);
		blit(g, m, x, y + h - c, 0, c + 1, c, c, c, c, color);
		blit(g, m, x + w - c, y + h - c, c + 1, c + 1, c, c, c, c, color);
		blit(g, m, x + c, y, c, 0, midW, c, 1, c, color);
		blit(g, m, x + c, y + h - c, c, c + 1, midW, c, 1, c, color);
		blit(g, m, x, y + c, 0, c, c, midH, c, 1, color);
		blit(g, m, x + w - c, y + c, c + 1, c, c, midH, c, 1, color);
		if (centre && midW > 0 && midH > 0) g.fill(x + c, y + c, x + c + midW, y + c + midH, color);
	}

	// ---------- shapes ----------

	/** Filled box with anti-aliased corners of radius `r` (GUI units). */
	public static void rect(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int color) {
		if (color >>> 24 == 0) return;
		inPixels(g, x, y, w, h, r, (px, py, pw, ph, pr) -> {
			if (pr == 0) g.fill(px, py, px + pw, py + ph, color);
			else nine(g, mask("fill", pr, 0), px, py, pw, ph, color, true);
		});
	}

	/** Border along the inside of the box, `thickness` physical pixels wide (1 = hairline). */
	public static void border(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int thickness, int color) {
		if (color >>> 24 == 0) return;
		inPixels(g, x, y, w, h, r, (px, py, pw, ph, pr) -> {
			if (pr == 0) {
				g.fill(px, py, px + pw, py + thickness, color);
				g.fill(px, py + ph - thickness, px + pw, py + ph, color);
				g.fill(px, py + thickness, px + thickness, py + ph - thickness, color);
				g.fill(px + pw - thickness, py + thickness, px + pw, py + ph - thickness, color);
				return;
			}
			Mask m = mask("ring", pr, thickness);
			nine(g, m, px, py, pw, ph, color, false);
			// The stretched edges of a ring mask are only as thick as the ring; nothing else to fill.
		});
	}

	/** Hairline border scaled a little on large GUI scales (1 px up to scale 3, then 2 px). */
	public static void outline(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int color) {
		border(g, x, y, w, h, r, px(g) > 3.5f ? 2 : 1, color);
	}

	/** Soft shadow under a box, like CSS `box-shadow: 0 offsetY blur color`. */
	public static void shadow(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float blur, float offsetY, int color) {
		if (color >>> 24 == 0) return;
		float p = px(g);
		int b = Math.max(1, Math.round(blur * p));
		float grow = 2 * b / p;
		inPixels(g, x - grow, y - grow + offsetY, w + 2 * grow, h + 2 * grow, 0, (px, py, pw, ph, pr) -> {
			int radius = Math.max(0, Math.min(Math.round(r * p), Math.min(pw, ph) / 2 - 2 * b));
			nine(g, mask("shadow", radius, b), px, py, pw, ph, color, true);
		});
	}

	/**
	 * Box filled column by column with `colorAt(t)` (t = 0 at the left, 1 at the right),
	 * corners still anti-aliased: the launcher's chroma button.
	 */
	public static void gradient(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, DoubleFunction<Integer> colorAt) {
		inPixels(g, x, y, w, h, r, (px, py, pw, ph, pr) -> {
			Mask m = pr > 0 ? mask("fill", pr, 0) : null;
			for (int dx = 0; dx < pw; dx++) {
				int color = colorAt.apply((dx + 0.5) / pw);
				int col = px + dx;
				boolean left = dx < pr, right = dx >= pw - pr;
				if (m == null || !(left || right)) {
					g.fill(col, py, col + 1, py + ph, color);
					continue;
				}
				// One column of the corner mask at the top and bottom, solid in between.
				int u = left ? dx : m.c() + 1 + (dx - (pw - pr));
				blit(g, m, col, py, u, 0, 1, pr, 1, pr, color);
				blit(g, m, col, py + ph - pr, u, m.c() + 1, 1, pr, 1, pr, color);
				g.fill(col, py + pr, col + 1, py + ph - pr, color);
			}
		});
	}

	/** The travelling chroma gradient (accent colours) across a box. */
	public static void chroma(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, double alpha, double brighten) {
		gradient(g, x, y, w, h, r, t -> {
			int c = Theme.chroma(-t * 0.5);
			if (brighten > 0) c = Theme.lerp(c, 0xFFFFFFFF, brighten);
			return Theme.alpha(c, alpha);
		});
	}

	/**
	 * Liquid glass like the launcher's sidebar and panels: faint tint with a light falling
	 * in from the top left, a specular rim along the top and a hairline border.
	 */
	public static void glass(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, double alpha) {
		shadow(g, x, y, w, h, r, 18, 8, Theme.alpha(0xFF000000, 0.45 * alpha));
		rect(g, x, y, w, h, r, Theme.alpha(Theme.PANEL_SOLID, 0.62 * alpha));
		// Light from the left: a white wash that fades towards the right.
		gradient(g, x, y, w, h, r, t -> Theme.alpha(0xFFFFFF, (0.06 - 0.045 * t) * alpha));
		rect(g, x, y, w, h, r, Theme.alpha(Theme.ACCENT, 0.05 * alpha));
		outline(g, x, y, w, h, r, Theme.alpha(0xFFFFFF, 0.1 * alpha));
		// Specular rim: a brighter hairline just inside the top edge, faded towards the corners.
		float hl = hairline(g);
		gradient(g, x + r, y + hl, w - 2 * r, hl, 0, t -> Theme.alpha(0xFFFFFF, 0.38 * Math.sin(Math.PI * t) * alpha));
	}

	// ---------- text and icons ----------

	/** Sora SemiBold at `size` (em size in GUI units), top at `y`. Returns the advance. */
	public static float text(GuiGraphicsExtractor g, String s, float x, float y, float size, int color) {
		return SANS.draw(g, s, x, y, size, size * 1.25f, color, null, false);
	}

	/** Sora ExtraBold, for headlines and the big instance name. */
	public static float bold(GuiGraphicsExtractor g, String s, float x, float y, float size, int color) {
		return SANS_BOLD.draw(g, s, x, y, size, size * 1.25f, color, null, false);
	}

	/** Sora ExtraBold with one colour per letter (chroma headline). */
	public static float bold(GuiGraphicsExtractor g, String s, float x, float y, float size, int[] colors) {
		return SANS_BOLD.draw(g, s, x, y, size, size * 1.25f, 0xFFFFFFFF, colors, false);
	}

	public static float width(String s, float size) {
		return SANS.width(s, size);
	}

	public static float boldWidth(String s, float size) {
		return SANS_BOLD.width(s, size);
	}

	/** Lucide icon ({@link Icon}) in a `size` x `size` box at (x, y). */
	public static void icon(GuiGraphicsExtractor g, String icon, float x, float y, float size, int color) {
		ICONS.draw(g, icon, x, y, size, size, color, null, false);
	}

	/** `s` shortened with "…" to fit `maxWidth` at `size`. */
	public static String fit(String s, float size, float maxWidth) {
		if (width(s, size) <= maxWidth) return s;
		int end = s.length();
		while (end > 0 && width(s.substring(0, end) + "…", size) > maxWidth) end--;
		return s.substring(0, end) + "…";
	}

	// ---------- motion ----------

	private static final Map<String, double[]> ANIMS = new HashMap<>();

	/**
	 * Eases a value towards 1 while `on`, towards 0 otherwise (hover and press states),
	 * frame-rate independent, about 120 ms to settle.
	 */
	public static double anim(String key, boolean on) {
		long now = System.nanoTime();
		double[] s = ANIMS.computeIfAbsent(key, k -> new double[] {on ? 1 : 0, now});
		double dt = Math.min(0.1, (now - s[1]) / 1e9);
		s[1] = now;
		double target = on ? 1 : 0;
		s[0] += (target - s[0]) * (1 - Math.exp(-dt * 22));
		if (Math.abs(target - s[0]) < 0.002) s[0] = target;
		return s[0];
	}
}
