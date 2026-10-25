package dev.slopclient.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
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
			if (solidSides(frame, scale)) wrapEdges(frame, scale);
			if (elytra) shapeElytra(frame, scale);
			Identifier id = Identifier.fromNamespaceAndPath(Cosmetics.NS, "cape/" + key + "/frame_" + i);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "slopclient cape " + id, frame));
			anim.ids.add(id);
			anim.frames.add(new ClientAsset.ResourceTexture(id, id));
		}
		return anim;
	}

	/**
	 * Capes saved before the Cape Studio wrapped the picture over the edges have sides
	 * in one flat colour: true when the left and right side are a single colour.
	 */
	static boolean solidSides(NativeImage img, int s) {
		int first = img.getPixel(0, s);
		for (int y = s; y < 17 * s; y++) {
			for (int k = 0; k < s; k++) {
				if (img.getPixel(k, y) != first || img.getPixel(11 * s + k, y) != first) return false;
			}
		}
		return true;
	}

	/**
	 * For those old capes: each side face (one cape pixel thick) repeats the front's
	 * border, mirrored outwards, so the picture seems to run around the edge.
	 */
	static void wrapEdges(NativeImage img, int s) {
		for (int k = 0; k < s; k++) {
			for (int y = s; y < 17 * s; y++) {
				img.setPixel(s - 1 - k, y, img.getPixel(s + k, y));
				img.setPixel(11 * s + k, y, img.getPixel(11 * s - 1 - k, y));
			}
			for (int x = 0; x < 10 * s; x++) {
				img.setPixel(s + x, s - 1 - k, img.getPixel(s + x, s + k));
				img.setPixel(11 * s + x, k, img.getPixel(s + x, 17 * s - 1 - k));
			}
		}
	}

	/**
	 * Cuts the wing silhouette into the elytra part of the texture. The elytra model is
	 * two plain boxes; vanilla gets the wing shape from transparent pixels, so a fully
	 * painted area would show as two slabs. Same shape as cape.ts in the launcher.
	 */
	static void shapeElytra(NativeImage img, int scale) {
		for (int y = 0; y < 22 * scale; y++) {
			for (int x = 22 * scale; x < 46 * scale; x++) {
				if (!keepElytra((x + 0.5f) / scale, (y + 0.5f) / scale)) img.setPixel(x, y, 0);
			}
		}
	}

	/** Wing width at v pixels below the top: full at the shoulder, tapering to the tip. */
	private static float wingWidth(float v) {
		if (v < 3) return 10;
		float t = Math.min(1, (v - 3) / 17f);
		return 10 * (1 - 0.72f * (float) Math.pow(t, 1.5));
	}

	/** Whether a point (in 64 × 32 texture pixels) of the elytra area stays visible. */
	static boolean keepElytra(float px, float py) {
		if (py < 2) return px >= 24 && px < 34; // top face; the bottom face (34..44) is hidden
		float v = py - 2;
		if (px >= 24 && px < 34) return px - 24 >= 10 - wingWidth(v); // front: inner edge at the right
		if (px >= 34 && px < 36) return true; // inner side
		if (px >= 36 && px < 46) return px - 36 <= wingWidth(v); // back: mirrored
		return false; // outer side face
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
