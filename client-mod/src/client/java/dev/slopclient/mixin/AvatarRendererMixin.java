package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Glass module: your arms in first person are glass too (vanilla already draws them translucent). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
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
