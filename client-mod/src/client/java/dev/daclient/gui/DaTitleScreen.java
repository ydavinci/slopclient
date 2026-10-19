package dev.daclient.gui;

import dev.daclient.DaClient;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * DaClient title screen: blurred panorama under a violet wash, a chroma wordmark
 * whose letters rise in one by one, and a vertical button list.
 */
public final class DaTitleScreen extends Screen {
	private static final int BTN_W = 200;
	/** Space the corner notices need at the bottom. */
	private static final int FOOTER = 40;
	private static final String LOGO = "DaClient";

	private record Entry(String label, String key, Runnable action) {
	}

	private final long openedAt = System.currentTimeMillis();
	private List<Entry> entries = List.of();

	public DaTitleScreen() {
		super(Component.literal("DaClient"));
	}

	@Override
	protected void init() {
		entries = List.of(
			new Entry("Einzelspieler", "", () -> minecraft.gui.setScreen(new SelectWorldScreen(this))),
			new Entry("Mehrspieler", "", () -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this))),
			new Entry("DaClient-Menü", "R-SHIFT", () -> minecraft.gui.setScreen(new ClickGuiScreen(this))),
			new Entry("Optionen", "", () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))),
			new Entry("Beenden", "", minecraft::stop));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	private double seconds() {
		return (System.currentTimeMillis() - openedAt) / 1000.0;
	}

	private boolean compact() {
		return height < 300;
	}

	private int btnH() {
		return compact() ? 17 : 20;
	}

	private int btnGap() {
		return compact() ? 3 : 5;
	}

	private float logoScale() {
		return compact() ? 3f : 4f;
	}

	/** Logo, subtitle and menu are centred together in the space above the footer. */
	private int blockTop() {
		int menuH = entries.size() * (btnH() + btnGap()) - btnGap();
		int block = Math.round(8 * logoScale()) + 26 + menuH;
		return Math.max(6, (height - FOOTER - block) / 2);
	}

	private int menuTop() {
		return blockTop() + Math.round(8 * logoScale()) + 26;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		extractPanorama(g, partialTick);
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, 0xB0120D20, 0xE809080E);
		// Two slow violet glows drifting behind the logo.
		double t = seconds();
		glow(g, (int) (width * 0.32 + Math.sin(t * 0.21) * width * 0.08), (int) (height * 0.3 + Math.cos(t * 0.17) * 30), 150, Theme.ACCENT);
		glow(g, (int) (width * 0.7 + Math.cos(t * 0.19) * width * 0.07), (int) (height * 0.62 + Math.sin(t * 0.23) * 26), 120, Theme.ACCENT_3);
	}

	/** Soft radial glow made of stacked translucent rounded squares. */
	private static void glow(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
		for (int i = 10; i >= 1; i--) {
			int r = radius * i / 10;
			Draw.round(g, cx - r, cy - r, r * 2, r * 2, Math.min(4, r), Theme.alpha(color, 0.022));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		double t = seconds();
		drawLogo(g, t);
		drawMenu(g, mouseX, mouseY, t);
		drawCorners(g, t);
	}

	private void drawLogo(GuiGraphicsExtractor g, double t) {
		float scale = logoScale();
		int logoW = Math.round(Draw.displayWidth(LOGO) * scale);
		float float_ = (float) Math.sin(t * 1.05) * 2f;
		float x = (width - logoW) / 2f;
		float y = blockTop() + float_;
		for (int i = 0; i < LOGO.length(); i++) {
			String ch = String.valueOf(LOGO.charAt(i));
			double k = Theme.ease((t - 0.15 - i * 0.045) / 0.7);
			if (k > 0) {
				int color = Theme.alpha(Theme.chroma(-i * 0.07), k);
				Draw.displayText(g, ch, x, y + (float) ((1 - k) * 12), scale, color);
			}
			x += Draw.displayWidth(ch) * scale;
		}
		String sub = "Minecraft " + SharedConstants.getCurrentVersion().name() + " · Legit-Client";
		double ks = Theme.ease((t - 0.5) / 0.6);
		if (ks > 0) Draw.text(g, sub, (width - Draw.width(sub)) / 2, (int) (blockTop() + 8 * scale + 5 + float_), Theme.alpha(Theme.MUTED, ks));
	}

	private int[] button(int i) {
		return new int[] {(width - BTN_W) / 2, menuTop() + i * (btnH() + btnGap()), BTN_W, btnH()};
	}

	private void drawMenu(GuiGraphicsExtractor g, int mx, int my, double t) {
		for (int i = 0; i < entries.size(); i++) {
			double k = Theme.ease((t - 0.5 - i * 0.06) / 0.55);
			if (k <= 0) continue;
			int[] b = button(i);
			int y = b[1] + (int) Math.round((1 - k) * 10);
			boolean hover = Draw.inside(mx, my, b[0], b[1], b[2], b[3]);
			int x = b[0] + (hover ? 3 : 0);
			if (hover) Draw.round(g, x, y, b[2], b[3], 4, Theme.chroma(-i * 0.05));
			else {
				Draw.round(g, x, y, b[2], b[3], 4, Theme.alpha(Theme.PANEL_SOLID, 0.82 * k));
				// The first entry gets a chroma outline, like the launcher's play button.
				Draw.roundOutline(g, x, y, b[2], b[3], 4, i == 0 ? Theme.chroma(0) : Theme.alpha(0xFFFFFF, 0.1 * k));
			}
			Entry e = entries.get(i);
			Draw.text(g, e.label(), x + 10, y + (b[3] - 8) / 2, Theme.alpha(hover ? 0xFFFFFF : Theme.TEXT, k));
			if (!e.key().isEmpty()) {
				Draw.scaledText(g, e.key(), x + b[2] - 10 - Draw.width(e.key()) * 0.75f, y + (b[3] - 6) / 2f, 0.75f, hover ? 0xFFFFFFFF : Theme.alpha(Theme.MUTED, k), false);
			}
		}
	}

	private void drawCorners(GuiGraphicsExtractor g, double t) {
		double k = Theme.ease((t - 0.9) / 0.6);
		if (k <= 0) return;
		// Bottom left: account and versions.
		String user = minecraft.getUser().getName();
		int ax = 10, ay = height - 30;
		Draw.round(g, ax, ay, 20, 20, 4, Theme.alpha(Theme.chroma(0), k));
		String initial = user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase();
		Draw.text(g, initial, ax + 10 - Draw.width(initial) / 2, ay + 6, Theme.alpha(0xFFFFFF, k));
		Draw.text(g, user, ax + 26, ay + 2, Theme.alpha(Theme.TEXT, k));
		Draw.scaledText(g, "DaClient " + DaClient.VERSION, ax + 26, ay + 12, 0.75f, Theme.alpha(Theme.MUTED, k), false);

		// Bottom right: required notices.
		String[] lines = {"Kein offizielles Minecraft-Produkt.", "Nicht von Mojang oder Microsoft genehmigt.", "Copyright Mojang AB. Do not distribute!"};
		for (int i = 0; i < lines.length; i++) {
			float w = Draw.width(lines[i]) * 0.75f;
			Draw.scaledText(g, lines[i], width - 10 - w, height - 30 + i * 8, 0.75f, Theme.alpha(Theme.DIM, k), false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (int i = 0; i < entries.size(); i++) {
				int[] b = button(i);
				if (Draw.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
					entries.get(i).action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
