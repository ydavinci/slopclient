package dev.slopclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's nametag drawing with a vertical offset, for the song line above a player's name. */
@Mixin(EntityRenderer.class)
public interface EntityRendererInvoker {
	@Invoker("submitNameDisplay")
	void slopclient$submitNameDisplay(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, int offset);
}
