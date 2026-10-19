package dev.daclient;

import dev.daclient.gui.ClickGuiScreen;
import dev.daclient.gui.DaTitleScreen;
import dev.daclient.gui.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development aid, only active with `-Ddaclient.selftest=true`: walks through the
 * title screen, ClickGUI and HUD editor, saves a screenshot of each to
 * `screenshots/` and quits. Lets the menus be checked without clicking through them.
 */
final class SelfTest {
	private static final String[] STEPS = {"title", "clickgui", "hud-editor"};
	private static int step = -1;
	private static int wait;
	private static DaTitleScreen title;

	private SelfTest() {
	}

	static boolean enabled() {
		return Boolean.getBoolean("daclient.selftest");
	}

	static void tick(Minecraft mc) {
		if (step < 0) {
			if (!(mc.gui.screen() instanceof DaTitleScreen screen)) return;
			title = screen;
			step = 0;
			wait = 70; // let the intro animation finish
			return;
		}
		if (--wait > 0) return;
		if (step < STEPS.length) {
			String name = "daclient-selftest-" + (step + 1) + "-" + STEPS[step] + ".png";
			Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> DaClient.LOG.info("Self-test: {}", msg.getString()));
			step++;
			switch (step) {
				case 1 -> {
					ClickGuiScreen gui = new ClickGuiScreen(title);
					mc.gui.setScreen(gui);
					gui.expand("Keystrokes");
					gui.expand("Zoom");
					// Shows how a module switched off by a server looks.
					DaClient.modules().freelook.setServerLocked(true);
				}
				case 2 -> {
					DaClient.modules().freelook.setServerLocked(false);
					mc.gui.setScreen(new HudEditorScreen(title));
				}
				default -> {
				}
			}
			wait = 30;
			return;
		}
		DaClient.LOG.info("Self-test finished");
		mc.stop();
	}
}
