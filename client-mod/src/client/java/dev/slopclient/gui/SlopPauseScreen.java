package dev.slopclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.slopclient.SlopClient;
import dev.slopclient.mixin.SpriteIconButtonAccessor;
import dev.slopclient.render.Theme;
import dev.slopclient.ui.Icon;
import dev.slopclient.ui.Kit;
import dev.slopclient.ui.Kit.Hit;
import dev.slopclient.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The ESC menu in the launcher look, in two layouts ("ESC-Menü" setting of the title
 * screen module, switchable in the menu itself):
 *
 * <p><b>Klassisch</b>: vanilla's structure, every button where vanilla puts it, drawn
 * in the launcher look.
 *
 * <p><b>Modern</b>: the glass sidebar (Spiel, Screenshots, Module,
 * Einstellungen; Welten and Server only from the title screen), the world or server
 * name large with chips (players, ping, play time, position) and the menu as buttons.
 *
 * <p>Every action runs through the matching button of an invisible vanilla
 * {@link PauseScreen} that is set up alongside. That keeps vanilla's behaviour exactly
 * (saving, disconnecting, report drafts, world options) and picks up buttons other mods
 * add to the pause menu (e.g. ModMenu's "Mods"), which appear in their own row. Their
 * screens return to that vanilla pause screen, which is swapped for this one again.
 */
public final class SlopPauseScreen extends Screen {
	/** Vanilla buttons by their translation key; the rest are mod buttons. */
	private static final String RETURN = "menu.returnToGame", OPTIONS = "menu.options", WORLD_OPTIONS = "options.worldOptions.button",
		ADVANCEMENTS = "gui.advancements", STATS = "gui.stats";
	private static final List<String> DISCONNECT = List.of("menu.disconnect", "menu.returnToMenu");
	/** Vanilla extras left out: the feedback, bug report and player report links. */
	private static final List<String> HIDDEN = List.of("menu.sendFeedback", "menu.reportBugs", "menu.playerReporting", "menu.game", "menu.paused");

	private final long openedAt = System.currentTimeMillis();
	private PauseScreen vanilla;
	private List<Hit> hits = new ArrayList<>();

	public SlopPauseScreen() {
		super(Component.literal("Spiel pausiert"));
	}

	@Override
	protected void init() {
		// Built (with every mod's additions) but never shown.
		vanilla = new PauseScreen(true);
		vanilla.init(width, height);
	}

	private static String key(AbstractWidget w) {
		return w.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : null;
	}

	private static boolean vanillaKey(String key) {
		return key != null && (key.startsWith("menu.") || key.startsWith("gui.") || key.startsWith("options."));
	}

	/** The vanilla pause screen's widget with this key, or null. */
	private AbstractWidget widget(String key) {
		for (var child : vanilla.children()) {
			if (child instanceof AbstractWidget w && w.visible && key.equals(key(w))) return w;
		}
		return null;
	}

	/** Runs a vanilla (or mod) button as if it had been clicked. */
	private static void press(AbstractWidget w) {
		if (w == null || !w.isActive()) return;
		if (w instanceof AbstractButton button) button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
	}

	private double seconds() {
		return (System.currentTimeMillis() - openedAt) / 1000.0;
	}

	private double intro(double delay) {
		return Theme.ease((seconds() - delay) / 0.45);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, 0x7009080E, 0xC009080E);
	}

	private static boolean classic() {
		return SlopClient.modules().titleScreen.pauseStyle.is("Klassisch");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		hits = new ArrayList<>();
		if (classic()) {
			drawClassic(g, mouseX, mouseY);
			drawStyleSwitch(g, Kit.playerPill(g, width, intro(0.1)) + 6, mouseX, mouseY);
			return;
		}
		List<Kit.Entry> entries = List.of(
			new Kit.Entry("Spiel", Icon.PLAY, true, true, null),
			new Kit.Entry("Welten (im Hauptmenü)", Icon.EARTH, false, false, null),
			new Kit.Entry("Server (im Hauptmenü)", Icon.SERVER, false, false, null),
			new Kit.Entry("Screenshots", Icon.IMAGES, false, true, () -> minecraft.gui.setScreen(new ScreenshotGalleryScreen(this))),
			new Kit.Entry("Module", Icon.LAYOUT_GRID, false, true, () -> minecraft.gui.setScreen(new ClickGuiScreen(this))),
			new Kit.Entry("Einstellungen", Icon.SETTINGS, false, true, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))));
		AbstractWidget leave = disconnectWidget();
		Kit.Entry bottom = leave == null ? null : new Kit.Entry(leaveLabel(), Icon.LOG_OUT, false, true, () -> press(leave));
		String[] tooltip = Kit.rail(g, "pause", entries, bottom, height, mouseX, mouseY, intro(0), hits);
		drawStyleSwitch(g, Kit.playerPill(g, width, intro(0.1)) + 6, mouseX, mouseY);
		drawPage(g, mouseX, mouseY);
		Kit.tooltip(g, tooltip);
	}

	private AbstractWidget disconnectWidget() {
		for (String key : DISCONNECT) {
			AbstractWidget w = widget(key);
			if (w != null) return w;
		}
		return null;
	}

	private String leaveLabel() {
		return minecraft.isLocalServer() ? "Speichern & beenden" : "Trennen";
	}

	/** World or server name, chips, menu buttons and the row of mod buttons, from the bottom up. */
	private void drawPage(GuiGraphicsExtractor g, int mx, int my) {
		float left = Kit.contentLeft(), right = width - 16, room = right - left;
		List<AbstractWidget> extras = new ArrayList<>(), mods = new ArrayList<>();
		for (var child : vanilla.children()) {
			if (!(child instanceof AbstractWidget w) || !w.visible) continue;
			String key = key(w);
			if (key != null && (key.equals(RETURN) || key.equals(OPTIONS) || key.equals(WORLD_OPTIONS) || key.equals(ADVANCEMENTS)
				|| key.equals(STATS) || DISCONNECT.contains(key) || HIDDEN.contains(key))) continue;
			(vanillaKey(key) ? extras : mods).add(w);
		}

		float y = height - 16;
		// Row of mod buttons (and vanilla extras like server links) as pills.
		List<AbstractWidget> pills = new ArrayList<>(extras);
		pills.addAll(mods);
		if (!pills.isEmpty()) {
			y -= 18;
			drawPills(g, pills, left, y, room, mx, my);
			y -= 22;
		}

		// Menu buttons.
		y -= Kit.BTN_H;
		drawButtons(g, left, y, room, mx, my);
		y -= 12 + 16;
		drawChips(g, left, y);
		drawTitle(g, left, y - 10, room);
	}

	private void drawTitle(GuiGraphicsExtractor g, float x, float bottom, float room) {
		double k = intro(0.05);
		String name, kind;
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			name = server.getWorldData().getLevelName();
			kind = "Einzelspieler";
		} else if (minecraft.getCurrentServer() != null) {
			name = minecraft.getCurrentServer().name;
			kind = minecraft.getCurrentServer().isRealm() ? "Realm" : "Server";
		} else {
			name = "Minecraft";
			kind = "";
		}
		float size = Math.max(20, Math.min(height < 300 ? 28 : 40, room * 0.075f));
		name = fitBold(name, size, room);
		float y = bottom - size * 1.25f + (float) (1 - k) * 8;
		Ui.text(g, "Pausiert" + (kind.isEmpty() ? "" : " · " + kind), x, y - 14, 9, Theme.alpha(Theme.MUTED, k));
		float cx = x;
		for (int i = 0; i < name.length(); i++) {
			String ch = String.valueOf(name.charAt(i));
			Ui.bold(g, ch, cx, y, size, Theme.alpha(Theme.chroma(-i * 0.07), k));
			cx += Ui.boldWidth(ch, size);
		}
	}

	private static String fitBold(String s, float size, float maxWidth) {
		if (Ui.boldWidth(s, size) <= maxWidth) return s;
		int end = s.length();
		while (end > 0 && Ui.boldWidth(s.substring(0, end) + "…", size) > maxWidth) end--;
		return s.substring(0, end) + "…";
	}

	private void drawChips(GuiGraphicsExtractor g, float x, float y) {
		double k = intro(0.15);
		if (k <= 0) return;
		y += (float) (1 - k) * 6;
		List<String> chips = new ArrayList<>();
		var connection = minecraft.getConnection();
		if (!minecraft.isLocalServer() && connection != null) {
			int players = connection.getOnlinePlayers().size();
			chips.add(players == 1 ? "1 Spieler online" : players + " Spieler online");
			var info = minecraft.player == null ? null : connection.getPlayerInfo(minecraft.player.getUUID());
			if (info != null) chips.add("Ping " + info.getLatency() + " ms");
		}
		long minutes = Math.max(0, (System.currentTimeMillis() - SlopClient.joinedAt) / 60000);
		chips.add(minutes < 60 ? "Seit " + minutes + " min" : String.format(Locale.GERMAN, "Seit %d:%02d h", minutes / 60, minutes % 60));
		if (minecraft.player != null) {
			var pos = minecraft.player.blockPosition();
			chips.add(pos.getX() + " / " + pos.getY() + " / " + pos.getZ());
		}
		x += Kit.chip(g, "Pausiert", x, y, Theme.ACCENT_2, k) + 5;
		for (String c : chips) x += Kit.chip(g, c, x, y, 0, k) + 5;
	}

	private void drawButtons(GuiGraphicsExtractor g, float x, float y, float room, int mx, int my) {
		double k = intro(0.25);
		if (k <= 0) return;
		y += (float) (1 - k) * 8;
		float playW = Kit.primaryWidth("Weiterspielen", null);
		Kit.primary(g, "pause.return", "Weiterspielen", null, Icon.PLAY, x, y, playW, mx, my, k, hits, this::onClose);
		float cx = x + playW + 8, end = x + room;

		record Btn(String key, String label, String icon) {
		}
		List<Btn> buttons = List.of(new Btn(OPTIONS, "Optionen", Icon.SETTINGS), new Btn(WORLD_OPTIONS, "Welt-Optionen", Icon.GLOBE),
			new Btn(ADVANCEMENTS, "Fortschritte", Icon.TROPHY), new Btn(STATS, "Statistiken", Icon.CHART_COLUMN));
		for (Btn b : buttons) {
			AbstractWidget w = widget(b.key());
			if (w == null) continue;
			// Small windows: icon-only buttons once the labels don't fit any more.
			float bw = Kit.glassWidth(b.label());
			Runnable action = b.key().equals(OPTIONS) ? () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options)) : () -> press(w);
			if (cx + bw > end - Kit.glassWidth(leaveLabel()) - 6) bw = 36;
			Kit.glass(g, "pause." + b.key(), bw > 36 ? b.label() : "", b.icon(), cx, y, bw, false, mx, my, k * (w.isActive() ? 1 : 0.4), hits, action);
			cx += bw + 6;
		}
		AbstractWidget leave = disconnectWidget();
		if (leave != null) {
			String label = leaveLabel();
			Kit.glass(g, "pause.leave", label, Icon.LOG_OUT, Math.max(cx + 6, x + room - Kit.glassWidth(label)), y, Kit.glassWidth(label), true, mx, my, k, hits, () -> press(leave));
		}
	}

	private void drawPills(GuiGraphicsExtractor g, List<AbstractWidget> widgets, float x, float y, float room, int mx, int my) {
		double k = intro(0.35);
		if (k <= 0) return;
		Ui.text(g, "Mehr", x, y + 4, 7.5f, Theme.alpha(Theme.DIM, k));
		float cx = x + Ui.width("Mehr", 7.5f) + 8;
		int i = 0;
		for (AbstractWidget w : widgets) {
			String label = w.getMessage().getString();
			if (label.isBlank()) label = "Mod-Knopf";
			if (cx + Kit.pillWidth(label) > x + room) break;
			cx += Kit.pill(g, "pause.pill." + (i++), label, cx, y, w.isActive(), mx, my, k, hits, () -> press(w)) + 5;
		}
	}

	/** Pill under the player pill that flips between the classic and the modern layout. */
	private void drawStyleSwitch(GuiGraphicsExtractor g, float y, int mx, int my) {
		String label = classic() ? "Ansicht: Klassisch" : "Ansicht: Modern";
		float x = width - 12 - Kit.pillWidth(label);
		Kit.pill(g, "pause.style", label, x, y, true, mx, my, intro(0.2), hits, () -> {
			SlopClient.modules().titleScreen.pauseStyle.cycle(classic() ? 1 : -1);
			SlopClient.save();
		});
	}

	// ---------- classic layout ----------

	/** Icons for vanilla's small icon buttons (feedback, bug report, player report, server links). */
	private static String iconFor(String key) {
		if (key == null) return null;
		return switch (key) {
			case "menu.sendFeedback" -> Icon.MESSAGE_SQUARE;
			case "menu.reportBugs" -> Icon.BUG;
			case "menu.playerReporting" -> Icon.FLAG;
			case "menu.server_links" -> Icon.EXTERNAL_LINK;
			default -> null;
		};
	}

	/**
	 * Vanilla's pause menu as it is laid out (every widget at its own place, mods' buttons
	 * included), drawn in the launcher look: chroma "Zurück zum Spiel", glass buttons,
	 * Sora labels, the leave button red on hover.
	 */
	private void drawClassic(GuiGraphicsExtractor g, int mx, int my) {
		double k = intro(0);
		float top = Float.MAX_VALUE, left = Float.MAX_VALUE, right = 0;
		boolean titleDrawn = false;
		float[] tooltip = null;
		String tooltipText = "";
		int i = 0;
		for (var child : vanilla.children()) {
			if (!(child instanceof AbstractWidget w) || !w.visible) continue;
			float x = w.getX(), y = w.getY() + (float) (1 - k) * 6, bw = w.getWidth(), bh = w.getHeight();
			String key = key(w), label = w.getMessage().getString();
			if (!(w instanceof AbstractButton)) {
				if (label.isBlank()) continue;
				if ("menu.game".equals(key) || "menu.paused".equals(key)) {
					// The "Spielmenü" title: centred bold text.
					Ui.bold(g, label, x + (bw - Ui.boldWidth(label, 11)) / 2, y + (bh - 13.75f) / 2, 11, Theme.alpha(Theme.TEXT, k));
					titleDrawn = true;
				} else {
					// Other text (e.g. the tip in the corner): small, wrapped to its box.
					drawWrapped(g, label, x, w.getY(), bw, bh, k);
				}
				continue;
			}
			top = Math.min(top, y);
			left = Math.min(left, x);
			right = Math.max(right, x + bw);
			boolean hover = w.isActive() && Kit.over(x, y, bw, bh, mx, my);
			double hv = Ui.anim("pause.classic." + (i++) + key, hover);
			double a = k * (w.isActive() ? 1 : 0.45);
			float r = Math.min(8, bh / 2.5f);
			if (RETURN.equals(key)) {
				Ui.shadow(g, x, y, bw, bh, r, 10, 3, Theme.alpha(Theme.ACCENT, (0.4 + 0.25 * hv) * a));
				Ui.chroma(g, x, y, bw, bh, r, a, 0.1 * hv);
			} else {
				boolean danger = DISCONNECT.contains(key);
				int tint = danger ? Theme.lerp(Theme.RAISED, 0xFF3A1820, hv) : Theme.RAISED;
				Ui.rect(g, x, y, bw, bh, r, Theme.alpha(tint, (0.72 + 0.23 * hv) * a));
				int line = danger ? Theme.lerp(0xFFFFFFFF, Theme.ERR, hv) : Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, hv);
				Ui.outline(g, x, y, bw, bh, r, Theme.alpha(line, (0.09 + 0.3 * hv) * a));
			}
			int textColor = RETURN.equals(key) ? 0xFFFFFFFF : Theme.lerp(Theme.TEXT, 0xFFFFFFFF, hv);
			String icon = iconFor(key);
			if (bw <= 24) {
				// Icon buttons: Lucide for vanilla's, the button's own sprite for mods'.
				if (icon != null) {
					float s = Math.min(11, bh - 8);
					Ui.icon(g, icon, x + (bw - s) / 2, y + (bh - s) / 2, s, Theme.alpha(textColor, a));
				} else if (w instanceof SpriteIconButtonAccessor sprite) {
					int sw = sprite.slopclient$spriteWidth(), sh = sprite.slopclient$spriteHeight();
					g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite.slopclient$sprite().get(w.isActive(), hover),
						Math.round(x + (bw - sw) / 2), Math.round(y + (bh - sh) / 2), sw, sh, Theme.alpha(0xFFFFFF, a));
				} else {
					String initial = label.isBlank() ? "?" : label.substring(0, 1).toUpperCase(Locale.ROOT);
					Ui.text(g, initial, x + (bw - Ui.width(initial, 8.5f)) / 2, y + (bh - 10.6f) / 2, 8.5f, Theme.alpha(textColor, a));
				}
				if (hover && !label.isBlank()) tooltip = new float[] {x + bw / 2, y};
				if (hover) tooltipText = label;
			} else {
				float size = bh < 18 ? 7.5f : 8.5f;
				String fitted = Ui.fit(label.isBlank() ? "…" : label, size, bw - 8);
				Ui.text(g, fitted, x + (bw - Ui.width(fitted, size)) / 2, y + (bh - size * 1.25f) / 2, size, Theme.alpha(textColor, a));
			}
			if (w.isActive()) hits.add(new Hit(x, y, bw, bh, () -> press(w)));
		}
		if (tooltip != null) drawTooltipAbove(g, tooltipText, tooltip[0], tooltip[1]);
		if (!titleDrawn && top != Float.MAX_VALUE) {
			String title = "Spielmenü";
			Ui.bold(g, title, (left + right - Ui.boldWidth(title, 11)) / 2, top - 22, 11, Theme.alpha(Theme.TEXT, k));
		}
	}

	/** Label of a hovered icon button, centred above it. */
	private void drawTooltipAbove(GuiGraphicsExtractor g, String label, float centreX, float top) {
		float size = 7.5f, w = Ui.width(label, size) + 14, h = 16;
		float x = Math.max(4, Math.min(width - 4 - w, centreX - w / 2)), y = top - h - 4;
		Ui.shadow(g, x, y, w, h, 6, 6, 2, 0x66000000);
		Ui.rect(g, x, y, w, h, 6, Theme.alpha(Theme.PANEL_SOLID, 0.96));
		Ui.outline(g, x, y, w, h, 6, Theme.LINE_2);
		Ui.text(g, label, x + 7, y + (h - size * 1.25f) / 2, size, Theme.TEXT);
	}

	/**
	 * Text in a vanilla text widget's box, word-wrapped at a small size. Aligned like the
	 * box sits: right when it hugs the right edge (vanilla's tip), else centred.
	 */
	private void drawWrapped(GuiGraphicsExtractor g, String text, float x, float y, float w, float h, double k) {
		float size = 7.5f, line = 10;
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\\n")) {
			StringBuilder current = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				String next = current.isEmpty() ? word : current + " " + word;
				if (Ui.width(next, size) > w && !current.isEmpty()) {
					lines.add(current.toString());
					current = new StringBuilder(word);
				} else {
					current = new StringBuilder(next);
				}
			}
			lines.add(current.toString());
		}
		boolean right = x + w > width - 24;
		float ty = y + h - lines.size() * line;
		for (String l : lines) {
			float lw = Ui.width(l, size);
			float lx = right ? x + w - lw : x + (w - lw) / 2;
			Ui.text(g, l, lx, ty, size, Theme.alpha(Theme.MUTED, k));
			ty += line;
		}
	}

	// ---------- input ----------

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SlopClient.clickGuiKey().matches(event)) {
			minecraft.gui.setScreen(new ClickGuiScreen(this));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// SDL3 sends 1 for a left click, legacy GLFW 0.
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
