package dev.slopclient.cosmetics.karambit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.slopclient.SlopClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First person with the Karambit: vanilla hides the arm whenever an item is held,
 * so the arm is drawn as for an empty hand and the knife is put into its fist.
 *
 * <p>The grip is defined in view space, the way a karambit is really held: the
 * handle runs across the fist with the finger ring on the inner side (left for the
 * right hand), the blade points outwards and its curve bends away from the player.
 * The arm's motion since its rest pose (punch, equip) is applied on top, so the
 * knife moves with the hand.
 */
public final class KarambitHands {
	/** Handle axis and curve side of the knife in item space (the bake tilts the knife by 20°). */
	private static final Vector3f HANDLE_AXIS = new Vector3f((float) Math.cos(Math.toRadians(20)), (float) Math.sin(Math.toRadians(20)), 0);
	private static final Vector3f CURVE_SIDE = new Vector3f((float) -Math.sin(Math.toRadians(20)), (float) Math.cos(Math.toRadians(20)), 0);
	/** Handle centre in item space: the point that sits in the fist. */
	private static final Vector3f HANDLE = new Vector3f(0.479f, 0.30f, 0.5f);

	/**
	 * Tilt of the flat towards the camera (degrees), size, and an offset from the fist
	 * in view space (blocks: right, up, towards the camera).
	 */
	private static final float[] GRIP = {60, 0.8f, 0, -0.03f, 0};
	private static float[] gripOverride;

	/** Inverse of the arm's rotation at rest, taken from the first frame without punch or equip motion. */
	private static final Matrix3f restInverse = new Matrix3f();
	private static boolean haveRest;

	private KarambitHands() {
	}

	public static boolean applies(ItemStack stack) {
		return stack.is(Items.NETHERITE_SWORD) && SlopClient.ready() && SlopClient.modules().karambit.active()
			&& KarambitRenderer.latestMesh() != null;
	}

	/** Development aid for the self-test. */
	public static void overrideGrip(float[] grip) {
		gripOverride = grip;
	}

	/** Knife orientation in view space. */
	private static Matrix3f viewRotation(boolean left, float tilt) {
		// Where the handle axis (ring towards blade), the curve side and the blade normal
		// end up. The arm pose mirrors x, so the blade points right with -x here.
		Vector3f handle = new Vector3f(left ? 1 : -1, 0, 0);
		Vector3f curve = new Vector3f(0, 0, -1);
		Vector3f normal = new Vector3f(handle).cross(curve);
		Matrix3f target = new Matrix3f().setColumn(0, handle).setColumn(1, curve).setColumn(2, normal);
		Vector3f n = new Vector3f(HANDLE_AXIS).cross(CURVE_SIDE);
		Matrix3f source = new Matrix3f().setColumn(0, HANDLE_AXIS).setColumn(1, CURVE_SIDE).setColumn(2, n);
		// Tilt around the view's x axis so the flat of the blade faces the camera a bit.
		return new Matrix3f().rotateX((float) Math.toRadians(tilt)).mul(target.mul(source.transpose()));
	}

	/** Called with the pose the arm was just drawn in. */
	public static void submitInHand(PoseStack pose, SubmitNodeCollector collector, int light, HumanoidArm arm, AvatarRenderState avatar,
		boolean foil, boolean atRest) {
		KarambitMesh mesh = KarambitRenderer.latestMesh();
		var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(avatar);
		if (!(renderer.getModel() instanceof PlayerModel model)) return;
		boolean left = arm == HumanoidArm.LEFT;
		// The fist: same hand offset as the held-item layer in third person.
		(left ? model.leftArm : model.rightArm).translateAndRotate(pose);
		pose.rotateDegrees(Axis.XP, -90);
		pose.rotateDegrees(Axis.YP, 180);
		pose.translate((left ? -1 : 1) / 16f, 0.125f, -0.625f);

		float[] g = gripOverride != null ? gripOverride : GRIP;
		Matrix4f hand = pose.last().pose();
		Vector3f fist = hand.transformPosition(new Vector3f());
		Matrix3f armRotation = hand.get3x3(new Matrix3f());
		// Drop any scale so only the turn remains.
		for (int c = 0; c < 3; c++) armRotation.setColumn(c, armRotation.getColumn(c, new Vector3f()).normalize());
		if (atRest && !haveRest) {
			restInverse.set(armRotation).invert();
			haveRest = true;
		}
		Matrix3f sinceRest = haveRest ? new Matrix3f(armRotation).mul(restInverse) : new Matrix3f();
		Matrix3f rotation = sinceRest.mul(viewRotation(left, g[0]));
		Matrix4f knife = new Matrix4f()
			.translation(fist.x + (left ? -g[2] : g[2]), fist.y + g[3], fist.z + g[4])
			.mul(new Matrix4f().set(rotation))
			.scale(g[1]);
		pose.last().pose().set(knife);
		pose.last().normal().set(rotation);
		pose.translate(-HANDLE.x, -HANDLE.y, -HANDLE.z);

		ItemDisplayContext context = left ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
		KarambitAnimation.animate(pose, context, mesh.pivot);
		var type = foil ? RenderTypes.entitySolidGlint(KarambitMesh.TEXTURE) : RenderTypes.entityCutout(KarambitMesh.TEXTURE);
		collector.submitCustomGeometry(pose, type, (p, out) -> mesh.emit(p, out, light, OverlayTexture.NO_OVERLAY));
	}
}
