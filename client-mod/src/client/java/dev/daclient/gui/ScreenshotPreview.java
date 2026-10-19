package dev.daclient.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.daclient.DaClient;
import dev.daclient.module.Category;
import dev.daclient.module.Module;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import dev.daclient.setting.NumberSetting;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Screenshot manager: after F2 a card with the picture slides in at the bottom right.
 * The "open screenshot" key shows the latest one in Explorer; the full gallery is the
 * launcher's Screenshots tab.
 */
public final class ScreenshotPreview extends Module {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DaClient.ID, "screenshot_preview");
	private static final int W = 128, H = 72;

	public final NumberSetting seconds = add(new NumberSetting("Anzeigedauer", 4, 1, 10, 0.5, v -> String.format(java.util.Locale.GERMAN, "%.1f s", v)));
	private DynamicTexture texture;
	private int texW, texH;
	private File latest;
	private long shownAt = -1;

	public ScreenshotPreview() {
		super("Screenshots", "Vorschau nach F2, Taste öffnet den letzten", Category.COMFORT, true);
	}

	/** Called when Minecraft saved a screenshot (any thread). */
	public void onSaved(Minecraft mc) {
		if (!active()) return;
		mc.execute(() -> {
			File dir = new File(mc.gameDirectory, "screenshots");
			File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
			if (files == null || files.length == 0) return;
			File newest = Arrays.stream(files).max(Comparator.comparingLong(File::lastModified)).get();
			try (InputStream in = new FileInputStream(newest)) {
				NativeImage image = NativeImage.read(in);
				release();
				texW = image.getWidth();
				texH = image.getHeight();
				texture = new DynamicTexture(() -> "DaClient screenshot preview", image);
				mc.getTextureManager().register(ID, texture);
				latest = newest;
				shownAt = System.currentTimeMillis();
			} catch (Exception e) {
				DaClient.LOG.warn("Could not show screenshot preview", e);
			}
		});
	}

	/** Shows the latest screenshot in Explorer. */
	public void openLatest() {
		File file = latest;
		if (file == null) {
			File[] files = new File(Minecraft.getInstance().gameDirectory, "screenshots").listFiles((d, n) -> n.endsWith(".png"));
			if (files == null || files.length == 0) return;
			file = Arrays.stream(files).max(Comparator.comparingLong(File::lastModified)).get();
		}
		try {
			new ProcessBuilder("explorer.exe", "/select," + file.getAbsolutePath()).start();
		} catch (Exception e) {
			DaClient.LOG.warn("Could not open {}", file, e);
		}
	}

	private void release() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
	}

	public void render(GuiGraphicsExtractor g) {
		if (shownAt < 0 || texture == null) return;
		double t = (System.currentTimeMillis() - shownAt) / 1000.0, total = seconds.get();
		if (t > total + 0.4) {
			shownAt = -1;
			release();
			return;
		}
		// Slides in, waits, slides out.
		double k = Math.min(Theme.ease(t / 0.4), Theme.ease((total + 0.4 - t) / 0.4));
		int pad = 6, cardW = W + 2 * pad, cardH = H + 2 * pad + 12;
		int x = g.guiWidth() - cardW - 6 + (int) Math.round((1 - k) * (cardW + 12));
		int y = g.guiHeight() - cardH - 40;
		Draw.round(g, x, y, cardW, cardH, 7, Theme.alpha(Theme.PANEL_SOLID, 0.9 * k));
		Draw.roundOutline(g, x, y, cardW, cardH, 7, Theme.alpha(Theme.ACCENT_2, 0.5 * k));
		// Cover the thumbnail box with the picture, keeping its aspect ratio.
		float scale = Math.max(W / (float) texW, H / (float) texH);
		int srcW = Math.round(W / scale), srcH = Math.round(H / scale);
		g.blit(RenderPipelines.GUI_TEXTURED, ID, x + pad, y + pad, (texW - srcW) / 2f, (texH - srcH) / 2f, W, H, srcW, srcH, texW, texH, Theme.alpha(0xFFFFFF, k));
		String name = latest == null ? "" : latest.getName().replaceAll("\\.png$", "");
		Draw.scaledText(g, name, x + pad, y + pad + H + 4, 0.75f, Theme.alpha(Theme.MUTED, k), false);
	}
}
