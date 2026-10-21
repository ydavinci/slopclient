package dev.slopclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.slopclient.SlopClient;
import dev.slopclient.render.Theme;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

/**
 * Hats and pets: small voxel models from {@code cosmetics/extras.json} (the same file
 * the launcher previews). A model is a list of coloured boxes in pixels; glowing
 * boxes ignore the world light.
 */
public final class Extras {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SlopClient.ID, "textures/misc/extras.png");

	/** One box: corners in pixels, colour spec (#rrggbb, accent, accent2, chroma), glow. */
	record Box(float x1, float y1, float z1, float x2, float y2, float z2, String color, boolean glow) {
	}

	public record Model(String id, String name, boolean spin, List<Box> boxes) {
	}

	private static Map<String, Model> hats;
	private static Map<String, Pets.Pet> pets;

	private Extras() {
	}

	private static void load() {
		if (hats != null) return;
		hats = new HashMap<>();
		pets = new HashMap<>();
		Identifier id = Identifier.fromNamespaceAndPath(SlopClient.ID, "cosmetics/extras.json");
		try (var in = new InputStreamReader(Minecraft.getInstance().getResourceManager().open(id), StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
			read(root.getAsJsonArray("hats"), hats);
			for (JsonElement el : root.getAsJsonArray("pets")) {
				Pets.Pet pet = Pets.read(el.getAsJsonObject());
				pets.put(pet.id(), pet);
			}
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not load hats and pets", e);
		}
	}

	private static void read(JsonArray list, Map<String, Model> into) {
		for (JsonElement el : list) {
			JsonObject o = el.getAsJsonObject();
			List<Box> boxes = new ArrayList<>();
			for (JsonElement b : o.getAsJsonArray("boxes")) {
				JsonArray a = b.getAsJsonArray();
				boxes.add(new Box(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat(), a.get(4).getAsFloat(),
					a.get(5).getAsFloat(), a.get(6).getAsString(), a.size() > 7 && a.get(7).getAsBoolean()));
			}
			String id = o.get("id").getAsString();
			into.put(id, new Model(id, o.get("name").getAsString(), o.has("spin") && o.get("spin").getAsBoolean(), boxes));
		}
	}

	public static Model hat(String id) {
		if (id == null || id.isEmpty()) return null;
		load();
		return hats.get(id);
	}

	public static Pets.Pet pet(String id) {
		if (id == null || id.isEmpty()) return null;
		load();
		return pets.get(id);
	}

	/** Your own choice from the launcher. */
	public static String ownHat() {
		return System.getProperty("slopclient.hat", "");
	}

	public static String ownPet() {
		return System.getProperty("slopclient.pet", "");
	}

	private static int rgb(String spec, double phase) {
		return switch (spec) {
			case "accent" -> Theme.ACCENT & 0xFFFFFF;
			case "accent2" -> Theme.ACCENT_2 & 0xFFFFFF;
			case "chroma" -> Theme.chroma(phase) & 0xFFFFFF;
			default -> {
				try {
					yield Integer.parseInt(spec.substring(1), 16);
				} catch (RuntimeException e) {
					yield 0xFF00FF;
				}
			}
		};
	}

	/** Emits the model as quads; the pose is in blocks, the model in pixels. */
	static void emit(Model model, PoseStack.Pose pose, VertexConsumer out, int light, int overlay, double phase) {
		for (Box b : model.boxes) {
			int color = 0xFF000000 | rgb(b.color, phase);
			int l = b.glow ? LightCoordsUtil.FULL_BRIGHT : light;
			float x1 = b.x1 / 16f, y1 = b.y1 / 16f, z1 = b.z1 / 16f, x2 = b.x2 / 16f, y2 = b.y2 / 16f, z2 = b.z2 / 16f;
			// Model space: y points down, so the "top" face is at y1.
			quad(pose, out, color, l, overlay, 0, -1, 0, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
			quad(pose, out, color, l, overlay, 0, 1, 0, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1);
			quad(pose, out, color, l, overlay, 0, 0, -1, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1);
			quad(pose, out, color, l, overlay, 0, 0, 1, x2, y2, z2, x1, y2, z2, x1, y1, z2, x2, y1, z2);
			quad(pose, out, color, l, overlay, -1, 0, 0, x1, y2, z2, x1, y2, z1, x1, y1, z1, x1, y1, z2);
			quad(pose, out, color, l, overlay, 1, 0, 0, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1);
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, int color, int light, int overlay, float nx, float ny, float nz, float... v) {
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		for (int i = 0; i < 4; i++) {
			out.addVertex(pose, v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
				.setColor(color)
				.setUv(uv[i][0], uv[i][1])
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, nx, ny, nz);
		}
	}
}
