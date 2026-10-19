package dev.daclient.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.daclient.DaClient;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The instance icon the launcher passes in `-Ddaclient.icon`, loaded once as a texture
 * with the launcher's rounded corners (28 % radius) cut into the alpha channel.
 */
public final class InstanceIconTexture {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DaClient.ID, "instance_icon");
	private static Boolean loaded;
	private static int size;

	private InstanceIconTexture() {
	}

	/** Texture size, or 0 when the instance has no icon. Loads on first use. */
	public static int size() {
		if (loaded == null) loaded = load();
		return loaded ? size : 0;
	}

	private static boolean load() {
		String file = System.getProperty("daclient.icon");
		if (file == null || file.isBlank()) return false;
		try {
			NativeImage image = NativeImage.read(Files.readAllBytes(Path.of(file)));
			roundCorners(image, 0.28);
			size = image.getWidth();
			Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "daclient instance icon", image));
			return true;
		} catch (Exception e) {
			DaClient.LOG.warn("Could not load the instance icon {}", file, e);
			return false;
		}
	}

	/** Makes pixels outside the rounded rectangle transparent, with a soft 1 px edge. */
	private static void roundCorners(NativeImage image, double radius) {
		int w = image.getWidth(), h = image.getHeight();
		double r = Math.min(w, h) * radius;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double dx = Math.max(0, Math.max(r - x - 0.5, x + 0.5 - (w - r)));
				double dy = Math.max(0, Math.max(r - y - 0.5, y + 0.5 - (h - r)));
				if (dx == 0 || dy == 0) continue;
				double outside = Math.sqrt(dx * dx + dy * dy) - r;
				if (outside <= -1) continue;
				int argb = image.getPixel(x, y);
				int alpha = (int) ((argb >>> 24) * Math.max(0, Math.min(1, -outside)));
				image.setPixel(x, y, alpha << 24 | argb & 0xFFFFFF);
			}
		}
	}
}
