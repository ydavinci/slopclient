package dev.slopclient;

import dev.slopclient.gui.ClickGuiScreen;
import dev.slopclient.gui.SlopTitleScreen;
import dev.slopclient.gui.HudEditorScreen;
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
 * Development aid, only active with `-Dslopclient.selftest=true`: walks through the
 * title screen, ClickGUI and HUD editor, then creates a flat test world and looks
 * at the player from behind (cape), saving a screenshot of each step to
 * `screenshots/` before quitting. With the netherite sword in hand it then shows the
 * Karambit in first person, mid-inspect, from the front and in the inventory.
 */
final class SelfTest {
	private enum Step { TITLE, CLICKGUI, HUD_EDITOR, WORLD_LOADING, CAPE, KARAMBIT, GRIP_SWEEP, INSPECT, KARAMBIT_FRONT, KARAMBIT_GUI, MODULES, MODULES_FRONT, INFO_HUD, PETS, CAPE_SIDE, ELYTRA, ELYTRA_SIDE, SKY, SKY_UP, TRAIL, TRAIL_GOLD, GLASS_FRONT, GLASS_BACK, DONE }

	private static Step step;
	private static int wait;
	private static SlopTitleScreen title;
	/**
	 * Candidate hand grips (x, y, z degrees, then x, y, z pixels) photographed with the
	 * arm visible when -Dslopclient.gripsweep=true.
	 */
	private static final float[][] GRIPS = {
		{35, 0.7f, 0, -0.03f, 0}, {50, 0.7f, 0, -0.03f, 0}, {65, 0.7f, 0, -0.03f, 0}, {50, 0.85f, 0, -0.03f, 0},
		{50, 0.7f, 0, -0.06f, 0}, {50, 0.7f, 0.04f, -0.03f, 0}, {50, 0.7f, 0, -0.03f, -0.05f}, {-50, 0.7f, 0, -0.03f, 0},
	};
	private static int grip;
	private static boolean infoShot;
	/** Pets photographed in the PETS step: id and camera (front or back). */
	private static final String[][] PET_SHOTS = {
		{"igel", "front"}, {"minime", "front"}, {"katze", "back"}, {"drache", "front"}, {"biene", "back"}, {"fuchs", "back"}, {"panda", "front"},
	};
	private static int petShot = -1;

	private SelfTest() {
	}

	static boolean enabled() {
		return Boolean.getBoolean("slopclient.selftest");
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "slopclient-selftest-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
			msg -> SlopClient.LOG.info("Self-test: {}", msg.getString()));
	}

	static void tick(Minecraft mc) {
		if (step == null) {
			if (!(mc.gui.screen() instanceof SlopTitleScreen screen)) return;
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
				SlopClient.modules().freelook.setServerLocked(true);
				next(Step.CLICKGUI, 30);
			}
			case CLICKGUI -> {
				shot(mc, "2-clickgui");
				SlopClient.modules().freelook.setServerLocked(false);
				mc.gui.setScreen(new HudEditorScreen(title));
				next(Step.HUD_EDITOR, 30);
			}
			case HUD_EDITOR -> {
				shot(mc, "3-hud-editor");
				// A fresh flat creative world, so the player (and the cape) can be seen.
				var settings = new LevelSettings("SlopClient Selbsttest", GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel("slopclient-selftest-" + System.currentTimeMillis(), settings,
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
				// -Dslopclient.selftest.focus=sky: only the skybox and trail shots.
				if ("sky".equals(System.getProperty("slopclient.selftest.focus"))) {
					SlopClient.modules().skybox.setEnabled(true);
					mc.options.setCameraType(CameraType.FIRST_PERSON);
					mc.player.setXRot(-8);
					next(Step.SKY, 100);
					return;
				}
				// -Dslopclient.selftest.focus=glass: only the glass figure.
				if ("glass".equals(System.getProperty("slopclient.selftest.focus"))) {
					SlopClient.modules().glass.setEnabled(true);
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
					mc.player.setXRot(5);
					next(Step.GLASS_FRONT, 100);
					return;
				}
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
				if (Boolean.getBoolean("slopclient.gripsweep")) {
					grip = 0;
					setGrip();
					next(Step.GRIP_SWEEP, 10);
					return;
				}
				dev.slopclient.cosmetics.karambit.KarambitAnimation.startInspect();
				next(Step.INSPECT, 22); // halfway through the double spin
			}
			case GRIP_SWEEP -> {
				float[] g = GRIPS[grip];
				shot(mc, "grip-" + String.format("%02d", grip) + "_tilt" + (int) g[0] + "_size" + (int) (g[1] * 100) + "_x" + (int) (g[2] * 100) + "_y" + (int) (g[3] * 100));
				if (++grip < GRIPS.length) {
					setGrip();
					next(Step.GRIP_SWEEP, 10);
					return;
				}
				dev.slopclient.cosmetics.karambit.KarambitHands.overrideGrip(null);
				dev.slopclient.cosmetics.karambit.KarambitAnimation.startInspect();
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
				var m = SlopClient.modules();
				for (var module : java.util.List.of(m.blockOutline, m.hitColor, m.lowFire, m.nametags, m.chat, m.scoreboard)) module.setEnabled(true);
				m.blockOutline.color.set("Chroma");
				var c = mc.player.connection;
				c.sendCommand("scoreboard objectives add slopclient dummy \"SlopClient\"");
				c.sendCommand("scoreboard objectives setdisplay sidebar slopclient");
				c.sendCommand("scoreboard players set DevPlayer slopclient 42");
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
				// M13 HUD: every new element on, with something to count and a boss bar.
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(0);
				for (var hud : SlopClient.modules().hud()) {
					if (hud.getClass().getEnclosingClass() == dev.slopclient.hud.InfoHud.class) hud.setEnabled(true);
				}
				SlopClient.modules().bossBar.fy = 0.12;
				var c = mc.player.connection;
				c.sendCommand("give @s arrow 37");
				c.sendCommand("give @s totem_of_undying 2");
				c.sendCommand("give @s ender_pearl 16");
				c.sendCommand("give @s golden_apple 5");
				c.sendCommand("bossbar add slopclient:test \"Selbsttest-Boss\"");
				c.sendCommand("bossbar set slopclient:test players @a");
				c.sendCommand("bossbar set slopclient:test value 60");
				next(Step.INFO_HUD, 40);
			}
			case INFO_HUD -> {
				// The pearl is in the inventory now: give it a cooldown to show.
				if (!infoShot) for (int i = 0; i < 9; i++) {
					var stack = mc.player.getInventory().getItem(i);
					if (stack.is(net.minecraft.world.item.Items.ENDER_PEARL)) mc.player.getCooldowns().addCooldown(stack, 200);
				}
				if (wait == 0 && !infoShot) {
					infoShot = true;
					wait = 25;
					return;
				}
				shot(mc, "11-info-hud");
				next(Step.PETS, 5);
			}
			case PETS -> {
				// One shot per pet after it had time to settle (walking pets follow first).
				if (petShot >= 0) shot(mc, "12-pet-" + PET_SHOTS[petShot][0]);
				petShot++;
				if (petShot >= PET_SHOTS.length) {
					// The cape at an angle: its sides carry on the image.
					System.setProperty("slopclient.pet", "");
					mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
					mc.player.setXRot(10);
					mc.player.setYBodyRot(mc.player.getYRot() + 50);
					next(Step.CAPE_SIDE, 15);
					return;
				}
				System.setProperty("slopclient.pet", PET_SHOTS[petShot][0]);
				System.setProperty("slopclient.hat", "");
				mc.options.setCameraType(PET_SHOTS[petShot][1].equals("front") ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(15);
				next(Step.PETS, 40);
			}
			case CAPE_SIDE -> {
				shot(mc, "13-cape-side");
				// The cape on elytra: wings, not two slabs.
				mc.player.connection.sendCommand("item replace entity @s armor.chest with elytra");
				mc.player.setYBodyRot(mc.player.getYRot());
				next(Step.ELYTRA, 30);
			}
			case ELYTRA -> {
				shot(mc, "14-elytra-back");
				mc.player.setYBodyRot(mc.player.getYRot() + 50);
				next(Step.ELYTRA_SIDE, 15);
			}
			case ELYTRA_SIDE -> {
				shot(mc, "15-elytra-side");
				next(Step.DONE, 10);
			}
			case SKY -> {
				shot(mc, "16-skybox");
				mc.player.setXRot(-55);
				next(Step.SKY_UP, 10);
			}
			case SKY_UP -> {
				shot(mc, "17-skybox-up");
				// Strafe past the camera so the trail streams sideways through the picture.
				var trail = SlopClient.modules().trail;
				trail.color.set("Chroma");
				trail.setEnabled(true);
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(12);
				mc.options.keyLeft.setDown(true);
				next(Step.TRAIL, 30);
			}
			case TRAIL -> {
				shot(mc, "18-trail-chroma");
				SlopClient.modules().trail.color.set("Gold");
				mc.options.keyLeft.setDown(false);
				mc.options.keyRight.setDown(true);
				next(Step.TRAIL_GOLD, 30);
			}
			case GLASS_FRONT -> {
				shot(mc, "20-glass-front");
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				next(Step.GLASS_BACK, 15);
			}
			case GLASS_BACK -> {
				shot(mc, "21-glass-back");
				next(Step.DONE, 10);
			}
			case TRAIL_GOLD -> {
				shot(mc, "19-trail-gold");
				mc.options.keyRight.setDown(false);
				next(Step.DONE, 10);
			}
			case DONE -> {
				SlopClient.LOG.info("Self-test finished");
				mc.stop();
			}
		}
	}

	private static void setGrip() {
		float[] g = GRIPS[grip];
		dev.slopclient.cosmetics.karambit.KarambitHands.overrideGrip(GRIPS[grip]);
	}

	private static void next(Step s, int ticks) {
		step = s;
		wait = ticks;
	}
}
