package dev.slopclient.gui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.MenuShader;
import dev.slopclient.render.Theme;
import dev.slopclient.ui.Icon;
import dev.slopclient.ui.Kit;
import dev.slopclient.ui.Kit.Hit;
import dev.slopclient.ui.Ui;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.NoticeWithLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * "Einzelspieler" as a launcher page ("Modern" in the Einzelspieler setting): the glass
 * sidebar with "Welten" active, a compact list with one row per world (icon, name, mode,
 * version, last played, size), search and sorting, and an action bar for the selected
 * world: Spielen, Neue Welt, Bearbeiten, Ordner, Backup, Löschen (click twice).
 *
 * <p>Playing, editing, creating and backups run through vanilla's own flows
 * ({@code WorldOpenFlows}, {@link EditWorldScreen}, {@link CreateWorldScreen}), so version
 * warnings, backups before upgrades and locked worlds behave exactly like vanilla.
 * Backups go to {@code .minecraft/backups}, as vanilla's "Backup erstellen".
 */
public final class SlopWorldsScreen extends Screen {
	private static final float ROW_H = 30, ROW_GAP = 3, BAR_H = 26, TOOL_H = 22;
	private static final String[] SORTS = {"Zuletzt gespielt", "Name", "Größe"};
	/** Folder sizes survive reopening the screen; a world's size is measured once per session. */
	private static final Map<String, Long> SIZES = new ConcurrentHashMap<>();

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private List<LevelSummary> all = List.of();
	private List<LevelSummary> shown = List.of();
	private boolean loaded, closed;
	private String selected;
	private String deleteArmed;
	private long deleteArmedAt;
	private double scroll, scrollTarget;
	private EditBox search;
	private List<Hit> hits = new ArrayList<>();

	public SlopWorldsScreen(Screen parent) {
		super(Component.literal("Welten"));
		this.parent = parent;
	}

	private static String sort() {
		return SlopClient.modules().titleScreen.worldsSort.get();
	}

	// ---------- data ----------

	@Override
	protected void init() {
		closed = false;
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 0, 0, 100, 12, Component.literal("Suchen"));
		search.setBordered(false);
		search.setCanLoseFocus(false);
		search.setMaxLength(64);
		search.setTextColor(Theme.TEXT);
		search.setValue(query);
		search.setResponder(q -> {
			scrollTarget = 0;
			refilter();
		});
		addWidget(search);
		setInitialFocus(search);
		load();
	}

	private void load() {
		try {
			LevelStorageSource source = minecraft.getLevelSource();
			source.loadLevelSummaries(source.findLevelCandidates()).thenAccept(list -> minecraft.execute(() -> {
				if (closed) return;
				all = List.copyOf(list);
				loaded = true;
				for (LevelSummary w : all) {
					uploadIcon(w);
					measure(w.getLevelId());
				}
				refilter();
			}));
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not list the worlds", e);
			loaded = true;
		}
	}

	/** Folder size in the background; rows show "…" until it is known. */
	private void measure(String id) {
		if (SIZES.containsKey(id)) return;
		Path dir = minecraft.getLevelSource().getLevelPath(id);
		CompletableFuture.runAsync(() -> {
			long total = 0;
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path p : (Iterable<Path>) files::iterator) {
					try {
						BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
						if (a.isRegularFile()) total += a.size();
					} catch (IOException e) {
						// A file that vanished while walking (session.lock, region being saved).
					}
				}
			} catch (Exception e) {
				return;
			}
			SIZES.put(id, total);
			minecraft.execute(() -> {
				if (!closed && sort().equals("Größe")) refilter();
			});
		});
	}

	private void uploadIcon(LevelSummary w) {
		Path path = w.getIcon();
		if (path == null || !Files.isRegularFile(path)) return;
		try (InputStream in = Files.newInputStream(path)) {
			FaviconTexture tex = icons.computeIfAbsent(w.getLevelId(), id -> FaviconTexture.forWorld(minecraft.getTextureManager(), id));
			tex.upload(NativeImage.read(in));
		} catch (Exception e) {
			// No icon then; the row shows a tile.
		}
	}

	private void refilter() {
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		Comparator<LevelSummary> order = switch (sort()) {
			case "Name" -> Comparator.comparing((LevelSummary w) -> w.getLevelName().toLowerCase(Locale.ROOT));
			case "Größe" -> Comparator.comparingLong((LevelSummary w) -> -SIZES.getOrDefault(w.getLevelId(), -1L));
			default -> Comparator.comparingLong((LevelSummary w) -> -w.getLastPlayed());
		};
		shown = all.stream()
			.filter(w -> q.isEmpty() || w.getLevelName().toLowerCase(Locale.ROOT).contains(q) || w.getLevelId().toLowerCase(Locale.ROOT).contains(q))
			.sorted(order.thenComparing(LevelSummary::getLevelId)).toList();
		if (selected != null && shown.stream().noneMatch(w -> w.getLevelId().equals(selected))) selected = null;
		if (selected == null && !shown.isEmpty()) selected = shown.getFirst().getLevelId();
	}

	@Override
	public void removed() {
		closed = true;
		icons.values().forEach(FaviconTexture::close);
		icons.clear();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private LevelSummary current() {
		if (selected == null) return null;
		for (LevelSummary w : shown) if (w.getLevelId().equals(selected)) return w;
		return null;
	}

	// ---------- actions ----------

	private void play(LevelSummary w) {
		if (w == null || !w.primaryActionActive()) return;
		if (w instanceof LevelSummary.SymlinkLevelSummary) {
			minecraft.gui.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(() -> minecraft.gui.setScreen(this)));
			return;
		}
		minecraft.createWorldOpenFlows().openWorld(w.getLevelId(), () -> minecraft.gui.setScreen(this));
	}

	private void create() {
		CreateWorldScreen.openFresh(minecraft, () -> minecraft.gui.setScreen(this));
	}

	private void edit(LevelSummary w) {
		if (w == null || !w.canEdit()) return;
		String id = w.getLevelId();
		LevelStorageSource.LevelStorageAccess access;
		try {
			access = minecraft.getLevelSource().validateAndCreateAccess(id);
		} catch (IOException e) {
			SystemToast.onWorldAccessFailure(minecraft, id);
			SlopClient.LOG.error("Failed to access level {}", id, e);
			return;
		} catch (Exception e) {
			// Symlinked world folder outside the allowed paths.
			minecraft.gui.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(() -> minecraft.gui.setScreen(this)));
			return;
		}
		try {
			minecraft.gui.setScreen(EditWorldScreen.create(minecraft, access, result -> {
				access.safeClose();
				minecraft.gui.setScreen(this);
			}));
		} catch (Exception e) {
			access.safeClose();
			SystemToast.onWorldAccessFailure(minecraft, id);
			SlopClient.LOG.error("Failed to load world data {}", id, e);
		}
	}

	private void openFolder(LevelSummary w) {
		if (w != null) Blaze3D.openPath(minecraft.getLevelSource().getLevelPath(w.getLevelId()));
	}

	/** Vanilla's "Backup erstellen": a zip in .minecraft/backups and a toast with its size. */
	private void backup(LevelSummary w) {
		if (w == null || w.isLocked()) return;
		String id = w.getLevelId();
		LevelStorageSource.LevelStorageAccess access;
		try {
			access = minecraft.getLevelSource().createAccess(id);
		} catch (IOException e) {
			SystemToast.onWorldAccessFailure(minecraft, id);
			return;
		}
		EditWorldScreen.makeBackupAndShowToast(access).whenCompleteAsync((ok, error) -> {
			access.safeClose();
			minecraft.gui.setScreen(this);
		}, minecraft);
	}

	/** First click arms the button for three seconds, the second deletes. */
	private void delete(LevelSummary w) {
		if (w == null) return;
		String id = w.getLevelId();
		if (!deleteArmed(id)) {
			deleteArmed = id;
			deleteArmedAt = System.currentTimeMillis();
			return;
		}
		deleteArmed = null;
		try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(id)) {
			access.deleteLevel();
		} catch (IOException e) {
			SystemToast.onWorldDeleteFailure(minecraft, id);
			SlopClient.LOG.error("Failed to delete world {}", id, e);
		}
		SIZES.remove(id);
		FaviconTexture icon = icons.remove(id);
		if (icon != null) icon.close();
		all = all.stream().filter(x -> !x.getLevelId().equals(id)).toList();
		selected = null;
		refilter();
		load();
	}

	private boolean deleteArmed(String id) {
		return id.equals(deleteArmed) && System.currentTimeMillis() - deleteArmedAt < 3000;
	}

	private void setSort(String mode) {
		SlopClient.modules().titleScreen.worldsSort.set(mode);
		SlopClient.save();
		refilter();
	}

	private void switchToClassic() {
		SlopClient.modules().titleScreen.worldsStyle.set("Klassisch");
		SlopClient.save();
		minecraft.gui.setScreen(new SelectWorldScreen(parent));
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
		List<Kit.Entry> entries = List.of(
			new Kit.Entry("Start", Icon.HOUSE, false, true, this::onClose),
			new Kit.Entry("Welten", Icon.EARTH, true, true, null),
			new Kit.Entry("Server", Icon.SERVER, false, minecraft.level == null, () -> minecraft.gui.setScreen(new JoinMultiplayerScreen(parent))),
			new Kit.Entry("Screenshots", Icon.IMAGES, false, true, () -> minecraft.gui.setScreen(new ScreenshotGalleryScreen(this))),
			new Kit.Entry("Module", Icon.LAYOUT_GRID, false, true, () -> minecraft.gui.setScreen(new ClickGuiScreen(this))),
			new Kit.Entry("Einstellungen", Icon.SETTINGS, false, true, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))));
		String[] tooltip = Kit.rail(g, "worlds", entries, new Kit.Entry("Zurück", Icon.CHEVRON_LEFT, false, true, this::onClose), height, mouseX, mouseY, intro(0), hits);

		float pillBottom = Kit.playerPill(g, width, intro(0.1));
		String style = "Ansicht: Modern";
		Kit.pill(g, "worlds.style", style, width - 12 - Kit.pillWidth(style), pillBottom + 6, true, mouseX, mouseY, intro(0.2), hits, this::switchToClassic);

		float left = Kit.contentLeft(), right = width - 16;
		drawHeader(g, left);
		float toolY = 52;
		drawToolbar(g, left, right, toolY, mouseX, mouseY);
		float listTop = toolY + TOOL_H + 10, listBottom = height - 14 - BAR_H - 10;
		drawList(g, left, right, listTop, listBottom, mouseX, mouseY);
		drawActions(g, left, right, height - 14 - BAR_H, mouseX, mouseY);
		Kit.tooltip(g, tooltip);
	}

	private void drawHeader(GuiGraphicsExtractor g, float left) {
		double k = intro(0.05);
		Ui.bold(g, "Welten", left, 14 + (float) (1 - k) * 6, 18, Theme.alpha(Theme.TEXT, k));
		long total = 0;
		boolean complete = true;
		for (LevelSummary w : all) {
			Long s = SIZES.get(w.getLevelId());
			if (s == null) complete = false;
			else total += s;
		}
		String sub = !loaded ? "Lädt …" : all.size() == 1 ? "1 Welt" : all.size() + " Welten";
		if (loaded && !all.isEmpty()) sub += " · " + (complete ? "" : "≥ ") + size(total);
		Ui.text(g, sub, left, 38, 7.5f, Theme.alpha(Theme.MUTED, k));
	}

	private void drawToolbar(GuiGraphicsExtractor g, float left, float right, float y, int mx, int my) {
		double k = intro(0.15);
		float room = right - left;
		// Search field: glass box with the vanilla EditBox (SDL3 text input) inside.
		float sw = Math.max(90, Math.min(190, room * 0.36f));
		boolean focused = search.isFocused();
		Ui.rect(g, left, y, sw, TOOL_H, TOOL_H / 2, Theme.alpha(Theme.PANEL_SOLID, 0.8 * k));
		Ui.outline(g, left, y, sw, TOOL_H, TOOL_H / 2, focused && !search.getValue().isEmpty() ? Theme.alpha(Theme.ACCENT_2, 0.55 * k) : Theme.alpha(0xFFFFFF, 0.12 * k));
		Ui.icon(g, Icon.SEARCH, left + 8, y + 5, 12, Theme.alpha(Theme.MUTED, k));
		int ex = Math.round(left + 25), ew = Math.round(sw - 25 - 22);
		search.setRectangle(ew, 10, ex, Math.round(y + (TOOL_H - 8) / 2));
		if (search.getValue().isEmpty()) Ui.text(g, "Welten suchen", ex, y + (TOOL_H - 9) / 2, 7.5f, Theme.alpha(Theme.DIM, k));
		search.extractRenderState(g, mx, my, 0);
		if (!search.getValue().isEmpty()) {
			float cx = left + sw - 18;
			boolean hover = Kit.over(cx, y + 3, 16, 16, mx, my);
			Ui.icon(g, Icon.X, cx + 2, y + 5, 12, Theme.alpha(hover ? Theme.TEXT : Theme.MUTED, k));
			hits.add(new Hit(cx, y + 3, 16, 16, () -> search.setValue("")));
		}

		// Sorting as a segmented control.
		float x = left + sw + 10;
		float segW = 0;
		for (String s : SORTS) segW += Ui.width(s, 7.5f) + 16;
		boolean segFits = x + segW + 4 + 90 < right;
		if (segFits) {
			Ui.rect(g, x, y, segW + 4, TOOL_H, TOOL_H / 2, Theme.alpha(Theme.PANEL_SOLID, 0.6 * k));
			Ui.outline(g, x, y, segW + 4, TOOL_H, TOOL_H / 2, Theme.alpha(0xFFFFFF, 0.08 * k));
			float sx = x + 2;
			for (String s : SORTS) {
				float w = Ui.width(s, 7.5f) + 16;
				boolean on = sort().equals(s);
				double hv = Ui.anim("worlds.sort." + s, Kit.over(sx, y + 2, w, TOOL_H - 4, mx, my));
				if (on) {
					Ui.rect(g, sx, y + 2, w, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(Theme.ACCENT, 0.28 * k));
					Ui.outline(g, sx, y + 2, w, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(Theme.ACCENT_2, 0.4 * k));
				} else if (hv > 0) {
					Ui.rect(g, sx, y + 2, w, TOOL_H - 4, (TOOL_H - 4) / 2, Theme.alpha(0xFFFFFF, 0.06 * hv * k));
				}
				Ui.text(g, s, sx + 8, y + (TOOL_H - 9.4f) / 2, 7.5f, Theme.alpha(on ? 0xFFFFFFFF : Theme.lerp(Theme.MUTED, Theme.TEXT, hv), k));
				hits.add(new Hit(sx, y + 2, w, TOOL_H - 4, () -> setSort(s)));
				sx += w;
			}
		}

		// "Neue Welt" on the right.
		String label = "Neue Welt";
		float bw = Ui.width(label, 8) + 32;
		button(g, "worlds.create", label, Icon.PLUS, right - bw, y, bw, TOOL_H, Style.PRIMARY, true, mx, my, k, this::create);
	}

	private enum Style { PRIMARY, GLASS, DANGER }

	/** Compact button for the toolbar and the action bar. */
	private void button(GuiGraphicsExtractor g, String key, String label, String icon, float x, float y, float w, float h, Style style, boolean enabled, int mx, int my, double k, Runnable action) {
		boolean hover = enabled && Kit.over(x, y, w, h, mx, my);
		double hv = Ui.anim(key, hover);
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
				Ui.rect(g, x, y, w, h, r, Theme.alpha(Theme.lerp(Theme.RAISED, 0xFF3A1820, Math.max(hv, deleteArmed(selected == null ? "" : selected) ? 1 : 0)), (0.68 + 0.27 * hv) * a));
				Ui.outline(g, x, y, w, h, r, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ERR, hv), (0.08 + 0.3 * hv) * a));
			}
		}
		int color = switch (style) {
			case PRIMARY -> 0xFFFFFFFF;
			case GLASS -> Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
			case DANGER -> Theme.lerp(Theme.MUTED, Theme.ERR, Math.max(hv, deleteArmed(selected == null ? "" : selected) ? 1 : 0));
		};
		Ui.icon(g, icon, x + 9, y + (h - 11) / 2, 11, Theme.alpha(color, a));
		int text = style == Style.GLASS ? Theme.lerp(Theme.TEXT, 0xFFFFFFFF, hv) : color;
		Ui.text(g, Ui.fit(label, 8, w - 30), x + 24, y + (h - 10) / 2, 8, Theme.alpha(text, a));
		if (enabled) hits.add(new Hit(x, y, w, h, action));
	}

	private float contentHeight() {
		return shown.size() * (ROW_H + ROW_GAP) - ROW_GAP;
	}

	private void drawList(GuiGraphicsExtractor g, float left, float right, float top, float bottom, int mx, int my) {
		double k = intro(0.25);
		float viewH = bottom - top;
		double max = Math.max(0, contentHeight() - viewH);
		scrollTarget = Math.max(0, Math.min(scrollTarget, max));
		scroll += (scrollTarget - scroll) * 0.35;
		if (Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;

		if (loaded && shown.isEmpty()) {
			boolean filtered = !search.getValue().isBlank();
			String head = filtered ? "Keine Welt gefunden" : "Noch keine Welten";
			String sub = filtered ? "Keine Welt passt zu „" + search.getValue().trim() + "“." : "Erstelle deine erste Welt mit „Neue Welt“.";
			float cx = (left + right) / 2, cy = top + viewH / 2 - 20;
			Ui.icon(g, filtered ? Icon.SEARCH : Icon.EARTH, cx - 10, cy - 24, 20, Theme.alpha(Theme.DIM, k));
			Ui.bold(g, head, cx - Ui.boldWidth(head, 11) / 2, cy, 11, Theme.alpha(Theme.TEXT, k));
			Ui.text(g, sub, cx - Ui.width(sub, 7.5f) / 2, cy + 18, 7.5f, Theme.alpha(Theme.MUTED, k));
			return;
		}

		boolean scrollbar = max > 0;
		float rowW = right - left - (scrollbar ? 8 : 0);
		g.enableScissor(Math.round(left) - 2, Math.round(top), Math.round(right) + 2, Math.round(bottom));
		boolean inView = my >= top && my < bottom;
		for (int i = 0; i < shown.size(); i++) {
			float y = top + i * (ROW_H + ROW_GAP) - (float) scroll;
			if (y + ROW_H < top || y > bottom) continue;
			LevelSummary w = shown.get(i);
			drawRow(g, w, left, y, rowW, inView && Kit.over(left, y, rowW, ROW_H, mx, my), k);
			if (inView) {
				float hy = Math.max(top, y), hh = Math.min(bottom, y + ROW_H) - hy;
				hits.add(new Hit(left, hy, rowW, hh, () -> click(w)));
			}
		}
		g.disableScissor();

		if (scrollbar) {
			float trackH = viewH, barH = Math.max(18, trackH * viewH / contentHeight());
			float barY = top + (float) ((trackH - barH) * (scroll / max));
			boolean hot = Kit.over(right - 5, top, 5, viewH, mx, my);
			double hv = Ui.anim("worlds.scrollbar", hot);
			float tw = 3 + (float) hv;
			Ui.rect(g, right - 4 + (4 - tw) / 2, barY, tw, barH, tw / 2, Theme.alpha(0xFFFFFF, (0.22 + 0.25 * hv) * k));
		}
	}

	private long lastClick;
	private String lastClicked;

	/** Click selects; a second click on the same row within 400 ms plays it. */
	private void click(LevelSummary w) {
		long now = System.currentTimeMillis();
		boolean dbl = w.getLevelId().equals(lastClicked) && now - lastClick < 400;
		lastClicked = w.getLevelId();
		lastClick = now;
		if (!w.getLevelId().equals(selected)) deleteArmed = null;
		selected = w.getLevelId();
		if (dbl) play(w);
	}

	private void drawRow(GuiGraphicsExtractor g, LevelSummary w, float x, float y, float rw, boolean hover, double k) {
		String id = w.getLevelId();
		boolean sel = id.equals(selected);
		double hv = Ui.anim("worlds.row." + id, hover);
		double sv = Ui.anim("worlds.sel." + id, sel);
		Ui.rect(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.PANEL_SOLID, (0.5 + 0.12 * hv + 0.15 * sv) * k));
		if (sv > 0.01) Ui.rect(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.ACCENT, 0.16 * sv * k));
		Ui.outline(g, x, y, rw, ROW_H, 9, Theme.alpha(Theme.lerp(0xFFFFFFFF, Theme.ACCENT_2, Math.max(hv * 0.6, sv)), (0.07 + 0.12 * hv + 0.3 * sv) * k));
		if (sv > 0.01) Ui.chroma(g, x + 1.5f, y + 8, 2.5f, (ROW_H - 16) * (float) sv, 1.25f, k, 0);

		// Icon, or a chroma tile with the globe.
		float ix = x + 6, iy = y + 4, is = ROW_H - 8;
		FaviconTexture tex = icons.get(id);
		if (tex != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex.textureLocation(), Math.round(ix), Math.round(iy), 0, 0, Math.round(is), Math.round(is), 64, 64, 64, 64, Theme.alpha(0xFFFFFF, (w.isDisabled() ? 0.5 : 1) * k));
		} else {
			Ui.rect(g, ix, iy, is, is, 6, Theme.alpha(Theme.chroma(-(Math.abs(id.hashCode()) % 100) / 100.0), 0.75 * k));
			Ui.icon(g, Icon.EARTH, ix + (is - 12) / 2, iy + (is - 12) / 2, 12, Theme.alpha(0xFFFFFF, k));
		}

		// Right column: last played and size.
		String when = SlopTitleScreen.ago(w.getLastPlayed());
		Long bytes = SIZES.get(id);
		String size = bytes == null ? "…" : size(bytes);
		float rx = x + rw - 10;
		Ui.text(g, when, rx - Ui.width(when, 7.5f), y + 5, 7.5f, Theme.alpha(Theme.MUTED, k));
		Ui.text(g, size, rx - Ui.width(size, 7), y + 17, 7, Theme.alpha(Theme.DIM, k));
		float rightCol = Math.max(Ui.width(when, 7.5f), Ui.width(size, 7)) + 16;

		// Name and info line.
		float tx = ix + is + 8, room = rw - (tx - x) - rightCol;
		Ui.text(g, Ui.fit(w.getLevelName(), 8.5f, room), tx, y + 4, 8.5f, Theme.alpha(w.isDisabled() ? Theme.MUTED : Theme.TEXT, k));
		float cx = tx;
		float iy2 = y + 17;
		for (String[] part : info(w)) {
			if (cx > tx) cx += Ui.text(g, " · ", cx, iy2, 7, Theme.alpha(Theme.DIM, k));
			int color = switch (part[1]) {
				case "err" -> Theme.ERR;
				case "warn" -> 0xFFFBBF24;
				default -> Theme.MUTED;
			};
			String text = Ui.fit(part[0], 7, tx + room - cx);
			if (text.isEmpty()) break;
			cx += Ui.text(g, text, cx, iy2, 7, Theme.alpha(color, k));
			if (cx >= tx + room) break;
		}
	}

	/** Info segments {text, tone}: problems first, then mode, cheats, experimental and version. */
	private static List<String[]> info(LevelSummary w) {
		List<String[]> out = new ArrayList<>();
		if (w.isLocked()) {
			out.add(new String[] {"Wird gerade von einem anderen Spiel benutzt", "err"});
			return out;
		}
		if (w.requiresManualConversion()) {
			out.add(new String[] {"Muss erst konvertiert werden", "err"});
			return out;
		}
		if (!w.isCompatible()) {
			out.add(new String[] {"Inkompatible Version " + w.getWorldVersionName().getString(), "err"});
			return out;
		}
		if (w.isHardcore()) out.add(new String[] {"Hardcore", "err"});
		else out.add(new String[] {mode(w.getGameMode().getName()), ""});
		if (w.hasCommands()) out.add(new String[] {"Cheats", ""});
		if (w.isExperimental()) out.add(new String[] {"Experimentell", "warn"});
		String version = w.getWorldVersionName().getString();
		boolean current = version.equals(SharedConstants.getCurrentVersion().name());
		out.add(new String[] {version, w.isDowngrade() ? "err" : w.shouldBackup() && !current ? "warn" : ""});
		return out;
	}

	private static String mode(String name) {
		return switch (name) {
			case "survival" -> "Überleben";
			case "creative" -> "Kreativ";
			case "adventure" -> "Abenteuer";
			case "spectator" -> "Zuschauer";
			default -> name;
		};
	}

	static String size(long bytes) {
		double mb = bytes / 1048576.0;
		if (mb < 0.1) return "< 0,1 MB";
		if (mb < 10) return String.format(Locale.GERMANY, "%.1f MB", mb);
		if (mb < 1024) return Math.round(mb) + " MB";
		return String.format(Locale.GERMANY, "%.1f GB", mb / 1024);
	}

	private void drawActions(GuiGraphicsExtractor g, float left, float right, float y, int mx, int my) {
		double k = intro(0.35);
		LevelSummary w = current();
		boolean any = w != null;
		float x = left;
		String playLabel = w != null && w.requiresFileFixing() ? "Upgraden & spielen" : "Spielen";
		float pw = Ui.width(playLabel, 8) + 34;
		button(g, "worlds.play", playLabel, Icon.PLAY, x, y, pw, BAR_H, Style.PRIMARY, any && w.primaryActionActive(), mx, my, k, () -> play(current()));
		x += pw + 8;
		record Act(String key, String label, String icon, Style style, boolean enabled, Runnable run) {
		}
		boolean armed = any && deleteArmed(w.getLevelId());
		List<Act> acts = List.of(
			new Act("edit", "Bearbeiten", Icon.PENCIL, Style.GLASS, any && w.canEdit(), () -> edit(current())),
			new Act("folder", "Ordner", Icon.FOLDER_OPEN, Style.GLASS, any, () -> openFolder(current())),
			new Act("backup", "Backup", Icon.ARCHIVE, Style.GLASS, any && !w.isLocked(), () -> backup(current())),
			new Act("delete", armed ? "Wirklich löschen?" : "Löschen", Icon.TRASH_2, Style.DANGER, any && w.canDelete() && !w.isLocked(), () -> delete(current())));
		float room = right - x;
		float full = 0;
		for (Act a : acts) full += Ui.width(a.label(), 8) + 32 + 6;
		boolean iconsOnly = full > room;
		for (Act a : acts) {
			float bw = iconsOnly ? BAR_H + 4 : Ui.width(a.label(), 8) + 32;
			if (iconsOnly) {
				boolean hover = a.enabled() && Kit.over(x, y, bw, BAR_H, mx, my);
				double hv = Ui.anim("worlds.act." + a.key(), hover);
				double al = (a.enabled() ? 1 : 0.4) * k;
				Ui.rect(g, x, y, bw, BAR_H, 10, Theme.alpha(a.style() == Style.DANGER && armed ? 0xFF3A1820 : Theme.RAISED, (0.68 + 0.27 * hv) * al));
				Ui.outline(g, x, y, bw, BAR_H, 10, Theme.alpha(0xFFFFFF, (0.08 + 0.12 * hv) * al));
				int c = a.style() == Style.DANGER ? Theme.lerp(Theme.MUTED, Theme.ERR, armed ? 1 : hv) : Theme.lerp(Theme.MUTED, Theme.TEXT, hv);
				Ui.icon(g, a.icon(), x + (bw - 12) / 2, y + (BAR_H - 12) / 2, 12, Theme.alpha(c, al));
				if (a.enabled()) hits.add(new Hit(x, y, bw, BAR_H, a.run()));
			} else {
				button(g, "worlds.act." + a.key(), a.label(), a.icon(), x, y, bw, BAR_H, a.style(), a.enabled(), mx, my, k, a.run());
			}
			x += bw + 6;
		}
		String hint = "Doppelklick spielt · Entf löscht";
		if (x + Ui.width(hint, 6.5f) + 10 < right) Ui.text(g, hint, right - Ui.width(hint, 6.5f), y + (BAR_H - 8) / 2, 6.5f, Theme.alpha(Theme.DIM, k));
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Minecraft 26.3 with SDL3 sends 1 for a left click, 0 with legacy GLFW.
		if (event.button() != 0 && event.button() != 1) return super.mouseClicked(event, doubleClick);
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(event.x(), event.y())) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		scrollTarget -= scrollY * (ROW_H + ROW_GAP) * 1.5;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (SlopClient.clickGuiKey().matches(event)) {
			minecraft.gui.setScreen(new ClickGuiScreen(this));
			return true;
		}
		if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
			moveSelection(key == InputConstants.KEY_DOWN ? 1 : -1);
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			play(current());
			return true;
		}
		if (key == InputConstants.KEY_DELETE && search.getValue().isEmpty() && current() != null && !current().isLocked()) {
			delete(current());
			return true;
		}
		return super.keyPressed(event);
	}

	private void moveSelection(int dir) {
		if (shown.isEmpty()) return;
		int i = 0;
		for (int j = 0; j < shown.size(); j++) if (shown.get(j).getLevelId().equals(selected)) i = j;
		i = Math.max(0, Math.min(shown.size() - 1, i + dir));
		selected = shown.get(i).getLevelId();
		deleteArmed = null;
		// Keep the selected row in view.
		float top = 52 + TOOL_H + 10, viewH = height - 14 - BAR_H - 10 - top;
		float rowTop = i * (ROW_H + ROW_GAP);
		if (rowTop < scrollTarget) scrollTarget = rowTop;
		else if (rowTop + ROW_H > scrollTarget + viewH) scrollTarget = rowTop + ROW_H - viewH;
	}
}
