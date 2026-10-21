package dev.slopclient.gui;

import dev.slopclient.SlopClient;
import dev.slopclient.render.Draw;
import dev.slopclient.render.InstanceIconTexture;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
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
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.gui.screens.FaviconTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.server.network.EventLoopGroupHolder;
import java.net.UnknownHostException;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * The launcher's start page as the title screen: flowing shader backdrop,
 * active-mod ArrayList, a big instance name with status chips, the chroma-ring
 * play button and a strip of saved servers with their icons.
 */
public final class SlopTitleScreen extends Screen {
	private static final int PAD = 14, CARD_W = 124, CARD_H = 30, BTN_H = 24;
	private static final Set<String> HIDDEN_MODS = Set.of("minecraft", "java", "fabricloader", "mixinextras");

	private record Button(String label, int x, int y, int w, boolean primary, Runnable action) {
		boolean hit(double mx, double my) {
			return Draw.inside(mx, my, x, y, w, BTN_H);
		}
	}

	private final long openedAt = System.currentTimeMillis();
	private final String instanceName = System.getProperty("slopclient.instance", "SlopClient");
	private final List<String[]> mods = new ArrayList<>();
	private final List<ServerData> servers = new ArrayList<>();
	/** Pings the saved servers so their icons are current (like the server list does). */
	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final java.util.Map<ServerData, FaviconTexture> icons = new java.util.HashMap<>();
	private final java.util.Map<ServerData, byte[]> uploaded = new java.util.HashMap<>();
	private ServerList serverList;
	private List<Button> buttons = List.of();

	public SlopTitleScreen() {
		super(Component.literal("SlopClient"));
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
		pinger.removeAll();
		serverList = new ServerList(minecraft);
		serverList.load();
		for (int i = 0; i < serverList.size(); i++) servers.add(serverList.get(i));
		for (ServerData s : servers) {
			try {
				// A changed icon is written back to servers.dat, as vanilla does.
				pinger.pingServer(s, () -> minecraft.execute(serverList::save), () -> {}, EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
			} catch (UnknownHostException | RuntimeException e) {
				// Unreachable servers just keep their stored icon.
			}
		}
		layout();
	}

	@Override
	public void tick() {
		pinger.tick();
	}

	@Override
	public void removed() {
		pinger.removeAll();
		icons.values().forEach(FaviconTexture::close);
		icons.clear();
		uploaded.clear();
	}

	/** The server's favicon texture, or null while it has none. */
	private Identifier icon(ServerData s) {
		byte[] bytes = s.getIconBytes();
		if (bytes == null) return null;
		FaviconTexture tex = icons.computeIfAbsent(s, k -> FaviconTexture.forServer(minecraft.getTextureManager(), k.ip));
		if (uploaded.get(s) != bytes) {
			uploaded.put(s, bytes);
			try {
				tex.upload(NativeImage.read(bytes));
			} catch (Exception e) {
				tex.clear();
				return null;
			}
		}
		return tex.textureLocation();
	}

	/** The SlopClient menu key (Right Shift by default) also works on the title screen. */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (dev.slopclient.SlopClient.clickGuiKey().matches(event)) {
			minecraft.gui.setScreen(new ClickGuiScreen(this));
			return true;
		}
		return super.keyPressed(event);
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

	/** Centred layout, chosen in the launcher (Einstellungen → Design). */
	private static final boolean CENTERED = "center".equals(System.getProperty("slopclient.layout"));

	/** Left edge of a row of the given width: the margin, or centred. */
	private int rowX(int rowWidth) {
		return CENTERED ? (width - rowWidth) / 2 : PAD + 2;
	}

	private void layout() {
		String[] labels = {"Einzelspieler", "Mehrspieler", "SlopClient-Menü", "Optionen"};
		int total = Draw.width("Beenden") + 18;
		for (int i = 0; i < labels.length; i++) total += Draw.width(labels[i]) + (i == 0 ? 40 : 22) + 6;
		int y = playY(), x = rowX(total);
		List<Button> list = new ArrayList<>();
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
		drawHero(g, t);
		drawButtons(g, mouseX, mouseY, t);
		drawServers(g, mouseX, mouseY, t);
		drawNotice(g, t);
	}

	/** Big name revealed letter by letter, then the status chips. */
	private void drawHero(GuiGraphicsExtractor g, double t) {
		float scale = nameScale();
		int icon = InstanceIconTexture.size();
		int iconSize = icon > 0 ? Math.round(8 * scale + 4) + 10 : 0;
		float x = rowX(Math.round(iconSize + Draw.displayWidth(instanceName) * scale));
		float y = chipsY() - 8 - 8 * scale;
		// The instance icon from the launcher sits in front of the name.
		if (icon > 0) {
			int s = Math.round(8 * scale + 4);
			double ki = Theme.ease((t - 0.1) / 0.6);
			if (ki > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, InstanceIconTexture.ID, (int) x, (int) (y - 3 + (1 - ki) * 8), 0, 0, s, s, icon, icon, icon, icon, Theme.alpha(0xFFFFFF, ki));
			}
			x += s + 10;
		}
		for (int i = 0; i < instanceName.length(); i++) {
			String ch = String.valueOf(instanceName.charAt(i));
			double k = Theme.ease((t - 0.15 - i * 0.045) / 0.7);
			if (k > 0) Draw.displayText(g, ch, x, y + (float) ((1 - k) * 10), scale, Theme.alpha(Theme.chroma(-i * 0.07), k));
			x += Draw.displayWidth(ch) * scale;
		}

		double k = Theme.ease((t - 0.55) / 0.5);
		if (k <= 0) return;
		int cy = chipsY() + (int) Math.round((1 - k) * 6);
		String loader = FabricLoader.getInstance().getModContainer("fabricloader")
			.map(c -> "Fabric " + c.getMetadata().getVersion().getFriendlyString()).orElse("Fabric");
		String version = "Minecraft " + SharedConstants.getCurrentVersion().name(), count = mods.size() + " Mods";
		String hint = "Rechts-Shift öffnet das SlopClient-Menü.";
		int chips = Draw.chipWidth("Bereit", true) + Draw.chipWidth(version, false) + Draw.chipWidth(loader, false) + Draw.chipWidth(count, false) + 12;
		boolean showHint = chips + 8 + Draw.width(hint) < width - 2 * PAD;
		int cx = rowX(chips + (showHint ? 8 + Draw.width(hint) : 0));
		cx += Draw.chip(g, cx, cy, "Bereit", Theme.ON) + 4;
		cx += Draw.chip(g, cx, cy, version, 0) + 4;
		cx += Draw.chip(g, cx, cy, loader, 0) + 4;
		cx += Draw.chip(g, cx, cy, count, 0) + 8;
		if (showHint) Draw.text(g, hint, cx, cy + 3, Theme.alpha(Theme.MUTED, k));
	}

	private void drawButtons(GuiGraphicsExtractor g, int mx, int my, double t) {
		for (int i = 0; i < buttons.size(); i++) {
			Button b = buttons.get(i);
			double k = Theme.ease((t - 0.65 - i * 0.05) / 0.5);
			if (k <= 0) continue;
			boolean hover = b.hit(mx, my);
			int y = b.y();
			if (b.primary()) {
				// The launcher's play button: chroma fill, glow, and a ring just outside it.
				Draw.glow(g, b.x(), y, b.w(), BTN_H, 7, Theme.ACCENT, (hover ? 0.6 : 0.4) * k);
				Draw.roundOutline(g, b.x() - 3, y - 3, b.w() + 6, BTN_H + 6, 10, Theme.alpha(Theme.ACCENT_2, (hover ? 0.85 : 0.55) * k));
				Draw.chromaFill(g, b.x(), y, b.w(), BTN_H, 8, k, hover ? 0.12 : 0);
				int tx = b.x() + 13, ty = y + BTN_H / 2;
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
		// Centred, the notice sits in the top-left corner and the cards get the full width.
		int room = width - 2 * PAD - (CENTERED ? 0 : noticeWidth() + 12) - CARD_W;
		return Math.min(servers.size(), Math.max(0, room / (CARD_W + 6)));
	}

	private int[] card(int i) {
		int total = (cardCount() + 1) * (CARD_W + 6) - 6;
		return new int[] {rowX(total) + i * (CARD_W + 6), stripY(), CARD_W, CARD_H};
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
			Identifier favicon = icon(s);
			if (favicon != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, favicon, c[0] + 7, iy + 7, 0, 0, 16, 16, 64, 64, 64, 64, Theme.alpha(0xFFFFFF, k));
			} else {
				// No icon (yet): the initial on a chroma tile.
				Draw.round(g, c[0] + 7, iy + 7, 16, 16, 4, Theme.alpha(Theme.chroma(-i * 0.12), k));
				String initial = s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase();
				Draw.text(g, initial, c[0] + 15 - Draw.width(initial) / 2, iy + 11, Theme.alpha(0xFFFFFF, k));
			}
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
		// Bottom-right corner, level with the server strip; centred, the strip needs the
		// whole width, so the notice moves to the free top-left corner.
		int y = CENTERED ? PAD : height - PAD - 15;
		for (String line : NOTICE) {
			float w = Draw.width(line) * 0.7f;
			Draw.scaledText(g, line, CENTERED ? PAD : width - PAD - w, y, 0.7f, Theme.alpha(Theme.DIM, k), false);
			y += 8;
		}
	}

	@Override
	public void repositionElements() {
		layout();
		super.repositionElements();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Minecraft 26.3 with SDL3 backend sends button 1 for left click, 0 for legacy GLFW.
		if (event.button() != 0 && event.button() != 1) return super.mouseClicked(event, doubleClick);
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
