package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Glass module: your arms in first person are glass too (vanilla already draws them translucent).
 * Music module: the song a player shares, one line above the nametag.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@org.spongepowered.asm.mixin.injection.Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
	private void slopclient$song(net.minecraft.client.renderer.entity.state.AvatarRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
		net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		var mc = Minecraft.getInstance();
		if (state.nameTag == null || !SlopClient.ready() || mc.level == null) return;
		if (!(mc.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) return;
		String song = SlopClient.modules().music.lineFor(player.getUUID());
		if (song == null) return;
		var name = state.nameTag;
		state.nameTag = net.minecraft.network.chat.Component.literal("♪ ").withColor(dev.slopclient.render.Theme.ACCENT_2 & 0xFFFFFF)
			.append(net.minecraft.network.chat.Component.literal(song).withColor(0xE8E4F0));
		// One line above the name (two with deadmau5's ears, which push the name up already).
		((EntityRendererInvoker) this).slopclient$submitNameDisplay(state, pose, collector, camera, state.showExtraEars ? -20 : -10);
		state.nameTag = name;
	}

	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true)
	private Identifier slopclient$glassArm(Identifier skin) {
		if (!SlopClient.ready() || !SlopClient.modules().glass.firstPersonArms()) return skin;
		var player = Minecraft.getInstance().player;
		return SlopClient.modules().glass.texture(player.getSkin().model() == PlayerModelType.SLIM);
	}

	/** Full-bright, like the glass figure in third person. */
	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true)
	private int slopclient$glassArmLight(int light) {
		return SlopClient.ready() && SlopClient.modules().glass.firstPersonArms() ? 0xF000F0 : light;
	}
}
