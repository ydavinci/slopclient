package dev.slopclient.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

/**
 * One cape (spritesheet in the shared SlopClient format) as a texture per frame, so the
 * vanilla cape and elytra models can use it unchanged. Must be built on the render thread.
 */
public final class CapeAnimation {
	public static final int BASE_W = 64, BASE_H = 32, MAX_FRAMES = 128, MIN_FRAME_MS = 40, MAX_SCALE = 16;

	private final List<Identifier> ids = new ArrayList<>();
	private final List<ClientAsset.Texture> frames = new ArrayList<>();
	private final int frameTimeMs;
	public final boolean elytra;
	/** Approximate texture memory in bytes. */
	public final long bytes;

	private CapeAnimation(int frameTimeMs, boolean elytra, long bytes) {
		this.frameTimeMs = frameTimeMs;
		this.elytra = elytra;
		this.bytes = bytes;
	}

	/**
	 * Splits `sheet` into frame textures named `slopclient:cape/<key>/frame_<i>`.
	 * With `firstFrameOnly` only frame 0 is uploaded (memory budget for other players).
	 */
	public static CapeAnimation fromSheet(NativeImage sheet, String key, int frames, int scale, int frameTimeMs, boolean elytra, boolean firstFrameOnly) {
		if (frames < 1 || frames > MAX_FRAMES || scale < 1 || scale > MAX_SCALE) throw new IllegalArgumentException("cape meta out of range");
		int w = BASE_W * scale, h = BASE_H * scale;
		if (sheet.getWidth() != w || sheet.getHeight() != h * frames) throw new IllegalArgumentException("spritesheet does not match its meta data");
		int count = firstFrameOnly ? 1 : frames;
		CapeAnimation anim = new CapeAnimation(Math.max(MIN_FRAME_MS, frameTimeMs), elytra, (long) w * h * 4 * count);
		for (int i = 0; i < count; i++) {
			NativeImage frame = new NativeImage(w, h, true);
			sheet.copyRect(frame, 0, i * h, 0, 0, w, h, false, false);
			Identifier id = Identifier.fromNamespaceAndPath(SlopClient.ID, "cape/" + key + "/frame_" + i);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "slopclient cape " + id, frame));
			anim.ids.add(id);
			anim.frames.add(new ClientAsset.ResourceTexture(id, id));
		}
		return anim;
	}

	public int frameCount() {
		return frames.size();
	}

	/** Frame to show now; the first frame when animation is off. */
	public ClientAsset.Texture current(boolean animate) {
		if (!animate || frames.size() == 1) return frames.getFirst();
		return frames.get((int) (System.currentTimeMillis() / frameTimeMs % frames.size()));
	}

	/** Frees the textures (render thread). */
	public void release() {
		var textures = Minecraft.getInstance().getTextureManager();
		for (Identifier id : ids) textures.release(id);
		ids.clear();
		frames.clear();
	}
}
