package dev.slopclient.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.SlopClient;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The launcher's shader as the sky (assets/slopclient/shaders/core/slop_sky.*): a box
 * around the camera like vanilla's End sky, coloured per pixel from the view direction.
 */
public final class SlopSky {
	private static final long START = System.currentTimeMillis();
	/** Starts 40 s in, like the launcher, so the first frame already has movement. */
	private static final float OFFSET_S = 40;
	private static RenderPipeline pipeline;
	private static GpuBuffer box;

	private SlopSky() {
	}

	/** Must run during client init, before pipelines are compiled. Optional like {@link MenuShader}. */
	public static void register() {
		pipeline = RenderPipelines.registerOptional(RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withLocation(Identifier.fromNamespaceAndPath(SlopClient.ID, "pipeline/slop_sky"))
			.withVertexShader(Identifier.fromNamespaceAndPath(SlopClient.ID, "core/slop_sky"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(SlopClient.ID, "core/slop_sky"))
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.build());
	}

	/**
	 * Draws the sky into the level target. False when the shader is unavailable (the
	 * driver rejected it); vanilla's sky is drawn then.
	 */
	public static boolean render(RenderTarget target, GpuBufferSlice fog) {
		if (pipeline == null) return false;
		var compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
		if (compiled == null) return false;
		if (box == null) box = buildBox();
		RenderSystem.setShaderFog(fog);
		float seconds = ((System.currentTimeMillis() - START) / 1000f + OFFSET_S) % 3600f;
		float hue = (float) ((Theme.SHIFT + 180) / 360);
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(seconds, hue, 0, 1));
		var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "SlopClient sky", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, box.slice());
			pass.setIndexBuffer(indices.getBuffer(36), indices.type());
			pass.drawIndexed(36, 1, 0, 0, 0);
		}
		return true;
	}

	/** Six faces at 100 blocks, as vanilla builds the End sky. */
	private static GpuBuffer buildBox() {
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(24 * DefaultVertexFormat.POSITION.getVertexSize())) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
			for (int face = 0; face < 6; face++) {
				Matrix4f m = new Matrix4f();
				switch (face) {
					case 1 -> m.rotationX((float) (Math.PI / 2));
					case 2 -> m.rotationX((float) (-Math.PI / 2));
					case 3 -> m.rotationX((float) Math.PI);
					case 4 -> m.rotationZ((float) (Math.PI / 2));
					case 5 -> m.rotationZ((float) (-Math.PI / 2));
					default -> {
					}
				}
				builder.addVertex(m, -100, -100, -100);
				builder.addVertex(m, -100, -100, 100);
				builder.addVertex(m, 100, -100, 100);
				builder.addVertex(m, 100, -100, -100);
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				return RenderSystem.getDevice().createBuffer(() -> "SlopClient sky box", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
			}
		}
	}
}
