package dev.slopclient.gui;

import dev.slopclient.SlopClient;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pieces shared by all SlopClient screens, matching the launcher's shell. */
final class Chrome {
	static final int PAD = 14;

	private Chrome() {
	}

	/** The SlopClient "S" mark (design/logo/glyph-512.png). */
	private static final net.minecraft.resources.Identifier LOGO = net.minecraft.resources.Identifier.fromNamespaceAndPath(SlopClient.ID, "textures/gui/logo.png");

	/** Glass watermark pill like the launcher's title bar: logo, name, version, player, Minecraft, clock. Returns its right edge. */
	static int titleBar(GuiGraphicsExtractor g, Minecraft mc, double k, String... extra) {
		int x = PAD - (int) Math.round((1 - k) * 12), y = 10, h = 20;
		String[] base = {
			"v" + SlopClient.VERSION,
			mc.getUser().getName(),
			"Minecraft " + SharedConstants.getCurrentVersion().name(),
			LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
		};
		String[] parts = new String[base.length + extra.length];
		System.arraycopy(extra, 0, parts, 0, extra.length);
		System.arraycopy(base, 0, parts, extra.length, base.length);

		int w = 22 + Draw.width("SlopClient") + 9;
		for (String p : parts) w += 13 + Draw.width(p);
		Draw.round(g, x, y, w, h, 6, Theme.alpha(Theme.PANEL_SOLID, 0.72 * k));
		Draw.roundOutline(g, x, y, w, h, 6, Theme.alpha(0xFFFFFF, 0.07 * k));
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, LOGO, x + 4, y + 3, 0, 0, 14, 14, 14, 14, Theme.alpha(0xFFFFFF, k));
		int cx = x + 22;
		cx += Draw.chromaText(g, "SlopClient", cx, y + 6, 0, false);
		for (String p : parts) {
			g.fill(cx + 6, y + 6, cx + 7, y + 14, Theme.alpha(0xFFFFFF, 0.14 * k));
			Draw.text(g, p, cx + 13, y + 6, Theme.alpha(Theme.MUTED, k));
			cx += 13 + Draw.width(p);
		}
		return x + w;
	}

	/** The launcher's spring toggle: chroma track when on, white knob. */
	static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, double alpha) {
		Draw.round(g, x, y, 16, 9, 4, on ? Theme.alpha(Theme.chroma(0), alpha) : Theme.alpha(0xFFFFFF, 0.16 * alpha));
		Draw.round(g, on ? x + 8 : x + 1, y + 1, 7, 7, 3, Theme.alpha(0xFFFFFF, alpha));
	}
}
