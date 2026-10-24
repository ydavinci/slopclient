package dev.slopclient.cosmetics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where walking pets are: each one follows its player to a spot behind and beside
 * them, stands on the ground below that spot and turns the way it walks. Purely
 * visual and client side; the pet has no collision and never touches the world.
 */
public final class PetMotion {
	/** Spot relative to the player: right of and behind them (blocks). */
	private static final double SIDE = 0.9, BEHIND = -0.9;

	static final class State {
		double x, y, z, px, py, pz;
		float yaw, pyaw, walk, pwalk, speed;
		boolean fresh = true;
	}

	private static final Map<Integer, State> states = new HashMap<>();

	private PetMotion() {
	}

	static State of(int entityId) {
		return states.get(entityId);
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			states.clear();
			return;
		}
		Set<Integer> seen = new HashSet<>();
		for (AbstractClientPlayer player : mc.level.players()) {
			boolean self = player == mc.player;
			Pets.Pet pet = Extras.petOf(player, self);
			if (pet == null || pet.tier() != Pets.Tier.WALKING) continue;
			seen.add(player.getId());
			step(mc, states.computeIfAbsent(player.getId(), id -> new State()), player);
		}
		states.keySet().retainAll(seen);
	}

	private static void step(Minecraft mc, State s, AbstractClientPlayer player) {
		float bodyYaw = player.yBodyRot;
		double rad = Math.toRadians(bodyYaw);
		// Minecraft: yaw 0 looks to +z, 90 to -x.
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		double rx = -fz, rz = fx;
		double tx = player.getX() + rx * SIDE + fx * BEHIND, tz = player.getZ() + rz * SIDE + fz * BEHIND;

		s.px = s.x;
		s.py = s.y;
		s.pz = s.z;
		s.pyaw = s.yaw;
		s.pwalk = s.walk;

		double dx = tx - s.x, dz = tz - s.z, dist = Math.sqrt(dx * dx + dz * dz);
		if (s.fresh || dist > 12) {
			s.x = s.px = tx;
			s.z = s.pz = tz;
			s.y = s.py = player.getY();
			s.yaw = s.pyaw = bodyYaw;
			s.fresh = false;
			return;
		}
		double move = 0;
		if (dist > 0.15) {
			// Faster when far behind, so it keeps up with sprinting.
			move = Math.min(dist, Math.min(0.55, 0.05 + dist * 0.22));
			s.x += dx / dist * move;
			s.z += dz / dist * move;
			float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
			s.yaw += Mth.wrapDegrees(target - s.yaw) * 0.35f;
		} else {
			s.yaw += Mth.wrapDegrees(bodyYaw - s.yaw) * 0.08f;
		}
		s.y = ground(mc, s.x, player.getY(), s.z, s.y);
		s.speed += ((float) Math.min(1, move / 0.18) - s.speed) * 0.4f;
		s.walk += (float) move * 6;
	}

	/** Top of the blocks below (x, z), searched from a little above the player down to 4 blocks below. */
	private static double ground(Minecraft mc, double x, double playerY, double z, double current) {
		Vec3 from = new Vec3(x, playerY + 1, z), to = new Vec3(x, playerY - 4, z);
		var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		double target = hit.getType() == HitResult.Type.MISS ? playerY : hit.getLocation().y;
		// Steps down smoothly, steps up at once (no sinking into stairs).
		return target > current ? target : current + (target - current) * 0.5;
	}
}
