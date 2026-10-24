package dev.slopclient.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * Draws a player's hat on the head and their pet: on the left shoulder, floating
 * beside the head, or walking behind them (see PetMotion). The mini-me is the
 * player's own model and skin, small and sitting on the shoulder.
 */
public final class ExtrasLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float MINIME_SCALE = 0.3f;

	public ExtrasLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible) return;
		CosmeticsSettings settings = Cosmetics.settings();
		if (!settings.extras()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(state.id) instanceof AbstractClientPlayer player)) return;

		boolean self = player == mc.player;
		if (!self && !settings.extrasOfOthers()) return;
		Extras.Model hat = Extras.hatOf(player, self);
		Pets.Pet pet = Extras.petOf(player, self);
		if (hat == null && pet == null) return;

		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
		double phase = state.ageInTicks / 60.0;

		// A helmet covers the head: the hat sits on top of it instead.
		if (hat != null && !(settings.hideHatWithHelmet() && !state.headEquipment.isEmpty())) {
			pose.pushPose();
			getParentModel().head.translateAndRotate(pose);
			if (!state.headEquipment.isEmpty()) pose.scale(1.15f, 1.15f, 1.15f);
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(Extras.TEXTURE), (p, out) -> Extras.emit(hat, p, out, light, overlay, phase));
			pose.popPose();
		}
		if (pet == null) return;

		float t = state.ageInTicks;
		float walk = 0, speed = 0;
		pose.pushPose();
		switch (pet.tier()) {
			case SHOULDER -> {
				// On top of the left shoulder, moving with the body (crouching leans it).
				getParentModel().body.translateAndRotate(pose);
				pose.translate(0.375f, 0, 0);
			}
			case FLYING -> {
				getParentModel().body.translateAndRotate(pose);
				pose.translate(0.81f, -0.44f - (float) Math.sin(t * 0.08) * 1.1f / 16f, 0.19f);
			}
			case WALKING -> {
				PetMotion.State s = PetMotion.of(player.getId());
				// Swimming and gliding turn the player model, which would drag the pet along.
				if (s == null || state.isFallFlying || state.isVisuallySwimming) {
					pose.popPose();
					return;
				}
				float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
				double x = Mth.lerp(pt, s.px, s.x), y = Mth.lerp(pt, s.py, s.y), z = Mth.lerp(pt, s.pz, s.z);
				float yaw = s.pyaw + Mth.wrapDegrees(s.yaw - s.pyaw) * pt;
				walk = Mth.lerp(pt, s.pwalk, s.walk);
				speed = s.speed;
				// Undo the player's model transform (LivingEntityRenderer.setupRotations) to get
				// back to world axes at the player, then stand the pet at its own position.
				pose.translate(0, 1.501f, 0);
				pose.scale(-1, -1, 1);
				pose.rotateDegrees(Axis.YP, -(180 - state.bodyRot));
				pose.translate(x - state.x, y - state.y, z - state.z);
				pose.rotateDegrees(Axis.YP, 180 - yaw);
				pose.scale(-1, -1, 1);
			}
		}

		if (pet.minime()) {
			submitMinime(pose, collector, light, overlay, state);
		} else {
			float w = walk, sp = speed;
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(pet.texture()), (p, out) -> Pets.emit(pet, poseOf(p), out, light, overlay, t, w, sp));
			if (Pets.hasColored(pet)) {
				collector.submitCustomGeometry(pose, RenderTypes.entityCutout(Extras.TEXTURE), (p, out) -> Pets.emitColored(pet, poseOf(p), out, light, overlay, t, w, sp));
			}
		}
		pose.popPose();
	}

	/** The geometry callback gets a finished pose; wrap it so the pet code can push and rotate parts. */
	private static PoseStack poseOf(PoseStack.Pose pose) {
		PoseStack stack = new PoseStack();
		stack.last().set(pose);
		return stack;
	}

	/** A small copy of the player, sitting, with their skin and head turned like theirs. */
	private void submitMinime(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, AvatarRenderState state) {
		AvatarRenderState mini = new AvatarRenderState();
		mini.skin = state.skin;
		mini.isPassenger = true;
		mini.showHat = state.showHat;
		mini.showJacket = state.showJacket;
		mini.showLeftSleeve = state.showLeftSleeve;
		mini.showRightSleeve = state.showRightSleeve;
		mini.showLeftPants = state.showLeftPants;
		mini.showRightPants = state.showRightPants;
		mini.ageInTicks = state.ageInTicks;
		mini.yRot = state.yRot;
		mini.xRot = state.xRot;
		mini.id = state.id;
		pose.pushPose();
		pose.scale(MINIME_SCALE, MINIME_SCALE, MINIME_SCALE);
		// Hips (12 px below the neck) on the shoulder.
		pose.translate(0, -12 / 16f, 0);
		PlayerModel model = getParentModel();
		collector.submitModel(model, mini, pose, model.renderType(state.skin.body().texturePath()), light, overlay, EntityRenderState.NO_OUTLINE);
		pose.popPose();
	}
}
