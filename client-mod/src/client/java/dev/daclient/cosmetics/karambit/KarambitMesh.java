package dev.daclient.cosmetics.karambit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.daclient.DaClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

/**
 * The baked Karambit mesh ({@code assets/daclient/karambit/karambit.mesh}, made from
 * the CS:GO model by {@code tools/karambit/bake.py}): positions already in item model
 * space, so the vanilla sword transforms place it in the hand.
 *
 * <p>Entity render types draw quads, so every triangle is emitted as a quad with its
 * last corner doubled.
 */
public final class KarambitMesh {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(DaClient.ID, "textures/item/karambit.png");
	private static final Identifier FILE = Identifier.fromNamespaceAndPath(DaClient.ID, "karambit/karambit.mesh");
	private static final int STRIDE = 8;

	/** x, y, z, nx, ny, nz, u, v per emitted corner (four per triangle). */
	private final float[] corners;
	/** Centre of the finger ring: the knife spins around it. */
	public final Vector3f pivot;
	public final Vector3f min;
	public final Vector3f max;

	private KarambitMesh(float[] corners, Vector3f pivot, Vector3f min, Vector3f max) {
		this.corners = corners;
		this.pivot = pivot;
		this.min = min;
		this.max = max;
	}

	public static KarambitMesh load(ResourceManager resources) {
		try (InputStream in = resources.open(FILE)) {
			ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
			if (b.getInt() != 0x314D4B44) throw new IOException("not a DKM1 file"); // "DKM1"
			int vertexCount = b.getInt(), indexCount = b.getInt();
			Vector3f pivot = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
			Vector3f min = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
			Vector3f max = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
			float[] vertices = new float[vertexCount * STRIDE];
			b.asFloatBuffer().get(vertices);
			b.position(b.position() + vertices.length * Float.BYTES);
			int triangles = indexCount / 3;
			float[] corners = new float[triangles * 4 * STRIDE];
			int o = 0;
			for (int t = 0; t < triangles; t++) {
				int a = b.getInt(), c1 = b.getInt(), c2 = b.getInt();
				for (int v : new int[] {a, c1, c2, c2}) {
					System.arraycopy(vertices, v * STRIDE, corners, o, STRIDE);
					o += STRIDE;
				}
			}
			DaClient.LOG.info("Karambit model loaded ({} triangles)", triangles);
			return new KarambitMesh(corners, pivot, min, max);
		} catch (IOException | RuntimeException e) {
			DaClient.LOG.error("Could not load the Karambit model", e);
			return null;
		}
	}

	public void emit(PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
		float[] c = corners;
		for (int i = 0; i < c.length; i += STRIDE) {
			out.addVertex(pose, c[i], c[i + 1], c[i + 2])
				.setColor(-1)
				.setUv(c[i + 6], c[i + 7])
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, c[i + 3], c[i + 4], c[i + 5]);
		}
	}
}
