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
 * `screenshots/` before quitting.
 */
final class SelfTest {
	private enum Step { TITLE, CLICKGUI, HUD_EDITOR, WORLD_LOADING, CAPE, DONE }

	private static Step step;
	private static int wait;
	private static DaTitleScreen title;

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
				next(Step.DONE, 10);
			}
			case DONE -> {
				DaClient.LOG.info("Self-test finished");
				mc.stop();
			}
		}
	}

	private static void next(Step s, int ticks) {
		step = s;
		wait = ticks;
	}
}
