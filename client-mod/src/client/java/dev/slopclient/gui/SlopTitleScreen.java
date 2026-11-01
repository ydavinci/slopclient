package dev.slopclient.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.InstanceIconTexture;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import dev.slopclient.ui.Icon;
import dev.slopclient.ui.Kit;
import dev.slopclient.ui.Kit.Hit;
import dev.slopclient.ui.Ui;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.nio.file.Files;
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
		private static final java.util.Set<String> HIDDEN_MODS = java.util.Set.of("minecraft", "java", "fabricloader", "mixinextras");
	private static final float CARD_W = 150, CARD_H = 40, BTN_H = Kit.BTN_H;
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
		Kit.tooltip(g, tooltip);
	}

	private static boolean over(float x, float y, float w, float h, int mx, int my) {
		return Kit.over(x, y, w, h, mx, my);
	}

	private String[] drawRail(GuiGraphicsExtractor g, int mx, int my) {
		List<Kit.Entry> entries = new ArrayList<>();
		for (Nav nav : Nav.values()) entries.add(new Kit.Entry(nav.label, nav.icon, nav == Nav.START, true, () -> open(nav)));
		return Kit.rail(g, "title", entries, new Kit.Entry("Beenden", Icon.POWER, false, true, minecraft::stop), height, mx, my, intro(0), hits);
	}

	/** Player pill top right, the legal notice under it. */
	private void drawTopRight(GuiGraphicsExtractor g) {
		float ny = Kit.playerPill(g, width, intro(0.2)) + 6;
		double kn = intro(1.0);
		String[] notice = {"Kein offizielles Minecraft-Produkt. Nicht von Mojang", "oder Microsoft genehmigt. Copyright Mojang AB."};
		for (String line : notice) {
			Ui.text(g, line, width - 12 - Ui.width(line, 6), ny, 6, Theme.alpha(Theme.DIM, kn));
			ny += 8;
		}
	}

	private float contentLeft() {
		return Kit.contentLeft();
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
			g.blit(RenderPipelines.GUI_TEXTURED, InstanceIconTexture.ID, Math.round(x), Math.round(y + (nameH - s) / 2 + (float) (1 - ki) * 8), 0, 0, s, s, icon, icon, icon, icon, Theme.alpha(0xFFFFFF, ki));
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
		float gap = 5, total = -gap;
		for (int i = 0; i < chips.length; i++) total += Kit.chipWidth(chips[i], i == 0) + gap;
		float x = rowX(total);
		y += (float) (1 - k) * 6;
		for (int i = 0; i < chips.length; i++) x += Kit.chip(g, chips[i], x, y, i == 0 ? Theme.ON : 0, k) + gap;
	}

	private void drawButtons(GuiGraphicsExtractor g, float y, int mx, int my) {
		String hint = playHint();
		float playW = Kit.primaryWidth("Spielen", hint);
		float sw = Kit.glassWidth("Einzelspieler"), mw = Kit.glassWidth("Mehrspieler");
		float x = rowX(playW + 8 + sw + 6 + mw);
		double k = intro(0.55);
		if (k <= 0) return;
		y += (float) (1 - k) * 8;
		Kit.primary(g, "title.play", "Spielen", hint, Icon.PLAY, x, y, playW, mx, my, k, hits, this::play);
		x += playW + 8;
		Kit.glass(g, "title.single", "Einzelspieler", Icon.EARTH, x, y, sw, false, mx, my, k, hits, () -> open(Nav.WORLDS));
		x += sw + 6;
		Kit.glass(g, "title.multi", "Mehrspieler", Icon.SERVER, x, y, mw, false, mx, my, k, hits, () -> open(Nav.SERVERS));
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

	static String ago(long millis) {
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
