package dev.slopclient.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.InstanceIconTexture;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import dev.slopclient.ui.Icon;
import dev.slopclient.ui.Ui;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * The title screen as the launcher: a liquid-glass sidebar on the left (Start, Welten,
 * Server, Screenshots, Module, Einstellungen, Beenden) and the start page on the right
 * with the instance name, status chips, "Spielen" (continues the world or server played
 * last) and cards of the recently played worlds and saved servers.
 *
 * <p>Everything is drawn through {@link Ui} in physical pixels, so corners, hairlines
 * and Sora text look like the launcher at every GUI scale. Sidebar entries without an
 * own page yet open the matching vanilla screen.
 */
public final class SlopTitleScreen extends Screen {
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SlopClient.ID, "textures/gui/logo.png");
	private static final java.util.Set<String> HIDDEN_MODS = java.util.Set.of("minecraft", "java", "fabricloader", "mixinextras");
	private static final float RAIL_X = 10, RAIL_W = 40, ITEM = 28, CARD_W = 150, CARD_H = 40, BTN_H = 34;
	/** Centred layout, chosen in the launcher (Einstellungen → Design). */
	private static final boolean CENTERED = "center".equals(System.getProperty("slopclient.layout"));

	/** Sidebar entries. */
	private enum Nav {
		START("Start", Icon.HOUSE), WORLDS("Welten", Icon.EARTH), SERVERS("Server", Icon.SERVER),
		SHOTS("Screenshots", Icon.IMAGES), MODULES("Module", Icon.LAYOUT_GRID), SETTINGS("Einstellungen", Icon.SETTINGS);

		final String label, icon;

		Nav(String label, String icon) {
			this.label = label;
			this.icon = icon;
		}
	}

	/** Something clickable laid out this frame. */
	private record Hit(float x, float y, float w, float h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** A card in the "Zuletzt gespielt" strip: a world or a saved server. */
	private record Card(String name, String sub, LevelSummary world, ServerData server) {
	}

	private final long openedAt = System.currentTimeMillis();
	private final String instanceName = System.getProperty("slopclient.instance", "SlopClient");
	private final int modCount;
	private final List<ServerData> servers = new ArrayList<>();
	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private final Map<ServerData, byte[]> uploaded = new HashMap<>();
	private ServerList serverList;
	private List<LevelSummary> worlds = List.of();
	private List<Hit> hits = new ArrayList<>();
	private boolean closed;

	public SlopTitleScreen() {
		super(Component.literal("SlopClient"));
		int count = 0;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (mod.getContainingMod().isEmpty() && !HIDDEN_MODS.contains(mod.getMetadata().getId())) count++;
		}
		modCount = count;
	}

	// ---------- data ----------

	@Override
	protected void init() {
		closed = false;
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
				// Unreachable servers keep their stored icon.
			}
		}
		loadWorlds();
	}

	/** Reads the world list in the background; the four played last become cards. */
	private void loadWorlds() {
		try {
			var source = minecraft.getLevelSource();
			source.loadLevelSummaries(source.findLevelCandidates()).thenAccept(list -> minecraft.execute(() -> {
				if (closed) return;
				worlds = list.stream().filter(w -> !w.isDisabled())
					.sorted((a, b) -> Long.compare(b.getLastPlayed(), a.getLastPlayed())).limit(4).toList();
				for (LevelSummary w : worlds) uploadWorldIcon(w);
			}));
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not list the worlds for the title screen", e);
		}
	}

	private void uploadWorldIcon(LevelSummary w) {
		var path = w.getIcon();
		if (path == null || !Files.isRegularFile(path)) return;
		try (InputStream in = Files.newInputStream(path)) {
			FaviconTexture tex = FaviconTexture.forWorld(minecraft.getTextureManager(), w.getLevelId());
			tex.upload(NativeImage.read(in));
			FaviconTexture old = icons.put("world:" + w.getLevelId(), tex);
			if (old != null && old != tex) old.close();
		} catch (Exception e) {
			// No icon then; the card shows a tile.
		}
	}

	@Override
	public void tick() {
		pinger.tick();
	}

	@Override
	public void removed() {
		closed = true;
		pinger.removeAll();
		icons.values().forEach(FaviconTexture::close);
		icons.clear();
		uploaded.clear();
	}

	/** The server's favicon texture, or null while it has none. */
	private Identifier serverIcon(ServerData s) {
		byte[] bytes = s.getIconBytes();
		if (bytes == null) return null;
		FaviconTexture tex = icons.computeIfAbsent("server:" + s.ip, k -> FaviconTexture.forServer(minecraft.getTextureManager(), s.ip));
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

	private List<Card> cards() {
		List<Card> out = new ArrayList<>();
		for (LevelSummary w : worlds) out.add(new Card(w.getLevelName(), "Welt · " + ago(w.getLastPlayed()), w, null));
		for (ServerData s : servers) {
			String sub = s.ping > 0 && s.ping < 100_000 ? "Server · " + s.ping + " ms" : "Server · " + s.ip;
			out.add(new Card(s.name, sub, null, s));
		}
		return out;
	}

	// ---------- actions ----------

	/** Continues what was played last; without anything known it opens the world list. */
	private void play() {
		String[] last = SlopClient.config().lastPlayed;
		if (last != null && last[0].equals("world") && minecraft.getLevelSource().levelExists(last[1])) {
			openWorld(last[1]);
			return;
		}
		if (last != null && last[0].equals("server")) {
			ServerData data = servers.stream().filter(s -> s.ip.equals(last[1])).findFirst()
				.orElseGet(() -> new ServerData(last[2], last[1], ServerData.Type.OTHER));
			join(data);
			return;
		}
		minecraft.gui.setScreen(new SelectWorldScreen(this));
	}

	private String playHint() {
		String[] last = SlopClient.config().lastPlayed;
		if (last == null) return "Welt auswählen";
		if (last[0].equals("world") && !minecraft.getLevelSource().levelExists(last[1])) return "Welt auswählen";
		return last[2].isBlank() ? last[1] : last[2];
	}

	private void openWorld(String levelId) {
		minecraft.createWorldOpenFlows().openWorld(levelId, () -> minecraft.gui.setScreen(this));
	}

	private void join(ServerData s) {
		ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.ip), s, false, null);
	}

	private void open(Nav nav) {
		switch (nav) {
			case START -> {
			}
			case WORLDS -> minecraft.gui.setScreen(new SelectWorldScreen(this));
			case SERVERS -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this));
			case SHOTS -> minecraft.gui.setScreen(new ScreenshotGalleryScreen(this));
			case MODULES -> minecraft.gui.setScreen(new ClickGuiScreen(this));
			case SETTINGS -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options));
		}
	}

	// ---------- drawing ----------

	private double seconds() {
		return (System.currentTimeMillis() - openedAt) / 1000.0;
	}

	/** Intro progress of an element that starts `delay` seconds in. */
	private double intro(double delay) {
		return Theme.ease((seconds() - delay) / 0.6);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		MenuShader.draw(g, Theme.ease(seconds() / 0.8));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		hits = new ArrayList<>();
		String[] tooltip = drawRail(g, mouseX, mouseY);
		drawTopRight(g);
		drawStart(g, mouseX, mouseY);
		if (tooltip != null) drawTooltip(g, tooltip[0], Float.parseFloat(tooltip[1]));
	}

	private static boolean over(float x, float y, float w, float h, int mx, int my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** The sidebar; returns {label, centre y} of a hovered entry for its tooltip. */
	private String[] drawRail(GuiGraphicsExtractor g, int mx, int my) {
		double k = intro(0);
		float x = RAIL_X - (float) (1 - k) * 12, y = 10, h = height - 20;
		Ui.glass(g, x, y, RAIL_W, h, 14, k);
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO, Math.round(x + 11), Math.round(y + 10), 0, 0, 18, 18, 18, 18, Theme.alpha(0xFFFFFF, k));
		Ui.rect(g, x + 10, y + 35, RAIL_W - 20, Ui.hairline(g), 0, Theme.alpha(0xFFFFFF, 0.1 * k));

		String[] tooltip = null;
		float ix = x + (RAIL_W - ITEM) / 2, iy = y + 42;
		for (Nav nav : Nav.values()) {
			boolean hover = over(ix, iy, ITEM, ITEM, mx, my);
			boolean active = nav == Nav.START;
			double hv = Ui.anim("title.nav." + nav, hover);
			if (active) {
				Ui.rect(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(Theme.ACCENT, 0.24 * k));
				Ui.outline(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(Theme.ACCENT_2, 0.32 * k));
				Ui.chroma(g, x + 1.5f, iy + 8, 2.5f, ITEM - 16, 1.25f, k, 0);
			} else if (hv > 0) {
				Ui.rect(g, ix, iy, ITEM, ITEM, 9, Theme.alpha(0xFFFFFF, 0.08 * hv * k));
			}
			int color = active ? 0xFFFFFFFF : Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
			Ui.icon(g, nav.icon, ix + 7, iy + 7, 14, Theme.alpha(color, k));
			hits.add(new Hit(ix, iy, ITEM, ITEM, () -> open(nav)));
			if (hover) tooltip = new String[] {nav.label, Float.toString(iy + ITEM / 2)};
			iy += ITEM + 6;
		}

		// Quit at the bottom.
		float qy = y + h - ITEM - 6;
		boolean hover = over(ix, qy, ITEM, ITEM, mx, my);
		double hv = Ui.anim("title.nav.quit", hover);
		if (hv > 0) Ui.rect(g, ix, qy, ITEM, ITEM, 9, Theme.alpha(Theme.ERR, 0.16 * hv * k));
		Ui.icon(g, Icon.POWER, ix + 7, qy + 7, 14, Theme.alpha(Theme.lerp(Theme.MUTED, Theme.ERR, hv), k));
		hits.add(new Hit(ix, qy, ITEM, ITEM, minecraft::stop));
		if (hover) tooltip = new String[] {"Beenden", Float.toString(qy + ITEM / 2)};
		return tooltip;
	}

	private void drawTooltip(GuiGraphicsExtractor g, String label, float centreY) {
		float size = 8, w = Ui.width(label, size) + 16, h = 18;
		float x = RAIL_X + RAIL_W + 8, y = centreY - h / 2;
		Ui.shadow(g, x, y, w, h, 7, 8, 3, 0x66000000);
		Ui.rect(g, x, y, w, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.96));
		Ui.outline(g, x, y, w, h, 7, Theme.LINE_2);
		Ui.text(g, label, x + 8, y + (h - size * 1.25f) / 2, size, Theme.TEXT);
	}

	/** Player and clock in a glass pill, top right; the legal notice under it. */
	private void drawTopRight(GuiGraphicsExtractor g) {
		double k = intro(0.2);
		if (k <= 0) return;
		String name = minecraft.getUser().getName(), time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
		float size = 8, h = 22;
		float w = 10 + 17 + Ui.width(name, size) + 13 + Ui.width(time, size) + 10;
		float x = width - 12 - w, y = 10;
		Ui.rect(g, x, y, w, h, 11, Theme.alpha(Theme.PANEL_SOLID, 0.55 * k));
		Ui.outline(g, x, y, w, h, 11, Theme.alpha(0xFFFFFF, 0.09 * k));
		float cx = x + 10;
		Ui.icon(g, Icon.USER, cx, y + 5, 12, Theme.alpha(Theme.ACCENT_2, k));
		cx += 17;
		cx += Ui.text(g, name, cx, y + (h - size * 1.25f) / 2, size, Theme.alpha(Theme.TEXT, k));
		Ui.rect(g, cx + 6, y + 7, Ui.hairline(g), h - 14, 0, Theme.alpha(0xFFFFFF, 0.14 * k));
		Ui.text(g, time, cx + 13, y + (h - size * 1.25f) / 2, size, Theme.alpha(Theme.MUTED, k));

		double kn = intro(1.0);
		String[] notice = {"Kein offizielles Minecraft-Produkt. Nicht von Mojang", "oder Microsoft genehmigt. Copyright Mojang AB."};
		float ny = y + h + 6;
		for (String line : notice) {
			Ui.text(g, line, width - 12 - Ui.width(line, 6), ny, 6, Theme.alpha(Theme.DIM, kn));
			ny += 8;
		}
	}

	private float contentLeft() {
		return RAIL_X + RAIL_W + 24;
	}

	/** Left edge of a row of the given width: the content margin, or centred in the content area. */
	private float rowX(float rowWidth) {
		float left = contentLeft();
		return CENTERED ? left + (width - 16 - left - rowWidth) / 2 : left;
	}

	private void drawStart(GuiGraphicsExtractor g, int mx, int my) {
		float left = contentLeft(), right = width - 16, bottom = height - 14;
		List<Card> cards = cards();
		int fit = (int) Math.max(0, Math.min(cards.size(), (right - left + 8) / (CARD_W + 8)));
		boolean strip = fit > 0 && height >= 230;

		float y = bottom;
		if (strip) {
			y -= CARD_H;
			drawCards(g, cards.subList(0, fit), y, mx, my);
			y -= 26;
		}
		y -= BTN_H;
		drawButtons(g, y, mx, my);
		y -= 12 + 16;
		drawChips(g, y);
		drawHero(g, y - 10, right - left);
	}

	/** Greeting, instance icon and the big name, letter by letter, ending at `bottom`. */
	private void drawHero(GuiGraphicsExtractor g, float bottom, float room) {
		float size = Math.max(20, Math.min(height < 300 ? 30 : 46, room * 0.085f));
		float nameH = size * 1.25f;
		int icon = InstanceIconTexture.size();
		float iconSize = icon > 0 ? size * 1.05f : 0;
		float nameW = Ui.boldWidth(instanceName, size) + (icon > 0 ? iconSize + size * 0.3f : 0);
		float x = rowX(nameW), y = bottom - nameH;

		double kg = intro(0.1);
		String greeting = "Willkommen zurück, " + minecraft.getUser().getName();
		Ui.text(g, greeting, rowX(Ui.width(greeting, 9)), y - 14 + (float) (1 - kg) * 6, 9, Theme.alpha(Theme.MUTED, kg));

		if (icon > 0) {
			double ki = intro(0.15);
			int s = Math.round(iconSize);
			g.blit(RenderPipelines.GUI_TEXTURED, InstanceIconTexture.ID, Math.round(x), Math.round(y + (nameH - s) / 2 + (1 - ki) * 8), 0, 0, s, s, icon, icon, icon, icon, Theme.alpha(0xFFFFFF, ki));
			x += iconSize + size * 0.3f;
		}
		for (int i = 0; i < instanceName.length(); i++) {
			String ch = String.valueOf(instanceName.charAt(i));
			double k = Theme.ease((seconds() - 0.2 - i * 0.04) / 0.7);
			if (k > 0) Ui.bold(g, ch, x, y + (float) ((1 - k) * 10), size, Theme.alpha(Theme.chroma(-i * 0.07), k));
			x += Ui.boldWidth(ch, size);
		}
	}

	private void drawChips(GuiGraphicsExtractor g, float y) {
		double k = intro(0.45);
		if (k <= 0) return;
		String loader = FabricLoader.getInstance().getModContainer("fabricloader")
			.map(c -> "Fabric " + c.getMetadata().getVersion().getFriendlyString()).orElse("Fabric");
		String[] chips = {"Bereit", "Minecraft " + SharedConstants.getCurrentVersion().name(), loader, modCount + " Mods"};
		float size = 7.5f, gap = 5, total = 0;
		for (int i = 0; i < chips.length; i++) total += chipWidth(chips[i], size, i == 0) + gap;
		float x = rowX(total - gap);
		y += (float) (1 - k) * 6;
		for (int i = 0; i < chips.length; i++) {
			float w = chipWidth(chips[i], size, i == 0);
			Ui.rect(g, x, y, w, 16, 8, Theme.alpha(0xFFFFFF, 0.06 * k));
			Ui.outline(g, x, y, w, 16, 8, Theme.alpha(0xFFFFFF, 0.12 * k));
			float tx = x + 8;
			if (i == 0) {
				Ui.rect(g, tx, y + 6, 4, 4, 2, Theme.alpha(Theme.ON, k));
				tx += 8;
			}
			Ui.text(g, chips[i], tx, y + (16 - size * 1.25f) / 2, size, Theme.alpha(i == 0 ? Theme.TEXT : Theme.MUTED, k));
			x += w + gap;
		}
	}

	private static float chipWidth(String text, float size, boolean dot) {
		return Ui.width(text, size) + 16 + (dot ? 8 : 0);
	}

	private void drawButtons(GuiGraphicsExtractor g, float y, int mx, int my) {
		String hint = playHint();
		float playW = Math.min(190, Math.max(118, Math.max(Ui.boldWidth("Spielen", 10), Ui.width(hint, 7)) + 46));
		float sw = Ui.width("Einzelspieler", 9) + 42, mw = Ui.width("Mehrspieler", 9) + 42;
		float x = rowX(playW + 8 + sw + 6 + mw);

		double k = intro(0.55);
		if (k <= 0) return;
		y += (float) (1 - k) * 8;
		// The launcher's play button: chroma fill, glow and a ring just outside it.
		boolean hover = over(x, y, playW, BTN_H, mx, my);
		double hv = Ui.anim("title.play", hover);
		Ui.shadow(g, x, y, playW, BTN_H, 12, 14, 4, Theme.alpha(Theme.ACCENT, (0.45 + 0.25 * hv) * k));
		Ui.outline(g, x - 3, y - 3, playW + 6, BTN_H + 6, 15, Theme.alpha(Theme.ACCENT_2, (0.45 + 0.4 * hv) * k));
		Ui.chroma(g, x, y, playW, BTN_H, 12, k, 0.1 * hv);
		Ui.icon(g, Icon.PLAY, x + 12, y + (BTN_H - 14) / 2, 14, Theme.alpha(0xFFFFFF, k));
		Ui.bold(g, "Spielen", x + 32, y + 5, 10, Theme.alpha(0xFFFFFF, k));
		Ui.text(g, Ui.fit(hint, 7, playW - 42), x + 32, y + 19, 7, Theme.alpha(0xFFFFFF, 0.78 * k));
		hits.add(new Hit(x, y, playW, BTN_H, this::play));
		x += playW + 8;

		x += glassButton(g, "Einzelspieler", Icon.EARTH, x, y, sw, mx, my, k, () -> open(Nav.WORLDS)) + 6;
		glassButton(g, "Mehrspieler", Icon.SERVER, x, y, mw, mx, my, k, () -> open(Nav.SERVERS));
	}

	private float glassButton(GuiGraphicsExtractor g, String label, String icon, float x, float y, float w, int mx, int my, double k, Runnable action) {
		boolean hover = over(x, y, w, BTN_H, mx, my);
		double hv = Ui.anim("title.btn." + label, hover);
		Ui.rect(g, x, y, w, BTN_H, 12, Theme.alpha(Theme.RAISED, (0.68 + 0.27 * hv) * k));
		Ui.outline(g, x, y, w, BTN_H, 12, Theme.alpha(0xFFFFFF, (0.08 + 0.1 * hv) * k));
		Ui.icon(g, icon, x + 12, y + (BTN_H - 13) / 2, 13, Theme.alpha(Theme.lerp(Theme.MUTED, Theme.TEXT, hv), k));
		Ui.text(g, label, x + 30, y + (BTN_H - 9 * 1.25f) / 2, 9, Theme.alpha(Theme.TEXT, k));
		hits.add(new Hit(x, y, w, BTN_H, action));
		return w;
	}

	private void drawCards(GuiGraphicsExtractor g, List<Card> cards, float y, int mx, int my) {
		float total = cards.size() * (CARD_W + 8) - 8;
		float x = rowX(total);
		Ui.text(g, "Zuletzt gespielt", x, y - 15, 8, Theme.alpha(Theme.DIM, intro(0.7)));
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			double k = intro(0.75 + i * 0.05);
			if (k > 0) {
				boolean hover = over(x, y, CARD_W, CARD_H, mx, my);
				double hv = Ui.anim("title.card." + i, hover);
				float cy = y + (float) ((1 - k) * 8 - 2 * hv);
				Ui.shadow(g, x, cy, CARD_W, CARD_H, 11, 10, 4, Theme.alpha(0xFF000000, 0.3 * k));
				Ui.rect(g, x, cy, CARD_W, CARD_H, 11, Theme.alpha(Theme.PANEL_SOLID, (0.6 + 0.15 * hv) * k));
				Ui.outline(g, x, cy, CARD_W, CARD_H, 11, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, hv), (0.08 + 0.32 * hv) * k));

				Identifier icon = c.world() != null ? textureOf("world:" + c.world().getLevelId()) : serverIcon(c.server());
				float ix = x + 8, iy = cy + 8;
				if (icon != null) {
					g.blit(RenderPipelines.GUI_TEXTURED, icon, Math.round(ix), Math.round(iy), 0, 0, 24, 24, 64, 64, 64, 64, Theme.alpha(0xFFFFFF, k));
				} else {
					Ui.rect(g, ix, iy, 24, 24, 7, Theme.alpha(Theme.chroma(-i * 0.12), 0.85 * k));
					Ui.icon(g, c.world() != null ? Icon.EARTH : Icon.SERVER, ix + 5, iy + 5, 14, Theme.alpha(0xFFFFFF, k));
				}
				Ui.text(g, Ui.fit(c.name(), 8.5f, CARD_W - 48), x + 40, cy + 8, 8.5f, Theme.alpha(Theme.TEXT, k));
				Ui.text(g, Ui.fit(c.sub(), 7, CARD_W - 48), x + 40, cy + 22, 7, Theme.alpha(Theme.MUTED, k));
				Runnable action = c.world() != null ? () -> openWorld(c.world().getLevelId()) : () -> join(c.server());
				hits.add(new Hit(x, y, CARD_W, CARD_H, action));
			}
			x += CARD_W + 8;
		}
	}

	private Identifier textureOf(String key) {
		FaviconTexture tex = icons.get(key);
		return tex == null ? null : tex.textureLocation();
	}

	private static String ago(long millis) {
		if (millis <= 0) return "nie gespielt";
		long s = (System.currentTimeMillis() - millis) / 1000;
		if (s < 90) return "gerade eben";
		if (s < 3600) return "vor " + Math.round(s / 60.0) + " min";
		if (s < 86400) return "vor " + Math.round(s / 3600.0) + " h";
		long days = Math.round(s / 86400.0);
		return days == 1 ? "gestern" : "vor " + days + " Tagen";
	}

	// ---------- input ----------

	/** The SlopClient menu key (Right Shift by default) also works on the title screen. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SlopClient.clickGuiKey().matches(event)) {
			minecraft.gui.setScreen(new ClickGuiScreen(this));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Minecraft 26.3 with the SDL3 backend sends 1 for a left click, 0 with legacy GLFW.
		if (event.button() != 0 && event.button() != 1) return super.mouseClicked(event, doubleClick);
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(event.x(), event.y())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
