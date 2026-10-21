package dev.slopclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.slopclient.cosmetics.karambit.KarambitHands;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Karambit in first person: the arm as for an empty hand, the knife in its fist. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@Shadow
	private void renderPlayerArm(PoseStack pose, SubmitNodeCollector collector, int light, float equip, float swing, HumanoidArm arm, PlayerRenderState player) {
	}

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void slopclient$karambitInHand(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, float partialTick, float xRot,
		InteractionHand hand, float swing, ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (state.isScoping || !KarambitHands.applies(stack)) return;
		var avatar = player.avatarRenderState;
		if (avatar == null || avatar.isInvisible) return;
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite();
		pose.pushPose();
		renderPlayerArm(pose, collector, light, equip, swing, arm, player);
		KarambitHands.submitInHand(pose, collector, light, arm, avatar, stack.hasFoil(), swing < 1e-3f && Math.abs(equip) < 1e-3f);
		pose.popPose();
		ci.cancel();
	}
}
