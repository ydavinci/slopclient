package dev.slopclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * Accessories (e.g. a katana at the hip): Java block model elements with per-face UVs and
 * rotations from {@code cosmetics/extras.json}, placed on the body or head. The faces are
 * baked once into quads; launcher/src/lib/accessory.ts draws the same data the same way.
 */
public final class Accessories {
	/** Faces in the catalogue's order, with Minecraft's corner order (FaceInfo) and normal. */
	private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

	/**
	 * A baked accessory: `quads` holds 4 vertices per face as x, y, z (model pixels, y up),
	 * u, v (0..1); `normals` one x, y, z per face.
	 */
	public record Accessory(String id, String name, boolean onHead, Identifier texture, float[] pivot, float[] position, float[] rotation, float scale,
		float[] quads, float[] normals) {
	}

	private Accessories() {
	}

	private static float[] vec(JsonObject o, String key, float fallback) {
		if (!o.has(key)) return new float[] {fallback, fallback, fallback};
		JsonArray a = o.getAsJsonArray(key);
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	/** Corners of a face (FaceInfo order); vertex i gets UV corner i, shifted by the face rotation. */
	private static float[][] corners(String face, float[] f, float[] t) {
		float x0 = f[0], y0 = f[1], z0 = f[2], x1 = t[0], y1 = t[1], z1 = t[2];
		return switch (face) {
			case "down" -> new float[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
			case "up" -> new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
			case "north" -> new float[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
			case "south" -> new float[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
			case "west" -> new float[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
			default -> new float[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
		};
	}

	private static float[] normal(String face) {
		return switch (face) {
			case "down" -> new float[] {0, -1, 0};
			case "up" -> new float[] {0, 1, 0};
			case "north" -> new float[] {0, 0, -1};
			case "south" -> new float[] {0, 0, 1};
			case "west" -> new float[] {-1, 0, 0};
			default -> new float[] {1, 0, 0};
		};
	}

	/** Rotates `p` around `origin` by `deg` degrees about the axis (right-handed, like block models). */
	private static float[] rotate(float[] p, float[] origin, String axis, double deg) {
		double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
		double x = p[0] - origin[0], y = p[1] - origin[1], z = p[2] - origin[2];
		double nx = x, ny = y, nz = z;
		switch (axis) {
			case "x" -> {
				ny = y * c - z * s;
				nz = y * s + z * c;
			}
			case "y" -> {
				nx = x * c + z * s;
				nz = -x * s + z * c;
			}
			default -> {
				nx = x * c - y * s;
				ny = x * s + y * c;
			}
		}
		return new float[] {(float) nx + origin[0], (float) ny + origin[1], (float) nz + origin[2]};
	}

	static Accessory read(JsonObject o, Function<String, Identifier> textures) {
		float uvW = 16, uvH = 16;
		if (o.has("uvSize")) {
			uvW = o.getAsJsonArray("uvSize").get(0).getAsFloat();
			uvH = o.getAsJsonArray("uvSize").get(1).getAsFloat();
		}
		JsonArray elements = o.getAsJsonArray("elements");
		int faces = 0;
		for (JsonElement el : elements) faces += el.getAsJsonObject().getAsJsonObject("faces").size();
		float[] quads = new float[faces * 4 * 5], normals = new float[faces * 3];
		int q = 0, n = 0;
		for (JsonElement el : elements) {
			JsonObject e = el.getAsJsonObject();
			float[] from = vec(e, "from", 0), to = vec(e, "to", 0);
			JsonObject rot = e.has("rotation") ? e.getAsJsonObject("rotation") : null;
			float[] origin = rot == null ? null : vec(rot, "origin", 0);
			String axis = rot == null ? null : rot.get("axis").getAsString();
			double angle = rot == null ? 0 : rot.get("angle").getAsDouble();
			JsonObject faceMap = e.getAsJsonObject("faces");
			for (String face : FACES) {
				if (!faceMap.has(face)) continue;
				JsonObject f = faceMap.getAsJsonObject(face);
				JsonArray uv = f.getAsJsonArray("uv");
				float u0 = uv.get(0).getAsFloat() / uvW, v0 = uv.get(1).getAsFloat() / uvH, u1 = uv.get(2).getAsFloat() / uvW, v1 = uv.get(3).getAsFloat() / uvH;
				int shift = f.has("rotation") ? f.get("rotation").getAsInt() / 90 : 0;
				float[][] cs = corners(face, from, to);
				for (int i = 0; i < 4; i++) {
					float[] p = rot == null ? cs[i] : rotate(cs[i], origin, axis, angle);
					// Like BlockFaceUV: u from corners 0/1 → u0, v from corners 0/3 → v0.
					int j = (i + shift) % 4;
					quads[q++] = p[0];
					quads[q++] = p[1];
					quads[q++] = p[2];
					quads[q++] = j == 0 || j == 1 ? u0 : u1;
					quads[q++] = j == 0 || j == 3 ? v0 : v1;
				}
				float[] nv = normal(face);
				if (rot != null) nv = rotate(nv, new float[3], axis, angle);
				normals[n++] = nv[0];
				normals[n++] = nv[1];
				normals[n++] = nv[2];
			}
		}
		return new Accessory(o.get("id").getAsString(), o.get("name").getAsString(), "head".equals(o.get("attach").getAsString()),
			textures.apply(o.get("texture").getAsString()), vec(o, "pivot", 0), vec(o, "position", 0), vec(o, "rotation", 0),
			o.has("scale") ? o.get("scale").getAsFloat() : 1, quads, normals);
	}

	/** Emits the quads; the pose already places the model (pixels, y up). */
	static void emit(Accessory a, PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
		float[] q = a.quads(), n = a.normals();
		for (int face = 0; face < n.length / 3; face++) {
			for (int i = 0; i < 4; i++) {
				int k = (face * 4 + i) * 5;
				out.addVertex(pose, q[k] / 16f, q[k + 1] / 16f, q[k + 2] / 16f)
					.setColor(-1)
					.setUv(q[k + 3], q[k + 4])
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(pose, n[face * 3], n[face * 3 + 1], n[face * 3 + 2]);
			}
		}
	}
}
