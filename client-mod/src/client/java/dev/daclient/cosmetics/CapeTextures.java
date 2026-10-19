package dev.daclient.cosmetics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.daclient.DaClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

/**
 * The cape from the launcher's Cape Studio, which the launcher copies to
 * `config/daclient/cosmetics/` (cape.png spritesheet + cape.json, see daclient-core cosmetics.rs).
 * Every frame becomes its own texture so the vanilla cape model can use it unchanged.
 */
public final class CapeTextures {
	private static final int BASE_W = 64, BASE_H = 32, MAX_FRAMES = 64, MIN_FRAME_MS = 40;

	private static final List<ClientAsset.Texture> frames = new ArrayList<>();
	private static int frameTimeMs;
	private static boolean elytra;
	private static boolean loaded;

	private CapeTextures() {
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("daclient").resolve("cosmetics");
	}

	/** Loads the cape on first use (textures need the render thread). */
	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path png = dir().resolve("cape.png"), json = dir().resolve("cape.json");
		if (!Files.isRegularFile(png) || !Files.isRegularFile(json)) return;
		try {
			JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			int count = meta.get("frames").getAsInt();
			int scale = meta.get("scale").getAsInt();
			frameTimeMs = Math.max(MIN_FRAME_MS, meta.has("frameTimeMs") ? meta.get("frameTimeMs").getAsInt() : 100);
			elytra = !meta.has("elytra") || meta.get("elytra").getAsBoolean();
			if (count < 1 || count > MAX_FRAMES || scale < 1 || scale > 8) throw new IllegalArgumentException("cape.json out of range");

			try (NativeImage sheet = NativeImage.read(Files.readAllBytes(png))) {
				int w = BASE_W * scale, h = BASE_H * scale;
				if (sheet.getWidth() != w || sheet.getHeight() != h * count) {
					throw new IllegalArgumentException("cape.png does not match cape.json");
				}
				for (int i = 0; i < count; i++) {
					NativeImage frame = new NativeImage(w, h, true);
					sheet.copyRect(frame, 0, i * h, 0, 0, w, h, false, false);
					Identifier id = Identifier.fromNamespaceAndPath(DaClient.ID, "cape/frame_" + i);
					Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "daclient cape " + id, frame));
					frames.add(new ClientAsset.ResourceTexture(id, id));
				}
			}
			DaClient.LOG.info("Loaded cape with {} frames ({} ms) at {}x", count, frameTimeMs, scale);
		} catch (Exception e) {
			DaClient.LOG.warn("Could not load the DaClient cape from {}", dir(), e);
			frames.clear();
		}
	}

	public static boolean available() {
		ensureLoaded();
		return !frames.isEmpty();
	}

	public static boolean elytra() {
		return elytra;
	}

	/** Texture of the frame to show now; the first frame when animation is off. */
	public static ClientAsset.Texture current(boolean animate) {
		ensureLoaded();
		if (frames.isEmpty()) return null;
		if (!animate || frames.size() == 1) return frames.getFirst();
		return frames.get((int) (System.currentTimeMillis() / frameTimeMs % frames.size()));
	}

	public static int frameCount() {
		ensureLoaded();
		return frames.size();
	}
}
