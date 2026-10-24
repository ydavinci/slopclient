package dev.slopclient.cosmetics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.ClientAsset;

/**
 * Your own cape from the launcher's Cape Studio, which the launcher copies to
 * `config/slopclient/cosmetics/` (cape.png spritesheet + cape.json, see slopclient-core cosmetics.rs).
 */
public final class CapeTextures {
	private static CapeAnimation local;
	private static boolean loaded;

	private CapeTextures() {
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("slopclient").resolve("cosmetics");
	}

	/** Loads the cape on first use (textures need the render thread). */
	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path png = dir().resolve("cape.png"), json = dir().resolve("cape.json");
		if (!Files.isRegularFile(png) || !Files.isRegularFile(json)) return;
		try {
			JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			int frames = meta.get("frames").getAsInt();
			int scale = meta.get("scale").getAsInt();
			int frameTime = meta.has("frameTimeMs") ? meta.get("frameTimeMs").getAsInt() : 100;
			boolean elytra = !meta.has("elytra") || meta.get("elytra").getAsBoolean();
			try (NativeImage sheet = NativeImage.read(Files.readAllBytes(png))) {
				local = CapeAnimation.fromSheet(sheet, "local", frames, scale, frameTime, elytra, false);
			}
			Cosmetics.LOG.info("Loaded cape with {} frames ({} ms) at {}x", frames, frameTime, scale);
		} catch (Exception e) {
			Cosmetics.LOG.warn("Could not load the SlopClient cape from {}", dir(), e);
			local = null;
		}
	}

	/** Your own cape, or null without one. */
	public static CapeAnimation local() {
		ensureLoaded();
		return local;
	}

	public static boolean available() {
		return local() != null;
	}

	public static boolean elytra() {
		return local() != null && local.elytra;
	}

	public static ClientAsset.Texture current(boolean animate) {
		return local() == null ? null : local.current(animate);
	}

	public static int frameCount() {
		return local() == null ? 0 : local.frameCount();
	}
}
