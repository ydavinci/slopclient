package dev.slopclient.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * SlopClient's own text rendering, so menus look like the launcher instead of
 * Minecraft's bitmap text. Minecraft bakes TrueType glyphs once at a fixed size,
 * rounds advances and ignores kerning; this rasterises every glyph with STB at the
 * exact physical pixel size it is shown at (GUI scale times any pose scale), keeps
 * fractional advances and applies the font's kerning.
 *
 * <p>One atlas per font and pixel size, filled as characters are first used.
 */
public final class CrispFont {
	private static final int ATLAS = 512;
	/** Slight emboldening: STB coverage is linear, browsers render a touch heavier. */
	private static final double GAMMA = 0.82;

	private static final Map<String, CrispFont> FONTS = new HashMap<>();

	private final String file;
	private ByteBuffer data;
	private STBTTFontinfo info;
	private boolean broken;
	private int ascent, descent;
	private final Map<Integer, Atlas> atlases = new HashMap<>();

	private CrispFont(String file) {
		this.file = file;
	}

	/** The font in assets/slopclient/font/{@code file}.ttf, loaded on first use. */
	public static CrispFont of(String file) {
		return FONTS.computeIfAbsent(file, CrispFont::new);
	}

	private boolean load() {
		if (info != null) return true;
		if (broken) return false;
		Identifier id = Identifier.fromNamespaceAndPath(SlopClient.ID, "font/" + file + ".ttf");
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id)) {
			byte[] bytes = in.readAllBytes();
			// STB reads the font in place, so the buffer has to stay alive.
			data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
			info = STBTTFontinfo.malloc();
			if (!STBTruetype.stbtt_InitFont(info, data)) throw new IllegalStateException("not a TrueType font");
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer a = stack.mallocInt(1), d = stack.mallocInt(1), g = stack.mallocInt(1);
				STBTruetype.stbtt_GetFontVMetrics(info, a, d, g);
				ascent = a.get(0);
				descent = d.get(0);
			}
			return true;
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not load font {}, using Minecraft's", file, e);
			broken = true;
			return false;
		}
	}

	/** Whether every character can be drawn with this font. */
	public boolean covers(String s) {
		if (!load()) return false;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			if (cp > ' ' && STBTruetype.stbtt_FindGlyphIndex(info, cp) == 0) return false;
			i += Character.charCount(cp);
		}
		return true;
	}

	/** Width in GUI pixels at font size `size` (em size in GUI pixels). */
	public float width(String s, float size) {
		if (!load()) return 0;
		float scale = STBTruetype.stbtt_ScaleForMappingEmToPixels(info, size);
		float w = 0;
		int prev = -1;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
			for (int i = 0; i < s.length(); ) {
				int cp = s.codePointAt(i);
				STBTruetype.stbtt_GetCodepointHMetrics(info, cp, adv, lsb);
				if (prev >= 0) w += STBTruetype.stbtt_GetCodepointKernAdvance(info, prev, cp) * scale;
				w += adv.get(0) * scale;
				prev = cp;
				i += Character.charCount(cp);
			}
		}
		return w;
	}

	/**
	 * Draws `s` with its top at `y` (line box of `lineHeight` GUI pixels, text centred
	 * in it like Minecraft's 9 px lines) and returns the advance in GUI pixels.
	 * `colors` may give one colour per character (chroma text), else `color` is used.
	 */
	public float draw(GuiGraphicsExtractor g, String s, float x, float y, float size, float lineHeight, int color, int[] colors, boolean shadow) {
		if (!load()) return 0;
		// Physical pixels per GUI pixel at this point, including HUD scaling.
		Matrix3x2f m = new Matrix3x2f(g.pose());
		float poseScale = (float) Math.hypot(m.m00(), m.m01());
		float px = (float) Minecraft.getInstance().getWindow().getGuiScale() * poseScale;
		int pixelSize = Math.max(4, Math.round(size * px));
		Atlas atlas = atlases.computeIfAbsent(pixelSize, Atlas::new);
		float scale = STBTruetype.stbtt_ScaleForMappingEmToPixels(info, pixelSize);

		// Baseline: the font's ascent, with the em box centred in the line.
		float emHeight = (ascent - descent) * scale;
		float top = (lineHeight * px - emHeight) / 2f;
		float baseline = top + ascent * scale;

		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(1f / px, 1f / px);
		float pen = 0;
		int prev = -1, index = 0;
		for (int i = 0; i < s.length(); index++) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (prev >= 0) pen += STBTruetype.stbtt_GetCodepointKernAdvance(info, prev, cp) * scale;
			Glyph glyph = atlas.glyph(cp, scale);
			int c = colors != null && index < colors.length ? colors[index] : color;
			if (glyph.w > 0) {
				int gx = Math.round(pen) + glyph.xoff, gy = Math.round(baseline) + glyph.yoff;
				if (shadow) blit(g, atlas, glyph, gx + Math.round(px), gy + Math.round(px), shadowOf(c));
				blit(g, atlas, glyph, gx, gy, c);
			}
			pen += glyph.advance;
			prev = cp;
		}
		g.pose().popMatrix();
		return pen / px;
	}

	private static int shadowOf(int color) {
		int a = color >>> 24;
		return (a << 24) | ((color >> 2) & 0x3F3F3F);
	}

	private static void blit(GuiGraphicsExtractor g, Atlas atlas, Glyph glyph, int x, int y, int color) {
		atlas.uploadIfDirty();
		g.blit(RenderPipelines.GUI_TEXTURED, atlas.id, x, y, glyph.u, glyph.v, glyph.w, glyph.h, glyph.w, glyph.h, ATLAS, ATLAS, color);
	}

	private record Glyph(int u, int v, int w, int h, int xoff, int yoff, float advance) {
	}

	/** Glyphs of one pixel size, packed row by row into a 512 px texture. */
	private final class Atlas {
		final Identifier id;
		final NativeImage image = new NativeImage(ATLAS, ATLAS, true);
		final DynamicTexture texture;
		final Map<Integer, Glyph> glyphs = new HashMap<>();
		int penX = 1, penY = 1, rowHeight;
		boolean dirty;

		Atlas(int pixelSize) {
			id = Identifier.fromNamespaceAndPath(SlopClient.ID, "crisp/" + file + "/" + pixelSize);
			for (int y = 0; y < ATLAS; y++) for (int x = 0; x < ATLAS; x++) image.setPixel(x, y, 0);
			texture = new DynamicTexture(() -> "SlopClient text " + file + " " + pixelSize, image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
		}

		void uploadIfDirty() {
			if (!dirty) return;
			texture.upload();
			dirty = false;
		}

		Glyph glyph(int cp, float scale) {
			Glyph cached = glyphs.get(cp);
			if (cached != null) return cached;
			Glyph glyph;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
				IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1), x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);
				STBTruetype.stbtt_GetCodepointHMetrics(info, cp, adv, lsb);
				STBTruetype.stbtt_GetCodepointBitmapBox(info, cp, scale, scale, x0, y0, x1, y1);
				int w = x1.get(0) - x0.get(0), h = y1.get(0) - y0.get(0);
				float advance = adv.get(0) * scale;
				if (w <= 0 || h <= 0 || cp == ' ') {
					glyph = new Glyph(0, 0, 0, 0, 0, 0, advance);
				} else {
					if (penX + w + 1 > ATLAS) {
						penX = 1;
						penY += rowHeight + 1;
						rowHeight = 0;
					}
					if (penY + h + 1 > ATLAS) {
						// Atlas full (very unusual): start over for this size.
						glyphs.clear();
						penX = 1;
						penY = 1;
						rowHeight = 0;
						for (int y = 0; y < ATLAS; y++) for (int x = 0; x < ATLAS; x++) image.setPixel(x, y, 0);
					}
					ByteBuffer bitmap = MemoryUtil.memAlloc(w * h);
					try {
						STBTruetype.stbtt_MakeCodepointBitmap(info, bitmap, w, h, w, scale, scale, cp);
						for (int y = 0; y < h; y++) {
							for (int x = 0; x < w; x++) {
								int a = bitmap.get(y * w + x) & 0xFF;
								if (a != 0) a = (int) Math.round(Math.pow(a / 255.0, GAMMA) * 255);
								image.setPixel(penX + x, penY + y, a << 24 | 0xFFFFFF);
							}
						}
					} finally {
						MemoryUtil.memFree(bitmap);
					}
					glyph = new Glyph(penX, penY, w, h, x0.get(0), y0.get(0), advance);
					penX += w + 1;
					rowHeight = Math.max(rowHeight, h);
					dirty = true;
				}
			}
			glyphs.put(cp, glyph);
			return glyph;
		}
	}
}
