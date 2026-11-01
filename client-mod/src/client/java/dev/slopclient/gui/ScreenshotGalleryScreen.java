package dev.slopclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The instance's screenshots in game: a grid of thumbnails grouped like the launcher's
 * gallery, and a viewer with zoom (mouse wheel), dragging, arrow keys, "show in folder"
 * and delete. Pictures are decoded on a background thread; only the open one is kept
 * at full size.
 */
public final class ScreenshotGalleryScreen extends Screen {
	private static final int TOP = 40, TILE_W = 128, TILE_H = 72, LABEL_H = 22, GAP = 8;
	/** Thumbnails kept on the GPU at most (about 37 KB each). */
	private static final int MAX_THUMBS = 160;
	private static final float MAX_ZOOM = 8;

	/** One decoding thread, so a big folder does not slow the game down. */
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SlopClient screenshot loader");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private record Picture(Identifier id, int w, int h) {
	}

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private final File dir;
	private List<File> files = List.of();
	private boolean newestFirst = true;
	private boolean closed;

	/** File -> thumbnail texture, least recently drawn first. */
	private final LinkedHashMap<File, Picture> thumbs = new LinkedHashMap<>(16, 0.75f, true);
	private final Set<File> loading = new HashSet<>();
	private int nextTexture;
	private double scroll, scrollTarget;

	/** Index of the picture in the viewer, -1 for the grid. */
	private int open = -1;
	private Picture full;
	private File fullFile;
	private float zoom = 1, panX, panY;
	private boolean confirmDelete;
	private long confirmAt;
	private String hint = "";
	private String flash = "";
	private long flashAt;

	public ScreenshotGalleryScreen(Screen parent) {
		super(Component.literal("Screenshots"));
		this.parent = parent;
		this.dir = new File(Minecraft.getInstance().gameDirectory, "screenshots");
	}

	@Override
	protected void init() {
		reload();
	}

	private void reload() {
		File[] found = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png") && !n.startsWith("slopclient-selftest"));
		List<File> list = new ArrayList<>(found == null ? List.of() : Arrays.asList(found));
		Comparator<File> byAge = Comparator.comparingLong(File::lastModified);
		list.sort(newestFirst ? byAge.reversed() : byAge);
		files = list;
		if (open >= files.size()) open = files.size() - 1;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (open >= 0) {
			closeViewer();
			return;
		}
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		closed = true;
		for (Picture p : thumbs.values()) release(p);
		thumbs.clear();
		releaseFull();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, 0x7009080E, 0xA009080E);
	}

	// ---------- textures ----------

	private Identifier newId() {
		return Identifier.fromNamespaceAndPath(SlopClient.ID, "screenshot_gallery/" + (nextTexture++));
	}

	private void release(Picture p) {
		if (p != null) minecraft.getTextureManager().release(p.id());
	}

	private void releaseFull() {
		release(full);
		full = null;
		fullFile = null;
	}

	/** Decodes `file` in the background, scaled to fit `maxW` x `maxH` (0 = full size), and hands it to `done` on the render thread. */
	private void decode(File file, int maxW, int maxH, java.util.function.Consumer<Picture> done) {
		LOADER.execute(() -> {
			if (closed) return;
			NativeImage image;
			try (InputStream in = Files.newInputStream(file.toPath())) {
				NativeImage source = NativeImage.read(in);
				if (maxW > 0 && (source.getWidth() > maxW || source.getHeight() > maxH)) {
					float scale = Math.min(maxW / (float) source.getWidth(), maxH / (float) source.getHeight());
					int w = Math.max(1, Math.round(source.getWidth() * scale)), h = Math.max(1, Math.round(source.getHeight() * scale));
					image = new NativeImage(w, h, false);
					source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), image);
					source.close();
				} else {
					image = source;
				}
			} catch (Exception e) {
				SlopClient.LOG.warn("Could not read screenshot {}", file, e);
				minecraft.execute(() -> done.accept(null));
				return;
			}
			NativeImage ready = image;
			minecraft.execute(() -> {
				if (closed) {
					ready.close();
					return;
				}
				Identifier id = newId();
				minecraft.getTextureManager().register(id, new DynamicTexture(() -> "SlopClient screenshot " + file.getName(), ready));
				done.accept(new Picture(id, ready.getWidth(), ready.getHeight()));
			});
		});
	}

	private Picture thumb(File file) {
		Picture p = thumbs.get(file);
		if (p == null && loading.add(file)) {
			decode(file, TILE_W * 2, TILE_H * 2, pic -> {
				loading.remove(file);
				if (pic == null) return;
				thumbs.put(file, pic);
				// Forget the thumbnails that were drawn longest ago.
				while (thumbs.size() > MAX_THUMBS) {
					var eldest = thumbs.entrySet().iterator().next();
					release(eldest.getValue());
					thumbs.remove(eldest.getKey());
				}
			});
		}
		return p;
	}

	// ---------- layout ----------

	private int x0() {
		return (width - totalWidth()) / 2;
	}

	private int totalWidth() {
		return Math.min(width - 20, 720);
	}

	private int bottom() {
		return height - 26;
	}

	private int columns() {
		return Math.max(1, (totalWidth() - 12 + GAP) / (TILE_W + GAP));
	}

	private int gridLeft() {
		int cols = columns();
		return x0() + (totalWidth() - cols * TILE_W - (cols - 1) * GAP) / 2;
	}

	private int gridTop() {
		return TOP + 26;
	}

	private int rowH() {
		return TILE_H + LABEL_H + GAP;
	}

	private int maxScroll() {
		int rows = (files.size() + columns() - 1) / columns();
		return Math.max(0, rows * rowH() - GAP - (bottom() - 6 - gridTop()));
	}

	/** Header buttons: [x, y, w, h] for "Neueste/Älteste", "Ordner öffnen" (grid) or the viewer's actions. */
	private List<int[]> headerButtons(String... labels) {
		List<int[]> out = new ArrayList<>();
		int x = x0() + totalWidth() - 7, y = TOP + 5;
		for (int i = labels.length - 1; i >= 0; i--) {
			int w = Draw.width(labels[i]) + 12;
			x -= w;
			out.add(0, new int[] {x, y, w, 14});
			x -= 4;
		}
		return out;
	}

	private String[] gridLabels() {
		return new String[] {newestFirst ? "Neueste zuerst" : "Älteste zuerst", "Ordner öffnen"};
	}

	private String[] viewerLabels() {
		return new String[] {"Im Ordner zeigen", confirmDelete ? "Wirklich löschen" : "Löschen", "Zurück"};
	}

	// ---------- drawing ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		double t = (System.currentTimeMillis() - openedAt) / 1000.0;
		Chrome.titleBar(g, minecraft, Theme.ease(t / 0.5), "Screenshots");
		if (confirmDelete && System.currentTimeMillis() - confirmAt > 3000) confirmDelete = false;
		hint = "";
		if (open >= 0) drawViewer(g, mouseX, mouseY);
		else drawGrid(g, mouseX, mouseY);
		boolean flashing = System.currentTimeMillis() - flashAt < 2500;
		String line = flashing ? flash : !hint.isEmpty() ? hint
			: open >= 0 ? "← → blättern · Mausrad: zoomen · Ziehen: verschieben · Entf: löschen · Esc: zurück"
			: "Klick: ansehen · Mausrad: scrollen · F2 im Spiel macht neue";
		Draw.text(g, ellipsize(line, width - 20), 10, height - 18, flashing ? Theme.ACCENT_2 : hint.isEmpty() ? Theme.DIM : Theme.MUTED);
	}

	private void panel(GuiGraphicsExtractor g, String title) {
		int x = x0(), w = totalWidth(), h = bottom() - TOP;
		Draw.glow(g, x, TOP, w, h, 5, 0xFF000000, 0.35);
		Draw.round(g, x, TOP, w, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.86));
		Draw.roundOutline(g, x, TOP, w, h, 7, Theme.LINE_2);
		g.fill(x + 7, TOP, x + w - 7, TOP + 2, Theme.chroma(0));
		Draw.shadowText(g, ellipsize(title, w - 220), x + 7, TOP + 8, Theme.TEXT);
	}

	private void button(GuiGraphicsExtractor g, int[] b, String label, boolean danger, int mx, int my) {
		boolean hover = Draw.inside(mx, my, b[0], b[1], b[2], b[3]);
		if (danger) Draw.round(g, b[0], b[1], b[2], b[3], 5, Theme.alpha(Theme.ERR, hover ? 0.55 : 0.4));
		else Draw.round(g, b[0], b[1], b[2], b[3], 5, hover ? 0x2CFFFFFF : 0x16FFFFFF);
		Draw.text(g, label, b[0] + 6, b[1] + 3, danger ? 0xFFFFFFFF : Theme.TEXT);
	}

	private void drawGrid(GuiGraphicsExtractor g, int mx, int my) {
		long bytes = files.stream().mapToLong(File::length).sum();
		panel(g, files.size() + (files.size() == 1 ? " Screenshot" : " Screenshots") + (files.isEmpty() ? "" : " · " + size(bytes)));
		String[] labels = gridLabels();
		List<int[]> buttons = headerButtons(labels);
		for (int i = 0; i < labels.length; i++) button(g, buttons.get(i), labels[i], false, mx, my);

		if (files.isEmpty()) {
			Draw.scaledText(g, "Noch keine Screenshots. Drück F2, dann erscheinen sie hier.", x0() + 7, gridTop() + 4, 0.85f, Theme.DIM, false);
			return;
		}
		scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
		scroll += (scrollTarget - scroll) * 0.35;
		if (Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;

		int top = gridTop(), bottom = bottom() - 6, cols = columns(), left = gridLeft();
		g.enableScissor(x0(), top, x0() + totalWidth(), bottom);
		for (int i = 0; i < files.size(); i++) {
			int row = i / cols, col = i % cols;
			int x = left + col * (TILE_W + GAP), y = top + row * rowH() - (int) Math.round(scroll);
			if (y + rowH() < top || y > bottom) continue;
			File file = files.get(i);
			boolean hover = my >= top && my < bottom && Draw.inside(mx, my, x, y, TILE_W, TILE_H + LABEL_H);
			if (hover) Draw.chromaRing(g, x - 1, y - 1, TILE_W + 2, TILE_H + 2, 1);
			Draw.round(g, x, y, TILE_W, TILE_H, 5, 0x22000000);
			Picture p = thumb(file);
			if (p != null) drawCover(g, p, x, y, TILE_W, TILE_H, hover ? 1 : 0.92);
			else Draw.round(g, x, y, TILE_W, TILE_H, 5, Theme.alpha(0xFFFFFF, 0.03 + 0.02 * Math.sin(System.currentTimeMillis() / 300.0 + i)));
			String name = file.getName().replaceAll("(?i)\\.png$", "");
			Draw.scaledText(g, ellipsize(name, (int) (TILE_W / 0.8f)), x + 1, y + TILE_H + 4, 0.8f, hover ? Theme.TEXT : Theme.MUTED, false);
			Draw.scaledText(g, relative(file.lastModified()) + " · " + size(file.length()), x + 1, y + TILE_H + 13, 0.7f, Theme.DIM, false);
			if (hover) hint = file.getName() + " · " + date(file.lastModified());
		}
		g.disableScissor();
		// Scrollbar
		int max = maxScroll();
		if (max > 0) {
			int trackH = bottom - top, barH = Math.max(20, trackH * trackH / (trackH + max));
			int barY = top + (int) ((trackH - barH) * (scroll / max));
			Draw.round(g, x0() + totalWidth() - 5, barY, 3, barH, 1, 0x40FFFFFF);
		}
	}

	/** Fills the box with the picture, cropped to keep its aspect ratio. */
	private static void drawCover(GuiGraphicsExtractor g, Picture p, int x, int y, int w, int h, double alpha) {
		float scale = Math.max(w / (float) p.w(), h / (float) p.h());
		int srcW = Math.min(p.w(), Math.round(w / scale)), srcH = Math.min(p.h(), Math.round(h / scale));
		g.blit(RenderPipelines.GUI_TEXTURED, p.id(), x, y, (p.w() - srcW) / 2f, (p.h() - srcH) / 2f, w, h, srcW, srcH, p.w(), p.h(), Theme.alpha(0xFFFFFF, alpha));
	}

	private int[] stage() {
		int x = x0() + 6, y = TOP + 24, w = totalWidth() - 12, h = bottom() - 6 - y;
		return new int[] {x, y, w, h};
	}

	/** Size and position of the picture on screen for the current zoom and pan: [x, y, w, h] (floats as doubles). */
	private double[] placement(Picture p) {
		int[] s = stage();
		double fit = Math.min(s[2] / (double) p.w(), s[3] / (double) p.h());
		double w = p.w() * fit * zoom, h = p.h() * fit * zoom;
		return new double[] {s[0] + (s[2] - w) / 2 + panX, s[1] + (s[3] - h) / 2 + panY, w, h};
	}

	private void drawViewer(GuiGraphicsExtractor g, int mx, int my) {
		File file = files.get(open);
		Picture p = full != null && file.equals(fullFile) ? full : thumbs.get(file);
		if (fullFile == null || !fullFile.equals(file)) loadFull(file);

		String dims = full != null && file.equals(fullFile) ? full.w() + " × " + full.h() : "…";
		panel(g, file.getName().replaceAll("(?i)\\.png$", "") + "  ·  " + (open + 1) + " von " + files.size());
		String[] labels = viewerLabels();
		List<int[]> buttons = headerButtons(labels);
		for (int i = 0; i < labels.length; i++) button(g, buttons.get(i), labels[i], i == 1 && confirmDelete, mx, my);
		Draw.scaledText(g, date(file.lastModified()) + " · " + dims + " · " + size(file.length()) + (zoom > 1.001f ? " · " + Math.round(zoom * 100) + " %" : ""),
			x0() + 7, TOP + 17, 0.75f, Theme.DIM, false);

		int[] s = stage();
		Draw.round(g, s[0], s[1], s[2], s[3], 5, 0x30000000);
		if (p == null) {
			Draw.scaledText(g, "Lädt …", s[0] + s[2] / 2f - 14, s[1] + s[3] / 2f - 3, 0.85f, Theme.DIM, false);
		} else {
			clampPan(p);
			double[] at = placement(p);
			// Only the visible part goes through blit, so a 4x zoom stays sharp and cheap.
			double vx0 = Math.max(s[0], at[0]), vy0 = Math.max(s[1], at[1]);
			double vx1 = Math.min(s[0] + s[2], at[0] + at[2]), vy1 = Math.min(s[1] + s[3], at[1] + at[3]);
			if (vx1 > vx0 && vy1 > vy0) {
				double px = p.w() / at[2], py = p.h() / at[3];
				float u = (float) ((vx0 - at[0]) * px), v = (float) ((vy0 - at[1]) * py);
				int dw = (int) Math.round(vx1 - vx0), dh = (int) Math.round(vy1 - vy0);
				int sw = Math.max(1, (int) Math.round(dw * px)), sh = Math.max(1, (int) Math.round(dh * py));
				g.blit(RenderPipelines.GUI_TEXTURED, p.id(), (int) Math.round(vx0), (int) Math.round(vy0), u, v, dw, dh, sw, sh, p.w(), p.h(), 0xFFFFFFFF);
			}
		}
		// Side arrows
		arrow(g, s[0] + 4, s[1] + s[3] / 2 - 10, "<", open > 0, mx, my);
		arrow(g, s[0] + s[2] - 24, s[1] + s[3] / 2 - 10, ">", open < files.size() - 1, mx, my);
	}

	private void arrow(GuiGraphicsExtractor g, int x, int y, String label, boolean enabled, int mx, int my) {
		if (!enabled) return;
		boolean hover = Draw.inside(mx, my, x, y, 20, 20);
		Draw.round(g, x, y, 20, 20, 10, Theme.alpha(Theme.PANEL_SOLID, hover ? 0.95 : 0.7));
		Draw.roundOutline(g, x, y, 20, 20, 10, hover ? Theme.ACCENT_2 : Theme.LINE_2);
		Draw.text(g, label, x + 10 - Draw.width(label) / 2, y + 6, Theme.TEXT);
	}

	private void loadFull(File file) {
		releaseFull();
		fullFile = file;
		zoom = 1;
		panX = panY = 0;
		decode(file, 0, 0, pic -> {
			if (pic == null) return;
			if (!file.equals(fullFile) || closed) {
				release(pic);
				return;
			}
			full = pic;
		});
	}

	private void clampPan(Picture p) {
		int[] s = stage();
		double[] at = placement(p);
		double spareX = Math.max(0, (at[2] - s[2]) / 2), spareY = Math.max(0, (at[3] - s[3]) / 2);
		panX = (float) Math.max(-spareX, Math.min(spareX, panX));
		panY = (float) Math.max(-spareY, Math.min(spareY, panY));
	}

	// ---------- actions ----------

	private void openViewer(int index) {
		open = index;
		confirmDelete = false;
	}

	private void closeViewer() {
		open = -1;
		confirmDelete = false;
		releaseFull();
	}

	private void step(int d) {
		int next = open + d;
		if (next < 0 || next >= files.size()) return;
		open = next;
		confirmDelete = false;
	}

	private void delete() {
		if (!confirmDelete) {
			confirmDelete = true;
			confirmAt = System.currentTimeMillis();
			return;
		}
		File file = files.get(open);
		try {
			Files.delete(file.toPath());
			release(thumbs.remove(file));
			releaseFull();
			say(file.getName() + " gelöscht");
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not delete {}", file, e);
			say("Löschen ging nicht: " + e.getMessage());
		}
		confirmDelete = false;
		reload();
		if (files.isEmpty()) closeViewer();
	}

	private void say(String message) {
		flash = message;
		flashAt = System.currentTimeMillis();
	}

	/** Explorer with the file selected (or the folder elsewhere). */
	private void reveal(File file) {
		try {
			if (net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.WINDOWS) {
				new ProcessBuilder("explorer.exe", file.isDirectory() ? file.getAbsolutePath() : "/select," + file.getAbsolutePath()).start();
			} else {
				File folder = file.isDirectory() ? file : file.getParentFile();
				new ProcessBuilder(net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.OSX ? "open" : "xdg-open", folder.getAbsolutePath()).start();
			}
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not open {}", file, e);
			say("Ordner ließ sich nicht öffnen");
		}
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int raw = event.button();
		// As in the ClickGUI: 26.3's SDL3 backend reports 1 or 0 for left, 3 for right.
		int button = raw == 3 ? 1 : raw == 0 || raw == 1 ? 0 : -1;
		if (button != 0) return super.mouseClicked(event, doubleClick);

		if (open >= 0) {
			List<int[]> b = headerButtons(viewerLabels());
			if (hit(b.get(0), mx, my)) reveal(files.get(open));
			else if (hit(b.get(1), mx, my)) delete();
			else if (hit(b.get(2), mx, my)) closeViewer();
			else {
				int[] s = stage();
				if (open > 0 && Draw.inside(mx, my, s[0] + 4, s[1] + s[3] / 2 - 10, 20, 20)) step(-1);
				else if (open < files.size() - 1 && Draw.inside(mx, my, s[0] + s[2] - 24, s[1] + s[3] / 2 - 10, 20, 20)) step(1);
				else if (doubleClick && Draw.inside(mx, my, s[0], s[1], s[2], s[3])) zoomAt(zoom > 1 ? 1 / zoom : 2.5f, mx, my);
			}
			return true;
		}

		List<int[]> b = headerButtons(gridLabels());
		if (hit(b.get(0), mx, my)) {
			newestFirst = !newestFirst;
			reload();
			scrollTarget = scroll = 0;
			return true;
		}
		if (hit(b.get(1), mx, my)) {
			dir.mkdirs();
			reveal(dir);
			return true;
		}
		int top = gridTop(), cols = columns(), left = gridLeft();
		if (my >= top && my < bottom() - 6) {
			for (int i = 0; i < files.size(); i++) {
				int x = left + (i % cols) * (TILE_W + GAP), y = top + (i / cols) * rowH() - (int) Math.round(scroll);
				if (Draw.inside(mx, my, x, y, TILE_W, TILE_H + LABEL_H)) {
					openViewer(i);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private static boolean hit(int[] b, double mx, double my) {
		return Draw.inside(mx, my, b[0], b[1], b[2], b[3]);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (open >= 0 && zoom > 1) {
			panX += (float) dx;
			panY += (float) dy;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (open >= 0) {
			zoomAt(scrollY > 0 ? 1.25f : 1 / 1.25f, mx, my);
			return true;
		}
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - scrollY * rowH() * 0.6));
		return true;
	}

	/** Zooms by `factor` keeping the point under the mouse still. */
	private void zoomAt(float factor, double mx, double my) {
		File file = files.get(open);
		Picture p = full != null && file.equals(fullFile) ? full : thumbs.get(file);
		float next = Math.max(1, Math.min(MAX_ZOOM, zoom * factor));
		if (p == null || next == zoom) return;
		int[] s = stage();
		// Mouse position relative to the stage centre, then keep that picture point under it.
		double cx = mx - (s[0] + s[2] / 2.0), cy = my - (s[1] + s[3] / 2.0);
		double k = next / zoom;
		panX = (float) (cx - (cx - panX) * k);
		panY = (float) (cy - (cy - panY) * k);
		zoom = next;
		if (zoom == 1) panX = panY = 0;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (open >= 0) {
			if (key == InputConstants.KEY_LEFT) step(-1);
			else if (key == InputConstants.KEY_RIGHT) step(1);
			else if (key == InputConstants.KEY_DELETE) delete();
			else if (key == InputConstants.KEY_0) zoomAt(1 / zoom, 0, 0);
			else return super.keyPressed(event);
			return true;
		}
		if (key == InputConstants.KEY_HOME) scrollTarget = 0;
		else if (key == InputConstants.KEY_END) scrollTarget = maxScroll();
		else return super.keyPressed(event);
		return true;
	}

	// ---------- text ----------

	private static String ellipsize(String s, int maxWidth) {
		if (Draw.width(s) <= maxWidth) return s;
		String dots = "…";
		int end = s.length();
		while (end > 0 && Draw.width(s.substring(0, end) + dots) > maxWidth) end--;
		return s.substring(0, end) + dots;
	}

	static String size(long bytes) {
		if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
		if (bytes < 1024L * 1024 * 1024) return String.format(Locale.GERMAN, "%.1f MB", bytes / (1024.0 * 1024));
		return String.format(Locale.GERMAN, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
	}

	static String relative(long millis) {
		long s = (System.currentTimeMillis() - millis) / 1000;
		if (s < 90) return "gerade eben";
		if (s < 3600) return "vor " + Math.round(s / 60.0) + " min";
		if (s < 86400) return "vor " + Math.round(s / 3600.0) + " h";
		long days = Math.round(s / 86400.0);
		return days == 1 ? "gestern" : "vor " + days + " Tagen";
	}

	private static String date(long millis) {
		return new SimpleDateFormat("EE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(new Date(millis));
	}
}
