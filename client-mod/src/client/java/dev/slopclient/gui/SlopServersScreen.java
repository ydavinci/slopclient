package dev.slopclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import dev.slopclient.ui.Icon;
import dev.slopclient.ui.Kit;
import dev.slopclient.ui.Kit.Hit;
import dev.slopclient.ui.Ui;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * "Mehrspieler" as a launcher page ("Modern" in the Mehrspieler setting): the glass
 * sidebar with "Server" active, the saved servers as compact rows (icon, name, MOTD in
 * its colours, players, ping as bars and ms, version conflicts in red), LAN worlds as an
 * own section, search, reordering by dragging and an action bar for the selected server:
 * Beitreten, Bearbeiten, Adresse kopieren, Löschen (click twice).
 *
 * <p>Adding, editing and "Direkt verbinden" open a glass dialog on the page (name,
 * address, resource packs Fragen/An/Aus). Joining, pinging, the LAN detection and
 * saving to servers.dat use vanilla's classes the same way {@link JoinMultiplayerScreen}
 * does, so a direct connection is stored as a hidden server and its icon cached.
 */
public final class SlopServersScreen extends Screen {
	private static final float ROW_H = 30, ROW_GAP = 3, BAR_H = 26, TOOL_H = 22, ICON_RADIUS = 6, SECTION_H = 18;
	/** Pings in the background like vanilla's "Server Pinger" pool (DNS lookups block). */
	private static final ExecutorService PINGS = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "SlopClient server pinger");
		t.setDaemon(true);
		return t;
	});

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private final Map<String, byte[]> uploaded = new HashMap<>();
	private ServerList servers;
	private final LanServerDetection.LanServerList lanList = new LanServerDetection.LanServerList();
	private LanServerDetection.LanServerDetector lanDetector;
	private List<LanServer> lan = List.of();
	private boolean closed;

	/** Selected row: a saved server (by object) or a LAN world (by address). */
	private ServerData selected;
	private String selectedLan;
	private ServerData deleteArmed;
	private long deleteArmedAt;
	private double scroll, scrollTarget;
	private EditBox search;
	private List<Hit> hits = new ArrayList<>();

	// Dragging a saved server to a new place.
	private ServerData dragging;
	private double dragStartY, dragY;
	private boolean dragMoved;

	private Dialog dialog;

	public SlopServersScreen(Screen parent) {
		super(Component.literal("Server"));
		this.parent = parent;
	}

	// ---------- data ----------

	@Override
	protected void init() {
		closed = false;
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 0, 0, 100, 12, Component.literal("Suchen"));
		search.setBordered(false);
		search.setMaxLength(64);
		search.setTextColor(Theme.TEXT);
		search.setValue(query);
		search.setResponder(q -> scrollTarget = 0);
		addWidget(search);
		if (dialog != null) {
			dialog.attach();
		} else {
			setInitialFocus(search);
		}
		if (servers == null) {
			servers = new ServerList(minecraft);
			servers.load();
		}
		if (lanDetector == null) {
			try {
				lanDetector = new LanServerDetection.LanServerDetector(lanList);
				lanDetector.start();
			} catch (Exception e) {
				SlopClient.LOG.warn("Unable to start LAN server detection", e);
			}
		}
	}

	@Override
	public void tick() {
		List<LanServer> dirty = lanList.takeDirtyServers();
		if (dirty != null) lan = List.copyOf(dirty);
		pinger.tick();
	}

	@Override
	public void removed() {
		closed = true;
		if (lanDetector != null) {
			lanDetector.interrupt();
			lanDetector = null;
		}
		pinger.removeAll();
		icons.values().forEach(FaviconTexture::close);
		icons.clear();
		uploaded.clear();
	}

	@Override
	public void onClose() {
		if (dialog != null) {
			closeDialog();
			return;
		}
		minecraft.gui.setScreen(parent);
	}

	private List<ServerData> saved() {
		List<ServerData> out = new ArrayList<>();
		for (int i = 0; i < servers.size(); i++) out.add(servers.get(i));
		return out;
	}

	private String query() {
		return search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
	}

	private List<ServerData> shownSaved() {
		String q = query();
		if (q.isEmpty()) return saved();
		return saved().stream().filter(s -> s.name.toLowerCase(Locale.ROOT).contains(q) || s.ip.toLowerCase(Locale.ROOT).contains(q)).toList();
	}

	private List<LanServer> shownLan() {
		String q = query();
		if (q.isEmpty()) return lan;
		return lan.stream().filter(s -> s.getMotd().toLowerCase(Locale.ROOT).contains(q) || s.getAddress().contains(q)).toList();
	}

	/** Starts the status ping once per server, like vanilla's list entries do. */
	private void ping(ServerData s) {
		if (s.state() != ServerData.State.INITIAL) return;
		s.setState(ServerData.State.PINGING);
		s.motd = Component.empty();
		s.status = Component.empty();
		PINGS.submit(() -> {
			try {
				pinger.pingServer(s, () -> minecraft.execute(servers::save), () -> s.setState(
					s.protocol == SharedConstants.getCurrentVersion().protocolVersion() ? ServerData.State.SUCCESSFUL : ServerData.State.INCOMPATIBLE),
					EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
			} catch (UnknownHostException e) {
				s.setState(ServerData.State.UNREACHABLE);
				s.motd = Component.literal("Adresse nicht gefunden");
			} catch (Exception e) {
				s.setState(ServerData.State.UNREACHABLE);
				s.motd = Component.literal("Keine Verbindung möglich");
			}
		});
	}

	/** The server's favicon, re-uploaded when its bytes change; null while it has none. */
	private net.minecraft.resources.Identifier icon(ServerData s) {
		byte[] bytes = s.getIconBytes();
		if (bytes == null) return null;
		FaviconTexture tex = icons.computeIfAbsent(s.ip, ip -> FaviconTexture.forServer(minecraft.getTextureManager(), ip));
		if (!Arrays.equals(uploaded.get(s.ip), bytes)) {
			uploaded.put(s.ip, bytes);
			try {
				NativeImage image = NativeImage.read(bytes);
				SlopWorldsScreen.roundCorners(image, ICON_RADIUS / (ROW_H - 8));
				tex.upload(image);
			} catch (Exception e) {
				tex.clear();
				return null;
			}
		}
		return tex.textureLocation();
	}

	// ---------- actions ----------

	private void join(ServerData s) {
		if (s == null) return;
		ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.ip), s, false, null);
	}

	private void joinLan(LanServer s) {
		if (s != null) join(new ServerData(s.getMotd(), s.getAddress(), ServerData.Type.LAN));
	}

	private void joinSelected() {
		if (selected != null) join(selected);
		else if (selectedLan != null) lan.stream().filter(s -> s.getAddress().equals(selectedLan)).findFirst().ifPresent(this::joinLan);
	}

	private void copyAddress() {
		String ip = selected != null ? selected.ip : selectedLan;
		if (ip == null) return;
		minecraft.keyboardHandler.setClipboard(ip);
		toast = "Adresse kopiert";
		toastAt = System.currentTimeMillis();
	}

	private String toast;
	private long toastAt;

	/** First click arms the button for three seconds, the second deletes. */
	private void delete() {
		ServerData s = selected;
		if (s == null) return;
		if (!armed(s)) {
			deleteArmed = s;
			deleteArmedAt = System.currentTimeMillis();
			return;
		}
		deleteArmed = null;
		servers.remove(s);
		servers.save();
		selected = null;
	}

	private boolean armed(ServerData s) {
		return s != null && s == deleteArmed && System.currentTimeMillis() - deleteArmedAt < 3000;
	}

	private void refresh() {
		for (ServerData s : saved()) s.setState(ServerData.State.INITIAL);
		pinger.removeAll();
	}

	private void switchToClassic() {
		SlopClient.modules().titleScreen.serversStyle.set("Klassisch");
		SlopClient.save();
		minecraft.gui.setScreen(new JoinMultiplayerScreen(parent));
	}

	// ---------- dialog (add, edit, direct connect) ----------

	private enum Mode { ADD, EDIT, DIRECT }

	private static final ServerData.ServerPackStatus[] PACKS = {ServerData.ServerPackStatus.PROMPT, ServerData.ServerPackStatus.ENABLED, ServerData.ServerPackStatus.DISABLED};
	private static final String[] PACK_LABELS = {"Fragen", "An", "Aus"};

	private final class Dialog {
		final Mode mode;
		final ServerData target;
		final EditBox name, address;
		ServerData.ServerPackStatus packs;
		final long openedAt = System.currentTimeMillis();

		Dialog(Mode mode, ServerData target) {
			this.mode = mode;
			this.target = target;
			name = field(target == null ? "Minecraft-Server" : target.name, 64);
			String ip = mode == Mode.DIRECT ? Optional.ofNullable(minecraft.options.lastMpIp).orElse("") : target == null ? "" : target.ip;
			address = field(ip, 128);
			packs = target == null ? ServerData.ServerPackStatus.PROMPT : target.getResourcePackStatus();
		}

		private EditBox field(String value, int max) {
			EditBox box = new EditBox(font, 0, 0, 100, 12, Component.empty());
			box.setBordered(false);
			box.setMaxLength(max);
			box.setTextColor(Theme.TEXT);
			box.setValue(value);
			return box;
		}

		void attach() {
			if (mode != Mode.DIRECT) addWidget(name);
			addWidget(address);
			setFocused(mode == Mode.DIRECT || mode == Mode.EDIT ? address : name);
		}

		void detach() {
			removeWidget(name);
			removeWidget(address);
		}

		boolean valid() {
			return ServerAddress.isValidAddress(address.getValue()) && (mode == Mode.DIRECT || !name.getValue().isBlank());
		}

		void submit() {
			if (!valid()) return;
			String ip = address.getValue().trim();
			switch (mode) {
				case ADD -> {
					ServerData data = new ServerData(name.getValue().trim(), ip, ServerData.Type.OTHER);
					data.setResourcePackStatus(packs);
					ServerData hidden = servers.unhide(ip);
					if (hidden != null) {
						hidden.copyNameIconFrom(data);
						hidden.setResourcePackStatus(packs);
					} else {
						servers.add(data, false);
					}
					servers.save();
					selected = hidden != null ? hidden : data;
					closeDialog();
				}
				case EDIT -> {
					target.name = name.getValue().trim();
					target.ip = ip;
					target.setResourcePackStatus(packs);
					target.setState(ServerData.State.INITIAL);
					servers.save();
					closeDialog();
				}
				case DIRECT -> {
					minecraft.options.lastMpIp = ip;
					minecraft.options.save();
					ServerData known = servers.get(ip);
					if (known == null) {
						known = new ServerData("Minecraft-Server", ip, ServerData.Type.OTHER);
						servers.add(known, true);
						servers.save();
					}
					closeDialog();
					join(known);
				}
			}
		}
	}

	private void openDialog(Mode mode, ServerData target) {
		if (dialog != null) dialog.detach();
		dialog = new Dialog(mode, target);
		dialog.attach();
	}

	private void closeDialog() {
		if (dialog == null) return;
		dialog.detach();
		dialog = null;
		setFocused(search);
	}

	// ---------- drawing ----------

	private double seconds() {
		return (System.currentTimeMillis() - openedAt) / 1000.0;
	}

	private double intro(double delay) {
		return Theme.ease((seconds() - delay) / 0.45);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level == null) {
			MenuShader.draw(g, 1);
			g.fillGradient(0, 0, width, height, 0x3009080E, 0x9009080E);
		} else {
			extractBlurredBackground(g);
			g.fillGradient(0, 0, width, height, 0x7009080E, 0xC009080E);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		hits = new ArrayList<>();
		// While the dialog is open the page underneath is not clickable.
		int mx = dialog == null ? mouseX : -1, my = dialog == null ? mouseY : -1;
		List<Kit.Entry> entries = List.of(
			new Kit.Entry("Start", Icon.HOUSE, false, true, this::onClose),
			new Kit.Entry("Welten", Icon.EARTH, false, minecraft.level == null, () -> minecraft.gui.setScreen(new SelectWorldScreen(parent))),
			new Kit.Entry("Server", Icon.SERVER, true, true, null),
			new Kit.Entry("Screenshots", Icon.IMAGES, false, true, () -> minecraft.gui.setScreen(new ScreenshotGalleryScreen(this))),
			new Kit.Entry("Module", Icon.LAYOUT_GRID, false, true, () -> minecraft.gui.setScreen(new ClickGuiScreen(this))),
			new Kit.Entry("Einstellungen", Icon.SETTINGS, false, true, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))));
		String[] tooltip = Kit.rail(g, "servers", entries, new Kit.Entry("Zurück", Icon.CHEVRON_LEFT, false, true, this::onClose), height, mx, my, intro(0), hits);
		Kit.playerPill(g, width, intro(0.1));

		float left = Kit.contentLeft(), right = width - 16;
		drawHeader(g, left);
		float toolY = 52;
		drawToolbar(g, left, right, toolY, mx, my);
		float listTop = toolY + TOOL_H + 10, listBottom = height - 14 - BAR_H - 10;
		drawList(g, left, right, listTop, listBottom, mx, my);
		drawActions(g, left, right, height - 14 - BAR_H, mx, my);
		drawToast(g, left, right);
		if (dialog == null) {
			Kit.tooltip(g, tooltip);
		} else {
			// Only the dialog takes clicks while it is open.
			hits = new ArrayList<>();
			drawDialog(g, mouseX, mouseY);
		}
	}

	private void drawHeader(GuiGraphicsExtractor g, float left) {
		double k = intro(0.05);
		Ui.bold(g, "Server", left, 14 + (float) (1 - k) * 6, 18, Theme.alpha(Theme.TEXT, k));
		int n = servers.size();
		long online = saved().stream().filter(s -> s.state() == ServerData.State.SUCCESSFUL).count();
		String sub = (n == 1 ? "1 Server" : n + " Server") + (n > 0 ? " · " + online + " erreichbar" : "");
		if (!lan.isEmpty()) sub += " · " + lan.size() + " im LAN";
		Ui.text(g, sub, left, 38, 7.5f, Theme.alpha(Theme.MUTED, k));
	}

	private void drawToolbar(GuiGraphicsExtractor g, float left, float right, float y, int mx, int my) {
		double k = intro(0.15);
		float room = right - left;
		float sw = Math.max(90, Math.min(190, room * 0.36f));
		boolean focused = search.isFocused();
		Ui.rect(g, left, y, sw, TOOL_H, TOOL_H / 2, Theme.alpha(Theme.PANEL_SOLID, 0.8 * k));
		Ui.outline(g, left, y, sw, TOOL_H, TOOL_H / 2, focused ? Theme.alpha(Theme.ACCENT_2, 0.45 * k) : Theme.alpha(0xFFFFFF, 0.12 * k));
		Ui.icon(g, Icon.SEARCH, left + 8, y + 5, 12, Theme.alpha(Theme.MUTED, k));
		int ex = Math.round(left + 25), ew = Math.round(sw - 25 - 22);
		search.setRectangle(ew, 10, ex, Math.round(y + (TOOL_H - 8) / 2));
		if (search.getValue().isEmpty()) Ui.text(g, "Server suchen", ex, y + (TOOL_H - 9) / 2, 7.5f, Theme.alpha(Theme.DIM, k));
		search.extractRenderState(g, mx, my, 0);
		hits.add(new Hit(left, y, sw, TOOL_H, () -> setFocused(search)));
		if (!search.getValue().isEmpty()) {
			float cx = left + sw - 18;
			boolean hover = Kit.over(cx, y + 3, 16, 16, mx, my);
			Ui.icon(g, Icon.X, cx + 2, y + 5, 12, Theme.alpha(hover ? Theme.TEXT : Theme.MUTED, k));
			hits.add(0, new Hit(cx, y + 3, 16, 16, () -> search.setValue("")));
		}

		// Right: Aktualisieren (icon), Direkt verbinden, Server hinzufügen; the view switch left of them.
		float x = right;
		String add = "Hinzufügen";
		float aw = Ui.width(add, 8) + 32;
		x -= aw;
		button(g, "servers.add", add, Icon.PLUS, x, y, aw, TOOL_H, Look.PRIMARY, true, mx, my, k, () -> openDialog(Mode.ADD, null));
		String direct = "Direkt verbinden";
		float dw = Ui.width(direct, 8) + 32;
		boolean roomy = x - dw - 6 - TOOL_H - 6 > left + sw + 10;
		if (roomy) {
			x -= dw + 6;
			button(g, "servers.direct", direct, Icon.ARROW_RIGHT, x, y, dw, TOOL_H, Look.GLASS, true, mx, my, k, () -> openDialog(Mode.DIRECT, null));
		} else {
			x -= TOOL_H + 6;
			iconButton(g, "servers.direct", Icon.ARROW_RIGHT, x, y, TOOL_H, mx, my, k, () -> openDialog(Mode.DIRECT, null));
		}
		x -= TOOL_H + 6;
		iconButton(g, "servers.refresh", Icon.REFRESH_CW, x, y, TOOL_H, mx, my, k, this::refresh);
		String style = "Ansicht: Modern";
		float px = x - 8 - Kit.pillWidth(style);
		if (px > left + sw + 10) Kit.pill(g, "servers.style", style, px, y + (TOOL_H - 18) / 2, true, mx, my, k, hits, this::switchToClassic);
	}

	private enum Look { PRIMARY, GLASS, DANGER }

	private void button(GuiGraphicsExtractor g, String key, String label, String icon, float x, float y, float w, float h, Look style, boolean enabled, int mx, int my, double k, Runnable action) {
		button(g, key, label, icon, x, y, w, h, style, enabled, false, mx, my, k, action);
	}

	/** Compact button for the toolbar, the action bar and the dialog. */
	private void button(GuiGraphicsExtractor g, String key, String label, String icon, float x, float y, float w, float h, Look style, boolean enabled, boolean hot, int mx, int my, double k, Runnable action) {
		boolean hover = enabled && Kit.over(x, y, w, h, mx, my);
		double hv = Math.max(Ui.anim(key, hover), hot ? 1 : 0);
		double a = (enabled ? 1 : 0.4) * k;
		float r = Math.min(h / 2, 10);
		switch (style) {
			case PRIMARY -> {
				Ui.shadow(g, x, y, w, h, r, 10, 3, Theme.alpha(Theme.ACCENT, (0.3 + 0.25 * hv) * a));
				Ui.chroma(g, x, y, w, h, r, a, 0.1 * hv);
			}
			case GLASS -> {
				Ui.rect(g, x, y, w, h, r, Theme.alpha(Theme.RAISED, (0.68 + 0.27 * hv) * a));
				Ui.outline(g, x, y, w, h, r, Theme.alpha(0xFFFFFF, (0.08 + 0.12 * hv) * a));
			}
			case DANGER -> {
				Ui.rect(g, x, y, w, h, r, Theme.alpha(Theme.lerp(Theme.RAISED, 0xFF3A1820, hv), (0.68 + 0.27 * hv) * a));
				Ui.outline(g, x, y, w, h, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ERR, hv), (0.08 + 0.3 * hv) * a));
			}
		}
		int color = switch (style) {
			case PRIMARY -> 0xFFFFFFFF;
			case GLASS -> Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
			case DANGER -> Theme.lerp(Theme.MUTED, Theme.ERR, hv);
		};
		float tx = x + 10;
		if (icon != null) {
			Ui.icon(g, icon, x + 9, y + (h - 11) / 2, 11, Theme.alpha(color, a));
			tx = x + 24;
		}
		int text = style == Look.GLASS ? Theme.lerp(Theme.TEXT, 0xFFFFFFFF, hv) : color;
		Ui.text(g, Ui.fit(label, 8, w - (tx - x) - 6), tx, y + (h - 10) / 2, 8, Theme.alpha(text, a));
		if (enabled) hits.add(new Hit(x, y, w, h, action));
	}

	private void iconButton(GuiGraphicsExtractor g, String key, String icon, float x, float y, float s, int mx, int my, double k, Runnable action) {
		boolean hover = Kit.over(x, y, s, s, mx, my);
		double hv = Ui.anim(key, hover);
		Ui.rect(g, x, y, s, s, s / 2, Theme.alpha(Theme.RAISED, (0.68 + 0.27 * hv) * k));
		Ui.outline(g, x, y, s, s, s / 2, Theme.alpha(0xFFFFFF, (0.08 + 0.12 * hv) * k));
		Ui.icon(g, icon, x + (s - 11) / 2, y + (s - 11) / 2, 11, Theme.alpha(Theme.lerp(Theme.MUTED, Theme.TEXT, hv), k));
		hits.add(new Hit(x, y, s, s, action));
	}

	/** Layout of the list: saved servers, then (if any) the LAN header and LAN worlds. */
	private float contentHeight(int saved, int lanCount) {
		float h = saved * (ROW_H + ROW_GAP);
		if (lanCount > 0) h += SECTION_H + lanCount * (ROW_H + ROW_GAP);
		return Math.max(0, h - ROW_GAP);
	}

	private void drawList(GuiGraphicsExtractor g, float left, float right, float top, float bottom, int mx, int my) {
		double k = intro(0.25);
		List<ServerData> rows = shownSaved();
		List<LanServer> lanRows = shownLan();
		float viewH = bottom - top;
		float content = contentHeight(rows.size(), lanRows.size());
		double max = Math.max(0, content - viewH);
		if (dragging != null) {
			// Scroll along while dragging at the edges.
			if (dragY < top + 12) scrollTarget -= 4;
			else if (dragY > bottom - 12) scrollTarget += 4;
		}
		scrollTarget = Math.max(0, Math.min(scrollTarget, max));
		scroll += (scrollTarget - scroll) * 0.35;
		if (Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;

		if (rows.isEmpty() && lanRows.isEmpty()) {
			boolean filtered = !search.getValue().isBlank();
			String head = filtered ? "Kein Server gefunden" : "Noch keine Server";
			String sub = filtered ? "Kein Server passt zu „" + search.getValue().trim() + "“." : "Füge einen mit „Hinzufügen“ hinzu oder verbinde dich direkt.";
			float cx = (left + right) / 2, cy = top + viewH / 2 - 20;
			Ui.icon(g, filtered ? Icon.SEARCH : Icon.SERVER, cx - 10, cy - 24, 20, Theme.alpha(Theme.DIM, k));
			Ui.bold(g, head, cx - Ui.boldWidth(head, 11) / 2, cy, 11, Theme.alpha(Theme.TEXT, k));
			Ui.text(g, sub, cx - Ui.width(sub, 7.5f) / 2, cy + 18, 7.5f, Theme.alpha(Theme.MUTED, k));
			return;
		}

		boolean scrollbar = max > 0;
		float rowW = right - left - (scrollbar ? 8 : 0);
		boolean inView = my >= top && my < bottom;
		g.enableScissor(Math.round(left) - 2, Math.round(top), Math.round(right) + 2, Math.round(bottom));
		float y = top - (float) scroll;
		for (ServerData s : rows) {
			ping(s);
			if (s != dragging && y + ROW_H >= top && y <= bottom) {
				drawServerRow(g, s, left, y, rowW, inView && dragging == null && Kit.over(left, y, rowW, ROW_H, mx, my), k);
				if (inView) {
					float hy = Math.max(top, y), hh = Math.min(bottom, y + ROW_H) - hy;
					hits.add(new Hit(left, hy, rowW, hh, () -> clickServer(s)));
				}
			} else if (s == dragging) {
				// The gap where the dragged server will land.
				Ui.outline(g, left, y, rowW, ROW_H, 9, Theme.alpha(Theme.ACCENT_2, 0.35 * k));
			}
			y += ROW_H + ROW_GAP;
		}
		if (!lanRows.isEmpty()) {
			Ui.icon(g, Icon.WIFI, left + 2, y + 4, 10, Theme.alpha(Theme.DIM, k));
			Ui.text(g, "Im lokalen Netzwerk", left + 16, y + 4, 7.5f, Theme.alpha(Theme.DIM, k));
			y += SECTION_H;
			for (LanServer s : lanRows) {
				if (y + ROW_H >= top && y <= bottom) {
					drawLanRow(g, s, left, y, rowW, inView && dragging == null && Kit.over(left, y, rowW, ROW_H, mx, my), k);
					if (inView) {
						float hy = Math.max(top, y), hh = Math.min(bottom, y + ROW_H) - hy;
						hits.add(new Hit(left, hy, rowW, hh, () -> clickLan(s)));
					}
				}
				y += ROW_H + ROW_GAP;
			}
		}
		g.disableScissor();
		if (dragging != null) {
			float dy = (float) Math.max(top - ROW_H / 2, Math.min(bottom - ROW_H / 2, dragY - ROW_H / 2));
			Ui.shadow(g, left, dy, rowW, ROW_H, 9, 12, 4, Theme.alpha(0xFF000000, 0.5));
			drawServerRow(g, dragging, left, dy, rowW, true, 1);
		}

		if (scrollbar) {
			float barH = Math.max(18, viewH * viewH / content);
			float barY = top + (float) ((viewH - barH) * (scroll / max));
			double hv = Ui.anim("servers.scrollbar", Kit.over(right - 5, top, 5, viewH, mx, my));
			float tw = 3 + (float) hv;
			Ui.rect(g, right - 4 + (4 - tw) / 2, barY, tw, barH, tw / 2, Theme.alpha(0xFFFFFF, (0.22 + 0.25 * hv) * k));
		}
	}

	private long lastClick;
	private Object lastClicked;

	private boolean doubleClick(Object row) {
		long now = System.currentTimeMillis();
		boolean dbl = row.equals(lastClicked) && now - lastClick < 400;
		lastClicked = row;
		lastClick = now;
		return dbl;
	}

	private void clickServer(ServerData s) {
		if (s != selected) deleteArmed = null;
		selected = s;
		selectedLan = null;
		if (doubleClick(s)) join(s);
	}

	private void clickLan(LanServer s) {
		selected = null;
		selectedLan = s.getAddress();
		deleteArmed = null;
		if (doubleClick("lan:" + s.getAddress())) joinLan(s);
	}

	/** Row frame shared by both kinds; returns the selection progress for extras. */
	private void rowFrame(GuiGraphicsExtractor g, String key, boolean sel, float x, float y, float rw, boolean hover, double k) {
		double hv = Ui.anim("servers.row." + key, hover);
		double sv = Ui.anim("servers.sel." + key, sel);
		Ui.rect(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.PANEL_SOLID, (0.5 + 0.12 * hv + 0.15 * sv) * k));
		if (sv > 0.01) Ui.rect(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.ACCENT, 0.16 * sv * k));
		Ui.outline(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, Math.max(hv * 0.6, sv)), (0.07 + 0.12 * hv + 0.3 * sv) * k));
		if (sv > 0.01) Ui.chroma(g, x + 1.5f, y + 8, 2.5f, (ROW_H - 16) * (float) sv, 1.25f, k, 0);
	}

	private void drawServerRow(GuiGraphicsExtractor g, ServerData s, float x, float y, float rw, boolean hover, double k) {
		String key = Integer.toHexString(System.identityHashCode(s));
		rowFrame(g, key, s == selected, x, y, rw, hover, k);

		float ix = x + 6, iy = y + 4, is = ROW_H - 8;
		net.minecraft.resources.Identifier tex = icon(s);
		if (tex != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, Math.round(ix), Math.round(iy), 0, 0, Math.round(is), Math.round(is), 64, 64, 64, 64, Theme.alpha(0xFFFFFF, k));
		} else {
			Ui.rect(g, ix, iy, is, is, ICON_RADIUS, Theme.alpha(Theme.chroma(-(Math.abs(s.ip.hashCode()) % 100) / 100.0), 0.75 * k));
			Ui.icon(g, Icon.SERVER, ix + (is - 12) / 2, iy + (is - 12) / 2, 12, Theme.alpha(0xFFFFFF, k));
		}

		// Right column: ping bars + ms, players (or the version in red on a mismatch).
		float rx = x + rw - 10;
		ServerData.State state = s.state();
		String top, bottom;
		int topColor = Theme.MUTED, bottomColor = Theme.DIM;
		switch (state) {
			case SUCCESSFUL -> {
				top = s.ping + " ms";
				bottom = s.players != null ? s.players.online() + " / " + s.players.max() + " Spieler" : s.status == null ? "" : s.status.getString();
			}
			case INCOMPATIBLE -> {
				top = "Andere Version";
				bottom = s.version.getString();
				topColor = Theme.ERR;
				bottomColor = Theme.ERR;
			}
			case UNREACHABLE -> {
				top = "Offline";
				bottom = "";
				topColor = Theme.ERR;
			}
			default -> {
				top = "Pingt …";
				bottom = "";
			}
		}
		float barsW = 14;
		drawBars(g, rx - barsW, y + 6, state, s.ping, k);
		Ui.text(g, top, rx - barsW - 5 - Ui.width(top, 7.5f), y + 5, 7.5f, Theme.alpha(topColor, k));
		if (!bottom.isEmpty()) Ui.text(g, bottom, rx - Ui.width(bottom, 7), y + 17, 7, Theme.alpha(bottomColor, k));
		float rightCol = Math.max(barsW + 5 + Ui.width(top, 7.5f), Ui.width(bottom, 7)) + 16;

		float tx = ix + is + 8, room = rw - (tx - x) - rightCol;
		Ui.text(g, Ui.fit(s.name, 8.5f, room), tx, y + 4, 8.5f, Theme.alpha(Theme.TEXT, k));
		Component motd = s.motd == null || s.motd.getString().isBlank()
			? Component.literal(state == ServerData.State.PINGING || state == ServerData.State.INITIAL ? s.ip : "")
			: s.motd;
		drawStyled(g, motd, tx, y + 17, 7, room, k);
	}

	/** Five signal bars like vanilla's, in the launcher's colours. */
	private void drawBars(GuiGraphicsExtractor g, float x, float y, ServerData.State state, long ping, double k) {
		int lit = 0;
		int color = Theme.ON;
		switch (state) {
			case SUCCESSFUL -> {
				lit = ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1000 ? 2 : 1;
				color = lit >= 4 ? Theme.ON : lit == 3 ? 0xFFFBBF24 : Theme.ERR;
			}
			case PINGING, INITIAL -> {
				// The bars run up and down while pinging.
				int phase = (int) (System.currentTimeMillis() / 120 % 8);
				lit = phase > 4 ? 8 - phase : phase;
				color = Theme.ACCENT_2;
			}
			default -> color = Theme.ERR;
		}
		for (int i = 0; i < 5; i++) {
			float bh = 2 + i * 1.6f, bx = x + i * 3;
			int c = i < lit ? color : 0x33FFFFFF;
			if (state == ServerData.State.UNREACHABLE || state == ServerData.State.INCOMPATIBLE) c = Theme.alpha(Theme.ERR, 0.45);
			Ui.rect(g, bx, y + 8 - bh, 2, bh, 0.6f, Theme.alpha(c, k));
		}
	}

	/** Draws a component in its own colours (MOTD), legacy § codes included, cut at `maxW`. */
	private void drawStyled(GuiGraphicsExtractor g, Component text, float x, float y, float size, float maxW, double k) {
		float[] cx = {x};
		float end = x + maxW;
		// Servers centre their MOTD for vanilla's wide list with leading spaces; the row starts at the name instead.
		started = false;
		text.visit((style, part) -> {
			int color = colorOf(style);
			StringBuilder run = new StringBuilder();
			for (int i = 0; i < part.length(); i++) {
				char c = part.charAt(i);
				if (c == '§' && i + 1 < part.length()) {
					if (!draw(g, run, cx, end, y, size, color, k)) return Optional.of(Boolean.TRUE);
					int legacy = "0123456789abcdef".indexOf(Character.toLowerCase(part.charAt(i + 1)));
					if (legacy >= 0) color = LEGACY[legacy];
					else if (Character.toLowerCase(part.charAt(i + 1)) == 'r') color = colorOf(style);
					i++;
					continue;
				}
				if (c == '\n') {
					draw(g, run, cx, end, y, size, color, k);
					return Optional.of(Boolean.TRUE);
				}
				run.append(c);
			}
			return draw(g, run, cx, end, y, size, color, k) ? Optional.empty() : Optional.of(Boolean.TRUE);
		}, Style.EMPTY);
	}

	/** Whether the current MOTD line has drawn visible text yet (leading spaces are dropped until then). */
	private boolean started;

	private static final int[] LEGACY = {0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
		0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF};

	private static int colorOf(Style style) {
		return style.getColor() == null ? Theme.MUTED : 0xFF000000 | style.getColor().getValue();
	}

	/** Draws and clears `run`; false once the line is full (text then ends with "…"). */
	private boolean draw(GuiGraphicsExtractor g, StringBuilder run, float[] cx, float end, float y, float size, int color, double k) {
		if (run.isEmpty()) return true;
		String s = run.toString();
		run.setLength(0);
		if (!started) {
			s = s.stripLeading();
			if (s.isEmpty()) return true;
			started = true;
		}
		// Very dark MOTD colours would vanish on the dark glass.
		int c = Theme.lerp(color, 0xFFFFFFFF, luminance(color) < 0.25 ? 0.45 : 0);
		if (cx[0] + Ui.width(s, size) <= end) {
			cx[0] += Ui.text(g, s, cx[0], y, size, Theme.alpha(c, k));
			return true;
		}
		String fit = Ui.fit(s, size, end - cx[0]);
		if (!fit.isEmpty()) cx[0] += Ui.text(g, fit, cx[0], y, size, Theme.alpha(c, k));
		return false;
	}

	private static double luminance(int c) {
		return (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255;
	}

	private void drawLanRow(GuiGraphicsExtractor g, LanServer s, float x, float y, float rw, boolean hover, double k) {
		rowFrame(g, "lan." + s.getAddress(), s.getAddress().equals(selectedLan), x, y, rw, hover, k);
		float ix = x + 6, iy = y + 4, is = ROW_H - 8;
		Ui.rect(g, ix, iy, is, is, ICON_RADIUS, Theme.alpha(Theme.RAISED, 0.9 * k));
		Ui.icon(g, Icon.WIFI, ix + (is - 12) / 2, iy + (is - 12) / 2, 12, Theme.alpha(Theme.ON, k));
		String hidden = minecraft.options.hideServerAddress ? "Adresse ausgeblendet" : s.getAddress();
		float tx = ix + is + 8, room = rw - (tx - x) - 70;
		Ui.text(g, Ui.fit(s.getMotd(), 8.5f, room), tx, y + 4, 8.5f, Theme.alpha(Theme.TEXT, k));
		Ui.text(g, Ui.fit(hidden, 7, room), tx, y + 17, 7, Theme.alpha(Theme.MUTED, k));
		String tag = "LAN-Welt";
		Ui.text(g, tag, x + rw - 10 - Ui.width(tag, 7.5f), y + 5, 7.5f, Theme.alpha(Theme.ON, k));
	}

	private void drawActions(GuiGraphicsExtractor g, float left, float right, float y, int mx, int my) {
		double k = intro(0.35);
		boolean any = selected != null || selectedLan != null;
		boolean savedSel = selected != null;
		float x = left;
		float pw = Ui.width("Beitreten", 8) + 34;
		button(g, "servers.join", "Beitreten", Icon.PLAY, x, y, pw, BAR_H, Look.PRIMARY, any, mx, my, k, this::joinSelected);
		x += pw + 8;
		record Act(String key, String label, String icon, Look style, boolean enabled, boolean hot, Runnable run) {
		}
		boolean armed = armed(selected);
		List<Act> acts = List.of(
			new Act("edit", "Bearbeiten", Icon.PENCIL, Look.GLASS, savedSel, false, () -> openDialog(Mode.EDIT, selected)),
			new Act("copy", "Adresse kopieren", Icon.COPY, Look.GLASS, any, false, this::copyAddress),
			new Act("delete", armed ? "Wirklich löschen?" : "Löschen", Icon.TRASH_2, Look.DANGER, savedSel, armed, this::delete));
		float full = 0;
		for (Act a : acts) full += Ui.width(a.label(), 8) + 32 + 6;
		boolean iconsOnly = full > right - x;
		for (Act a : acts) {
			float bw = iconsOnly ? BAR_H + 4 : Ui.width(a.label(), 8) + 32;
			if (iconsOnly) {
				boolean hover = a.enabled() && Kit.over(x, y, bw, BAR_H, mx, my);
				double hv = Math.max(Ui.anim("servers.act." + a.key(), hover), a.hot() ? 1 : 0);
				double al = (a.enabled() ? 1 : 0.4) * k;
				Ui.rect(g, x, y, bw, BAR_H, 10, Theme.alpha(a.style() == Look.DANGER ? Theme.lerp(Theme.RAISED, 0xFF3A1820, hv) : Theme.RAISED, (0.68 + 0.27 * hv) * al));
				Ui.outline(g, x, y, bw, BAR_H, 10, Theme.alpha(0xFFFFFF, (0.08 + 0.12 * hv) * al));
				int c = a.style() == Look.DANGER ? Theme.lerp(Theme.MUTED, Theme.ERR, hv) : Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
				Ui.icon(g, a.icon(), x + (bw - 12) / 2, y + (BAR_H - 12) / 2, 12, Theme.alpha(c, al));
				if (a.enabled()) hits.add(new Hit(x, y, bw, BAR_H, a.run()));
			} else {
				button(g, "servers.act." + a.key(), a.label(), a.icon(), x, y, bw, BAR_H, a.style(), a.enabled(), a.hot(), mx, my, k, a.run());
			}
			x += bw + 6;
		}
		String hint = query().isEmpty() ? "Doppelklick verbindet · Ziehen sortiert" : "Doppelklick verbindet";
		if (x + Ui.width(hint, 6.5f) + 10 < right) Ui.text(g, hint, right - Ui.width(hint, 6.5f), y + (BAR_H - 8) / 2, 6.5f, Theme.alpha(Theme.DIM, k));
	}

	private void drawToast(GuiGraphicsExtractor g, float left, float right) {
		if (toast == null) return;
		double age = (System.currentTimeMillis() - toastAt) / 1000.0;
		if (age > 1.8) {
			toast = null;
			return;
		}
		double a = Math.min(1, Math.min(age / 0.15, (1.8 - age) / 0.3));
		float w = Ui.width(toast, 8) + 34, h = 22, x = (left + right - w) / 2, y = height - 14 - BAR_H - 10 - h - 6;
		Ui.rect(g, x, y, w, h, 11, Theme.alpha(Theme.PANEL_SOLID, 0.95 * a));
		Ui.outline(g, x, y, w, h, 11, Theme.alpha(Theme.ON, 0.4 * a));
		Ui.icon(g, Icon.CHECK, x + 10, y + 5.5f, 11, Theme.alpha(Theme.ON, a));
		Ui.text(g, toast, x + 25, y + (h - 10) / 2, 8, Theme.alpha(Theme.TEXT, a));
	}

	private void drawDialog(GuiGraphicsExtractor g, int mx, int my) {
		Dialog d = dialog;
		double k = Theme.ease((System.currentTimeMillis() - d.openedAt) / 1000.0 / 0.25);
		g.fill(0, 0, width, height, Theme.alpha(0xFF05040A, 0.55 * k));
		String title = switch (d.mode) {
			case ADD -> "Server hinzufügen";
			case EDIT -> "Server bearbeiten";
			case DIRECT -> "Direkt verbinden";
		};
		float w = Math.min(300, width - 40), pad = 16;
		float fieldH = 22, gap = 8;
		float h = pad + 18 + 10 + (d.mode == Mode.DIRECT ? 0 : 12 + fieldH + gap) + 12 + fieldH + gap + (d.mode == Mode.DIRECT ? 0 : 12 + TOOL_H + gap) + 6 + BAR_H + pad;
		float x = (width - w) / 2, y = (height - h) / 2 + (float) (1 - k) * 10;
		Ui.glass(g, x, y, w, h, 16, k);
		Ui.rect(g, x, y, w, h, 16, Theme.alpha(Theme.PANEL_SOLID, 0.55 * k));
		Ui.bold(g, title, x + pad, y + pad, 13, Theme.alpha(Theme.TEXT, k));
		float cy = y + pad + 18 + 10;
		if (d.mode != Mode.DIRECT) {
			cy = field(g, d.name, "Name", "Minecraft-Server", x + pad, cy, w - 2 * pad, fieldH, k) + gap;
		}
		cy = field(g, d.address, "Adresse", "z. B. play.example.net", x + pad, cy, w - 2 * pad, fieldH, k) + gap;
		if (!d.address.getValue().isBlank() && !ServerAddress.isValidAddress(d.address.getValue())) {
			Ui.text(g, "Ungültige Adresse", x + w - pad - Ui.width("Ungültige Adresse", 7), cy - gap - fieldH - 12, 7, Theme.alpha(Theme.ERR, k));
		}
		if (d.mode != Mode.DIRECT) {
			Ui.text(g, "Resourcepacks des Servers", x + pad, cy, 7.5f, Theme.alpha(Theme.MUTED, k));
			cy += 12;
			float sx = x + pad;
			float segW = 0;
			for (String s : PACK_LABELS) segW += Ui.width(s, 7.5f) + 22;
			Ui.rect(g, sx, cy, segW + 4, TOOL_H, TOOL_H / 2, Theme.alpha(Theme.PANEL_SOLID, 0.7 * k));
			Ui.outline(g, sx, cy, segW + 4, TOOL_H, TOOL_H / 2, Theme.alpha(0xFFFFFF, 0.08 * k));
			sx += 2;
			for (int i = 0; i < PACKS.length; i++) {
				ServerData.ServerPackStatus p = PACKS[i];
				float bw = Ui.width(PACK_LABELS[i], 7.5f) + 22;
				boolean on = d.packs == p;
				double hv = Ui.anim("servers.dialog.pack." + i, Kit.over(sx, cy + 2, bw, TOOL_H - 4, mx, my));
				if (on) {
					Ui.rect(g, sx, cy + 2, bw, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(Theme.ACCENT, 0.28 * k));
					Ui.outline(g, sx, cy + 2, bw, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(Theme.ACCENT_2, 0.4 * k));
				} else if (hv > 0) {
					Ui.rect(g, sx, cy + 2, bw, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(0xFFFFFF, 0.06 * hv * k));
				}
				Ui.text(g, PACK_LABELS[i], sx + 11, cy + (TOOL_H - 9.4f) / 2, 7.5f, Theme.alpha(on ? 0xFFFFFFFF : Theme.lerp(Theme.MUTED, Theme.TEXT, hv), k));
				hits.add(new Hit(sx, cy + 2, bw, TOOL_H - 4, () -> d.packs = p));
				sx += bw;
			}
			cy += TOOL_H + gap;
		}
		cy += 6;
		String ok = switch (d.mode) {
			case ADD -> "Hinzufügen";
			case EDIT -> "Speichern";
			case DIRECT -> "Verbinden";
		};
		float okW = Ui.width(ok, 8) + 34, cancelW = Ui.width("Abbrechen", 8) + 22;
		float bx = x + w - pad - okW;
		button(g, "servers.dialog.ok", ok, d.mode == Mode.DIRECT ? Icon.PLAY : Icon.CHECK, bx, cy, okW, BAR_H, Look.PRIMARY, d.valid(), mx, my, k, d::submit);
		button(g, "servers.dialog.cancel", "Abbrechen", null, bx - 6 - cancelW, cy, cancelW, BAR_H, Look.GLASS, true, mx, my, k, this::closeDialog);
		// Clicks outside the panel close it, like the launcher's dialogs.
		final float px = x, py = y, pw = w, ph = h;
		hits.add(new Hit(0, 0, width, height, () -> {
			if (!Kit.over(px, py, pw, ph, (int) lastMouseX, (int) lastMouseY)) closeDialog();
		}));
	}

	private double lastMouseX, lastMouseY;

	/** A labelled text field (vanilla EditBox inside a glass box); returns its bottom. */
	private float field(GuiGraphicsExtractor g, EditBox box, String label, String hint, float x, float y, float w, float h, double k) {
		Ui.text(g, label, x, y, 7.5f, Theme.alpha(Theme.MUTED, k));
		y += 12;
		boolean focused = getFocused() == box;
		Ui.rect(g, x, y, w, h, 9, Theme.alpha(Theme.PANEL_SOLID, 0.9 * k));
		Ui.outline(g, x, y, w, h, 9, focused ? Theme.alpha(Theme.ACCENT_2, 0.6 * k) : Theme.alpha(0xFFFFFF, 0.12 * k));
		box.setRectangle(Math.round(w - 20), 10, Math.round(x + 10), Math.round(y + (h - 8) / 2));
		if (box.getValue().isEmpty()) Ui.text(g, hint, x + 10, y + (h - 9) / 2, 7.5f, Theme.alpha(Theme.DIM, k));
		box.extractRenderState(g, -1, -1, 0);
		hits.add(new Hit(x, y, w, h, () -> setFocused(box)));
		return y + h;
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		lastMouseX = event.x();
		lastMouseY = event.y();
		// Minecraft 26.3 with SDL3 sends 1 for a left click, 0 with legacy GLFW.
		if (event.button() != 0 && event.button() != 1) return super.mouseClicked(event, doubleClick);
		if (dialog == null) {
			ServerData row = savedRowAt(event.y());
			if (row != null && query().isEmpty()) {
				dragging = null;
				pendingDrag = row;
				dragStartY = dragY = event.y();
				dragMoved = false;
			}
		}
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(event.x(), event.y())) {
				h.action().run();
				// Let the focused text field place its cursor.
				if (getFocused() instanceof EditBox box && box.isMouseOver(event.x(), event.y())) box.mouseClicked(event, doubleClick);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private ServerData pendingDrag;

	/** The saved server under `y` in the unfiltered list, or null. */
	private ServerData savedRowAt(double y) {
		float top = 52 + TOOL_H + 10, bottom = height - 14 - BAR_H - 10;
		if (y < top || y >= bottom) return null;
		double rel = y - top + scroll;
		int i = (int) Math.floor(rel / (ROW_H + ROW_GAP));
		if (i < 0 || i >= servers.size() || rel - i * (ROW_H + ROW_GAP) > ROW_H) return null;
		return servers.get(i);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pendingDrag != null && !dragMoved && Math.abs(event.y() - dragStartY) > 4) {
			dragMoved = true;
			dragging = pendingDrag;
			selected = dragging;
		}
		if (dragging != null) {
			dragY = event.y();
			// Swap with the neighbour once the dragged row's centre passes it.
			float top = 52 + TOOL_H + 10;
			int target = (int) Math.floor((dragY - top + scroll) / (ROW_H + ROW_GAP));
			target = Math.max(0, Math.min(servers.size() - 1, target));
			int at = saved().indexOf(dragging);
			while (at >= 0 && at < target) {
				servers.swap(at, at + 1);
				at++;
			}
			while (at > target) {
				servers.swap(at, at - 1);
				at--;
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			servers.save();
			dragging = null;
			pendingDrag = null;
			lastClicked = null;
			return true;
		}
		pendingDrag = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (dialog != null) return true;
		scrollTarget -= scrollY * (ROW_H + ROW_GAP) * 1.5;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (dialog != null) {
			if (key == InputConstants.KEY_ESCAPE) {
				closeDialog();
				return true;
			}
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				dialog.submit();
				return true;
			}
			if (key == InputConstants.KEY_TAB && dialog.mode != Mode.DIRECT) {
				setFocused(getFocused() == dialog.name ? dialog.address : dialog.name);
				return true;
			}
			return super.keyPressed(event);
		}
		if (SlopClient.clickGuiKey().matches(event)) {
			minecraft.gui.setScreen(new ClickGuiScreen(this));
			return true;
		}
		if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
			moveSelection(key == InputConstants.KEY_DOWN ? 1 : -1);
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			joinSelected();
			return true;
		}
		if (key == InputConstants.KEY_DELETE && search.getValue().isEmpty() && selected != null) {
			delete();
			return true;
		}
		if (key == InputConstants.KEY_F5) {
			refresh();
			return true;
		}
		return super.keyPressed(event);
	}

	private void moveSelection(int dir) {
		List<Object> rows = new ArrayList<>(shownSaved());
		rows.addAll(shownLan());
		if (rows.isEmpty()) return;
		int i = -1;
		for (int j = 0; j < rows.size(); j++) {
			Object r = rows.get(j);
			if (r == selected || (r instanceof LanServer l && l.getAddress().equals(selectedLan))) i = j;
		}
		i = Math.max(0, Math.min(rows.size() - 1, i + dir));
		Object r = rows.get(i);
		deleteArmed = null;
		if (r instanceof ServerData s) {
			selected = s;
			selectedLan = null;
		} else if (r instanceof LanServer l) {
			selected = null;
			selectedLan = l.getAddress();
		}
		int savedCount = shownSaved().size();
		float rowTop = i * (ROW_H + ROW_GAP) + (i >= savedCount ? SECTION_H : 0);
		float top = 52 + TOOL_H + 10, viewH = height - 14 - BAR_H - 10 - top;
		if (rowTop < scrollTarget) scrollTarget = rowTop;
		else if (rowTop + ROW_H > scrollTarget + viewH) scrollTarget = rowTop + ROW_H - viewH;
	}
}
