package dev.daclient;

import dev.daclient.gui.ClickGuiScreen;
import dev.daclient.gui.DaTitleScreen;
import dev.daclient.gui.HudEditorScreen;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development aid, only active with `-Ddaclient.selftest=true`: walks through the
 * title screen, ClickGUI and HUD editor, then creates a flat test world and looks
 * at the player from behind (cape), saving a screenshot of each step to
 * `screenshots/` before quitting. With the netherite sword in hand it then shows the
 * Karambit in first person, mid-inspect, from the front and in the inventory.
 */
final class SelfTest {
	private enum Step { TITLE, CLICKGUI, HUD_EDITOR, WORLD_LOADING, CAPE, KARAMBIT, GRIP_SWEEP, INSPECT, KARAMBIT_FRONT, KARAMBIT_GUI, MODULES, MODULES_FRONT, DONE }

	private static Step step;
	private static int wait;
	private static DaTitleScreen title;
	/** Candidate grips (x, y, z degrees, upward shift) photographed when -Ddaclient.gripsweep=true. */
	private static final float[][] GRIPS = {
		{180, 0, 30, 0.2f}, {180, 0, 45, 0.25f}, {180, 0, 60, 0.3f}, {180, 30, 30, 0.2f},
		{180, -30, 30, 0.2f}, {180, 0, 30, 0.35f}, {180, -30, 45, 0.25f}, {180, 30, 45, 0.25f},
		{180, 0, 15, 0.15f}, {150, 0, 30, 0.2f}, {210, 0, 30, 0.2f}, {180, -45, 30, 0.25f},
	};
	private static int grip;

	private SelfTest() {
	}

	static boolean enabled() {
		return Boolean.getBoolean("daclient.selftest");
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "daclient-selftest-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
			msg -> DaClient.LOG.info("Self-test: {}", msg.getString()));
	}

	static void tick(Minecraft mc) {
		if (step == null) {
			if (!(mc.gui.screen() instanceof DaTitleScreen screen)) return;
			title = screen;
			step = Step.TITLE;
			wait = 70; // let the intro animation finish
			return;
		}
		if (--wait > 0) return;
		switch (step) {
			case TITLE -> {
				shot(mc, "1-title");
				ClickGuiScreen gui = new ClickGuiScreen(title);
				mc.gui.setScreen(gui);
				gui.expand("Keystrokes");
				gui.expand("Capes");
				// Shows how a module switched off by a server looks.
				DaClient.modules().freelook.setServerLocked(true);
				next(Step.CLICKGUI, 30);
			}
			case CLICKGUI -> {
				shot(mc, "2-clickgui");
				DaClient.modules().freelook.setServerLocked(false);
				mc.gui.setScreen(new HudEditorScreen(title));
				next(Step.HUD_EDITOR, 30);
			}
			case HUD_EDITOR -> {
				shot(mc, "3-hud-editor");
				// A fresh flat creative world, so the player (and the cape) can be seen.
				var settings = new LevelSettings("DaClient Selbsttest", GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel("daclient-selftest-" + System.currentTimeMillis(), settings,
					WorldOptions.defaultWithRandomSeed(), WorldPresets::createTestWorldDimensions, title);
				next(Step.WORLD_LOADING, 20);
			}
			case WORLD_LOADING -> {
				if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
					wait = 20;
					return;
				}
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(10);
				next(Step.CAPE, 100); // chunks and the cape texture settle
			}
			case CAPE -> {
				shot(mc, "4-cape");
				mc.player.connection.sendCommand("give @s netherite_sword");
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(0);
				next(Step.KARAMBIT, 40); // the draw animation finishes
			}
			case KARAMBIT -> {
				shot(mc, "5-karambit");
				if (Boolean.getBoolean("daclient.gripsweep")) {
					grip = 0;
					setGrip();
					next(Step.GRIP_SWEEP, 10);
					return;
				}
				dev.daclient.cosmetics.karambit.KarambitAnimation.startInspect();
				next(Step.INSPECT, 22); // halfway through the double spin
			}
			case GRIP_SWEEP -> {
				float[] g = GRIPS[grip];
				shot(mc, "grip-" + String.format("%02d", grip) + "_" + (int) g[0] + "_" + (int) g[1] + "_" + (int) g[2] + "_" + (int) (g[3] * 100));
				if (++grip < GRIPS.length) {
					setGrip();
					next(Step.GRIP_SWEEP, 10);
					return;
				}
				dev.daclient.cosmetics.karambit.KarambitAnimation.overrideGrip(null, null);
				dev.daclient.cosmetics.karambit.KarambitAnimation.startInspect();
				next(Step.INSPECT, 22);
			}
			case INSPECT -> {
				shot(mc, "6-karambit-inspect");
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				next(Step.KARAMBIT_FRONT, 50);
			}
			case KARAMBIT_FRONT -> {
				shot(mc, "7-karambit-front");
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
				next(Step.KARAMBIT_GUI, 20);
			}
			case KARAMBIT_GUI -> {
				shot(mc, "8-karambit-gui");
				mc.gui.setScreen(null);
				// M12 modules: outline, own nametag, chat stacking, scoreboard.
				var m = DaClient.modules();
				for (var module : java.util.List.of(m.blockOutline, m.hitColor, m.lowFire, m.nametags, m.chat, m.scoreboard)) module.setEnabled(true);
				m.blockOutline.color.set("Chroma");
				var c = mc.player.connection;
				c.sendCommand("scoreboard objectives add daclient dummy \"DaClient\"");
				c.sendCommand("scoreboard objectives setdisplay sidebar daclient");
				c.sendCommand("scoreboard players set DevPlayer daclient 42");
				for (int i = 0; i < 3; i++) c.sendCommand("say Hallo aus dem Selbsttest");
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(70); // look at the ground: block outline
				next(Step.MODULES, 40);
			}
			case MODULES -> {
				shot(mc, "9-modules");
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(10);
				next(Step.MODULES_FRONT, 30);
			}
			case MODULES_FRONT -> {
				shot(mc, "10-nametag");
				next(Step.DONE, 10);
			}
			case DONE -> {
				DaClient.LOG.info("Self-test finished");
				mc.stop();
			}
		}
	}

	private static void setGrip() {
		float[] g = GRIPS[grip];
		dev.daclient.cosmetics.karambit.KarambitAnimation.overrideGrip(new org.joml.Quaternionf().rotationXYZ(
			(float) Math.toRadians(g[0]), (float) Math.toRadians(g[1]), (float) Math.toRadians(g[2])), new org.joml.Vector3f(0, g[3], 0));
	}

	private static void next(Step s, int ticks) {
		step = s;
		wait = ticks;
	}
}
