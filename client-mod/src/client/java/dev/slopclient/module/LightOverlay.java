package dev.slopclient.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import dev.slopclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Light level on the ground around the player: red where monsters can always spawn,
 * yellow where they can at night, green where light keeps them away. Uses the spawn
 * rules of the current dimension like vanilla does for normal monsters (zombie,
 * skeleton, creeper, spider). Drawn with depth test, so nothing shows through walls.
 */
public final class LightOverlay extends Module {
	static final int RED = 0xFB7185, YELLOW = 0xFBBF24, GREEN = 0x4ADE80;
	/** Numbers further away than this are left out (unreadable and costly). */
	private static final double TEXT_DISTANCE = 12;

	public final NumberSetting range = add(new NumberSetting("Reichweite", 16, 4, 32, 1, v -> Math.round(v) + " Blöcke"));
	public final NumberSetting height = add(new NumberSetting("Höhe", 8, 2, 16, 1, v -> "± " + Math.round(v)));
	public final ModeSetting display = add(new ModeSetting("Anzeige", "Farbe + Zahl", "Farbe + Zahl", "Nur Farbe", "Nur Zahl"));
	public final ModeSetting number = add(new ModeSetting("Zahl", "Blocklicht", "Blocklicht", "Himmelslicht", "Beides"));
	public final ModeSetting shape = add(new ModeSetting("Form", "Fläche", "Fläche", "Rahmen", "Kreuz"));
	public final BoolSetting spawnOnly = add(new BoolSetting("Nur Spawn-Plätze", true));
	public final NumberSetting opacity = add(new NumberSetting("Deckkraft", 0.4, 0.1, 1, 0.05, Visuals::percent));

	/** x, y, z, colour, block light, sky light per spot; swapped whole, never changed. */
	private volatile int[] spots = new int[0];
	private BlockPos lastCenter;
	private int ticks;
	private KeyMapping key;

	public LightOverlay() {
		// Slimes, drowned, spawners and biomes without monsters follow their own rules.
		super("Lichtlevel-Overlay", "Zeigt das Lichtlevel auf dem Boden: rot = Monster spawnen immer, gelb = nachts, grün = sicher (normale Monster, keine Schleime oder Spawner)", Category.RENDER, false);
	}

	public void bind(KeyMapping key) {
		this.key = key;
	}

	/** The toggle key works while the module is off, so it is polled from the client tick. */
	public void pollKey(Minecraft mc) {
		while (key != null && key.consumeClick()) {
			if (mc.player == null) continue;
			if (serverLocked()) {
				mc.player.sendOverlayMessage(Component.literal("Lichtlevel-Overlay ist auf diesem Server deaktiviert"));
				continue;
			}
			toggle();
			mc.player.sendOverlayMessage(Component.literal("Lichtlevel-Overlay " + (enabled() ? "an" : "aus")));
		}
	}

	@Override
	public String suffix() {
		return Math.round(range.get()) + "";
	}

	@Override
	protected void onDisable() {
		spots = new int[0];
		lastCenter = null;
	}

	@Override
	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || mc.player.isSpectator()) {
			spots = new int[0];
			return;
		}
		BlockPos center = mc.player.blockPosition();
		// About four times a second, or at once when the player moved to another block.
		if (++ticks < 5 && center.equals(lastCenter)) return;
		ticks = 0;
		lastCenter = center;
		spots = scan(level, center);
	}

	private int[] scan(ClientLevel level, BlockPos center) {
		int r = (int) Math.round(range.get()), h = (int) Math.round(height.get());
		var dim = level.dimensionType();
		int blockLimit = dim.monsterSpawnBlockLightLimit();
		int maxTest = dim.monsterSpawnLightTest().maxInclusive();
		boolean skyLight = dim.hasSkyLight();
		boolean skipSafe = spawnOnly.on();
		int top = Math.min(center.getY() + h, level.getMaxY());
		int bottom = Math.max(center.getY() - h, level.getMinY() + 1);

		int[] out = new int[6 * 256];
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				int x = center.getX() + dx, z = center.getZ() + dz;
				if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
				// Top down: the block below the spot must carry a mob, cheap checks first.
				for (int y = top; y >= bottom; y--) {
					pos.set(x, y - 1, z);
					var ground = level.getBlockState(pos);
					if (ground.isAir() || !ground.getFluidState().isEmpty()) continue;
					pos.set(x, y, z);
					if (!level.getBlockState(pos).getFluidState().isEmpty()) continue;
					if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, EntityTypes.ZOMBIE)) continue;
					int block = level.getBrightness(LightLayer.BLOCK, pos);
					int sky = skyLight ? level.getBrightness(LightLayer.SKY, pos) : 0;
					int color = block > blockLimit ? GREEN : Math.max(block, sky) <= maxTest ? RED : YELLOW;
					if (skipSafe && color == GREEN) continue;
					if (n + 6 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
					out[n++] = x;
					out[n++] = y;
					out[n++] = z;
					out[n++] = color;
					out[n++] = block;
					out[n++] = sky;
				}
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Called while the level collects what it draws (LevelRenderEvents.COLLECT_SUBMITS). */
	public void render(LevelRenderContext ctx) {
		if (!active()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden()) return;
		int[] s = spots;
		if (s.length == 0) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack poses = ctx.poseStack();
		var collector = ctx.submitNodeCollector();
		int alpha = (int) Math.round(opacity.get() * 255) << 24;

		if (!display.is("Nur Zahl")) {
			String form = shape.get();
			collector.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, out) -> {
				for (int i = 0; i < s.length; i += 6) {
					float x = (float) (s[i] - cam.x), y = (float) (s[i + 1] + 0.005 - cam.y), z = (float) (s[i + 2] - cam.z);
					int argb = alpha | s[i + 3];
					switch (form) {
						case "Rahmen" -> frame(pose, out, x, y, z, argb);
						case "Kreuz" -> cross(pose, out, x, y, z, argb);
						default -> quad(pose, out, x, z, x + 1, z + 1, y, argb);
					}
				}
			});
		}

		if (!display.is("Nur Farbe")) {
			Font font = mc.font;
			float yaw = ctx.levelState().cameraRenderState.yRot;
			boolean colored = display.is("Nur Zahl");
			double max = TEXT_DISTANCE * TEXT_DISTANCE;
			for (int i = 0; i < s.length; i += 6) {
				double cx = s[i] + 0.5 - cam.x, cy = s[i + 1] + 0.01 - cam.y, cz = s[i + 2] + 0.5 - cam.z;
				if (cx * cx + cy * cy + cz * cz > max) continue;
				String text = switch (number.get()) {
					case "Himmelslicht" -> Integer.toString(s[i + 5]);
					case "Beides" -> s[i + 4] + "/" + s[i + 5];
					default -> Integer.toString(s[i + 4]);
				};
				FormattedCharSequence chars = FormattedCharSequence.forward(text, Style.EMPTY);
				poses.pushPose();
				poses.translate(cx, cy, cz);
				// Flat on the ground, upright for someone looking in the camera's direction.
				poses.rotateDegrees(Axis.YP, 180 - yaw);
				poses.rotateDegrees(Axis.XP, -90);
				float scale = text.length() > 2 ? 0.035f : 0.05f;
				poses.scale(scale, -scale, scale);
				int color = 0xFF000000 | (colored ? s[i + 3] : 0xFFFFFF);
				collector.submitText(poses, -font.width(chars) / 2f, -4, chars, false, Font.DisplayMode.NORMAL,
					LightCoordsUtil.FULL_BRIGHT, color, 0, 0);
				poses.popPose();
			}
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, float x0, float z0, float x1, float z1, float y, int argb) {
		out.addVertex(pose, x0, y, z0).setColor(argb);
		out.addVertex(pose, x0, y, z1).setColor(argb);
		out.addVertex(pose, x1, y, z1).setColor(argb);
		out.addVertex(pose, x1, y, z0).setColor(argb);
	}

	private static void frame(PoseStack.Pose pose, VertexConsumer out, float x, float y, float z, int argb) {
		float w = 0.08f;
		quad(pose, out, x, z, x + 1, z + w, y, argb);
		quad(pose, out, x, z + 1 - w, x + 1, z + 1, y, argb);
		quad(pose, out, x, z + w, x + w, z + 1 - w, y, argb);
		quad(pose, out, x + 1 - w, z + w, x + 1, z + 1 - w, y, argb);
	}

	/** Two diagonal strips from corner to corner. */
	private static void cross(PoseStack.Pose pose, VertexConsumer out, float x, float y, float z, int argb) {
		float w = 0.06f;
		out.addVertex(pose, x, y, z + w).setColor(argb);
		out.addVertex(pose, x + 1 - w, y, z + 1).setColor(argb);
		out.addVertex(pose, x + 1, y, z + 1 - w).setColor(argb);
		out.addVertex(pose, x + w, y, z).setColor(argb);

		out.addVertex(pose, x + 1 - w, y, z).setColor(argb);
		out.addVertex(pose, x, y, z + 1 - w).setColor(argb);
		out.addVertex(pose, x + w, y, z + 1).setColor(argb);
		out.addVertex(pose, x + 1, y, z + w).setColor(argb);
	}
}
