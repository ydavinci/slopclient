package dev.daclient.cosmetics.karambit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;

/**
 * First-person Karambit animations in the style of CS:GO, in item model space
 * (X along the knife, Y up, Z through the blade). Rotations around Z turn the knife
 * in its own plane around the finger ring, like the spin tricks in the game.
 *
 * <ul>
 *   <li><b>Draw</b> when the knife comes into the main hand: it swings up from below
 *       while spinning once around the ring.</li>
 *   <li><b>Inspect</b> on the inspect key: turn the flat side to the camera, spin
 *       twice around the ring, flip to the other side, return.</li>
 * </ul>
 * Timing uses the wall clock so the motion stays smooth at any frame rate.
 */
public final class KarambitAnimation {
	private static final float DRAW_SECONDS = 0.6f;
	/** Keyframes of the inspect: time, yaw (degrees), spin (degrees), lift. */
	private static final float[][] INSPECT = {
		{0.00f, 0, 0, 0},
		{0.40f, -55, 0, 0.10f},
		{1.55f, -55, 720, 0.12f},
		{2.05f, 50, 720, 0.12f},
		{2.40f, 50, 720, 0.10f},
		{2.85f, 0, 720, 0},
	};
	private static final float INSPECT_SECONDS = INSPECT[INSPECT.length - 1][0];

	/**
	 * Reverse grip of a real karambit: turns the knife (which the item transforms hold
	 * blade-up like a sword) around the handle centre. Applied in every hand context.
	 */
	private static final org.joml.Quaternionf GRIP = new org.joml.Quaternionf().rotationXYZ(
		(float) Math.toRadians(180), (float) Math.toRadians(-30), (float) Math.toRadians(45));
	/** Handle centre in item space (where the bake put the grip). */
	private static final Vector3f HANDLE = new Vector3f(0.479f, 0.30f, 0.5f);
	private static org.joml.Quaternionf gripOverride;
	private static Vector3f gripShiftOverride;
	/** Moves the knife up in first person so the whole blade stays in view. */
	private static final Vector3f GRIP_SHIFT = new Vector3f(0, 0.25f, 0);

	private static long drawStart = -1;
	private static long inspectStart = -1;

	private KarambitAnimation() {
	}

	public static void startDraw() {
		drawStart = System.nanoTime();
		inspectStart = -1;
	}

	public static void startInspect() {
		// Pressing again mid-inspect does not restart it, like in CS.
		if (inspectStart < 0 && seconds(drawStart) >= DRAW_SECONDS) inspectStart = System.nanoTime();
	}

	public static void stop() {
		drawStart = -1;
		inspectStart = -1;
	}

	public static void cancelInspect() {
		inspectStart = -1;
	}

	public static boolean inspecting() {
		return inspectStart >= 0 && seconds(inspectStart) < INSPECT_SECONDS;
	}

	private static float seconds(long start) {
		return start < 0 ? Float.MAX_VALUE : (System.nanoTime() - start) / 1e9f;
	}

	/** Development aid for the self-test: tries another grip. */
	public static void overrideGrip(org.joml.Quaternionf q, Vector3f shift) {
		gripOverride = q;
		gripShiftOverride = shift;
	}

	/** Grip in the hands, then the running animation if this is the main hand in first person. */
	public static void apply(PoseStack pose, ItemDisplayContext context, Vector3f pivot) {
		if (!context.firstPerson() && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return;
		org.joml.Quaternionf grip = gripOverride != null ? gripOverride : GRIP;
		Vector3f shift = gripShiftOverride != null ? gripShiftOverride : GRIP_SHIFT;
		if (context.firstPerson()) pose.translate(shift.x, shift.y, shift.z);
		pose.translate(HANDLE.x, HANDLE.y, HANDLE.z);
		pose.rotate(grip);
		pose.translate(-HANDLE.x, -HANDLE.y, -HANDLE.z);
		if (!context.firstPerson() || context != mainHandContext()) return;
		float yaw = 0, spin = 0, lift = 0;

		float d = seconds(drawStart);
		if (d < DRAW_SECONDS) {
			float k = 1 - easeOutCubic(d / DRAW_SECONDS);
			spin -= 360 * k;
			lift -= 0.45f * k;
		}

		float s = seconds(inspectStart);
		if (s < INSPECT_SECONDS) {
			for (int i = 1; i < INSPECT.length; i++) {
				float[] a = INSPECT[i - 1], b = INSPECT[i];
				if (s > b[0]) continue;
				float t = smooth((s - a[0]) / (b[0] - a[0]));
				yaw += lerp(a[1], b[1], t);
				spin += lerp(a[2], b[2], t);
				lift += lerp(a[3], b[3], t);
				break;
			}
		} else if (inspectStart >= 0) {
			inspectStart = -1;
		}

		if (yaw == 0 && spin == 0 && lift == 0) return;
		pose.translate(0, lift, 0);
		pose.translate(pivot.x, pivot.y, pivot.z);
		if (yaw != 0) pose.rotateDegrees(Axis.YP, yaw);
		if (spin != 0) pose.rotateDegrees(Axis.ZP, spin);
		pose.translate(-pivot.x, -pivot.y, -pivot.z);
	}

	private static ItemDisplayContext mainHandContext() {
		HumanoidArm arm = Minecraft.getInstance().options.mainHand().get();
		return arm == HumanoidArm.LEFT ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
	}

	private static float easeOutCubic(float t) {
		float u = 1 - Math.min(1, Math.max(0, t));
		return 1 - u * u * u;
	}

	private static float smooth(float t) {
		t = Math.min(1, Math.max(0, t));
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
