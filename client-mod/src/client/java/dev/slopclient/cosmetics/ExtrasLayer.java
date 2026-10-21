package dev.slopclient.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Draws a player's hat on the head and the pet floating beside the shoulder. */
public final class ExtrasLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	public ExtrasLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		if (!SlopClient.ready() || state.isInvisible) return;
		var module = SlopClient.modules().extras;
		if (!module.active()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(state.id) instanceof AbstractClientPlayer player)) return;

		boolean self = player == mc.player;
		if (!self && !module.others.on()) return;
		Extras.Model hat = Extras.hat(self ? Extras.ownHat() : RemoteCapes.hat(player.getUUID()));
		Extras.Model pet = Extras.pet(self ? Extras.ownPet() : RemoteCapes.pet(player.getUUID()));
		if (hat == null && pet == null) return;

		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
		double phase = state.ageInTicks / 60.0;
		var type = RenderTypes.entityCutout(Extras.TEXTURE);

		// A helmet covers the head: the hat sits on top of it instead.
		if (hat != null && !(module.hideWithHelmet.on() && !state.headEquipment.isEmpty())) {
			pose.pushPose();
			getParentModel().head.translateAndRotate(pose);
			if (!state.headEquipment.isEmpty()) pose.scale(1.15f, 1.15f, 1.15f);
			collector.submitCustomGeometry(pose, type, (p, out) -> Extras.emit(hat, p, out, light, overlay, phase));
			pose.popPose();
		}

		if (pet != null) {
			pose.pushPose();
			// Beside the left shoulder, bobbing gently; y points down in model space.
			float t = state.ageInTicks;
			float bob = (float) Math.sin(t * 0.08) * 0.07f;
			pose.translate(0.62f, -0.32f + bob, 0.12f);
			pose.scale(0.9f, 0.9f, 0.9f);
			if (pet.spin()) {
				pose.rotateDegrees(Axis.YP, t * 2.4f);
				pose.rotateDegrees(Axis.XP, t * 1.3f);
			} else {
				pose.rotateDegrees(Axis.YP, (float) Math.sin(t * 0.035) * 12);
				pose.rotateDegrees(Axis.ZP, (float) Math.sin(t * 0.05) * 4);
			}
			collector.submitCustomGeometry(pose, type, (p, out) -> Extras.emit(pet, p, out, light, overlay, phase));
			pose.popPose();
		}
	}
}
