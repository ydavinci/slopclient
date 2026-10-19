package dev.daclient.gui;

import dev.daclient.DaClient;
import dev.daclient.render.Draw;
import dev.daclient.render.MenuShader;
import dev.daclient.render.Theme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * The launcher's start page as the title screen: flowing shader backdrop,
 * watermark bar, active-mod ArrayList, a big instance name with status chips,
 * the chroma-ring play button and a strip of saved servers.
 */
public final class DaTitleScreen extends Screen {
	private static final int PAD = 14, CARD_W = 124, CARD_H = 30, BTN_H = 24;
	private static final Set<String> HIDDEN_MODS = Set.of("minecraft", "java", "fabricloader", "mixinextras");

	private record Button(String label, int x, int y, int w, boolean primary, Runnable action) {
		boolean hit(double mx, double my) {
			return Draw.inside(mx, my, x, y, w, BTN_H);
		}
	}

	private final long openedAt = System.currentTimeMillis();
	private final String instanceName = System.getProperty("daclient.instance", "DaClient");
	private final List<String[]> mods = new ArrayList<>();
	private final List<ServerData> servers = new ArrayList<>();
	private List<Button> buttons = List.of();

	public DaTitleScreen() {
		super(Component.literal("DaClient"));
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			var meta = mod.getMetadata();
			if (mod.getContainingMod().isPresent() || HIDDEN_MODS.contains(meta.getId())) continue;
			String version = meta.getVersion().getFriendlyString().replaceAll("^mc[\\d.]+-", "").split("\\+")[0];
			mods.add(new String[] {meta.getName(), version});
		}
		mods.sort(Comparator.comparingInt((String[] m) -> m[0].length() + m[1].length()).reversed());
	}

	@Override
	protected void init() {
		servers.clear();
		ServerList list = new ServerList(minecraft);
		list.load();
		for (int i = 0; i < list.size(); i++) servers.add(list.get(i));
		layout();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	private double seconds() {
		return (System.currentTimeMillis() - openedAt) / 1000.0;
	}

	/** Top of the server strip; equal to the height when there is no room for it. */
	private int stripY() {
		return height < 220 ? height : height - PAD - CARD_H;
	}

	private int playY() {
		return stripY() - (stripY() == height ? PAD + BTN_H : 16 + BTN_H);
	}

	private int chipsY() {
		return playY() - 12 - 14;
	}

	private float nameScale() {
		float fit = (width * 0.6f) / Math.max(1, Draw.displayWidth(instanceName));
		return Math.max(2.5f, Math.min(height < 300 ? 3.5f : 5.5f, fit));
	}

	private void layout() {
		int y = playY(), x = PAD + 2;
		List<Button> list = new ArrayList<>();
		String[] labels = {"Einzelspieler", "Mehrspieler", "DaClient-Menü", "Optionen"};
		Runnable[] actions = {
			() -> minecraft.gui.setScreen(new SelectWorldScreen(this)),
			() -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this)),
			() -> minecraft.gui.setScreen(new ClickGuiScreen(this)),
			() -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options)),
		};
		for (int i = 0; i < labels.length; i++) {
			int w = Draw.width(labels[i]) + (i == 0 ? 40 : 22);
			list.add(new Button(labels[i], x, y, w, i == 0, actions[i]));
			x += w + 6;
		}
		list.add(new Button("Beenden", x, y, Draw.width("Beenden") + 18, false, minecraft::stop));
		buttons = list;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		MenuShader.draw(g, Theme.ease(seconds() / 0.8));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		double t = seconds();
		drawTitleBar(g, t);
		drawArrayList(g, t);
		drawHero(g, t);
		drawButtons(g, mouseX, mouseY, t);
		drawServers(g, mouseX, mouseY, t);
		drawNotice(g, t);
	}

	private void drawTitleBar(GuiGraphicsExtractor g, double t) {
		Chrome.titleBar(g, minecraft, Theme.ease(t / 0.6));
	}

	/** Loaded mods, longest first, sliding in from the right like the launcher's ArrayList. */
	private void drawArrayList(GuiGraphicsExtractor g, double t) {
		int right = width - PAD, y = 12;
		int maxRows = Math.max(3, (int) (height * 0.42 / 11));
		String title = "Aktive Mods";
		double kt = Theme.ease((t - 0.3) / 0.5);
		if (kt > 0) Draw.scaledText(g, title, right - Draw.width(title) * 0.75f, y, 0.75f, Theme.alpha(Theme.DIM, kt), false);
		y += 10;
		for (int i = 0; i < Math.min(maxRows, mods.size()); i++) {
			double k = Theme.ease((t - 0.35 - i * 0.03) / 0.45);
			if (k <= 0) break;
			String[] m = mods.get(i);
			int rw = Draw.width(m[0]) + 5 + Draw.width(m[1]) + 10;
			int rx = right - rw + (int) Math.round((1 - k) * 16);
			int color = Theme.alpha(Theme.chroma(-i * 0.05), k);
			g.fill(rx, y, right, y + 11, Theme.alpha(Theme.PANEL_SOLID, 0.55 * k));
			g.fill(right - 1, y, right, y + 11, color);
			Draw.text(g, m[0], rx + 4, y + 2, color);
			Draw.text(g, m[1], rx + 9 + Draw.width(m[0]), y + 2, Theme.alpha(Theme.MUTED, k));
			y += 11;
		}
		if (mods.size() > maxRows && kt > 0) {
			String more = "+" + (mods.size() - maxRows) + " weitere";
			Draw.scaledText(g, more, right - Draw.width(more) * 0.75f, y + 2, 0.75f, Theme.alpha(Theme.DIM, kt), false);
		}
	}

	/** Big name revealed letter by letter, then the status chips. */
	private void drawHero(GuiGraphicsExtractor g, double t) {
		float scale = nameScale();
		float x = PAD + 2;
		float y = chipsY() - 8 - 8 * scale;
		for (int i = 0; i < instanceName.length(); i++) {
			String ch = String.valueOf(instanceName.charAt(i));
			double k = Theme.ease((t - 0.15 - i * 0.045) / 0.7);
			if (k > 0) Draw.displayText(g, ch, x, y + (float) ((1 - k) * 10), scale, Theme.alpha(Theme.chroma(-i * 0.07), k));
			x += Draw.displayWidth(ch) * scale;
		}

		double k = Theme.ease((t - 0.55) / 0.5);
		if (k <= 0) return;
		int cy = chipsY() + (int) Math.round((1 - k) * 6);
		int cx = PAD + 2;
		String loader = FabricLoader.getInstance().getModContainer("fabricloader")
			.map(c -> "Fabric " + c.getMetadata().getVersion().getFriendlyString()).orElse("Fabric");
		cx += Draw.chip(g, cx, cy, "Bereit", Theme.ON) + 4;
		cx += Draw.chip(g, cx, cy, "Minecraft " + SharedConstants.getCurrentVersion().name(), 0) + 4;
		cx += Draw.chip(g, cx, cy, loader, 0) + 4;
		cx += Draw.chip(g, cx, cy, mods.size() + " Mods", 0) + 8;
		String hint = "Rechts-Shift öffnet das DaClient-Menü.";
		if (cx + Draw.width(hint) < width - PAD) Draw.text(g, hint, cx, cy + 3, Theme.alpha(Theme.MUTED, k));
	}

	private void drawButtons(GuiGraphicsExtractor g, int mx, int my, double t) {
		for (int i = 0; i < buttons.size(); i++) {
			Button b = buttons.get(i);
			double k = Theme.ease((t - 0.65 - i * 0.05) / 0.5);
			if (k <= 0) continue;
			boolean hover = b.hit(mx, my);
			int y = b.y() + (int) Math.round((1 - k) * 8);
			if (b.primary()) {
				// The launcher's play button: glow, rotating chroma ring, chroma fill on hover.
				Draw.glow(g, b.x(), y, b.w(), BTN_H, 6, Theme.ACCENT, (hover ? 0.55 : 0.35) * k);
				Draw.round(g, b.x(), y, b.w(), BTN_H, 7, hover ? Theme.chroma(0) : Theme.alpha(Theme.PANEL_SOLID, 0.94 * k));
				Draw.chromaRing(g, b.x(), y, b.w(), BTN_H, 1);
				int tx = b.x() + 12, ty = y + BTN_H / 2;
				for (int r = 0; r < 4; r++) g.fill(tx + r, ty - 4 + r, tx + r + 1, ty + 4 - r, Theme.alpha(0xFFFFFF, k));
				Draw.text(g, b.label(), b.x() + 24, y + 8, Theme.alpha(0xFFFFFF, k));
			} else if (b.label().equals("Beenden")) {
				if (hover) Draw.round(g, b.x(), y, b.w(), BTN_H, 6, 0x14FFFFFF);
				Draw.text(g, b.label(), b.x() + (b.w() - Draw.width(b.label())) / 2, y + 8, Theme.alpha(hover ? Theme.TEXT : Theme.MUTED, k));
			} else {
				Draw.glassButton(g, b.x(), y, b.w(), BTN_H, b.label(), hover, k);
			}
		}
	}

	private static final String[] NOTICE = {"Kein offizielles Minecraft-Produkt. Nicht von Mojang", "oder Microsoft genehmigt. Copyright Mojang AB."};

	private int noticeWidth() {
		int w = 0;
		for (String line : NOTICE) w = Math.max(w, Math.round(Draw.width(line) * 0.7f));
		return w;
	}

	/** Server cards that fit next to the notice in the bottom-right corner (plus the add card). */
	private int cardCount() {
		int room = width - 2 * PAD - noticeWidth() - 12 - CARD_W;
		return Math.min(servers.size(), Math.max(0, room / (CARD_W + 6)));
	}

	private int[] card(int i) {
		return new int[] {PAD + 2 + i * (CARD_W + 6), stripY(), CARD_W, CARD_H};
	}

	/** Saved servers as cards (the launcher's instance strip); click to join. */
	private void drawServers(GuiGraphicsExtractor g, int mx, int my, double t) {
		if (stripY() == height) return;
		int count = cardCount();
		for (int i = 0; i <= count; i++) {
			double k = Theme.ease((t - 0.8 - i * 0.05) / 0.5);
			if (k <= 0) continue;
			int[] c = card(i);
			int y = c[1] + (int) Math.round((1 - k) * 8);
			boolean hover = Draw.inside(mx, my, c[0], c[1], c[2], c[3]);
			if (i == count) {
				// "Add server" card, dashed like the launcher's "Neue Instanz".
				Draw.dashed(g, c[0], y, c[2], c[3], Theme.alpha(hover ? Theme.ACCENT_2 : 0xFFFFFF, (hover ? 0.7 : 0.18) * k));
				Draw.round(g, c[0] + 7, y + 7, 16, 16, 4, Theme.alpha(0xFFFFFF, 0.06 * k));
				Draw.text(g, "+", c[0] + 12, y + 11, Theme.alpha(Theme.MUTED, k));
				Draw.text(g, "Server hinzufügen", c[0] + 29, y + 11, Theme.alpha(hover ? Theme.TEXT : Theme.MUTED, k));
				continue;
			}
			ServerData s = servers.get(i);
			int iy = y - (hover ? 2 : 0);
			Draw.round(g, c[0], iy, c[2], c[3], 7, Theme.alpha(Theme.PANEL_SOLID, 0.78 * k));
			Draw.roundOutline(g, c[0], iy, c[2], c[3], 7, Theme.alpha(0xFFFFFF, (hover ? 0.16 : 0.07) * k));
			Draw.round(g, c[0] + 7, iy + 7, 16, 16, 4, Theme.alpha(Theme.chroma(-i * 0.12), k));
			String initial = s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase();
			Draw.text(g, initial, c[0] + 15 - Draw.width(initial) / 2, iy + 11, Theme.alpha(0xFFFFFF, k));
			Draw.text(g, ellipsize(s.name, c[2] - 36), c[0] + 29, iy + 6, Theme.alpha(Theme.TEXT, k));
			Draw.scaledText(g, ellipsize(s.ip, (int) ((c[2] - 36) / 0.8f)), c[0] + 29, iy + 17, 0.8f, Theme.alpha(Theme.MUTED, k), false);
		}
	}

	private static String ellipsize(String s, int maxWidth) {
		if (Draw.width(s) <= maxWidth) return s;
		while (!s.isEmpty() && Draw.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	private void drawNotice(GuiGraphicsExtractor g, double t) {
		double k = Theme.ease((t - 1.0) / 0.6);
		if (k <= 0) return;
		// Bottom-right corner, level with the server strip.
		int y = height - PAD - 15;
		for (String line : NOTICE) {
			float w = Draw.width(line) * 0.7f;
			Draw.scaledText(g, line, width - PAD - w, y, 0.7f, Theme.alpha(Theme.DIM, k), false);
			y += 8;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		for (Button b : buttons) {
			if (b.hit(event.x(), event.y())) {
				b.action().run();
				return true;
			}
		}
		if (stripY() != height) {
			int count = cardCount();
			for (int i = 0; i <= count; i++) {
				int[] c = card(i);
				if (!Draw.inside(event.x(), event.y(), c[0], c[1], c[2], c[3])) continue;
				if (i == count) minecraft.gui.setScreen(new JoinMultiplayerScreen(this));
				else {
					ServerData s = servers.get(i);
					ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.ip), s, false, null);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
