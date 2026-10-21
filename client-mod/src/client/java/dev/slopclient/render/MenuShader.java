package dev.slopclient.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.slopclient.SlopClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The launcher's flowing violet shader as a GUI pipeline
 * (assets/slopclient/shaders/core/menu_shader.fsh). Drawn as a full-screen fill;
 * the fill colour carries the time to the shader.
 */
public final class MenuShader {
	private static final long START = System.currentTimeMillis();
	/** Starts 40 s in, like the launcher, so the first frame already has movement. */
	private static final long OFFSET_CS = 4000;
	private static RenderPipeline pipeline;

	private MenuShader() {
	}

	/**
	 * Must run during client init, before pipelines are compiled. Optional, so a
	 * driver that rejects the shader only loses the backdrop, not every menu.
	 */
	public static void register() {
		pipeline = RenderPipelines.registerOptional(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(SlopClient.ID, "pipeline/menu_shader"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(SlopClient.ID, "core/menu_shader"))
			.build());
	}

	/** Fills the screen with the shader; `alpha` fades it in. */
	public static void draw(GuiGraphicsExtractor g, double alpha) {
		// Dark base first: stays visible if the shader is unavailable.
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), Theme.BG);
		if (pipeline == null) return;
		// Green and blue: a 16-bit counter in 1/20 s. Red: the accent hue shift.
		long ticks = ((System.currentTimeMillis() - START) / 50 + OFFSET_CS / 5) & 0xFFFF;
		int hue = (int) Math.round((Theme.SHIFT + 180) / 360 * 255);
		int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
		g.fill(pipeline, 0, 0, g.guiWidth(), g.guiHeight(), a << 24 | hue << 16 | (int) ticks);
	}
}
